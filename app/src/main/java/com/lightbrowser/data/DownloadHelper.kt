package com.lightbrowser.data

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.webkit.CookieManager
import android.webkit.URLUtil
import android.widget.Toast
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.flow.asStateFlow

object DownloadHelper {
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    data class ActiveDl(
        val id: Long,
        val name: String,
        val url: String,
        val progress: Float?, // null = indeterminate
        val received: Long,
        val total: Long
    )

    private val _active = kotlinx.coroutines.flow.MutableStateFlow<List<ActiveDl>>(emptyList())
    val active: kotlinx.coroutines.flow.StateFlow<List<ActiveDl>> = _active.asStateFlow()
    private val cancelFlags = java.util.concurrent.ConcurrentHashMap<Long, java.util.concurrent.atomic.AtomicBoolean>()
    private val cancelConns = java.util.concurrent.ConcurrentHashMap<Long, java.net.HttpURLConnection>()
    private var nextId = 1L

    fun cancel(id: Long) {
        try { cancelFlags[id]?.set(true) } catch (_: Exception) {}
        try { cancelConns[id]?.disconnect() } catch (_: Exception) {}
    }

    private fun updateDl(dl: ActiveDl) {
        _active.value = _active.value.filterNot { it.id == dl.id } + dl
    }

    private fun removeDl(id: Long) {
        _active.value = _active.value.filterNot { it.id == id }
        cancelFlags.remove(id)
        try { cancelConns.remove(id) } catch (_: Exception) {}
    }

    private fun toastOnMain(ctx: Context, msg: String, long: Boolean = false) {
        try {
            val app = ctx.applicationContext
            mainHandler.post {
                try {
                    android.widget.Toast.makeText(
                        app, msg,
                        if (long) android.widget.Toast.LENGTH_LONG else android.widget.Toast.LENGTH_SHORT
                    ).show()
                } catch (_: Exception) {}
            }
        } catch (_: Exception) {}
    }

