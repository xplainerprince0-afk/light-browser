package com.lightbrowser.data

import android.content.Context
import android.os.Build
import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView
import java.io.File

/**
 * Persistent browser profile: cookies, localStorage, HTTP cache, and WebView data dir.
 */
object BrowserProfile {
    private const val TAG = "BrowserProfile"

    fun configure(context: Context, webView: WebView) {
        val ctx = context.applicationContext
        val dataDir = File(ctx.filesDir, "browser_data").apply { mkdirs() }
        val cacheDir = File(dataDir, "cache").apply { mkdirs() }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                WebView.setDataDirectorySuffix("lightbrowser")
            } catch (e: Exception) {
                Log.w(TAG, "setDataDirectorySuffix failed", e)
            }
        }

        val settings = webView.settings
        settings.javaScriptEnabled = Prefs.jsEnabled
        settings.domStorageEnabled = true
        settings.databaseEnabled = true
        settings.allowFileAccess = true
        settings.allowContentAccess = true

        try {
            @Suppress("DEPRECATION")
            settings.databasePath = File(dataDir, "databases").apply { mkdirs() }.absolutePath
        } catch (_: Exception) {}

        settings.cacheMode = if (Prefs.cacheEnabled) {
            WebSettings.LOAD_DEFAULT
        } else {
            WebSettings.LOAD_NO_CACHE
        }

        settings.setSupportZoom(true)
        settings.builtInZoomControls = true
        settings.displayZoomControls = false
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = true
        settings.setSupportMultipleWindows(true)
        settings.javaScriptCanOpenWindowsAutomatically = true
        settings.mediaPlaybackRequiresUserGesture = false
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        settings.setGeolocationEnabled(false)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            settings.safeBrowsingEnabled = true
        }

        // Hardware acceleration for smoother rendering
        webView.setLayerType(android.view.View.LAYER_TYPE_HARDWARE, null)

        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        cookieManager.setAcceptThirdPartyCookies(webView, true)

        if (Prefs.saveSiteData) {
            persistCookies()
        }

        Log.d(TAG, "Profile configured – data=${dataDir.absolutePath}, cache=${cacheDir.absolutePath}")
    }

    fun persistCookies() {
        try {
            CookieManager.getInstance().flush()
        } catch (e: Exception) {
            Log.w(TAG, "Cookie flush failed", e)
        }
    }

    fun onWebViewPause(webView: WebView?) {
        try {
            webView?.onPause()
            if (Prefs.saveSiteData) persistCookies()
        } catch (_: Exception) {}
    }

    fun onWebViewResume(webView: WebView?) {
        try { webView?.onResume() } catch (_: Exception) {}
    }
}
