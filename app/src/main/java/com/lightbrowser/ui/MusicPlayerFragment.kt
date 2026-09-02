package com.lightbrowser.ui

import android.content.Intent
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
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

    private var folderPicker: ActivityResultLauncher<Uri?>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            folderPicker = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
                if (uri != null) {
                    try {
                        try { requireContext().contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) {}
                        val docFile = DocumentFile.fromTreeUri(requireContext(), uri)
                        val name = docFile?.name ?: "custom"
                        Toast.makeText(requireContext(), "Added $name – scanning…", Toast.LENGTH_SHORT).show()
                        scanWithSaf(uri)
                    } catch (e: Exception) { safeToast(e.message) }
                }
            }
        } catch (_: Exception) {}
    }

    private fun safeToast(m: String?) { try { Toast.makeText(requireContext(), m ?: "error", Toast.LENGTH_SHORT).show() } catch (_: Exception) {} }

    private val updateRunnable = object : Runnable {
        override fun run() {
            try {
                val bb = _b
                val p = player
                if (bb != null && p != null && try { p.isPlaying } catch (_: Exception) { false } && !isSeeking) {
                    val pos = try { p.currentPosition } catch (_: Exception) { 0 }
                    val dur = try { p.duration } catch (_: Exception) { 0 }
                    try { bb.seekBar.max = if (dur > 0) dur else 100 } catch (_: Exception) {}
                    try { bb.seekBar.progress = pos } catch (_: Exception) {}
                    try { bb.tvCurrent.text = fmt(pos) } catch (_: Exception) {}
                    try { bb.tvDuration.text = fmt(dur) } catch (_: Exception) {}
                }
                handler.postDelayed(this, 500)
            } catch (_: Exception) { try { handler.postDelayed(this, 500) } catch (_: Exception) {} }
        }
    }

    override fun onCreateView(inflater: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        return try {
            _b = FragmentMusicBinding.inflate(inflater, c, false)
            b.root
        } catch (e: Exception) {
            android.util.Log.e("Music", "onCreateView", e)
            TextView(requireContext()).apply { text = "Music unavailable: ${e.message}" }
        }
    }

    override fun onViewCreated(v: View, s: Bundle?) {
        try {
            val bb = _b ?: return
            try { bb.recycler.layoutManager = LinearLayoutManager(requireContext()) } catch (e: Exception) { android.util.Log.e("Music", "layoutManager", e) }
            try { bb.btnScan.setOnClickListener { try { scan() } catch (e: Exception) { safeToast(e.message) } } } catch (_: Exception) {}
            try { bb.btnAddFolder.setOnClickListener { try { folderPicker?.launch(null) ?: safeToast("Folder picker unavailable") } catch (e: Exception) { safeToast(e.message) } } } catch (_: Exception) {}
            try { bb.btnStop.setOnClickListener { try { stop() } catch (_: Exception) {} } } catch (_: Exception) {}
            try { bb.btnPlay.setOnClickListener { try { toggle() } catch (_: Exception) {} } } catch (_: Exception) {}
            try { bb.btnPrev.setOnClickListener { try { prev() } catch (_: Exception) {} } } catch (_: Exception) {}
            try { bb.btnNext.setOnClickListener { try { next() } catch (_: Exception) {} } } catch (_: Exception) {}
            try {
                bb.seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) { if (fromUser) try { bb.tvCurrent.text = fmt(p) } catch (_: Exception) {} }
                    override fun onStartTrackingTouch(sb: SeekBar?) { isSeeking = true }
                    override fun onStopTrackingTouch(sb: SeekBar?) { isSeeking = false; try { player?.seekTo(sb?.progress ?: 0) } catch (_: Exception) {} }
                })
            } catch (_: Exception) {}
            try { scan() } catch (e: Exception) { android.util.Log.e("Music", "scan init", e) }
            try { handler.post(updateRunnable) } catch (_: Exception) {}
        } catch (e: Exception) {
            android.util.Log.e("Music", "onViewCreated crash", e)
            safeToast("Music error: ${e.message}")
        }
    }

    private fun scan() {
        val bb = _b ?: return
        val downloads = try { Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS) } catch (_: Exception) { null }
        val baseFiles = try {
            downloads?.listFiles()?.filter { it.isFile && it.extension.lowercase() in setOf("mp3","m4a","aac","ogg","wav","flac","opus") }?.sortedBy { it.name.lowercase() } ?: emptyList()
        } catch (_: Exception) { emptyList<File>() }
        val sandboxMusic = try { File(requireContext().filesDir, "sandbox/music").apply { if (!exists()) mkdirs() } } catch (_: Exception) { try { File(requireContext().cacheDir, "sandbox/music").apply { if (!exists()) mkdirs() } } catch (_: Exception) { null } }
        val sandboxFiles = try { sandboxMusic?.listFiles()?.filter { it.extension.lowercase() in setOf("mp3","m4a","aac","ogg","wav","flac") } ?: emptyList() } catch (_: Exception) { emptyList() }
        val all = try {
            (baseFiles + sandboxFiles + customFolders.flatMap { dir ->
                try { dir.listFiles()?.filter { it.extension.lowercase() in setOf("mp3","m4a","aac","ogg","wav","flac") } ?: emptyList() } catch (_: Exception) { emptyList() }
            }).distinctBy { it.absolutePath }.sortedBy { it.name.lowercase() }
        } catch (_: Exception) { emptyList() }

        queue = all
        try { bb.empty.visibility = if (all.isEmpty()) View.VISIBLE else View.GONE } catch (_: Exception) {}
        try { bb.recycler.visibility = if (all.isEmpty()) View.GONE else View.VISIBLE } catch (_: Exception) {}
        try {
            bb.recycler.adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
                override fun onCreateViewHolder(p: ViewGroup, t: Int) =
                    object : RecyclerView.ViewHolder(LayoutInflater.from(p.context).inflate(R.layout.item_download, p, false)) {}
                override fun onBindViewHolder(h: RecyclerView.ViewHolder, i: Int) {
                    try {
                        val f = all[i]
                        val (title, artist) = getId3(f)
                        h.itemView.findViewById<TextView>(R.id.tvTitle).text = title ?: f.nameWithoutExtension
                        h.itemView.findViewById<TextView>(R.id.tvStatus).text = "${artist ?: f.parentFile?.name ?: "Unknown"} • ${f.length()/1024} KB • ${if (i == currentIndex) "▶ Playing" else "Tap"}"
                        h.itemView.alpha = if (i == currentIndex) 1f else 0.85f
                        h.itemView.setOnClickListener { try { playAt(i) } catch (_: Exception) {} }
                    } catch (_: Exception) {}
                }
                override fun getItemCount() = all.size
            }
        } catch (_: Exception) {}
        try { if (all.isNotEmpty() && currentIndex == -1) bb.tvTitle.text = "${all.size} tracks" } catch (_: Exception) {}
    }

    private fun scanWithSaf(treeUri: Uri) {
        try {
            val ctx = try { requireContext() } catch (_: Exception) { return }
            val docTree = DocumentFile.fromTreeUri(ctx, treeUri) ?: return
            val destDir = try { File(ctx.filesDir, "sandbox/music").apply { mkdirs() } } catch (_: Exception) { return }
            val files = mutableListOf<File>()
            docTree.listFiles().forEach { doc ->
                if (doc.isFile && doc.name?.lowercase()?.endsWith(".mp3") == true) {
                    try {
                        val dest = File(destDir, doc.name ?: "track_${System.currentTimeMillis()}.mp3")
                        ctx.contentResolver.openInputStream(doc.uri)?.use { input ->
                            dest.outputStream().use { input.copyTo(it) }
                        }
                        files.add(dest)
                    } catch (_: Exception) {}
                }
            }
            safeToast("Imported ${files.size} tracks to sandbox")
            try { scan() } catch (_: Exception) {}
        } catch (e: Exception) { safeToast(e.message) }
    }

    private fun getId3(file: File): Pair<String?, String?> {
        return try {
            val mmr = MediaMetadataRetriever()
            try { mmr.setDataSource(file.absolutePath) } catch (e: Exception) { try { mmr.release() } catch (_: Exception) {}; return Pair(null, null) }
            val title = try { mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE) } catch (_: Exception) { null }
            val artist = try { mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST) } catch (_: Exception) { null }
            val hasArt = try { mmr.embeddedPicture } catch (_: Exception) { null }
            if (hasArt != null && currentIndex != -1 && queue.getOrNull(currentIndex) == file) {
                try {
                    val bb = _b
                    if (bb != null) {
                        val bmp = android.graphics.BitmapFactory.decodeByteArray(hasArt, 0, hasArt.size)
                        bb.imgArt.setImageBitmap(bmp)
                        bb.imgArt.visibility = View.VISIBLE
                    }
                } catch (_: Exception) { try { _b?.imgArt?.visibility = View.GONE } catch (_: Exception) {} }
            }
            try { mmr.release() } catch (_: Exception) {}
            Pair(title, artist)
        } catch (_: Exception) { Pair(null, null) }
    }

    private fun playAt(idx: Int) {
        if (idx < 0 || idx >= queue.size) return
        try {
            stopPlayer()
            currentIndex = idx
            val f = queue[idx]
            val (title, artist) = getId3(f)
            val ctx = try { requireContext() } catch (_: Exception) { return }
            player = MediaPlayer().apply {
                try { setDataSource(ctx, Uri.fromFile(f)) } catch (e: Exception) { safeToast("Play failed: ${e.message}"); return }
                setOnPreparedListener {
                    try { start() } catch (_: Exception) {}
                    _b?.let { bb ->
                        try { bb.btnPlay.text = "⏸" } catch (_: Exception) {}
                        try { bb.tvTitle.text = title ?: f.nameWithoutExtension } catch (_: Exception) {}
                        try { bb.tvArtist.text = artist ?: f.parentFile?.name ?: "Downloads" } catch (_: Exception) {}
                        try { bb.tvDuration.text = fmt(duration) } catch (_: Exception) {}
                        try { if (bb.imgArt.visibility != View.VISIBLE) { val (t2,a2)=getId3(f); if(t2!=null) bb.tvTitle.text=t2; if(a2!=null) bb.tvArtist.text=a2 } } catch (_: Exception) {}
                    }
                }
                setOnCompletionListener { try { next() } catch (_: Exception) {} }
                setOnErrorListener { _, what, extra -> safeToast("Error $what/$extra"); true }
                try { prepareAsync() } catch (e: Exception) { safeToast(e.message) }
            }
            try { _b?.recycler?.adapter?.notifyDataSetChanged() } catch (_: Exception) {}
        } catch (e: Exception) { safeToast("Play failed: ${e.message}") }
    }

    private fun toggle() {
        try {
            val p = player
            if (p == null) {
                if (queue.isNotEmpty()) playAt(0) else safeToast("Scan or Add Folder")
                return
            }
            if (try { p.isPlaying } catch (_: Exception) { false }) { try { p.pause() } catch (_: Exception) {}; try { _b?.btnPlay?.text = "▶" } catch (_: Exception) {} }
            else { try { p.start() } catch (_: Exception) {}; try { _b?.btnPlay?.text = "⏸" } catch (_: Exception) {} }
        } catch (_: Exception) {}
    }

    private fun prev() { if (queue.isEmpty()) return; try { playAt(if (currentIndex <= 0) queue.size - 1 else currentIndex - 1) } catch (_: Exception) {} }
    private fun next() { if (queue.isEmpty()) return; try { playAt(if (currentIndex >= queue.size - 1) 0 else currentIndex + 1) } catch (_: Exception) {} }
    private fun stop() {
        try { stopPlayer() } catch (_: Exception) {}
        try { _b?.btnPlay?.text = "▶" } catch (_: Exception) {}
        try { _b?.tvTitle?.text = "Stopped" } catch (_: Exception) {}
        try { _b?.tvArtist?.text = "—" } catch (_: Exception) {}
        try { _b?.imgArt?.visibility = View.GONE } catch (_: Exception) {}
        try { _b?.seekBar?.progress = 0 } catch (_: Exception) {}
        try { _b?.tvCurrent?.text = "0:00" } catch (_: Exception) {}
        currentIndex = -1
        try { _b?.recycler?.adapter?.notifyDataSetChanged() } catch (_: Exception) {}
    }
    private fun stopPlayer() { try { player?.stop(); player?.release() } catch (_: Exception) {}; player = null }
    private fun fmt(ms: Int): String { if (ms <= 0) return "0:00"; val s = ms / 1000; return String.format("%d:%02d", s / 60, s % 60) }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        try { if (hidden && try { player?.isPlaying == true } catch (_: Exception) { false }) { try { player?.pause() } catch (_: Exception) {}; try { _b?.btnPlay?.text = "▶" } catch (_: Exception) {} } } catch (_: Exception) {}
    }
    override fun onDestroyView() { try { handler.removeCallbacks(updateRunnable) } catch (_: Exception) {}; try { stopPlayer() } catch (_: Exception) {}; _b = null; super.onDestroyView() }
}
