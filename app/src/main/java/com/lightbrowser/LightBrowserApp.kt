package com.lightbrowser

import android.app.Application
import android.os.Build
import com.google.android.material.color.DynamicColors

class LightBrowserApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Must run BEFORE any WebView is created (BrowserFragment inflates one later).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                android.webkit.WebView.setDataDirectorySuffix("lightbrowser")
            } catch (_: Exception) {}
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            DynamicColors.applyToActivitiesIfAvailable(this)
        }
    }
}
