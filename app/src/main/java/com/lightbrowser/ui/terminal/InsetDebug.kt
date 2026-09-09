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

    /**
     * Measured keyboard height, px (visible window frame — includes
     * suggestion strips that Compose IME insets may omit). 0 when closed.
     * Published by MainActivity's layout listener; drives the keys lift.
     */
    var kbHeightPx = mutableIntStateOf(0)

    /** Static system navigation-bar inset, px (already reserved below us). */
    var sysNavPx = mutableIntStateOf(0)
}

/**
 * Measured keyboard lift: visible-frame keyboard height (suggestion strip
 * included — Compose IME insets may omit it) minus the static system-nav
 * inset the outer Scaffold already reserves below us. Zero when closed.
 * Immune to inset-consumption quirks: pure measurement, no inset reads.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun Modifier.keyboardLift(): Modifier {
    val density = LocalDensity.current
    val kb = InsetDebug.kbHeightPx.intValue
    val nav = InsetDebug.sysNavPx.intValue
    val pad = (kb - nav).coerceAtLeast(0)
    return this.padding(bottom = with(density) { pad.toDp() })
}
