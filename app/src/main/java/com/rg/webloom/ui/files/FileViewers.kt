package com.rg.webloom.ui.files

import android.graphics.BitmapFactory
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import coil.compose.AsyncImage
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * In-app file viewer/editor for the Sandbox tab (ported pattern from
 * Quark Files' Viewers.kt, but lightweight: NO Sora/Sketch/ZoomImage deps).
 *
 * - Text/code: editable OutlinedTextField (monospace, line count, save).
 *   500 KB cap keeps big logs from pinning the heap.
 * - Markdown: edit + simple preview toggle.
 * - Image: Coil AsyncImage + pinch-zoom (transform gestures, no new lib).
 * - Video: framework VideoView (no media3-ui dep). Audio: ExoPlayer headless
 *   (media3-exoplayer is already a dep) + play/pause + seek slider.
 * - PDF: framework PdfRenderer, one bitmap per page, recycled on dispose.
 */
sealed interface ViewerKind {
    data object Text : ViewerKind
    data object Image : ViewerKind
    data object Video : ViewerKind
    data object Audio : ViewerKind
    data object Pdf : ViewerKind
    data object Other : ViewerKind
}

fun viewerKindFor(f: File): ViewerKind {
    if (f.isDirectory) return ViewerKind.Other
    return when (f.extension.lowercase()) {
        in setOf("txt", "md", "log", "json", "xml", "csv", "kt", "java", "py",
            "js", "ts", "html", "css", "sh", "prop", "ini", "yaml", "yml") -> ViewerKind.Text
        in setOf("jpg", "jpeg", "png", "gif", "webp", "bmp") -> ViewerKind.Image
        in setOf("mp4", "mkv", "webm", "3gp", "mov") -> ViewerKind.Video
        in setOf("mp3", "m4a", "aac", "ogg", "opus", "wav", "flac") -> ViewerKind.Audio
        "pdf" -> ViewerKind.Pdf
        else -> ViewerKind.Other
    }
}

const val VIEWER_TEXT_CAP = 500_000L

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileViewerScreen(file: File, onClose: () -> Unit, onShare: (File) -> Unit) {
    when (viewerKindFor(file)) {
        ViewerKind.Text -> TextEditorScreen(file, onClose)
        ViewerKind.Image -> ImageViewerScreen(file, onClose, onShare)
        ViewerKind.Video -> VideoViewerScreen(file, onClose)
        ViewerKind.Audio -> AudioViewerScreen(file, onClose)
        ViewerKind.Pdf -> PdfViewerScreen(file, onClose)
        ViewerKind.Other -> UnsupportedScreen(file, onClose, onShare)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TextEditorScreen(file: File, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    val snacks = remember { SnackbarHostState() }
    var loaded by remember { mutableStateOf(false) }
    var binary by remember { mutableStateOf(false) }
    var tooBig by remember { mutableStateOf(false) }
    var content by remember { mutableStateOf("") }
    var savedContent by remember { mutableStateOf("") }
    var preview by remember { mutableStateOf(false) }
    val dirty = content != savedContent
    val isMd = file.extension.lowercase() == "md"

    LaunchedEffect(file.absolutePath) {
        withContext(Dispatchers.IO) {
            if (file.length() > VIEWER_TEXT_CAP) {
                tooBig = true
            } else {
                val bytes = runCatching { file.readBytes() }.getOrDefault(ByteArray(0))
                binary = bytes.any { it == 0.toByte() }
                if (!binary) {
                    val t = runCatching { String(bytes, Charsets.UTF_8) }.getOrDefault("")
                    content = t
                    savedContent = t
                }
            }
            loaded = true
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snacks) },
        topBar = {
            TopAppBar(
                title = { Text((if (dirty) "• " else "") + file.name, maxLines = 1) },
                navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = {
                    if (isMd) FilterChip(
                        selected = preview,
                        onClick = { preview = !preview },
                        label = { Text("Preview") }
                    )
                    FilledTonalButton(
                        onClick = {
                            scope.launch(Dispatchers.IO) {
                                val ok = runCatching { file.writeText(content) }.isSuccess
                                withContext(Dispatchers.Main) {
                                    if (ok) savedContent = content
                                    scope.launch { snacks.showSnackbar(if (ok) "Saved" else "Save failed") }
                                }
                            }
                        },
                        enabled = dirty && !binary && !tooBig
                    ) {
                        Icon(Icons.Filled.Save, null, modifier = Modifier.size(18.dp))
                        Text("Save", modifier = Modifier.padding(start = 4.dp))
                    }
                }
            )
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                !loaded -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator()
                        Text("Loading…", style = MaterialTheme.typography.bodySmall)
                    }
                }
                tooBig -> ViewerMessage("File too large to edit (over 500 KB)", file.length())
                binary -> ViewerMessage("Binary file — cannot edit as text", file.length())
                preview && isMd -> MarkdownPreview(
                    content,
                    Modifier.fillMaxSize().padding(12.dp)
                )
                else -> Column(Modifier.fillMaxSize()) {
                    Text(
                        "${content.lines().size} lines • ${content.length} chars",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                    )
                    OutlinedTextField(
                        value = content,
                        onValueChange = { content = it.take(600_000) },
                        modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp).imePadding(),
                        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        singleLine = false,
                        maxLines = Int.MAX_VALUE
                    )
                }
            }
        }
    }
}

