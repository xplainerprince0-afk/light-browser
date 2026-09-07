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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.DesktopWindows
import androidx.compose.material.icons.filled.Download
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
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowserScreen(
    modifier: Modifier = Modifier,
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

    var webView by remember { mutableStateOf<WebView?>(null) }
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

    val loadReq by vm.loadRequest.collectAsState()
    LaunchedEffect(loadReq) {
        val (url, _) = loadReq ?: return@LaunchedEffect
        try {
            webView?.loadUrl(url, mapOf("X-Requested-With" to ""))
        } catch (_: Exception) {}
    }

    BackHandler(enabled = ui.searchExpanded) {
        vm.setSearch(false)
        focusManager.clearFocus()
    }
    BackHandler(enabled = !ui.searchExpanded && webView?.canGoBack() == true) {
        try { webView?.goBack() } catch (_: Exception) {}
    }

    DisposableEffect(Unit) {
        onDispose {
            try {
                webView?.stopLoading()
                webView?.destroy()
            } catch (_: Exception) {}
            webView = null
        }
    }

    fun currentDomain(): String = try {
        android.net.Uri.parse(ui.currentUrl).host ?: ui.currentUrl.ifBlank { "Search or enter URL" }
    } catch (_: Exception) {
        ui.currentUrl.ifBlank { "Search or enter URL" }
    }

    fun goTo(url: String) {
        vm.onPageStarted(url)
        try { webView?.loadUrl(url, mapOf("X-Requested-With" to "")) } catch (_: Exception) {}
    }

    // FIXED search: pill or editor lives at the top of a plain Column, the WebView
    // below is ALWAYS composed (never destroyed). No imePadding anywhere here —
    // the keyboard overlays the bottom instead of pushing content up.
    Column(modifier = modifier.fillMaxSize()) {
        if (ui.searchExpanded) {
            val focusReq = remember { FocusRequester() }
            OutlinedTextField(
                value = ui.searchQuery,
                onValueChange = vm::setQuery,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp).focusRequester(focusReq),
                placeholder = { Text("Search or enter URL") },
                leadingIcon = { Icon(Icons.Filled.Search, null) },
                trailingIcon = {
                    IconButton(onClick = {
                        vm.setSearch(false)
                        focusManager.clearFocus()
                    }) { Icon(Icons.Filled.Close, "Collapse") }
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = {
                    val url = vm.resolveInput(ui.searchQuery)
                    if (url.isNotEmpty()) goTo(url)
                    vm.setSearch(false)
                    focusManager.clearFocus()
                }),
                shape = MaterialTheme.shapes.extraLarge
            )
            LaunchedEffect(Unit) {
                try { focusReq.requestFocus() } catch (_: Exception) {}
            }
            val q = ui.searchQuery.lowercase()
            val sugBookmarks = bookmarks.filter {
                q.isBlank() || it.url.lowercase().contains(q) || it.title.lowercase().contains(q)
            }.take(4)
            val sugHistory = history.filter {
                (q.isBlank() || it.url.lowercase().contains(q) || it.title.lowercase().contains(q)) &&
                    sugBookmarks.none { b -> b.url == it.url }
            }.take(6)
            LazyColumn(modifier = Modifier.weight(1f)) {
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
                        Icon(Icons.Filled.MoreVert, "Menu")
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
                                try { webView?.findAllAsync(it) } catch (_: Exception) {}
                            },
                            modifier = Modifier.weight(1f),
                            placeholder = { Text("Find in page") },
                            singleLine = true,
                            shape = MaterialTheme.shapes.large
                        )
                        IconButton(onClick = { try { webView?.findNext(false) } catch (_: Exception) {} }) {
                            Icon(Icons.Filled.KeyboardArrowUp, "Prev")
                        }
                        IconButton(onClick = { try { webView?.findNext(true) } catch (_: Exception) {} }) {
                            Icon(Icons.Filled.KeyboardArrowDown, "Next")
                        }
                        IconButton(onClick = {
                            findOpen = false
                            try { webView?.clearMatches() } catch (_: Exception) {}
                        }) { Icon(Icons.Filled.Close, "Close find") }
                    }
                }
            }

            // WebView fills the rest and is NEVER removed from composition.
            AndroidView(
                factory = { c ->
                    WebView(c).also { wv ->
                        setupLightWebView(
                            wv,
                            BrowserCallbacks(
                                onStarted = { vm.onPageStarted(it) },
                                onProgress = {
                                    vm.onProgress(it)
                                    try {
                                        canGoBack = webView?.canGoBack() == true
                                        canGoForward = webView?.canGoForward() == true
                                    } catch (_: Exception) {}
                                },
                                onFinished = { url, title -> vm.onPageFinished(url, title) },
                                onLongPressUrl = { longPressUrl = it },
                                inject = { w, url, runAt -> vm.injectAll(w, url, runAt) }
                            )
                        )
                        webView = wv
                        val start = ui.tabs.firstOrNull()?.url ?: Prefs.homePage
                        try { wv.loadUrl(start, mapOf("X-Requested-With" to "")) } catch (_: Exception) {}
                    }
                },
                modifier = Modifier.fillMaxSize().weight(1f),
                update = { wv ->
                    if (webView == null) webView = wv
                    var js = true
                    var desk = false
                    try { js = Prefs.jsEnabled } catch (_: Exception) {}
                    try { desk = Prefs.desktopMode } catch (_: Exception) {}
                    try {
                        if (wv.settings.javaScriptEnabled != js) wv.settings.javaScriptEnabled = js
                        if (desk && wv.settings.userAgentString != DESKTOP_UA) wv.settings.userAgentString = DESKTOP_UA
                        else if (!desk && wv.settings.userAgentString == DESKTOP_UA) {
                            wv.settings.userAgentString = System.getProperty("http.agent")
                        }
                    } catch (_: Exception) {}
                }
            )
        }
    }

    // ── Overflow menu sheet ──
    if (showMenu) {
        ModalBottomSheet(
            onDismissRequest = { showMenu = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ) {
            MenuGrid(
                bookmarked = ui.bookmarked,
                desktopOn = try { Prefs.desktopMode } catch (_: Exception) { false },
                onAction = { action ->
                    showMenu = false
                    when (action) {
                        MenuAction.Refresh -> try { webView?.reload() } catch (_: Exception) {}
                        MenuAction.NewTab -> vm.openTab(try { Prefs.homePage } catch (_: Exception) { "https://www.google.com" })
                        MenuAction.Bookmark -> vm.toggleBookmark()
                        MenuAction.Find -> findOpen = true
                        MenuAction.Share -> shareUrl(ctx, ui.currentUrl)
                        MenuAction.OpenExternal -> openExternal(ctx, ui.currentUrl)
                        MenuAction.Desktop -> {
                            try { Prefs.desktopMode = !Prefs.desktopMode } catch (_: Exception) {}
                            try { webView?.reload() } catch (_: Exception) {}
                        }
                        MenuAction.History -> { sheetSearch = ""; showHistory = true }
                        MenuAction.Bookmarks -> { sheetSearch = ""; showBookmarks = true }
                        MenuAction.Scripts -> onOpenScripts()
                        MenuAction.Downloads -> onOpenDownloads()
                        MenuAction.Settings -> onOpenSettings()
                        MenuAction.ClearCache -> scope.launch {
                            try {
                                android.webkit.CookieManager.getInstance().removeAllCookies(null)
                                android.webkit.WebStorage.getInstance().deleteAllData()
                                webView?.clearCache(true)
                            } catch (_: Exception) {}
                        }
                    }
                }
            )
        }
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
                    vm.openTab(try { Prefs.homePage } catch (_: Exception) { "https://www.google.com" })
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
                                IconButton(onClick = { vm.closeTab(i) }, enabled = ui.tabs.size > 1) {
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

    // ── History bottom sheet with search ──
    if (showHistory) {
        ModalBottomSheet(
            onDismissRequest = { showHistory = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ) {
            SheetHeader(title = "History", onClear = { vm.clearHistory() }, clearLabel = "Clear all")
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
            if (list.isEmpty()) Text("Nothing here", modifier = Modifier.padding(20.dp))
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
            SheetHeader(title = "Bookmarks", onClear = { vm.clearBookmarks() }, clearLabel = "Clear")
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

    longPressUrl?.let { url ->
        AlertDialog(
            onDismissRequest = { longPressUrl = null },
            title = { Text("Link", maxLines = 1, overflow = TextOverflow.Ellipsis) },
            text = { Text(url) },
            confirmButton = {
                TextButton(onClick = {
                    longPressUrl = null
                    goTo(url)
                }) { Text("Open") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        longPressUrl = null
                        try { DownloadHelper.enqueue(ctx, url, null, null, null) } catch (_: Exception) {}
                    }) { Text("Download") }
                    TextButton(onClick = {
                        longPressUrl = null
                        copyText(ctx, url)
                    }) { Text("Copy") }
                }
            }
        )
    }
}

@Composable
private fun SheetHeader(title: String, onClear: () -> Unit, clearLabel: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
        TextButton(onClick = onClear) { Text(clearLabel) }
    }
}

private enum class MenuAction { Refresh, NewTab, Bookmark, Find, Share, OpenExternal, Desktop, History, Bookmarks, Scripts, Downloads, Settings, ClearCache }

@Composable
private fun MenuGrid(
    bookmarked: Boolean,
    desktopOn: Boolean,
    onAction: (MenuAction) -> Unit
) {
    data class Item(val icon: ImageVector, val label: String, val action: MenuAction)
    val items = listOf(
        Item(Icons.Filled.Refresh, "Refresh", MenuAction.Refresh),
        Item(Icons.Filled.Add, "New tab", MenuAction.NewTab),
        Item(if (bookmarked) Icons.Filled.Bookmark else Icons.Filled.BookmarkBorder, "Bookmark", MenuAction.Bookmark),
        Item(Icons.Filled.FindReplace, "Find", MenuAction.Find),
        Item(Icons.Filled.Share, "Share", MenuAction.Share),
        Item(Icons.Filled.OpenInNew, "External", MenuAction.OpenExternal),
        Item(Icons.Filled.DesktopWindows, if (desktopOn) "Desktop ON" else "Desktop", MenuAction.Desktop),
        Item(Icons.Filled.History, "History", MenuAction.History),
        Item(Icons.Filled.Bookmark, "Saved", MenuAction.Bookmarks),
        Item(Icons.Filled.Code, "Scripts", MenuAction.Scripts),
        Item(Icons.Filled.Download, "Downloads", MenuAction.Downloads),
        Item(Icons.Filled.Settings, "Settings", MenuAction.Settings),
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
