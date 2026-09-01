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

object DownloadHelper {
    fun enqueue(ctx: Context, url: String, userAgent: String?, contentDisposition: String?, mimeType: String?) {
        try {
            val dm = ctx.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val uri = Uri.parse(url)
            val fileName = try { URLUtil.guessFileName(url, contentDisposition, mimeType) } catch (_: Exception) { uri.lastPathSegment ?: "download" }
            val req = DownloadManager.Request(uri)
                .setMimeType(mimeType)
                .addRequestHeader("User-Agent", userAgent ?: "")
                .addRequestHeader("cookie", CookieManager.getInstance().getCookie(url) ?: "")
                .setTitle(fileName)
                .setDescription("Downloading via LightBrowser")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
                .setAllowedOverMetered(true).setAllowedOverRoaming(true)
            dm.enqueue(req)
            Toast.makeText(ctx, "Downloading $fileName", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(ctx, "Download failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    // Wibgar exact: xv1.onBlobDownload(String base64data, String mime, String disposition)
    // JS calls window.BlobDownloader.onBlobDownload(base64,mime,disposition)
    class BlobBridge(private val ctx: Context) {
        @android.webkit.JavascriptInterface
        fun onBlobDownload(base64data: String, mime: String?, disposition: String?) {
            try {
                // base64data is data: URL like data:application/zip;base64,....
                val dataPart = base64data.substringAfter(",", base64data)
                val bytes = android.util.Base64.decode(dataPart, android.util.Base64.DEFAULT)
                val fileName = try {
                    URLUtil.guessFileName("blob", disposition, mime)
                } catch (_: Exception) { "download_${System.currentTimeMillis()}.bin" }
                val safeName = if (fileName.isBlank()) "download_${System.currentTimeMillis()}.bin" else fileName
                val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                if (!dir.exists()) dir.mkdirs()
                val file = File(dir, safeName)
                FileOutputStream(file).use { it.write(bytes) }
                // also notify via MediaScanner
                try {
                    android.media.MediaScannerConnection.scanFile(ctx, arrayOf(file.absolutePath), arrayOf(mime ?: "*/*"), null)
                } catch (_: Exception) {}
                Toast.makeText(ctx, "Saved $safeName (${bytes.size/1024} KB)", Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                Toast.makeText(ctx, "Blob save failed: ${e.message}", Toast.LENGTH_LONG).show()
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
