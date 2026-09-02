package com.lightbrowser.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.lightbrowser.R
import com.lightbrowser.databinding.FragmentFilemanagerBinding
import java.io.File
import java.io.FileOutputStream
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
    }

    override fun onCreateView(inflater: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        return try {
            _b = FragmentFilemanagerBinding.inflate(inflater, c, false)
            b.root
        } catch (e: Exception) {
            android.util.Log.e("FileManager", "onCreateView crash", e)
            // fallback to simple TextView to avoid crash
            TextView(requireContext()).apply { text = "File Manager unavailable: ${e.message}" }
        }
    }

    override fun onViewCreated(v: View, s: Bundle?) {
        try {
            // Phase 1 fix: MainActivity container now handles statusBars.top – fragment must not double-pad.
            try {
                val sd = try { File(requireContext().filesDir, "sandbox").apply { if (!exists()) mkdirs() } } catch (_: Exception) { try { File(requireContext().cacheDir, "sandbox").apply { if (!exists()) mkdirs() } } catch (_: Exception) { null } }
                sandboxDir = sd
            } catch (_: Exception) {}
            try {
                downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            } catch (_: Exception) {
                downloadsDir = sandboxDir
            }
            if (sandboxDir == null) try { sandboxDir = File(requireContext().cacheDir, "sandbox").apply { if (!exists()) mkdirs() } } catch (_: Exception) {}
            if (downloadsDir == null) downloadsDir = sandboxDir

            val bb = _b ?: return
            try { bb.recycler.layoutManager = LinearLayoutManager(requireContext()) } catch (_: Exception) {}
            try { bb.btnUp.setOnClickListener { try { navigateUp() } catch (e: Exception) { safeToast(e.message) } } } catch (_: Exception) {}
            try { bb.btnAppFiles.setOnClickListener { try { sandboxDir?.let { openDir(it) } } catch (_: Exception) {} } } catch (_: Exception) {}
            try { bb.btnDownloads.setOnClickListener { try { downloadsDir?.let { openDir(it) } } catch (_: Exception) {} } } catch (_: Exception) {}
            try {
                bb.btnCache.text = "Import"
                bb.btnCache.setOnClickListener { try { importLauncher?.launch(arrayOf("*/*")) ?: safeToast("Import unavailable") } catch (e: Exception) { safeToast(e.message) } }
            } catch (_: Exception) {}
            try { bb.tvPath.setOnClickListener { try { showFileManagerMenu() } catch (_: Exception) {} } } catch (_: Exception) {}
            try { bb.tvPath.setOnLongClickListener { try { showFileManagerMenu() } catch (_: Exception) {}; true } } catch (_: Exception) {}

            try { sandboxDir?.let { openDir(it) } ?: run { bb.empty.visibility = View.VISIBLE; bb.empty.text = "Sandbox unavailable" } } catch (e: Exception) {
                android.util.Log.e("FileManager", "openDir sandbox", e)
                safeToast("Init error: ${e.message}")
            }
        } catch (e: Exception) {
            android.util.Log.e("FileManager", "onViewCreated crash", e)
            try { Toast.makeText(requireContext(), "Files error: ${e.message}", Toast.LENGTH_LONG).show() } catch (_: Exception) {}
        }
    }

    private fun safeToast(msg: String?) {
        try { Toast.makeText(requireContext(), msg ?: "error", Toast.LENGTH_SHORT).show() } catch (_: Exception) {}
    }

    private fun showFileManagerMenu() {
        try {
            val bb = _b ?: return
            val ctx = try { requireContext() } catch (_: Exception) { return }
            val popup = android.widget.PopupMenu(ctx, bb.tvPath)
            popup.menu.add(0, 1, 0, "📥 Import File (SAF → Sandbox)")
            popup.menu.add(0, 2, 0, "📤 Export File (Sandbox → Phone)")
            popup.menu.add(0, 3, 0, "📁 Open Sandbox")
            popup.menu.add(0, 4, 0, "📁 Open Downloads")
            popup.setOnMenuItemClickListener { item ->
                try {
                    when (item.itemId) {
                        1 -> try { importLauncher?.launch(arrayOf("*/*")) } catch (e: Exception) { safeToast(e.message) }
                        2 -> {
                            val cur = currentDir
                            val sd = sandboxDir
                            if (cur != null && sd != null && (cur == sd || cur.absolutePath.startsWith(sd.absolutePath))) {
                                safeToast("Long-press a file to Export")
                            } else {
                                safeToast("Go to Sandbox first")
                                sd?.let { openDir(it) }
                            }
                        }
                        3 -> sandboxDir?.let { openDir(it) }
                        4 -> downloadsDir?.let { openDir(it) }
                    }
                } catch (_: Exception) {}
                true
            }
            popup.show()
        } catch (e: Exception) { android.util.Log.e("FileManager", "menu", e) }
    }

    private fun openDir(dir: File) {
        try {
            val sd = sandboxDir ?: return
            val dd = downloadsDir ?: sd
            val allowed = try { dir.absolutePath.startsWith(sd.absolutePath) || dir.absolutePath.startsWith(dd.absolutePath) || dir == sd || dir == dd } catch (_: Exception) { false }
            val target = try { if (allowed && dir.exists()) dir else sd } catch (_: Exception) { sd }
            currentDir = target
            refresh()
        } catch (_: Exception) { try { refresh() } catch (_: Exception) {} }
    }

    private fun navigateUp() {
        try {
            val cur = currentDir
            val sd = sandboxDir ?: return
            val dd = downloadsDir ?: sd
            if (cur == sd || cur == dd) {
                safeToast("Already at root (Sandbox/Downloads only)")
                return
            }
            val parent = cur.parentFile
            if (parent != null && try { parent.absolutePath.startsWith(sd.absolutePath) || parent.absolutePath.startsWith(dd.absolutePath) } catch (_: Exception) { false }) {
                openDir(parent)
            } else {
                openDir(sd)
            }
        } catch (e: Exception) { safeToast(e.message) }
    }

    private fun refresh() {
        val bb = _b ?: return
        val ctx = try { requireContext() } catch (_: Exception) { return }
        val sd = sandboxDir
        val dd = downloadsDir
        if (sd == null || dd == null) return
        try { bb.tvPath.text = try { currentDir.absolutePath.replace(ctx.filesDir.absolutePath, "[Sandbox]").replace(dd.absolutePath, "[Downloads]") } catch (_: Exception) { currentDir.name } } catch (_: Exception) {}
        val files = try { currentDir.listFiles()?.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() })) ?: emptyList() } catch (_: Exception) { emptyList() }
        try { bb.tvCount.text = "${files.size} items" } catch (_: Exception) {}
        try { bb.empty.visibility = if (files.isEmpty()) View.VISIBLE else View.GONE } catch (_: Exception) {}
        try { bb.recycler.visibility = if (files.isEmpty()) View.GONE else View.VISIBLE } catch (_: Exception) {}
        try {
            bb.recycler.adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
                override fun onCreateViewHolder(p: ViewGroup, t: Int) =
                    object : RecyclerView.ViewHolder(LayoutInflater.from(p.context).inflate(R.layout.item_download, p, false)) {}
                override fun onBindViewHolder(h: RecyclerView.ViewHolder, i: Int) {
                    try {
                        val f = files[i]
                        val title = h.itemView.findViewById<TextView>(R.id.tvTitle)
                        val status = h.itemView.findViewById<TextView>(R.id.tvStatus)
                        val isDir = f.isDirectory
                        title.text = (if (isDir) "📁 " else "📄 ") + f.name
                        val size = if (isDir) "${f.listFiles()?.size ?: 0} items" else "${f.length()/1024} KB"
                        val date = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(f.lastModified()))
                        status.text = "$size • $date"
                        h.itemView.setOnClickListener { try { if (isDir) openDir(f) else openFile(f) } catch (_: Exception) {} }
                        h.itemView.setOnLongClickListener { try { showFileOptions(f) } catch (_: Exception) {}; true }
                    } catch (_: Exception) {}
                }
                override fun getItemCount() = files.size
            }
        } catch (_: Exception) {}
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
            val opts = if (isInSandbox) arrayOf("Open", "Share", "Export (SAF)", "Rename", "Delete", "Details") else arrayOf("Open", "Share", "Copy to Sandbox", "Details")
            android.app.AlertDialog.Builder(ctx)
                .setTitle(f.name)
                .setItems(opts) { _, which ->
                    try {
                        when (opts[which]) {
                            "Open" -> openFile(f)
                            "Share" -> shareFile(f)
                            "Export (SAF)" -> {
                                exportFile = f
                                try { exportLauncher?.launch(f.name) ?: safeToast("Export unavailable") } catch (e: Exception) { safeToast(e.message) }
                            }
                            "Copy to Sandbox" -> copyToSandbox(f)
                            "Rename" -> renameFile(f)
                            "Delete" -> deleteFile(f)
                            "Details" -> showDetails(f)
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
            safeToast("Imported to Sandbox/$name")
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
            val input = file.inputStream()
            val out = ctx.contentResolver.openOutputStream(uri) ?: return
            input.copyTo(out)
            input.close()
            out.close()
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
                .setTitle("Delete ${f.name}?")
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
            val info = "Name: ${f.name}\nPath: ${f.absolutePath}\nSize: ${f.length()} bytes\nDir: ${f.isDirectory}\nModified: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(f.lastModified()))}"
            android.app.AlertDialog.Builder(ctx).setTitle("Details").setMessage(info).setPositiveButton("OK", null).show()
        } catch (_: Exception) {}
    }

    override fun onDestroyView() { _b = null; super.onDestroyView() }
}
