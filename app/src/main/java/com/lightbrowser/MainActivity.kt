package com.lightbrowser

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.exclude
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lightbrowser.data.AppCtx
import com.lightbrowser.data.Prefs
import com.lightbrowser.ui.browser.BrowserScreen
import com.lightbrowser.ui.browser.BrowserViewModel
import com.lightbrowser.ui.downloads.DownloadsScreen
import com.lightbrowser.ui.files.FilesScreen
import com.lightbrowser.ui.music.MiniPlayer
import com.lightbrowser.ui.music.MusicScreen
import com.lightbrowser.ui.music.MusicViewModel
import com.lightbrowser.ui.scripts.ScriptsScreen
import com.lightbrowser.ui.settings.SettingsScreen
import com.lightbrowser.ui.terminal.TerminalScreen
import com.lightbrowser.ui.theme.LightBrowserTheme
import kotlinx.coroutines.launch

private enum class Tab(
    val title: String,
    val icon: ImageVector,
    val inBar: Boolean
) {
    Browser("Browser", Icons.Filled.Language, true),
    Terminal("Terminal", Icons.Filled.Terminal, true),
    Files("Sandbox", Icons.Filled.Folder, true),
    Music("Player", Icons.Filled.AudioFile, true),
    Scripts("Scripts", Icons.Filled.Description, false),
    Downloads("Downloads", Icons.Filled.Download, false),
    Settings("Settings", Icons.Filled.Settings, false)
}

class MainActivity : ComponentActivity() {

    // Resize-proof keyboard signal: measures the visible window frame, so it works
    // even on devices where the window shrinks for the keyboard (insets read 0 there).
    private val keyboardOpenFlow = kotlinx.coroutines.flow.MutableStateFlow(false)
    private var layoutListener: android.view.ViewTreeObserver.OnGlobalLayoutListener? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        backMainHandler = android.os.Handler(mainLooper)
        enableEdgeToEdge()
        // Insurance: keyboard must NEVER resize the window (that shoves the bottom
        // nav above the keys). In bookmark-manager terms: the nav bar stays docked.
        try {
            androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        } catch (_: Exception) {}
        try { AppCtx.init(this) } catch (_: Exception) {}
        try { com.lightbrowser.ui.settings.applyLang(this, try { Prefs.appLang } catch (_: Exception) { "system" }) } catch (_: Exception) {}
        val startUrl = intent?.data?.toString()?.takeIf { it.startsWith("http") }

        val decor = window.decorView
        layoutListener = android.view.ViewTreeObserver.OnGlobalLayoutListener {
            try {
                val r = android.graphics.Rect()
                decor.getWindowVisibleDisplayFrame(r)
                val screenH = decor.height.coerceAtLeast(1)
                val keyH = screenH - r.bottom
                keyboardOpenFlow.value = keyH > screenH * 0.15
                // Measured lift for terminal keys: the visible frame excludes
                // the WHOLE IME window (suggestion strip included — Compose
                // IME insets may omit it). Publish height + static nav inset.
                try {
                    com.lightbrowser.ui.terminal.InsetDebug.kbHeightPx.intValue =
                        if (keyH > screenH * 0.15) keyH else 0
                    val ri = decor.rootWindowInsets
                    if (ri != null) {
                        val compat = androidx.core.view.WindowInsetsCompat
                            .toWindowInsetsCompat(ri, decor)
                        com.lightbrowser.ui.terminal.InsetDebug.sysNavPx.intValue =
                            compat.getInsets(
                                androidx.core.view.WindowInsetsCompat.Type.navigationBars()
                            ).bottom
                    }
                } catch (_: Exception) {}
            } catch (_: Exception) {}
        }
        try {
            decor.viewTreeObserver.addOnGlobalLayoutListener(layoutListener)
        } catch (_: Exception) {}

