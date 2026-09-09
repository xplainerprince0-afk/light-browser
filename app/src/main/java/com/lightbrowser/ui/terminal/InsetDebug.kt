package com.lightbrowser.ui.terminal

import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
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

    /**
     * Compose IME height read at the setContent ROOT (above Scaffold/drawer
     * consumption). The keys-level read can see a consumed (short/zero)
     * value — inner Scaffold + drawer consume on the way down — while this
     * level provably sees the live value (imeVisible, read one level down,
     * is what hides the bottom UI). Published by MainActivity.
     */
    var composeImePx = mutableIntStateOf(0)

    /**
     * Drawer open requests (edge-strip long-press lives in MainActivity and
     * can't reach TerminalScreen's drawer directly). TerminalScreen observes
     * and opens; only incremented while the Terminal tab is frontmost.
     */
    var drawerAsk by mutableLongStateOf(0L)
}

/**
 * Keyboard lift for terminal keys (single source — no per-mode math).
 * Signal: max(decor-listener height, hoisted root IME, live keys-level IME)
 * so one dead/lying source can't bury the toolbar. Minus outerPadPx (the
 * EXACT px MainActivity's Scaffold reserves below the content): keysBottom =
 * screenH − outerPad − (kb − outerPad) = screenH − kb under every inset
 * convention. Zero when closed.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun Modifier.keyboardLift(): Modifier {
    val density = LocalDensity.current
    val kb = maxOf(
        InsetDebug.kbHeightPx.intValue,
        InsetDebug.composeImePx.intValue,
        WindowInsets.ime.getBottom(density)
    )
    val outer = InsetDebug.outerPadPx
    val pad = (kb - outer).coerceAtLeast(0)
    return this.padding(bottom = with(density) { pad.toDp() })
}