    fun enqueue(ctx: Context, url: String, userAgent: String?, contentDisposition: String?, mimeType: String?) {
        try {
            val app = ctx.applicationContext
            val uri = Uri.parse(url)
            val fileName = try { URLUtil.guessFileName(url, contentDisposition, mimeType) } catch (_: Exception) { uri.lastPathSegment ?: "download" }
            val safeName = fileName.ifBlank { "download_${System.currentTimeMillis()}.bin" }

            // DownloadManager CANNOT write to internal filesDir (different UID) — it would fail
            // with SecurityException. Download in-app on a background thread instead.
            val id = synchronized(this) { nextId++ }
            val flag = java.util.concurrent.atomic.AtomicBoolean(false)
            cancelFlags[id] = flag
            updateDl(ActiveDl(id, safeName, url, null, 0, -1))
            toastOnMain(app, "Downloading $safeName…")
            Thread {
                var conn: java.net.HttpURLConnection? = null
                try {
                    val sandboxDir = getSandboxDownloadsDir(app)
                    val outFile = uniqueFile(sandboxDir, safeName)
                    conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
                    cancelConns[id] = conn
                    conn.connectTimeout = 30_000
                    conn.readTimeout = 120_000
                    conn.instanceFollowRedirects = true
                    val ua = if (!userAgent.isNullOrBlank() && !userAgent.startsWith("Java/")) userAgent
                        else try { System.getProperty("http.agent") } catch (_: Exception) { null }
                    if (!ua.isNullOrBlank()) conn.setRequestProperty("User-Agent", ua)
                    try {
                        val ck = try {
                            // CookieManager should be read on Main; post-and-wait briefly, fall back direct.
                            if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) CookieManager.getInstance().getCookie(url)
                            else {
                                val f = java.util.concurrent.CompletableFuture<String?>()
                                mainHandler.post { try { f.complete(CookieManager.getInstance().getCookie(url)) } catch (_: Exception) { f.complete(null) } }
                                try { f.get(2, java.util.concurrent.TimeUnit.SECONDS) } catch (_: Exception) { CookieManager.getInstance().getCookie(url) }
                            }
                        } catch (_: Exception) { null }
                        if (!ck.isNullOrBlank()) conn.setRequestProperty("Cookie", ck)
                    } catch (_: Exception) {}
                    conn.connect()
                    if (conn.responseCode !in 200..299) throw java.io.IOException("HTTP ${conn.responseCode}")
                    val total = try { conn.contentLengthLong } catch (_: Exception) { -1L }
                    var received = 0L
                    var lastPush = 0L
                    conn.inputStream.use { input ->
                        java.io.FileOutputStream(outFile).use { output ->
                            val buf = ByteArray(64 * 1024)
                            while (true) {
                                if (flag.get()) throw java.io.IOException("Cancelled")
                                val n = input.read(buf)
                                if (n < 0) break
                                output.write(buf, 0, n)
                                received += n
                                val now = System.currentTimeMillis()
                                if (now - lastPush > 250) {
                                    lastPush = now
                                    val p = if (total > 0) received.toFloat() / total else null
                                    updateDl(ActiveDl(id, outFile.name, url, p, received, total))
                                }
                            }
                        }
                    }
                    if (flag.get()) {
                        try { outFile.delete() } catch (_: Exception) {}
                        removeDl(id)
                        toastOnMain(app, "Download cancelled")
                        return@Thread
                    }
                    removeDl(id)
                    try {
                        android.media.MediaScannerConnection.scanFile(app, arrayOf(outFile.absolutePath), arrayOf(mimeType ?: "*/*"), null)
                    } catch (_: Exception) {}
                    toastOnMain(app, "Saved ${outFile.name} to sandbox/Downloads", long = true)
                } catch (e: Exception) {
                    removeDl(id)
                    android.util.Log.e("LightBrowser", "download fail", e)
                    val friendly = when {
                        e.message?.contains("Cancelled", true) == true -> "Download cancelled"
                        e.message?.contains("HTTP 404", true) == true -> "Download failed: file not found (404)"
                        e.message?.contains("HTTP 403", true) == true -> "Download failed: forbidden (403)"
                        else -> "Download failed. Tap to retry."
                    }
                    toastOnMain(app, friendly, long = true)
                } finally {
                    try { cancelConns.remove(id) } catch (_: Exception) {}
                    try { conn?.disconnect() } catch (_: Exception) {}
                }
            }.also { it.isDaemon = true }.start()
        } catch (e: Exception) {
            toastOnMain(ctx, "Download failed: ${e.message}", long = true)
        }
    }

    private fun uniqueFile(dir: File, name: String): File {
        var f = File(dir, name)
        if (!f.exists()) return f
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var i = 1
        while (f.exists() && i < 1000) {
            f = File(dir, "$base($i)$ext")
            i++
        }
        if (f.exists()) f = File(dir, "${base}_${System.currentTimeMillis()}$ext")
        return f
    }

    private fun getSandboxDownloadsDir(ctx: Context): File {
        return try {
            File(ctx.filesDir, "sandbox/Downloads").apply { if (!exists()) mkdirs() }
        } catch (_: Exception) {
            File(ctx.cacheDir, "sandbox/Downloads").apply { if (!exists()) mkdirs() }
        }
    }

    // Wibgar exact: xv1.onBlobDownload(String base64data, String mime, String disposition)
    // JS calls window.BlobDownloader.onBlobDownload(base64,mime,disposition)
    // NOTE: @JavascriptInterface runs on a background thread — never touch Toast/Views directly.
    class BlobBridge(private val ctx: Context) {
        private val appCtx: Context = ctx.applicationContext
        private val io = java.util.concurrent.Executors.newSingleThreadExecutor()

        @android.webkit.JavascriptInterface
        fun onBlobDownload(base64data: String, mime: String?, disposition: String?) {
            // Offload Base64 decode + file write off the JS thread (large videos = multi-MB).
            try { io.execute { saveBlob(base64data, mime, disposition) } } catch (_: Exception) {
                try { saveBlob(base64data, mime, disposition) } catch (_: Exception) {}
            }
        }

        private fun saveBlob(base64data: String, mime: String?, disposition: String?) {
            try {
                // base64data is data: URL like data:application/zip;base64,....
                // Guard: huge videos as base64 dataURL = 2-3x RAM → OOM. Cap at ~80MB string.
                if (base64data.length > 110_000_000) {
                    toastOnMain(appCtx, "Blob too large to save in-app", long = true)
                    return
                }
                val dataPart = base64data.substringAfter(",", base64data)
                val bytes = try {
                    android.util.Base64.decode(dataPart, android.util.Base64.DEFAULT)
                } catch (e: OutOfMemoryError) {
                    toastOnMain(appCtx, "Blob too large (out of memory)", long = true)
                    return
                }
                val fileName = try {
                    URLUtil.guessFileName("blob", disposition, mime)
                } catch (_: Exception) { "download_${System.currentTimeMillis()}.bin" }
                val safeName = if (fileName.isBlank()) "download_${System.currentTimeMillis()}.bin" else fileName
                
                val sandboxDir = getSandboxDownloadsDir(appCtx)
                val file = uniqueFile(sandboxDir, safeName)
                FileOutputStream(file).use { it.write(bytes) }
                
                // also notify via MediaScanner
                try {
                    android.media.MediaScannerConnection.scanFile(appCtx, arrayOf(file.absolutePath), arrayOf(mime ?: "*/*"), null)
                } catch (_: Exception) {}
                toastOnMain(appCtx, "Saved $safeName (${bytes.size/1024} KB) to sandbox/Downloads", long = true)
            } catch (e: Exception) {
                toastOnMain(appCtx, "Blob save failed: ${e.message}", long = true)
                android.util.Log.e("LightBrowser", "Blob save fail", e)
            }
        }

        // keep legacy name for compatibility
        @android.webkit.JavascriptInterface
        fun onBlobData(base64: String, fileName: String, mime: String) {
            onBlobDownload(base64, mime, "attachment; filename=\"$fileName\"")
        }
    }
}