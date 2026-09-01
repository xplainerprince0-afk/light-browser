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
import androidx.core.content.FileProvider
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.lightbrowser.R
import com.lightbrowser.databinding.FragmentFilemanagerBinding
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class FileManagerFragment : Fragment() {
    private var _b: FragmentFilemanagerBinding? = null
    private val b get() = _b!!
    private var currentDir: File = File("/")

    override fun onCreateView(inflater: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        _b = FragmentFilemanagerBinding.inflate(inflater, c, false)
        return b.root
    }

    override fun onViewCreated(v: View, s: Bundle?) {
        b.recycler.layoutManager = LinearLayoutManager(requireContext())
        b.btnUp.setOnClickListener { navigateUp() }
        b.btnAppFiles.setOnClickListener { openDir(requireContext().filesDir) }
        b.btnDownloads.setOnClickListener { openDir(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)) }
        b.btnCache.setOnClickListener { openDir(requireContext().cacheDir) }

        // start at app files
        openDir(requireContext().filesDir)
    }

    private fun openDir(dir: File) {
        val target = try { if (dir.exists()) dir else requireContext().filesDir } catch (_: Exception) { requireContext().filesDir }
        currentDir = target
        refresh()
    }

    private fun navigateUp() {
        val parent = currentDir.parentFile
        if (parent != null && parent.exists()) openDir(parent)
        else Toast.makeText(requireContext(), "Already at root", Toast.LENGTH_SHORT).show()
    }

    private fun refresh() {
        val ctx = requireContext()
        b.tvPath.text = currentDir.absolutePath
        val files = try { currentDir.listFiles()?.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() })) ?: emptyList() } catch (_: Exception) { emptyList() }
        b.tvCount.text = "${files.size} items"
        b.empty.visibility = if (files.isEmpty()) View.VISIBLE else View.GONE
        b.recycler.visibility = if (files.isEmpty()) View.GONE else View.VISIBLE
        b.recycler.adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            override fun onCreateViewHolder(p: ViewGroup, t: Int): RecyclerView.ViewHolder {
                val view = LayoutInflater.from(p.context).inflate(R.layout.item_download, p, false)
                return object : RecyclerView.ViewHolder(view) {}
            }
            override fun onBindViewHolder(h: RecyclerView.ViewHolder, i: Int) {
                val f = files[i]
                val title = h.itemView.findViewById<TextView>(R.id.tvTitle)
                val status = h.itemView.findViewById<TextView>(R.id.tvStatus)
                val isDir = f.isDirectory
                title.text = (if (isDir) "📁 " else "📄 ") + f.name
                val size = if (isDir) "${f.listFiles()?.size ?: 0} items" else "${f.length()/1024} KB"
                val date = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(f.lastModified()))
                status.text = "$size • $date • ${if (f.canRead()) "r" else "-"}${if (f.canWrite()) "w" else "-"}"
                h.itemView.setOnClickListener {
                    if (isDir) openDir(f)
                    else openFile(f)
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
        val opts = arrayOf("Share", "Delete", "Rename", "Details")
        android.app.AlertDialog.Builder(requireContext())
            .setTitle(f.name)
            .setItems(opts) { _, which ->
                when (which) {
                    0 -> shareFile(f)
                    1 -> deleteFile(f)
                    2 -> renameFile(f)
                    3 -> showDetails(f)
                }
            }
            .show()
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
            .setMessage("This cannot be undone.")
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
            Read: ${f.canRead()} Write: ${f.canWrite()} Exec: ${f.canExecute()}
            Modified: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(f.lastModified()))}
            Files: ${if (f.isDirectory) f.listFiles()?.size ?: 0 else "-"}
        """.trimIndent()
        android.app.AlertDialog.Builder(requireContext())
            .setTitle("Details")
            .setMessage(info)
            .setPositiveButton("OK", null)
            .setNeutralButton("Copy path") { _, _ ->
                val cm = requireContext().getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                cm.setPrimaryClip(android.content.ClipData.newPlainText("path", f.absolutePath))
                Toast.makeText(requireContext(), "Copied", Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    override fun onDestroyView() { _b = null; super.onDestroyView() }
}
