package com.lightbrowser.ui

import android.content.Intent
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.PopupMenu
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.documentfile.provider.DocumentFile
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.menu.MaterialMenuInflater
import com.lightbrowser.R
import com.lightbrowser.databinding.FragmentMusicBinding
import java.io.File
import java.util.Comparator

class MusicPlayerFragment : Fragment() {

    private var _b: FragmentMusicBinding? = null
    private val b get() = _b!!

    private var player: MediaPlayer? = null
    private var chapters: List<Chapter> = emptyList()
    private var currentChapterIndex = -1
    private val handler = Handler(Looper.getMainLooper())
    private var isSeeking = false

    // Audiobook library structure
    private var libraryUri: Uri? = null
    private var novels: List<Novel> = emptyList()
    private var currentNovelIndex = -1

    private var folderPicker: ActivityResultLauncher<Uri?>? = null

    data class Novel(
        val name: String,
        val directory: DocumentFile,
        val cover: DocumentFile?,
        val chapters: List<Chapter>
    )

    data class Chapter(
        val title: String,
        val file: DocumentFile,
        val index: Int,
        val novel: Novel
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            folderPicker = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
                if (uri != null) {
                    try {
                        requireContext().contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        libraryUri = uri
                        scanLibrary(uri)
                    } catch (e: Exception) { safeToast(e.message) }
                }
            }
        } catch (_: Exception) {}
    }

    private fun safeToast(m: String?) { try { Toast.makeText(requireContext(), m ?: "error", Toast.LENGTH_SHORT).show() } catch (_: Exception) {} }

    private val updateRunnable = object : Runnable {
        override fun run() {
            try {
                val bb = _b
                val p = player
                if (bb != null && p != null && try { p.isPlaying } catch (_: Exception) { false } && !isSeeking) {
                    val pos = try { p.currentPosition } catch (_: Exception) { 0 }
                    val dur = try { p.duration } catch (_: Exception) { 0 }
                    try { bb.seekBar.max = if (dur > 0) dur else 100 } catch (_: Exception) {}
                    try { bb.seekBar.progress = pos } catch (_: Exception) {}
                    try { bb.tvCurrent.text = fmt(pos) } catch (_: Exception) {}
                    try { bb.tvDuration.text = fmt(dur) } catch (_: Exception) {}
                }
                handler.postDelayed(this, 500)
            } catch (_: Exception) { try { handler.postDelayed(this, 500) } catch (_: Exception) {} }
        }
    }

    override fun onCreateView(inflater: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        return try {
            _b = FragmentMusicBinding.inflate(inflater, c, false)
            b.root
        } catch (e: Exception) {
            Log.e("Audiobook", "onCreateView", e)
            TextView(requireContext()).apply { text = "Audiobook unavailable: ${e.message}" }
        }
    }

    override fun onViewCreated(v: View, s: Bundle?) {
        try {
            val bb = _b ?: return
            try { bb.recycler.layoutManager = LinearLayoutManager(requireContext()) } catch (e: Exception) { Log.e("Audiobook", "layoutManager", e) }
            try { bb.btnPickLibrary.setOnClickListener { try { folderPicker?.launch(null) ?: safeToast("Folder picker unavailable") } catch (e: Exception) { safeToast(e.message) } } } catch (_: Exception) {}
            try { bb.btnSwitchNovel.setOnClickListener { try { showNovelSwitcher() } catch (e: Exception) { safeToast(e.message) } } } catch (_: Exception) {}
            try { bb.btnStop.setOnClickListener { try { stop() } catch (_: Exception) {} } } catch (_: Exception) {}
            try { bb.btnOverflow.setOnClickListener { try { showOverflowMenu() } catch (_: Exception) {} } } catch (_: Exception) {}
            try { bb.btnPlay.setOnClickListener { try { toggle() } catch (_: Exception) {} } } catch (_: Exception) {}
            try { bb.btnPrev.setOnClickListener { try { prevChapter() } catch (_: Exception) {} } } catch (_: Exception) {}
            try { bb.btnNext.setOnClickListener { try { nextChapter() } catch (_: Exception) {} } } catch (_: Exception) {}
            try { bb.btnSeekBack.setOnClickListener { try { seekRelative(-10000) } catch (_: Exception) {} } } catch (_: Exception) {}
            try { bb.btnSeekForward.setOnClickListener { try { seekRelative(10000) } catch (_: Exception) {} } } catch (_: Exception) {}
            try {
                bb.seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) { if (fromUser) try { bb.tvCurrent.text = fmt(p) } catch (_: Exception) {} }
                    override fun onStartTrackingTouch(sb: SeekBar?) { isSeeking = true }
                    override fun onStopTrackingTouch(sb: SeekBar?) { isSeeking = false; try { player?.seekTo(sb?.progress ?: 0) } catch (_: Exception) {} }
                })
            } catch (_: Exception) {}
            try { handler.post(updateRunnable) } catch (_: Exception) {}
            // Load persisted library URI if available
            loadPersistedLibrary()
        } catch (e: Exception) {
            Log.e("Audiobook", "onViewCreated crash", e)
            safeToast("Audiobook error: ${e.message}")
        }
    }

    private fun loadPersistedLibrary() {
        // Could load from SharedPreferences if needed
    }

    private fun scanLibrary(treeUri: Uri) {
        try {
            val ctx = requireContext()
            val docTree = DocumentFile.fromTreeUri(ctx, treeUri) ?: return
            val novelList = mutableListOf<Novel>()

            docTree.listFiles()?.forEach { novelDir ->
                if (novelDir.isDirectory) {
                    val novelName = novelDir.name ?: "Unknown Novel"
                    val chapterList = mutableListOf<Chapter>()
                    var coverFile: DocumentFile? = null

                    novelDir.listFiles()?.forEach { file ->
                        if (file.isFile) {
                            val name = file.name?.lowercase() ?: ""
                            if (name.endsWith(".mp3") || name.endsWith(".m4a") || name.endsWith(".opus") || name.endsWith(".ogg")) {
                                val chapterTitle = file.nameWithoutExtension
                                val index = extractChapterNumber(chapterTitle)
                                chapterList.add(Chapter(chapterTitle, file, index, null!!)) // novel will be set after
                            } else if (name.endsWith(".jpg") || name.endsWith(".jpeg") || name.endsWith(".png") || name.endsWith(".webp")) {
                                if (coverFile == null) coverFile = file
                            }
                        }
                    }

                    // Sort chapters numerically
                    chapterList.sortWith(Comparator.compareBy<Chapter> { it.index }.thenBy { it.title.lowercase() })
                    
                    // Update novel reference in chapters
                    val novel = Novel(novelName, novelDir, coverFile, chapterList.map { it.copy(novel = Novel(novelName, novelDir, coverFile, emptyList())) })
                    novelList.add(novel)
                }
            }

            // Sort novels by name
            novelList.sortBy { it.name.lowercase() }

            novels = novelList
            if (novels.isNotEmpty() && currentNovelIndex == -1) {
                selectNovel(0)
            } else {
                updateNovelList()
            }
            safeToast("Found ${novels.size} novels in library")
        } catch (e: Exception) {
            Log.e("Audiobook", "scanLibrary", e)
            safeToast("Scan failed: ${e.message}")
        }
    }

    private fun extractChapterNumber(title: String): Int {
        // Extract leading number for sorting (e.g., "001 Chapter One" -> 1, "Chapter 5" -> 5)
        val pattern = "\\d+".toRegex()
        val match = pattern.find(title)
        return match?.value?.toIntOrNull() ?: Int.MAX_VALUE
    }

    private fun selectNovel(index: Int) {
        if (index < 0 || index >= novels.size) return
        currentNovelIndex = index
        val novel = novels[index]
        chapters = novel.chapters
        currentChapterIndex = -1
        updateNovelUI(novel)
        updateChapterList()
        safeToast("Loaded: ${novel.name} (${chapters.size} chapters)")
    }

    private fun updateNovelUI(novel: Novel) {
        val bb = _b ?: return
        try { bb.tvNovelTitle.text = novel.name } catch (_: Exception) {}
        try { bb.tvChapterTitle.text = "Select a chapter" } catch (_: Exception) {}
        // Load cover image
        novel.cover?.let { cover ->
            try {
                val input = requireContext().contentResolver.openInputStream(cover.uri)
                input?.use { stream ->
                    val bitmap = android.graphics.BitmapFactory.decodeStream(stream)
                    bb.imgCover.setImageBitmap(bitmap)
                    bb.imgCover.visibility = View.VISIBLE
                }
            } catch (_: Exception) { try { bb.imgCover.visibility = View.GONE } catch (_: Exception) {} }
        } ?: run { try { bb.imgCover.visibility = View.GONE } catch (_: Exception) {} }
    }

    private fun updateNovelList() {
        // Could show a list of novels in a dialog
    }

    private fun updateChapterList() {
        val bb = _b ?: return
        try { bb.empty.visibility = if (chapters.isEmpty()) View.VISIBLE else View.GONE } catch (_: Exception) {}
        try { bb.recycler.visibility = if (chapters.isEmpty()) View.GONE else View.VISIBLE } catch (_: Exception) {}
        try {
            bb.recycler.adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
                override fun onCreateViewHolder(p: ViewGroup, t: Int) =
                    object : RecyclerView.ViewHolder(LayoutInflater.from(p.context).inflate(R.layout.item_download, p, false)) {}
                override fun onBindViewHolder(h: RecyclerView.ViewHolder, i: Int) {
                    try {
                        val ch = chapters[i]
                        val title = h.itemView.findViewById<TextView>(R.id.tvTitle)
                        val status = h.itemView.findViewById<TextView>(R.id.tvStatus)
                        val icon = h.itemView.findViewById<ImageView>(R.id.ivFileIcon)
                        icon.setImageResource(R.drawable.ic_audio)
                        icon.contentDescription = "Audio"
                        title.text = "${ch.index}. ${ch.title}"
                        status.text = "Chapter ${ch.index} • Tap to play"
                        h.itemView.alpha = if (i == currentChapterIndex) 1f else 0.85f
                        h.itemView.setOnClickListener { try { playChapter(i) } catch (_: Exception) {} }
                    } catch (_: Exception) {}
                }
                override fun getItemCount() = chapters.size
            }
        } catch (_: Exception) {}
    }

    private fun playChapter(idx: Int) {
        if (idx < 0 || idx >= chapters.size) return
        try {
            stopPlayer()
            currentChapterIndex = idx
            val ch = chapters[idx]
            val ctx = requireContext()
            player = MediaPlayer().apply {
                try { setDataSource(ctx, ch.file.uri) } catch (e: Exception) { safeToast("Play failed: ${e.message}"); return@apply }
                setOnPreparedListener {
                    try { start() } catch (_: Exception) {}
                    bb?.let { bb ->
                        try { bb.btnPlay.text = "⏸" } catch (_: Exception) {}
                        try { bb.tvChapterTitle.text = "${ch.index}. ${ch.title}" } catch (_: Exception) {}
                        try { bb.tvDuration.text = fmt(duration) } catch (_: Exception) {}
                    }
                }
                setOnCompletionListener { try { nextChapter() } catch (_: Exception) {} }
                setOnErrorListener { _, what, extra -> safeToast("Error $what/$extra"); true }
                try { prepareAsync() } catch (e: Exception) { safeToast(e.message) }
            }
            try { bb?.recycler?.adapter?.notifyDataSetChanged() } catch (_: Exception) {}
        } catch (e: Exception) { safeToast("Play failed: ${e.message}") }
    }

    private fun toggle() {
        try {
            val p = player
            if (p == null) {
                if (chapters.isNotEmpty()) playChapter(0) else safeToast("Select a novel first")
                return
            }
            if (try { p.isPlaying } catch (_: Exception) { false }) { try { p.pause() } catch (_: Exception) {}; try { _b?.btnPlay?.text = "▶" } catch (_: Exception) {} }
            else { try { p.start() } catch (_: Exception) {}; try { _b?.btnPlay?.text = "⏸" } catch (_: Exception) {} }
        } catch (_: Exception) {}
    }

    private fun prevChapter() { if (chapters.isEmpty()) return; try { playChapter(if (currentChapterIndex <= 0) chapters.size - 1 else currentChapterIndex - 1) } catch (_: Exception) {} }
    private fun nextChapter() { if (chapters.isEmpty()) return; try { playChapter(if (currentChapterIndex >= chapters.size - 1) 0 else currentChapterIndex + 1) } catch (_: Exception) {} }

    private fun stop() {
        try { stopPlayer() } catch (_: Exception) {}
        try { _b?.btnPlay?.text = "▶" } catch (_: Exception) {}
        try { _b?.tvChapterTitle?.text = "Stopped" } catch (_: Exception) {}
        try { _b?.seekBar?.progress = 0 } catch (_: Exception) {}
        try { _b?.tvCurrent?.text = "0:00" } catch (_: Exception) {}
        currentChapterIndex = -1
        try { _b?.recycler?.adapter?.notifyDataSetChanged() } catch (_: Exception) {}
    }

    private fun showOverflowMenu() {
        try {
            val bb = _b ?: return
            val ctx = requireContext()
            val popup = PopupMenu(ctx, bb.btnOverflow)
            MaterialMenuInflater(ctx).inflate(R.menu.audiobook_menu, popup.menu)
            popup.setOnMenuItemClickListener { item: MenuItem ->
                try {
                    when (item.itemId) {
                        R.id.menu_pick_library -> folderPicker?.launch(null)
                        R.id.menu_switch_novel -> showNovelSwitcher()
                        R.id.menu_seek_back -> seekRelative(-10000)
                        R.id.menu_seek_forward -> seekRelative(10000)
                        R.id.menu_scan -> libraryUri?.let { scanLibrary(it) }
                        R.id.menu_stop -> stop()
                    }
                } catch (_: Exception) {}
                true
            }
            popup.show()
        } catch (e: Exception) { Log.e("Audiobook", "overflow menu", e) }
    }

    private fun seekRelative(ms: Int) {
        try {
            val p = player ?: return
            val newPos = (p.currentPosition + ms).coerceIn(0, p.duration)
            p.seekTo(newPos)
        } catch (_: Exception) {}
    }

    private fun showNovelSwitcher() {
        if (novels.isEmpty()) { safeToast("No novels loaded"); return }
        val items = novels.map { it.name }.toTypedArray()
        android.app.AlertDialog.Builder(requireContext())
            .setTitle("Switch Novel")
            .setSingleChoiceItems(items, currentNovelIndex) { _, which ->
                selectNovel(which)
                it.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun stopPlayer() { try { player?.stop(); player?.release() } catch (_: Exception) {}; player = null }
    private fun fmt(ms: Int): String { if (ms <= 0) return "0:00"; val s = ms / 1000; return String.format("%d:%02d", s / 60, s % 60) }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        try { if (hidden && try { player?.isPlaying == true } catch (_: Exception) { false }) { try { player?.pause() } catch (_: Exception) {}; try { _b?.btnPlay?.text = "▶" } catch (_: Exception) {} } } catch (_: Exception) {}
    }
    override fun onDestroyView() { try { handler.removeCallbacks(updateRunnable) } catch (_: Exception) {}; try { stopPlayer() } catch (_: Exception) {}; _b = null; super.onDestroyView() }
}