        // Double-back to exit. Compose BackHandlers (search collapse, web go-back)
        // run first; this fires only when nothing else consumes back.
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (keyboardOpenFlow.value) {
                    hideKeyboard()
                    return
                }
                if (backArmed) {
                    finish()
                    return
                }
                backArmed = true
                try {
                    android.widget.Toast.makeText(this@MainActivity, "Press back again to exit", android.widget.Toast.LENGTH_SHORT).show()
                } catch (_: Exception) {}
                try {
                    backHandler?.let { backMainHandler?.removeCallbacks(it) }
                    val r = Runnable { backArmed = false }
                    backHandler = r
                    backMainHandler?.postDelayed(r, 2000)
                } catch (_: Exception) {}
            }
        })

        setContent {
            var themeMode by androidx.compose.runtime.saveable.rememberSaveable {
                mutableStateOf(try { Prefs.themeMode } catch (_: Exception) { "system" })
            }
            val dark = when (themeMode) {
                "dark" -> true
                "light" -> false
                else -> androidx.compose.foundation.isSystemInDarkTheme()
            }
            val black = try { Prefs.trueBlack } catch (_: Exception) { false }
            val uiScale = try { Prefs.uiFontScale.coerceIn(0.7f, 1.6f) } catch (_: Exception) { 1f }
            val keyboardOpen by keyboardOpenFlow.collectAsState()
            // Persist theme changes (was memory-only → rotation flicker).
            LightBrowserTheme(darkTheme = dark, blackTheme = black && dark) {
                val baseDensity = androidx.compose.ui.platform.LocalDensity.current
                // Scale font only (was density*scale → double-scaled dp layouts at large uiScale).
                val scaled = remember(baseDensity, uiScale) {
                    androidx.compose.ui.unit.Density(baseDensity.density, baseDensity.fontScale * uiScale)
                }
                androidx.compose.runtime.CompositionLocalProvider(
                    androidx.compose.ui.platform.LocalDensity provides scaled
                ) {
                    AppShell(
                        startUrl = startUrl,
                        keyboardOpen = keyboardOpen,
                        onThemeChange = {
                            themeMode = it
                            try { Prefs.themeMode = it } catch (_: Exception) {}
                        }
                    )
                }
            }
        }
    }

    private var backHandler: Runnable? = null
    // Initialized in onCreate — field initializers run before attach, when
    // getMainLooper() still throws NPE (that was the install-launch crash).
    private var backMainHandler: android.os.Handler? = null

    override fun onDestroy() {
        try {
            layoutListener?.let { window.decorView.viewTreeObserver.removeOnGlobalLayoutListener(it) }
        } catch (_: Exception) {}
        layoutListener = null
        try { backHandler?.let { backMainHandler?.removeCallbacks(it) } } catch (_: Exception) {}
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // Forward warm deep-links to current browser (was dropped).
        try {
            val url = intent.data?.toString()?.takeIf { it.startsWith("http") } ?: return
            // Can't touch viewModel here; store for AppShell via intent extra.
            intent.putExtra("lb_forward_url", url)
        } catch (_: Exception) {}
    }

    // ── Double-back to exit: 1st back hides the keyboard (or arms), 2nd exits ──
    private var backArmed = false

    private fun hideKeyboard() {
        try {
            val imm = getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
            currentFocus?.let {
                imm.hideSoftInputFromWindow(it.windowToken, 0)
                it.clearFocus()
            } ?: imm.hideSoftInputFromWindow(window.decorView.windowToken, 0)
        } catch (_: Exception) {}
    }
}

