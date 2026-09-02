package com.lightbrowser.ui

import android.content.Intent
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.PopupMenu
import android.widget.ProgressBar
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.Comparator

class MusicPlayerFragment : Fragment() {

    private var _b: FragmentMusicBinding? = null
    private val b get() = _b!!

    private var player: MediaPlayer? = null
    private var chapters: List<Chapter> = emptyList()
    private var currentChapterIndex = -1
    private val handler = Handler(Looper.getMainLooper())
    private var isSeeking = false

    // Audiobook library structure
    private var libraryUri: Uri? = null
    private var novels: List<Novel> = emptyList()
    private var currentNovelIndex = -1

    private var folderPicker: ActivityResultLauncher<Uri?>? = null
    private var importFolderPicker: ActivityResultLauncher<Uri?>? = null

    private val scope = CoroutineScope(Dispatchers.Main)

    data class Novel(
        val name: String,
        val directory: DocumentFile,
        val cover: DocumentFile?,
        val chapters: List<Chapter>
    )

    data class Chapter(
        val title: String,
        val file: DocumentFile,
        val index: Int,
        val novelName: String
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Restricted folder picker - only allows picking from sandbox
        try {
            folderPicker = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
                if (uri != null) {
                    try {
                        requireContext().contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        libraryUri = uri
                        scanLibrary(uri)
                    } catch (e: Exception) { safeToast(e.message) }
                }
            }
        } catch (_: Exception) {}

