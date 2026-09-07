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
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material.icons.filled.VideoFile
import androidx.compose.material.icons.filled.ViewList
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
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
    var showNewFolder by remember { mutableStateOf(false) }
    var renameFor by remember { mutableStateOf<File?>(null) }
    var sortOpen by remember { mutableStateOf(false) }
    var text by remember { mutableStateOf("") }

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
            val uris = files.map {
                FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", it)
            }
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

    Scaffold(
        modifier = modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(snacks) },
        floatingActionButton = {
            if (ui.selected.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ExtendedFloatingActionButton(
                        onClick = {
                            val files = ui.files.filter { it.absolutePath in ui.selected }
                            shareFiles(files)
                        },
                        icon = { Icon(Icons.Filled.Share, null) },
                        text = { Text("Share") }
                    )
                    ExtendedFloatingActionButton(
                        onClick = {
                            val files = ui.files.filter { it.absolutePath in ui.selected }
                            vm.delete(files) { ok ->
                                scope.launch { snacks.showSnackbar(if (ok) "Deleted" else "Delete failed") }
                            }
                        },
                        icon = { Icon(Icons.Filled.Delete, null) },
                        text = { Text("Delete") }
                    )
                }
            } else {
                ExtendedFloatingActionButton(
                    onClick = { showNewFolder = true },
                    icon = { Icon(Icons.Filled.CreateNewFolder, null) },
                    text = { Text("New folder") }
                )
            }
        }
    ) { _ ->
        Column(modifier = Modifier.fillMaxSize()) {
            // Search + view controls
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = ui.query,
                    onValueChange = vm::setQuery,
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Search files…") },
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
                    IconButton(onClick = { sortOpen = true }) { Icon(Icons.Filled.Sort, "Sort") }
                    DropdownMenu(expanded = sortOpen, onDismissRequest = { sortOpen = false }) {
                        listOf("Name", "Size", "Date", "Type").forEachIndexed { i, label ->
                            DropdownMenuItem(
                                text = { Text(label) },
                                onClick = { sortOpen = false; vm.setSort(i) }
                            )
                        }
                    }
                }
                IconButton(onClick = vm::toggleGrid) {
                    Icon(if (ui.grid) Icons.Filled.ViewList else Icons.Filled.GridView, "Toggle view")
                }
            }

            // Breadcrumb + up
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = vm::navigateUp) { Icon(Icons.Filled.ArrowBack, "Up") }
                LazyRow(
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

            // Quick chips
            LazyRow(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                item {
                    AssistChip(onClick = vm::goSandbox, label = { Text("Sandbox") }, leadingIcon = { Icon(Icons.Filled.Folder, null) })
                }
                item {
                    AssistChip(onClick = vm::goDownloads, label = { Text("Downloads") }, leadingIcon = { Icon(Icons.Filled.Download, null) })
                }
                item {
                    AssistChip(onClick = { importLauncher.launch(arrayOf("*/*")) }, label = { Text("Import") }, leadingIcon = { Icon(Icons.Filled.Upload, null) })
                }
                item {
                    AssistChip(onClick = { folderLauncher.launch(null) }, label = { Text("Import folder") }, leadingIcon = { Icon(Icons.Filled.Upload, null) })
                }
            }

            // Storage meter (basic feature)
            Card(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Sandbox ${FilesViewModel.formatSize(ui.usedBytes)}",
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.weight(1f)
                    )
                    LinearProgressIndicator(
                        progress = { (ui.usedBytes / (200f * 1024 * 1024)).coerceIn(0f, 1f) },
                        modifier = Modifier.width(120.dp)
                    )
                }
            }

            if (ui.busy != null) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    LinearProgressIndicator(modifier = Modifier.weight(1f))
                    Spacer(Modifier.width(8.dp))
                    Text(ui.busy!!, style = MaterialTheme.typography.labelMedium)
                }
            }

            // List / grid
            if (ui.files.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("📂", style = MaterialTheme.typography.displayMedium)
                        Spacer(Modifier.height(8.dp))
                        Text("No files here", style = MaterialTheme.typography.titleMedium)
                        Text("Import files or create a folder", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                            onLongClick = { vm.toggleSelect(f.absolutePath) },
                            onMore = { menuFor = f }
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
                                    fileIcon(f),
                                    null,
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

    menuFor?.let { f ->
        ModalBottomSheet(
            onDismissRequest = { menuFor = null },
            sheetState = rememberModalBottomSheetState()
        ) {
            Column(modifier = Modifier.padding(bottom = 24.dp)) {
                ListItem(headlineContent = { Text(f.name, maxLines = 1, overflow = TextOverflow.Ellipsis) })
                FileAction(Icons.Filled.FileOpen, "Open") { menuFor = null; openFile(f) }
                FileAction(Icons.Filled.Share, "Share") { menuFor = null; shareFiles(listOf(f)) }
                if (!f.isDirectory) FileAction(Icons.Filled.Upload, "Export (SAF)") {
                    menuFor = null
                    exportTarget.value = f
                    exportLauncher.launch(f.name)
                }
                FileAction(Icons.Filled.ContentCopy, "Copy path") {
                    menuFor = null
                    try {
                        (ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager)
                            .setPrimaryClip(android.content.ClipData.newPlainText("path", f.absolutePath))
                    } catch (_: Exception) {}
                }
                FileAction(Icons.Filled.Info, "Rename") { renameFor = f }
                FileAction(Icons.Filled.Info, "Details") {
                    menuFor = null
                    scope.launch { snacks.showSnackbar(vm.details(f).lineSequence().take(3).joinToString(" • ")) }
                }
                FileAction(Icons.Filled.Delete, "Delete") {
                    menuFor = null
                    vm.delete(listOf(f)) { ok ->
                        scope.launch { snacks.showSnackbar(if (ok) "Deleted" else "Delete failed") }
                    }
                }
            }
        }
    }

    if (showNewFolder) {
        text = ""
        AlertDialog(
            onDismissRequest = { showNewFolder = false },
            title = { Text("New folder") },
            text = {
                OutlinedTextField(value = text, onValueChange = { text = it }, placeholder = { Text("Folder name") }, singleLine = true)
            },
            confirmButton = {
                TextButton(onClick = {
                    showNewFolder = false
                    if (text.isNotBlank()) vm.createFolder(text.trim()) { ok ->
                        scope.launch { snacks.showSnackbar(if (ok) "Created" else "Failed") }
                    }
                }) { Text("Create") }
            },
            dismissButton = { TextButton(onClick = { showNewFolder = false }) { Text("Cancel") } }
        )
    }

    renameFor?.let { f ->
        text = f.name
        AlertDialog(
            onDismissRequest = { renameFor = null },
            title = { Text("Rename") },
            text = {
                OutlinedTextField(value = text, onValueChange = { text = it }, singleLine = true)
            },
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
}

@Composable
private fun FileGridCell(
    file: File,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onMore: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow
        ),
        modifier = Modifier.size(120.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .combinedClickable(onClick = onClick, onLongClick = onLongClick)
                .padding(8.dp),
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
        in setOf("pdf", "txt", "md", "log", "kt", "java", "py", "js", "ts", "html", "css", "json", "xml") -> Icons.Filled.Description
        else -> Icons.Filled.Description
    }
}