@Composable
private fun AppShell(
    startUrl: String?,
    keyboardOpen: Boolean,
    onThemeChange: (String) -> Unit
) {
    // Plain remember (rotation resets to Browser — same as last working build).
    // rememberSaveable with a custom Saver over the private Tab enum is an
    // R8/startup risk; theme (String) stays saveable, it is natively supported.
    var tab by remember { mutableStateOf(Tab.Browser) }
    var showAbout by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    // Activity-scoped shared VMs: MiniPlayer and screens observe the same state.
    val browserVm: BrowserViewModel = viewModel()
    val musicVm: MusicViewModel = viewModel()

    fun open(t: Tab) {
        tab = t
        scope.launch { try { drawerState.close() } catch (_: Exception) {} }
    }

    LaunchedEffect(startUrl) {
        if (startUrl != null) {
            tab = Tab.Browser
            try {
                val url = browserVm.resolveInput(startUrl).ifBlank { startUrl }
                browserVm.onPageStarted(url)
                browserVm.requestLoad(url)
            } catch (_: Exception) {}
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        // Drawer gesture OFF for now (opens randomly over the browser; no hamburger yet).
        // Scripts/Downloads/Settings stay reachable via the browser ⋮ menu.
        gesturesEnabled = false,
        drawerContent = {
            ModalDrawerSheet {
                Text("LightBrowser", style = androidx.compose.material3.MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(start = 20.dp, top = 20.dp, end = 20.dp))
                Text(
                    "v${try { com.lightbrowser.BuildConfig.VERSION_NAME } catch (_: Exception) { "?" }} (${try { com.lightbrowser.BuildConfig.VERSION_CODE } catch (_: Exception) { "?" }})",
                    style = androidx.compose.material3.MaterialTheme.typography.labelMedium,
                    color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 20.dp, bottom = 8.dp)
                )
                Tab.entries.forEach { t ->
                    NavigationDrawerItem(
                        label = { Text(t.title) },
                        selected = tab == t,
                        onClick = { open(t) },
                        icon = { Icon(t.icon, null) },
                        modifier = Modifier.padding(horizontal = 12.dp)
                    )
                }
                NavigationDrawerItem(
                    label = { Text("About") },
                    selected = false,
                    onClick = {
                        showAbout = true
                        scope.launch { try { drawerState.close() } catch (_: Exception) {} }
                    },
                    icon = { Icon(Icons.Filled.Info, null) },
                    modifier = Modifier.padding(horizontal = 12.dp)
                )
            }
        }
    ) {
        // safeDrawing INCLUDES the IME — exclude it so the keyboard overlays
        // the pinned bottom zone instead of pushing it up (adjustNothing).
        Scaffold(contentWindowInsets = WindowInsets.safeDrawing.exclude(WindowInsets.ime)) { inner ->
            // Publish the real outer bottom pad for `kbd-diag` (terminal
            // root-lift math needs no nav assumptions).
            val outerDensity = LocalDensity.current
            SideEffect {
                try {
                    com.lightbrowser.ui.terminal.InsetDebug.outerPadPx =
                        with(outerDensity) { inner.calculateBottomPadding().toPx().toInt() }
                } catch (_: Exception) {}
            }
            BoxWithConstraints(modifier = Modifier.fillMaxSize().padding(inner).consumeWindowInsets(inner)) {
                val wide = maxWidth >= 600.dp
                Row(modifier = Modifier.fillMaxSize()) {
                    if (wide) {
                        NavigationRail {
                            Tab.entries.filter { it.inBar }.forEach { t ->
                                NavigationRailItem(
                                    selected = tab == t,
                                    onClick = { tab = t },
                                    icon = { Icon(t.icon, t.title) },
                                    label = { Text(t.title) }
                                )
                            }
                        }
                    }
                    Column(modifier = Modifier.weight(1f).fillMaxSize()) {
                        // ALL tabs stay composed (WebView keeps its page, lists keep
                        // scroll, terminal keeps colors). Inactive ones are parked
                        // offscreen: no destroy, no reload, no state reset, no touch.
                        Box(modifier = Modifier.weight(1f).fillMaxSize()) {
                            BrowserScreen(
                                modifier = Modifier.fillMaxSize().offscreen(tab != Tab.Browser),
                                active = tab == Tab.Browser,
                                keyboardOpen = keyboardOpen,
                                onOpenScripts = { tab = Tab.Scripts },
                                onOpenDownloads = { tab = Tab.Downloads },
                                onOpenSettings = { tab = Tab.Settings },
                                vm = browserVm
                            )
                            FilesScreen(modifier = Modifier.fillMaxSize().offscreen(tab != Tab.Files))
                            MusicScreen(modifier = Modifier.fillMaxSize().offscreen(tab != Tab.Music), vm = musicVm)
                            TerminalScreen(modifier = Modifier.fillMaxSize().offscreen(tab != Tab.Terminal))
                            ScriptsScreen(modifier = Modifier.fillMaxSize().offscreen(tab != Tab.Scripts))
                            DownloadsScreen(modifier = Modifier.fillMaxSize().offscreen(tab != Tab.Downloads))
                            SettingsScreen(modifier = Modifier.fillMaxSize().offscreen(tab != Tab.Settings), onThemeChange = onThemeChange)
                        }
                        // Bottom zone is pinned behind the keyboard (adjustNothing +
                        // IME excluded above). No tall tab bar: a slim swipe strip
                        // (drag L/R, tap dots/chevrons) switches Browser ⇄
                        // Terminal ⇄ Sandbox ⇄ Player. The gesture lives on the
                        // strip only, so it never fights WebView scrolling.
                        // Hidden while typing (space for the terminal, which
                        // keeps its own keys above the keyboard). Instant
                        // show/hide, no animation, no lag.
                        val kbOpen = com.lightbrowser.ui.terminal.InsetDebug.imeVisible
                        Column {
                            if (tab != Tab.Music) {
                                MiniPlayer(vm = musicVm, onExpand = { tab = Tab.Music })
                            }
                            if (!wide && !kbOpen) {
                                TabSwipeStrip(current = tab, onSelect = { tab = it })
                            }
                        }
                    }
                }
            }
        }
    }

    if (showAbout) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showAbout = false },
            title = { Text("LightBrowser 3.1") },
            text = { Text("v${try { com.lightbrowser.BuildConfig.VERSION_NAME } catch (_: Exception) { "?" }} (${try { com.lightbrowser.BuildConfig.VERSION_CODE } catch (_: Exception) { "?" }})\n\nBrowser · Sandbox · Terminal (Alpine) · Player\n\nCompose + Material 3 Expressive · ExoPlayer") },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = { showAbout = false }) { Text("OK") }
            }
        )
    }
}

