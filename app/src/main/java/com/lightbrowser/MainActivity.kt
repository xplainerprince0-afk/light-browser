package com.lightbrowser

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
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
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
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
    Files("Sandbox", Icons.Filled.Folder, true),
    Music("Player", Icons.Filled.AudioFile, true),
    Terminal("Terminal", Icons.Filled.Terminal, true),
    Scripts("Scripts", Icons.Filled.Description, false),
    Downloads("Downloads", Icons.Filled.Download, false),
    Settings("Settings", Icons.Filled.Settings, false)
}

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Insurance: keyboard must NEVER resize the window (that shoves the bottom
        // nav above the keys). In bookmark-manager terms: the nav bar stays docked.
        try {
            androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        } catch (_: Exception) {}
        try { AppCtx.init(this) } catch (_: Exception) {}
        val startUrl = intent?.data?.toString()?.takeIf { it.startsWith("http") }

        setContent {
            var themeMode by remember {
                mutableStateOf(try { Prefs.themeMode } catch (_: Exception) { "system" })
            }
            val dark = when (themeMode) {
                "dark" -> true
                "light" -> false
                else -> androidx.compose.foundation.isSystemInDarkTheme()
            }
            LightBrowserTheme(darkTheme = dark) {
                AppShell(
                    startUrl = startUrl,
                    onThemeChange = { themeMode = it }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }
}

@Composable
private fun AppShell(
    startUrl: String?,
    onThemeChange: (String) -> Unit
) {
    var tab by remember { mutableStateOf(Tab.Browser) }
    var showAbout by remember { mutableStateOf(false) }
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
                Text("LightBrowser", style = androidx.compose.material3.MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(20.dp))
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
        // contentWindowInsets = safeDrawing (no IME) — inputs add imePadding themselves.
        // This is the exact fix for the old black-gap-above-keyboard bug.
        Scaffold(contentWindowInsets = WindowInsets.safeDrawing) { inner ->
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
                        Box(modifier = Modifier.weight(1f).fillMaxSize()) {
                            when (tab) {
                                Tab.Browser -> BrowserScreen(
                                    onOpenScripts = { tab = Tab.Scripts },
                                    onOpenDownloads = { tab = Tab.Downloads },
                                    onOpenSettings = { tab = Tab.Settings },
                                    vm = browserVm
                                )
                                Tab.Files -> FilesScreen()
                                Tab.Music -> MusicScreen(vm = musicVm)
                                Tab.Terminal -> TerminalScreen()
                                Tab.Scripts -> ScriptsScreen()
                                Tab.Downloads -> DownloadsScreen()
                                Tab.Settings -> SettingsScreen(onThemeChange = onThemeChange)
                            }
                        }
                        // Bottom zone is IME-immune: even if IME insets arrive here, the
                        // mini-player + tab bar ignore them and stay anchored at the
                        // very bottom; the keyboard overlays them instead of pushing up.
                        Column(modifier = Modifier.consumeWindowInsets(WindowInsets.ime)) {
                            if (tab != Tab.Music) {
                                MiniPlayer(vm = musicVm, onExpand = { tab = Tab.Music })
                            }
                            if (!wide) {
                                NavigationBar {
                                    Tab.entries.filter { it.inBar }.forEach { t ->
                                        NavigationBarItem(
                                            selected = tab == t,
                                            onClick = { tab = t },
                                            icon = { Icon(t.icon, t.title) },
                                            label = { Text(t.title) }
                                        )
                                    }
                                }
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
            title = { Text("LightBrowser 3.0") },
            text = { Text("Browser · Sandbox · Terminal (Alpine) · Player\n\nCompose + Material 3 Expressive · ExoPlayer") },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = { showAbout = false }) { Text("OK") }
            }
        )
    }
}
