package com.lightbrowser

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.exclude
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.Icon
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

internal enum class Tab(
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

/**
 * Hoisted app-tab state (was AppShell-local remember: rotation reset + no
 * external writer). Screens request Browser via goBrowser() so Back unwinds
 * tab-internal state first and only then returns here — exit-arm is the
 * LAST step, never the first. edgeSwipe mirrors Prefs.edgeSwipe for
 * immediate recomposition when toggled in Settings.
 */
internal object AppTabs {
    var current by mutableStateOf(Tab.Browser)
    var edgeSwipe by mutableStateOf(false)
    fun goBrowser() { current = Tab.Browser }
}

class MainActivity : ComponentActivity() {

    // Keyboard state: fed ONLY by the decor inset listener below (single
    // writer — a second frame-based writer kept clobbering good values
    // with 0 under adjustNothing). Drives double-back-to-exit.
    private val keyboardOpenFlow = kotlinx.coroutines.flow.MutableStateFlow(false)

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
        try { AppTabs.edgeSwipe = Prefs.edgeSwipe } catch (_: Exception) {}
        try { com.lightbrowser.ui.settings.applyLang(this, try { Prefs.appLang } catch (_: Exception) { "system" }) } catch (_: Exception) {}
        val startUrl = intent?.data?.toString()?.takeIf { it.startsWith("http") }

        val decor = window.decorView
        // Keyboard signal: inset listener on the DECOR (dispatch root — immune
        // to child CONSUMED and to setContent timing; enableEdgeToEdge sets no
        // listener so there is no ordering hazard). Fires on IME show/hide/
        // resize in every adjust mode. max(ime, frame): the frame is frozen
        // at 0 under adjustNothing (by definition — no resize/pan), the inset
        // is the live one (suggestion strip included — it's inside the IME
        // window). Insets pass through unconsumed. requestApplyInsets forces
        // the initial dispatch so we never sit on stale 0.
        try {
            androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(decor) { _, insets ->
                try {
                    val ime = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.ime()).bottom
                    val nav = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.navigationBars()).bottom
                    val r = android.graphics.Rect()
                    decor.getWindowVisibleDisplayFrame(r)
                    val screenH = decor.height.coerceAtLeast(1)
                    val frameKb = (decor.height - r.bottom).coerceAtLeast(0)
                    com.lightbrowser.ui.terminal.InsetDebug.kbHeightPx.intValue = maxOf(ime, frameKb)
                    com.lightbrowser.ui.terminal.InsetDebug.sysNavPx.intValue = nav
                    keyboardOpenFlow.value = ime > 0 || frameKb > screenH * 0.15
                } catch (_: Exception) {}
                insets
            }
            androidx.core.view.ViewCompat.requestApplyInsets(decor)
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
                // Unconsumed IME height, read above ALL consumers (Scaffold +
                // drawer consume on the way down — keys-level reads can see a
                // short/zero value while this level stays live). Single source
                // for the terminal toolbar lift (see InsetDebug).
                val imeTopPx = WindowInsets.ime.getBottom(LocalDensity.current)
                SideEffect {
                    try { com.lightbrowser.ui.terminal.InsetDebug.composeImePx.intValue = imeTopPx } catch (_: Exception) {}
                }
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
    // Hoisted (AppTabs): survives rotation, writable from any screen's
    // BackHandler so Back returns to Browser before the exit arm.
    var tab by AppTabs::current
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
                            FilesScreen(
                                modifier = Modifier.fillMaxSize().offscreen(tab != Tab.Files),
                                active = tab == Tab.Files,
                                onExitToBrowser = { AppTabs.goBrowser() }
                            )
                            MusicScreen(
                                modifier = Modifier.fillMaxSize().offscreen(tab != Tab.Music),
                                active = tab == Tab.Music,
                                onExitToBrowser = { AppTabs.goBrowser() },
                                vm = musicVm
                            )
                            TerminalScreen(modifier = Modifier.fillMaxSize().offscreen(tab != Tab.Terminal), active = tab == Tab.Terminal)
                            ScriptsScreen(
                                modifier = Modifier.fillMaxSize().offscreen(tab != Tab.Scripts),
                                active = tab == Tab.Scripts,
                                onExitToBrowser = { AppTabs.goBrowser() }
                            )
                            DownloadsScreen(
                                modifier = Modifier.fillMaxSize().offscreen(tab != Tab.Downloads),
                                active = tab == Tab.Downloads,
                                onExitToBrowser = { AppTabs.goBrowser() }
                            )
                            SettingsScreen(
                                modifier = Modifier.fillMaxSize().offscreen(tab != Tab.Settings),
                                active = tab == Tab.Settings,
                                onExitToBrowser = { AppTabs.goBrowser() },
                                onThemeChange = onThemeChange
                            )
                            // Edge-swipe tab switching is OPT-IN (Settings →
                            // Navigation, default OFF): a full-height 44dp
                            // edge-drag stole the SYSTEM back gesture and the
                            // toolbar scroll. When on: a slim 20dp strip over
                            // the MIDDLE band only (top = status/shade, bottom
                            // = keys/keyboard stay clear) with a 120px
                            // threshold. Long-press on the LEFT edge always
                            // opens the terminal drawer (Terminal tab only) —
                            // taps/long-press never conflict with system nav.
                            val edgeOn = AppTabs.edgeSwipe
                            EdgeTabStrip(
                                current = tab,
                                onSelect = { tab = it },
                                onLongPress = if (tab == Tab.Terminal) {
                                    { com.lightbrowser.ui.terminal.InsetDebug.drawerAsk++ }
                                } else null,
                                dragEnabled = edgeOn,
                                modifier = Modifier.align(Alignment.CenterStart)
                                    .fillMaxHeight(0.55f)
                            )
                            EdgeTabStrip(
                                current = tab,
                                onSelect = { tab = it },
                                dragEnabled = edgeOn,
                                modifier = Modifier.align(Alignment.CenterEnd)
                                    .fillMaxHeight(0.55f)
                            )
                        }
                        // Bottom zone: MiniPlayer only. No tab bar of any kind —
                        // tabs switch via the left/right edge-swipe strips
                        // (below). Nothing here changes size with the keyboard,
                        // so the outer bottom inset is static and terminal math
                        // stays exact. (Terminal keeps its own keys above the
                        // keyboard.)
                        Column {
                            if (tab != Tab.Music) {
                                MiniPlayer(vm = musicVm, onExpand = { tab = Tab.Music })
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
 * Edge-swipe tab switching (both screen sides, OPT-IN via Settings).
 * A slim 20dp strip floats over the MIDDLE band: drag horizontally far
 * (>120px) to move between main tabs. Taps and vertical scrolls pass
 * through untouched. Long-press (left edge only, via onLongPress) is the
 * terminal drawer's handle. With dragEnabled=false only the long-press
 * detector is active, so the system back gesture is never disturbed.
 */
@Composable
private fun EdgeTabStrip(
    current: Tab,
    onSelect: (Tab) -> Unit,
    modifier: Modifier = Modifier,
    onLongPress: (() -> Unit)? = null,
    dragEnabled: Boolean = true
) {
    val order = remember { Tab.entries.filter { it.inBar } }
    val idx = order.indexOf(current).coerceAtLeast(0)
    var drag by remember { mutableStateOf(0f) }
    Box(
        modifier = modifier.width(20.dp)
            .then(if (dragEnabled) {
                Modifier.pointerInput(idx) {
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            if (drag < -120) {
                                val n = (idx + 1).coerceAtMost(order.lastIndex)
                                if (n != idx) onSelect(order[n])
                            } else if (drag > 120) {
                                val n = (idx - 1).coerceAtLeast(0)
                                if (n != idx) onSelect(order[n])
                            }
                            drag = 0f
                        },
                        onDragCancel = { drag = 0f },
                        onHorizontalDrag = { _, dx -> drag += dx }
                    )
                }
            } else Modifier)
            .pointerInput(onLongPress, idx) {
                detectTapGestures(
                    onLongPress = { try { onLongPress?.invoke() } catch (_: Exception) {} }
                )
            }
    )
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
