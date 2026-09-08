package com.lightbrowser.ui.music

import android.content.ComponentName
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.MoreExecutors
import com.lightbrowser.PlayerService
import com.lightbrowser.data.AppCtx
import com.lightbrowser.data.LibraryScanner
import com.lightbrowser.data.Novel
import com.lightbrowser.data.Prefs
import com.lightbrowser.data.formatDuration
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class PlayerUiState(
    val isPlaying: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val chapterIndex: Int = -1,
    val novelIndex: Int = -1,
    val shuffle: Boolean = false,
    val repeat: Int = 0, // 0 off, 1 all, 2 one
    val speed: Float = 1f,
    val sleepMinutesLeft: Int? = null,
    val ready: Boolean = false
)

class MusicViewModel : ViewModel() {

    private val _novels = MutableStateFlow<List<Novel>>(emptyList())
    val novels: StateFlow<List<Novel>> = _novels.asStateFlow()

    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning.asStateFlow()

    private val _player = MutableStateFlow(PlayerUiState())
    val player: StateFlow<PlayerUiState> = _player.asStateFlow()

    private var controller: MediaController? = null
    private var pollJob: Job? = null
    private var sleepJob: Job? = null
    private var resumeTried = false

    val ctx get() = AppCtx.ctx

    fun connect() {
        if (controller != null) return
        try {
            val app = ctx
            val token = SessionToken(app, ComponentName(app, PlayerService::class.java))
            val future = MediaController.Builder(app, token).buildAsync()
            future.addListener(
                {
                    try {
                        controller = future.get()
                        attach(controller!!)
                    } catch (e: Exception) {
                        _player.update { it.copy(error = "Player unavailable: ${e.message}") }
                    }
                },
                MoreExecutors.directExecutor()
            )
        } catch (e: Exception) {
            _player.update { it.copy(error = "Player unavailable") }
        }
    }

