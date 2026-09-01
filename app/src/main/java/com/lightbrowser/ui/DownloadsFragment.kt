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
            try {
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(Uri.parse(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).toString()), "*/*")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(Intent.createChooser(intent, "Open Downloads"))
            } catch (_: Exception) {
                startActivity(Intent(DownloadManager.ACTION_VIEW_DOWNLOADS))
            }
        }
        load()
    }

    private fun load() {
        val ctx = requireContext()
        val dm = ctx.getSystemService(android.content.Context.DOWNLOAD_SERVICE) as DownloadManager
        val q = DownloadManager.Query()
        val c = dm.query(q)
        val items = mutableListOf<Map<String, String>>()
        // also list files in Downloads folder for blob saves
        try {
            val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            dir.listFiles()?.sortedByDescending { it.lastModified() }?.take(30)?.forEach { f ->
                items.add(mapOf("title" to f.name, "status" to "File · ${f.length()/1024} KB · ${java.text.SimpleDateFormat("MM-dd HH:mm").format(java.util.Date(f.lastModified()))}"))
            }
        } catch (_: Exception) {}

        // add DownloadManager entries on top
        val dmItems = mutableListOf<Map<String, String>>()
        if (c != null) {
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
            c.close()
        }
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
                    // try to open file if exists
                    val f = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), m["title"] ?: "")
                    if (f.exists()) {
                        try {
                            val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", f)
                            val intent = Intent(Intent.ACTION_VIEW).apply {
                                setDataAndType(uri, "*/*"); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            startActivity(intent)
                        } catch (_: Exception) {}
                    }
                }
            }
            override fun getItemCount() = all.size
        }
    }

    override fun onResume() { super.onResume(); try { load() } catch (_: Exception) {} }
    override fun onDestroyView() { _b = null; super.onDestroyView() }
}
