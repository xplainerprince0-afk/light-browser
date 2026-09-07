package com.lightbrowser.ui

import android.app.DownloadManager
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.FileProvider
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.lightbrowser.R
import com.lightbrowser.databinding.FragmentDownloadsBinding
import android.os.Environment
import java.io.File

class DownloadsFragment : Fragment() {
    private var _b: FragmentDownloadsBinding? = null
    private val b get() = _b!!

    override fun onCreateView(inflater: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        _b = FragmentDownloadsBinding.inflate(inflater, c, false)
        return b.root
    }

    override fun onViewCreated(v: View, s: Bundle?) {
        b.recycler.layoutManager = LinearLayoutManager(requireContext())
        b.btnOpenFolder.setOnClickListener {
            // DownloadHelper saves into sandbox/Downloads (private storage), NOT public Downloads.
            // Old code tried to VIEW the public folder with a broken file:// Uri — always failed.
            try {
                (activity as? com.lightbrowser.MainActivity)?.switchToTab(com.lightbrowser.R.id.nav_filemanager)
            } catch (_: Exception) {
                try { startActivity(Intent(DownloadManager.ACTION_VIEW_DOWNLOADS)) } catch (_: Exception) {}
            }
        }
        load()
    }

    private fun sandboxDownloadsDir(): File {
        return try {
            File(requireContext().filesDir, "sandbox/Downloads").apply { if (!exists()) mkdirs() }
        } catch (_: Exception) {
            File(requireContext().cacheDir, "sandbox/Downloads").apply { if (!exists()) mkdirs() }
        }
    }

    private fun load() {
        val ctx = requireContext()
        val items = mutableListOf<Map<String, String>>()
        // List the REAL folder: sandbox/Downloads (where BlobBridge + enqueue save).
        try {
            val dir = sandboxDownloadsDir()
            dir.listFiles()?.sortedByDescending { it.lastModified() }?.take(30)?.forEach { f ->
                items.add(mapOf("title" to f.name, "status" to "File · ${f.length()/1024} KB · ${java.text.SimpleDateFormat("MM-dd HH:mm").format(java.util.Date(f.lastModified()))}"))
            }
        } catch (_: Exception) {}

        // add DownloadManager entries on top (guarded close — old code leaked the cursor on throw)
        val dmItems = mutableListOf<Map<String, String>>()
        try {
            val dm = ctx.getSystemService(android.content.Context.DOWNLOAD_SERVICE) as DownloadManager
            dm.query(DownloadManager.Query())?.use { c ->
                while (c.moveToNext()) {
                    try {
                        val title = c.getString(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TITLE)) ?: "download"
                        val status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                        val st = when (status) {
                            DownloadManager.STATUS_SUCCESSFUL -> "✓ Completed"
                            DownloadManager.STATUS_RUNNING -> "↓ Downloading"
                            DownloadManager.STATUS_FAILED -> "✗ Failed"
                            DownloadManager.STATUS_PAUSED -> "⏸ Paused"
                            else -> "Pending $status"
                        }
                        dmItems.add(mapOf("title" to title, "status" to st))
                    } catch (_: Exception) {}
                }
            }
        } catch (_: Exception) {}
        val all = dmItems + items
        b.empty.visibility = if (all.isEmpty()) View.VISIBLE else View.GONE
        b.recycler.visibility = if (all.isEmpty()) View.GONE else View.VISIBLE
        b.recycler.adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            override fun onCreateViewHolder(p: ViewGroup, t: Int) =
                object : RecyclerView.ViewHolder(LayoutInflater.from(p.context).inflate(R.layout.item_download, p, false)) {}
            override fun onBindViewHolder(h: RecyclerView.ViewHolder, i: Int) {
                val m = all[i]
                h.itemView.findViewById<TextView>(R.id.tvTitle).text = m["title"]
                h.itemView.findViewById<TextView>(R.id.tvStatus).text = m["status"]
                h.itemView.setOnClickListener {
                    // Open from sandbox/Downloads via FileProvider (old code looked in public
                    // Downloads, which is empty since we save privately -> tap did nothing).
                    val name = m["title"] ?: return@setOnClickListener
                    val f = File(sandboxDownloadsDir(), name)
                    if (f.exists()) {
                        try {
                            val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", f)
                            val mime = android.webkit.MimeTypeMap.getSingleton()
                                .getMimeTypeFromExtension(f.extension) ?: "*/*"
                            val intent = Intent(Intent.ACTION_VIEW).apply {
                                setDataAndType(uri, mime); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            startActivity(Intent.createChooser(intent, "Open $name"))
                        } catch (e: Exception) {
                            try { android.widget.Toast.makeText(ctx, "Open failed: ${e.message}", android.widget.Toast.LENGTH_SHORT).show() } catch (_: Exception) {}
                        }
                    }
                }
            }
            override fun getItemCount() = all.size
        }
    }

    override fun onResume() { super.onResume(); try { load() } catch (_: Exception) {} }
    override fun onDestroyView() { _b = null; super.onDestroyView() }
}
