package com.lightbrowser.data

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.io.File

/**
 * Audiobook library model + suspend scanner (runs on Dispatchers.IO).
 * Covers come from sibling image files or embedded art; durations via MMR.
 */
data class Chapter(
    val title: String,
    val uri: Uri,
    val index: Int,
    val novelName: String,
    val durationMs: Long = 0L
)

data class Novel(
    val name: String,
    val coverUri: Uri?,
    val chapters: List<Chapter>,
    val totalMs: Long = chapters.sumOf { it.durationMs }
)

private val AUDIO_EXTS = setOf("mp3", "m4a", "aac", "ogg", "wav", "flac", "opus")
private val IMAGE_EXTS = setOf("jpg", "jpeg", "png", "webp")

fun formatDuration(ms: Long): String {
    if (ms <= 0) return "0:00"
    val s = ms / 1000
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}

object LibraryScanner {
    fun sandboxMusicDir(ctx: Context): File =
        File(ctx.filesDir, "sandbox").apply { if (!exists()) mkdirs() }

    suspend fun scanRoot(root: DocumentFile, app: Context): List<Novel> {
        val novels = mutableListOf<Novel>()
        val top = root.listFiles() ?: emptyArray()
        val novelDirs = top.filter { it.isDirectory }
        if (novelDirs.isEmpty()) {
            // Flat folder = single novel
            collectNovel(root, root.name ?: "Unknown Novel", app)?.let { novels.add(it) }
        } else {
            novelDirs.forEach { dir ->
                val audioDir = dir.listFiles()?.firstOrNull {
                    it.isDirectory && it.name?.lowercase() == "audio"
                }
                val source = audioDir ?: dir
                val hasAudio = source.listFiles()?.any { it.isFile && isAudio(it.name) } == true
                if (hasAudio) {
                    collectNovel(source, dir.name ?: "Unknown Novel", app, coverScope = dir)?.let {
                        novels.add(it)
                    }
                }
            }
            if (novels.isEmpty()) {
                // No novel matched — try treating each dir flatly
                novelDirs.forEach { dir ->
                    collectNovel(dir, dir.name ?: "Unknown", app)?.let { novels.add(it) }
                }
            }
        }
        return novels.sortedBy { it.name.lowercase() }
    }

    private fun collectNovel(
        source: DocumentFile,
        novelName: String,
        app: Context,
        coverScope: DocumentFile? = null
    ): Novel? {
        val files = source.listFiles() ?: return null
        var cover: DocumentFile? = null
        // Prefer cover in novel dir, fall back to audio dir
        coverScope?.listFiles()?.firstOrNull { it.isFile && isImage(it.name) }?.let { cover = it }
        if (cover == null) cover = files.firstOrNull { it.isFile && isImage(it.name) }
        val chapters = files
            .filter { it.isFile && isAudio(it.name) }
            .map { f ->
                val title = f.name?.substringBeforeLast(".") ?: "Unknown"
                Chapter(
                    title = title,
                    uri = f.uri,
                    index = extractNumber(title),
                    novelName = novelName,
                    durationMs = audioDuration(app, f.uri)
                )
            }
            .sortedWith(compareBy<Chapter> { it.index }.thenBy { it.title.lowercase() })
        if (chapters.isEmpty()) return null
        return Novel(novelName, cover?.uri, chapters)
    }

    private fun isAudio(name: String?): Boolean {
        val ext = name?.substringAfterLast(".", "")?.lowercase() ?: return false
        return ext in AUDIO_EXTS
    }

    private fun isImage(name: String?): Boolean {
        val ext = name?.substringAfterLast(".", "")?.lowercase() ?: return false
        return ext in IMAGE_EXTS
    }

    private fun extractNumber(title: String): Int =
        "\\d+".toRegex().find(title)?.value?.toIntOrNull() ?: Int.MAX_VALUE

    private fun audioDuration(app: Context, uri: Uri): Long {
        var mmr: MediaMetadataRetriever? = null
        return try {
            mmr = MediaMetadataRetriever()
            mmr.setDataSource(app, uri)
            mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        } catch (_: Exception) {
            0L
        } finally {
            try { mmr?.release() } catch (_: Exception) {}
        }
    }
}
