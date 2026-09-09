package com.lightbrowser.ui.terminal

import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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

    /** Keyboard-visible flag as State (drives MainActivity tab hiding). */
    var imeVisible by mutableStateOf(false)

    /** Outer content bottom pad (MainActivity Scaffold), px. State: recomposes readers. */
    var outerPadPx by mutableIntStateOf(0)
}

/**
 * Bottom padding that hugs the keyboard: keys must end at
 * screenBottom − ime. Our layout bottom already sits at
 * screenBottom − outerPad, so pad = ime − outerPad (clamped ≥ 0).
 * No nav assumptions — correct whether or not the outer insets include IME.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun Modifier.keyboardHug(): Modifier {
    val density = LocalDensity.current
    val ime = WindowInsets.ime.getBottom(density)
    val pad = (ime - InsetDebug.outerPadPx).coerceAtLeast(0)
    return this.padding(bottom = with(density) { pad.toDp() })
}
