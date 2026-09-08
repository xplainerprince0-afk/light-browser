package com.lightbrowser.data

import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.WebView
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
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

    // Minimal socket HTTP server (same-device testing). We deliberately avoid
    // com.sun.net.httpserver — it is NOT on Android and breaks R8/release builds.
    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null
    private val pool = Executors.newCachedThreadPool()
    private val mainHandler = Handler(Looper.getMainLooper())

    // ── Console ring buffer (last 200) ──
    private val consoleBuf = ArrayDeque<String>()
    @Synchronized
    fun logConsole(s: String) {
        consoleBuf.addLast(s)
        while (consoleBuf.size > 200) consoleBuf.removeFirst()
    }
    @Synchronized
    fun consoleTail(n: Int): List<String> = consoleBuf.takeLast(n.coerceIn(1, 200)).toList()
    @Synchronized
    fun clearConsole() { consoleBuf.clear() }

    /** Viewport screenshot → sandbox/shots file. Returns path or null. */
    fun captureShot(): String? {
        val f = CompletableFuture<String?>()
        mainHandler.post {
            try {
                val wv = webViewProvider?.invoke()
                if (wv == null || wv.width <= 0 || wv.height <= 0) {
                    f.complete(null)
                    return@post
                }
                val scale = (1280f / wv.width).coerceAtMost(1f)
                val bw = (wv.width * scale).toInt().coerceAtLeast(1)
                val bh = (wv.height * scale).toInt().coerceAtLeast(1)
                val bmp = android.graphics.Bitmap.createBitmap(bw, bh, android.graphics.Bitmap.Config.ARGB_8888)
                val c = android.graphics.Canvas(bmp)
                c.scale(scale, scale)
                wv.draw(c)
                val dir = java.io.File(wv.context.filesDir, "sandbox/shots").apply { mkdirs() }
                val out = java.io.File(dir, "shot_${System.currentTimeMillis()}.png")
                java.io.FileOutputStream(out).use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 90, it) }
                try { bmp.recycle() } catch (_: Exception) {}
                f.complete(out.absolutePath)
            } catch (e: Exception) {
                Log.w(TAG, "shot", e)
                try { f.complete(null) } catch (_: Exception) {}
            }
        }
        return try {
            f.get(15, TimeUnit.SECONDS)
        } catch (_: Exception) { null }
    }

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
            scroll:function(y){try{window.scrollBy(0,y||500);}catch(err){}return 'OK';},
            describe:function(e){
              try{
                if(!e||!e.tagName) return {selector:'?',text:''};
                var s=sel(e);
                var t=((e.innerText||e.value||e.getAttribute('aria-label')||'')+'').slice(0,120);
                var r={selector:s,tag:e.tagName.toLowerCase(),text:t};
                try{var b=e.getBoundingClientRect();r.rect=[Math.round(b.x),Math.round(b.y),Math.round(b.width),Math.round(b.height)];}catch(err){}
                return r;
              }catch(err){return {selector:'?',text:''};}
            },
            record:function(on){
              try{
                if(window.__lb_recHandler&&window.__lb_recTarget){
                  window.__lb_recTarget.removeEventListener('click',window.__lb_recHandler,true);
                  window.__lb_recTarget.removeEventListener('input',window.__lb_recHandler,true);
                  window.__lb_recHandler=null;
                }
                if(!on) return 'OK';
                var h=function(ev){
                  try{
                    var d=window.LightAgent.describe(ev.target);
                    d.op=(ev.type==='input')?'fill':'click';
                    if(ev.type==='input'){d.value=(ev.target.value||'').slice(0,200);}
                    console.log('__LB_REC__:'+JSON.stringify(d));
                  }catch(err){}
                };
                window.__lb_recHandler=h;
                window.__lb_recTarget=document;
                document.addEventListener('click',h,true);
                document.addEventListener('input',h,true);
                return 'OK';
              }catch(err){return 'ERR '+err;}
            }
          };
        })();
    """.trimIndent()

    fun ensureShim(wv: WebView) {
        try {
            wv.evaluateJavascript(SHIM, null)
        } catch (e: Exception) { Log.w(TAG, "shim", e) }
    }

    // ── Click recorder (human automation data) ──
    private val _recording = MutableStateFlow(false)
    val recording: StateFlow<Boolean> = _recording.asStateFlow()
    private val recEvents = mutableListOf<JSONObject>()
    private var recStartUrl = ""
    private var recStartMs = 0L

    fun isRecording(): Boolean = _recording.value

    fun startRecording() {
        recEvents.clear()
        recStartUrl = try { webViewProvider?.invoke()?.url ?: "" } catch (_: Exception) { "" }
        recStartMs = System.currentTimeMillis()
        _recording.value = true
        try {
            webViewProvider?.invoke()?.evaluateJavascript(
                "(function(){try{if(window.LightAgent)window.LightAgent.record(true);}catch(e){}})()", null
            )
        } catch (_: Exception) {}
    }

    fun stopRecording() {
        _recording.value = false
        try {
            webViewProvider?.invoke()?.evaluateJavascript(
                "(function(){try{if(window.LightAgent)window.LightAgent.record(false);}catch(e){}})()", null
            )
        } catch (_: Exception) {}
    }

    /** Called from the shim's capture listener (re-armed after every navigation). */
    fun rearmRecorder(wv: WebView) {
        if (!_recording.value) return
        try {
            wv.evaluateJavascript(
                "(function(){try{if(window.LightAgent)window.LightAgent.record(true);}catch(e){}})()", null
            )
        } catch (_: Exception) {}
    }

    @Synchronized
    fun recordEvent(json: String) {
        if (!_recording.value) return
        try {
            val o = JSONObject(json)
            o.put("t", System.currentTimeMillis() - recStartMs)
            recEvents.add(o)
        } catch (_: Exception) {}
    }

    @Synchronized
    fun recCount(): Int = recEvents.size

    @Synchronized
    fun saveRecording(name: String): String? {
        return try {
            if (recEvents.isEmpty()) return null
            val app = AppCtx.ctx
            val dir = java.io.File(app.filesDir, "sandbox/agent_recs").apply { mkdirs() }
            val safe = name.replace(Regex("[^A-Za-z0-9_-]"), "_").take(40).ifBlank { "rec" }
            val out = java.io.File(dir, "${safe}_${System.currentTimeMillis()}.json")
            val root = JSONObject()
            root.put("app", "lightbrowser-rec")
            root.put("v", 1)
            root.put("startUrl", recStartUrl)
            root.put("startedAt", recStartMs)
            val arr = org.json.JSONArray()
            recEvents.forEach { arr.put(it) }
            root.put("actions", arr)
            out.writeText(root.toString(1), Charsets.UTF_8)
            out.absolutePath
        } catch (_: Exception) { null }
    }

    @Synchronized
    fun listRecordings(): List<Pair<String, Int>> {
        return try {
            val dir = java.io.File(AppCtx.ctx.filesDir, "sandbox/agent_recs")
            (dir.listFiles()?.filter { it.extension == "json" } ?: emptyList())
                .sortedByDescending { it.lastModified() }
                .map { f ->
                    val n = try { JSONObject(f.readText()).optJSONArray("actions")?.length() ?: 0 } catch (_: Exception) { 0 }
                    f.name to n
                }
        } catch (_: Exception) { emptyList() }
    }

    // ── Localhost HTTP server (same-device testing only) ──

    @Synchronized
    fun startServer(): String {
        stopServer()
        token = UUID.randomUUID().toString().take(8)
        return try {
            val ss = ServerSocket()
            ss.reuseAddress = true
            ss.bind(InetSocketAddress("127.0.0.1", PORT))
            serverSocket = ss
            val running = AtomicBoolean(true)
            val t = Thread({
                while (running.get() && !ss.isClosed) {
                    try {
                        val sock = ss.accept()
                        pool.execute { handleSocket(sock) }
                    } catch (_: Exception) {
                        break
                    }
                }
            }, "AgentServer")
            t.isDaemon = true
            acceptThread = t
            t.start()
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
        try { serverSocket?.close() } catch (_: Exception) {}
        serverSocket = null
        acceptThread = null
        _serverRunning.value = false
        _serverLabel.value = ""
    }

    private fun handleSocket(sock: Socket) {
        try {
            sock.soTimeout = 15_000
            val reader = BufferedReader(InputStreamReader(sock.getInputStream(), Charsets.UTF_8))
            val requestLine = reader.readLine() ?: return
            // Consume headers
            var line: String?
            do {
                line = reader.readLine()
            } while (line != null && line.isNotEmpty())
            val parts = requestLine.split(" ")
            if (parts.size < 2 || parts[0] != "GET") {
                respondRaw(sock.getOutputStream(), 405, """{"ok":false,"err":"GET only"}""")
                return
            }
            val rawTarget = parts[1]
            val path = rawTarget.substringBefore("?")
            val query = if (rawTarget.contains("?")) rawTarget.substringAfter("?") else ""
            val q = parseQuery(query)
            if (path != "/status" && q["token"] != token) {
                respondRaw(sock.getOutputStream(), 403, """{"ok":false,"err":"bad token"}""")
                return
            }
            respondRaw(sock.getOutputStream(), 200, handle(path, q))
        } catch (_: Exception) {
            try { respondRaw(sock.getOutputStream(), 500, """{"ok":false,"err":"io"}""") } catch (_: Exception) {}
        } finally {
            try { sock.close() } catch (_: Exception) {}
        }
    }

    private fun respondRaw(out: OutputStream, code: Int, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val head = "HTTP/1.1 $code OK\r\nContent-Type: application/json; charset=utf-8\r\n" +
            "Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
        out.write(head.toByteArray(Charsets.UTF_8))
        out.write(bytes)
        out.flush()
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
            "/fill" -> {                val sel = q["sel"] ?: return """{"ok":false,"err":"missing sel"}"""
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
            "/console" -> {
                val n = q["n"]?.toIntOrNull() ?: 30
                val arr = org.json.JSONArray()
                consoleTail(n).forEach { arr.put(it) }
                JSONObject().put("ok", true).put("lines", arr).toString()
            }
            "/cookies" -> awaitMain {
                val f = CompletableFuture<String?>()
                mainHandler.post {
                    try {
                        val wv = webViewProvider?.invoke()
                        val url = wv?.url ?: ""
                        f.complete(
                            try { android.webkit.CookieManager.getInstance().getCookie(url) } catch (_: Exception) { null }
                        )
                    } catch (_: Exception) { f.complete(null) }
                }
                val ck = try { f.get(5, TimeUnit.SECONDS) } catch (_: Exception) { null }
                JSONObject().put("ok", true).put("cookies", ck ?: JSONObject.NULL).toString()
            }
            "/shot" -> awaitMain {
                val f = CompletableFuture<String?>()
                try {
                    val wv = webViewProvider?.invoke()
                    if (wv == null || wv.width <= 0 || wv.height <= 0) f.complete(null)
                    else {
                        val scale = (1280f / wv.width).coerceAtMost(1f)
                        val bmp = android.graphics.Bitmap.createBitmap(
                            (wv.width * scale).toInt().coerceAtLeast(1),
                            (wv.height * scale).toInt().coerceAtLeast(1),
                            android.graphics.Bitmap.Config.ARGB_8888
                        )
                        val c = android.graphics.Canvas(bmp)
                        c.scale(scale, scale)
                        wv.draw(c)
                        val dir = java.io.File(wv.context.filesDir, "sandbox/shots").apply { mkdirs() }
                        val out = java.io.File(dir, "shot_${System.currentTimeMillis()}.png")
                        java.io.FileOutputStream(out).use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 90, it) }
                        try { bmp.recycle() } catch (_: Exception) {}
                        f.complete(out.absolutePath)
                    }
                } catch (e: Exception) { f.complete(null) }
                val path = try { f.get(15, TimeUnit.SECONDS) } catch (_: Exception) { null }
                if (path != null) JSONObject().put("ok", true).put("path", path).toString()
                else """{"ok":false,"err":"shot failed"}"""
            }
            else -> """{"ok":false,"err":"unknown path. try /status /open /text /snap /js /click /fill /back /reload /console /shot"}"""
        }
    }
}
