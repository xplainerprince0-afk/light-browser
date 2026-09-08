package com.lightbrowser.ui.theme

import android.os.Build
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

@Composable
fun LightBrowserTheme(
    darkTheme: Boolean = androidx.compose.foundation.isSystemInDarkTheme(),
    blackTheme: Boolean = false,
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && !blackTheme && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val ctx = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        }
        darkTheme && blackTheme -> BlackScheme
        darkTheme -> DarkScheme
        else -> LightScheme
    }
    // Sync status/nav icon contrast with theme (was invisible white-on-white / black-on-black).
    val view = androidx.compose.ui.platform.LocalView.current
    androidx.compose.runtime.SideEffect {
        try {
            val window = (view.context as? android.app.Activity)?.window ?: return@SideEffect
            androidx.core.view.WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !darkTheme
                isAppearanceLightNavigationBars = !darkTheme
            }
        } catch (_: Exception) {}
    }
    MaterialTheme(
        colorScheme = colorScheme,
        shapes = AppShapes,
        typography = Typography(),
        content = content
    )
}
