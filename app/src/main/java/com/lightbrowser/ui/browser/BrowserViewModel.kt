package com.lightbrowser.ui.browser

import android.webkit.WebView
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lightbrowser.data.AppCtx
import com.lightbrowser.data.Bookmark
import com.lightbrowser.data.BookmarkStorage
import com.lightbrowser.data.HistoryEntry
import com.lightbrowser.data.HistoryStorage
import com.lightbrowser.data.Prefs
import com.lightbrowser.data.ScriptStorage
import com.lightbrowser.data.UserScript
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class BrowserTab(
    val id: String = UUID.randomUUID().toString(),
    val url: String,
    val title: String = ""
)

data class BrowserUiState(
    val tabs: List<BrowserTab> = emptyList(),
    val currentIndex: Int = 0,
    val progress: Int = 0,
    val loading: Boolean = false,
    val currentUrl: String = "",
    val currentTitle: String = "",
    val searchExpanded: Boolean = false,
    val searchQuery: String = "",
    val bookmarked: Boolean = false
)

class BrowserViewModel : ViewModel() {

    private val _ui = MutableStateFlow(BrowserUiState())
    val ui: StateFlow<BrowserUiState> = _ui.asStateFlow()

    private val _history = MutableStateFlow<List<HistoryEntry>>(emptyList())
    val history: StateFlow<List<HistoryEntry>> = _history.asStateFlow()

    private val _bookmarks = MutableStateFlow<List<Bookmark>>(emptyList())
    val bookmarks: StateFlow<List<Bookmark>> = _bookmarks.asStateFlow()

    private val _scripts = MutableStateFlow<List<UserScript>>(emptyList())
    val scripts: StateFlow<List<UserScript>> = _scripts.asStateFlow()

    /** One-shot URL loads consumed by the WebView holder. */
    private val _loadRequest = MutableStateFlow<Pair<String, Long>?>(null)
    val loadRequest: StateFlow<Pair<String, Long>?> = _loadRequest.asStateFlow()

    val ctx get() = AppCtx.ctx

    init {
        val home = Prefs.homePage
        _ui.update { it.copy(tabs = listOf(BrowserTab(url = home)), currentUrl = home) }
        refreshLists()
    }