@Composable
private fun ViewerMessage(title: String, bytes: Long) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(24.dp)
        ) {
            Icon(Icons.Filled.Description, null, modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.surfaceVariant)
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(FilesViewModel.formatSize(bytes), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun MarkdownPreview(text: String, modifier: Modifier = Modifier) {
    Column(modifier.verticalScroll(rememberScrollState())) {
        var inCode = false
        text.lines().forEach { raw ->
            when {
                raw.startsWith("```") -> { inCode = !inCode }
                inCode -> Text(raw, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                raw.startsWith("### ") -> Text(raw.removePrefix("### "), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                raw.startsWith("## ") -> Text(raw.removePrefix("## "), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                raw.startsWith("# ") -> Text(raw.removePrefix("# "), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                raw.startsWith("- ") || raw.startsWith("* ") -> Text("• " + raw.drop(2), modifier = Modifier.padding(start = 8.dp, top = 2.dp, bottom = 2.dp))
                raw.isBlank() -> Text("")
                else -> Text(raw, modifier = Modifier.padding(vertical = 2.dp))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ImageViewerScreen(file: File, onClose: () -> Unit, onShare: (File) -> Unit) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    val dims = remember(file.absolutePath) {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        runCatching { BitmapFactory.decodeFile(file.absolutePath, o) }
        if (o.outWidth > 0) "${o.outWidth}×${o.outHeight}" else ""
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(file.name, maxLines = 1) },
                navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = { TextButton(onClick = { onShare(file) }) { Text("Share") } }
            )
        }
    ) { padding ->
        Box(
            Modifier.fillMaxSize().padding(padding).background(MaterialTheme.colorScheme.surfaceContainerLowest),
            contentAlignment = Alignment.Center
        ) {
            AsyncImage(
                model = file,
                contentDescription = file.name,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize()
                    .pointerInput(Unit) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            scale = (scale * zoom).coerceIn(1f, 6f)
                            offset = if (scale <= 1f) Offset.Zero else offset + pan
                        }
                    }
                    .graphicsLayer(scaleX = scale, scaleY = scale, translationX = offset.x, translationY = offset.y)
            )
            Text(
                "$dims • ${FilesViewModel.formatSize(file.length())}",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.align(Alignment.BottomCenter).padding(12.dp)
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.7f), MaterialTheme.shapes.medium)
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VideoViewerScreen(file: File, onClose: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(file.name, maxLines = 1) },
                navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } }
            )
        }
    ) { padding ->
        // Framework VideoView: no media3-ui dep, tiny RAM (surface playback).
        AndroidView(
            factory = { ctx ->
                android.widget.VideoView(ctx).apply {
                    setVideoURI(Uri.fromFile(file))
                    setOnPreparedListener { it.isLooping = false; start() }
                }
            },
            modifier = Modifier.fillMaxSize().padding(padding)
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AudioViewerScreen(file: File, onClose: () -> Unit) {
    val context = LocalContext.current
    // Headless ExoPlayer (media3-exoplayer is already a dep; media3-ui is not
    // needed for audio). Released on dispose — no service, no leak.
    val player = remember(file.absolutePath) {
        androidx.media3.exoplayer.ExoPlayer.Builder(context).build().apply {
            setMediaItem(androidx.media3.common.MediaItem.fromUri(Uri.fromFile(file)))
            prepare()
        }
    }
    DisposableEffect(file.absolutePath) {
        onDispose { runCatching { player.release() } }
    }
    var playing by remember { mutableStateOf(false) }
    var duration by remember { mutableLongStateOf(1L) }
    var position by remember { mutableLongStateOf(0L) }
    LaunchedEffect(player) {
        while (true) {
            duration = runCatching { player.duration.coerceAtLeast(1L) }.getOrDefault(1L)
            position = runCatching { player.currentPosition }.getOrDefault(0L)
            playing = try { player.isPlaying } catch (_: Exception) { false }
            delay(500)
        }
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(file.name, maxLines = 1) },
                navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } }
            )
        }
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            IconButton(
                onClick = { try { if (playing) player.pause() else player.play() } catch (_: Exception) {} },
                modifier = Modifier.size(72.dp)
            ) {
                Icon(
                    if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    if (playing) "Pause" else "Play",
                    modifier = Modifier.size(48.dp)
                )
            }
            Slider(
                value = position.toFloat().coerceIn(0f, duration.toFloat().coerceAtLeast(1f)),
                onValueChange = { position = it.toLong(); runCatching { player.seekTo(it.toLong()) } },
                valueRange = 0f..duration.toFloat().coerceAtLeast(1f),
                modifier = Modifier.fillMaxWidth()
            )
            Text("${position / 1000}s / ${duration / 1000}s", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PdfViewerScreen(file: File, onClose: () -> Unit) {
    var count by remember { mutableIntStateOf(0) }
    var renderer by remember { mutableStateOf<PdfRenderer?>(null) }
    var pfd by remember { mutableStateOf<ParcelFileDescriptor?>(null) }
    LaunchedEffect(file.absolutePath) {
        withContext(Dispatchers.IO) {
            runCatching {
                val fd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                pfd = fd
                val r = PdfRenderer(fd)
                renderer = r
                count = r.pageCount
            }
        }
    }
    DisposableEffect(file.absolutePath) {
        onDispose {
            runCatching { renderer?.close() }
            runCatching { pfd?.close() }
        }
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(file.name + if (count > 0) " ($count)" else "", maxLines = 1) },
                navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } }
            )
        }
    ) { padding ->
        if (count == 0) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().consumeWindowInsets(padding),
                contentPadding = PaddingValues(
                    start = 8.dp, end = 8.dp,
                    top = padding.calculateTopPadding() + 8.dp,
                    bottom = padding.calculateBottomPadding() + 8.dp
                ),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(count, key = { it }) { index ->
                    PdfPageView(renderer, index)
                }
            }
        }
    }
}

