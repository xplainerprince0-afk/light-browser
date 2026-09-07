package com.lightbrowser.ui.files

import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lightbrowser.data.AppCtx
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class FilesUiState(
    val currentPath: String = "",
    val crumbs: List<Pair<String, String>> = emptyList(),
    val files: List<File> = emptyList(),
    val query: String = "",
    val sortMode: Int = 0, // 0 name, 1 size, 2 date, 3 type
    val grid: Boolean = false,
    val selected: Set<String> = emptySet(),
    val busy: String? = null,
    val count: Int = 0,
    val usedBytes: Long = 0L,
    val clip: List<String> = emptyList(),
    val clipCut: Boolean = false
)

class FilesViewModel : ViewModel() {

    private val _ui = MutableStateFlow(FilesUiState())
    val ui: StateFlow<FilesUiState> = _ui.asStateFlow()

    var sandboxDir: File? = null
        private set
    var downloadsDir: File? = null
        private set
    private var currentDir: File? = null

    fun init() {
        if (sandboxDir != null) {
            refresh()
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val app = AppCtx.ctx
                val sd = File(app.filesDir, "sandbox").apply { if (!exists()) mkdirs() }
                val dd = File(sd, "Downloads").apply { if (!exists()) mkdirs() }
                File(sd, "music").apply { if (!exists()) mkdirs() }
                sandboxDir = sd
                downloadsDir = dd
                if (currentDir == null) currentDir = sd
                refresh()
            } catch (_: Exception) {}
        }
    }

    fun openDir(dir: File) {
        val sd = sandboxDir ?: return
        val target = try {
            if (dir.absolutePath.startsWith(sd.absolutePath) && dir.exists()) dir else sd
        } catch (_: Exception) { sd }
        currentDir = target
        _ui.update { it.copy(query = "", selected = emptySet()) }
        refresh()
    }

    fun goSandbox() { sandboxDir?.let { openDir(it) } }
    fun goDownloads() { downloadsDir?.let { openDir(it) } }

    fun navigateUp() {
        val sd = sandboxDir ?: return
        val cur = currentDir ?: return
        if (cur == sd) return
        val parent = cur.parentFile
        if (parent != null && parent.absolutePath.startsWith(sd.absolutePath)) openDir(parent)
        else openDir(sd)
    }

    fun setQuery(q: String) {
        _ui.update { it.copy(query = q) }
        refresh()
    }

    fun setSort(mode: Int) {
        _ui.update { it.copy(sortMode = mode) }
        refresh()
    }

    fun toggleGrid() {
        _ui.update { it.copy(grid = !it.grid) }
    }

    fun toggleSelect(path: String) {
        _ui.update {
            val s = it.selected.toMutableSet()
            if (!s.add(path)) s.remove(path)
            it.copy(selected = s)
        }
    }

    fun clearSelection() {
        _ui.update { it.copy(selected = emptySet()) }
    }

    fun selectAll() {
        _ui.update { it.copy(selected = it.files.map { f -> f.absolutePath }.toSet()) }
    }

    // ── Clipboard: copy / cut / paste (LiteFM-style, survives navigation) ──
    fun copyToClip(paths: List<String>) {
        _ui.update { it.copy(clip = paths, clipCut = false, selected = emptySet()) }
    }

    fun cutToClip(paths: List<String>) {
        _ui.update { it.copy(clip = paths, clipCut = true, selected = emptySet()) }
    }

    fun clearClip() {
        _ui.update { it.copy(clip = emptyList(), clipCut = false) }
    }

    fun paste(done: (String) -> Unit) {
        val dest = currentDir ?: return
        val srcs = _ui.value.clip.mapNotNull { File(it).takeIf { f -> f.exists() } }
        if (srcs.isEmpty()) {
            done("Nothing to paste")
            return
        }
        val move = _ui.value.clipCut
        _ui.update { it.copy(busy = if (move) "Moving…" else "Copying…") }
        viewModelScope.launch(Dispatchers.IO) {
            var n = 0
            srcs.forEach { src ->
                try {
                    // Guard: never paste a folder into itself
                    if (src.isDirectory && dest.absolutePath.startsWith(src.absolutePath)) return@forEach
                    var out = File(dest, src.name)
                    if (out.absolutePath == src.absolutePath) return@forEach
                    var i = 1
                    while (out.exists()) {
                        val dot = src.name.lastIndexOf('.')
                        out = if (!src.isDirectory && dot > 0) {
                            File(dest, "${src.name.substring(0, dot)}($i)${src.name.substring(dot)}")
                        } else File(dest, "${src.name}($i)")
                        if (++i > 999) break
                    }
                    if (move) {
                        if (src.renameTo(out)) n++
                        else {
                            if (src.isDirectory) src.copyRecursively(out) else src.copyTo(out, overwrite = true)
                            if (if (src.isDirectory) src.deleteRecursively() else src.delete()) n++
                        }
                    } else {
                        if (src.isDirectory) src.copyRecursively(out) else src.copyTo(out, overwrite = true)
                        n++
                    }
                } catch (_: Exception) {}
            }
            val msg = if (move) "Moved $n item(s)" else "Copied $n item(s)"
            withContext(Dispatchers.Main) {
                _ui.update { it.copy(busy = null, clip = if (move) emptyList() else it.clip, clipCut = false) }
                refresh()
                done(msg)
            }
        }
    }

    fun refresh() {
        val dir = currentDir ?: return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val all = dir.listFiles()?.toList() ?: emptyList()
                val q = _ui.value.query
                val filtered = if (q.isBlank()) all
                else all.filter { it.name.contains(q, ignoreCase = true) }
                val sorted = when (_ui.value.sortMode) {
                    1 -> filtered.sortedWith(compareBy({ !it.isDirectory }, { if (it.isFile) -it.length() else 0L }))
                    2 -> filtered.sortedWith(compareBy({ !it.isDirectory }, { -it.lastModified() }))
                    3 -> filtered.sortedWith(compareBy({ !it.isDirectory }, { it.extension.lowercase() }, { it.name.lowercase() }))
                    else -> filtered.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
                }
                val sd = sandboxDir
                val crumbs = buildList {
                    if (sd != null) {
                        add("Sandbox" to sd.absolutePath)
                        val rel = dir.absolutePath.removePrefix(sd.absolutePath).trim('/').trimStart('/')
                        if (rel.isNotEmpty()) {
                            var p = sd
                            rel.split("/").forEach { seg ->
                                p = File(p, seg)
                                add(seg to p.absolutePath)
                            }
                        }
                    }
                }
                val used = try {
                    sd?.walkTopDown()?.filter { it.isFile }?.sumOf { it.length() } ?: 0L
                } catch (_: Exception) { 0L }
                withContext(Dispatchers.Main) {
                    _ui.update {
                        it.copy(
                            currentPath = dir.absolutePath,
                            crumbs = crumbs,
                            files = sorted,
                            count = sorted.size,
                            usedBytes = used
                        )
                    }
                }
            } catch (_: Exception) {}
        }
    }

    fun createFolder(name: String, done: (Boolean) -> Unit) {
        val dir = currentDir ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val ok = try { File(dir, name).mkdirs() } catch (_: Exception) { false }
            withContext(Dispatchers.Main) {
                if (ok) refresh()
                done(ok)
            }
        }
    }

    fun createFile(name: String, done: (Boolean) -> Unit) {
        val dir = currentDir ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val ok = try { File(dir, name).createNewFile() } catch (_: Exception) { false }
            withContext(Dispatchers.Main) {
                if (ok) refresh()
                done(ok)
            }
        }
    }

    fun rename(file: File, newName: String, done: (Boolean) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val ok = try { file.renameTo(File(file.parentFile, newName)) } catch (_: Exception) { false }
            withContext(Dispatchers.Main) {
                if (ok) refresh()
                done(ok)
            }
        }
    }

    fun delete(files: List<File>, done: (Boolean) -> Unit) {
        _ui.update { it.copy(busy = "Deleting…", selected = emptySet()) }
        viewModelScope.launch(Dispatchers.IO) {
            var ok = true
            files.forEach {
                try {
                    ok = (if (it.isDirectory) it.deleteRecursively() else it.delete()) && ok
                } catch (_: Exception) { ok = false }
            }
            withContext(Dispatchers.Main) {
                _ui.update { it.copy(busy = null) }
                refresh()
                done(ok)
            }
        }
    }

    fun importUri(uri: Uri, done: (String?) -> Unit) {
        val sd = sandboxDir ?: return
        _ui.update { it.copy(busy = "Importing…") }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val app = AppCtx.ctx
                var name: String? = null
                try {
                    app.contentResolver.query(uri, null, null, null, null)?.use { c ->
                        val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                        if (c.moveToFirst() && idx >= 0) name = c.getString(idx)
                    }
                } catch (_: Exception) {}
                val out = File(sd, name ?: "import_${System.currentTimeMillis()}")
                app.contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(out).use { output -> input.copyTo(output) }
                }
                withContext(Dispatchers.Main) {
                    _ui.update { it.copy(busy = null) }
                    openDir(sd)
                    done(out.name)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    _ui.update { it.copy(busy = null) }
                    done(null)
                }
            }
        }
    }

    fun importTree(treeUri: Uri, done: (Int) -> Unit) {
        val sd = sandboxDir ?: return
        _ui.update { it.copy(busy = "Importing folder…") }
        viewModelScope.launch(Dispatchers.IO) {
            var count = 0
            try {
                val app = AppCtx.ctx
                val doc = DocumentFile.fromTreeUri(app, treeUri)
                if (doc != null) count = copyTree(app, doc, sd)
            } catch (_: Exception) {}
            withContext(Dispatchers.Main) {
                _ui.update { it.copy(busy = null) }
                refresh()
                done(count)
            }
        }
    }

    private fun copyTree(app: android.content.Context, doc: DocumentFile, dest: File): Int {
        var count = 0
        doc.listFiles().forEach { item ->
            try {
                if (item.isDirectory) {
                    val sub = File(dest, item.name ?: "folder").apply { mkdirs() }
                    count += copyTree(app, item, sub)
                } else if (item.isFile) {
                    val out = File(dest, item.name ?: "file_${System.currentTimeMillis()}")
                    app.contentResolver.openInputStream(item.uri)?.use { input ->
                        FileOutputStream(out).use { output -> input.copyTo(output) }
                    }
                    count++
                }
            } catch (_: Exception) {}
        }
        return count
    }

    fun details(file: File): String {
        val size = if (file.isDirectory) {
            "${try { file.walkTopDown().count { it.isFile } } catch (_: Exception) { 0 }} files"
        } else formatSize(file.length())
        return "Name: ${file.name}\nPath: ${file.absolutePath}\nSize: $size\n" +
            "Type: ${if (file.isDirectory) "Folder" else file.extension.uppercase().ifEmpty { "File" }}\n" +
            "Modified: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date(file.lastModified()))}"
    }

    companion object {
        fun formatSize(bytes: Long): String {
            if (bytes < 1024) return "$bytes B"
            val kb = bytes / 1024.0
            if (kb < 1024) return "${"%.1f".format(kb)} KB"
            val mb = kb / 1024.0
            if (mb < 1024) return "${"%.1f".format(mb)} MB"
            return "${"%.1f".format(mb / 1024.0)} GB"
        }
    }
}
