package com.lightbrowser.data

import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.WebView
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

/**
 * In-process terminal → browser bridge + optional localhost HTTP server for
 * quick testing from the main Termux (`curl http://127.0.0.1:8089/...`).
 *
 * Control flows native → JS only. Page content coming back is UNTRUSTED.
 */
object BrowserAgent {
    private const val TAG = "BrowserAgent"
    const val PORT = 8089

    var webViewProvider: (() -> WebView?)? = null

    private val _serverRunning = MutableStateFlow(false)
    val serverRunning: StateFlow<Boolean> = _serverRunning.asStateFlow()

    private val _serverLabel = MutableStateFlow("")
    val serverLabel: StateFlow<String> = _serverLabel.asStateFlow()

    var token: String = UUID.randomUUID().toString().take(8)
        private set

    private var server: HttpServer? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    // ── WebView ops (always on Main) ──

    fun navigate(url: String) {
        val safe = url.trim()
        if (!safe.startsWith("http://") && !safe.startsWith("https://")) return
        mainHandler.post {
            try {
                webViewProvider?.invoke()?.loadUrl(safe, mapOf("X-Requested-With" to ""))
            } catch (e: Exception) { Log.w(TAG, "navigate", e) }
        }
    }

    fun runOnPage(action: (WebView) -> Unit) {
        mainHandler.post {
            try {
                webViewProvider?.invoke()?.let(action)
            } catch (e: Exception) { Log.w(TAG, "runOnPage", e) }
        }
    }

    fun currentUrl(): String? = try {
        webViewProvider?.invoke()?.url
    } catch (_: Exception) { null }

    /** Suspend eval with timeout + size cap. Returns raw JSON-ish string. */
    suspend fun eval(expr: String, timeoutMs: Long = 12_000, maxChars: Int = 60_000): String {
        return try {
            val future = CompletableFuture<String>()
            mainHandler.post {
                try {
                    val wv = webViewProvider?.invoke()
                    if (wv == null) {
                        future.complete("ERR no-webview")
                        return@post
                    }
                    wv.evaluateJavascript(expr) { raw ->
                        try {
                            if (!future.isDone) future.complete(raw ?: "null")
                        } catch (_: Exception) {}
                    }
                } catch (e: Exception) {
                    try {
                        if (!future.isDone) future.complete("ERR ${e.message}")
                    } catch (_: Exception) {}
                }
            }
            var out = try {
                future.get(timeoutMs, TimeUnit.MILLISECONDS) ?: "null"
            } catch (_: Exception) {
                "ERR timeout"
            }
            if (out.length > maxChars) out = out.take(maxChars) + "…[truncated]"
            out
        } catch (e: Exception) {
            "ERR ${e.message}"
        }
    }

    suspend fun snapshot(): String =
        eval("(function(){try{return window.LightAgent?window.LightAgent.snapshot(): 'ERR no-shim';}catch(e){return 'ERR '+e;}})()")

    suspend fun pageText(max: Int = 8000): String =
        eval("(function(){try{return JSON.stringify(document.body?document.body.innerText.slice(0,$max):'');}catch(e){return 'ERR '+e;}})()")

    // ── LightAgent JS shim ──

    val SHIM: String = """
        (function(){
          if(window.LightAgent) return;
          var refs={}; var n=0;
          function sel(e){
            try{
              if(e.id) return '#'+CSS.escape(e.id);
              var sibs=Array.prototype.filter.call(e.parentNode.children,function(x){return x.tagName===e.tagName;});
              return e.tagName.toLowerCase()+':nth-of-type('+(sibs.indexOf(e)+1)+')';
            }catch(err){return e.tagName.toLowerCase();}
          }
          window.LightAgent={
            snapshot:function(max){
              max=max||8000; n=0; refs={};
              var els=Array.prototype.slice.call(document.querySelectorAll('a,button,input,select,textarea,[role=button]'));
              els=els.filter(function(e){return e.offsetParent!==null;}).slice(0,200);
              var items=els.map(function(e){
                var r='e'+(++n); refs[r]=sel(e);
                var name=(e.innerText||e.value||e.getAttribute('aria-label')||e.placeholder||'').toString().slice(0,120);
                return {ref:r,role:(e.getAttribute('role')||e.tagName.toLowerCase()),name:name,selector:refs[r]};
              });
              return JSON.stringify({url:location.href,title:document.title,count:items.length,interactive:items,text:(document.body?document.body.innerText.slice(0,max):'')});
            },
            getText:function(m){return document.body?document.body.innerText.slice(0,m||8000):'';},
            click:function(t){var s=refs[t]||t;var e=document.querySelector(s);if(!e)return 'ERR no-node';try{e.scrollIntoView({block:'center'});}catch(err){}e.click();return 'OK';},
            fill:function(t,v){var s=refs[t]||t;var e=document.querySelector(s);if(!e)return 'ERR no-node';e.focus();e.value=v;e.dispatchEvent(new Event('input',{bubbles:true}));e.dispatchEvent(new Event('change',{bubbles:true}));return 'OK';},
            scroll:function(y){try{window.scrollBy(0,y||500);}catch(err){}return 'OK';}
          };
        })();
    """.trimIndent()

