package com.lightbrowser.ui.files

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DriveFileMove
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material.icons.filled.VideoFile
import androidx.compose.material.icons.filled.ViewList
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import java.io.File
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context

/**
 * LiteFM-style browser: app bar with expanding search, breadcrumb chips,
 * tonal icon tiles, contextual bottom bar for selections, paste banner.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun FilesScreen(
    modifier: Modifier = Modifier,
    vm: FilesViewModel = viewModel()
) {
    val ctx = LocalContext.current
    val ui by vm.ui.collectAsState()
    val scope = rememberCoroutineScope()
    val snacks = remember { SnackbarHostState() }

    var menuFor by remember { mutableStateOf<File?>(null) }
    var showCreate by remember { mutableStateOf(false) }
    var renameFor by remember { mutableStateOf<File?>(null) }
    var propsFor by remember { mutableStateOf<File?>(null) }
    var previewFor by remember { mutableStateOf<Pair<String, String?>?>(null) }
    var overflow by remember { mutableStateOf(false) }
    var searching by remember { mutableStateOf(false) }
    var text by remember { mutableStateOf("") }
    var createIsFile by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { vm.init() }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.importUri(uri) { name ->
            scope.launch { snacks.showSnackbar(if (name != null) "Imported $name" else "Import failed") }
        }
    }
    val exportTarget = remember { mutableStateOf<File?>(null) }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri ->
        val f = exportTarget.value
        if (uri != null && f != null) {
            scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                var ok = false
                try {
                    f.inputStream().use { input ->
                        val out = ctx.contentResolver.openOutputStream(uri)
                        if (out != null) { out.use { o -> input.copyTo(o) }; ok = true }
                    }
                } catch (_: Exception) { ok = false }
                withContext(kotlinx.coroutines.Dispatchers.Main) {
                    try { snacks.showSnackbar(if (ok) "Exported ${f.name}" else "Export failed") } catch (_: Exception) {}
                }
            }
        }
    }
    val folderLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) vm.importTree(uri) { n ->
            scope.launch { snacks.showSnackbar("Imported $n file(s)") }
        }
    }

    fun openFile(f: File) {
        try {
            val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", f)
            val ext = f.extension.lowercase()
            val mime = when (ext) {
                "apk" -> "application/vnd.android.package-archive"
                "mp3" -> "audio/mpeg"
                "m4a" -> "audio/mp4"
                "ogg", "opus" -> "audio/ogg"
                "flac" -> "audio/flac"
                "wav" -> "audio/wav"
                else -> android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "*/*"
            }
            ctx.startActivity(
                Intent.createChooser(
                    Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(uri, mime)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    },
                    "Open ${f.name}"
                )
            )
        } catch (_: Exception) {
            scope.launch { snacks.showSnackbar("No app can open this file") }
        }
    }

    fun shareFiles(files: List<File>) {
        if (files.isEmpty()) return
        try {
            val uris = files.map { FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", it) }
            val intent = if (uris.size == 1) {
                Intent(Intent.ACTION_SEND).apply {
                    type = "*/*"
                    putExtra(Intent.EXTRA_STREAM, uris[0])
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            } else {
                Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                    type = "*/*"
                    putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            }
            ctx.startActivity(Intent.createChooser(intent, "Share"))
        } catch (_: Exception) {}
    }

    // Cached formatter (was allocated per row per recomposition).
    val dateFmt = remember { java.text.SimpleDateFormat("MMM dd, HH:mm", java.util.Locale.getDefault()) }
    fun selectedFiles() = ui.files.filter { it.absolutePath in ui.selected }
    val folderName = ui.currentPath.substringAfterLast("/").ifBlank { "Sandbox" }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(snacks) },
        topBar = {
            if (searching) {
                TopAppBar(
                    title = {
                        OutlinedTextField(
                            value = ui.query,
                            onValueChange = vm::setQuery,
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = { Text("Search here") },
                            singleLine = true,
                            shape = MaterialTheme.shapes.extraLarge
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = { searching = false; vm.setQuery("") }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                        }
                    }
                )
            } else {
                TopAppBar(
                    title = {
                        Column {
                            Text(folderName, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                "${ui.count} items",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = vm::navigateUp) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Up")
                        }
                    },
                    actions = {
                        IconButton(onClick = { searching = true }) { Icon(Icons.Filled.Search, "Search") }
                        Box {
                            IconButton(onClick = { overflow = true }) { Icon(Icons.Filled.MoreVert, "More") }
                            DropdownMenu(expanded = overflow, onDismissRequest = { overflow = false }) {
                                Text("Sort by", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
                                listOf("Name", "Size", "Date", "Type").forEachIndexed { i, label ->
                                    DropdownMenuItem(
                                        text = { Text((if (ui.sortMode == i) "✓ " else "") + label) },
                                        onClick = { overflow = false; vm.setSort(i) }
                                    )
                                }
                                DropdownMenuItem(
                                    text = { Text(if (ui.grid) "List view" else "Grid view") },
                                    leadingIcon = { Icon(if (ui.grid) Icons.Filled.ViewList else Icons.Filled.GridView, null) },
                                    onClick = { overflow = false; vm.toggleGrid() }
                                )
                                DropdownMenuItem(
                                    text = { Text("Sandbox home") },
                                    leadingIcon = { Icon(Icons.Filled.Folder, null) },
                                    onClick = { overflow = false; vm.goSandbox() }
                                )
                                DropdownMenuItem(
                                    text = { Text("Downloads") },
                                    leadingIcon = { Icon(Icons.Filled.Download, null) },
                                    onClick = { overflow = false; vm.goDownloads() }
                                )
                                DropdownMenuItem(
                                    text = { Text("Import file") },
                                    leadingIcon = { Icon(Icons.Filled.Upload, null) },
                                    onClick = { overflow = false; importLauncher.launch(arrayOf("*/*")) }
                                )
                                DropdownMenuItem(
                                    text = { Text("Import folder") },
                                    leadingIcon = { Icon(Icons.Filled.Upload, null) },
                                    onClick = { overflow = false; folderLauncher.launch(null) }
                                )
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
                )
            }
        },
        bottomBar = {
            if (ui.selected.isNotEmpty()) {
                BottomAppBar {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                        BottomAction(Icons.Filled.ContentCopy, "Copy") {
                            vm.copyToClip(ui.selected.toList())
                            scope.launch { snacks.showSnackbar("Copied — open a folder and paste") }
                        }
                        BottomAction(Icons.Filled.DriveFileMove, "Move") {
                            vm.cutToClip(ui.selected.toList())
                            scope.launch { snacks.showSnackbar("Cut — open a folder and paste") }
                        }
                        BottomAction(Icons.Filled.Share, "Share") { shareFiles(selectedFiles()) }
                        BottomAction(Icons.Filled.Delete, "Delete") {
                            vm.delete(selectedFiles()) { ok ->
                                scope.launch { snacks.showSnackbar(if (ok) "Deleted" else "Delete failed") }
                            }
                        }
                        BottomAction(Icons.Filled.SelectAll, "All") { vm.selectAll() }
                    }
                }
            }
        },
        floatingActionButton = {
            // Always allow create; paste lives in banner. Hiding FAB trapped users with non-empty clip.
            FloatingActionButton(onClick = { showCreate = true }) {
                Icon(Icons.Filled.Add, "Create")
            }
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
            // ── Breadcrumb chips ──
            LazyRow(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                items(ui.crumbs.size) { i ->
                    val (name, path) = ui.crumbs[i]
                    FilterChip(
                        selected = i == ui.crumbs.size - 1,
                        onClick = {
                            val sd = vm.sandboxDir ?: return@FilterChip
                            vm.openDir(File(path).takeIf { it.exists() } ?: sd)
                        },
                        label = { Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    )
                }
            }

            // ── Paste banner ──
            if (ui.clip.isNotEmpty()) {
                Card(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Row(modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(if (ui.clipCut) Icons.Filled.ContentCut else Icons.Filled.ContentCopy, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "${ui.clip.size} to ${if (ui.clipCut) "move" else "copy"}",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = { vm.paste { msg -> scope.launch { snacks.showSnackbar(msg) } } }) { Text("Paste") }
                        TextButton(onClick = vm::clearClip) { Text("✕") }
                    }
                }
            }

            // ── Storage card ──
            Card(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                shape = RoundedCornerShape(16.dp)
            ) {
                Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Storage, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Sandbox ${FilesViewModel.formatSize(ui.usedBytes)}", style = MaterialTheme.typography.labelLarge)
                        Spacer(Modifier.height(6.dp))
                        LinearProgressIndicator(
                            progress = { (ui.usedBytes / (1f * 1024 * 1024 * 1024)).coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }

            if (ui.busy != null) {
                Row(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    LinearProgressIndicator(modifier = Modifier.weight(1f))
                    Spacer(Modifier.width(8.dp))
                    Text(ui.busy!!, style = MaterialTheme.typography.labelMedium)
                }
            }

            // ── Files ──
            if (ui.files.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) {
                        Icon(Icons.Filled.Folder, null, modifier = Modifier.size(64.dp), tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.height(12.dp))
                        Text(
                            if (ui.query.isNotBlank()) "No matches for \"${ui.query}\"" else if (ui.currentPath.isBlank()) "Loading…" else "Empty folder",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            if (ui.query.isNotBlank()) "Try a different search" else "Import files or tap + to create",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else if (ui.grid) {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(110.dp),
                    modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(ui.files, key = { it.absolutePath }) { f ->
                        val sel = f.absolutePath in ui.selected
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = if (sel) MaterialTheme.colorScheme.primaryContainer
                                else MaterialTheme.colorScheme.surfaceContainerLow
                            ),
                            shape = RoundedCornerShape(20.dp),
                            modifier = Modifier.size(110.dp)
                        ) {
                            Column(
                                modifier = Modifier.fillMaxSize().combinedClickable(
                                    onClick = {
                                        if (ui.selected.isNotEmpty()) vm.toggleSelect(f.absolutePath)
                                        else if (f.isDirectory) vm.openDir(f) else openFile(f)
                                    },
                                    onLongClick = { vm.toggleSelect(f.absolutePath) }
                                ).padding(10.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                Box(
                                    modifier = Modifier.size(48.dp).clip(RoundedCornerShape(16.dp))
                                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(fileIcon(f), null, modifier = Modifier.size(26.dp), tint = MaterialTheme.colorScheme.primary)
                                }
                                Spacer(Modifier.height(6.dp))
                                Text(f.name, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(ui.files, key = { it.absolutePath }) { f ->
                        val sel = f.absolutePath in ui.selected
                        ListItem(
                            headlineContent = { Text(f.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            supportingContent = {
                                Text(
                                    (if (f.isDirectory) "Folder" else FilesViewModel.formatSize(f.length())) +
                                        "  •  " + try { dateFmt.format(java.util.Date(f.lastModified())) } catch (_: Exception) { "" },
                                    maxLines = 1, overflow = TextOverflow.Ellipsis
                                )
                            },
                            leadingContent = {
                                Box(
                                    modifier = Modifier.size(46.dp).clip(RoundedCornerShape(14.dp))
                                        .background(
                                            if (sel) MaterialTheme.colorScheme.primary
                                            else MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                                        ),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        fileIcon(f), null,
                                        modifier = Modifier.size(24.dp),
                                        tint = if (sel) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.primary
                                    )
                                }
                            },
                            trailingContent = {
                                IconButton(onClick = { menuFor = f }) { Icon(Icons.Filled.MoreVert, "Options") }
                            },
                            modifier = Modifier.combinedClickable(
                                onClick = {
                                    if (ui.selected.isNotEmpty()) vm.toggleSelect(f.absolutePath)
                                    else if (f.isDirectory) vm.openDir(f) else openFile(f)
                                },
                                onLongClick = { vm.toggleSelect(f.absolutePath) }
                            ),
                            colors = androidx.compose.material3.ListItemDefaults.colors(
                                containerColor = if (sel) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface
                            )
                        )
                    }
                }
            }
        }
    }

    menuFor?.let { f ->
        ModalBottomSheet(
            onDismissRequest = { menuFor = null },
            sheetState = rememberModalBottomSheetState()
        ) {
            Column(modifier = Modifier.padding(bottom = 24.dp)) {
                ListItem(
                    headlineContent = { Text(f.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    leadingContent = {
                        Box(
                            modifier = Modifier.size(46.dp).clip(RoundedCornerShape(14.dp))
                                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
                            contentAlignment = Alignment.Center
                        ) { Icon(fileIcon(f), null, tint = MaterialTheme.colorScheme.primary) }
                    }
                )
                SheetRow(Icons.Filled.FileOpen, "Open") { menuFor = null; openFile(f) }
                if (!f.isDirectory && f.extension.lowercase() in setOf("txt", "md", "log", "json", "xml", "csv", "kt", "java", "py", "js", "ts", "html", "css", "sh", "prop", "ini")) {
                    SheetRow(Icons.Filled.Description, "Preview text") {
                        menuFor = null
                        vm.readTextPreview(f) { previewFor = f.name to it }
                    }
                }
                if (!f.isDirectory && f.extension.lowercase() == "zip") {
                    SheetRow(Icons.Filled.Download, "Extract here") {
                        menuFor = null
                        vm.extractZip(f) { msg -> scope.launch { snacks.showSnackbar(msg) } }
                    }
                }
                SheetRow(Icons.Filled.ContentCopy, "Copy") {
                    menuFor = null
                    vm.copyToClip(listOf(f.absolutePath))
                    scope.launch { snacks.showSnackbar("Copied — paste anywhere") }
                }
                SheetRow(Icons.Filled.ContentCut, "Cut") {
                    menuFor = null
                    vm.cutToClip(listOf(f.absolutePath))
                    scope.launch { snacks.showSnackbar("Cut — paste anywhere") }
                }
                SheetRow(Icons.Filled.Share, "Share") { menuFor = null; shareFiles(listOf(f)) }
                if (!f.isDirectory) SheetRow(Icons.Filled.Upload, "Export (SAF)") {
                    menuFor = null
                    exportTarget.value = f
                    exportLauncher.launch(f.name)
                }
                SheetRow(Icons.Filled.DriveFileRenameOutline, "Rename") { renameFor = f; menuFor = null }
                SheetRow(Icons.Filled.Description, "Properties") { propsFor = f; menuFor = null }
                SheetRow(Icons.Filled.Delete, "Delete") {
                    menuFor = null
                    vm.delete(listOf(f)) { ok ->
                        scope.launch { snacks.showSnackbar(if (ok) "Deleted" else "Delete failed") }
                    }
                }
            }
        }
    }

    if (showCreate) {
        // Key on open so text resets once per open — not on every recomposition (was wiping input).
        key(showCreate) {
            var createText by remember { mutableStateOf("") }
            ModalBottomSheet(
                onDismissRequest = { showCreate = false },
                sheetState = rememberModalBottomSheetState()
            ) {
                Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                    Text("Create new", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(vertical = 8.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = !createIsFile, onClick = { createIsFile = false }, label = { Text("Folder") })
                        FilterChip(selected = createIsFile, onClick = { createIsFile = true }, label = { Text("File") })
                    }
                    OutlinedTextField(
                        value = createText,
                        onValueChange = { createText = it },
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                        placeholder = { Text(if (createIsFile) "file.txt" else "Folder name") },
                        singleLine = true
                    )
                    Row(modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = { showCreate = false }) { Text("Cancel") }
                        TextButton(onClick = {
                            showCreate = false
                            if (createText.isBlank()) return@TextButton
                            if (createIsFile) vm.createFile(createText.trim()) { ok ->
                                scope.launch { snacks.showSnackbar(if (ok) "Created" else "Invalid name or failed") }
                            } else vm.createFolder(createText.trim()) { ok ->
                                scope.launch { snacks.showSnackbar(if (ok) "Created" else "Invalid name or failed") }
                            }
                        }) { Text("Create") }
                    }
                }
            }
        }
    }

    renameFor?.let { f ->
        key(f.absolutePath) {
            var renameText by remember { mutableStateOf(f.name) }
            AlertDialog(
                onDismissRequest = { renameFor = null },
                title = { Text("Rename") },
                text = { OutlinedTextField(value = renameText, onValueChange = { renameText = it }, singleLine = true) },
                confirmButton = {
                    TextButton(onClick = {
                        renameFor = null
                        menuFor = null
                        if (renameText.isNotBlank() && renameText != f.name) vm.rename(f, renameText.trim()) { ok ->
                            scope.launch { snacks.showSnackbar(if (ok) "Renamed" else "Invalid name or failed") }
                        }
                    }) { Text("OK") }
                },
                dismissButton = { TextButton(onClick = { renameFor = null }) { Text("Cancel") } }
            )
        }
    }

    propsFor?.let { f ->
        AlertDialog(
            onDismissRequest = { propsFor = null },
            title = { Text("Properties") },
            text = { Text(vm.details(f)) },
            confirmButton = { TextButton(onClick = { propsFor = null }) { Text("OK") } }
        )
    }

    // ── Text preview dialog ──
    previewFor?.let { (name, text) ->
        AlertDialog(
            onDismissRequest = { previewFor = null },
            title = { Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            text = {
                if (text == null) Text("Can't preview (binary or too large).")
                else androidx.compose.foundation.text.selection.SelectionContainer {
                    LazyColumn(modifier = Modifier.height(400.dp)) {
                        item {
                            Text(
                                text,
                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
            },
            confirmButton = {
                Row {
                    TextButton(onClick = { try { copyText(ctx, text ?: "") } catch (_: Exception) {} }) { Text("Copy") }
                    TextButton(onClick = { previewFor = null }) { Text("Close") }
                }
            }
        )
    }
}

@Composable
private fun BottomAction(icon: ImageVector, label: String, onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.combinedClickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Icon(icon, label, modifier = Modifier.size(22.dp))
        Text(label, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun SheetRow(icon: ImageVector, label: String, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(label) },
        leadingContent = { Icon(icon, null) },
        modifier = Modifier.combinedClickable(onClick = onClick)
    )
}

private fun fileIcon(f: File): ImageVector {
    if (f.isDirectory) return Icons.Filled.Folder
    return when (f.extension.lowercase()) {
        in setOf("mp3", "m4a", "aac", "ogg", "wav", "flac", "opus") -> Icons.Filled.MusicNote
        in setOf("mp4", "mkv", "avi", "mov", "webm") -> Icons.Filled.VideoFile
        in setOf("jpg", "jpeg", "png", "gif", "webp", "bmp") -> Icons.Filled.Image
        in setOf("pdf") -> Icons.Filled.Description
        in setOf("zip", "rar", "7z", "tar", "gz") -> Icons.Filled.Description
        in setOf("apk") -> Icons.Filled.Description
        else -> Icons.Filled.Description
    }
}

private fun copyText(ctx: Context, text: String) {
    try {
        (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
            .setPrimaryClip(ClipData.newPlainText("text", text))
    } catch (_: Exception) {}
}
