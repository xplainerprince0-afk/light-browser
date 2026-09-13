package com.rg.webloom

import android.app.Application
import android.os.Build

class LightBrowserApp : Application(), coil.ImageLoaderFactory {
    override fun onCreate() {
        super.onCreate()
        try { com.rg.webloom.data.AppCtx.init(this) } catch (_: Exception) {}
        // Must run BEFORE any WebView is created.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                android.webkit.WebView.setDataDirectorySuffix("lightbrowser")
            } catch (_: Exception) {}
        }
    }

    /** RAM: cap Coil bitmap memory to 16 MB (default is ~25% of device RAM —
     *  too much next to 4 live WebViews + ExoPlayer on 2-3 GB devices). */
    override fun newImageLoader(): coil.ImageLoader {
        return coil.ImageLoader.Builder(this)
            .memoryCache {
                coil.memory.MemoryCache.Builder(this)
                    .maxSizeBytes(16 * 1024 * 1024)
                    .build()
            }
            .diskCache {
                coil.disk.DiskCache.Builder()
                    .directory(cacheDir.resolve("coil"))
                    .maxSizeBytes(64L * 1024 * 1024)
                    .build()
            }
            .crossfade(false)
            .build()
    }
}
