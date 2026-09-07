package com.lightbrowser.ui.files

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material.icons.filled.VideoFile
import androidx.compose.material.icons.filled.ViewList
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
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
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import java.io.File
import kotlinx.coroutines.launch

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
    var overflow by remember { mutableStateOf(false) }
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
                try {
                    f.inputStream().use { input ->
                        ctx.contentResolver.openOutputStream(uri)?.use { out -> input.copyTo(out) }
                    }
                    snacks.showSnackbar("Exported ${f.name}")
                } catch (_: Exception) {
                    snacks.showSnackbar("Export failed")
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
            val mime = android.webkit.MimeTypeMap.getSingleton()
                .getMimeTypeFromExtension(f.extension) ?: "*/*"
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

    fun selectedFiles() = ui.files.filter { it.absolutePath in ui.selected }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(snacks) },
        floatingActionButton = {
            when {
                ui.selected.isNotEmpty() -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ExtendedFloatingActionButton(
                        onClick = { shareFiles(selectedFiles()) },
                        icon = { Icon(Icons.Filled.Share, null) },
                        text = { Text("Share") }
                    )
                    ExtendedFloatingActionButton(
                        onClick = {
                            vm.delete(selectedFiles()) { ok ->
                                scope.launch { snacks.showSnackbar(if (ok) "Deleted" else "Delete failed") }
                            }
                        },
                        icon = { Icon(Icons.Filled.Delete, null) },
                        text = { Text("Delete") }
                    )
                }
                ui.clip.isNotEmpty() -> ExtendedFloatingActionButton(
                    onClick = { vm.paste { msg -> scope.launch { snacks.showSnackbar(msg) } } },
                    icon = { Icon(Icons.Filled.ContentPaste, null) },
                    text = { Text("Paste") }
                )
                else -> ExtendedFloatingActionButton(
                    onClick = { showCreate = true },
                    icon = { Icon(Icons.Filled.Folder, null) },
                    text = { Text("New") }
                )
            }
        }
    ) { _ ->
        Column(modifier = Modifier.fillMaxSize()) {
            // ── Single toolbar row: up + search + overflow (was 3 stacked rows) ──
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = vm::navigateUp) { Icon(Icons.Filled.ArrowBack, "Up") }
                OutlinedTextField(
                    value = ui.query,
                    onValueChange = vm::setQuery,
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Search here") },
                    leadingIcon = { Icon(Icons.Filled.Search, null) },
                    trailingIcon = {
                        if (ui.query.isNotEmpty()) IconButton(onClick = { vm.setQuery("") }) {
                            Icon(Icons.Filled.Close, "Clear")
                        }
                    },
                    singleLine = true,
                    shape = MaterialTheme.shapes.extraLarge
                )
                Box {
                    IconButton(onClick = { overflow = true }) { Icon(Icons.Filled.MoreVert, "More") }
                    DropdownMenu(expanded = overflow, onDismissRequest = { overflow = false }) {
                        Text("Sort by", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
                        listOf("Name", "Size", "Date", "Type").forEachIndexed { i, label ->
                            DropdownMenuItem(
                                text = { Text((if (ui.sortMode == i) "✓ " else "") + label) },
                                onClick = { vm.setSort(i) }
                            )
                        }
                        DropdownMenuItem(
                            text = { Text(if (ui.grid) "List view" else "Grid view") },
                            leadingIcon = { Icon(if (ui.grid) Icons.Filled.ViewList else Icons.Filled.GridView, null) },
                            onClick = { overflow = false; vm.toggleGrid() }
                        )
                        DropdownMenuItem(
                            text = { Text("Go to Sandbox") },
                            leadingIcon = { Icon(Icons.Filled.Folder, null) },
                            onClick = { overflow = false; vm.goSandbox() }
                        )
                        DropdownMenuItem(
                            text = { Text("Go to Downloads") },
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
            }

            // ── Breadcrumb ──
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                androidx.compose.foundation.lazy.LazyRow(
                    modifier = Modifier.weight(1f),
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
                Text("${ui.count}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            // ── Selection bar ──
            if (ui.selected.isNotEmpty()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("${ui.selected.size} selected", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                    IconButton(onClick = vm::selectAll) { Icon(Icons.Filled.SelectAll, "Select all") }
                    IconButton(onClick = { vm.copyToClip(ui.selected.toList()); scope.launch { snacks.showSnackbar("Copied — paste anywhere") } }) {
                        Icon(Icons.Filled.ContentCopy, "Copy")
                    }
                    IconButton(onClick = { vm.cutToClip(ui.selected.toList()); scope.launch { snacks.showSnackbar("Cut — paste anywhere") } }) {
                        Icon(Icons.Filled.ContentCut, "Cut")
                    }
                    IconButton(onClick = vm::clearSelection) { Icon(Icons.Filled.Close, "Clear selection") }
                }
            }

            // ── Paste banner (clipboard survives navigation) ──
            if (ui.clip.isNotEmpty()) {
                Card(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                ) {
                    Row(modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(if (ui.clipCut) Icons.Filled.ContentCut else Icons.Filled.ContentCopy, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "${ui.clip.size} item(s) to ${if (ui.clipCut) "move" else "copy"}",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = { vm.paste { msg -> scope.launch { snacks.showSnackbar(msg) } } }) { Text("Paste") }
                        TextButton(onClick = vm::clearClip) { Text("Cancel") }
                    }
                }
            }

            // ── Storage meter ──
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Sandbox ${FilesViewModel.formatSize(ui.usedBytes)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                LinearProgressIndicator(
                    progress = { (ui.usedBytes / (200f * 1024 * 1024)).coerceIn(0f, 1f) },
                    modifier = Modifier.width(120.dp)
                )
            }

            if (ui.busy != null) {
                Row(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    LinearProgressIndicator(modifier = Modifier.weight(1f))
                    Spacer(Modifier.width(8.dp))
                    Text(ui.busy!!, style = MaterialTheme.typography.labelMedium)
                }
            }

            // ── List / grid ──
            if (ui.files.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("📂", style = MaterialTheme.typography.displayMedium)
                        Spacer(Modifier.height(8.dp))
                        Text("No files here", style = MaterialTheme.typography.titleMedium)
                        Text("Import files or create one", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            } else if (ui.grid) {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(120.dp),
                    modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(ui.files, key = { it.absolutePath }) { f ->
                        FileGridCell(
                            file = f,
                            selected = f.absolutePath in ui.selected,
                            onClick = {
                                if (ui.selected.isNotEmpty()) vm.toggleSelect(f.absolutePath)
                                else if (f.isDirectory) vm.openDir(f) else openFile(f)
                            },
                            onLongClick = { vm.toggleSelect(f.absolutePath) }
                        )
                    }
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize().padding(horizontal = 4.dp)) {
                    items(ui.files, key = { it.absolutePath }) { f ->
                        val sel = f.absolutePath in ui.selected
                        ListItem(
                            headlineContent = { Text(f.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            supportingContent = {
                                Text(
                                    (if (f.isDirectory) "${f.listFiles()?.size ?: 0} items" else FilesViewModel.formatSize(f.length())) +
                                        "  •  " + java.text.SimpleDateFormat("MMM dd, HH:mm", java.util.Locale.getDefault()).format(java.util.Date(f.lastModified())),
                                    maxLines = 1, overflow = TextOverflow.Ellipsis
                                )
                            },
                            leadingContent = {
                                Icon(
                                    fileIcon(f), null,
                                    tint = if (sel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                )
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

    // ── Per-file sheet: Open, Copy, Cut, Share, Export, Rename, Properties, Delete ──
    menuFor?.let { f ->
        ModalBottomSheet(
            onDismissRequest = { menuFor = null },
            sheetState = rememberModalBottomSheetState()
        ) {
            Column(modifier = Modifier.padding(bottom = 24.dp)) {
                ListItem(headlineContent = { Text(f.name, maxLines = 1, overflow = TextOverflow.Ellipsis) })
                FileAction(Icons.Filled.FileOpen, "Open") { menuFor = null; openFile(f) }
                FileAction(Icons.Filled.ContentCopy, "Copy") {
                    menuFor = null
                    vm.copyToClip(listOf(f.absolutePath))
                    scope.launch { snacks.showSnackbar("Copied — paste anywhere") }
                }
                FileAction(Icons.Filled.ContentCut, "Cut") {
                    menuFor = null
                    vm.cutToClip(listOf(f.absolutePath))
                    scope.launch { snacks.showSnackbar("Cut — paste anywhere") }
                }
                FileAction(Icons.Filled.Share, "Share") { menuFor = null; shareFiles(listOf(f)) }
                if (!f.isDirectory) FileAction(Icons.Filled.Upload, "Export (SAF)") {
                    menuFor = null
                    exportTarget.value = f
                    exportLauncher.launch(f.name)
                }
                FileAction(Icons.Filled.DriveFileRenameOutline, "Rename") { renameFor = f }
                FileAction(Icons.Filled.Info, "Properties") { propsFor = f; menuFor = null }
                FileAction(Icons.Filled.Delete, "Delete") {
                    menuFor = null
                    vm.delete(listOf(f)) { ok ->
                        scope.launch { snacks.showSnackbar(if (ok) "Deleted" else "Delete failed") }
                    }
                }
            }
        }
    }

    // ── Create sheet: folder or file ──
    if (showCreate) {
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
                text = ""
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    placeholder = { Text(if (createIsFile) "file.txt" else "Folder name") },
                    singleLine = true
                )
                Row(modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = { showCreate = false }) { Text("Cancel") }
                    TextButton(onClick = {
                        showCreate = false
                        if (text.isBlank()) return@TextButton
                        if (createIsFile) vm.createFile(text.trim()) { ok ->
                            scope.launch { snacks.showSnackbar(if (ok) "Created" else "Failed") }
                        } else vm.createFolder(text.trim()) { ok ->
                            scope.launch { snacks.showSnackbar(if (ok) "Created" else "Failed") }
                        }
                    }) { Text("Create") }
                }
            }
        }
    }

    renameFor?.let { f ->
        text = f.name
        AlertDialog(
            onDismissRequest = { renameFor = null },
            title = { Text("Rename") },
            text = { OutlinedTextField(value = text, onValueChange = { text = it }, singleLine = true) },
            confirmButton = {
                TextButton(onClick = {
                    renameFor = null
                    menuFor = null
                    if (text.isNotBlank() && text != f.name) vm.rename(f, text.trim()) { ok ->
                        scope.launch { snacks.showSnackbar(if (ok) "Renamed" else "Failed") }
                    }
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { renameFor = null }) { Text("Cancel") } }
        )
    }

    // ── Properties dialog (was a truncated snackbar) ──
    propsFor?.let { f ->
        AlertDialog(
            onDismissRequest = { propsFor = null },
            title = { Text("Properties") },
            text = { Text(vm.details(f)) },
            confirmButton = { TextButton(onClick = { propsFor = null }) { Text("OK") } }
        )
    }
}

@Composable
private fun FileGridCell(file: File, selected: Boolean, onClick: () -> Unit, onLongClick: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow
        ),
        modifier = Modifier.size(120.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxSize().combinedClickable(onClick = onClick, onLongClick = onLongClick).padding(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(fileIcon(file), null, modifier = Modifier.size(36.dp))
            Spacer(Modifier.height(6.dp))
            Text(file.name, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun FileAction(icon: ImageVector, label: String, onClick: () -> Unit) {
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
        else -> Icons.Filled.Description
    }
}
