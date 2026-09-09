package com.lightbrowser.ui.terminal

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