    fun ensureShim(wv: WebView) {
        try {
            wv.evaluateJavascript(SHIM, null)
        } catch (e: Exception) { Log.w(TAG, "shim", e) }
    }

    // ── Localhost HTTP server (same-device testing only) ──

    @Synchronized
    fun startServer(): String {
        stopServer()
        token = UUID.randomUUID().toString().take(8)
        return try {
            val s = HttpServer.create(InetSocketAddress("127.0.0.1", PORT), 0)
            s.createContext("/") { ex ->
                try {
                    val path = ex.requestURI.path ?: "/"
                    val q = parseQuery(ex.requestURI.rawQuery ?: "")
                    if (path != "/status" && q["token"] != token) {
                        respond(ex, 403, """{"ok":false,"err":"bad token"}""")
                        return@createContext
                    }
                    // WebView calls must run on Main: block this worker thread briefly.
                    val out = handle(path, q)
                    respond(ex, 200, out)
                } catch (e: Exception) {
                    try { respond(ex, 500, """{"ok":false,"err":"${e.message}"}""") } catch (_: Exception) {}
                }
            }
            s.executor = java.util.concurrent.Executors.newCachedThreadPool()
            s.start()
            server = s
            _serverRunning.value = true
            _serverLabel.value = "http://127.0.0.1:$PORT • token $token"
            _serverLabel.value
        } catch (e: Exception) {
            Log.e(TAG, "startServer", e)
            _serverRunning.value = false
            _serverLabel.value = "Failed: ${e.message}"
            _serverLabel.value
        }
    }

    @Synchronized
    fun stopServer() {
        try { server?.stop(0) } catch (_: Exception) {}
        server = null
        _serverRunning.value = false
        _serverLabel.value = ""
    }

    private fun parseQuery(raw: String): Map<String, String> {
        if (raw.isBlank()) return emptyMap()
        return raw.split("&").mapNotNull { kv ->
            val i = kv.indexOf('=')
            if (i < 0) null
            else try {
                URLDecoder.decode(kv.substring(0, i), "UTF-8") to
                    URLDecoder.decode(kv.substring(i + 1), "UTF-8")
            } catch (_: Exception) { null }
        }.toMap()
    }

    private fun respond(ex: com.sun.net.httpserver.HttpExchange, code: Int, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        ex.responseHeaders.add("Content-Type", "application/json; charset=utf-8")
        ex.sendResponseHeaders(code, bytes.size.toLong())
        ex.responseBody.use { it.write(bytes) }
    }

