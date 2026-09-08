package com.lightbrowser.ui.browser

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.webkit.WebView
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.Article
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.DesktopWindows
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.FindReplace
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lightbrowser.data.DownloadHelper
import com.lightbrowser.data.Prefs
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowserScreen(
    modifier: Modifier = Modifier,
    active: Boolean = true,
    keyboardOpen: Boolean = false,
    vm: BrowserViewModel = viewModel(),
    onOpenScripts: () -> Unit,
    onOpenDownloads: () -> Unit,
    onOpenSettings: () -> Unit
) {
    val ctx = LocalContext.current
    val ui by vm.ui.collectAsState()
    val history by vm.history.collectAsState()
    val bookmarks by vm.bookmarks.collectAsState()
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current

    // ── True multi-WebView pool: tabId → WebView (max 4, evict oldest background) ──
    // Each tab keeps its own history/scroll/form. Only current is visible; others are
    // kept alive offscreen so back/forward works per-tab like Chrome.
    val webViews = remember { androidx.compose.runtime.mutableStateMapOf<String, WebView>() }
    // Current tab id derived from index; stable across recompositions.
    val currentTabId = ui.tabs.getOrNull(ui.currentIndex)?.id
    var currentWebView by remember { mutableStateOf<WebView?>(null) }
    fun activeWebView(): WebView? = currentTabId?.let { webViews[it] } ?: currentWebView
    // Current WebView (recomputed each recomposition from pool).
    val webView: WebView? = activeWebView()
    var canGoBack by remember { mutableStateOf(false) }
    var canGoForward by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    var showTabs by remember { mutableStateOf(false) }
    var showHistory by remember { mutableStateOf(false) }
    var showBookmarks by remember { mutableStateOf(false) }
    var longPressUrl by remember { mutableStateOf<String?>(null) }
    var findOpen by remember { mutableStateOf(false) }
    var findQuery by remember { mutableStateOf("") }
    var sheetSearch by remember { mutableStateOf("") }
    var showAgent by remember { mutableStateOf(false) }
    var showReader by remember { mutableStateOf(false) }
    var showSite by remember { mutableStateOf(false) }
    var prefsVer by remember { mutableStateOf(0) }

    // Route TabBus window.open → new tab.
    LaunchedEffect(Unit) {
        TabBus.openInNewTab = { url -> try { vm.openTab(url, select = true) } catch (_: Exception) {} }
    }

    val loadReq by vm.loadRequest.collectAsState()
    val findCount by vm.findCount.collectAsState()
    val reader by vm.reader.collectAsState()
    val recording by com.lightbrowser.data.BrowserAgent.recording.collectAsState()
    // Retry load if WebView not yet created (factory race): keep pending until applied.
    var pendingLoad by remember { mutableStateOf<Pair<String, Long>?>(null) }
    LaunchedEffect(loadReq) {
        val (url, ts) = loadReq ?: return@LaunchedEffect
        if (url.startsWith("lb://")) return@LaunchedEffect
        pendingLoad = url to ts
    }
    // Apply pending load to CURRENT tab's WebView only, with URL check + retry.
    LaunchedEffect(pendingLoad, currentTabId) {
        val (url, _) = pendingLoad ?: return@LaunchedEffect
        val wv = currentTabId?.let { webViews[it] } ?: return@LaunchedEffect
        try {
            if (wv.url != url) wv.loadUrl(url, mapOf("X-Requested-With" to ""))
            pendingLoad = null
        } catch (_: Exception) {}
    }
    // Keep agent pointed at current WebView.
    LaunchedEffect(currentTabId, webViews.size) {
        try { com.lightbrowser.data.BrowserAgent.webViewProvider = { activeWebView() } } catch (_: Exception) {}
    }

    BackHandler(enabled = active && ui.searchExpanded) {
        vm.setSearch(false)
        focusManager.clearFocus()
    }
    // canGoBack state (updated on progress/finish) drives enablement — not webView?.canGoBack()
    // directly (non-reactive). Falls back to live check for safety.
    BackHandler(enabled = active && !ui.searchExpanded && !keyboardOpen && (canGoBack || webView?.canGoBack() == true)) {
        try {
            val wv = activeWebView()
            if (wv?.canGoBack() == true) wv.goBack()
        } catch (_: Exception) {}
    }

    // Pause all background WebViews when tab hidden; resume current when visible.
    // Also flush cookies on pause (BrowserProfile never got onWebViewPause before).
    LaunchedEffect(active) {
        try {
            if (active) {
                activeWebView()?.onResume()
                try { com.lightbrowser.data.BrowserProfile.onWebViewResume(activeWebView()) } catch (_: Exception) {}
            } else {
                webViews.values.forEach { try { it.onPause() } catch (_: Exception) {} }
                try { com.lightbrowser.data.BrowserProfile.onWebViewPause(activeWebView()) } catch (_: Exception) {}
            }
        } catch (_: Exception) {}
    }

    DisposableEffect(Unit) {
        onDispose {
            try {
                webViews.values.forEach { try { it.stopLoading(); it.destroy() } catch (_: Exception) {} }
                webViews.clear()
            } catch (_: Exception) {}
            try { TabBus.openInNewTab = null } catch (_: Exception) {}
        }
    }
    // Evict closed tabs' WebViews + cap pool at 4 (destroy oldest background first).
    LaunchedEffect(ui.tabs.map { it.id }, currentTabId) {
        try {
            val alive = ui.tabs.map { it.id }.toSet()
            (webViews.keys - alive).forEach { id ->
                try { webViews.remove(id)?.destroy() } catch (_: Exception) { try { webViews.remove(id) } catch (_: Exception) {} }
            }
            if (webViews.size > 4 && currentTabId != null) {
                val candidates = webViews.keys.filter { it != currentTabId }.take(webViews.size - 4)
                candidates.forEach { id -> try { webViews.remove(id)?.destroy() } catch (_: Exception) {} }
            }
        } catch (_: Exception) {}
    }

    fun currentDomain(): String = try {
        android.net.Uri.parse(ui.currentUrl).host ?: ui.currentUrl.ifBlank { "Search or enter URL" }
    } catch (_: Exception) {
        ui.currentUrl.ifBlank { "Search or enter URL" }
    }

    fun goTo(url: String) {
        // Same page (e.g. tapping the current suggestion) must NOT reload.
        try {
            val cur = webView?.url
            if (url.isNotBlank() && cur != null && cur == url) {
                vm.setSearch(false)
                focusManager.clearFocus()
                return
            }
        } catch (_: Exception) {}
        vm.onPageStarted(url)
        if (url.startsWith("lb://")) return // native screen, nothing to load
        try { webView?.loadUrl(url, mapOf("X-Requested-With" to "")) } catch (_: Exception) {}
    }

    // FIXED search: pill or editor lives at the top of a plain Column, the WebView
    // below is ALWAYS composed (never destroyed). No imePadding anywhere here —
    // the keyboard overlays the bottom instead of pushing content up.
    Column(modifier = modifier.fillMaxSize()) {
        if (ui.searchExpanded) {
            val focusReq = remember(ui.searchExpanded) { FocusRequester() }
            OutlinedTextField(
                value = ui.searchQuery,
                onValueChange = vm::setQuery,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp).focusRequester(focusReq),
                placeholder = { Text("Search or enter URL") },
                leadingIcon = { Icon(Icons.Filled.Search, null) },
                trailingIcon = {
                    if (ui.searchQuery.isNotEmpty()) {
                        // X clears the text and stays in search (never navigates).
                        IconButton(onClick = { vm.setQuery("") }) { Icon(Icons.Filled.Close, "Clear") }
                    }
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = {
                    val url = vm.resolveInput(ui.searchQuery)
                    if (url.isNotEmpty()) goTo(url)
                    // Don't collapse on empty input — keeps context.
                    if (ui.searchQuery.isNotBlank()) { vm.setSearch(false); focusManager.clearFocus() }
                }),
                shape = MaterialTheme.shapes.extraLarge
            )
            LaunchedEffect(ui.searchExpanded) {
                try {
                    kotlinx.coroutines.delay(80)
                    focusReq.requestFocus()
                } catch (_: Exception) {}
            }
        } else {
            Surface(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
                shape = MaterialTheme.shapes.extraLarge,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                tonalElevation = 2.dp,
                onClick = { vm.setSearch(true) }
            ) {
                Row(modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { try { webView?.goBack() } catch (_: Exception) {} }, enabled = canGoBack) {
                        Icon(Icons.Filled.ArrowBack, "Back")
                    }
                    IconButton(onClick = { try { webView?.goForward() } catch (_: Exception) {} }, enabled = canGoForward) {
                        Icon(Icons.Filled.ArrowForward, "Forward")
                    }
                    Icon(
                        if (ui.currentUrl.startsWith("https://")) Icons.Filled.Lock else Icons.Filled.Warning,
                        null,
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        currentDomain(),
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodyMedium
                    )
                    if (ui.loading) {
                        IconButton(onClick = { try { webView?.stopLoading() } catch (_: Exception) {} }) {
                            Icon(Icons.Filled.Close, "Stop")
                        }
                    } else {
                        IconButton(onClick = { try { webView?.reload() } catch (_: Exception) {} }) {
                            Icon(Icons.Filled.Refresh, "Reload")
                        }
                    }
                    // Plain tabs button — no count badge
                    TextButton(onClick = { showTabs = true }) { Text("Tabs") }
                    IconButton(onClick = { showMenu = true }) {
                        Icon(
                            Icons.Filled.MoreVert, "Menu",
                            tint = if (recording) androidx.compose.ui.graphics.Color.Red else MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }

            AnimatedVisibility(visible = ui.loading, enter = fadeIn(), exit = fadeOut()) {
                LinearProgressIndicator(
                    progress = { (ui.progress.coerceIn(0, 100)) / 100f },
                    modifier = Modifier.fillMaxWidth().height(3.dp)
                )
            }

            AnimatedVisibility(visible = findOpen) {
                Surface(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), shape = MaterialTheme.shapes.large, tonalElevation = 2.dp) {
                    Row(modifier = Modifier.padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = findQuery,
                            onValueChange = {
                                findQuery = it
                                // Debounce: delay then find (cancels previous via scope).
                                scope.launch {
                                    kotlinx.coroutines.delay(250)
                                    if (findQuery == it) {
                                        try {
                                            if (it.isBlank()) activeWebView()?.findAllAsync("")
                                            else activeWebView()?.findAllAsync(it)
                                        } catch (_: Exception) {}
                                    }
                                }
                            },
                            modifier = Modifier.weight(1f),
                            placeholder = { Text("Find in page") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(onSearch = {
                                try { activeWebView()?.findNext(true) } catch (_: Exception) {}
                            }),
                            shape = MaterialTheme.shapes.large
                        )
                        IconButton(onClick = { try { webView?.findNext(false) } catch (_: Exception) {} }) {
                            Icon(Icons.Filled.KeyboardArrowUp, "Prev")
                        }
                        findCount?.let { (at, total) ->
                            Text(
                                if (total > 0) "$at/$total" else "0/0",
                                style = MaterialTheme.typography.labelMedium,
                                color = if (total > 0) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                                modifier = Modifier.padding(horizontal = 4.dp)
                            )
                        }
                        IconButton(onClick = { try { webView?.findNext(true) } catch (_: Exception) {} }) {
                            Icon(Icons.Filled.KeyboardArrowDown, "Next")
                        }
                        IconButton(onClick = {
                            findOpen = false
                            findQuery = ""
                            vm.clearFind()
                            try { activeWebView()?.findAllAsync(""); activeWebView()?.clearMatches() } catch (_: Exception) {}
                        }) { Icon(Icons.Filled.Close, "Close find") }
                    }
                }
            }
        }

        // ── Multi-WebView pool: one AndroidView per tab (capped at 4 alive) ──
        // Only current is full-size; background tabs are 1dp (kept alive, no rendering cost).
        Box(modifier = Modifier.fillMaxSize().weight(1f)) {
            ui.tabs.forEach { tab ->
                val isCurrent = tab.id == currentTabId
                key(tab.id) {
                    // WebView creation can throw (missing/updating system WebView).
                    // Never let one bad tab kill app startup — show a fallback view.
                    var webViewFailed by remember { mutableStateOf<String?>(null) }
                    if (webViewFailed != null) {
                        Box(modifier = if (isCurrent) Modifier.fillMaxSize() else Modifier.size(1.dp), contentAlignment = Alignment.Center) {
                            if (isCurrent) Text(
                                "WebView unavailable (${webViewFailed}). Update System WebView / Chrome.",
                                modifier = Modifier.padding(24.dp),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    } else {
                    AndroidView(
                        factory = { c ->
                            try {
                            WebView(c).also { wv ->
                                setupLightWebView(
                                    wv,
                                    BrowserCallbacks(
                                        onStarted = {
                                            try {
                                                activeWebView()?.let { cur ->
                                                    cur.url?.let { u -> vm.saveScroll(u, cur.scrollY) }
                                                }
                                            } catch (_: Exception) {}
                                            // Only current tab drives global UI state.
                                            if (tab.id == vm.ui.value.tabs.getOrNull(vm.ui.value.currentIndex)?.id) vm.onPageStarted(it)
                                        },
                                        onProgress = {
                                            if (tab.id == vm.ui.value.tabs.getOrNull(vm.ui.value.currentIndex)?.id) {
                                                vm.onProgress(it)
                                                try {
                                                    canGoBack = wv.canGoBack()
                                                    canGoForward = wv.canGoForward()
                                                } catch (_: Exception) {}
                                            }
                                        },
                                        onFinished = { url, title ->
                                            if (tab.id == vm.ui.value.tabs.getOrNull(vm.ui.value.currentIndex)?.id) {
                                                vm.onPageFinished(url, title)
                                                try { canGoBack = wv.canGoBack(); canGoForward = wv.canGoForward() } catch (_: Exception) {}
                                                // Scroll restore only if still on same URL (no jump after nav-away).
                                                try {
                                                    val y = vm.popScroll(url)
                                                    if (y != null && y > 0) {
                                                        wv.postDelayed({
                                                            try { if (wv.url == url) wv.scrollTo(0, y) } catch (_: Exception) {}
                                                        }, 400)
                                                    }
                                                } catch (_: Exception) {}
                                            }
                                        },
                                        onLongPressUrl = { longPressUrl = it },
                                        inject = { w, url, runAt -> vm.injectAll(w, url, runAt) }
                                    )
                                )
                                webViews[tab.id] = wv
                                if (tab.id == currentTabId) {
                                    currentWebView = wv
                                    try { com.lightbrowser.data.BrowserAgent.webViewProvider = { activeWebView() } } catch (_: Exception) {}
                                }
                                wv.setFindListener { ordinal, total, _ -> vm.setFind(ordinal + 1, total) }
                                if (!tab.url.startsWith("lb://")) {
                                    try { wv.loadUrl(tab.url, mapOf("X-Requested-With" to "")) } catch (_: Exception) {}
                                }
                                wv
                            }
                            } catch (e: Exception) {
                                webViewFailed = e.message ?: "init failed"
                                android.widget.TextView(c).apply { text = "WebView unavailable" }
                            }
                        },
                        modifier = if (isCurrent) Modifier.fillMaxSize() else Modifier.size(1.dp),
                        update = { v ->
                            val wv = v as? WebView ?: return@AndroidView
                            try { webViews[tab.id] = wv } catch (_: Exception) {}
                            if (isCurrent) {
                                currentWebView = wv
                                // Global switches apply independently per setting (was: skip both if either override set).
                                try {
                                    val host = com.lightbrowser.data.SitePrefs.hostOf(tab.url.ifBlank { ui.currentUrl })
                                    val site = com.lightbrowser.data.SitePrefs.get(ctx, host)
                                    var js = true
                                    var desk = false
                                    try { js = site.js ?: Prefs.jsEnabled } catch (_: Exception) {}
                                    try { desk = site.desktop ?: Prefs.desktopMode } catch (_: Exception) {}
                                    try {
                                        if (wv.settings.javaScriptEnabled != js) wv.settings.javaScriptEnabled = js
                                        if (desk && wv.settings.userAgentString != DESKTOP_UA) wv.settings.userAgentString = DESKTOP_UA
                                        else if (!desk && wv.settings.userAgentString == DESKTOP_UA) {
                                            wv.settings.userAgentString = null
                                        }
                                    } catch (_: Exception) {}
                                } catch (_: Exception) {}
                            }
                        }
                    )
                    } // end else (WebView available)
                } // end key(tab.id)
            } // end forEach tab
            // Keep currentWebView ref in sync when switching tabs.
            LaunchedEffect(currentTabId) {
                try {
                    currentTabId?.let { webViews[it]?.let { w -> currentWebView = w } }
                    val wv = activeWebView()
                    try { canGoBack = wv?.canGoBack() == true; canGoForward = wv?.canGoForward() == true } catch (_: Exception) {}
                    // Pending load retry now that target WebView exists.
                    pendingLoad?.let { (url, _) ->
                        try { if (wv != null && wv.url != url && !url.startsWith("lb://")) { wv.loadUrl(url, mapOf("X-Requested-With" to "")); pendingLoad = null } } catch (_: Exception) {}
                    }
                } catch (_: Exception) {}
            }
                if (ui.currentUrl == HOME_URL) {
                    HomeScreen(
                        modifier = Modifier.fillMaxSize(),
                        vm = vm,
                        onNavigate = { goTo(it) }
                    )
                }
                // Suggestions appear ONLY while typing, floating over the page —
                // never a full black screen.
                if (ui.searchExpanded && ui.searchQuery.isNotBlank()) {
                    val q = ui.searchQuery.lowercase()
                    val sugBookmarks = bookmarks.filter {
                        it.url.lowercase().contains(q) || it.title.lowercase().contains(q)
                    }.take(4)
                    val sugHistory = history.filter {
                        (it.url.lowercase().contains(q) || it.title.lowercase().contains(q)) &&
                            sugBookmarks.none { b -> b.url == it.url }
                    }.take(6)
                    if (sugBookmarks.isNotEmpty() || sugHistory.isNotEmpty()) {
                        Surface(
                            modifier = Modifier.fillMaxWidth().padding(8.dp),
                            shape = MaterialTheme.shapes.extraLarge,
                            tonalElevation = 6.dp,
                            shadowElevation = 6.dp
                        ) {
                            LazyColumn(
                                modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp),
                                contentPadding = PaddingValues(vertical = 8.dp)
                            ) {
                                items(sugBookmarks, key = { "b${it.url}" }) { b ->
                                    ListItem(
                                        headlineContent = { Text(b.title.ifBlank { b.url }, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                        supportingContent = { Text(b.url, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                        leadingContent = { Icon(Icons.Filled.Bookmark, null) },
                                        modifier = Modifier.clickable {
                                            vm.setSearch(false)
                                            focusManager.clearFocus()
                                            goTo(b.url)
                                        }
                                    )
                                }
                                items(sugHistory, key = { "h${it.url}${it.time}" }) { h ->
                                    ListItem(
                                        headlineContent = { Text(h.title.ifBlank { h.url }, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                        supportingContent = { Text(h.url, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                        leadingContent = { Icon(Icons.Filled.History, null) },
                                        modifier = Modifier.clickable {
                                            vm.setSearch(false)
                                            focusManager.clearFocus()
                                            goTo(h.url)
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
        }
    }

    // ── Overflow menu sheet ──
    var showClearCacheConfirm by remember { mutableStateOf(false) }
    if (showMenu) {
        ModalBottomSheet(
            onDismissRequest = { showMenu = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ) {
            MenuGrid(
                bookmarked = ui.bookmarked,
                desktopOn = try { Prefs.desktopMode } catch (_: Exception) { false } ,
                _prefsVer = prefsVer,
                onAction = { action ->
                    showMenu = false
                    when (action) {
                        MenuAction.Refresh -> try { webView?.reload() } catch (_: Exception) {}
                        MenuAction.NewTab -> vm.openTab(HOME_URL)
                        MenuAction.Bookmark -> vm.toggleBookmark()
                        MenuAction.Find -> { findQuery = ""; vm.clearFind(); findOpen = true }
                        MenuAction.Reader -> { vm.loadReader(); showReader = true }
                        MenuAction.Site -> { showSite = true }
                        MenuAction.Record -> {
                            if (com.lightbrowser.data.BrowserAgent.isRecording()) {
                                com.lightbrowser.data.BrowserAgent.stopRecording()
                                // Prompt to save — stop discards data otherwise.
                                scope.launch { try { showAgent = true } catch (_: Exception) {} }
                            } else com.lightbrowser.data.BrowserAgent.startRecording()
                        }
                        MenuAction.Share -> shareUrl(ctx, ui.currentUrl)
                        MenuAction.OpenExternal -> openExternal(ctx, ui.currentUrl)
                        MenuAction.Desktop -> {
                            try { Prefs.desktopMode = !Prefs.desktopMode } catch (_: Exception) {}
                            prefsVer++
                            try { webView?.reload() } catch (_: Exception) {}
                        }
                        MenuAction.History -> { sheetSearch = ""; showHistory = true }
                        MenuAction.Bookmarks -> { sheetSearch = ""; showBookmarks = true }
                        MenuAction.Scripts -> onOpenScripts()
                        MenuAction.Downloads -> onOpenDownloads()
                        MenuAction.Settings -> onOpenSettings()
                        MenuAction.Agent -> showAgent = true
                        MenuAction.ClearCache -> { showClearCacheConfirm = true }
                    }
                }
            )
        }
    }
    if (showClearCacheConfirm) {
        AlertDialog(
            onDismissRequest = { showClearCacheConfirm = false },
            title = { Text("Clear browsing data?") },
            text = { Text("This clears cache only by default. Cookies (logins) are kept unless you tick them.") },
            confirmButton = {
                TextButton(onClick = {
                    showClearCacheConfirm = false
                    scope.launch {
                        try {
                            activeWebView()?.clearCache(true)
                            android.webkit.WebStorage.getInstance().deleteAllData()
                        } catch (_: Exception) {}
                    }
                }) { Text("Clear cache") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { showClearCacheConfirm = false }) { Text("Cancel") }
                    TextButton(onClick = {
                        showClearCacheConfirm = false
                        scope.launch {
                            try {
                                android.webkit.CookieManager.getInstance().removeAllCookies(null)
                                android.webkit.WebStorage.getInstance().deleteAllData()
                                activeWebView()?.clearCache(true)
                            } catch (_: Exception) {}
                        }
                    }) { Text("Cache + logouts") }
                }
            }
        )
    }

    // ── Tabs bottom sheet (redesigned: cards, no dialog) ──
    if (showTabs) {
        ModalBottomSheet(
            onDismissRequest = { showTabs = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Open tabs", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                TextButton(onClick = {
                    vm.openTab(HOME_URL)
                    showTabs = false
                }) {
                    Icon(Icons.Filled.Add, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("New tab")
                }
            }
            LazyColumn(modifier = Modifier.padding(horizontal = 12.dp)) {
                items(ui.tabs.size) { i ->
                    val t = ui.tabs[i]
                    val selected = i == ui.currentIndex
                    androidx.compose.material3.Card(
                        onClick = {
                            vm.selectTab(i)
                            showTabs = false
                            // selectTab already requestLoads — no double load here.
                        },
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        colors = androidx.compose.material3.CardDefaults.cardColors(
                            containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer
                            else MaterialTheme.colorScheme.surfaceContainerLow
                        )
                    ) {
                        ListItem(
                            headlineContent = { Text(t.title.ifBlank { t.url }, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            supportingContent = { Text(t.url, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            leadingContent = {
                                if (selected) Text("●", color = MaterialTheme.colorScheme.primary)
                            },
                            trailingContent = {
                                IconButton(onClick = {
                                    vm.closeTab(i)
                                    showTabs = false
                                }, enabled = ui.tabs.size > 1) {
                                    Icon(Icons.Filled.Close, "Close tab")
                                }
                            }
                        )
                    }
                }
                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }

    // Failsafe: pages that never hit 100 (long-poll/YT) stuck loading=true forever.
    // After 25s on same URL, force progress to 100 (Stop button unsticks).
    LaunchedEffect(ui.loading, ui.currentUrl) {
        if (!ui.loading) return@LaunchedEffect
        try {
            kotlinx.coroutines.delay(25_000)
            try { vm.onProgress(100) } catch (_: Exception) {}
        } catch (_: Exception) {}
    }

    // ── History bottom sheet with search ──
    var confirmClearHistory by remember { mutableStateOf(false) }
    var confirmClearBookmarks by remember { mutableStateOf(false) }
    if (showHistory) {
        ModalBottomSheet(
            onDismissRequest = { showHistory = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ) {
            SheetHeader(title = "History", onClear = { confirmClearHistory = true }, clearLabel = "Clear all")
            OutlinedTextField(
                value = sheetSearch,
                onValueChange = { sheetSearch = it },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                placeholder = { Text("Search history") },
                leadingIcon = { Icon(Icons.Filled.Search, null) },
                singleLine = true,
                shape = MaterialTheme.shapes.extraLarge
            )
            val q = sheetSearch.lowercase()
            val list = history.filter { q.isBlank() || it.url.lowercase().contains(q) || it.title.lowercase().contains(q) }
            if (list.isEmpty()) {
                Text(
                    if (q.isNotBlank()) "No matches for \"$sheetSearch\"" else "No history yet — pages you visit will appear here",
                    modifier = Modifier.padding(20.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            else LazyColumn(modifier = Modifier.padding(horizontal = 8.dp)) {
                items(list, key = { it.url + it.time }) { h ->
                    ListItem(
                        headlineContent = { Text(h.title.ifBlank { h.url }, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        supportingContent = { Text(h.url, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        leadingContent = { Icon(Icons.Filled.History, null) },
                        modifier = Modifier.clickable {
                            showHistory = false
                            goTo(h.url)
                        }
                    )
                }
                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }

    // ── Bookmarks bottom sheet with search ──
    if (showBookmarks) {
        ModalBottomSheet(
            onDismissRequest = { showBookmarks = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ) {
            SheetHeader(title = "Bookmarks", onClear = { confirmClearBookmarks = true }, clearLabel = "Clear")
            OutlinedTextField(
                value = sheetSearch,
                onValueChange = { sheetSearch = it },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                placeholder = { Text("Search bookmarks") },
                leadingIcon = { Icon(Icons.Filled.Search, null) },
                singleLine = true,
                shape = MaterialTheme.shapes.extraLarge
            )
            val q = sheetSearch.lowercase()
            val list = bookmarks.filter { q.isBlank() || it.url.lowercase().contains(q) || it.title.lowercase().contains(q) }
            if (list.isEmpty()) Text("No bookmarks — use ☆ in the menu to add one", modifier = Modifier.padding(20.dp))
            else LazyColumn(modifier = Modifier.padding(horizontal = 8.dp)) {
                items(list, key = { it.url }) { b ->
                    ListItem(
                        headlineContent = { Text(b.title.ifBlank { b.url }, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        supportingContent = { Text(b.url, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        leadingContent = { Icon(Icons.Filled.Bookmark, null) },
                        modifier = Modifier.clickable {
                            showBookmarks = false
                            goTo(b.url)
                        }
                    )
                }
                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }

    // ── Reader sheet ──
    if (showReader) {
        ModalBottomSheet(
            onDismissRequest = { showReader = false; vm.clearReader() },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ) {
            val (title, text) = reader ?: ("" to "Extracting…")
            Text(title.ifBlank { "Reader" }, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
            val failed = text == "(reader failed)" || text == "(no article text found)"
            if (text.isBlank() || text == "Extracting…") {
                Box(modifier = Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                    androidx.compose.material3.CircularProgressIndicator()
                }
            } else {
                LazyColumn(modifier = Modifier.padding(horizontal = 20.dp)) {
                    item {
                        androidx.compose.foundation.text.selection.SelectionContainer {
                            Text(text, style = MaterialTheme.typography.bodyLarge, lineHeight = MaterialTheme.typography.bodyLarge.lineHeight * 1.4)
                        }
                        Spacer(Modifier.height(12.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = { vm.loadReader() }) { Text("Retry") }
                            TextButton(onClick = { copyText(ctx, "$title\n\n$text") }) { Text("Copy") }
                            TextButton(onClick = { shareUrl(ctx, "$title\n\n$text") }) { Text("Share") }
                        }
                        if (failed) Text("Tip: some pages block reader extraction.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(32.dp))
                    }
                }
            }
        }
    }

    // ── Per-site settings dialog ──
    if (showSite) {
        val host = com.lightbrowser.data.SitePrefs.hostOf(ui.currentUrl)
        val cur = com.lightbrowser.data.SitePrefs.get(ctx, host)
        @Composable
        fun tri(label: String, value: Boolean?, global: Boolean, onPick: (Boolean?) -> Unit) {
            Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(label)
                    Text(
                        "Auto = ${if (global) "On" else "Off"}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                listOf(null to "Auto", true to "On", false to "Off").forEach { (v, name) ->
                    TextButton(onClick = { onPick(v) }) {
                        Text(
                            name,
                            color = if (value == v) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
        AlertDialog(
            onDismissRequest = { showSite = false },
            title = { Text(host.ifBlank { "This site" }, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            text = {
                Column {
                    if (host.isBlank() || ui.currentUrl.startsWith("lb://")) {
                        Text("Open a website to set per-site options.", style = MaterialTheme.typography.bodySmall)
                    } else {
                        Text("Auto follows the global switch.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        tri("JavaScript", cur.js, try { Prefs.jsEnabled } catch (_: Exception) { true }) { v ->
                            if (host.isNotBlank()) com.lightbrowser.data.SitePrefs.set(ctx, host, v, cur.desktop, cur.adblock)
                            // Only reload if effective value actually changed.
                            showSite = false; try { webView?.reload() } catch (_: Exception) {}
                        }
                        tri("Desktop site", cur.desktop, try { Prefs.desktopMode } catch (_: Exception) { false }) { v ->
                            if (host.isNotBlank()) com.lightbrowser.data.SitePrefs.set(ctx, host, cur.js, v, cur.adblock)
                            showSite = false; try { webView?.reload() } catch (_: Exception) {}
                        }
                        tri("Ad blocker", cur.adblock, try { Prefs.adBlock } catch (_: Exception) { true }) { v ->
                            if (host.isNotBlank()) com.lightbrowser.data.SitePrefs.set(ctx, host, cur.js, cur.desktop, v)
                            showSite = false; try { webView?.reload() } catch (_: Exception) {}
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showSite = false }) { Text("Done") } }
        )
    }

    // ── Agent bridge sheet (separate menu: server + terminal commands) ──
    if (showAgent) {
        ModalBottomSheet(
            onDismissRequest = { showAgent = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ) {
            AgentSheet(onClose = { showAgent = false })
        }
    }

    if (confirmClearHistory) {
        AlertDialog(
            onDismissRequest = { confirmClearHistory = false },
            title = { Text("Clear all history?") },
            text = { Text("This cannot be undone.") },
            confirmButton = { TextButton(onClick = { confirmClearHistory = false; vm.clearHistory() }) { Text("Clear") } },
            dismissButton = { TextButton(onClick = { confirmClearHistory = false }) { Text("Cancel") } }
        )
    }
    if (confirmClearBookmarks) {
        AlertDialog(
            onDismissRequest = { confirmClearBookmarks = false },
            title = { Text("Clear all bookmarks?") },
            text = { Text("This cannot be undone.") },
            confirmButton = { TextButton(onClick = { confirmClearBookmarks = false; vm.clearBookmarks() }) { Text("Clear") } },
            dismissButton = { TextButton(onClick = { confirmClearBookmarks = false }) { Text("Cancel") } }
        )
    }

    longPressUrl?.let { url ->
        AlertDialog(
            onDismissRequest = { longPressUrl = null },
            title = { Text("Link", maxLines = 1, overflow = TextOverflow.Ellipsis) },
            text = {
                androidx.compose.foundation.text.selection.SelectionContainer {
                    Text(url, maxLines = 4, overflow = TextOverflow.Ellipsis)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    longPressUrl = null
                    if (url.startsWith("blob:") || url.startsWith("data:")) {
                        try { activeWebView()?.loadUrl(url) } catch (_: Exception) {}
                    } else goTo(url)
                }) { Text("Open") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        longPressUrl = null
                        // blob:/data: must go via BlobBridge (DownloadManager can't fetch them).
                        if (url.startsWith("blob:") || url.startsWith("data:")) {
                            try { activeWebView()?.loadUrl(url) } catch (_: Exception) {}
                        } else try { DownloadHelper.enqueue(ctx, url, null, null, null) } catch (_: Exception) {}
                    }) { Text("Download") }
                    TextButton(onClick = {
                        longPressUrl = null
                        copyText(ctx, url)
                    }) { Text("Copy") }
                    TextButton(onClick = {
                        longPressUrl = null
                        vm.openTab(url, select = true)
                    }) { Text("New tab") }
                }
            }
        )
    }
}

@Composable
private fun AgentSheet(onClose: () -> Unit) {
    val ctx = LocalContext.current
    val running by com.lightbrowser.data.BrowserAgent.serverRunning.collectAsState()
    val label by com.lightbrowser.data.BrowserAgent.serverLabel.collectAsState()
    val recordingNow by com.lightbrowser.data.BrowserAgent.recording.collectAsState()
    val scope = rememberCoroutineScope()
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.SmartToy, null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(8.dp))
            Text("Agent bridge", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            TextButton(onClick = onClose) { Text("Done") }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            "Drive this browser from the Terminal tab (b open, b snap…) or from your main Termux over localhost.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(12.dp))
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (running) "● Server running" else "○ Server stopped",
                color = if (running) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = {
                scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                    if (running) com.lightbrowser.data.BrowserAgent.stopServer()
                    else com.lightbrowser.data.BrowserAgent.startServer()
                }
            }) { Text(if (running) "Stop" else "Expose server") }
        }
        if (running) {
            Spacer(Modifier.height(4.dp))
            Surface(
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(label, style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = {
                            // Copy full authed URL (bare IP without token is useless).
                            copyText(ctx, "http://127.0.0.1:${com.lightbrowser.data.BrowserAgent.PORT}/text?token=${com.lightbrowser.data.BrowserAgent.token}")
                        }) { Text("Copy URL") }
                        TextButton(onClick = {
                            copyText(ctx, com.lightbrowser.data.BrowserAgent.token)
                        }) { Text("Copy token") }
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "From Termux: curl 'http://127.0.0.1:8089/text?token=TOKEN'",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (recordingNow) "● Recording taps (${com.lightbrowser.data.BrowserAgent.recCount()} actions)" else "○ Click recorder",
                color = if (recordingNow) androidx.compose.ui.graphics.Color.Red else MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = {
                if (recordingNow) com.lightbrowser.data.BrowserAgent.stopRecording()
                else com.lightbrowser.data.BrowserAgent.startRecording()
            }) { Text(if (recordingNow) "Stop" else "Start") }
        }
        var recVersion by remember { mutableStateOf(0) }
        var recs by remember { mutableStateOf<List<Pair<String, Int>>>(emptyList()) }
        // Loaded async — was runBlocking on Main during composition (startup ANR risk).
        LaunchedEffect(recordingNow, recVersion) {
            try {
                recs = withContext(kotlinx.coroutines.Dispatchers.IO) { com.lightbrowser.data.BrowserAgent.listRecordings().take(5) }
            } catch (_: Exception) { recs = emptyList() }
        }
        if (recs.isNotEmpty()) {
            recs.forEach { (f, n) ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("• $f ($n)", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                    TextButton(onClick = {
                        scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                            try { com.lightbrowser.data.BrowserAgent.saveRecording(f.substringBefore("_")) } catch (_: Exception) {}
                            recVersion++
                        }
                    }) { Text("Save") }
                }
            }
            Spacer(Modifier.height(4.dp))
        }
        if (!recordingNow && com.lightbrowser.data.BrowserAgent.recCount() > 0) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Unsaved: ${com.lightbrowser.data.BrowserAgent.recCount()} actions", style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = {
                    scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                        try { com.lightbrowser.data.BrowserAgent.saveRecording("rec") } catch (_: Exception) {}
                        recVersion++
                    }
                }) { Text("Save now") }
            }
            Spacer(Modifier.height(4.dp))
        }
        Text("Terminal commands", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(4.dp))
        listOf(
            "b open <url> — navigate (/open?url=)",
            "b snap — page refs + text (/snap)",
            "b click <ref> — tap it (/click?sel=)",
            "b fill <ref> <val> — type it (/fill?sel=&value=)",
            "b js <expr> — run JS (/js?expr=)",
            "b shot — save screenshot (/shot)",
            "b console — JS logs (/console)",
            "b record start|stop|save — capture taps"
        ).forEach { cmd ->
            Text("• $cmd", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun SheetHeader(title: String, onClear: () -> Unit, clearLabel: String) {    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
        TextButton(onClick = onClear) { Text(clearLabel) }
    }
}

private enum class MenuAction { Refresh, NewTab, Bookmark, Find, Reader, Site, Record, Share, OpenExternal, Desktop, History, Bookmarks, Scripts, Downloads, Settings, Agent, ClearCache }

@Composable
private fun MenuGrid(
    bookmarked: Boolean,
    desktopOn: Boolean,
    _prefsVer: Int = 0,
    onAction: (MenuAction) -> Unit
) {
    data class Item(val icon: ImageVector, val label: String, val action: MenuAction)
    val items = listOf(
        Item(Icons.Filled.Refresh, "Refresh", MenuAction.Refresh),
        Item(Icons.Filled.Add, "New tab", MenuAction.NewTab),
        Item(if (bookmarked) Icons.Filled.Bookmark else Icons.Filled.BookmarkBorder, "Bookmark", MenuAction.Bookmark),
        Item(Icons.Filled.FindReplace, "Find", MenuAction.Find),
        Item(Icons.Filled.Article, "Reader", MenuAction.Reader),
        Item(Icons.Filled.Tune, "Site", MenuAction.Site),
        Item(Icons.Filled.FiberManualRecord, "Record", MenuAction.Record),
        Item(Icons.Filled.Share, "Share", MenuAction.Share),
        Item(Icons.Filled.OpenInNew, "External", MenuAction.OpenExternal),
        Item(Icons.Filled.DesktopWindows, if (desktopOn) "Desktop ON" else "Desktop", MenuAction.Desktop),
        Item(Icons.Filled.History, "History", MenuAction.History),
        Item(Icons.Filled.Bookmark, "Saved", MenuAction.Bookmarks),
        Item(Icons.Filled.Code, "Scripts", MenuAction.Scripts),
        Item(Icons.Filled.Download, "Downloads", MenuAction.Downloads),
        Item(Icons.Filled.Settings, "Settings", MenuAction.Settings),
        Item(Icons.Filled.SmartToy, "Agent", MenuAction.Agent),
        Item(Icons.Filled.Block, "Clear cache", MenuAction.ClearCache)
    )
    LazyVerticalGrid(
        columns = GridCells.Fixed(4),
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        items(items) { item ->
            Column(
                modifier = Modifier.clickable { onAction(item.action) }.padding(8.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                FilledTonalIconButton(onClick = { onAction(item.action) }) {
                    Icon(item.icon, item.label)
                }
                Spacer(Modifier.height(4.dp))
                Text(item.label, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

private fun shareUrl(ctx: Context, url: String) {
    if (url.isBlank()) return
    try {
        ctx.startActivity(
            Intent.createChooser(
                Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, url)
                },
                "Share link"
            )
        )
    } catch (_: Exception) {}
}

private fun openExternal(ctx: Context, url: String) {
    if (url.isBlank()) return
    try {
        ctx.startActivity(
            Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        )
    } catch (_: Exception) {}
}

private fun copyText(ctx: Context, text: String) {
    try {
        (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
            .setPrimaryClip(ClipData.newPlainText("url", text))
    } catch (_: Exception) {}
}
