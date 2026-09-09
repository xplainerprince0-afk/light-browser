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

    var token: String = UUID.randomUUID().toString().take(24)
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

    /** Viewport screenshot → sandbox/shots file. Returns path or null. Main-safe (no deadlock). */
    /** Compact readability extraction (article/main → largest text block,
     *  chrome stripped). Single source for EXEC `b read` and `/read`. */
    fun readJs(max: Int): String = "(function(){try{" +
        "var root=document.querySelector('article')||document.querySelector('main')||document.querySelector('[role=main]')||document.body;" +
        "var c=root.cloneNode(true);" +
        "var k=c.querySelectorAll('script,style,nav,header,footer,aside,form');" +
        "for(var j=k.length-1;j>=0;j--){try{k[j].remove();}catch(x){}}" +
        "var out=[],ps=c.querySelectorAll('p,h1,h2,h3,li,pre');" +
        "for(var i=0;i<ps.length;i++){var s=(ps[i].textContent||'').replace(/\\s+/g,' ').trim();if(s.length>40)out.push(s);}" +
        "var t=out.join('\\n\\n');if(!t)t=(c.textContent||'').replace(/\\s+/g,' ').trim();" +
        "return (document.title||'')+'\\n\\n'+t.slice(0," + max + ");" +
        "}catch(e){return 'ERR '+e;}})()"

    /**
     * Probe JS for `b key`: anchor field (explicit sel → focused field →
     * field with text → first search/text field), then the nearest visible
     * button (same-form submit first, else closest by distance). Returns
     * JSON {x,y,label} or ERR. Single source for EXEC + `/key`.
     */
    fun keyProbeJs(sel: String): String {
        val e0 = sel.replace("\\", "\\\\").replace("'", "\\'").take(500)
        return "(function(){try{" +
            "var anchor=null;" +
            (if (e0.isNotBlank()) "try{anchor=document.querySelector('$e0');}catch(x){}" else "") +
            "if(!anchor){var ae=document.activeElement;if(ae&&(ae.tagName==='INPUT'||ae.tagName==='TEXTAREA'||ae.isContentEditable))anchor=ae;}" +
            "if(!anchor){var ins=document.querySelectorAll('input');for(var i=0;i<ins.length;i++){var t=(ins[i].type||'text').toLowerCase();if((t==='text'||t==='search'||t==='email'||t==='password'||t==='url'||t==='number')&&ins[i].value&&vis(ins[i])){anchor=ins[i];break;}}}" +
            "if(!anchor){var ss=document.querySelectorAll('input');for(var s2=0;s2<ss.length;s2++){var t2=(ss[s2].type||'text').toLowerCase();if((t2==='text'||t2==='search')&&vis(ss[s2])){anchor=ss[s2];break;}}}" +
            "if(!anchor)return 'ERR no-field (focus a field or pass a selector)';" +
            // offsetParent is null for position:fixed (sticky SPA headers —
            // the Bing miss), so visibility is checked via computed style.
            "function vis(e){if(!e||!e.getBoundingClientRect)return false;try{var cs=getComputedStyle(e);if(cs.display==='none'||cs.visibility==='hidden'||cs.opacity==='0')return false;}catch(x){}var r=e.getBoundingClientRect();return r.width>4&&r.height>4&&r.bottom>0&&r.top<window.innerHeight;}" +
            "function isBtn(e){if(!e||!e.tagName)return false;var t=e.tagName;if(t==='BUTTON')return true;if(t==='INPUT'){var ty=(e.type||'').toLowerCase();return ty==='submit'||ty==='button'||ty==='image';}return e.getAttribute&&e.getAttribute('role')==='button';}" +
            "var ar=anchor.getBoundingClientRect(),ax=ar.left+ar.width/2,ay=ar.top+ar.height/2;" +
            "var best=null,f=anchor.form||(anchor.closest?anchor.closest('form'):null);" +
            "if(f){var bs=f.querySelectorAll('button,input[type=submit],input[type=image],input[type=button],[role=button]');for(var b=0;b<bs.length;b++){if(isBtn(bs[b])&&vis(bs[b])){best=bs[b];break;}}}" +
            "if(!best){var bd=1e18,all=document.querySelectorAll('button,input[type=submit],input[type=image],input[type=button],[role=button]');for(var k=0;k<all.length&&k<600;k++){var e=all[k];if(!isBtn(e)||!vis(e))continue;var r=e.getBoundingClientRect();var dx=r.left+r.width/2-ax,dy=r.top+r.height/2-ay;var d=dx*dx+dy*dy;if(d<bd){bd=d;best=e;}}}" +
            // Round 2 (SPA/React handlers leave no DOM trace): links, onclick
            // holders, keyboard-focusables. Only when no real button exists.
            "if(!best){var bd2=1e18,all2=document.querySelectorAll('a[href],[onclick],[tabindex],summary');for(var m=0;m<all2.length&&m<600;m++){var e2=all2[m];if(!vis(e2))continue;var r2=e2.getBoundingClientRect();var dx2=r2.left+r2.width/2-ax,dy2=r2.top+r2.height/2-ay;var d2=dx2*dx2+dy2*dy2;if(d2<bd2){bd2=d2;best=e2;}}}" +
            "if(!best)return 'ERR no-button near field';" +
            "var rr=best.getBoundingClientRect();" +
            "return JSON.stringify({x:Math.round(rr.left+rr.width/2),y:Math.round(rr.top+rr.height/2),label:(best.innerText||best.value||(best.getAttribute&&best.getAttribute('aria-label'))||best.tagName||'').toString().trim().slice(0,40)});" +
            "}catch(e){return 'ERR '+e;}})()"
    }

    fun captureShot(): String? {
        // Fast path: already on Main — do directly, no post+wait.
        if (Looper.myLooper() == Looper.getMainLooper()) return captureViewportOnMain()
        val f = CompletableFuture<String?>()
        mainHandler.post {
            try { f.complete(captureViewportOnMain()) } catch (e: Exception) {
                Log.w(TAG, "shot", e)
                try { f.complete(null) } catch (_: Exception) {}
            }
        }
        return try {
            f.get(15, TimeUnit.SECONDS)
        } catch (_: Exception) { null }
    }

    /**
     * Viewport-only capture. PixelCopy from the window when the WebView is
     * actually on-screen — draw() can't copy hardware-rendered surfaces
     * (blank shots — the "shot isn't working" bug; same root cause as the
     * MAUI WebView screenshot fix). draw() stays as the fallback for
     * parked tabs / pre-26 / PixelCopy failure.
     */
    private fun captureViewportOnMain(): String? {
        return try {
            val wv = webViewProvider?.invoke() ?: return null
            if (wv.width <= 0 || wv.height <= 0) return null
            val loc = IntArray(2)
            try { wv.getLocationInWindow(loc) } catch (_: Exception) { return captureShotOnMain() }
            // Parked offscreen tabs (our offscreen() modifier) sit at
            // -100000: PixelCopy would grab the wrong pixels — fall back.
            if (loc[0] < 0 || loc[1] < 0) return captureShotOnMain()
            if (android.os.Build.VERSION.SDK_INT < 26) return captureShotOnMain()
            val act = try {
                var c: android.content.Context? = wv.context
                while (c is android.content.ContextWrapper && c !is android.app.Activity) c = c.baseContext
                c as? android.app.Activity
            } catch (_: Exception) { null } ?: return captureShotOnMain()
            val win = try { act.window } catch (_: Exception) { null } ?: return captureShotOnMain()
            val rect = android.graphics.Rect(loc[0], loc[1], loc[0] + wv.width, loc[1] + wv.height)
            val bmp = android.graphics.Bitmap.createBitmap(wv.width, wv.height, android.graphics.Bitmap.Config.ARGB_8888)
            val f = CompletableFuture<Boolean>()
            try {
                android.view.PixelCopy.request(win, rect, bmp, { res ->
                    try { f.complete(res == android.view.PixelCopy.SUCCESS) } catch (_: Exception) {}
                }, mainHandler)
            } catch (_: Exception) {
                try { bmp.recycle() } catch (_: Exception) {}
                return captureShotOnMain()
            }
            val ok = try { f.get(10, TimeUnit.SECONDS) } catch (_: Exception) { false }
            if (!ok) {
                try { bmp.recycle() } catch (_: Exception) {}
                return captureShotOnMain()
            }
            val dir = java.io.File(wv.context.filesDir, "sandbox/shots").apply { mkdirs() }
            pruneDir(dir, 30)
            val out = java.io.File(dir, "shot_${System.currentTimeMillis()}.png")
            java.io.FileOutputStream(out).use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 90, it) }
            try { bmp.recycle() } catch (_: Exception) {}
            out.absolutePath
        } catch (e: Exception) {
            Log.w(TAG, "shot", e)
            null
        }
    }

    private fun captureShotOnMain(): String? {
        return try {
            val wv = webViewProvider?.invoke()
            if (wv == null || wv.width <= 0 || wv.height <= 0) return null
            val scale = (1280f / wv.width).coerceAtMost(1f)
            val bw = (wv.width * scale).toInt().coerceAtLeast(1)
            val bh = (wv.height * scale).toInt().coerceAtLeast(1)
            val bmp = android.graphics.Bitmap.createBitmap(bw, bh, android.graphics.Bitmap.Config.ARGB_8888)
            val c = android.graphics.Canvas(bmp)
            c.scale(scale, scale)
            wv.draw(c)
            val dir = java.io.File(wv.context.filesDir, "sandbox/shots").apply { mkdirs() }
            pruneDir(dir, 30)
            val out = java.io.File(dir, "shot_${System.currentTimeMillis()}.png")
            java.io.FileOutputStream(out).use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 90, it) }
            try { bmp.recycle() } catch (_: Exception) {}
            out.absolutePath
        } catch (e: Exception) {
            Log.w(TAG, "shot", e)
            null
        }
    }

    private fun pruneDir(dir: java.io.File, keep: Int) {
        try {
            val files = dir.listFiles()?.sortedByDescending { it.lastModified() } ?: return
            files.drop(keep).forEach { try { it.delete() } catch (_: Exception) {} }
        } catch (_: Exception) {}
    }

    /** Full-page screenshot via capturePicture (viewport-only is captureShot).
     *  Height-capped (~6MP) so endless pages can't OOM. Main-safe. */
    fun captureFullShot(): String? {
        if (Looper.myLooper() == Looper.getMainLooper()) return captureFullShotOnMain()
        val f = CompletableFuture<String?>()
        mainHandler.post {
            try { f.complete(captureFullShotOnMain()) } catch (e: Exception) {
                Log.w(TAG, "shotfull", e)
                try { f.complete(null) } catch (_: Exception) {}
            }
        }
        return try {
            f.get(20, TimeUnit.SECONDS)
        } catch (_: Exception) { null }
    }

    @Suppress("DEPRECATION")
    private fun captureFullShotOnMain(): String? {
        return try {
            val wv = webViewProvider?.invoke() ?: return null
            val pic = try { wv.capturePicture() } catch (_: Exception) { return null }
            val pw = pic.width; val ph = pic.height
            if (pw <= 0 || ph <= 0) return null
            var scale = (1280f / pw).coerceAtMost(1f)
            val est = pw.toDouble() * ph * scale * scale
            if (est > 6_000_000.0) scale = Math.sqrt(6_000_000.0 / (pw.toDouble() * ph)).toFloat()
            val bw = (pw * scale).toInt().coerceAtLeast(1)
            val bh = (ph * scale).toInt().coerceAtLeast(1)
            val bmp = android.graphics.Bitmap.createBitmap(bw, bh, android.graphics.Bitmap.Config.ARGB_8888)
            val c = android.graphics.Canvas(bmp)
            c.scale(scale, scale)
            pic.draw(c)
            val dir = java.io.File(wv.context.filesDir, "sandbox/shots").apply { mkdirs() }
            pruneDir(dir, 30)
            val out = java.io.File(dir, "full_${System.currentTimeMillis()}.png")
            java.io.FileOutputStream(out).use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 90, it) }
            try { bmp.recycle() } catch (_: Exception) {}
            out.absolutePath
        } catch (e: Exception) {
            Log.w(TAG, "shotfull", e)
            null
        }
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

    fun stopLoad() {
        runOnPage { try { it.stopLoading() } catch (_: Exception) {} }
    }

    fun findInPage(query: String) {
        runOnPage {
            try {
                if (query.isBlank()) { try { it.clearMatches() } catch (_: Exception) {} }
                else it.findAllAsync(query)
            } catch (_: Exception) {}
        }
    }

    fun findNext(forward: Boolean) {
        runOnPage { try { it.findNext(forward) } catch (_: Exception) {} }
    }

    /** CSS px → view px for synthetic touches. Deprecated WebView.getScale()
     *  returns zoom-only 1.0 on modern Chromium, which misplaced every tap
     *  by the density factor ("tap missed - page unchanged"). Density is
     *  exact at default zoom; z>=d implies an old density-included scale. */
    private fun cssScale(wv: WebView): Float {
        return try {
            val d = wv.resources.displayMetrics.density.coerceAtLeast(1f)
            val z = try { wv.scale } catch (_: Exception) { 1f }
            if (z >= d) z.coerceIn(1f, 6f) else (d * z.coerceAtLeast(1f)).coerceIn(1f, 6f)
        } catch (_: Exception) { 1f }
    }

    /** Real tap at CSS-pixel coords (from `locate`): full touch pipeline, trusted by pages. */
    fun tapAt(xCss: Float, yCss: Float) {
        mainHandler.post {
            try {
                val wv = webViewProvider?.invoke() ?: return@post
                val s = cssScale(wv)
                val x = xCss * s
                val y = yCss * s
                val now = android.os.SystemClock.uptimeMillis()
                val down = android.view.MotionEvent.obtain(now, now, android.view.MotionEvent.ACTION_DOWN, x, y, 0)
                try { wv.dispatchTouchEvent(down) } catch (_: Exception) {} finally {
                    try { down.recycle() } catch (_: Exception) {}
                }
                wv.postDelayed({
                    try {
                        val up = android.view.MotionEvent.obtain(now, now + 60, android.view.MotionEvent.ACTION_UP, x, y, 0)
                        try { wv.dispatchTouchEvent(up) } catch (_: Exception) {} finally {
                            try { up.recycle() } catch (_: Exception) {}
                        }
                    } catch (_: Exception) {}
                }, 70)
            } catch (_: Exception) {}
        }
    }

    /** Drag from (x1,y1) to (x2,y2) in CSS px over ~ms: scrolls, sliders, drawers. */
    fun swipe(x1Css: Float, y1Css: Float, x2Css: Float, y2Css: Float, ms: Long = 300) {
        mainHandler.post {
            try {
                val wv = webViewProvider?.invoke() ?: return@post
                val s = cssScale(wv)
                val x1 = x1Css * s; val y1 = y1Css * s; val x2 = x2Css * s; val y2 = y2Css * s
                val steps = 8
                val t0 = android.os.SystemClock.uptimeMillis()
                try {
                    val down = android.view.MotionEvent.obtain(t0, t0, android.view.MotionEvent.ACTION_DOWN, x1, y1, 0)
                    try { wv.dispatchTouchEvent(down) } catch (_: Exception) {} finally {
                        try { down.recycle() } catch (_: Exception) {}
                    }
                } catch (_: Exception) {}
                for (i in 1..steps) {
                    val f = i.toFloat() / steps
                    val t = t0 + (ms * f).toLong()
                    val idx = i
                    wv.postDelayed({
                        try {
                            val mv = android.view.MotionEvent.obtain(t0, t, android.view.MotionEvent.ACTION_MOVE, x1 + (x2 - x1) * (idx.toFloat() / steps), y1 + (y2 - y1) * (idx.toFloat() / steps), 0)
                            try { wv.dispatchTouchEvent(mv) } catch (_: Exception) {} finally {
                                try { mv.recycle() } catch (_: Exception) {}
                            }
                        } catch (_: Exception) {}
                    }, (ms * f).toLong())
                }
                wv.postDelayed({
                    try {
                        val t = android.os.SystemClock.uptimeMillis()
                        val up = android.view.MotionEvent.obtain(t0, t, android.view.MotionEvent.ACTION_UP, x2, y2, 0)
                        try { wv.dispatchTouchEvent(up) } catch (_: Exception) {} finally {
                            try { up.recycle() } catch (_: Exception) {}
                        }
                    } catch (_: Exception) {}
                }, ms + 30)
            } catch (_: Exception) {}
        }
    }

    /** Locate a ref/selector → center coords JSON (or ERR). Worker-safe. */
    fun locateBlocking(sel: String, timeoutS: Long = 12): String {
        val esc = sel.replace("\\", "\\\\").replace("'", "\\'").take(500)
        return evalBlockingJs("(function(){try{return JSON.stringify(window.LightAgent?window.LightAgent.locate('$esc'):'ERR no-shim');}catch(e){return 'ERR '+e;}})()", timeoutS)
    }

    fun currentUrl(): String? = try {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            webViewProvider?.invoke()?.url
        } else {
            val f = CompletableFuture<String?>()
            mainHandler.post {
                try { f.complete(webViewProvider?.invoke()?.url) } catch (_: Exception) { f.complete(null) }
            }
            try { f.get(2, TimeUnit.SECONDS) } catch (_: Exception) {
                // Timeout: Main is wedged — never touch the WebView off-Main as fallback.
                null
            }
        }
    } catch (_: Exception) { null }

    /** Suspend eval with timeout + size cap. Returns raw JSON-ish string. Main-safe. */
    suspend fun eval(expr: String, timeoutMs: Long = 12_000, maxChars: Int = 60_000): String {
        return try {
            // Fast path: already on Main — call evaluateJavascript directly.
            if (Looper.myLooper() == Looper.getMainLooper()) {
                val future = CompletableFuture<String>()
                try {
                    val wv = webViewProvider?.invoke()
                    if (wv == null) future.complete("ERR no-webview")
                    else wv.evaluateJavascript(expr) { raw ->
                        try { if (!future.isDone) future.complete(raw ?: "null") } catch (_: Exception) {}
                    }
                } catch (e: Exception) {
                    try { if (!future.isDone) future.complete("ERR ${e.message}") } catch (_: Exception) {}
                }
                var out = try { future.get(timeoutMs, TimeUnit.MILLISECONDS) ?: "null" } catch (_: Exception) { "ERR timeout" }
                if (out.length > maxChars) out = out.take(maxChars) + "…[truncated]"
                return out
            }
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
            locate:function(t){var s=refs[t]||t;var e=null;try{e=document.querySelector(s);}catch(err){return 'ERR bad-sel';}if(!e)return 'ERR no-node';try{var b=e.getBoundingClientRect();var cx=Math.round((b.left+b.right)/2),cy=Math.round((b.top+b.bottom)/2);return JSON.stringify({x:cx,y:cy,w:Math.round(b.width),h:Math.round(b.height),left:Math.round(b.left),top:Math.round(b.top),scrollX:Math.round(window.scrollX),scrollY:Math.round(window.scrollY)});}catch(err){return 'ERR '+err;}},
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
        // currentUrl() hops to Main itself (direct .url here = StrictMode violation from Terminal thread).
        recStartUrl = try { currentUrl() ?: "" } catch (_: Exception) { "" }
        recStartMs = System.currentTimeMillis()
        _recording.value = true
        // Ensure shim exists first (JS-off / pre-finish would otherwise silently record nothing).
        mainHandler.post {
            try {
                val wv = webViewProvider?.invoke() ?: return@post
                ensureShim(wv)
                wv.postDelayed({
                    try {
                        wv.evaluateJavascript(
                            "(function(){try{if(window.LightAgent)window.LightAgent.record(true);}catch(e){}})()", null
                        )
                    } catch (_: Exception) {}
                }, 300)
            } catch (_: Exception) {}
        }
    }

    fun stopRecording() {
        _recording.value = false
        mainHandler.post {
            try {
                webViewProvider?.invoke()?.evaluateJavascript(
                    "(function(){try{if(window.LightAgent)window.LightAgent.record(false);}catch(e){}})()", null
                )
            } catch (_: Exception) {}
        }
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
        // Shape/rate guard: page could forge __LB_REC__ console lines.
        if (json.length > 4_000) return
        try {
            val o = JSONObject(json)
            val op = o.optString("op", "")
            if (op != "click" && op != "fill") return
            val sel = o.optString("selector", "")
            if (sel.isBlank() || sel.length > 500) return
            o.put("t", System.currentTimeMillis() - recStartMs)
            // Per-action URL so SPA replay works (was only startUrl before).
            try { o.put("url", try { currentUrl() } catch (_: Exception) { null } ?: recStartUrl) } catch (_: Exception) { o.put("url", recStartUrl) }
            recEvents.add(o)
            // Cap memory: keep last 500 actions.
            while (recEvents.size > 500) recEvents.removeAt(0)
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
            pruneDir(dir, 30)
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

    /** Blocking JS eval for worker threads: posts to Main, waits on WORKER. Shared by handle() + locateBlocking. */
    private fun evalBlockingJs(js: String, timeoutS: Long = 12): String {
        val f = CompletableFuture<String>()
        mainHandler.post {
            try {
                val wv = webViewProvider?.invoke()
                if (wv == null) { f.complete("ERR no-webview"); return@post }
                try { ensureShim(wv) } catch (_: Exception) {}
                wv.evaluateJavascript(js) { raw ->
                    try { if (!f.isDone) f.complete(raw ?: "null") } catch (_: Exception) {}
                }
            } catch (e: Exception) {
                try { if (!f.isDone) f.complete("ERR ${e.message}") } catch (_: Exception) {}
            }
        }
        return try { f.get(timeoutS, TimeUnit.SECONDS) ?: "ERR timeout" } catch (_: Exception) { "ERR timeout" }
    }

    @Synchronized
    fun startServer(): String {
        stopServer()
        token = UUID.randomUUID().toString().take(24)
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
            writeTokenFile()
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
        token = ""
        clearTokenFile()
    }

    /** Token file lets shell CLIs (`b` shim, curl) auth without pasting. */
    private fun tokenFile(): java.io.File? {
        return try {
            java.io.File(AppCtx.ctx.filesDir, "sandbox/.agent_token")
        } catch (_: Exception) { null }
    }

    private fun writeTokenFile() {
        try {
            tokenFile()?.writeText("$PORT $token")
        } catch (_: Exception) {}
    }

    private fun clearTokenFile() {
        try { tokenFile()?.delete() } catch (_: Exception) {}
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
            // All endpoints (incl /status) require token — URL itself is private.
            if (q["token"] != token) {
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

    /** Runs on the HTTP worker thread; WebView ops hop to Main and block-await.
     *  IMPORTANT: op() already runs ON Main — it must do WebView work DIRECTLY,
     *  never future.get() on Main (that deadlocks: Main waiting on Main). */
    private fun handle(path: String, q: Map<String, String>): String {
        // Blocking JS eval helper for worker threads: posts to Main, waits on WORKER.
        fun evalBlocking(js: String, timeoutS: Long = 12): String = evalBlockingJs(js, timeoutS)
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
            "/forward" -> {
                runOnPage { try { if (it.canGoForward()) it.goForward() } catch (_: Exception) {} }
                """{"ok":true}"""
            }
            "/stop" -> {
                runOnPage { try { it.stopLoading() } catch (_: Exception) {} }
                """{"ok":true}"""
            }
            "/find" -> {
                val q = q["q"] ?: ""
                runOnPage {
                    try {
                        if (q.isBlank()) { try { it.clearMatches() } catch (_: Exception) {} }
                        else it.findAllAsync(q)
                    } catch (_: Exception) {}
                }
                """{"ok":true}"""
            }
            "/pos" -> {
                val sel = com.lightbrowser.ui.terminal.BStore.resolve(q["sel"] ?: return """{"ok":false,"err":"missing sel"}""")
                val raw = locateBlocking(sel, 12).take(4_000)
                JSONObject().put("ok", !raw.startsWith("ERR")).put("rect", raw).toString()
            }
            "/tap" -> {
                val x = q["x"]?.toFloatOrNull()
                val y = q["y"]?.toFloatOrNull()
                if (x == null || y == null) return """{"ok":false,"err":"missing x/y"}"""
                tapAt(x, y)
                """{"ok":true}"""
            }
            "/swipe" -> {
                val x1 = q["x1"]?.toFloatOrNull()
                val y1 = q["y1"]?.toFloatOrNull()
                val x2 = q["x2"]?.toFloatOrNull()
                val y2 = q["y2"]?.toFloatOrNull()
                if (x1 == null || y1 == null || x2 == null || y2 == null) return """{"ok":false,"err":"missing x1/y1/x2/y2"}"""
                swipe(x1, y1, x2, y2, q["ms"]?.toLongOrNull()?.coerceIn(50, 2000) ?: 300)
                """{"ok":true}"""
            }
            "/scrollto" -> {
                val x = q["x"]?.toIntOrNull() ?: 0
                val y = q["y"]?.toIntOrNull() ?: 0
                val raw = evalBlockingJs("(function(){try{window.scrollTo($x,$y);return 'OK '+window.scrollX+','+window.scrollY;}catch(e){return 'ERR '+e;}})()", 12)
                JSONObject().put("ok", raw.contains("OK")).put("result", raw).toString()
            }
            "/scroll" -> {
                val y = q["y"]?.toIntOrNull() ?: 500
                val raw = evalBlockingJs("(function(){try{window.scrollBy(0,$y);return 'OK '+window.scrollX+','+window.scrollY;}catch(e){return 'ERR '+e;}})()", 12)
                JSONObject().put("ok", raw.contains("OK")).put("result", raw).toString()
            }
            "/reload" -> {
                runOnPage { try { it.reload() } catch (_: Exception) {} }
                """{"ok":true}"""
            }
            "/js" -> {
                val expr = q["expr"] ?: return """{"ok":false,"err":"missing expr"}"""
                val raw = evalBlocking(expr, 12).take(60_000)
                JSONObject().put("ok", !raw.startsWith("ERR")).put("result", raw).toString()
            }
            "/text" -> {
                val raw = evalBlocking("(function(){try{return JSON.stringify(window.LightAgent?window.LightAgent.getText(8000):(document.body?document.body.innerText.slice(0,8000):''));}catch(e){return 'ERR '+e;}})()", 12).take(60_000)
                JSONObject().put("ok", !raw.startsWith("ERR")).put("text", raw).toString()
            }
            "/snap" -> evalBlocking("(function(){try{return window.LightAgent?window.LightAgent.snapshot():'ERR no-shim';}catch(e){return 'ERR '+e;}})()", 12)
            "/click" -> {
                val sel = com.lightbrowser.ui.terminal.BStore.resolve(q["sel"] ?: return """{"ok":false,"err":"missing sel"}""")
                val esc = sel.replace("\\", "\\\\").replace("'", "\\'").take(500)
                val raw = evalBlocking("(function(){try{return window.LightAgent?window.LightAgent.click('$esc'):'ERR no-shim';}catch(e){return 'ERR '+e;}})()", 12)
                JSONObject().put("ok", raw.contains("OK")).put("result", raw).toString()
            }
            "/fill" -> {                val sel = com.lightbrowser.ui.terminal.BStore.resolve(q["sel"] ?: return """{"ok":false,"err":"missing sel"}""")
                val value = q["value"] ?: ""
                val e1 = sel.replace("\\", "\\\\").replace("'", "\\'").take(500)
                val e2 = value.replace("\\", "\\\\").replace("'", "\\'").take(2000)
                val raw = evalBlocking("(function(){try{return window.LightAgent?window.LightAgent.fill('$e1','$e2'):'ERR no-shim';}catch(e){return 'ERR '+e;}})()", 12)
                JSONObject().put("ok", raw.contains("OK")).put("result", raw).toString()
            }
            "/console" -> {
                val n = q["n"]?.toIntOrNull() ?: 30
                val arr = org.json.JSONArray()
                consoleTail(n).forEach { arr.put(it) }
                JSONObject().put("ok", true).put("lines", arr).toString()
            }
            "/key" -> {
                // Nearest-button tap: probe coords via JS, then tap (tapAt is
                // Main-safe from this worker thread, same as /tap).
                val sel = com.lightbrowser.ui.terminal.BStore.resolve(q["sel"] ?: "")
                val raw = evalBlocking(keyProbeJs(sel), 12)
                try {
                    var s = raw.trim()
                    repeat(2) {
                        if (s.startsWith("\"") && s.endsWith("\"") && s.length >= 2) {
                            s = try { org.json.JSONObject("{\"v\":$s}").optString("v", s) } catch (_: Exception) { s }
                        }
                    }
                    if (s.startsWith("ERR")) """{"ok":false,"err":"${s.take(200)}"}"""
                    else {
                        val o = org.json.JSONObject(s)
                        val x = o.optDouble("x", -1.0); val y = o.optDouble("y", -1.0)
                        if (x < 0 || y < 0) """{"ok":false,"err":"no button found"}"""
                        else {
                            tapAt(x.toFloat(), y.toFloat())
                            JSONObject().put("ok", true).put("x", x).put("y", y)
                                .put("label", o.optString("label", "")).toString()
                        }
                    }
                } catch (_: Exception) { """{"ok":false,"err":"probe failed: ${raw.take(120)}"}""" }
            }
            "/cookies" -> awaitMain {
                // Already on Main — CookieManager calls are Main-safe.
                val op = q["op"] ?: "get"
                val cm = try { android.webkit.CookieManager.getInstance() } catch (_: Exception) { null }
                if (cm == null) return@awaitMain """{"ok":false,"err":"no CookieManager"}"""
                when (op) {
                    "set" -> {
                        val v = q["value"] ?: return@awaitMain """{"ok":false,"err":"missing value"}"""
                        val url = (q["url"] ?: "").ifBlank { webViewProvider?.invoke()?.url ?: "" }
                        try {
                            cm.setCookie(url, v)
                            try { cm.flush() } catch (_: Exception) {}
                            """{"ok":true}"""
                        } catch (e: Exception) { """{"ok":false,"err":"${e.message}"}""" }
                    }
                    "clear" -> {
                        try { cm.removeAllCookies(null) } catch (_: Exception) {}
                        try { cm.flush() } catch (_: Exception) {}
                        """{"ok":true}"""
                    }
                    else -> {
                        val url = (q["url"] ?: "").ifBlank { webViewProvider?.invoke()?.url ?: "" }
                        val ck = try { cm.getCookie(url) } catch (_: Exception) { null }
                        JSONObject().put("ok", true).put("cookies", ck ?: JSONObject.NULL).toString()
                    }
                }
            }
            "/read" -> {
                val max = q["max"]?.toIntOrNull()?.coerceIn(500, 60_000) ?: 6000
                val raw = evalBlocking(readJs(max), 12).take(max + 4000)
                JSONObject().put("ok", !raw.startsWith("ERR")).put("text", raw).toString()
            }
            "/hover" -> {
                val sel = com.lightbrowser.ui.terminal.BStore.resolve(q["sel"] ?: return """{"ok":false,"err":"missing sel"}""")
                val e1 = sel.replace("\\", "\\\\").replace("'", "\\'").take(500)
                val raw = evalBlocking("(function(){try{var e=document.querySelector('$e1');if(!e)return 'ERR no-node';var r=e.getBoundingClientRect();['mouseover','mouseenter','mousemove'].forEach(function(t){e.dispatchEvent(new MouseEvent(t,{bubbles:true,cancelable:true,clientX:r.left+r.width/2,clientY:r.top+r.height/2}));});try{e.focus();}catch(x){}return 'OK hover '+Math.round(r.left)+','+Math.round(r.top);}catch(e){return 'ERR '+e;}})()", 12)
                JSONObject().put("ok", raw.contains("OK")).put("result", raw).toString()
            }
            "/select" -> {
                val sel = com.lightbrowser.ui.terminal.BStore.resolve(q["sel"] ?: return """{"ok":false,"err":"missing sel"}""")
                val value = q["value"] ?: ""
                val e1 = sel.replace("\\", "\\\\").replace("'", "\\'").take(500)
                val e2 = value.replace("\\", "\\\\").replace("'", "\\'").take(500)
                val raw = evalBlocking("(function(){try{var e=document.querySelector('$e1');if(!e)return 'ERR no-node';if(e.tagName!=='SELECT')return 'ERR not-a-select';var v='$e2';var hit=false;for(var i=0;i<e.options.length;i++){if(e.options[i].value===v||e.options[i].text.trim()===v){e.selectedIndex=i;hit=true;break;}}if(!hit)e.value=v;e.dispatchEvent(new Event('input',{bubbles:true}));e.dispatchEvent(new Event('change',{bubbles:true}));return 'OK selected '+e.selectedIndex;}catch(e){return 'ERR '+e;}})()", 12)
                JSONObject().put("ok", raw.contains("OK")).put("result", raw).toString()
            }
            "/store" -> {
                val S = com.lightbrowser.ui.terminal.BStore
                when (q["op"] ?: "list") {
                    "set" -> {
                        val name = q["name"] ?: return """{"ok":false,"err":"missing name"}"""
                        val sel = q["sel"] ?: return """{"ok":false,"err":"missing sel"}"""
                        if (!name.matches(Regex("[a-zA-Z0-9_-]+"))) return """{"ok":false,"err":"bad name"}"""
                        S.set(name, sel)
                        """{"ok":true}"""
                    }
                    "remove" -> {
                        S.remove(q["name"] ?: "")
                        """{"ok":true}"""
                    }
                    else -> {
                        val o = JSONObject()
                        S.all().forEach { (k, v) -> o.put(k, v) }
                        JSONObject().put("ok", true).put("stores", o).toString()
                    }
                }
            }
            "/shot" -> awaitMain {
                // Already on Main — capture directly. full=1 renders the whole
                // page via capturePicture (capped, may differ from viewport).
                val path = try {
                    if (q["full"] == "1") captureFullShotOnMain() else captureShotOnMain()
                } catch (_: Exception) { null }
                if (path != null) JSONObject().put("ok", true).put("path", path).toString()
                else """{"ok":false,"err":"shot failed"}"""
            }
            "/tabs" -> awaitMain {
                // Already on Main — TabBus reads ViewModel state directly.
                val arr = org.json.JSONArray()
                try {
                    com.lightbrowser.ui.browser.TabBus.listTabs?.invoke()?.forEach { t ->
                        arr.put(JSONObject().put("i", t.index).put("url", t.url)
                            .put("title", t.title).put("current", t.current))
                    }
                } catch (_: Exception) {}
                JSONObject().put("ok", true).put("tabs", arr).toString()
            }
            "/new" -> {
                val url = q["url"] ?: return """{"ok":false,"err":"missing url"}"""
                mainHandler.post {
                    try { com.lightbrowser.ui.browser.TabBus.openInNewTab?.invoke(url) } catch (_: Exception) {}
                }
                """{"ok":true}"""
            }
            "/close" -> {
                val i = q["i"]?.toIntOrNull() ?: -1
                mainHandler.post {
                    try { com.lightbrowser.ui.browser.TabBus.closeTabAt?.invoke(i) } catch (_: Exception) {}
                }
                """{"ok":true}"""
            }
            "/switch" -> {
                val i = q["i"]?.toIntOrNull() ?: return """{"ok":false,"err":"missing i"}"""
                mainHandler.post {
                    try { com.lightbrowser.ui.browser.TabBus.selectTab?.invoke(i) } catch (_: Exception) {}
                }
                """{"ok":true}"""
            }
            "/submit" -> {
                val sel = com.lightbrowser.ui.terminal.BStore.resolve(q["sel"] ?: return """{"ok":false,"err":"missing sel"}""")
                val e1 = sel.replace("\\", "\\\\").replace("'", "\\'").take(500)
                val raw = evalBlocking("(function(){try{var e=document.querySelector('$e1');var f=e?(e.form||e.closest('form')||(e.tagName==='FORM'?e:null)):null;if(!f)return 'ERR no-form';f.submit();return 'OK submitted';}catch(e){return 'ERR '+e;}})()", 12)
                JSONObject().put("ok", raw.contains("OK")).put("result", raw).toString()
            }
            "/history" -> {
                val n = q["n"]?.toIntOrNull()?.coerceIn(1, 100) ?: 20
                try {
                    val ctx = webViewProvider?.invoke()?.context
                    val arr = org.json.JSONArray()
                    if (ctx != null) {
                        com.lightbrowser.data.HistoryStorage.all(ctx).takeLast(n).reversed().forEach { h ->
                            arr.put(JSONObject().put("url", h.url).put("title", h.title).put("time", h.time))
                        }
                    }
                    JSONObject().put("ok", true).put("history", arr).toString()
                } catch (e: Exception) { """{"ok":false,"err":"${e.message}"}""" }
            }
            "/downloads" -> {
                try {
                    val ctx = webViewProvider?.invoke()?.context
                        ?: return """{"ok":false,"err":"no webview"}"""
                    val dir = java.io.File(ctx.filesDir, "sandbox/Downloads")
                    val arr = org.json.JSONArray()
                    dir.listFiles()?.sortedByDescending { it.lastModified() }?.take(30)?.forEach { f ->
                        arr.put(JSONObject().put("name", f.name).put("size", f.length()).put("mtime", f.lastModified()))
                    }
                    JSONObject().put("ok", true).put("files", arr).toString()
                } catch (e: Exception) { """{"ok":false,"err":"${e.message}"}""" }
            }
            "/home" -> {
                mainHandler.post {
                    try { com.lightbrowser.ui.browser.TabBus.openHome?.invoke() } catch (_: Exception) {}
                }
                """{"ok":true}"""
            }
            else -> """{"ok":false,"err":"unknown path. try /status /open /new /tabs /switch /close /home /text /read /snap /js /click /fill /submit /key /hover /select /store /pos /tap /swipe /scroll /scrollto /back /forward /reload /stop /find /console /cookies /shot /history /downloads"}"""
        }
    }
}
