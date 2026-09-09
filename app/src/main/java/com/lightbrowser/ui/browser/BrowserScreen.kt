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
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.Article
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DesktopWindows
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.FindReplace
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Language
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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
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
    // Live page thumbnails per tab for the Chrome-style grid (recycled on evict).
    val thumbs = remember { androidx.compose.runtime.mutableStateMapOf<String, android.graphics.Bitmap>() }
    // Current tab id derived from index; stable across recompositions.
    val currentTabId = ui.tabs.getOrNull(ui.currentIndex)?.id
    var currentWebView by remember { mutableStateOf<WebView?>(null) }
    fun activeWebView(): WebView? = currentTabId?.let { webViews[it] } ?: currentWebView
    // Current WebView (recomputed each recomposition from pool).
    val webView: WebView? = activeWebView()
    var canGoBack by remember { mutableStateOf(false) }
    var canGoForward by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }

    // ── 3-dot menu helpers (used by the toolbar-anchored dropdown above) ──
    var showClearCacheConfirm by remember { mutableStateOf(false) }
    fun dismissMenuAnd(action: () -> Unit) {
        showMenu = false
        try { action() } catch (_: Exception) {}
    }
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
    var showScriptLog by remember { mutableStateOf(false) }
    var prefsVer by remember { mutableStateOf(0) }

    // Route TabBus window.open → new tab.
    LaunchedEffect(Unit) {
        TabBus.openInNewTab = { url -> try { vm.openTab(url, select = true) } catch (_: Exception) {} }
        TabBus.selectTab = { i -> try { vm.selectTab(i) } catch (_: Exception) {} }
        TabBus.closeTabAt = { i ->
            try {
                val idx = if (i < 0) vm.ui.value.currentIndex else i
                vm.closeTab(idx)
            } catch (_: Exception) {}
        }
        TabBus.listTabs = {
            try {
                vm.ui.value.tabs.mapIndexed { i, t ->
                    TabInfo(i, t.url, t.title.ifBlank { t.url }, i == vm.ui.value.currentIndex)
                }
            } catch (_: Exception) { emptyList() }
        }
        TabBus.openHome = { try { vm.goHome() } catch (_: Exception) {} }
    }

    val loadReq by vm.loadRequest.collectAsState()
    val findCount by vm.findCount.collectAsState()
    val reader by vm.reader.collectAsState()
    val recording by com.lightbrowser.data.BrowserAgent.recording.collectAsState()
    // Retry load if WebView not yet created (factory race): keep pending until applied.
    // Tagged with the target tab id so a quick tab switch can't load it into the wrong tab.
    var pendingLoad by remember { mutableStateOf<Triple<String, Long, String?>?>(null) }
    LaunchedEffect(loadReq) {
        val (url, ts) = loadReq ?: return@LaunchedEffect
        if (url.startsWith("lb://")) return@LaunchedEffect
        pendingLoad = Triple(url, ts, currentTabId)
    }
    // Apply pending load to its TARGET tab's WebView only; skip when already there
    // (redirect-final URLs equal WebView URL — reloading every switch was the churn).
    LaunchedEffect(pendingLoad, currentTabId) {
        val (url, _, targetId) = pendingLoad ?: return@LaunchedEffect
        if (targetId != null && targetId != currentTabId) return@LaunchedEffect
        val wv = currentTabId?.let { webViews[it] } ?: return@LaunchedEffect
        try {
            if (!sameUrl(wv.url, url)) wv.loadUrl(url, mapOf("X-Requested-With" to ""))
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
    // Refresh scripts/history when returning (Scripts tab saves bypass the VM cache).
    LaunchedEffect(active) {
        try {
            if (active) {
                activeWebView()?.onResume()
                try { com.lightbrowser.data.BrowserProfile.onWebViewResume(activeWebView()) } catch (_: Exception) {}
                try { vm.refreshLists() } catch (_: Exception) {}
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
    // Evict closed tabs' WebViews + cap pool at 6 alive (smoothness-first RAM budget).
    // Destroy oldest background tabs first; current tab is never evicted.
    // Thumbnails die with their tabs (recycled, no bitmap leak).
    LaunchedEffect(ui.tabs.map { it.id }, currentTabId) {
        try {
            val alive = ui.tabs.map { it.id }.toSet()
            (webViews.keys - alive).forEach { id ->
                try { webViews.remove(id)?.destroy() } catch (_: Exception) { try { webViews.remove(id) } catch (_: Exception) {} }
                try { thumbs.remove(id)?.recycle() } catch (_: Exception) {}
            }
            if (webViews.size > 6 && currentTabId != null) {
                val order = ui.tabs.map { it.id }.filter { it != currentTabId }
                val victims = order.take(webViews.size - 6)
                victims.forEach { id ->
                    try { webViews.remove(id)?.destroy() } catch (_: Exception) {}
                    try { thumbs.remove(id)?.recycle() } catch (_: Exception) {}
                }
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
        // Normalized compare: finished URLs (trailing slash, www) equal requests.
        try {
            val cur = webView?.url
            if (url.isNotBlank() && cur != null && sameUrl(cur, url)) {
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
                    // Chrome-style home: jumps straight to homepage, same tab.
                    IconButton(onClick = { try { vm.goHome() } catch (_: Exception) {} }) {
                        Icon(Icons.Filled.Home, "Home")
                    }
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
                    Box {
                        IconButton(onClick = { showMenu = true }) {
                        Icon(
                        Icons.Filled.MoreVert, "Menu",
                        tint = if (recording) androidx.compose.ui.graphics.Color.Red else MaterialTheme.colorScheme.onSurface
                        )
                        }
                        if (showMenu) {
                        // Desktop checkbox must recompose on toggle: prefsVer is the trigger.
                        val desktopOn = remember(prefsVer, showMenu) {
                        try { Prefs.desktopMode } catch (_: Exception) { false }
                        }
                        // Anchored to the ⋮ button: drops down right-aligned, Chrome-style.
                        DropdownMenu(
                        expanded = true,
                        onDismissRequest = { showMenu = false },
                        modifier = Modifier.width(300.dp).background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(16.dp))
                        ) {
                        // Top action row: forward | bookmark | download page | site info | refresh
                        Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                        ) {
                        IconButton(onClick = { try { webView?.goForward() } catch (_: Exception) {} }, enabled = canGoForward) {
                        Icon(Icons.Filled.ArrowForward, "Forward")
                        }
                        IconButton(onClick = { vm.toggleBookmark() }) {
                        Icon(if (ui.bookmarked) Icons.Filled.Bookmark else Icons.Filled.BookmarkBorder, "Bookmark")
                        }
                        IconButton(onClick = dismissMenu@{
                        val u = ui.currentUrl
                        if (u.isBlank() || u.startsWith("lb://")) return@dismissMenu
                        try { DownloadHelper.enqueue(ctx, u, null, null, null) } catch (_: Exception) {}
                        }) { Icon(Icons.Filled.Download, "Download page") }
                        IconButton(onClick = { showMenu = false; showSite = true }) { Icon(Icons.Filled.Info, "Site info") }
                        IconButton(onClick = { try { webView?.reload() } catch (_: Exception) {} }) { Icon(Icons.Filled.Refresh, "Refresh") }
                        }
                        HorizontalDivider(modifier = Modifier.padding(horizontal = 12.dp))
                        ChromeRow(Icons.Filled.Add, "New tab") { dismissMenuAnd { vm.openTab(HOME_URL) } }
                        ChromeRow(Icons.Filled.History, "History") { dismissMenuAnd { sheetSearch = ""; showHistory = true } }
                        ChromeRow(Icons.Filled.Delete, "Delete browsing data") { showMenu = false; showClearCacheConfirm = true }
                        ChromeRow(Icons.Filled.Download, "Downloads") { dismissMenuAnd { onOpenDownloads() } }
                        ChromeRow(Icons.Filled.Bookmark, "Bookmarks") { dismissMenuAnd { sheetSearch = ""; showBookmarks = true } }
                        ChromeRow(Icons.Filled.OpenInNew, "Recent tabs") { dismissMenuAnd { showTabs = true } }
                        ChromeRow(Icons.Filled.Share, "Share…") { dismissMenuAnd { shareUrl(ctx, ui.currentUrl) } }
                        ChromeRow(Icons.Filled.FindReplace, "Find in page") { dismissMenuAnd { findQuery = ""; vm.clearFind(); findOpen = true } }
                        // Desktop site with trailing checkbox — toggles in place, menu stays open.
                        DropdownMenuItem(
                        text = { Text("Desktop site") },
                        leadingIcon = { Icon(Icons.Filled.DesktopWindows, null) },
                        trailingIcon = {
                        Checkbox(
                        checked = desktopOn,
                        onCheckedChange = {
                        try { Prefs.desktopMode = it } catch (_: Exception) {}
                        prefsVer++
                        try { webView?.reload() } catch (_: Exception) {}
                        }
                        )
                        },
                        onClick = {
                        try { Prefs.desktopMode = !Prefs.desktopMode } catch (_: Exception) {}
                        prefsVer++
                        try { webView?.reload() } catch (_: Exception) {}
                        }
                        )
                        HorizontalDivider(modifier = Modifier.padding(horizontal = 12.dp))
                        ChromeRow(Icons.Filled.Article, "Reader") { dismissMenuAnd { vm.loadReader(); showReader = true } }
                        ChromeRow(Icons.Filled.Tune, "Site settings") { dismissMenuAnd { showSite = true } }
                        ChromeRow(Icons.Filled.BugReport, "Script log") { dismissMenuAnd { showScriptLog = true } }
                        ChromeRow(
                        Icons.Filled.FiberManualRecord,
                        if (recording) "Stop recording" else "Record taps"
                        ) {
                        dismissMenuAnd {
                        if (com.lightbrowser.data.BrowserAgent.isRecording()) {
                        com.lightbrowser.data.BrowserAgent.stopRecording()
                        scope.launch { try { showAgent = true } catch (_: Exception) {} }
                        } else com.lightbrowser.data.BrowserAgent.startRecording()
                        }
                        }
                        ChromeRow(Icons.Filled.Code, "Scripts") { dismissMenuAnd { onOpenScripts() } }
                        ChromeRow(Icons.Filled.SmartToy, "Agent bridge") { dismissMenuAnd { showAgent = true } }
                        ChromeRow(Icons.Filled.Settings, "Settings") { dismissMenuAnd { onOpenSettings() } }
                        }
                        }
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
                        Box(modifier = if (isCurrent) Modifier.fillMaxSize() else Modifier.size(0.dp), contentAlignment = Alignment.Center) {
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
                                wv.visibility = if (tab.id == currentTabId) android.view.View.VISIBLE else android.view.View.GONE
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
                                        inject = { w, url, runAt -> vm.injectAll(w, url, runAt) },
                                        onVisited = { url -> vm.onVisited(url) }
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
                        modifier = if (isCurrent) Modifier.fillMaxSize() else Modifier.size(0.dp),
                        update = { v ->
                            val wv = v as? WebView ?: return@AndroidView
                            // GONE backgrounds never draw (was 1dp slivers stacked top-left).
                            try { wv.visibility = if (isCurrent) android.view.View.VISIBLE else android.view.View.GONE } catch (_: Exception) {}
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
            // Captures the outgoing tab's thumbnail first (Chrome-style grid previews).
            var prevThumbTab by remember { mutableStateOf<String?>(null) }
            LaunchedEffect(currentTabId) {
                try {
                    prevThumbTab?.let { pid ->
                        if (pid != currentTabId) {
                            try {
                                webViews[pid]?.let { wv ->
                                    captureThumb(wv)?.let { bmp ->
                                        try {
                                            thumbs[pid]?.recycle()
                                        } catch (_: Exception) {}
                                        thumbs[pid] = bmp
                                    }
                                }
                            } catch (_: Exception) {}
                        }
                    }
                } catch (_: Exception) {}
                prevThumbTab = currentTabId
                try {
                    currentTabId?.let { webViews[it]?.let { w -> currentWebView = w } }
                    val wv = activeWebView()
                    try { canGoBack = wv?.canGoBack() == true; canGoForward = wv?.canGoForward() == true } catch (_: Exception) {}
                    // Pending load retry now that target WebView exists.
                    pendingLoad?.let { (url, _, targetId) ->
                        if (targetId == null || targetId == currentTabId) {
                            try { if (wv != null && !sameUrl(wv.url, url) && !url.startsWith("lb://")) { wv.loadUrl(url, mapOf("X-Requested-With" to "")); pendingLoad = null } } catch (_: Exception) {}
                        }
                    }
                } catch (_: Exception) {}
                // Watchdog: if the current tab ended up blank (skipped/failed load),
                // load its URL once. Narrow on purpose: only blank views, never reloads.
                try {
                    kotlinx.coroutines.delay(600)
                    val tabs = vm.ui.value.tabs
                    val idx = vm.ui.value.currentIndex
                    if (currentTabId != tabs.getOrNull(idx)?.id) return@LaunchedEffect
                    val t = tabs.getOrNull(idx) ?: return@LaunchedEffect
                    if (t.url.startsWith("lb://")) return@LaunchedEffect
                    val wv = try { currentTabId?.let { webViews[it] } } catch (_: Exception) { null }
                        ?: return@LaunchedEffect
                    val cur = try { wv.url } catch (_: Exception) { null }
                    if (cur.isNullOrBlank()) {
                        try { wv.loadUrl(t.url, mapOf("X-Requested-With" to "")) } catch (_: Exception) {}
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

    // ── Tabs sheet, Chrome-style: + / count badge / overflow, search, 2-col preview grid ──
    // Live thumbnails captured from the outgoing tab's WebView on every switch (see above).
    var tabSearch by remember { mutableStateOf("") }
    var showCloseAllTabs by remember { mutableStateOf(false) }
    if (showTabs) {
        // Fresh thumbnail of the current page too (it only gets captured on switch-away).
        LaunchedEffect(showTabs) {
            try {
                currentTabId?.let { id ->
                    webViews[id]?.let { wv ->
                        captureThumb(wv)?.let { bmp ->
                            try { thumbs[id]?.recycle() } catch (_: Exception) {}
                            thumbs[id] = bmp
                        }
                    }
                }
            } catch (_: Exception) {}
        }
        ModalBottomSheet(
            onDismissRequest = { showTabs = false; tabSearch = "" },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ) {
            // Top bar: [+] new tab | [count] | [overflow: close all]
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { vm.openTab(HOME_URL); tabSearch = "" }) {
                    Icon(Icons.Filled.Add, "New tab")
                }
                Spacer(Modifier.weight(1f))
                // Count badge with active underline, like Chrome's tab counter.
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        modifier = Modifier
                            .size(34.dp)
                            .border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(8.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("${ui.tabs.size}", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                    }
                    Spacer(Modifier.height(2.dp))
                    Box(modifier = Modifier.width(34.dp).height(2.dp).background(MaterialTheme.colorScheme.primary, RoundedCornerShape(1.dp)))
                }
                Spacer(Modifier.weight(1f))
                Box {
                    var tabsOverflow by remember { mutableStateOf(false) }
                    IconButton(onClick = { tabsOverflow = true }) { Icon(Icons.Filled.MoreVert, "Tab options") }
                    DropdownMenu(expanded = tabsOverflow, onDismissRequest = { tabsOverflow = false }) {
                        DropdownMenuItem(text = { Text("Close all tabs") }, onClick = {
                            tabsOverflow = false; showCloseAllTabs = true
                        })
                        DropdownMenuItem(text = { Text("New tab") }, onClick = {
                            tabsOverflow = false; vm.openTab(HOME_URL); tabSearch = ""
                        })
                    }
                }
            }
            OutlinedTextField(
                value = tabSearch,
                onValueChange = { tabSearch = it },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                placeholder = { Text("Search your tabs") },
                leadingIcon = { Icon(Icons.Filled.Search, null) },
                trailingIcon = {
                    if (tabSearch.isNotEmpty()) IconButton(onClick = { tabSearch = "" }) { Icon(Icons.Filled.Close, "Clear") }
                },
                singleLine = true,
                shape = MaterialTheme.shapes.extraLarge
            )
            val tq = tabSearch.lowercase()
            val shown = ui.tabs.mapIndexed { i, t -> i to t }.filter { (_, t) ->
                tq.isBlank() || t.url.lowercase().contains(tq) || t.title.lowercase().contains(tq)
            }
            if (shown.isEmpty()) {
                Text(
                    if (tq.isNotBlank()) "No tabs match \"$tabSearch\"" else "No tabs",
                    modifier = Modifier.padding(24.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(shown, key = { (_, t) -> t.id }) { (i, t) ->
                        val selected = i == ui.currentIndex
                        Card(
                            onClick = { vm.selectTab(i); showTabs = false; tabSearch = "" },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(20.dp),
                            border = if (selected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
                        ) {
                            Column {
                                // Header: favicon-letter + title + close X.
                                Row(modifier = Modifier.fillMaxWidth().padding(start = 10.dp, end = 2.dp, top = 6.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                                    val letter = (t.title.ifBlank { t.url }.trim().firstOrNull()?.uppercase() ?: "•")
                                    Box(
                                        modifier = Modifier.size(26.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(letter, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, maxLines = 1)
                                    }
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        t.title.ifBlank { t.url },
                                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                                        style = MaterialTheme.typography.titleSmall,
                                        modifier = Modifier.weight(1f)
                                    )
                                    IconButton(
                                        onClick = { vm.closeTab(i) },
                                        enabled = ui.tabs.size > 1,
                                        modifier = Modifier.size(30.dp)
                                    ) { Icon(Icons.Filled.Close, "Close tab", modifier = Modifier.size(18.dp)) }
                                }
                                // Preview: live thumbnail or placeholder.
                                val bmp = thumbs[t.id]
                                if (bmp != null) {
                                    Image(
                                        bitmap = bmp.asImageBitmap(),
                                        contentDescription = null,
                                        modifier = Modifier.fillMaxWidth().height(170.dp).padding(horizontal = 8.dp).clip(RoundedCornerShape(12.dp)),
                                        contentScale = ContentScale.Crop
                                    )
                                } else {
                                    Box(
                                        modifier = Modifier.fillMaxWidth().height(170.dp).padding(horizontal = 8.dp).clip(RoundedCornerShape(12.dp))
                                            .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(Icons.Filled.Language, null, modifier = Modifier.size(40.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                                Text(
                                    t.url, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                                )
                            }
                        }
                    }
                    item(span = { GridItemSpan(2) }) { Spacer(Modifier.height(20.dp)) }
                }
            }
        }
    }
    if (showCloseAllTabs) {
        AlertDialog(
            onDismissRequest = { showCloseAllTabs = false },
            title = { Text("Close all tabs?") },
            text = { Text("All ${ui.tabs.size} tabs will be closed and one home tab opened.") },
            confirmButton = {
                TextButton(onClick = {
                    showCloseAllTabs = false
                    try {
                        val n = ui.tabs.size
                        for (k in n - 1 downTo 1) { try { vm.closeTab(k) } catch (_: Exception) {} }
                        try { vm.closeTab(0) } catch (_: Exception) {}
                        vm.openTab(HOME_URL)
                    } catch (_: Exception) {}
                }) { Text("Close all", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { showCloseAllTabs = false }) { Text("Cancel") } }
        )
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

    // ── Userscript log sheet: what did my scripts do / what broke ──
    if (showScriptLog) {
        ModalBottomSheet(
            onDismissRequest = { showScriptLog = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ) {
            val logs = remember(showScriptLog) {
                try {
                    com.lightbrowser.data.BrowserAgent.consoleTail(200).filter {
                        it.contains("LB inject", true) || it.contains("LB script", true) ||
                            it.contains("GM_", true) || it.contains("userscript", true) ||
                            it.contains("inject", true)
                    }.takeLast(80)
                } catch (_: Exception) { emptyList() }
            }
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.BugReport, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(8.dp))
                    Text("Script log", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    TextButton(onClick = { showScriptLog = false }) { Text("Done") }
                }
                Text(
                    "Injections + errors from your userscripts on this page.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                if (logs.isEmpty()) {
                    Text(
                        "No script output yet — open a page with matching scripts, then reopen this log.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 16.dp)
                    )
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { copyText(ctx, logs.joinToString("\n")) }) { Text("Copy") }
                        TextButton(onClick = { shareUrl(ctx, logs.joinToString("\n")) }) { Text("Share") }
                    }
                    LazyColumn(modifier = Modifier.heightIn(max = 380.dp)) {
                        items(logs) { line ->
                            androidx.compose.foundation.text.selection.SelectionContainer {
                                Text(
                                    line.take(600),
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                    color = if (line.contains("error", true)) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.padding(vertical = 2.dp)
                                )
                            }
                        }
                        item { Spacer(Modifier.height(24.dp)) }
                    }
                }
                Spacer(Modifier.height(16.dp))
            }
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
            "b tabs — list tabs | b new <url> | b close [n] | b home",
            "b back | b forward | b reload | b stop",
            "b snap — page refs + text (/snap)",
            "b click <ref> — tap it (/click?sel=)",
            "b fill <ref> <val> — type it (/fill?sel=&value=)",
            "b find <text> — find in page (/find?q=)",
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

@Composable
private fun ChromeRow(icon: ImageVector, label: String, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(label) },
        leadingIcon = { Icon(icon, null) },
        onClick = onClick
    )
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

/** Normalized URL equality: ignores trailing slashes + fragment so redirect-final
 *  URLs match their requests (prevents reload-every-tab-switch churn). */
private fun sameUrl(a: String?, b: String?): Boolean {
    if (a == null || b == null) return false
    return try {
        a.trim().substringBefore("#").trimEnd('/').lowercase() ==
            b.trim().substringBefore("#").trimEnd('/').lowercase()
    } catch (_: Exception) { a == b }
}

/** Downscaled snapshot of a WebView for the tab-grid preview. Main thread only. */
private fun captureThumb(wv: WebView): android.graphics.Bitmap? {
    return try {
        if (wv.width <= 0 || wv.height <= 0) return null
        val scale = (360f / wv.width).coerceAtMost(1f)
        val bw = (wv.width * scale).toInt().coerceAtLeast(1)
        val bh = (wv.height * scale).toInt().coerceAtLeast(1)
        val bmp = android.graphics.Bitmap.createBitmap(bw, bh, android.graphics.Bitmap.Config.RGB_565)
        val c = android.graphics.Canvas(bmp)
        c.scale(scale, scale)
        wv.draw(c)
        bmp
    } catch (_: Exception) { null }
}