    private fun attach(c: MediaController) {
        try {
            c.shuffleModeEnabled = Prefs.playerShuffle
            c.repeatMode = when (Prefs.playerRepeat) {
                2 -> Player.REPEAT_MODE_ONE
                1 -> Player.REPEAT_MODE_ALL
                else -> Player.REPEAT_MODE_OFF
            }
            c.setPlaybackSpeed(Prefs.playerSpeed.coerceIn(0.25f, 3f))
        } catch (_: Exception) {}
        c.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                _player.update { it.copy(isPlaying = isPlaying, error = null) }
                if (isPlaying) startPolling() else { stopPolling(); saveResumePoint() }
            }
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                _player.update { it.copy(chapterIndex = c.currentMediaItemIndex, positionMs = 0) }
            }
            override fun onPlaybackStateChanged(state: Int) {
                _player.update {
                    it.copy(
                        ready = state == Player.STATE_READY,
                        error = if (state == Player.STATE_IDLE && it.chapterIndex >= 0) it.error else it.error
                    )
                }
                // Post-prepare duration was 0 (polling only while playing) — poll once when ready.
                if (state == Player.STATE_READY) {
                    viewModelScope.launch {
                        try {
                            _player.update {
                                it.copy(
                                    positionMs = c.currentPosition.coerceAtLeast(0),
                                    durationMs = c.duration.coerceAtLeast(0).takeIf { d -> d != androidx.media3.common.C.TIME_UNSET } ?: 0L
                                )
                            }
                        } catch (_: Exception) {}
                    }
                }
            }
            override fun onPlayerError(error: PlaybackException) {
                _player.update { it.copy(error = error.message ?: "Playback error (${error.errorCode})", isPlaying = false) }
            }
        })
        _player.update {
            it.copy(
                shuffle = c.shuffleModeEnabled,
                repeat = when (c.repeatMode) {
                    Player.REPEAT_MODE_ONE -> 2
                    Player.REPEAT_MODE_ALL -> 1
                    else -> 0
                },
                speed = c.playbackParameters.speed
            )
        }
        if (c.isPlaying) startPolling()
    }

    private fun startPolling() {
        if (pollJob?.isActive == true) return
        pollJob = viewModelScope.launch {
            var ticks = 0
            while (isActive) {
                val c = controller
                if (c == null) break
                try {
                    _player.update {
                        it.copy(
                            positionMs = c.currentPosition.coerceAtLeast(0),
                            durationMs = c.duration.coerceAtLeast(0).takeIf { d -> d != androidx.media3.common.C.TIME_UNSET } ?: 0L,
                            chapterIndex = c.currentMediaItemIndex,
                            isPlaying = c.isPlaying
                        )
                    }
                    // Resume point + listen stats, saved every ~5s while playing.
                    if (c.isPlaying && ++ticks % 10 == 0) {
                        saveResumePoint()
                        bumpListenStats(5)
                    }
                } catch (_: Exception) {}
                delay(500)
            }
        }
    }

    private fun stopPolling() {
        pollJob?.cancel()
        pollJob = null
        val c = controller
        if (c != null) {
            try {
                _player.update {
                    it.copy(
                        positionMs = c.currentPosition.coerceAtLeast(0),
                        durationMs = c.duration.coerceAtLeast(0).takeIf { d -> d != androidx.media3.common.C.TIME_UNSET } ?: 0L
                    )
                }
            } catch (_: Exception) {}
        }
    }

    fun scanTree(treeUri: Uri) {
        _scanning.value = true
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val app = AppCtx.ctx
                val doc = DocumentFile.fromTreeUri(app, treeUri)
                val list = if (doc != null) LibraryScanner.scanRoot(doc, app) else emptyList()
                withContext(Dispatchers.Main) {
                    _novels.value = list
                    _scanning.value = false
                    if (!resumeTried) {
                        resumeTried = true
                        tryRestoreResume()
                    }
                }
            } catch (_: Exception) {
                withContext(Dispatchers.Main) { _scanning.value = false }
            }
        }
    }

    fun scanSandboxFolder(dir: File) {
        _scanning.value = true
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val app = AppCtx.ctx
                val doc = DocumentFile.fromFile(dir)
                val list = if (doc != null) LibraryScanner.scanRoot(doc, app) else emptyList()
                withContext(Dispatchers.Main) {
                    _novels.value = list
                    _scanning.value = false
                    if (!resumeTried) {
                        resumeTried = true
                        tryRestoreResume()
                    }
                }
            } catch (_: Exception) {
                withContext(Dispatchers.Main) { _scanning.value = false }
            }
        }
    }

    fun sandboxDirs(): List<File> {
        return try {
            val sd = File(ctx.filesDir, "sandbox")
            sd.listFiles()?.filter { it.isDirectory }?.toList() ?: emptyList()
        } catch (_: Exception) { emptyList() }
    }

    fun selectNovel(index: Int) {
        val novels = _novels.value
        if (index !in novels.indices) return
        val c = controller ?: return
        try {
            val novel = novels[index]
            val items = novel.chapters.map { ch ->
                MediaItem.Builder()
                    .setUri(ch.uri)
                    .setMediaMetadata(
                        androidx.media3.common.MediaMetadata.Builder()
                            .setTitle(ch.title)
                            .setArtist(novel.name)
                            .setArtworkUri(novel.coverUri)
                            .build()
                    )
                    .build()
            }
            c.setMediaItems(items)
            c.prepare()
            _player.update { it.copy(novelIndex = index, chapterIndex = -1) }
        } catch (_: Exception) {}
    }

    fun playChapter(index: Int) {
        try {
            controller?.seekToDefaultPosition(index)
            controller?.play()
        } catch (_: Exception) {}
    }

    fun toggle() {
        val c = controller ?: return
        try {
            if (c.isPlaying) c.pause()
            else {
                if (c.mediaItemCount > 0) c.play()
            }
        } catch (_: Exception) {}
    }

    fun next() {
        try {
            val c = controller ?: return
            // Respect shuffle order (was seekToDefaultPosition(0) ignoring shuffle).
            if (c.hasNextMediaItem()) c.seekToNextMediaItem() else c.seekToDefaultPosition(0)
        } catch (_: Exception) {}
    }

    fun prev() {
        try {
            val c = controller ?: return
            if (c.currentPosition > 5000) c.seekTo(0)
            else if (c.hasPreviousMediaItem()) c.seekToPreviousMediaItem()
        } catch (_: Exception) {}
    }

    fun seekTo(ms: Long) {
        try { controller?.seekTo(ms.coerceAtLeast(0)) } catch (_: Exception) {}
    }

    fun seekRelative(deltaMs: Long) {
        val c = controller ?: return
        try {
            val dur = c.duration.takeIf { it != androidx.media3.common.C.TIME_UNSET } ?: Long.MAX_VALUE
            c.seekTo((c.currentPosition + deltaMs).coerceIn(0, dur))
        } catch (_: Exception) {}
    }

    fun toggleShuffle() {
        val c = controller ?: return
        try {
            c.shuffleModeEnabled = !c.shuffleModeEnabled
            Prefs.playerShuffle = c.shuffleModeEnabled
            _player.update { it.copy(shuffle = c.shuffleModeEnabled) }
        } catch (_: Exception) {}
    }

    fun cycleRepeat() {
        val c = controller ?: return
        try {
            val next = (_player.value.repeat + 1) % 3
            c.repeatMode = when (next) {
                2 -> Player.REPEAT_MODE_ONE
                1 -> Player.REPEAT_MODE_ALL
                else -> Player.REPEAT_MODE_OFF
            }
            Prefs.playerRepeat = next
            _player.update { it.copy(repeat = next) }
        } catch (_: Exception) {}
    }

    fun cycleSpeed() {
        val c = controller ?: return
        try {
            // Include 0.5 to match Settings slider range (was diverged).
            val speeds = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f)
            val cur = _player.value.speed
            val next = speeds.firstOrNull { it > cur + 0.01f } ?: 0.5f
            c.setPlaybackSpeed(next)
            Prefs.playerSpeed = next
            _player.update { it.copy(speed = next) }
        } catch (_: Exception) {}
    }

    fun setSleepTimer(minutes: Int?) {
        sleepJob?.cancel()
        sleepJob = null
        _player.update { it.copy(sleepMinutesLeft = minutes) }
        if (minutes != null && minutes > 0) {
            sleepJob = viewModelScope.launch {
                var left = minutes
                while (left > 0) {
                    delay(60_000)
                    left--
                    _player.update { it.copy(sleepMinutesLeft = left.takeIf { l -> l > 0 }) }
                }
                try { controller?.pause() } catch (_: Exception) {}
                _player.update { it.copy(sleepMinutesLeft = null) }
            }
        }
    }

    fun currentNovel(): Novel? = _novels.value.getOrNull(_player.value.novelIndex)

    private fun saveResumePoint() {
        try {
            val n = currentNovel() ?: return
            val ch = n.chapters.getOrNull(_player.value.chapterIndex) ?: return
            val pos = _player.value.positionMs
            if (pos < 10_000) return // too early to be a meaningful resume point
            Prefs.lastNovel = n.name
            Prefs.lastChapterUri = ch.uri.toString()
            Prefs.lastPosition = pos
        } catch (_: Exception) {}
    }

    private fun bumpListenStats(seconds: Long) {
        try {
            val today = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())
            if (Prefs.listenDate != today) {
                Prefs.listenDate = today
                Prefs.listenSeconds = 0
            }
            Prefs.listenSeconds = Prefs.listenSeconds + seconds
        } catch (_: Exception) {}
    }

    fun todayListened(): String {
        return try {
            val today = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())
            if (Prefs.listenDate != today) return ""
            val s = Prefs.listenSeconds
            if (s < 60) return ""
            "Listened ${s / 60}m today"
        } catch (_: Exception) { "" }
    }

    /** After a scan, jump back to the saved novel/chapter/position when files match. */
    fun tryRestoreResume(): Boolean {
        return try {
            val uri = Prefs.lastChapterUri
            if (uri.isBlank()) return false
            _novels.value.forEachIndexed { ni, novel ->
                val ci = novel.chapters.indexOfFirst { it.uri.toString() == uri }
                if (ci >= 0 && controller != null) {
                    selectNovel(ni)
                    val pos = Prefs.lastPosition
                    // Atomic: set items + seek + prepare together, wait for READY (was fixed delays).
                    viewModelScope.launch {
                        try {
                            selectNovel(ni)
                            playChapter(ci)
                            if (pos > 10_000) {
                                var tries = 0
                                while (tries++ < 20) {
                                    kotlinx.coroutines.delay(250)
                                    try {
                                        val c = controller ?: break
                                        if (c.playbackState == Player.STATE_READY) { c.seekTo(pos); break }
                                    } catch (_: Exception) { break }
                                }
                            }
                        } catch (_: Exception) {}
                    }
                    return true
                }
            }
            false
        } catch (_: Exception) { false }
    }

    fun chapterTitle(): String {
        val n = currentNovel() ?: return "Select a novel"
        val i = _player.value.chapterIndex
        if (i !in n.chapters.indices) return "${n.chapters.size} chapters"
        return "${i + 1}. ${n.chapters[i].title}"
    }

    fun progressText(): String {
        val n = currentNovel() ?: return ""
        return "${formatDuration(_player.value.positionMs)} • ${formatDuration(n.totalMs)}"
    }

    override fun onCleared() {
        try { controller?.release() } catch (_: Exception) {}
        controller = null
    }
}
