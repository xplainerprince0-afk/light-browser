package com.rg.webloom.data

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

        // NOTE: WebView.setDataDirectorySuffix() must run in Application.onCreate BEFORE
        // any WebView is inflated. Calling it here is too late (WebView already exists)
        // and throws on Android P+. Moved to LightBrowserApp. See that file.

        val settings = webView.settings
        settings.javaScriptEnabled = Prefs.jsEnabled
        settings.domStorageEnabled = true
        settings.databaseEnabled = true
        settings.allowFileAccess = false
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
        // Overview OFF: zoomed-out overview + empty-shell SPAs (JS-rendered
        // body) left the layout viewport at 0 height on some ROMs — every
        // vh/dvh/% height resolved to 0 (black localhost webUIs, collapsed
        // drawers). Pages without a viewport meta still get our injected
        // device-width meta (WebViewSetup), so nothing needs the overview.
        settings.loadWithOverviewMode = false
        settings.setSupportMultipleWindows(true)
        settings.javaScriptCanOpenWindowsAutomatically = true
        settings.mediaPlaybackRequiresUserGesture = true
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        settings.setGeolocationEnabled(false)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            settings.safeBrowsingEnabled = true
        }

        // Rendering layer: hardware default, software when the global toggle is
        // ON (custom-ROM fix). Per-navigation private-host fallback happens in
        // WebViewSetup.onPageStarted via applyLayer().
        try { applyLayer(webView, null) } catch (_: Exception) {
            try { webView.setLayerType(android.view.View.LAYER_TYPE_HARDWARE, null) } catch (_: Exception) {}
        }

        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        cookieManager.setAcceptThirdPartyCookies(webView, true)

        if (Prefs.saveSiteData) {
            persistCookies()
        }

        Log.d(TAG, "Profile configured – data=${dataDir.absolutePath}, cache=${cacheDir.absolutePath}")
    }

    /**
     * Rendering layer policy (custom-ROM black-page fix).
     * Hardware is smooth but some GPU drivers render heavy pages black.
     * Private/local hosts (dev webUIs) always use software — they are simple
     * and must never go black. Global [Prefs.softwareRender] forces software
     * everywhere when ON.
     */
    fun isPrivateHost(host: String): Boolean {
        val h = host.lowercase().trim()
        if (h.isEmpty()) return false
        if (h == "localhost" || h == "[::1]" || h == "::1") return true
        if (h == "127.0.0.1" || h.startsWith("127.")) return true
        if (h.startsWith("10.")) return true
        if (h.startsWith("192.168.")) return true
        if (h.endsWith(".local")) return true
        // 172.16.0.0 – 172.31.255.255
        if (h.startsWith("172.")) {
            val second = h.split(".").getOrNull(1)?.toIntOrNull()
            if (second != null && second in 16..31) return true
        }
        return false
    }

    fun applyLayer(webView: WebView, url: String?) {
        try {
            val host = try { url?.let { android.net.Uri.parse(it).host?.lowercase() } ?: "" } catch (_: Exception) { "" }
            val sw = try { Prefs.softwareRender } catch (_: Exception) { false }
            val layer = if (sw || isPrivateHost(host)) android.view.View.LAYER_TYPE_SOFTWARE
            else android.view.View.LAYER_TYPE_HARDWARE
            webView.setLayerType(layer, null)
        } catch (_: Exception) {}
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
