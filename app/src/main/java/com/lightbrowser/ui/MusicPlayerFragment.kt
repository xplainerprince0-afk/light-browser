package com.lightbrowser.ui

import android.content.Intent
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.documentfile.provider.DocumentFile
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.lightbrowser.R
import com.lightbrowser.databinding.FragmentMusicBinding
import java.io.File

class MusicPlayerFragment : Fragment() {
    private var _b: FragmentMusicBinding? = null
    private val b get() = _b!!
    private var player: MediaPlayer? = null
    private var queue: List<File> = emptyList()
    private var currentIndex = -1
    private val handler = Handler(Looper.getMainLooper())
    private var isSeeking = false
    private var customFolders: MutableList<File> = mutableListOf()

    private val folderPicker = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri != null) {
            try {
                requireContext().contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                val docFile = DocumentFile.fromTreeUri(requireContext(), uri)
                val name = docFile?.name ?: "custom"
                // For simplicity, just scan that uri's files via DocumentFile and copy to queue as File wrappers
                // Since we can't get File from SAF directly, we list via DocumentFile
                val files = mutableListOf<File>()
                // Fallback: scan Downloads + try to list via DocumentFile and create temp File refs
                // For now, just show toast and scan that folder via DocumentFile
                Toast.makeText(requireContext(), "Added $name – scanning…", Toast.LENGTH_SHORT).show()
                scanWithSaf(uri)
            } catch (e: Exception) { Toast.makeText(requireContext(), e.message, Toast.LENGTH_LONG).show() }
        }
    }

    private val updateRunnable = object : Runnable {
        override fun run() {
            val p = player
            if (p != null && p.isPlaying && !isSeeking) {
                val pos = p.currentPosition
                val dur = p.duration
                b.seekBar.max = if (dur > 0) dur else 100
                b.seekBar.progress = pos
                b.tvCurrent.text = fmt(pos)
                b.tvDuration.text = fmt(dur)
            }
            handler.postDelayed(this, 500)
        }
    }

    override fun onCreateView(inflater: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        _b = FragmentMusicBinding.inflate(inflater, c, false)
        return b.root
    }

    override fun onViewCreated(v: View, s: Bundle?) {
        b.recycler.layoutManager = LinearLayoutManager(requireContext())
        b.btnScan.setOnClickListener { scan() }
        b.btnAddFolder.setOnClickListener { folderPicker.launch(null) }
        b.btnStop.setOnClickListener { stop() }
        b.btnPlay.setOnClickListener { toggle() }
        b.btnPrev.setOnClickListener { prev() }
        b.btnNext.setOnClickListener { next() }
        b.seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) { if (fromUser) b.tvCurrent.text = fmt(p) }
            override fun onStartTrackingTouch(sb: SeekBar?) { isSeeking = true }
            override fun onStopTrackingTouch(sb: SeekBar?) { isSeeking = false; player?.seekTo(sb?.progress ?: 0) }
        })
        scan()
        handler.post(updateRunnable)
    }

    private fun scan() {
        val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val baseFiles = try {
            downloads.listFiles()?.filter { it.isFile && it.extension.lowercase() in setOf("mp3","m4a","aac","ogg","wav","flac","opus") }?.sortedBy { it.name.lowercase() } ?: emptyList()
        } catch (_: Exception) { emptyList<File>() }
        // also include sandbox music folder
        val sandboxMusic = File(requireContext().filesDir, "sandbox/music").apply { if (!exists()) mkdirs() }
        val sandboxFiles = try { sandboxMusic.listFiles()?.filter { it.extension.lowercase() in setOf("mp3","m4a","aac","ogg","wav","flac") } ?: emptyList() } catch (_: Exception) { emptyList() }
        val all = (baseFiles + sandboxFiles + customFolders.flatMap { dir ->
            try { dir.listFiles()?.filter { it.extension.lowercase() in setOf("mp3","m4a","aac","ogg","wav","flac") } ?: emptyList() } catch (_: Exception) { emptyList() }
        }).distinctBy { it.absolutePath }.sortedBy { it.name.lowercase() }

        queue = all
        b.empty.visibility = if (all.isEmpty()) View.VISIBLE else View.GONE
        b.recycler.visibility = if (all.isEmpty()) View.GONE else View.VISIBLE
        b.recycler.adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            override fun onCreateViewHolder(p: ViewGroup, t: Int) =
                object : RecyclerView.ViewHolder(LayoutInflater.from(p.context).inflate(R.layout.item_download, p, false)) {}
            override fun onBindViewHolder(h: RecyclerView.ViewHolder, i: Int) {
                val f = all[i]
                val (title, artist) = getId3(f)
                h.itemView.findViewById<TextView>(R.id.tvTitle).text = title ?: f.nameWithoutExtension
                h.itemView.findViewById<TextView>(R.id.tvStatus).text = "${artist ?: f.parentFile?.name ?: "Unknown"} • ${f.length()/1024} KB • ${if (i == currentIndex) "▶ Playing" else "Tap"}"
                h.itemView.alpha = if (i == currentIndex) 1f else 0.85f
                h.itemView.setOnClickListener { playAt(i) }
            }
            override fun getItemCount() = all.size
        }
        if (all.isNotEmpty() && currentIndex == -1) b.tvTitle.text = "${all.size} tracks"
    }

    private fun scanWithSaf(treeUri: Uri) {
        try {
            val docTree = DocumentFile.fromTreeUri(requireContext(), treeUri) ?: return
            val files = mutableListOf<File>()
            // DocumentFile cannot be directly converted to File, so we copy SAF files to sandbox for playback
            val destDir = File(requireContext().filesDir, "sandbox/music").apply { mkdirs() }
            docTree.listFiles().forEach { doc ->
                if (doc.isFile && doc.name?.lowercase()?.endsWith(".mp3") == true) {
                    try {
                        val dest = File(destDir, doc.name ?: "track_${System.currentTimeMillis()}.mp3")
                        requireContext().contentResolver.openInputStream(doc.uri)?.use { input ->
                            dest.outputStream().use { input.copyTo(it) }
                        }
                        files.add(dest)
                    } catch (_: Exception) {}
                }
            }
            Toast.makeText(requireContext(), "Imported ${files.size} tracks to sandbox", Toast.LENGTH_LONG).show()
            scan()
        } catch (e: Exception) { Toast.makeText(requireContext(), e.message, Toast.LENGTH_LONG).show() }
    }

    private fun getId3(file: File): Pair<String?, String?> {
        return try {
            val mmr = MediaMetadataRetriever()
            mmr.setDataSource(file.absolutePath)
            val title = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
            val artist = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
            val hasArt = mmr.embeddedPicture
            if (hasArt != null && currentIndex != -1 && queue.getOrNull(currentIndex) == file) {
                try {
                    val bmp = android.graphics.BitmapFactory.decodeByteArray(hasArt, 0, hasArt.size)
                    b.imgArt.setImageBitmap(bmp)
                    b.imgArt.visibility = View.VISIBLE
                } catch (_: Exception) { b.imgArt.visibility = View.GONE }
            }
            mmr.release()
            Pair(title, artist)
        } catch (_: Exception) { Pair(null, null) }
    }

    private fun playAt(idx: Int) {
        if (idx < 0 || idx >= queue.size) return
        try {
            stopPlayer()
            currentIndex = idx
            val f = queue[idx]
            // ID3
            val (title, artist) = getId3(f)
            player = MediaPlayer().apply {
                setDataSource(requireContext(), Uri.fromFile(f))
                setOnPreparedListener {
                    start()
                    b.btnPlay.text = "⏸"
                    b.tvTitle.text = title ?: f.nameWithoutExtension
                    b.tvArtist.text = artist ?: f.parentFile?.name ?: "Downloads"
                    b.tvDuration.text = fmt(duration)
                    // try to show art if not already
                    if (b.imgArt.visibility != View.VISIBLE) {
                        val (t2, a2) = getId3(f)
                        if (t2 != null) b.tvTitle.text = t2
                        if (a2 != null) b.tvArtist.text = a2
                    }
                }
                setOnCompletionListener { next() }
                setOnErrorListener { _, what, extra ->
                    Toast.makeText(requireContext(), "Error $what/$extra", Toast.LENGTH_SHORT).show()
                    true
                }
                prepareAsync()
            }
            b.recycler.adapter?.notifyDataSetChanged()
        } catch (e: Exception) {
            Toast.makeText(requireContext(), "Play failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun toggle() {
        val p = player
        if (p == null) {
            if (queue.isNotEmpty()) playAt(0) else Toast.makeText(requireContext(), "Scan or Add Folder", Toast.LENGTH_SHORT).show()
            return
        }
        if (p.isPlaying) { p.pause(); b.btnPlay.text = "▶" }
        else { p.start(); b.btnPlay.text = "⏸" }
    }

    private fun prev() { if (queue.isEmpty()) return; playAt(if (currentIndex <= 0) queue.size - 1 else currentIndex - 1) }
    private fun next() { if (queue.isEmpty()) return; playAt(if (currentIndex >= queue.size - 1) 0 else currentIndex + 1) }
    private fun stop() {
        stopPlayer()
        b.btnPlay.text = "▶"
        b.tvTitle.text = "Stopped"
        b.tvArtist.text = "—"
        b.imgArt.visibility = View.GONE
        b.seekBar.progress = 0
        b.tvCurrent.text = "0:00"
        currentIndex = -1
        b.recycler.adapter?.notifyDataSetChanged()
    }
    private fun stopPlayer() { try { player?.stop(); player?.release() } catch (_: Exception) {}; player = null }
    private fun fmt(ms: Int): String { if (ms <= 0) return "0:00"; val s = ms / 1000; return String.format("%d:%02d", s / 60, s % 60) }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (hidden && player?.isPlaying == true) { player?.pause(); b.btnPlay.text = "▶" }
    }
    override fun onDestroyView() { handler.removeCallbacks(updateRunnable); stopPlayer(); _b = null; super.onDestroyView() }
}
