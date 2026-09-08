package com.lightbrowser.ui.music

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Equalizer
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.RepeatOn
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import coil.compose.AsyncImage
import com.lightbrowser.data.Novel
import com.lightbrowser.data.formatDuration
import com.lightbrowser.ui.theme.LargeIncreasedShape
import com.lightbrowser.ui.theme.PebbleShape

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MusicScreen(
    modifier: Modifier = Modifier,
    vm: MusicViewModel
) {
    val ctx = LocalContext.current
    val novels by vm.novels.collectAsState()
    val scanning by vm.scanning.collectAsState()
    val pl by vm.player.collectAsState()

    var showHero by remember { mutableStateOf(false) }
    var showPickFolder by remember { mutableStateOf(false) }
    var showChapters by remember { mutableStateOf(false) }
    var showSleep by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { vm.connect() }

    // Notification permission for background playback (API 33+)
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33) {
            try {
                if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                    notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            } catch (_: Exception) {}
        }
    }

    val treePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            try {
                ctx.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: Exception) {}
            vm.scanTree(uri)
        }
    }

    AnimatedContent(targetState = showHero && pl.novelIndex != -1, label = "player-morph") { hero ->
        if (!hero) {
            // ── Novel library ──
            Column(modifier = modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Audiobooks", style = MaterialTheme.typography.headlineSmall)
                        val listened = remember(novels) { vm.todayListened() }
                        if (listened.isNotEmpty()) {
                            Text(listened, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    AssistChip(onClick = { showPickFolder = true }, label = { Text("Library") }, leadingIcon = { Icon(Icons.Filled.FolderOpen, null) })
                    Spacer(Modifier.width(8.dp))
                    AssistChip(onClick = { treePicker.launch(null) }, label = { Text("Browse") }, leadingIcon = { Icon(Icons.Filled.Add, null) })
                }
                if (scanning) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                if (novels.isEmpty() && !scanning) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) {
                            Icon(Icons.Filled.LibraryMusic, null, modifier = Modifier.size(64.dp), tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.height(12.dp))
                            Text("No audiobooks yet", style = MaterialTheme.typography.titleMedium)
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "Pick a Library folder from Sandbox, or Browse storage.\nStructure: Novel/audio/*.mp3 + cover.jpg",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(novels, key = { it.name + "@" + it.chapters.firstOrNull()?.uri.toString() }) { novel ->
                            NovelCard(
                                novel = novel,
                                playing = novels.indexOf(novel) == pl.novelIndex,
                                onClick = {
                                    val idx = novels.indexOf(novel)
                                    vm.selectNovel(idx)
                                    // Autoplay first chapter (was paused at -1, dead play).
                                    try { vm.playChapter(0) } catch (_: Exception) {}
                                    showHero = true
                                }
                            )
                        }
                    }
                }
            }
        } else {
            val novel = vm.currentNovel()
            if (novel != null) {
                HeroPlayer(
                    modifier = modifier,
                    vm = vm,
                    novel = novel,
                    onBack = { showHero = false },
                    onChapters = { showChapters = true },
                    onSleep = { showSleep = true }
                )
            } else {
                // Stale novelIndex after rescan (was blank screen) — fall back to library.
                Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Library changed — pick again", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(8.dp))
                        TextButton(onClick = { showHero = false }) { Text("Back to library") }
                    }
                }
            }
        }
    }

    if (showPickFolder) {
        val dirs = remember { try { kotlinx.coroutines.runBlocking(kotlinx.coroutines.Dispatchers.IO) { vm.sandboxDirs() } } catch (_: Exception) { emptyList() } }
        AlertDialog(
            onDismissRequest = { showPickFolder = false },
            title = { Text("Library folder (Sandbox)") },
            text = {
                if (dirs.isEmpty()) Text("No folders in Sandbox yet — use Files → Import first.")
                else LazyColumn {
                    items(dirs, key = { it.absolutePath }) { d ->
                        ListItem(
                            headlineContent = { Text(d.name) },
                            modifier = Modifier.clickable {
                                showPickFolder = false
                                vm.scanSandboxFolder(d)
                            }
                        )
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showPickFolder = false }) { Text("Cancel") } }
        )
    }

    if (showChapters && pl.novelIndex != -1) {
        val novel = vm.currentNovel()
        if (novel != null) {
            ModalBottomSheet(
                onDismissRequest = { showChapters = false },
                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
            ) {
                Text("Chapters", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
                LazyColumn(modifier = Modifier.padding(bottom = 24.dp)) {
                    items(novel.chapters.size) { i ->
                        val ch = novel.chapters[i]
                        ListItem(
                            headlineContent = { Text(ch.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            supportingContent = { Text(formatDuration(ch.durationMs)) },
                            leadingContent = {
                                if (i == pl.chapterIndex) Icon(Icons.Filled.Equalizer, null, tint = MaterialTheme.colorScheme.primary)
                                else Text("%03d".format(ch.index), style = MaterialTheme.typography.labelMedium)
                            },
                            modifier = Modifier.clickable {
                                vm.playChapter(i)
                                showChapters = false
                            },
                            colors = androidx.compose.material3.ListItemDefaults.colors(
                                containerColor = if (i == pl.chapterIndex) MaterialTheme.colorScheme.primaryContainer
                                else MaterialTheme.colorScheme.surface
                            )
                        )
                    }
                }
            }
        }
    }

    if (showSleep) {
        AlertDialog(
            onDismissRequest = { showSleep = false },
            title = { Text("Sleep timer") },
            text = {
                Column {
                    listOf(null to "Off", 15 to "15 min", 30 to "30 min", 45 to "45 min", 60 to "1 hour").forEach { (m, label) ->
                        ListItem(
                            headlineContent = { Text(label) },
                            trailingContent = {
                                if (pl.sleepMinutesLeft == m && m != null) Text("●", color = MaterialTheme.colorScheme.primary)
                            },
                            modifier = Modifier.clickable {
                                vm.setSleepTimer(m)
                                showSleep = false
                            }
                        )
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showSleep = false }) { Text("Close") } }
        )
    }
}

@Composable
private fun NovelCard(novel: Novel, playing: Boolean, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        shape = LargeIncreasedShape,
        colors = CardDefaults.cardColors(
            containerColor = if (playing) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surfaceContainerLow
        )
    ) {
        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            AsyncImage(
                model = novel.coverUri,
                contentDescription = null,
                modifier = Modifier.size(64.dp).clip(RoundedCornerShape(16.dp)),
                contentScale = ContentScale.Crop
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(novel.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "${novel.chapters.size} chapters • ${formatDuration(novel.totalMs)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (playing) Icon(Icons.Filled.Equalizer, null, tint = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun HeroPlayer(
    modifier: Modifier = Modifier,
    vm: MusicViewModel,
    novel: Novel,
    onBack: () -> Unit,
    onChapters: () -> Unit,
    onSleep: () -> Unit
) {
    val pl by vm.player.collectAsState()
    var scrub by remember { mutableFloatStateOf(-1f) }
    var pressed by remember { mutableStateOf(false) }

    // Pebble breathing: artwork gently morphs while playing
    val breathe = rememberInfiniteTransition(label = "breathe")
    val breathScale by breathe.animateFloat(
        initialValue = 1f, targetValue = if (pl.isPlaying) 1.045f else 1f,
        animationSpec = infiniteRepeatable(animation = tween(4000), repeatMode = RepeatMode.Reverse),
        label = "breath"
    )
    val corner by animateDpAsState(
        targetValue = if (pl.isPlaying) 64.dp else 48.dp,
        animationSpec = spring(stiffness = Spring.StiffnessLow),
        label = "pebble"
    )
    val playScale by animateFloatAsState(
        targetValue = if (pressed) 0.85f else 1f,
        animationSpec = spring(stiffness = Spring.StiffnessLow),
        label = "squish"
    )

    Column(
        modifier = modifier.fillMaxSize().padding(horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Filled.QueueMusic, "Library") }
            Spacer(Modifier.weight(1f))
            if (pl.sleepMinutesLeft != null) {
                AssistChip(onClick = onSleep, label = { Text("${pl.sleepMinutesLeft}m") })
            } else {
                IconButton(onClick = onSleep) { Icon(Icons.Filled.Timer, "Sleep timer") }
            }
        }

        Spacer(Modifier.height(8.dp))
        AsyncImage(
            model = novel.coverUri,
            contentDescription = null,
            modifier = Modifier
                .size(280.dp)
                .scale(breathScale)
                .clip(RoundedCornerShape(corner)),
            contentScale = ContentScale.Crop
        )
        Spacer(Modifier.height(20.dp))
        Text(novel.name, style = MaterialTheme.typography.headlineSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(
            vm.chapterTitle(),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1, overflow = TextOverflow.Ellipsis
        )
        Text(
            vm.progressText(),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp)
        )

        Spacer(Modifier.height(8.dp))
        val dur = pl.durationMs.coerceAtLeast(1)
        Slider(
            value = (if (scrub >= 0) scrub else pl.positionMs.toFloat()).coerceIn(0f, dur.toFloat()),
            onValueChange = { scrub = it },
            onValueChangeFinished = {
                if (scrub >= 0) vm.seekTo(scrub.toLong())
                scrub = -1f
            },
            valueRange = 0f..dur.toFloat(),
            modifier = Modifier.fillMaxWidth()
        )
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
            Text(formatDuration(if (scrub >= 0) scrub.toLong() else pl.positionMs), style = MaterialTheme.typography.labelSmall)
            Spacer(Modifier.weight(1f))
            Text(formatDuration(pl.durationMs), style = MaterialTheme.typography.labelSmall)
        }

        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center, modifier = Modifier.fillMaxWidth()) {
            IconButton(onClick = vm::toggleShuffle) {
                Icon(Icons.Filled.Shuffle, "Shuffle", tint = if (pl.shuffle) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.width(8.dp))
            FilledTonalIconButton(onClick = vm::prev, modifier = Modifier.size(56.dp)) {
                Icon(Icons.Filled.SkipPrevious, "Previous", modifier = Modifier.size(28.dp))
            }
            Spacer(Modifier.width(16.dp))
            FilledIconButton(
                onClick = { pressed = true; vm.toggle(); pressed = false },
                modifier = Modifier.size(72.dp).scale(playScale),
                shape = CircleShape
            ) {
                Icon(
                    if (pl.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    if (pl.isPlaying) "Pause" else "Play",
                    modifier = Modifier.size(36.dp)
                )
            }
            Spacer(Modifier.width(16.dp))
            FilledTonalIconButton(onClick = vm::next, modifier = Modifier.size(56.dp)) {
                Icon(Icons.Filled.SkipNext, "Next", modifier = Modifier.size(28.dp))
            }
            Spacer(Modifier.width(8.dp))
            IconButton(onClick = vm::cycleRepeat) {
                Icon(
                    when (pl.repeat) {
                        2 -> Icons.Filled.RepeatOne
                        1 -> Icons.Filled.RepeatOn
                        else -> Icons.Filled.Repeat
                    },
                    "Repeat",
                    tint = if (pl.repeat != 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AssistChip(onClick = { vm.seekRelative(-10_000) }, label = { Text("-10s") })
            AssistChip(onClick = { vm.seekRelative(30_000) }, label = { Text("+30s") })
            AssistChip(onClick = vm::cycleSpeed, label = { Text("${pl.speed}x") }, leadingIcon = { Icon(Icons.Filled.Speed, null) })
        }
        Spacer(Modifier.height(8.dp))
        AssistChip(onClick = onChapters, label = { Text("Chapters (${novel.chapters.size})") })
    }
}

@Composable
fun MiniPlayer(
    modifier: Modifier = Modifier,
    vm: MusicViewModel,
    onExpand: () -> Unit
) {
    val pl by vm.player.collectAsState()
    if (pl.novelIndex == -1) return
    val novel = vm.currentNovel() ?: return
    Surface(
        modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        shape = LargeIncreasedShape,
        color = MaterialTheme.colorScheme.secondaryContainer,
        tonalElevation = 3.dp,
        onClick = onExpand
    ) {
        Column {
            Row(modifier = Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                AsyncImage(
                    model = novel.coverUri,
                    contentDescription = null,
                    modifier = Modifier.size(48.dp).clip(RoundedCornerShape(12.dp)),
                    contentScale = ContentScale.Crop
                )
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(novel.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(vm.chapterTitle(), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (!pl.ready && !pl.isPlaying && pl.positionMs == 0L) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                } else {
                    IconButton(onClick = vm::toggle) {
                        Icon(if (pl.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow, if (pl.isPlaying) "Pause" else "Play")
                    }
                }
            }
            val dur = pl.durationMs.coerceAtLeast(1)
            LinearProgressIndicator(
                progress = { (pl.positionMs.coerceIn(0, dur)) / dur.toFloat() },
                modifier = Modifier.fillMaxWidth().height(3.dp)
            )
        }
    }
}

private fun repeatIcon(repeat: Int): ImageVector = when (repeat) {
    2 -> Icons.Filled.RepeatOne
    1 -> Icons.Filled.RepeatOn
    else -> Icons.Filled.Repeat
}
