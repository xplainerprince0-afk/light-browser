package com.lightbrowser.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

// Seed: LightBrowser teal. Dynamic color (API 31+) derives from wallpaper;
// these static schemes are the fallback + the pre-dynamic identity.
val TealPrimary = Color(0xFF0F766E)
val TealBright = Color(0xFF14B8A6)
val TealSoft = Color(0xFF5EEAD4)

val LightScheme = lightColorScheme(
    primary = Color(0xFF0B6B64),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFF9CF2E2),
    onPrimaryContainer = Color(0xFF00201C),
    secondary = Color(0xFF4A635E),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFCDE8E1),
    onSecondaryContainer = Color(0xFF06201C),
    tertiary = Color(0xFF456179),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFCBE6FF),
    onTertiaryContainer = Color(0xFF001E31),
    surface = Color(0xFFF6FAF8),
    onSurface = Color(0xFF0F172A),
    surfaceVariant = Color(0xFFDBE4E1),
    onSurfaceVariant = Color(0xFF3F4947),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF0F4F3),
    surfaceContainer = Color(0xFFEAEFEF),
    surfaceContainerHigh = Color(0xFFE4E9E9),
    surfaceContainerHighest = Color(0xFFDEE3E3),
    outline = Color(0xFF6F7976),
    outlineVariant = Color(0xFFBEC9C6),
    error = Color(0xFFBA1A1A)
)

val DarkScheme = darkColorScheme(    primary = TealSoft,
    onPrimary = Color(0xFF003730),
    primaryContainer = TealPrimary,
    onPrimaryContainer = Color(0xFF9CF2E2),
    secondary = Color(0xFFB1CCC5),
    onSecondary = Color(0xFF1C3531),
    secondaryContainer = Color(0xFF324B47),
    onSecondaryContainer = Color(0xFFCDE8E1),
    tertiary = Color(0xFFA9CBE4),
    onTertiary = Color(0xFF0C3348),
    tertiaryContainer = Color(0xFF2A4A60),
    onTertiaryContainer = Color(0xFFCBE6FF),
    background = Color(0xFF0B0F19),
    surface = Color(0xFF0B0F19),
    onSurface = Color(0xFFF8FAFC),
    surfaceVariant = Color(0xFF1E293B),
    onSurfaceVariant = Color(0xFF94A3B8),
    surfaceContainerLowest = Color(0xFF060A12),
    surfaceContainerLow = Color(0xFF121827),
    surfaceContainer = Color(0xFF182032),
    surfaceContainerHigh = Color(0xFF222D44),
    surfaceContainerHighest = Color(0xFF2D3A54),
    outline = Color(0xFF334155),
    outlineVariant = Color(0xFF1E293B),
    error = Color(0xFFFFB4AB)
)

// True-black AMOLED variant of the dark scheme.
val BlackScheme = darkColorScheme(
    primary = TealSoft,
    onPrimary = Color(0xFF003730),
    primaryContainer = TealPrimary,
    onPrimaryContainer = Color(0xFF9CF2E2),
    secondary = Color(0xFFB1CCC5),
    onSecondary = Color(0xFF1C3531),
    secondaryContainer = Color(0xFF324B47),
    onSecondaryContainer = Color(0xFFCDE8E1),
    tertiary = Color(0xFFA9CBE4),
    onTertiary = Color(0xFF0C3348),
    tertiaryContainer = Color(0xFF2A4A60),
    onTertiaryContainer = Color(0xFFCBE6FF),
    background = Color(0xFF000000),
    surface = Color(0xFF000000),
    onSurface = Color(0xFFF8FAFC),
    surfaceVariant = Color(0xFF111111),
    onSurfaceVariant = Color(0xFF94A3B8),
    surfaceContainerLowest = Color(0xFF000000),
    surfaceContainerLow = Color(0xFF0A0A0A),
    surfaceContainer = Color(0xFF111111),
    surfaceContainerHigh = Color(0xFF1A1A1A),
    surfaceContainerHighest = Color(0xFF242424),
    outline = Color(0xFF333333),
    outlineVariant = Color(0xFF1A1A1A),
    error = Color(0xFFFFB4AB)
)