/**
 * Slim bottom tab switcher replacing the tall NavigationBar. Drag left/right
 * on the strip to move between main tabs (Browser ⇄ Terminal ⇄ Sandbox ⇄
 * Player); tap a dot or chevron to jump. The touch target is the strip only,
 * so swipes never fight WebView scrolling. Hidden while the keyboard is up.
 */
@Composable
private fun TabSwipeStrip(current: Tab, onSelect: (Tab) -> Unit) {
    val order = remember { Tab.entries.filter { it.inBar } }
    val idx = order.indexOf(current).coerceAtLeast(0)
    val scheme = MaterialTheme.colorScheme
    var drag by remember { mutableStateOf(0f) }
    Row(
        modifier = Modifier.fillMaxWidth().height(32.dp)
            .pointerInput(idx) {
                detectHorizontalDragGestures(
                    onDragEnd = {
                        if (drag < -60) {
                            val n = (idx + 1).coerceAtMost(order.lastIndex)
                            if (n != idx) onSelect(order[n])
                        } else if (drag > 60) {
                            val n = (idx - 1).coerceAtLeast(0)
                            if (n != idx) onSelect(order[n])
                        }
                        drag = 0f
                    },
                    onDragCancel = { drag = 0f },
                    onHorizontalDrag = { _, dx -> drag += dx }
                )
            },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        IconButton(
            onClick = {
                val n = (idx - 1).coerceAtLeast(0)
                if (n != idx) onSelect(order[n])
            },
            modifier = Modifier.size(32.dp)
        ) {
            Icon(Icons.Filled.ChevronLeft, "Previous tab")
        }
        order.forEach { t ->
            val active = t == order[idx]
            Box(
                modifier = Modifier.padding(horizontal = 5.dp)
                    .size(width = if (active) 18.dp else 7.dp, height = 7.dp)
                    .background(
                        if (active) scheme.primary
                        else scheme.onSurfaceVariant.copy(alpha = 0.4f),
                        CircleShape
                    )
                    .clickable { onSelect(t) }
            )
        }
        IconButton(
            onClick = {
                val n = (idx + 1).coerceAtMost(order.lastIndex)
                if (n != idx) onSelect(order[n])
            },
            modifier = Modifier.size(32.dp)
        ) {
            Icon(Icons.Filled.ChevronRight, "Next tab")
        }
    }
}

/** Parks hidden tabs far offscreen: still composed (state kept), never touched.
 *  Also hides from accessibility when hidden (was still focusable). */
private fun Modifier.offscreen(hidden: Boolean): Modifier =
    this.then(
        if (hidden) {
            Modifier.layout { measurable, constraints ->
                val placeable = measurable.measure(constraints)
                layout(placeable.width, placeable.height) {
                    placeable.placeRelative(-100_000, -100_000)
                }
            }.clearAndSetSemantics { }
        } else Modifier
    )
