package com.lightbrowser

import android.app.Application
import android.os.Build
import com.google.android.material.color.DynamicColors

class LightBrowserApp : Application() {
    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            DynamicColors.applyToActivitiesIfAvailable(this)
        }
    }
}
