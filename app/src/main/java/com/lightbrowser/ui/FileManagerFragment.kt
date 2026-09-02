package com.lightbrowser.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.DocumentsContract
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
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
    private lateinit var sandboxDir: File
    private lateinit var downloadsDir: File

    // SAF launchers
    private val importLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) importFile(uri)
    }
    private var exportFile: File? = null
    private val exportLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri: Uri? ->
        if (uri != null && exportFile != null) exportFileToUri(exportFile!!, uri)
    }

    override fun onCreateView(inflater: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        _b = FragmentFilemanagerBinding.inflate(inflater, c, false)
        return b.root
    }

    override fun onViewCreated(v: View, s: Bundle?) {
        // Phase 1: Fix status bar overlap for File Manager top bar
        try {
            androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(b.root) { view, insets ->
                val statusBars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.statusBars())
                view.setPadding(0, statusBars.top, 0, 0)
                insets
            }
            androidx.core.view.ViewCompat.requestApplyInsets(b.root)
        } catch (_: Exception) {}
        sandboxDir = File(requireContext().filesDir, "sandbox").apply { if (!exists()) mkdirs() }
        downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        b.recycler.layoutManager = LinearLayoutManager(requireContext())
        b.btnUp.setOnClickListener { navigateUp() }
        b.btnAppFiles.setOnClickListener { openDir(sandboxDir) }
        b.btnDownloads.setOnClickListener { openDir(downloadsDir) }
        b.btnCache.setOnClickListener { openDir(sandboxDir) }
        // Replace Cache button with Import/Export in sandboxed mode – keep for now but repurpose
        b.btnCache.text = "Import"
        b.btnCache.setOnClickListener { importLauncher.launch(arrayOf("*/*")) }

        // Add 3-dot menu for Import/Export
        b.tvPath.setOnClickListener { showFileManagerMenu() }
        // Long press path for export
        b.tvPath.setOnLongClickListener { showFileManagerMenu(); true }

        // Initially show sandbox
        openDir(sandboxDir)
    }

    private fun showFileManagerMenu() {
        val popup = android.widget.PopupMenu(requireContext(), b.tvPath)
        popup.menu.add(0, 1, 0, "📥 Import File (SAF → Sandbox)")
        popup.menu.add(0, 2, 0, "📤 Export File (Sandbox → Phone)")
        popup.menu.add(0, 3, 0, "📁 Open Sandbox")
        popup.menu.add(0, 4, 0, "📁 Open Downloads")
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> importLauncher.launch(arrayOf("*/*"))
                2 -> {
                    if (currentDir == sandboxDir || currentDir.absolutePath.startsWith(sandboxDir.absolutePath)) {
                        Toast.makeText(requireContext(), "Long-press a file to Export", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(requireContext(), "Go to Sandbox first", Toast.LENGTH_SHORT).show()
                        openDir(sandboxDir)
                    }
                }
                3 -> openDir(sandboxDir)
                4 -> openDir(downloadsDir)
            }
            true
        }
        popup.show()
    }

    private fun openDir(dir: File) {
        // Sandboxed: only allow sandbox and Downloads
        val allowed = dir.absolutePath.startsWith(sandboxDir.absolutePath) || dir.absolutePath.startsWith(downloadsDir.absolutePath) || dir == sandboxDir || dir == downloadsDir
        val target = if (allowed && dir.exists()) dir else sandboxDir
        currentDir = target
        refresh()
    }

    private fun navigateUp() {
        // Don't go above sandbox or Downloads root
        if (currentDir == sandboxDir || currentDir == downloadsDir) {
            Toast.makeText(requireContext(), "Already at root (Sandbox/Downloads only)", Toast.LENGTH_SHORT).show()
            return
        }
        val parent = currentDir.parentFile
        if (parent != null && (parent.absolutePath.startsWith(sandboxDir.absolutePath) || parent.absolutePath.startsWith(downloadsDir.absolutePath))) {
            openDir(parent)
        } else {
            openDir(sandboxDir)
        }
    }

    private fun refresh() {
        val ctx = requireContext()
        b.tvPath.text = currentDir.absolutePath.replace(ctx.filesDir.absolutePath, "[Sandbox]").replace(downloadsDir.absolutePath, "[Downloads]")
        val files = try { currentDir.listFiles()?.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() })) ?: emptyList() } catch (_: Exception) { emptyList() }
        b.tvCount.text = "${files.size} items"
        b.empty.visibility = if (files.isEmpty()) View.VISIBLE else View.GONE
        b.recycler.visibility = if (files.isEmpty()) View.GONE else View.VISIBLE
        b.recycler.adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            override fun onCreateViewHolder(p: ViewGroup, t: Int) =
                object : RecyclerView.ViewHolder(LayoutInflater.from(p.context).inflate(R.layout.item_download, p, false)) {}
            override fun onBindViewHolder(h: RecyclerView.ViewHolder, i: Int) {
                val f = files[i]
                val title = h.itemView.findViewById<TextView>(R.id.tvTitle)
                val status = h.itemView.findViewById<TextView>(R.id.tvStatus)
                val isDir = f.isDirectory
                title.text = (if (isDir) "📁 " else "📄 ") + f.name
                val size = if (isDir) "${f.listFiles()?.size ?: 0} items" else "${f.length()/1024} KB"
                val date = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(f.lastModified()))
                status.text = "$size • $date"
                h.itemView.setOnClickListener {
                    if (isDir) openDir(f) else openFile(f)
                }
                h.itemView.setOnLongClickListener {
                    showFileOptions(f)
                    true
                }
            }
            override fun getItemCount() = files.size
        }
    }

    private fun openFile(f: File) {
        try {
            val uri = FileProvider.getUriForFile(requireContext(), "${requireContext().packageName}.fileprovider", f)
            val mime = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(f.extension) ?: "*/*"
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mime)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, "Open ${f.name}"))
        } catch (e: Exception) {
            Toast.makeText(requireContext(), "Open failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun showFileOptions(f: File) {
        val opts = if (f.absolutePath.startsWith(sandboxDir.absolutePath)) {
            arrayOf("Open", "Share", "Export (SAF)", "Rename", "Delete", "Details")
        } else {
            arrayOf("Open", "Share", "Copy to Sandbox", "Details")
        }
        android.app.AlertDialog.Builder(requireContext())
            .setTitle(f.name)
            .setItems(opts) { _, which ->
                when (opts[which]) {
                    "Open" -> openFile(f)
                    "Share" -> shareFile(f)
                    "Export (SAF)" -> {
                        exportFile = f
                        exportLauncher.launch(f.name)
                    }
                    "Copy to Sandbox" -> copyToSandbox(f)
                    "Rename" -> renameFile(f)
                    "Delete" -> deleteFile(f)
                    "Details" -> showDetails(f)
                }
            }
            .show()
    }

    private fun importFile(uri: Uri) {
        try {
            val input = requireContext().contentResolver.openInputStream(uri) ?: return
            val name = getDisplayName(uri) ?: "import_${System.currentTimeMillis()}"
            val outFile = File(sandboxDir, name)
            FileOutputStream(outFile).use { out -> input.copyTo(out) }
            input.close()
            Toast.makeText(requireContext(), "Imported to Sandbox/$name", Toast.LENGTH_LONG).show()
            openDir(sandboxDir)
        } catch (e: Exception) {
            Toast.makeText(requireContext(), "Import failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun getDisplayName(uri: Uri): String? {
        return try {
            requireContext().contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (cursor.moveToFirst() && idx >= 0) cursor.getString(idx) else null
            }
        } catch (_: Exception) { null }
    }

    private fun exportFileToUri(file: File, uri: Uri) {
        try {
            val input = file.inputStream()
            val out = requireContext().contentResolver.openOutputStream(uri) ?: return
            input.copyTo(out)
            input.close()
            out.close()
            Toast.makeText(requireContext(), "Exported ${file.name}", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(requireContext(), "Export failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun copyToSandbox(f: File) {
        try {
            val out = File(sandboxDir, f.name)
            f.inputStream().use { input -> FileOutputStream(out).use { input.copyTo(it) } }
            Toast.makeText(requireContext(), "Copied to Sandbox", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) { Toast.makeText(requireContext(), e.message, Toast.LENGTH_LONG).show() }
    }

    private fun shareFile(f: File) {
        try {
            val uri = FileProvider.getUriForFile(requireContext(), "${requireContext().packageName}.fileprovider", f)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "*/*"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, "Share ${f.name}"))
        } catch (e: Exception) { Toast.makeText(requireContext(), e.message, Toast.LENGTH_LONG).show() }
    }

    private fun deleteFile(f: File) {
        android.app.AlertDialog.Builder(requireContext())
            .setTitle("Delete ${f.name}?")
            .setPositiveButton("Delete") { _, _ ->
                val ok = try { if (f.isDirectory) f.deleteRecursively() else f.delete() } catch (_: Exception) { false }
                Toast.makeText(requireContext(), if (ok) "Deleted" else "Failed", Toast.LENGTH_SHORT).show()
                refresh()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun renameFile(f: File) {
        val et = android.widget.EditText(requireContext()).apply { setText(f.name); selectAll() }
        android.app.AlertDialog.Builder(requireContext())
            .setTitle("Rename")
            .setView(et)
            .setPositiveButton("OK") { _, _ ->
                val newName = et.text.toString().trim()
                if (newName.isNotEmpty() && newName != f.name) {
                    val newFile = File(f.parentFile, newName)
                    val ok = f.renameTo(newFile)
                    Toast.makeText(requireContext(), if (ok) "Renamed" else "Failed", Toast.LENGTH_SHORT).show()
                    refresh()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showDetails(f: File) {
        val info = """
            Name: ${f.name}
            Path: ${f.absolutePath}
            Size: ${f.length()} bytes
            Dir: ${f.isDirectory}
            Modified: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(f.lastModified()))}
        """.trimIndent()
        android.app.AlertDialog.Builder(requireContext())
            .setTitle("Details")
            .setMessage(info)
            .setPositiveButton("OK", null)
            .show()
    }

    override fun onDestroyView() { _b = null; super.onDestroyView() }
}