        // Import folder picker for File Manager
        try {
            importFolderPicker = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
                if (uri != null) {
                    try {
                        requireContext().contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        copyFolderToSandbox(uri)
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
            Log.e("Audiobook", "onCreateView", e)
            TextView(requireContext()).apply { text = "Audiobook unavailable: ${e.message}" }
        }
    }

    override fun onViewCreated(v: View, s: Bundle?) {
        try {
            val bb = _b ?: return
            try { bb.recycler.layoutManager = LinearLayoutManager(requireContext()) } catch (e: Exception) { Log.e("Audiobook", "layoutManager", e) }
            try { bb.btnPickLibrary.setOnClickListener { try { pickLibraryFromSandbox() } catch (e: Exception) { safeToast(e.message) } } } catch (_: Exception) {}
            try { bb.btnSwitchNovel.setOnClickListener { try { showNovelSwitcher() } catch (e: Exception) { safeToast(e.message) } } } catch (_: Exception) {}
            try { bb.btnStop.setOnClickListener { try { stop() } catch (_: Exception) {} } } catch (_: Exception) {}
            try { bb.btnOverflow.setOnClickListener { try { showOverflowMenu() } catch (_: Exception) {} } } catch (_: Exception) {}
            try { bb.btnPlay.setOnClickListener { try { toggle() } catch (_: Exception) {} } } catch (_: Exception) {}
            try { bb.btnPrev.setOnClickListener { try { prevChapter() } catch (_: Exception) {} } } catch (_: Exception) {}
            try { bb.btnNext.setOnClickListener { try { nextChapter() } catch (_: Exception) {} } } catch (_: Exception) {}
            try { bb.btnSeekBack.setOnClickListener { try { seekRelative(-10000) } catch (_: Exception) {} } } catch (_: Exception) {}
            try { bb.btnSeekForward.setOnClickListener { try { seekRelative(10000) } catch (_: Exception) {} } } catch (_: Exception) {}
            try {
                bb.seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) { if (fromUser) try { bb.tvCurrent.text = fmt(p) } catch (_: Exception) {} }
                    override fun onStartTrackingTouch(sb: SeekBar?) { isSeeking = true }
                    override fun onStopTrackingTouch(sb: SeekBar?) { isSeeking = false; try { player?.seekTo(sb?.progress ?: 0) } catch (_: Exception) {} }
                })
            } catch (_: Exception) {}
            try { handler.post(updateRunnable) } catch (_: Exception) {}
            loadPersistedLibrary()
        } catch (e: Exception) {
            Log.e("Audiobook", "onViewCreated crash", e)
            safeToast("Audiobook error: ${e.message}")
        }
    }

    private fun loadPersistedLibrary() {
        // Could load from SharedPreferences if needed
    }

    private fun pickLibraryFromSandbox() {
        val sandboxDir = getSandboxDir()
        if (sandboxDir == null) {
            safeToast("Sandbox unavailable")
            return
        }

        // Create a document tree URI for the sandbox
        val sandboxUri = Uri.fromFile(sandboxDir)
        
        // Show a custom dialog to pick from sandbox subdirectories
        showSandboxLibraryPicker(sandboxDir)
    }

    private fun getSandboxDir(): File? {
        return try {
            File(requireContext().filesDir, "sandbox").apply { if (!exists()) mkdirs() }
        } catch (_: Exception) {
            try {
                File(requireContext().cacheDir, "sandbox").apply { if (!exists()) mkdirs() }
            } catch (_: Exception) { null }
        }
    }

    private fun showSandboxLibraryPicker(sandboxDir: File) {
        val dirs: Array<File> = sandboxDir.listFiles()?.filter { it.isDirectory } ?: emptyArray()
        
        if (dirs.isEmpty()) {
            safeToast("No folders in sandbox. Use 'Import Folder' in File Manager to add your library.")
            return
        }

        val items = dirs.map { dir -> dir.name }.toTypedArray()
        
        android.app.AlertDialog.Builder(requireContext())
            .setTitle("Pick Library Root (from Sandbox)")
            .setItems(items) { dialog: android.content.DialogInterface, which: Int ->
                val selectedDir = dirs[which]
                val uri = Uri.fromFile(selectedDir)
                libraryUri = uri
                scanLibraryFromFile(selectedDir)
                dialog.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun scanLibrary(treeUri: Uri) {
        try {
            val ctx = requireContext()
            val docTree = DocumentFile.fromTreeUri(ctx, treeUri) ?: return
            scanLibraryFromDocumentFile(docTree)
        } catch (e: Exception) {
            Log.e("Audiobook", "scanLibrary", e)
            safeToast("Scan failed: ${e.message}")
        }
    }

    private fun scanLibraryFromFile(dir: File) {
        val docFile = DocumentFile.fromFile(dir)
        if (docFile != null) {
            scanLibraryFromDocumentFile(docFile)
        }
    }

    private fun scanLibraryFromDocumentFile(libraryRoot: DocumentFile) {
        try {
            val novelList = mutableListOf<Novel>()

            libraryRoot.listFiles()?.forEach { novelDir ->
                if (novelDir.isDirectory) {
                    // Check if this directory has an 'audio' subfolder
                    var audioDir: DocumentFile? = null
                    var coverFile: DocumentFile? = null
                    var hasAudioFilesDirectly = false

                    novelDir.listFiles()?.forEach { item ->
                        if (item.isDirectory && item.name?.lowercase() == "audio") {
                            audioDir = item
                        } else if (item.isFile) {
                            val name = item.name?.lowercase() ?: ""
                            if (name.endsWith(".mp3") || name.endsWith(".m4a") || name.endsWith(".opus") || name.endsWith(".ogg")) {
                                hasAudioFilesDirectly = true
                            } else if (name.endsWith(".jpg") || name.endsWith(".jpeg") || name.endsWith(".png") || name.endsWith(".webp")) {
                                if (coverFile == null) coverFile = item
                            }
                        }
                    }

                    // Edge case 1: User picked the novel folder itself (has audio/ subfolder)
                    // Edge case 2: User picked the audio/ folder directly
                    val chapterSource = audioDir ?: if (hasAudioFilesDirectly) novelDir else null
                    
                    if (chapterSource != null) {
                        val novelName = novelDir.name ?: "Unknown Novel"
                        val chapterList = mutableListOf<Chapter>()

                        chapterSource.listFiles()?.forEach { file ->
                            if (file.isFile) {
                                val name = file.name?.lowercase() ?: ""
                                if (name.endsWith(".mp3") || name.endsWith(".m4a") || name.endsWith(".opus") || name.endsWith(".ogg")) {
                                    val chapterTitle = file.name?.substringBeforeLast(".") ?: file.name ?: "Unknown"
                                    val index = extractChapterNumber(chapterTitle)
                                    chapterList.add(Chapter(chapterTitle, file, index, novelName))
                                }
                            }
                        }

                        if (chapterList.isNotEmpty()) {
                            // Sort chapters numerically
                            chapterList.sortWith(compareBy<Chapter> { it.index }.thenBy { it.title.lowercase() })

                            val novel = Novel(novelName, novelDir, coverFile, chapterList)
                            novelList.add(novel)
                        }
                    }
                }
            }

            // Edge case: If user picked the audio/ folder directly (parent is novel folder)
            if (novelList.isEmpty()) {
                // Check if libraryRoot.name is "audio" - if so, use its parent directory's name
                val parentName = if (libraryRoot.name?.lowercase() == "audio") {
                    // Need to get parent DocumentFile - we can't directly, so use the libraryUri path
                    "Unknown Novel"
                } else {
                    libraryRoot.name ?: "Unknown Novel"
                }
                val chapterList = mutableListOf<Chapter>()
                var coverFile: DocumentFile? = null

                libraryRoot.listFiles()?.forEach { file ->
                    if (file.isFile) {
                        val name = file.name?.lowercase() ?: ""
                        if (name.endsWith(".mp3") || name.endsWith(".m4a") || name.endsWith(".opus") || name.endsWith(".ogg")) {
                            val chapterTitle = file.name?.substringBeforeLast(".") ?: file.name ?: "Unknown"
                            val index = extractChapterNumber(chapterTitle)
                            chapterList.add(Chapter(chapterTitle, file, index, parentName))
                        } else if (name.endsWith(".jpg") || name.endsWith(".jpeg") || name.endsWith(".png") || name.endsWith(".webp")) {
                            if (coverFile == null) coverFile = file
                        }
                    }
                }

                if (chapterList.isNotEmpty()) {
                    chapterList.sortWith(compareBy<Chapter> { it.index }.thenBy { it.title.lowercase() })
                    val novel = Novel(parentName, libraryRoot, coverFile, chapterList)
                    novelList.add(novel)
                }
            }

            // Sort novels by name
            novelList.sortBy { it.name.lowercase() }

            novels = novelList
            if (novels.isNotEmpty() && currentNovelIndex == -1) {
                selectNovel(0)
            } else {
                updateNovelList()
            }
            safeToast("Found ${novels.size} novel(s) in library")
        } catch (e: Exception) {
            Log.e("Audiobook", "scanLibraryFromDocumentFile", e)
            safeToast("Scan failed: ${e.message}")
        }
    }

    private fun extractChapterNumber(title: String): Int {
        val pattern = "\\d+".toRegex()
        val match = pattern.find(title)
        return match?.value?.toIntOrNull() ?: Int.MAX_VALUE
    }

    private fun selectNovel(index: Int) {
        if (index < 0 || index >= novels.size) return
        currentNovelIndex = index
        val novel = novels[index]
        chapters = novel.chapters
        currentChapterIndex = -1
        updateNovelUI(novel)
        updateChapterList()
        safeToast("Loaded: ${novel.name} (${chapters.size} chapters)")
    }

    private fun updateNovelUI(novel: Novel) {
        val bb = _b ?: return
        try { bb.tvNovelTitle.text = novel.name } catch (_: Exception) {}
        try { bb.tvChapterTitle.text = "Select a chapter" } catch (_: Exception) {}
        novel.cover?.let { cover ->
            try {
                val input = requireContext().contentResolver.openInputStream(cover.uri)
                input?.use { stream ->
                    val bitmap = android.graphics.BitmapFactory.decodeStream(stream)
                    bb.imgCover.setImageBitmap(bitmap)
                    bb.imgCover.visibility = View.VISIBLE
                }
            } catch (_: Exception) { try { bb.imgCover.visibility = View.GONE } catch (_: Exception) {} }
        } ?: run { try { bb.imgCover.visibility = View.GONE } catch (_: Exception) {} }
    }

    private fun updateNovelList() {
        // Could show a list of novels in a dialog
    }

    private fun updateChapterList() {
        val bb = _b ?: return
        try { bb.empty.visibility = if (chapters.isEmpty()) View.VISIBLE else View.GONE } catch (_: Exception) {}
        try { bb.recycler.visibility = if (chapters.isEmpty()) View.GONE else View.VISIBLE } catch (_: Exception) {}
        try {
            bb.recycler.adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
                override fun onCreateViewHolder(p: ViewGroup, t: Int) =
                    object : RecyclerView.ViewHolder(LayoutInflater.from(p.context).inflate(R.layout.item_download, p, false)) {}
                override fun onBindViewHolder(h: RecyclerView.ViewHolder, i: Int) {
                    try {
                        val ch = chapters[i]
                        val title = h.itemView.findViewById<TextView>(R.id.tvTitle)
                        val status = h.itemView.findViewById<TextView>(R.id.tvStatus)
                        val icon = h.itemView.findViewById<ImageView>(R.id.ivFileIcon)
                        icon.setImageResource(R.drawable.ic_audio)
                        icon.contentDescription = "Audio"
                        title.text = "${ch.index}. ${ch.title}"
                        status.text = "Chapter ${ch.index} • Tap to play"
                        h.itemView.alpha = if (i == currentChapterIndex) 1f else 0.85f
                        h.itemView.setOnClickListener { try { playChapter(i) } catch (_: Exception) {} }
                    } catch (_: Exception) {}
                }
                override fun getItemCount() = chapters.size
            }
        } catch (_: Exception) {}
    }

    private fun playChapter(idx: Int) {
        if (idx < 0 || idx >= chapters.size) return
        try {
            stopPlayer()
            currentChapterIndex = idx
            val ch = chapters[idx]
            val ctx = requireContext()
            player = MediaPlayer().apply {
                try { setDataSource(ctx, ch.file.uri) } catch (e: Exception) { safeToast("Play failed: ${e.message}"); return@apply }
                setOnPreparedListener {
                    try { start() } catch (_: Exception) {}
                    val bb = _b
                    bb?.let {
                        try { it.btnPlay.text = "⏸" } catch (_: Exception) {}
                        try { it.tvChapterTitle.text = "${ch.index}. ${ch.title}" } catch (_: Exception) {}
                        try { it.tvDuration.text = fmt(duration) } catch (_: Exception) {}
                    }
                }
                setOnCompletionListener { try { nextChapter() } catch (_: Exception) {} }
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
                if (chapters.isNotEmpty()) playChapter(0) else safeToast("Select a novel first")
                return
            }
            if (try { p.isPlaying } catch (_: Exception) { false }) { try { p.pause() } catch (_: Exception) {}; try { _b?.btnPlay?.text = "▶" } catch (_: Exception) {} }
            else { try { p.start() } catch (_: Exception) {}; try { _b?.btnPlay?.text = "⏸" } catch (_: Exception) {} }
        } catch (_: Exception) {}
    }

    private fun prevChapter() { if (chapters.isEmpty()) return; try { playChapter(if (currentChapterIndex <= 0) chapters.size - 1 else currentChapterIndex - 1) } catch (_: Exception) {} }
    private fun nextChapter() { if (chapters.isEmpty()) return; try { playChapter(if (currentChapterIndex >= chapters.size - 1) 0 else currentChapterIndex + 1) } catch (_: Exception) {} }

    private fun stop() {
        try { stopPlayer() } catch (_: Exception) {}
        try { _b?.btnPlay?.text = "▶" } catch (_: Exception) {}
        try { _b?.tvChapterTitle?.text = "Stopped" } catch (_: Exception) {}
        try { _b?.seekBar?.progress = 0 } catch (_: Exception) {}
        try { _b?.tvCurrent?.text = "0:00" } catch (_: Exception) {}
        currentChapterIndex = -1
        try { _b?.recycler?.adapter?.notifyDataSetChanged() } catch (_: Exception) {}
    }

    private fun showOverflowMenu() {
        try {
            val bb = _b ?: return
            val ctx = requireContext()
            val popup = PopupMenu(ctx, bb.btnOverflow)
            popup.menuInflater.inflate(R.menu.audiobook_menu, popup.menu)
            popup.setOnMenuItemClickListener { item: MenuItem ->
                try {
                    when (item.itemId) {
                        R.id.menu_pick_library -> pickLibraryFromSandbox()
                        R.id.menu_switch_novel -> showNovelSwitcher()
                        R.id.menu_seek_back -> seekRelative(-10000)
                        R.id.menu_seek_forward -> seekRelative(10000)
                        R.id.menu_scan -> libraryUri?.let { scanLibrary(it) }
                        R.id.menu_stop -> stop()
                    }
                } catch (_: Exception) {}
                true
            }
            popup.show()
        } catch (e: Exception) { Log.e("Audiobook", "overflow menu", e) }
    }

    private fun seekRelative(ms: Int) {
        try {
            val p = player ?: return
            val newPos = (p.currentPosition + ms).coerceIn(0, p.duration)
            p.seekTo(newPos)
        } catch (_: Exception) {}
    }

    private fun showNovelSwitcher() {
        if (novels.isEmpty()) { safeToast("No novels loaded"); return }
        val items = novels.map { it.name }.toTypedArray()
        android.app.AlertDialog.Builder(requireContext())
            .setTitle("Switch Novel")
            .setSingleChoiceItems(items, currentNovelIndex) { dialog, which ->
                selectNovel(which)
                dialog.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun stopPlayer() { try { player?.stop(); player?.release() } catch (_: Exception) {}; player = null }
    private fun fmt(ms: Int): String { if (ms <= 0) return "0:00"; val s = ms / 1000; return String.format("%d:%02d", s / 60, s % 60) }

    // Import folder from external storage to sandbox
    private fun copyFolderToSandbox(sourceUri: Uri) {
        try {
            val sandboxDir = getSandboxDir() ?: return
            val sourceDoc = DocumentFile.fromTreeUri(requireContext(), sourceUri) ?: return
            
            val progressDialog = android.app.ProgressDialog(requireContext()).apply {
                setTitle("Importing Folder")
                setMessage("Copying files to sandbox...")
                setProgressStyle(android.app.ProgressDialog.STYLE_HORIZONTAL)
                setCancelable(false)
                show()
            }

            scope.launch(Dispatchers.IO) {
                try {
                    val copiedCount = copyDocumentTreeRecursive(sourceDoc, sandboxDir, progressDialog)
                    withContext(Dispatchers.Main) {
                        progressDialog.dismiss()
                        safeToast("Imported $copiedCount file(s) to sandbox")
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        progressDialog.dismiss()
                        safeToast("Import failed: ${e.message}")
                    }
                }
            }
        } catch (e: Exception) {
            safeToast("Import failed: ${e.message}")
        }
    }

    private suspend fun copyDocumentTreeRecursive(sourceDoc: DocumentFile, destDir: File, progressDialog: android.app.ProgressDialog): Int {
        var count = 0
        sourceDoc.listFiles()?.forEach { item ->
            if (item.isDirectory) {
                val subDir = File(destDir, item.name ?: "folder").apply { mkdirs() }
                count += copyDocumentTreeRecursive(item, subDir, progressDialog)
            } else if (item.isFile) {
                try {
                    val destFile = File(destDir, item.name ?: "file_${System.currentTimeMillis()}")
                    requireContext().contentResolver.openInputStream(item.uri)?.use { input ->
                        FileOutputStream(destFile).use { output ->
                            input.copyTo(output)
                        }
                    }
                    count++
                    withContext(Dispatchers.Main) {
                        progressDialog.incrementProgressBy(1)
                    }
                } catch (_: Exception) {}
            }
        }
        return count
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        try { if (hidden && try { player?.isPlaying == true } catch (_: Exception) { false }) { try { player?.pause() } catch (_: Exception) {}; try { _b?.btnPlay?.text = "▶" } catch (_: Exception) {} } } catch (_: Exception) {}
    }
    override fun onDestroyView() { try { handler.removeCallbacks(updateRunnable) } catch (_: Exception) {}; try { stopPlayer() } catch (_: Exception) {}; _b = null; super.onDestroyView() }
}