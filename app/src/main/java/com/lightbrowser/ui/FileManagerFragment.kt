package com.lightbrowser.ui

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.lightbrowser.R
import com.lightbrowser.databinding.FragmentFilemanagerBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.text.DecimalFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class FileManagerFragment : Fragment() {
    private var _b: FragmentFilemanagerBinding? = null
    private val b get() = _b!!
    private var currentDir: File = File("/")
    private var sandboxDir: File? = null
    private var downloadsDir: File? = null

    private var importLauncher: ActivityResultLauncher<Array<String>>? = null
    private var exportLauncher: ActivityResultLauncher<String>? = null
    private var exportFile: File? = null
    private var importFolderLauncher: ActivityResultLauncher<Uri?>? = null

    private val scope = CoroutineScope(Dispatchers.Main)

    // Sort order: 0=name, 1=size, 2=date, 3=type
    private var sortMode = 0
    // View mode: 0=list, 1=grid
    private var viewMode = 0
    // Search query
    private var searchQuery = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            importLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
                if (uri != null) try { importFile(uri) } catch (_: Exception) {}
            }
        } catch (_: Exception) {}
        try {
            exportLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri: Uri? ->
                if (uri != null && exportFile != null) try { exportFileToUri(exportFile!!, uri) } catch (_: Exception) {}
            }
        } catch (_: Exception) {}
        try {
            importFolderLauncher = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
                if (uri != null) {
                    try {
                        requireContext().contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        copyFolderToSandbox(uri)
                    } catch (e: Exception) { safeToast(e.message) }
                }
            }
        } catch (_: Exception) {}
    }

    override fun onCreateView(inflater: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        return try {
            _b = FragmentFilemanagerBinding.inflate(inflater, c, false)
            b.root
        } catch (e: Exception) {
            android.util.Log.e("FileManager", "onCreateView crash", e)
            TextView(requireContext()).apply { text = "File Manager unavailable: ${e.message}" }
        }
    }

    override fun onViewCreated(v: View, s: Bundle?) {
        try {
            // Init dirs
            try {
                sandboxDir = File(requireContext().filesDir, "sandbox").apply { if (!exists()) mkdirs() }
            } catch (_: Exception) {
                try { sandboxDir = File(requireContext().cacheDir, "sandbox").apply { if (!exists()) mkdirs() } } catch (_: Exception) {}
            }
            try {
                downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            } catch (_: Exception) { downloadsDir = sandboxDir }
            if (downloadsDir == null) downloadsDir = sandboxDir

            val bb = _b ?: return
            bb.recycler.layoutManager = LinearLayoutManager(requireContext())

            // Search watcher
            bb.etSearch.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    searchQuery = s?.toString() ?: ""
                    refresh()
                }
            })

            bb.btnUp.setOnClickListener { navigateUp() }

            bb.btnAppFiles.setOnClickListener { sandboxDir?.let { openDir(it) } }
            bb.btnDownloads.setOnClickListener { downloadsDir?.let { openDir(it) } }
            bb.btnImport.setOnClickListener {
                try { importLauncher?.launch(arrayOf("*/*")) ?: safeToast("Import unavailable") } catch (e: Exception) { safeToast(e.message) }
            }
            bb.btnNewFolder.setOnClickListener { showNewFolderDialog() }

            bb.btnViewToggle.setOnClickListener {
                viewMode = if (viewMode == 0) 1 else 0
                bb.btnViewToggle.text = if (viewMode == 0) "▦" else "☰"
                bb.recycler.layoutManager = if (viewMode == 1)
                    GridLayoutManager(requireContext(), 3)
                else
                    LinearLayoutManager(requireContext())
                refresh()
            }

            bb.btnSort.setOnClickListener { showSortMenu() }
            bb.btnOverflow.setOnClickListener { showOverflowMenu() }

            ensureSandboxDownloadsFolder()
            sandboxDir?.let { openDir(it) } ?: run {
                bb.emptyState.visibility = View.VISIBLE
                bb.recycler.visibility = View.GONE
                bb.empty.text = "Sandbox unavailable"
            }
        } catch (e: Exception) {
            android.util.Log.e("FileManager", "onViewCreated crash", e)
            try { Toast.makeText(requireContext(), "Files error: ${e.message}", Toast.LENGTH_LONG).show() } catch (_: Exception) {}
        }
    }

    private fun ensureSandboxDownloadsFolder() {
        sandboxDir?.let { File(it, "Downloads").apply { if (!exists()) mkdirs() } }
    }

    private fun safeToast(msg: String?) {
        try { Toast.makeText(requireContext(), msg ?: "error", Toast.LENGTH_SHORT).show() } catch (_: Exception) {}
    }

    private fun showSortMenu() {
        try {
            val bb = _b ?: return
            val popup = PopupMenu(requireContext(), bb.btnSort)
            popup.menu.add(0, 0, 0, "Sort by Name")
            popup.menu.add(0, 1, 1, "Sort by Size")
            popup.menu.add(0, 2, 2, "Sort by Date")
            popup.menu.add(0, 3, 3, "Sort by Type")
            popup.setOnMenuItemClickListener { item ->
                sortMode = item.itemId
                refresh()
                true
            }
            popup.show()
        } catch (e: Exception) { android.util.Log.e("FileManager", "sort menu", e) }
    }

    private fun showOverflowMenu() {
        try {
            val bb = _b ?: return
            val ctx = try { requireContext() } catch (_: Exception) { return }
            val popup = PopupMenu(ctx, bb.btnOverflow)
            popup.menuInflater.inflate(R.menu.filemanager_menu, popup.menu)
            popup.setOnMenuItemClickListener { item: MenuItem ->
                try {
                    when (item.itemId) {
                        R.id.menu_open_sandbox   -> sandboxDir?.let { openDir(it) }
                        R.id.menu_open_downloads -> downloadsDir?.let { openDir(it) }
                        R.id.menu_import         -> try { importLauncher?.launch(arrayOf("*/*")) } catch (e: Exception) { safeToast(e.message) }
                        R.id.menu_import_folder  -> importFolderLauncher?.launch(null)
                        R.id.menu_export         -> safeToast("Long-press a file to export")
                        R.id.menu_details        -> showDetails(currentDir)
                    }
                } catch (_: Exception) {}
                true
            }
            popup.show()
        } catch (e: Exception) { android.util.Log.e("FileManager", "overflow menu", e) }
    }

    private fun showNewFolderDialog() {
        try {
            val ctx = try { requireContext() } catch (_: Exception) { return }
            val et = android.widget.EditText(ctx).apply { hint = "Folder name" }
            android.app.AlertDialog.Builder(ctx)
                .setTitle("New Folder")
                .setView(et)
                .setPositiveButton("Create") { _, _ ->
                    val name = et.text.toString().trim()
                    if (name.isNotEmpty()) {
                        val f = File(currentDir, name)
                        if (f.mkdirs()) { safeToast("Created $name"); refresh() }
                        else safeToast("Failed to create folder")
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
        } catch (_: Exception) {}
    }

    private fun openDir(dir: File) {
        try {
            val sd = sandboxDir ?: return
            val dd = downloadsDir ?: sd
            val allowed = try {
                dir.absolutePath.startsWith(sd.absolutePath) || dir.absolutePath.startsWith(dd.absolutePath) || dir == sd || dir == dd
            } catch (_: Exception) { false }
            val target = try { if (allowed && dir.exists()) dir else sd } catch (_: Exception) { sd }
            currentDir = target
            searchQuery = ""
            _b?.etSearch?.text?.clear()
            refresh()
        } catch (_: Exception) { try { refresh() } catch (_: Exception) {} }
    }

    private fun navigateUp() {
        try {
            val cur = currentDir
            val sd = sandboxDir ?: return
            val dd = downloadsDir ?: sd
            if (cur == sd || cur == dd) { safeToast("Already at root"); return }
            val parent = cur.parentFile
            if (parent != null && try { parent.absolutePath.startsWith(sd.absolutePath) || parent.absolutePath.startsWith(dd.absolutePath) } catch (_: Exception) { false }) {
                openDir(parent)
            } else openDir(sd)
        } catch (e: Exception) { safeToast(e.message) }
    }

    private fun getSortedFiles(): List<File> {
        val allFiles = try { currentDir.listFiles()?.toList() ?: emptyList() } catch (_: Exception) { emptyList() }
        val filtered = if (searchQuery.isBlank()) allFiles
        else allFiles.filter { it.name.contains(searchQuery, ignoreCase = true) }
        return when (sortMode) {
            1 -> filtered.sortedWith(compareBy({ !it.isDirectory }, { if (it.isFile) -it.length() else 0L }))
            2 -> filtered.sortedWith(compareBy({ !it.isDirectory }, { -it.lastModified() }))
            3 -> filtered.sortedWith(compareBy({ !it.isDirectory }, { it.extension.lowercase() }, { it.name.lowercase() }))
            else -> filtered.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
        }
    }

    private fun refresh() {
        val bb = _b ?: return
        val ctx = try { requireContext() } catch (_: Exception) { return }
        val sd = sandboxDir
        val dd = downloadsDir
        if (sd == null) return

        try { bb.tvPath.text = currentDir.absolutePath } catch (_: Exception) {}
        updateBreadcrumb()

        val files = getSortedFiles()
        val hasFiles = files.isNotEmpty()
        bb.emptyState.layoutParams.height = if (!hasFiles) 0 else ViewGroup.LayoutParams.MATCH_PARENT
        bb.emptyState.visibility = if (!hasFiles) View.GONE else View.VISIBLE
        bb.recycler.visibility = if (hasFiles) View.VISIBLE else View.GONE

        if (hasFiles) {
            bb.emptyState.visibility = View.GONE
            bb.recycler.visibility = View.VISIBLE
        } else {
            bb.emptyState.visibility = View.VISIBLE
            bb.recycler.visibility = View.GONE
        }

        bb.tvCount.text = "${files.size} items"

        bb.recycler.adapter = object : RecyclerView.Adapter<FileViewHolder>() {
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): FileViewHolder {
                val v = LayoutInflater.from(parent.context).inflate(R.layout.item_file, parent, false)
                return FileViewHolder(v)
            }
            override fun onBindViewHolder(holder: FileViewHolder, position: Int) {
                val f = files[position]
                val isDir = f.isDirectory
                holder.icon.setImageResource(getFileIconRes(f))
                holder.title.text = f.name
                val sizeTxt = if (isDir) "${f.listFiles()?.size ?: 0} items" else formatSize(f.length())
                val date = SimpleDateFormat("MMM dd, HH:mm", Locale.getDefault()).format(Date(f.lastModified()))
                holder.status.text = "$sizeTxt  •  $date"
                holder.more.setOnClickListener { showFileOptions(f) }
                holder.itemView.setOnClickListener { if (isDir) openDir(f) else openFile(f) }
                holder.itemView.setOnLongClickListener { showFileOptions(f); true }
            }
            override fun getItemCount() = files.size
        }
    }

    inner class FileViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val icon: ImageView = view.findViewById(R.id.ivFileIcon)
        val title: TextView = view.findViewById(R.id.tvTitle)
        val status: TextView = view.findViewById(R.id.tvStatus)
        val more: TextView = view.findViewById(R.id.btnMore)
    }

    private fun formatSize(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024) return "${DecimalFormat("#.#").format(kb)} KB"
        val mb = kb / 1024.0
        if (mb < 1024) return "${DecimalFormat("#.#").format(mb)} MB"
        return "${DecimalFormat("#.#").format(mb / 1024.0)} GB"
    }

    private fun getFileIconRes(file: File): Int {
        if (file.isDirectory) return R.drawable.ic_folder
        val ext = file.extension.lowercase()
        return when {
            ext in setOf("mp3", "m4a", "aac", "ogg", "wav", "flac", "opus") -> R.drawable.ic_audio
            ext in setOf("mp4", "mkv", "avi", "mov", "webm") -> R.drawable.ic_video
            ext in setOf("jpg", "jpeg", "png", "gif", "webp", "bmp") -> R.drawable.ic_image
            ext in setOf("pdf") -> R.drawable.ic_pdf
            ext in setOf("txt", "md", "log") -> R.drawable.ic_text
            ext in setOf("zip", "rar", "7z", "tar", "gz") -> R.drawable.ic_archive
            ext in setOf("apk") -> R.drawable.ic_apk
            ext in setOf("kt", "java", "py", "js", "ts", "html", "css", "json", "xml") -> R.drawable.ic_code
            else -> R.drawable.ic_file
        }
    }

    private fun updateBreadcrumb() {
        val bb = _b ?: return
        val breadcrumbContainer = bb.llBreadcrumb
        val sd = sandboxDir
        val dd = downloadsDir
        if (sd == null || dd == null) return

        try {
            breadcrumbContainer.removeAllViews()
            val ctx = requireContext()
            val dp = ctx.resources.displayMetrics.density

            val isSandbox = currentDir.absolutePath.startsWith(sd.absolutePath)
            val isDownloads = !isSandbox && dd != null && currentDir.absolutePath.startsWith(dd.absolutePath)
            val rootName = when { isSandbox -> "Sandbox"; isDownloads -> "Downloads"; else -> "Root" }
            val rootPath = when { isSandbox -> sd; isDownloads -> dd!!; else -> currentDir }

            addBreadcrumbItem(breadcrumbContainer, rootName, rootPath, isRoot = true)

            if (currentDir != rootPath) {
                val relPath = currentDir.absolutePath.substring(rootPath.absolutePath.length).trimStart('/')
                if (relPath.isNotEmpty()) {
                    var path = rootPath
                    relPath.split("/").forEach { seg ->
                        path = File(path, seg)
                        addBreadcrumbItem(breadcrumbContainer, seg, path, isRoot = false)
                    }
                }
            }
        } catch (_: Exception) {}
    }

    private fun addBreadcrumbItem(container: ViewGroup, name: String, path: File, isRoot: Boolean) {
        val ctx = container.context
        if (!isRoot) {
            val sep = TextView(ctx).apply {
                text = " / "
                setTextColor(0xFF475569.toInt())
                textSize = 12f
            }
            container.addView(sep)
        }
        val btn = MaterialButton(ctx).apply {
            text = name
            textSize = 12f
            isAllCaps = false
            setPaddingRelative(8, 2, 8, 2)
            setMinWidth(0)
            insetTop = 0
            insetBottom = 0
            cornerRadius = 8
            setBackgroundColor(Color.TRANSPARENT)
            setTextColor(ColorStateList.valueOf(if (path == currentDir) 0xFFE2E8F0.toInt() else 0xFF64748B.toInt()))
            setOnClickListener { openDir(path) }
        }
        container.addView(btn)
    }

    private fun openFile(f: File) {
        try {
            val ctx = try { requireContext() } catch (_: Exception) { return }
            val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", f)
            val mime = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(f.extension) ?: "*/*"
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mime)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, "Open ${f.name}"))
        } catch (e: Exception) { safeToast("Open failed: ${e.message}") }
    }

    private fun showFileOptions(f: File) {
        try {
            val ctx = try { requireContext() } catch (_: Exception) { return }
            val sd = sandboxDir
            val isInSandbox = try { sd != null && f.absolutePath.startsWith(sd.absolutePath) } catch (_: Exception) { false }
            val opts = if (isInSandbox)
                arrayOf("📂 Open", "📤 Share", "💾 Export (SAF)", "✏️ Rename", "📋 Copy path", "🗑️ Delete", "ℹ️ Details")
            else
                arrayOf("📂 Open", "📤 Share", "📁 Copy to Sandbox", "ℹ️ Details")
            android.app.AlertDialog.Builder(ctx)
                .setTitle(f.name)
                .setItems(opts) { _, which ->
                    try {
                        when (opts[which]) {
                            "📂 Open"         -> openFile(f)
                            "📤 Share"        -> shareFile(f)
                            "💾 Export (SAF)" -> {
                                exportFile = f
                                try { exportLauncher?.launch(f.name) ?: safeToast("Export unavailable") } catch (e: Exception) { safeToast(e.message) }
                            }
                            "📁 Copy to Sandbox" -> copyToSandbox(f)
                            "✏️ Rename"        -> renameFile(f)
                            "📋 Copy path"     -> {
                                val cm = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                cm.setPrimaryClip(android.content.ClipData.newPlainText("path", f.absolutePath))
                                safeToast("Path copied")
                            }
                            "🗑️ Delete"       -> deleteFile(f)
                            "ℹ️ Details"      -> showDetails(f)
                        }
                    } catch (_: Exception) {}
                }.show()
        } catch (_: Exception) {}
    }

    private fun importFile(uri: Uri) {
        try {
            val sd = sandboxDir ?: return
            val ctx = try { requireContext() } catch (_: Exception) { return }
            val input = ctx.contentResolver.openInputStream(uri) ?: return
            val name = getDisplayName(uri) ?: "import_${System.currentTimeMillis()}"
            val outFile = File(sd, name)
            FileOutputStream(outFile).use { out -> input.copyTo(out) }
            input.close()
            safeToast("Imported: $name")
            openDir(sd)
        } catch (e: Exception) { safeToast("Import failed: ${e.message}") }
    }

    private fun getDisplayName(uri: Uri): String? {
        return try {
            val ctx = try { requireContext() } catch (_: Exception) { return null }
            ctx.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (cursor.moveToFirst() && idx >= 0) cursor.getString(idx) else null
            }
        } catch (_: Exception) { null }
    }

    private fun exportFileToUri(file: File, uri: Uri) {
        try {
            val ctx = try { requireContext() } catch (_: Exception) { return }
            file.inputStream().use { input ->
                ctx.contentResolver.openOutputStream(uri)?.use { out -> input.copyTo(out) }
            }
            safeToast("Exported ${file.name}")
        } catch (e: Exception) { safeToast("Export failed: ${e.message}") }
    }

    private fun copyToSandbox(f: File) {
        try {
            val sd = sandboxDir ?: return
            val out = File(sd, f.name)
            f.inputStream().use { input -> FileOutputStream(out).use { input.copyTo(it) } }
            safeToast("Copied to Sandbox")
        } catch (e: Exception) { safeToast(e.message) }
    }

    private fun shareFile(f: File) {
        try {
            val ctx = try { requireContext() } catch (_: Exception) { return }
            val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", f)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "*/*"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, "Share ${f.name}"))
        } catch (e: Exception) { safeToast(e.message) }
    }

    private fun deleteFile(f: File) {
        try {
            val ctx = try { requireContext() } catch (_: Exception) { return }
            android.app.AlertDialog.Builder(ctx)
                .setTitle("Delete \"${f.name}\"?")
                .setMessage(if (f.isDirectory) "This will delete the folder and all its contents." else "This action cannot be undone.")
                .setPositiveButton("Delete") { _, _ ->
                    val ok = try { if (f.isDirectory) f.deleteRecursively() else f.delete() } catch (_: Exception) { false }
                    safeToast(if (ok) "Deleted" else "Failed")
                    try { refresh() } catch (_: Exception) {}
                }.setNegativeButton("Cancel", null).show()
        } catch (_: Exception) {}
    }

    private fun renameFile(f: File) {
        try {
            val ctx = try { requireContext() } catch (_: Exception) { return }
            val et = android.widget.EditText(ctx).apply { setText(f.name); selectAll() }
            android.app.AlertDialog.Builder(ctx)
                .setTitle("Rename")
                .setView(et)
                .setPositiveButton("OK") { _, _ ->
                    val newName = et.text.toString().trim()
                    if (newName.isNotEmpty() && newName != f.name) {
                        val newFile = File(f.parentFile, newName)
                        val ok = try { f.renameTo(newFile) } catch (_: Exception) { false }
                        safeToast(if (ok) "Renamed" else "Failed")
                        try { refresh() } catch (_: Exception) {}
                    }
                }.setNegativeButton("Cancel", null).show()
        } catch (_: Exception) {}
    }

    private fun showDetails(f: File) {
        try {
            val ctx = try { requireContext() } catch (_: Exception) { return }
            val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
            val size = if (f.isDirectory) "${f.walkTopDown().count { it.isFile }} files" else formatSize(f.length())
            val info = buildString {
                appendLine("Name:     ${f.name}")
                appendLine("Path:     ${f.absolutePath}")
                appendLine("Size:     $size")
                appendLine("Type:     ${if (f.isDirectory) "Folder" else f.extension.uppercase().ifEmpty { "File" }}")
                appendLine("Modified: ${sdf.format(Date(f.lastModified()))}")
            }
            android.app.AlertDialog.Builder(ctx)
                .setTitle("Details")
                .setMessage(info.trim())
                .setPositiveButton("OK", null)
                .show()
        } catch (_: Exception) {}
    }

    private fun copyFolderToSandbox(sourceUri: Uri) {
        try {
            val sandbox = this.sandboxDir ?: return
            val sourceDoc = androidx.documentfile.provider.DocumentFile.fromTreeUri(requireContext(), sourceUri) ?: return
            safeToast("Importing folder…")
            scope.launch(Dispatchers.IO) {
                try {
                    val count = copyDocumentTreeRecursive(sourceDoc, sandbox)
                    withContext(Dispatchers.Main) {
                        safeToast("Imported $count file(s) to sandbox")
                        refresh()
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) { safeToast("Import failed: ${e.message}") }
                }
            }
        } catch (e: Exception) { safeToast("Import failed: ${e.message}") }
    }

    private suspend fun copyDocumentTreeRecursive(sourceDoc: androidx.documentfile.provider.DocumentFile, destDir: File): Int {
        var count = 0
        sourceDoc.listFiles()?.forEach { item ->
            if (item.isDirectory) {
                val subDir = File(destDir, item.name ?: "folder").apply { mkdirs() }
                count += copyDocumentTreeRecursive(item, subDir)
            } else if (item.isFile) {
                try {
                    val destFile = File(destDir, item.name ?: "file_${System.currentTimeMillis()}")
                    requireContext().contentResolver.openInputStream(item.uri)?.use { input ->
                        FileOutputStream(destFile).use { output -> input.copyTo(output) }
                    }
                    count++
                } catch (_: Exception) {}
            }
        }
        return count
    }

    override fun onDestroyView() { _b = null; super.onDestroyView() }
}