@Composable
private fun PdfPageView(renderer: PdfRenderer?, index: Int) {
    var bmp by remember(index) { mutableStateOf<android.graphics.Bitmap?>(null) }
    LaunchedEffect(renderer, index) {
        withContext(Dispatchers.IO) {
            runCatching {
                val r = renderer ?: return@runCatching
                // RAM: render at 1.5x (was 2x in Quark Files — 4K pages OOM).
                synchronized(r) {
                    r.openPage(index).use { page ->
                        val scale = 1.5f
                        val b = android.graphics.Bitmap.createBitmap(
                            (page.width * scale).toInt(), (page.height * scale).toInt(),
                            android.graphics.Bitmap.Config.RGB_565
                        )
                        page.render(b, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        bmp?.recycle()
                        bmp = b
                    }
                }
            }
        }
    }
    DisposableEffect(index) {
        onDispose { try { bmp?.recycle() } catch (_: Exception) {}; bmp = null }
    }
    val b = bmp
    if (b != null) {
        Image(
            bitmap = b.asImageBitmap(),
            contentDescription = "Page ${index + 1}",
            modifier = Modifier.fillMaxWidth()
        )
    } else {
        Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun UnsupportedScreen(file: File, onClose: () -> Unit, onShare: (File) -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(file.name, maxLines = 1) },
                navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } }
            )
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Filled.Description, null, modifier = Modifier.size(48.dp))
                Text("No in-app preview for .${file.extension}", style = MaterialTheme.typography.titleMedium)
                TextButton(onClick = { onShare(file) }) { Text("Share / open with another app") }
            }
        }
    }
}
