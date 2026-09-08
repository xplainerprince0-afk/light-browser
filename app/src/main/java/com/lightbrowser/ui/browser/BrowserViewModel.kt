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
import kotlinx.coroutines.withContext

data class BrowserTab(
    val id: String = UUID.randomUUID().toString(),
    val url: String,
    val title: String = ""
)

/** Bridge for window.open / target=_blank → open in new tab (set by BrowserScreen). */
object TabBus {
    var openInNewTab: ((String) -> Unit)? = null
    fun openInNewTab(url: String) { try { openInNewTab?.invoke(url) } catch (_: Exception) {} }
}

const val HOME_URL = "lb://home"

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

    private val _loadRequest = MutableStateFlow<Pair<String, Long>?>(null)
    val loadRequest: StateFlow<Pair<String, Long>?> = _loadRequest.asStateFlow()

    private val _findCount = MutableStateFlow<Pair<Int, Int>?>(null)
    val findCount: StateFlow<Pair<Int, Int>?> = _findCount.asStateFlow()

    private val _reader = MutableStateFlow<Pair<String, String>?>(null)
    val reader: StateFlow<Pair<String, String>?> = _reader.asStateFlow()

    val ctx get() = AppCtx.ctx

    /** url → saved scroll Y, restored when the page is revisited. */
    private val scrollMemory = mutableMapOf<String, Int>()

    init {
        val restored = restoreTabs()
        if (restored != null) {
            _ui.update { it.copy(tabs = restored.first, currentIndex = restored.second, currentUrl = restored.first.getOrNull(restored.second)?.url ?: "") }
        } else {
            val home = HOME_URL
            _ui.update { it.copy(tabs = listOf(BrowserTab(url = home)), currentUrl = home) }
        }
        refreshLists()
    }

    private fun persistTabs() {
        try {
            val tabs = _ui.value.tabs
            val arr = org.json.JSONArray()
            tabs.forEach { t ->
                arr.put(org.json.JSONObject().put("url", t.url).put("title", t.title))
            }
            ctx.getSharedPreferences("browser_tabs", android.content.Context.MODE_PRIVATE).edit()
                .putString("tabs", arr.toString())
                .putInt("index", _ui.value.currentIndex)
                .apply()
        } catch (_: Exception) {}
    }

    private fun restoreTabs(): Pair<List<BrowserTab>, Int>? {
        return try {
            val p = ctx.getSharedPreferences("browser_tabs", android.content.Context.MODE_PRIVATE)
            val raw = p.getString("tabs", null) ?: return null
            val arr = org.json.JSONArray(raw)
            if (arr.length() == 0) return null
            val tabs = (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                BrowserTab(url = o.optString("url", HOME_URL), title = o.optString("title", ""))
            }.filter { it.url.isNotBlank() }
            if (tabs.isEmpty()) return null
            tabs to p.getInt("index", 0).coerceIn(tabs.indices)
        } catch (_: Exception) { null }
    }

    fun saveScroll(url: String, y: Int) {
        if (url.isNotBlank() && !url.startsWith("lb://") && y > 0) {
            scrollMemory[url] = y
            if (scrollMemory.size > 60) scrollMemory.remove(scrollMemory.keys.first())
        }
    }

    fun popScroll(url: String): Int? = scrollMemory.remove(url)

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
            // Sync bookmark title if this URL is bookmarked but title was URL placeholder.
            it.copy(tabs = tabs, currentUrl = url, currentTitle = title, loading = false, progress = 100)
        }
        persistTabs()
        if (url.startsWith("lb://") || url.isBlank()) return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                HistoryStorage.add(ctx, url, title)
                _history.value = HistoryStorage.all(ctx)
                val marked = _bookmarks.value.any { it.url == url }
                    || BookmarkStorage.isBookmarked(ctx, url)
                withContext(Dispatchers.Main) { _ui.update { it.copy(bookmarked = marked) } }
            } catch (_: Exception) {}
        }
    }

    fun resolveInput(input: String): String {
        val t = input.trim()
        if (t.isEmpty()) return ""
        return when {
            t.startsWith("lb://") -> t
            t.startsWith("http://") || t.startsWith("https://") -> t
            t.startsWith("ftp://") || t.startsWith("file://") -> t
            // localhost / IP with port, no dot but valid dev URL
            t.matches(Regex("""^(localhost|127\.0\.0\.1|\[::1\])(:\d+)?(/.*)?$""", RegexOption.IGNORE_CASE)) -> "http://$t"
            t.matches(Regex("""^[\w-]+(\.[\w-]+)+(:\d+)?(/.*)?$""")) && !t.contains(" ") -> "https://$t"
            t.contains(".") && !t.contains(" ") -> "https://$t"
            else -> Prefs.buildSearchUrl(t)
        }
    }

    fun openTab(url: String, select: Boolean = true) {
        _ui.update {
            val tabs = it.tabs + BrowserTab(url = url)
            val idx = if (select) tabs.size - 1 else it.currentIndex
            val cur = tabs[idx]
            it.copy(tabs = tabs, currentIndex = idx, currentUrl = cur.url, currentTitle = cur.title)
        }
        if (select && !url.startsWith("lb://")) requestLoad(url)
        persistTabs()
    }

    fun selectTab(i: Int) {
        val tabs = _ui.value.tabs
        if (i !in tabs.indices || i == _ui.value.currentIndex) return
        // Save scroll of outgoing tab is done by Screen; here just switch state + request load.
        _ui.update { it.copy(currentIndex = i, currentUrl = tabs[i].url, currentTitle = tabs[i].title, loading = tabs[i].url.startsWith("http")) }
        if (!tabs[i].url.startsWith("lb://")) requestLoad(tabs[i].url)
        persistTabs()
    }

    fun closeTab(i: Int): String {
        val tabs = _ui.value.tabs.toMutableList()
        if (tabs.size <= 1 || i !in tabs.indices) return _ui.value.currentUrl
        val cur = _ui.value.currentIndex
        tabs.removeAt(i)
        // If closing a tab before current, shift index left; if closing current, clamp.
        val ni = when {
            i < cur -> (cur - 1).coerceIn(tabs.indices)
            i == cur -> cur.coerceIn(tabs.indices)
            else -> cur.coerceIn(tabs.indices)
        }
        _ui.update { it.copy(tabs = tabs, currentIndex = ni, currentUrl = tabs[ni].url, currentTitle = tabs[ni].title, loading = false) }
        persistTabs()
        if (!tabs[ni].url.startsWith("lb://")) requestLoad(tabs[ni].url)
        return tabs[ni].url
    }

    fun onTabSettled() = persistTabs()

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

    fun setFind(ordinal: Int, total: Int) {
        _findCount.value = ordinal to total
    }

    fun clearFind() {
        _findCount.value = null
    }

    fun clearReader() {
        _reader.value = null
    }

    fun loadReader() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val raw = com.lightbrowser.data.BrowserAgent.eval(
                    "(function(){try{" +
                        "var a=document.querySelector('article');var t='';if(a){t=a.innerText;}" +
                        "else{var ps=Array.prototype.slice.call(document.querySelectorAll('p'));" +
                        "ps=ps.filter(function(p){return p.innerText&&p.innerText.length>80;});" +
                        "t=ps.map(function(p){return p.innerText;}).join('\\n\\n').slice(0,30000);}" +
                        "return JSON.stringify({title:document.title,text:t});" +
                        "}catch(e){return JSON.stringify({title:'',text:''});}})()"
                )
                val title = _ui.value.currentTitle.ifBlank { _ui.value.currentUrl }
                val parsed = parseReader(raw, title)
                withContext(Dispatchers.Main) { _reader.value = parsed }
            } catch (_: Exception) {
                _reader.value = _ui.value.currentUrl to "(reader failed)"
            }
        }
    }

    private fun parseReader(raw: String, title: String): Pair<String, String> {
        return try {
            // evaluateJavascript returns a JSON-encoded string: unwrap quotes/escapes.
            // Order matters: \\\" -> \" first via placeholder, then \\n, then \\\\ last is wrong.
            // Correct: unescape \\\\ first using JSON parser semantics manually.
            var s = raw.trim()
            if (s.startsWith("\"") && s.endsWith("\"") && s.length >= 2) {
                // Use JSONObject to unescape properly instead of chained replace.
                s = try { org.json.JSONObject("\"v\":$s\"".let { "{\"v\":$s}" }).optString("v", s) } catch (_: Exception) {
                    s.substring(1, s.length - 1)
                        .replace("\\\\", "\u0000").replace("\\n", "\n").replace("\\\"", "\"")
                        .replace("\u0000", "\\")
                }
            }
            val o = org.json.JSONObject(s)
            val t = o.optString("title", title)
            val text = o.optString("text", "")
            (if (t.isBlank()) title else t) to text.ifBlank { "(no article text found)" }
        } catch (_: Exception) {
            title to "(reader failed)"
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
        // Exact runAt match only — previously end+idle both fired twice (WebViewSetup
        // calls end then idle 350ms apart). document_end scripts run on end, idle on idle.
        val toInject = matched.filter { sc ->
            when (sc.runAt) {
                "document_start" -> runAt == "document_start"
                "document_end" -> runAt == "document_end"
                else -> runAt == "document_idle"
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
