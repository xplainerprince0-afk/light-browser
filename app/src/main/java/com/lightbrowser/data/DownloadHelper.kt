package com.lightbrowser.data

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.webkit.CookieManager
import android.widget.Toast

object DownloadHelper {
    fun enqueue(ctx: Context, url: String, userAgent: String?, contentDisposition: String?, mimeType: String?) {
        try {
            val dm = ctx.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val uri = Uri.parse(url)
            // guess filename
            val fileName = try {
                android.webkit.URLUtil.guessFileName(url, contentDisposition, mimeType)
            } catch (_: Exception) { uri.lastPathSegment ?: "download" }

            val req = DownloadManager.Request(uri)
                .setMimeType(mimeType)
                .addRequestHeader("User-Agent", userAgent ?: "")
                .addRequestHeader("cookie", CookieManager.getInstance().getCookie(url) ?: "")
                .setTitle(fileName)
                .setDescription("Downloading via LightBrowser")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
                .setAllowedOverMetered(true)
                .setAllowedOverRoaming(true)

            dm.enqueue(req)
            Toast.makeText(ctx, "Downloading $fileName", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(ctx, "Download failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    // For blob: URLs – injected JS will call this bridge with base64
    class BlobBridge(private val ctx: Context) {
        @android.webkit.JavascriptInterface
        fun onBlobData(base64: String, fileName: String, mime: String) {
            try {
                val bytes = android.util.Base64.decode(base64.substringAfter(","), android.util.Base64.DEFAULT)
                val file = java.io.File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), fileName)
                file.writeBytes(bytes)
                android.widget.Toast.makeText(ctx, "Saved $fileName", android.widget.Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                android.widget.Toast.makeText(ctx, "Blob save failed: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }
}
