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
import com.google.android.material.bottomsheet.BottomSheetDialog
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
    private var isPlayerViewVisible = false

    private var folderPicker: ActivityResultLauncher<Uri?>? = null
    private var importFolderPicker: ActivityResultLauncher<Uri?>? = null

    private val scope = CoroutineScope(Dispatchers.Main)

    data class Novel(
        val name: String,
        val directory: DocumentFile,
        val cover: DocumentFile?,
        val chapters: List<Chapter>,
        val totalDuration: Long = 0,
        val playedDuration: Long = 0
    ) {
        val progressPercent: Int
            get() = if (totalDuration > 0) ((playedDuration * 100) / totalDuration).toInt() else 0
        val formattedTotalDuration: String
            get() = formatDuration(totalDuration)
    }

    data class Chapter(
        val title: String,
        val file: DocumentFile,
        val index: Int,
        val novelName: String,
        val duration: Long = 0
    ) {
        val formattedDuration: String
            get() = formatDuration(duration)
    }

    companion object {
        fun formatDuration(ms: Long): String {
            if (ms <= 0) return "0:00"
            val s = ms / 1000
            val h = s / 3600
            val m = (s % 3600) / 60
            val sec = s % 60
            return if (h > 0) String.format("%d:%02d:%02d", h, m, sec)
            else String.format("%d:%02d", m, sec)
        }
    }

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
                    try { bb.playerSeekBar.max = if (dur > 0) dur else 100 } catch (_: Exception) {}
                    try { bb.playerSeekBar.progress = pos } catch (_: Exception) {}
                    try { bb.playerCurrentTime.text = formatDuration(pos.toLong()) } catch (_: Exception) {}
                    try { bb.playerDuration.text = formatDuration(dur.toLong()) } catch (_: Exception) {}
                    
                    // Update chapter progress in novel list
                    if (currentNovelIndex >= 0 && currentNovelIndex < novels.size) {
                        val novel = novels[currentNovelIndex]
                        val updatedChapters = novel.chapters.toMutableList()
                        if (currentChapterIndex >= 0 && currentChapterIndex < updatedChapters.size) {
                            val currentChapter = updatedChapters[currentChapterIndex]
                            val playedMs = pos.toLong() + updatedChapters.take(currentChapterIndex).sumOf { it.duration }
                            val updatedNovel = novel.copy(
                                playedDuration = playedMs
                            )
                            novels = novels.toMutableList().apply { this[currentNovelIndex] = updatedNovel }
                            handler.post { updateNovelList() }
                        }
                    }
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
            
            // Setup novel list recycler
            bb.novelRecycler.layoutManager = LinearLayoutManager(requireContext())
            bb.novelRecycler.adapter = NovelAdapter()
            
            // Setup player view
            bb.playerSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) { 
                    if (fromUser) try { bb.playerCurrentTime.text = formatDuration(p.toLong()) } catch (_: Exception) {} 
                }
                override fun onStartTrackingTouch(sb: SeekBar?) { isSeeking = true }
                override fun onStopTrackingTouch(sb: SeekBar?) { isSeeking = false; try { player?.seekTo(sb?.progress ?: 0) } catch (_: Exception) {} }
            })

            // Button listeners
            bb.btnPickLibrary.setOnClickListener { try { pickLibraryFromSandbox() } catch (e: Exception) { safeToast(e.message) } }
            bb.playerBtnPlay.setOnClickListener { try { toggle() } catch (_: Exception) {} }
            bb.playerBtnPrev.setOnClickListener { try { prevChapter() } catch (_: Exception) {} }
            bb.playerBtnNext.setOnClickListener { try { nextChapter() } catch (_: Exception) {} }
            bb.playerBtnChapters.setOnClickListener { showChaptersBottomSheet() }
            
            try { handler.post(updateRunnable) } catch (_: Exception) {}
            loadPersistedLibrary()
            
            // Show appropriate view
            showNovelList()
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
        val dirs: Array<File> = sandboxDir.listFiles()?.filter { it.isDirectory }?.toTypedArray() ?: emptyArray()
        
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

                    val chapterSource = audioDir ?: if (hasAudioFilesDirectly) novelDir else null
                    
                    if (chapterSource != null) {
                        val novelName = novelDir.name ?: "Unknown Novel"
                        val chapterList = mutableListOf<Chapter>()
                        var totalDuration = 0L

                        chapterSource.listFiles()?.forEach { file ->
                            if (file.isFile) {
                                val name = file.name?.lowercase() ?: ""
                                if (name.endsWith(".mp3") || name.endsWith(".m4a") || name.endsWith(".opus") || name.endsWith(".ogg")) {
                                    val chapterTitle = file.name?.substringBeforeLast(".") ?: file.name ?: "Unknown"
                                    val index = extractChapterNumber(chapterTitle)
                                    val duration = getAudioDuration(file)
                                    chapterList.add(Chapter(chapterTitle, file, index, novelName, duration))
                                    totalDuration += duration
                                }
                            }
                        }

                        if (chapterList.isNotEmpty()) {
                            chapterList.sortWith(compareBy<Chapter> { it.index }.thenBy { it.title.lowercase() })
                            val novel = Novel(novelName, novelDir, coverFile, chapterList, totalDuration, 0L)
                            novelList.add(novel)
                        }
                    }
                }
            }

            if (novelList.isEmpty()) {
                val parentName = if (libraryRoot.name?.lowercase() == "audio") {
                    "Unknown Novel"
                } else {
                    libraryRoot.name ?: "Unknown Novel"
                }
                val chapterList = mutableListOf<Chapter>()
                var coverFile: DocumentFile? = null
                var totalDuration = 0L

                libraryRoot.listFiles()?.forEach { file ->
                    if (file.isFile) {
                        val name = file.name?.lowercase() ?: ""
                        if (name.endsWith(".mp3") || name.endsWith(".m4a") || name.endsWith(".opus") || name.endsWith(".ogg")) {
                            val chapterTitle = file.name?.substringBeforeLast(".") ?: file.name ?: "Unknown"
                            val index = extractChapterNumber(chapterTitle)
                            val duration = getAudioDuration(file)
                            chapterList.add(Chapter(chapterTitle, file, index, parentName, duration))
                            totalDuration += duration
                        } else if (name.endsWith(".jpg") || name.endsWith(".jpeg") || name.endsWith(".png") || name.endsWith(".webp")) {
                            if (coverFile == null) coverFile = file
                        }
                    }
                }

                if (chapterList.isNotEmpty()) {
                    chapterList.sortWith(compareBy<Chapter> { it.index }.thenBy { it.title.lowercase() })
                    val novel = Novel(parentName, libraryRoot, coverFile, chapterList, totalDuration, 0L)
                    novelList.add(novel)
                }
            }

            novels = novelList
            
            handler.post {
                updateNovelList()
                if (novels.isNotEmpty() && currentNovelIndex == -1) {
                    // Don't auto-select, let user choose
                    showNovelList()
                }
            }
            safeToast("Found ${novels.size} novel(s) in library")
        } catch (e: Exception) {
            Log.e("Audiobook", "scanLibraryFromDocumentFile", e)
            safeToast("Scan failed: ${e.message}")
        }
    }

    private fun getAudioDuration(file: DocumentFile): Long {
        try {
            val mmr = MediaMetadataRetriever()
            mmr.setDataSource(requireContext(), file.uri)
            val durationStr = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            mmr.release()
            return durationStr?.toLongOrNull() ?: 0L
        } catch (_: Exception) {
            return 0L
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
        showPlayerView(novel)
        safeToast("Loaded: ${novel.name} (${chapters.size} chapters)")
    }

    private fun showNovelList() {
        isPlayerViewVisible = false
        val bb = _b ?: return
        bb.novelListContainer.visibility = View.VISIBLE
        bb.playerContainer.visibility = View.GONE
        updateNovelList()
    }

    private fun showPlayerView(novel: Novel) {
        isPlayerViewVisible = true
        val bb = _b ?: return
        bb.novelListContainer.visibility = View.GONE
        bb.playerContainer.visibility = View.VISIBLE
        
        // Load cover
        novel.cover?.let { cover ->
            try {
                val input = requireContext().contentResolver.openInputStream(cover.uri)
                input?.use { stream ->
                    val bitmap = android.graphics.BitmapFactory.decodeStream(stream)
                    bb.playerCover.setImageBitmap(bitmap)
                    bb.playerCoverBg.setImageBitmap(bitmap)
                }
            } catch (_: Exception) { 
                bb.playerCover.setImageResource(R.drawable.bg_url_bar)
                bb.playerCoverBg.setImageResource(R.drawable.bg_url_bar)
            }
        } ?: run { 
            bb.playerCover.setImageResource(R.drawable.bg_url_bar)
            bb.playerCoverBg.setImageResource(R.drawable.bg_url_bar)
        }
        
        bb.playerNovelTitle.text = novel.name
        bb.playerChapterTitle.text = "Tap a chapter to play"
        bb.playerProgress.text = "${novel.progressPercent}% • ${novel.formattedTotalDuration}"
        bb.playerSeekBar.max = 100
        bb.playerSeekBar.progress = novel.progressPercent
    }

    private fun updateNovelList() {
        val bb = _b ?: return
        bb.novelEmpty.visibility = if (novels.isEmpty()) View.VISIBLE else View.GONE
        bb.novelRecycler.visibility = if (novels.isEmpty()) View.GONE else View.VISIBLE
        (bb.novelRecycler.adapter as? NovelAdapter)?.notifyDataSetChanged()
    }

    inner class NovelAdapter : RecyclerView.Adapter<NovelViewHolder>() {
        override fun onCreateViewHolder(p: ViewGroup, t: Int): NovelViewHolder {
            val v = LayoutInflater.from(p.context).inflate(R.layout.item_novel, p, false)
            return NovelViewHolder(v)
        }
        override fun onBindViewHolder(h: NovelViewHolder, i: Int) {
            val novel = novels[i]
            h.title.text = novel.name
            h.duration.text = novel.formattedTotalDuration
            h.progressText.text = "${novel.progressPercent}%"
            h.progressBar.progress = novel.progressPercent
            
            // Load cover thumbnail
            novel.cover?.let { cover ->
                try {
                    val input = requireContext().contentResolver.openInputStream(cover.uri)
                    input?.use { stream ->
                        val bitmap = android.graphics.BitmapFactory.decodeStream(stream)
                        h.cover.setImageBitmap(bitmap)
                    }
                } catch (_: Exception) { h.cover.setImageResource(R.drawable.bg_url_bar) }
            } ?: run { h.cover.setImageResource(R.drawable.bg_url_bar) }
            
            h.itemView.setOnClickListener { selectNovel(i) }
        }
        override fun getItemCount() = novels.size
    }

    inner class NovelViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val title: TextView = view.findViewById(R.id.novelTitle)
        val duration: TextView = view.findViewById(R.id.novelDuration)
        val progressText: TextView = view.findViewById(R.id.novelProgressText)
        val progressBar: ProgressBar = view.findViewById(R.id.novelProgressBar)
        val cover: ImageView = view.findViewById(R.id.novelCover)
    }

    private fun updateChapterList() {
        // Called when player is visible - update chapter list in bottom sheet
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
                        try { it.playerBtnPlay.text = "⏸" } catch (_: Exception) {}
                        try { it.playerChapterTitle.text = "${ch.index}. ${ch.title}" } catch (_: Exception) {}
                        try { it.playerDuration.text = ch.formattedDuration } catch (_: Exception) {}
                        try { it.playerSeekBar.max = ch.duration.toInt() } catch (_: Exception) {}
                    }
                }
                setOnCompletionListener { try { nextChapter() } catch (_: Exception) {} }
                setOnErrorListener { _, what, extra -> safeToast("Error $what/$extra"); true }
                try { prepareAsync() } catch (e: Exception) { safeToast(e.message) }
            }
            try { (_b?.novelRecycler?.adapter as? NovelAdapter)?.notifyDataSetChanged() } catch (_: Exception) {}
        } catch (e: Exception) { safeToast("Play failed: ${e.message}") }
    }

    private fun toggle() {
        try {
            val p = player
            if (p == null) {
                if (chapters.isNotEmpty()) playChapter(0) else safeToast("Select a chapter")
                return
            }
            if (try { p.isPlaying } catch (_: Exception) { false }) { try { p.pause() } catch (_: Exception) {}; try { _b?.playerBtnPlay?.text = "▶" } catch (_: Exception) {} }
            else { try { p.start() } catch (_: Exception) {}; try { _b?.playerBtnPlay?.text = "⏸" } catch (_: Exception) {} }
        } catch (_: Exception) {}
    }

    private fun prevChapter() { if (chapters.isEmpty()) return; try { playChapter(if (currentChapterIndex <= 0) chapters.size - 1 else currentChapterIndex - 1) } catch (_: Exception) {} }
    private fun nextChapter() { if (chapters.isEmpty()) return; try { playChapter(if (currentChapterIndex >= chapters.size - 1) 0 else currentChapterIndex + 1) } catch (_: Exception) {} }

    private fun stop() {
        try { stopPlayer() } catch (_: Exception) {}
        try { _b?.playerBtnPlay?.text = "▶" } catch (_: Exception) {}
        try { _b?.playerChapterTitle?.text = "Stopped" } catch (_: Exception) {}
        try { _b?.playerSeekBar?.progress = 0 } catch (_: Exception) {}
        try { _b?.playerCurrentTime?.text = "0:00" } catch (_: Exception) {}
        currentChapterIndex = -1
    }

    private fun showOverflowMenu() {
        try {
            val bb = _b ?: return
            val ctx = requireContext()
            val popup = PopupMenu(ctx, bb.playerBtnChapters) // Using chapters button as anchor
            popup.menuInflater.inflate(R.menu.audiobook_menu, popup.menu)
            popup.setOnMenuItemClickListener { item: MenuItem ->
                try {
                    when (item.itemId) {
                        R.id.menu_pick_library -> pickLibraryFromSandbox()
                        R.id.menu_switch_novel -> showNovelList()
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

    private fun showChaptersBottomSheet() {
        if (chapters.isEmpty()) { safeToast("No chapters loaded"); return }
        
        val bottomSheet = BottomSheetDialog(requireContext())
        val view = LayoutInflater.from(requireContext()).inflate(R.layout.bottomsheet_chapters, null)
        bottomSheet.setContentView(view)
        
        val recycler = view.findViewById<RecyclerView>(R.id.chapterRecycler)
        recycler.layoutManager = LinearLayoutManager(requireContext())
        
        recycler.adapter = object : RecyclerView.Adapter<ChapterViewHolder>() {
            override fun onCreateViewHolder(p: ViewGroup, t: Int): ChapterViewHolder {
                val v = LayoutInflater.from(p.context).inflate(R.layout.item_chapter, p, false)
                return ChapterViewHolder(v)
            }
            override fun onBindViewHolder(h: ChapterViewHolder, i: Int) {
                val ch = chapters[i]
                h.number.text = "%03d".format(ch.index)
                h.title.text = ch.title
                h.duration.text = ch.formattedDuration
                h.playingIndicator.visibility = if (i == currentChapterIndex) View.VISIBLE else View.GONE
                h.itemView.setOnClickListener {
                    playChapter(i)
                    bottomSheet.dismiss()
                }
            }
            override fun getItemCount() = chapters.size
        }
        
        bottomSheet.show()
    }

    inner class ChapterViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val number: TextView = view.findViewById(R.id.chapterNumber)
        val title: TextView = view.findViewById(R.id.chapterTitle)
        val duration: TextView = view.findViewById(R.id.chapterDuration)
        val playingIndicator: ImageView = view.findViewById(R.id.chapterPlayingIndicator)
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
        try { if (hidden && try { player?.isPlaying == true } catch (_: Exception) { false }) { try { player?.pause() } catch (_: Exception) {}; try { _b?.playerBtnPlay?.text = "▶" } catch (_: Exception) {} } } catch (_: Exception) {}
    }
    override fun onDestroyView() { try { handler.removeCallbacks(updateRunnable) } catch (_: Exception) {}; try { stopPlayer() } catch (_: Exception) {}; _b = null; super.onDestroyView() }
}