    fun refreshLists() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                _history.value = HistoryStorage.all(ctx)
                _bookmarks.value = BookmarkStorage.all(ctx)
                _scripts.value = ScriptStorage.all(ctx)
            } catch (_: Exception) {}
        }
    }

    fun requestLoad(url: String) {
        _loadRequest.value = url to System.currentTimeMillis()
    }

    fun onPageStarted(url: String) {
        _ui.update {
            val tabs = it.tabs.toMutableList()
            if (tabs.isEmpty()) tabs.add(BrowserTab(url = url))
            else tabs[it.currentIndex.coerceIn(tabs.indices)] =
                tabs[it.currentIndex.coerceIn(tabs.indices)].copy(url = url)
            it.copy(tabs = tabs, currentUrl = url, loading = true, progress = 10)
        }
    }

    fun onProgress(p: Int) {
        _ui.update { it.copy(progress = p, loading = p < 100) }
    }

    fun onPageFinished(url: String, title: String) {
        _ui.update {
            val tabs = it.tabs.toMutableList()
            if (tabs.isEmpty()) tabs.add(BrowserTab(url = url, title = title))
            else {
                val i = it.currentIndex.coerceIn(tabs.indices)
                tabs[i] = tabs[i].copy(url = url, title = title)
            }
            it.copy(tabs = tabs, currentUrl = url, currentTitle = title, loading = false, progress = 100)
        }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                HistoryStorage.add(ctx, url, title)
                _history.value = HistoryStorage.all(ctx)
                _ui.update { it.copy(bookmarked = BookmarkStorage.isBookmarked(ctx, url)) }
            } catch (_: Exception) {}
        }
    }

    fun resolveInput(input: String): String {
        val t = input.trim()
        if (t.isEmpty()) return ""
        return when {
            t.startsWith("http://") || t.startsWith("https://") -> t
            t.contains(".") && !t.contains(" ") -> "https://$t"
            else -> Prefs.buildSearchUrl(t)
        }
    }

    fun openTab(url: String, select: Boolean = true) {
        _ui.update {
            val tabs = it.tabs + BrowserTab(url = url)
            val idx = if (select) tabs.size - 1 else it.currentIndex
            it.copy(tabs = tabs, currentIndex = idx)
        }
        if (select) requestLoad(url)
    }

    fun selectTab(i: Int) {
        val tabs = _ui.value.tabs
        if (i !in tabs.indices || i == _ui.value.currentIndex) return
        _ui.update { it.copy(currentIndex = i) }
        requestLoad(tabs[i].url)
    }

    fun closeTab(i: Int) {
        val tabs = _ui.value.tabs.toMutableList()
        if (tabs.size <= 1 || i !in tabs.indices) return
        tabs.removeAt(i)
        val ni = _ui.value.currentIndex.coerceAtMost(tabs.size - 1)
        _ui.update { it.copy(tabs = tabs, currentIndex = ni) }
        requestLoad(tabs[ni].url)
    }

    fun toggleBookmark(): Boolean {
        val url = _ui.value.currentUrl
        if (url.isBlank()) return false
        return try {
            val marked = BookmarkStorage.toggle(ctx, url, _ui.value.currentTitle.ifBlank { url })
            _ui.update { it.copy(bookmarked = marked) }
            viewModelScope.launch(Dispatchers.IO) {
                try { _bookmarks.value = BookmarkStorage.all(ctx) } catch (_: Exception) {}
            }
            marked
        } catch (_: Exception) { false }
    }

    fun clearHistory() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                HistoryStorage.clear(ctx)
                _history.value = emptyList()
            } catch (_: Exception) {}
        }
    }

    fun clearBookmarks() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                BookmarkStorage.clear(ctx)
                _bookmarks.value = emptyList()
                _ui.update { it.copy(bookmarked = false) }
            } catch (_: Exception) {}
        }
    }

    fun setSearch(expanded: Boolean, query: String = _ui.value.currentUrl) {
        _ui.update { it.copy(searchExpanded = expanded, searchQuery = query) }
    }

    fun setQuery(q: String) {
        _ui.update { it.copy(searchQuery = q) }
    }

    fun scriptsFor(url: String): List<UserScript> =
        _scripts.value.filter { it.enabled && UserScript.matchesUrl(it.matches, url) }

    fun injectAll(webView: WebView?, url: String, runAt: String) {
        if (webView == null) return
        val matched = scriptsFor(url)
        if (matched.isEmpty()) return
        val toInject = matched.filter { sc ->
            when (sc.runAt) {
                "document_start" -> runAt == "document_start"
                "document_end" -> runAt == "document_end" || runAt == "document_idle"
                else -> runAt == "document_idle" || runAt == "document_end"
            }
        }
        toInject.forEach { sc -> injectSingle(webView, sc) }
    }

    private fun injectSingle(webView: WebView, sc: UserScript) {
        try {
            val needsGM = sc.grants.any { it.startsWith("GM_") } && !sc.grants.contains("none")
            val gm = if (needsGM) """
                window.GM_info={script:{name:'${esc(sc.name)}'}};
                window.GM_setValue=function(k,v){try{localStorage.setItem('GM_'+k,JSON.stringify(v))}catch(e){}};
                window.GM_getValue=function(k,d){try{var v=localStorage.getItem('GM_'+k);return v===null?d:JSON.parse(v)}catch(e){return d}};
                window.GM_addStyle=function(css){var s=document.createElement('style');s.textContent=css;document.head.appendChild(s);return s};
                window.GM_xmlhttpRequest=function(o){fetch(o.url,{method:o.method||'GET',headers:o.headers,body:o.data,credentials:'include'}).then(r=>r.text().then(t=>o.onload&&o.onload({responseText:t,status:r.status}))).catch(e=>o.onerror&&o.onerror(e))};
                window.unsafeWindow=window;
            """.trimIndent() else ""
            val wrapped =
                "(function(){try{\nconsole.log('LB inject: ${esc(sc.name)}');\n$gm\n${sc.code}\n}catch(e){console.error('LB script error:',e);}})();"
            webView.evaluateJavascript(wrapped, null)
        } catch (_: Exception) {}
    }

    private fun esc(s: String) =
        s.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n").replace("\"", "\\\"")
}
