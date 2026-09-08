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

    fun isAllowed(f: File): Boolean {
        return try {
            val sd = sandboxDir ?: return false
            val sdCanon = sd.canonicalFile.absolutePath
            val fCanon = f.canonicalFile.absolutePath
            fCanon == sdCanon || fCanon.startsWith(sdCanon + File.separator)
        } catch (_: Exception) { false }
    }

    fun sanitizeName(raw: String): String? {
        val t = raw.trim().trim('/', '\\')
        if (t.isEmpty() || t == "." || t == "..") return null
        if (t.contains("/") || t.contains("\\") || t.contains("\u0000")) return null
        if (t == "." || t == ".." || t.startsWith("..")) return null
        if (t.length > 120) return null
        if (t in setOf("CON", "PRN", "AUX", "NUL")) return null
        return t
    }

    fun openDir(dir: File) {
        val sd = sandboxDir ?: return
        val target = try {
            val canon = dir.canonicalFile
            if (isAllowed(canon) && dir.exists()) canon else sd
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
        try {
            if (cur.canonicalFile.absolutePath == sd.canonicalFile.absolutePath) return
        } catch (_: Exception) { return }
        val parent = cur.parentFile
        if (parent != null && isAllowed(parent)) openDir(parent)
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
        if (!isAllowed(dest)) { done("Invalid destination"); return }
        val srcs = _ui.value.clip.mapNotNull { File(it).takeIf { f -> f.exists() } }
            .filter { isAllowed(it) }
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
                    if (src.isDirectory && dest.canonicalFile.absolutePath.startsWith(src.canonicalFile.absolutePath + File.separator)) return@forEach
                    var out = File(dest, src.name)
                    if (out.canonicalFile.absolutePath == src.canonicalFile.absolutePath) return@forEach
                    if (!out.canonicalFile.absolutePath.startsWith(dest.canonicalFile.absolutePath + File.separator)) return@forEach
                    var i = 1
                    while (out.exists()) {
                        val dot = src.name.lastIndexOf('.')
                        out = if (!src.isDirectory && dot > 0) {
                            File(dest, "${src.name.substring(0, dot)}($i)${src.name.substring(dot)}")
                        } else File(dest, "${src.name}($i)")
                        if (++i > 999) break
                    }
                    if (out.exists()) {
                        out = File(dest, "${src.nameWithoutExtension}_${System.currentTimeMillis()}.${src.extension}".trim('.'))
                        if (out.exists()) return@forEach
                    }
                    if (move) {
                        if (src.renameTo(out)) n++
                        else {
                            if (src.isDirectory) src.copyRecursively(out) else src.copyTo(out, overwrite = false)
                            if (if (src.isDirectory) src.deleteRecursively() else src.delete()) n++
                        }
                    } else {
                        if (src.isDirectory) src.copyRecursively(out) else src.copyTo(out, overwrite = false)
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
        val safe = sanitizeName(name)
        if (safe == null) { done(false); return }
        viewModelScope.launch(Dispatchers.IO) {
            val ok = try {
                val out = File(dir, safe)
                if (!out.canonicalFile.absolutePath.startsWith(dir.canonicalFile.absolutePath + File.separator)) false
                else out.mkdirs()
            } catch (_: Exception) { false }
            withContext(Dispatchers.Main) {
                if (ok) refresh()
                done(ok)
            }
        }
    }

    fun createFile(name: String, done: (Boolean) -> Unit) {
        val dir = currentDir ?: return
        val safe = sanitizeName(name)
        if (safe == null) { done(false); return }
        viewModelScope.launch(Dispatchers.IO) {
            val ok = try {
                val out = File(dir, safe)
                if (!out.canonicalFile.absolutePath.startsWith(dir.canonicalFile.absolutePath + File.separator)) false
                else out.createNewFile()
            } catch (_: Exception) { false }
            withContext(Dispatchers.Main) {
                if (ok) refresh()
                done(ok)
            }
        }
    }

    fun readTextPreview(file: File, done: (String?) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val text = try {
                if (file.length() > 300_000) return@launch withContext(Dispatchers.Main) { done(null) }
                val bytes = file.readBytes()
                // Refuse binary
                if (bytes.take(4096).any { it == 0.toByte() }) {
                    withContext(Dispatchers.Main) { done(null) }
                    return@launch
                }
                String(bytes, Charsets.UTF_8).take(100_000)
            } catch (_: Exception) { null }
            withContext(Dispatchers.Main) { done(text) }
        }
    }

    fun extractZip(file: File, done: (String) -> Unit) {
        if (!isAllowed(file)) { done("Denied"); return }
        val dest = try { File(file.parentFile, file.nameWithoutExtension) } catch (_: Exception) { return }
        _ui.update { it.copy(busy = "Extracting…") }
        viewModelScope.launch(Dispatchers.IO) {
            var count = 0
            var totalBytes = 0L
            try {
                dest.mkdirs()
                // Zip-bomb guard: max 1000 files / 500MB uncompressed.
                java.util.zip.ZipFile(file).use { zip ->
                    val entries = zip.entries()
                    while (entries.hasMoreElements()) {
                        if (count > 1000 || totalBytes > 500L * 1024 * 1024) {
                            withContext(Dispatchers.Main) {
                                _ui.update { it.copy(busy = null) }
                                done("Stopped: zip too large (bomb guard)")
                            }
                            return@launch
                        }
                        val e = entries.nextElement()
                        // Zip-slip guard
                        val out = File(dest, e.name)
                        if (!out.canonicalFile.absolutePath.startsWith(dest.canonicalFile.absolutePath + File.separator) &&
                            out.canonicalFile.absolutePath != dest.canonicalFile.absolutePath
                        ) continue
                        if (e.isDirectory) out.mkdirs()
                        else {
                            // Skip entries that would overwrite without prompt — uniquify.
                            var target = out
                            if (target.exists()) {
                                val b = target.nameWithoutExtension
                                val ext = target.extension.let { if (it.isBlank()) "" else ".$it" }
                                target = File(target.parentFile, "${b}_${System.currentTimeMillis()}$ext")
                            }
                            target.parentFile?.mkdirs()
                            zip.getInputStream(e).use { input ->
                                java.io.FileOutputStream(target).use { output ->
                                    val buf = ByteArray(64 * 1024)
                                    while (true) {
                                        val n = input.read(buf)
                                        if (n < 0) break
                                        totalBytes += n
                                        if (totalBytes > 500L * 1024 * 1024) break
                                        output.write(buf, 0, n)
                                    }
                                }
                            }
                            count++
                        }
                    }
                }
                withContext(Dispatchers.Main) {
                    _ui.update { it.copy(busy = null) }
                    refresh()
                    done("Extracted $count file(s) to ${dest.name}")
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    _ui.update { it.copy(busy = null) }
                    done("Extract failed: ${e.message}")
                }
            }
        }
    }

    fun rename(file: File, newName: String, done: (Boolean) -> Unit) {
        val safe = sanitizeName(newName)
        if (safe == null || !isAllowed(file)) { done(false); return }
        viewModelScope.launch(Dispatchers.IO) {
            val ok = try {
                val out = File(file.parentFile, safe)
                if (!out.canonicalFile.absolutePath.startsWith(file.parentFile.canonicalFile.absolutePath + File.separator)) false
                else if (out.exists()) false
                else file.renameTo(out)
            } catch (_: Exception) { false }
            withContext(Dispatchers.Main) {
                if (ok) refresh()
                done(ok)
            }
        }
    }

    fun delete(files: List<File>, done: (Boolean) -> Unit) {
        val targets = files.filter { isAllowed(it) }
        _ui.update { it.copy(busy = "Deleting…", selected = emptySet()) }
        viewModelScope.launch(Dispatchers.IO) {
            var ok = true
            targets.forEach {
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
        val destDir = currentDir ?: sandboxDir ?: return
        if (!isAllowed(destDir)) { done(null); return }
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
                val rawName = name ?: "import_${System.currentTimeMillis()}"
                val safeName = sanitizeName(rawName.substringAfterLast("/").substringAfterLast("\\")) ?: "import_${System.currentTimeMillis()}"
                var out = File(destDir, safeName)
                if (out.exists()) {
                    val b = out.nameWithoutExtension
                    val ext = out.extension.let { if (it.isBlank()) "" else ".$it" }
                    out = File(destDir, "${b}_${System.currentTimeMillis()}$ext")
                }
                app.contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(out).use { output -> input.copyTo(output) }
                }
                withContext(Dispatchers.Main) {
                    _ui.update { it.copy(busy = null) }
                    refresh()
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
        val destDir = currentDir ?: sandboxDir ?: return
        if (!isAllowed(destDir)) { done(0); return }
        _ui.update { it.copy(busy = "Importing folder…") }
        viewModelScope.launch(Dispatchers.IO) {
            var count = 0
            try {
                val app = AppCtx.ctx
                val doc = DocumentFile.fromTreeUri(app, treeUri)
                // Cap: max 2000 files to avoid OOM (loads listFiles into memory otherwise).
                if (doc != null) count = copyTree(app, doc, destDir, 0)
            } catch (_: Exception) {}
            withContext(Dispatchers.Main) {
                _ui.update { it.copy(busy = null) }
                refresh()
                done(count)
            }
        }
    }

    private fun copyTree(app: android.content.Context, doc: DocumentFile, dest: File, depth: Int): Int {
        if (depth > 8) return 0
        var count = 0
        val items = try { doc.listFiles().take(2000) } catch (_: Exception) { return 0 }
        items.forEach { item ->
            if (count > 2000) return count
            try {
                val raw = (item.name ?: "file").substringAfterLast("/").substringAfterLast("\\").take(100)
                val safe = sanitizeName(raw) ?: return@forEach
                if (item.isDirectory) {
                    val sub = File(dest, safe).apply { mkdirs() }
                    if (!sub.canonicalFile.absolutePath.startsWith(dest.canonicalFile.absolutePath + File.separator)) return@forEach
                    count += copyTree(app, item, sub, depth + 1)
                } else if (item.isFile) {
                    var out = File(dest, safe)
                    if (out.exists()) out = File(dest, "${out.nameWithoutExtension}_${System.currentTimeMillis()}.${out.extension}".trim('.'))
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
