package com.lightbrowser

import android.app.Application
import android.os.Build

class LightBrowserApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Must run BEFORE any WebView is created.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                android.webkit.WebView.setDataDirectorySuffix("lightbrowser")
            } catch (_: Exception) {}
        }
    }
}