    /** Runs on the HTTP worker thread; WebView ops hop to Main and block-await. */
    private fun handle(path: String, q: Map<String, String>): String {
        fun awaitMain(op: () -> String): String {
            val f = CompletableFuture<String>()
            mainHandler.post {
                try {
                    f.complete(op())
                } catch (e: Exception) {
                    f.complete("""{"ok":false,"err":"${e.message}"}""")
                }
            }
            return try {
                f.get(15, TimeUnit.SECONDS)
            } catch (_: Exception) {
                """{"ok":false,"err":"timeout"}"""
            }
        }
        return when (path) {
            "/status" -> awaitMain {
                val wv = webViewProvider?.invoke()
                JSONObject().put("ok", true)
                    .put("url", wv?.url ?: JSONObject.NULL)
                    .put("title", wv?.title ?: JSONObject.NULL).toString()
            }
            "/open" -> {
                val url = q["url"] ?: return """{"ok":false,"err":"missing url"}"""
                navigate(url)
                """{"ok":true}"""
            }
            "/back" -> {
                runOnPage { try { if (it.canGoBack()) it.goBack() } catch (_: Exception) {} }
                """{"ok":true}"""
            }
            "/reload" -> {
                runOnPage { try { it.reload() } catch (_: Exception) {} }
                """{"ok":true}"""
            }
            "/js" -> {
                val expr = q["expr"] ?: return """{"ok":false,"err":"missing expr"}"""
                awaitMain {
                    val f = CompletableFuture<String>()
                    try {
                        webViewProvider?.invoke()?.evaluateJavascript(expr) { raw ->
                            try { f.complete(raw ?: "null") } catch (_: Exception) {}
                        } ?: f.complete("ERR no-webview")
                    } catch (e: Exception) { f.complete("ERR ${e.message}") }
                    val raw = try { f.get(12, TimeUnit.SECONDS) } catch (_: Exception) { "ERR timeout" }
                    JSONObject().put("ok", !raw.startsWith("ERR")).put("result", raw.take(60_000)).toString()
                }
            }
            "/text" -> awaitMain {
                val f = CompletableFuture<String>()
                try {
                    val wv = webViewProvider?.invoke()
                    if (wv == null) f.complete("ERR no-webview")
                    else {
                        ensureShim(wv)
                        wv.evaluateJavascript("(function(){try{return JSON.stringify(window.LightAgent.getText(8000));}catch(e){return 'ERR '+e;}})()") { raw ->
                            try { f.complete(raw ?: "null") } catch (_: Exception) {}
                        }
                    }
                } catch (e: Exception) { f.complete("ERR ${e.message}") }
                val raw = try { f.get(12, TimeUnit.SECONDS) } catch (_: Exception) { "ERR timeout" }
                JSONObject().put("ok", !raw.startsWith("ERR")).put("text", raw.take(60_000)).toString()
            }
            "/snap" -> awaitMain {
                val f = CompletableFuture<String>()
                try {
                    val wv = webViewProvider?.invoke()
                    if (wv == null) f.complete("ERR no-webview")
                    else {
                        ensureShim(wv)
                        wv.evaluateJavascript("(function(){try{return window.LightAgent.snapshot();}catch(e){return 'ERR '+e;}})()") { raw ->
                            try { f.complete(raw ?: "null") } catch (_: Exception) {}
                        }
                    }
                } catch (e: Exception) { f.complete("ERR ${e.message}") }
                try {
                    f.get(12, TimeUnit.SECONDS)
                } catch (_: Exception) { "ERR timeout" }
            }
            "/click" -> {
                val sel = q["sel"] ?: return """{"ok":false,"err":"missing sel"}"""
                awaitMain {
                    val f = CompletableFuture<String>()
                    try {
                        val wv = webViewProvider?.invoke()
                        if (wv == null) f.complete("ERR no-webview")
                        else {
                            ensureShim(wv)
                            val esc = sel.replace("\\", "\\\\").replace("'", "\\'")
                            wv.evaluateJavascript("(function(){try{return window.LightAgent.click('$esc');}catch(e){return 'ERR '+e;}})()") { raw ->
                                try { f.complete(raw ?: "null") } catch (_: Exception) {}
                            }
                        }
                    } catch (e: Exception) { f.complete("ERR ${e.message}") }
                    val raw = try { f.get(12, TimeUnit.SECONDS) } catch (_: Exception) { "ERR timeout" }
                    JSONObject().put("ok", raw.contains("OK")).put("result", raw).toString()
                }
            }
            "/fill" -> {
                val sel = q["sel"] ?: return """{"ok":false,"err":"missing sel"}"""
                val value = q["value"] ?: ""
                awaitMain {
                    val f = CompletableFuture<String>()
                    try {
                        val wv = webViewProvider?.invoke()
                        if (wv == null) f.complete("ERR no-webview")
                        else {
                            ensureShim(wv)
                            val e1 = sel.replace("\\", "\\\\").replace("'", "\\'")
                            val e2 = value.replace("\\", "\\\\").replace("'", "\\'")
                            wv.evaluateJavascript("(function(){try{return window.LightAgent.fill('$e1','$e2');}catch(e){return 'ERR '+e;}})()") { raw ->
                                try { f.complete(raw ?: "null") } catch (_: Exception) {}
                            }
                        }
                    } catch (e: Exception) { f.complete("ERR ${e.message}") }
                    val raw = try { f.get(12, TimeUnit.SECONDS) } catch (_: Exception) { "ERR timeout" }
                    JSONObject().put("ok", raw.contains("OK")).put("result", raw).toString()
                }
            }
            else -> """{"ok":false,"err":"unknown path. try /status /open /text /snap /js /click /fill /back /reload"}"""
        }
    }
}
