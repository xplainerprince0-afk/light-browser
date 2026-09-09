package com.lightbrowser.ui.terminal

import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity

/**
 * Live inset readings for `kbd-diag`: Compose captures these during
 * composition (see TerminalScreen), the ViewModel prints them. Used to
 * diagnose keyboard-vs-keys spacing without guessing.
 */
object InsetDebug {
    @Volatile var imeBottomPx: Int = -1
    @Volatile var navBottomPx: Int = -1
    @Volatile var imeVisible: Boolean = false
}

/**
 * Bottom padding that hugs the keyboard: the IME inset spans to the screen
 * bottom (covering the nav zone), but our layout bottom already sits above
 * the nav bar (outer padding) — so plain imePadding() floats the keys by the
 * nav height. Subtract it; clamp at 0 when the keyboard is closed.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun Modifier.keyboardHug(): Modifier {
    val density = LocalDensity.current
    val ime = WindowInsets.ime.getBottom(density)
    val nav = WindowInsets.navigationBars.getBottom(density)
    val pad = (ime - nav).coerceAtLeast(0)
    return this.padding(bottom = with(density) { pad.toDp() })
}
