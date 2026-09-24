package com.rg.webloom.data

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
        // Worker path: async PixelCopy on Main, block the WORKER on the latch.
        if (Looper.myLooper() == Looper.getMainLooper()) {
            // Main path: synchronous draw fallback ONLY. Blocking Main on the
            // PixelCopy latch deadlocks it (the callback needs Main) — that
            // was the record-start ANR (freeze → wait/close → slow fallback).
            return try { captureShotOnMain() } catch (e: Exception) {
                Log.w(TAG, "shot", e)
                null
            }
        }
        val f = CompletableFuture<String?>()
        mainHandler.post {
            try {
                captureViewportOnMain { p ->
                    try { f.complete(p) } catch (_: Exception) {}
                }
            } catch (e: Exception) {
                Log.w(TAG, "shot", e)
                try { f.complete(null) } catch (_: Exception) {}
            }
        }
        return try {
            f.get(15, TimeUnit.SECONDS)
        } catch (_: Exception) { null }
    }

    /**
     * Viewport-only capture, ALWAYS async, runs ON Main, never blocks it.
     * PixelCopy from the window when the WebView is actually on-screen —
     * draw() can't copy hardware-rendered surfaces (blank shots). draw()
     * stays as the synchronous fallback for parked tabs / pre-26 / failure.
     */
    private fun captureViewportOnMain(cb: (String?) -> Unit) {
        fun done(p: String?) { try { cb(p) } catch (_: Exception) {} }
        fun restoreOverlay() { try { _shotHideOverlay.value = false } catch (_: Exception) {} }
        try {
            val wv = webViewProvider?.invoke() ?: return done(null)
            if (wv.width <= 0 || wv.height <= 0) return done(null)
            val loc = IntArray(2)
            try { wv.getLocationInWindow(loc) } catch (_: Exception) { return done(captureShotOnMain()) }
            // Parked offscreen tabs (our offscreen() modifier) sit at
            // -100000: PixelCopy would grab the wrong pixels — fall back.
            if (loc[0] < 0 || loc[1] < 0) return done(captureShotOnMain())
            if (android.os.Build.VERSION.SDK_INT < 26) return done(captureShotOnMain())
            val act = try {
                var c: android.content.Context? = wv.context
                while (c is android.content.ContextWrapper && c !is android.app.Activity) c = c.baseContext
                c as? android.app.Activity
            } catch (_: Exception) { null } ?: return done(captureShotOnMain())
            val win = try { act.window } catch (_: Exception) { null } ?: return done(captureShotOnMain())
            val rect = android.graphics.Rect(loc[0], loc[1], loc[0] + wv.width, loc[1] + wv.height)
            val bmp = android.graphics.Bitmap.createBitmap(wv.width, wv.height, android.graphics.Bitmap.Config.ARGB_8888)
            // PixelCopy grabs the WINDOW (Compose overlays included) — hide the
            // recording pill first, wait a frame, restore in the callback.
            // draw()/capturePicture fallbacks render the WebView only: unaffected.
            try { _shotHideOverlay.value = true } catch (_: Exception) {}
            try {
                mainHandler.postDelayed({
                    try {
                        android.view.PixelCopy.request(win, rect, bmp, { res ->
                            try {
                                if (res == android.view.PixelCopy.SUCCESS) {
                                    val dir = java.io.File(wv.context.filesDir, "sandbox/shots").apply { mkdirs() }
                                    pruneDir(dir, 30)
                                    val out = java.io.File(dir, "shot_${System.currentTimeMillis()}.png")
                                    java.io.FileOutputStream(out).use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 90, it) }
                                    try { bmp.recycle() } catch (_: Exception) {}
                                    restoreOverlay()
                                    done(out.absolutePath)
                                } else {
                                    try { bmp.recycle() } catch (_: Exception) {}
                                    restoreOverlay()
                                    done(captureShotOnMain())
                                }
                            } catch (_: Exception) {
                                try { bmp.recycle() } catch (_: Exception) {}
                                restoreOverlay()
                                done(null)
                            }
                        }, mainHandler)
                    } catch (_: Exception) {
                        try { bmp.recycle() } catch (_: Exception) {}
                        restoreOverlay()
                        done(captureShotOnMain())
                    }
                }, 120)
            } catch (_: Exception) {
                try { bmp.recycle() } catch (_: Exception) {}
                restoreOverlay()
                done(captureShotOnMain())
            }
        } catch (e: Exception) {
            Log.w(TAG, "shot", e)
            done(null)
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
     *  Height-capped (~4MP RGB) so endless pages can't OOM. Main-safe. */
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
            if (est > 4_000_000.0) scale = Math.sqrt(4_000_000.0 / (pw.toDouble() * ph)).toFloat()
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
        // User blocklist (b block): refuse before touching the WebView.
        if (com.rg.webloom.ui.terminal.BBlock.blocksUrl(safe)) return
        mainHandler.post {
            try {
                webViewProvider?.invoke()?.loadUrl(safe)
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

    private fun viewScale(wv: WebView, jsZoom: Float, jsDpr: Float): Float {
        return try {
            val d = wv.resources.displayMetrics.density.coerceAtLeast(1f)
            val z = try { wv.scale } catch (_: Exception) { 1f }
            val base = if (z >= d) z.coerceIn(1f, 6f) else (d * z.coerceAtLeast(1f)).coerceIn(1f, 6f)
            val vv = if (jsZoom.isFinite() && jsZoom > 0.2f && jsZoom < 6f) jsZoom else 1f
            // Cross-check with DPR when both are sane: dpr ≈ density × pageZoom.
            val fromDpr = if (jsDpr.isFinite() && jsDpr > 0.2f && jsDpr < 6f) {
                (jsDpr / d).coerceIn(0.5f, 6f)
            } else 1f
            val zoom = when {
                vv != 1f && fromDpr != 1f -> ((vv + fromDpr) / 2f).coerceIn(0.25f, 6f)
                vv != 1f -> vv.coerceIn(0.25f, 6f)
                fromDpr != 1f && base <= d * 1.05f -> (d * fromDpr).coerceIn(1f, 6f)
                else -> base
            }
            if (vv != 1f || (fromDpr != 1f && base <= d * 1.05f)) zoom.coerceIn(0.25f, 6f)
            else base
        } catch (_: Exception) { 1f }
    }

    /** Coordinate contract (single source of truth):
     *  EVERYTHING is CSS px — `b pos`/`b box`, `b tap`/`b swipe`, `/tap`/`/swipe`,
     *  recorder payloads. CSS→view scaling lives ONLY in [deliverTouch].
     *  `lastTap` logs both spaces plus `delivered` so `b metrics` can verify. */
    /** Last synthetic-touch audit trail (for `b metrics` accuracy tests). */
    @Volatile
    var lastTap: String? = null
        private set

    data class TouchResult(val delivered: Boolean, val reason: String)

    private data class TouchOp(
        val wv: WebView,
        val kind: String, // "tap" | "swipe" | "stroke" (polyline gesture)
        val css: List<Pair<Float, Float>>, // tap: 1 pt; swipe: start→end; stroke: full trail
        val ms: Long,
        val jsZoom: Float,
        val jsDpr: Float,
        val future: CompletableFuture<TouchResult>? = null
    )

    private val touchQueue = ArrayDeque<TouchOp>()
    private var touchBusy = false
    private val touchRand = java.util.Random()

    /** FIFO gestures with an inter-gesture gap: overlapping DOWN/UP streams
     *  made Chromium cancel every stream under rapid repeats (the silent-drop
     *  bug). Queueing + 150ms settle keeps each gesture atomic. */
    private fun enqueueTouch(op: TouchOp) {
        mainHandler.post {
            touchQueue.add(op)
            pumpTouch()
        }
    }

    private fun pumpTouch() {
        if (touchBusy) return
        val op = touchQueue.removeFirstOrNull() ?: return
        touchBusy = true
        try {
            val res = deliverTouch(op)
            try { op.future?.complete(res) } catch (_: Exception) {}
            val settle = when (op.kind) {
                "swipe", "stroke" -> op.ms + 180L
                else -> 150L
            }
            mainHandler.postDelayed({ touchBusy = false; pumpTouch() }, settle)
        } catch (_: Exception) {
            try { op.future?.complete(TouchResult(false, "pump-error")) } catch (_: Exception) {}
            touchBusy = false
            pumpTouch()
        }
    }

    private fun humanMs(base: Long, spread: Long): Long =
        (base + (touchRand.nextFloat() - 0.5f) * 2 * spread).toLong().coerceAtLeast(40)

    /** Humanized native touch on Main. Instance-pinned: delivers to the exact
     *  WebView captured at enqueue time (no provider re-resolve mid-stream).
     *  FINGER tool type + touchscreen source (Chromium drops UNKNOWN-tool
     *  streams after accepting them — the delivered:true/zero-events ghost);
     *  pressure/size/duration/±1px jitter defeat exact-coordinate bot checks;
     *  pure DOWN→UP (no spurious MOVE) lets Chromium synthesize click.
     *  A backgrounded (parked/paused) target gets onResume() first so its
     *  renderer actually runs input; parked is logged for forensics. */
    private fun deliverTouch(op: TouchOp): TouchResult {
        val wv = op.wv
        try {
            if (wv.parent == null) {
                logTouch(op, 0f, 0f, 0f, 0f, 0f, false, "detached", 0L, parked = false)
                return TouchResult(false, "detached")
            }
            // Wake a parked/paused page: its renderer acks input without
            // running it otherwise. Idempotent when already resumed.
            try { wv.onResume() } catch (_: Exception) {}
            val parked = try {
                val loc = IntArray(2)
                wv.getLocationInWindow(loc)
                loc[0] < -1000 || loc[1] < -1000
            } catch (_: Exception) { false }
            val dm = wv.resources.displayMetrics
            val d = dm.density.coerceAtLeast(1f)
            val s = viewScale(wv, op.jsZoom, op.jsDpr)
            val t0 = android.os.SystemClock.uptimeMillis()
            val pressure = 0.85f + touchRand.nextFloat() * 0.15f
            val size = 0.9f + touchRand.nextFloat() * 0.2f
            val props = arrayOf(android.view.MotionEvent.PointerProperties().apply {
                id = 0; toolType = android.view.MotionEvent.TOOL_TYPE_FINGER
            })
            fun evAt(downT: Long, et: Long, action: Int, x: Float, y: Float) =
                android.view.MotionEvent.obtain(downT, et, action, 1, props,
                    arrayOf(android.view.MotionEvent.PointerCoords().apply {
                        this.x = x; this.y = y; this.pressure = pressure; this.size = size
                    }), 0, 0, 1f, 1f, 0, 0, android.view.InputDevice.SOURCE_TOUCHSCREEN, 0)
            fun jx(css: Float) = (css + (touchRand.nextFloat() - 0.5f) * 2f) * s
            fun jy(css: Float) = (css + (touchRand.nextFloat() - 0.5f) * 2f) * s
            if (op.kind == "tap") {
                val (cx, cy) = op.css[0]
                val x = jx(cx); val y = jy(cy)
                val dur = humanMs(95, 25) // 70..120ms finger dwell
                val down = evAt(t0, t0, android.view.MotionEvent.ACTION_DOWN, x, y)
                val dDown = try { wv.dispatchTouchEvent(down) } catch (_: Exception) { false } finally {
                    try { down.recycle() } catch (_: Exception) {}
                }
                val upAt = t0 + dur
                mainHandler.postDelayed({
                    try {
                        val up = evAt(t0, upAt, android.view.MotionEvent.ACTION_UP, x, y)
                        try { wv.dispatchTouchEvent(up) } catch (_: Exception) {} finally {
                            try { up.recycle() } catch (_: Exception) {}
                        }
                    } catch (_: Exception) {}
                }, dur)
                logTouch(op, x, y, x, y, s, dDown, if (dDown) "ok" else "rejected", t0, dur, parked)
                return TouchResult(dDown, if (dDown) "ok" else "rejected")
            } else if (op.kind == "stroke") {
                // Freeform gesture: DOWN at p0, eased MOVEs along the polyline
                // by arc length (even finger speed — bot checks flag teleporting
                // cursors), UP at the end. Per-point ±1px jitter via jx/jy.
                val trail = op.css.map { jx(it.first) to jy(it.second) }
                val x1 = trail.first().first; val y1 = trail.first().second
                val x2 = trail.last().first; val y2 = trail.last().second
                val down = evAt(t0, t0, android.view.MotionEvent.ACTION_DOWN, x1, y1)
                val dDown = try { wv.dispatchTouchEvent(down) } catch (_: Exception) { false } finally {
                    try { down.recycle() } catch (_: Exception) {}
                }
                if (dDown) {
                    // Arc-length table so speed is uniform across segments.
                    val cum = mutableListOf(0f)
                    for (i in 1 until trail.size) {
                        val dx = trail[i].first - trail[i - 1].first
                        val dy = trail[i].second - trail[i - 1].second
                        cum.add(cum.last() + kotlin.math.sqrt(dx * dx + dy * dy))
                    }
                    val total = cum.last().coerceAtLeast(1f)
                    val steps = ((op.ms / 16).toLong()).coerceIn(8, 64).toInt()
                    var seg = 1
                    for (i in 1..steps) {
                        val target = total * i / steps
                        while (seg < cum.size - 1 && cum[seg] < target) seg++
                        val c0 = cum[seg - 1]; val span = (cum[seg] - c0).coerceAtLeast(0.001f)
                        val f = ((target - c0) / span).coerceIn(0f, 1f)
                        val mx = trail[seg - 1].first + (trail[seg].first - trail[seg - 1].first) * f
                        val my = trail[seg - 1].second + (trail[seg].second - trail[seg - 1].second) * f
                        // Ease in/out on top of uniform speed (human attack/decay).
                        val te = i.toFloat() / steps
                        val e = if (te < 0.5f) 2 * te * te else 1 - ((-2 * te + 2) * (-2 * te + 2)) / 2
                        val et = t0 + (op.ms * e).toLong()
                        val at = (op.ms * e).toLong()
                        mainHandler.postDelayed({
                            try {
                                val mv = evAt(t0, et, android.view.MotionEvent.ACTION_MOVE, mx, my)
                                try { wv.dispatchTouchEvent(mv) } catch (_: Exception) {} finally {
                                    try { mv.recycle() } catch (_: Exception) {}
                                }
                            } catch (_: Exception) {}
                        }, at)
                    }
                    val upEt = t0 + op.ms + 20
                    mainHandler.postDelayed({
                        try {
                            val up = evAt(t0, upEt, android.view.MotionEvent.ACTION_UP, x2, y2)
                            try { wv.dispatchTouchEvent(up) } catch (_: Exception) {} finally {
                                try { up.recycle() } catch (_: Exception) {}
                            }
                        } catch (_: Exception) {}
                    }, op.ms + 20)
                }
                logTouch(op, x1, y1, x2, y2, s, dDown, if (dDown) "ok" else "rejected", t0, op.ms, parked)
                return TouchResult(dDown, if (dDown) "ok" else "rejected")
            } else {
                // Swipe: discrete eased MOVEs (Chromium ignores addBatch history
                // folded into ACTION_DOWN — the old swipe never scrolled).
                val (c1x, c1y) = op.css[0]
                val (c2x, c2y) = op.css[1]
                val x1 = jx(c1x); val y1 = jy(c1y); val x2 = jx(c2x); val y2 = jy(c2y)
                val steps = ((op.ms / 16).toLong()).coerceIn(6, 32).toInt()
                val down = evAt(t0, t0, android.view.MotionEvent.ACTION_DOWN, x1, y1)
                val dDown = try { wv.dispatchTouchEvent(down) } catch (_: Exception) { false } finally {
                    try { down.recycle() } catch (_: Exception) {}
                }
                if (dDown) {
                    for (i in 1..steps) {
                        val t = i.toFloat() / steps
                        val e = if (t < 0.5f) 2 * t * t else 1 - ((-2 * t + 2) * (-2 * t + 2)) / 2
                        val mx = x1 + (x2 - x1) * e; val my = y1 + (y2 - y1) * e
                        val et = t0 + (op.ms * e).toLong()
                        val at = (op.ms * e).toLong()
                        mainHandler.postDelayed({
                            try {
                                val mv = evAt(t0, et, android.view.MotionEvent.ACTION_MOVE, mx, my)
                                try { wv.dispatchTouchEvent(mv) } catch (_: Exception) {} finally {
                                    try { mv.recycle() } catch (_: Exception) {}
                                }
                            } catch (_: Exception) {}
                        }, at)
                    }
                    val upEt = t0 + op.ms + 20
                    mainHandler.postDelayed({
                        try {
                            val up = evAt(t0, upEt, android.view.MotionEvent.ACTION_UP, x2, y2)
                            try { wv.dispatchTouchEvent(up) } catch (_: Exception) {} finally {
                                try { up.recycle() } catch (_: Exception) {}
                            }
                        } catch (_: Exception) {}
                    }, op.ms + 20)
                }
                logTouch(op, x1, y1, x2, y2, s, dDown, if (dDown) "ok" else "rejected", t0, op.ms, parked)
                return TouchResult(dDown, if (dDown) "ok" else "rejected")
            }
        } catch (_: Exception) {
            return TouchResult(false, "deliver-error")
        }
    }

    private fun logTouch(
        op: TouchOp, vx1: Float, vy1: Float, vx2: Float, vy2: Float,
        scale: Float, delivered: Boolean, reason: String, downTime: Long, durMs: Long = 0,
        parked: Boolean = false
    ) {
        try {
            val wv = op.wv
            val o = JSONObject()
                .put("kind", op.kind)
                .put("cssX", op.css[0].first).put("cssY", op.css[0].second)
                .put("viewX", vx1).put("viewY", vy1)
                .put("scale", scale.toDouble())
                .put("density", wv.resources.displayMetrics.density.toDouble())
                .put("viewW", wv.width).put("viewH", wv.height)
                .put("scrollX", try { wv.scrollX } catch (_: Exception) { -1 })
                .put("scrollY", try { wv.scrollY } catch (_: Exception) { -1 })
                .put("progress", try { wv.progress } catch (_: Exception) { -1 })
                .put("jsZoom", op.jsZoom.toDouble()).put("jsDpr", op.jsDpr.toDouble())
                .put("delivered", delivered).put("reason", reason)
                .put("tool", "finger").put("parked", parked)
                .put("downTime", downTime).put("durMs", durMs)
                .put("ts", System.currentTimeMillis())
            if (op.kind == "swipe" && op.css.size > 1) {
                o.put("cssX2", op.css[1].first).put("cssY2", op.css[1].second)
                    .put("viewX2", vx2).put("viewY2", vy2).put("ms", op.ms)
            }
            if (op.kind == "stroke") {
                o.put("n", op.css.size).put("ms", op.ms)
            }
            lastTap = o.toString()
        } catch (_: Exception) {}
    }

    /** Async tap (CSS px): enqueue + return. Safe from any thread. */
    fun tapAt(xCss: Float, yCss: Float, jsZoom: Float = 1f, jsDpr: Float = -1f) {
        val wv = try { webViewProvider?.invoke() } catch (_: Exception) { null }
        if (wv == null) {
            try {
                lastTap = JSONObject().put("kind", "tap").put("cssX", xCss).put("cssY", yCss)
                    .put("delivered", false).put("reason", "no-webview")
                    .put("ts", System.currentTimeMillis()).toString()
            } catch (_: Exception) {}
            return
        }
        enqueueTouch(TouchOp(wv, "tap", listOf(xCss to yCss), 0L, jsZoom, jsDpr, null))
    }

    /** Worker-safe blocking tap (CSS px): returns delivery. Never call on Main. */
    fun tapSync(xCss: Float, yCss: Float, jsZoom: Float = 1f, jsDpr: Float = -1f, timeoutMs: Long = 10_000): TouchResult {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            tapAt(xCss, yCss, jsZoom, jsDpr)
            return TouchResult(false, "main-thread-async")
        }
        val wv = try { webViewProvider?.invoke() } catch (_: Exception) { null }
            ?: return TouchResult(false, "no-webview")
        val f = CompletableFuture<TouchResult>()
        enqueueTouch(TouchOp(wv, "tap", listOf(xCss to yCss), 0L, jsZoom, jsDpr, f))
        return try {
            f.get(timeoutMs, TimeUnit.MILLISECONDS) ?: TouchResult(false, "timeout")
        } catch (_: Exception) {
            TouchResult(false, "timeout")
        } finally {
            try { if (!f.isDone) f.cancel(true) } catch (_: Exception) {}
        }
    }

    // dispatchStroke retired: swipe now schedules discrete eased MOVEs inside
    // deliverTouch (Chromium ignores addBatch history folded into ACTION_DOWN).

    /** Drag from (x1,y1) to (x2,y2) in CSS px over ~ms: scrolls, sliders, drawers.
     *  Async version: enqueue + return. Safe from any thread. */
    fun swipe(x1Css: Float, y1Css: Float, x2Css: Float, y2Css: Float, ms: Long = 300, jsZoom: Float = 1f, jsDpr: Float = -1f) {
        // Touch-slop guard: tiny drags are taps on most pages.
        try {
            val dx = x2Css - x1Css; val dy = y2Css - y1Css
            if (dx * dx + dy * dy < 12f * 12f) {
                tapAt((x1Css + x2Css) / 2f, (y1Css + y2Css) / 2f, jsZoom, jsDpr)
                return
            }
        } catch (_: Exception) {}
        val wv = try { webViewProvider?.invoke() } catch (_: Exception) { null }
        if (wv == null) {
            try {
                lastTap = JSONObject().put("kind", "swipe").put("cssX", x1Css).put("cssY", y1Css)
                    .put("delivered", false).put("reason", "no-webview")
                    .put("ts", System.currentTimeMillis()).toString()
            } catch (_: Exception) {}
            return
        }
        enqueueTouch(TouchOp(wv, "swipe", listOf(x1Css to y1Css, x2Css to y2Css), ms.coerceIn(50, 2000), jsZoom, jsDpr, null))
    }

    /** Worker-safe blocking swipe (CSS px): returns delivery. Never call on Main. */
    fun swipeSync(x1Css: Float, y1Css: Float, x2Css: Float, y2Css: Float, ms: Long = 300, jsZoom: Float = 1f, jsDpr: Float = -1f, timeoutMs: Long = 15_000): TouchResult {
        try {
            val dx = x2Css - x1Css; val dy = y2Css - y1Css
            if (dx * dx + dy * dy < 12f * 12f) return tapSync((x1Css + x2Css) / 2f, (y1Css + y2Css) / 2f, jsZoom, jsDpr, timeoutMs)
        } catch (_: Exception) {}
        if (Looper.myLooper() == Looper.getMainLooper()) {
            swipe(x1Css, y1Css, x2Css, y2Css, ms, jsZoom, jsDpr)
            return TouchResult(false, "main-thread-async")
        }
        val wv = try { webViewProvider?.invoke() } catch (_: Exception) { null }
            ?: return TouchResult(false, "no-webview")
        val f = CompletableFuture<TouchResult>()
        enqueueTouch(TouchOp(wv, "swipe", listOf(x1Css to y1Css, x2Css to y2Css), ms.coerceIn(50, 2000), jsZoom, jsDpr, f))
        return try {
            f.get(timeoutMs, TimeUnit.MILLISECONDS) ?: TouchResult(false, "timeout")
        } catch (_: Exception) {
            TouchResult(false, "timeout")
        } finally {
            try { if (!f.isDone) f.cancel(true) } catch (_: Exception) {}
        }
    }

    /** Freeform human stroke: DOWN at p0, eased MOVEs along the polyline,
     *  UP at the end (circles, doodles, recorded human trails). Async. */
    fun stroke(pts: List<Pair<Float, Float>>, ms: Long = 600, jsZoom: Float = 1f, jsDpr: Float = -1f) {
        val trail = try { pts.take(GestureGen.MAX_PTS) } catch (_: Exception) { pts }
        if (trail.size < 2) return
        val wv = try { webViewProvider?.invoke() } catch (_: Exception) { null }
        if (wv == null) {
            try {
                lastTap = JSONObject().put("kind", "stroke").put("cssX", trail[0].first).put("cssY", trail[0].second)
                    .put("delivered", false).put("reason", "no-webview")
                    .put("ts", System.currentTimeMillis()).toString()
            } catch (_: Exception) {}
            return
        }
        enqueueTouch(TouchOp(wv, "stroke", trail, ms.coerceIn(100, 5000), jsZoom, jsDpr, null))
    }

    /** Worker-safe blocking stroke (CSS px polyline): returns delivery. Never call on Main. */
    fun strokeSync(pts: List<Pair<Float, Float>>, ms: Long = 600, jsZoom: Float = 1f, jsDpr: Float = -1f, timeoutMs: Long = 15_000): TouchResult {
        val trail = try { pts.take(GestureGen.MAX_PTS) } catch (_: Exception) { pts }
        if (trail.size < 2) return TouchResult(false, "need-2-pts")
        if (Looper.myLooper() == Looper.getMainLooper()) {
            stroke(trail, ms, jsZoom, jsDpr)
            return TouchResult(false, "main-thread-async")
        }
        val wv = try { webViewProvider?.invoke() } catch (_: Exception) { null }
            ?: return TouchResult(false, "no-webview")
        val f = CompletableFuture<TouchResult>()
        enqueueTouch(TouchOp(wv, "stroke", trail, ms.coerceIn(100, 5000), jsZoom, jsDpr, f))
        return try {
            f.get(timeoutMs, TimeUnit.MILLISECONDS) ?: TouchResult(false, "timeout")
        } catch (_: Exception) {
            TouchResult(false, "timeout")
        } finally {
            try { if (!f.isDone) f.cancel(true) } catch (_: Exception) {}
        }
    }

    /** Locate a ref/selector → center coords JSON (or ERR). Worker-safe. */
    fun locateBlocking(sel: String, timeoutS: Long = 12): String {
        val esc = sel.replace("\\", "\\\\").replace("'", "\\'").take(500)
        return evalBlockingJs("(function(){try{return JSON.stringify(window.LightAgent?window.LightAgent.locate('$esc'):'ERR no-shim');}catch(e){return 'ERR '+e;}})()", timeoutS)
    }

    /**
     * WebView on-screen box for tap-accuracy math (view x/y are
     * WebView-relative — use screenY = viewY + boxY for physical pixels).
     * Worker threads ONLY (posts to Main and waits; calling on Main deadlocks).
     */
    fun webViewBoxBlocking(timeoutS: Long = 8): String {
        if (Looper.myLooper() == Looper.getMainLooper()) return "ERR main-thread"
        val f = CompletableFuture<String>()
        mainHandler.post {
            try {
                val wv = webViewProvider?.invoke()
                if (wv == null || wv.width <= 0 || wv.height <= 0) {
                    f.complete("ERR no-webview")
                    return@post
                }
                val loc = IntArray(2)
                // Same origin as the screenshot path (getLocationInWindow):
                // getLocationOnScreen includes status-bar height, which made
                // metrics viewY disagree with shot crops by exactly that offset.
                try { wv.getLocationInWindow(loc) } catch (_: Exception) {
                    f.complete("ERR no-loc")
                    return@post
                }
                val dm = try { wv.resources.displayMetrics } catch (_: Exception) { null }
                f.complete(
                    JSONObject().put("x", loc[0]).put("y", loc[1])
                        .put("w", wv.width).put("h", wv.height)
                        .put("scrW", dm?.widthPixels ?: -1).put("scrH", dm?.heightPixels ?: -1)
                        .put("density", ((dm?.density ?: -1f).toDouble())).toString()
                )
            } catch (e: Exception) {
                try { f.complete("ERR ${e.message}") } catch (_: Exception) {}
            }
        }
        return try { f.get(timeoutS, TimeUnit.SECONDS) } catch (_: Exception) { "ERR timeout" } finally {
            try { if (!f.isDone) f.cancel(true) } catch (_: Exception) {}
        }
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
            } finally {
                try { if (!f.isDone) f.cancel(true) } catch (_: Exception) {}
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
                var out = try { future.get(timeoutMs, TimeUnit.MILLISECONDS) ?: "null" } catch (_: Exception) { "ERR timeout" } finally {
                    try { if (!future.isDone) future.cancel(true) } catch (_: Exception) {}
                }
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
            } finally {
                try { if (!future.isDone) future.cancel(true) } catch (_: Exception) {}
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

    /** Blocking page text for worker threads (/wait polls, no coroutine scope). */
    fun pageTextBlocking(max: Int = 8000): String {
        return try {
            kotlinx.coroutines.runBlocking(kotlinx.coroutines.Dispatchers.IO) { pageText(max) }
        } catch (_: Exception) { "ERR" }
    }

    // ── Agent file bridge (b upload / js-file / fill-file) ──

    /** Sandbox-jailed resolver for agent file args: ~/…, sandbox/…, relative, or absolute. */
    fun sandboxFile(path: String): java.io.File? {
        return try {
            var p = path.trim()
            if (p.isEmpty()) return null
            val sb = java.io.File(AppCtx.ctx.filesDir, "sandbox")
            if (p == "~" || p == "~/") return sb.takeIf { it.isDirectory }
            if (p.startsWith("~/")) p = p.substring(2)
            else if (p.startsWith("sandbox/")) p = p.removePrefix("sandbox/")
            val f = if (p.startsWith("/")) java.io.File(p) else java.io.File(sb, p)
            val root = sb.canonicalFile.absolutePath.trimEnd('/') + '/'
            val c = f.canonicalFile.absolutePath
            if (c == root.trimEnd('/') || c.startsWith(root)) f.takeIf { it.isFile } else null
        } catch (_: Exception) { null }
    }

    private fun mimeFor(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "svg" -> "image/svg+xml"
        "mp4" -> "video/mp4"
        "webm" -> "video/webm"
        "mp3" -> "audio/mpeg"
        "wav" -> "audio/wav"
        "ogg", "opus" -> "audio/ogg"
        "pdf" -> "application/pdf"
        "txt", "md", "log" -> "text/plain"
        "html", "htm" -> "text/html"
        "json" -> "application/json"
        "js" -> "text/javascript"
        "zip" -> "application/zip"
        else -> "application/octet-stream"
    }

    /**
     * Set a file input from local bytes via DataTransfer (file inputs reject
     * programmatic `value=` — this is the only JS path). Base64 ships in
     * binder-safe chunks (evaluateJavascript IPC caps single calls).
     */
    private suspend fun uploadCore(
        sel: String, data: ByteArray, fileName: String, mime: String,
        runJs: suspend (String) -> String
    ): String {
        val eSel = sel.replace("\\", "\\\\").replace("'", "\\'").take(500)
        val probe = runJs("(function(){try{var e=document.querySelector('$eSel');if(!e)return 'ERR no-node';if(e.tagName!=='INPUT'||(e.type||'').toLowerCase()!=='file')return 'ERR not-a-file-input';return 'OK'+(e.multiple?'+multi':'');}catch(x){return 'ERR '+x;}})()")
        if (!probe.contains("OK")) return probe.take(300)
        val b64 = try {
            android.util.Base64.encodeToString(data, android.util.Base64.NO_WRAP)
        } catch (_: Exception) { return "ERR encode failed" }
        var r = runJs("window.__lb_up='';'OK'")
        if (r.startsWith("ERR")) return r.take(200)
        var i = 0
        while (i < b64.length) {
            val c = b64.substring(i, minOf(i + 200_000, b64.length))
            r = runJs("window.__lb_up+='$c';'OK'")
            if (r.startsWith("ERR")) return "ERR chunk failed @ $i"
            i += 200_000
        }
        val eName = fileName.replace("\\", "\\\\").replace("'", "\\'").take(120)
        val eMime = mime.replace("\\", "\\\\").replace("'", "\\'").take(120)
        return runJs("(function(){try{var e=document.querySelector('$eSel');if(!e)return 'ERR no-node';var bin=atob(window.__lb_up);window.__lb_up='';var arr=new Uint8Array(bin.length);for(var i=0;i<bin.length;i++)arr[i]=bin.charCodeAt(i);var file=new File([new Blob([arr],{type:'$eMime'})],'$eName',{type:'$eMime'});var dt=new DataTransfer();if(e.multiple){for(var j=0;j<e.files.length;j++){try{dt.items.add(e.files[j]);}catch(x){}}}dt.items.add(file);e.files=dt.files;e.dispatchEvent(new Event('input',{bubbles:true}));e.dispatchEvent(new Event('change',{bubbles:true}));return 'OK uploaded '+file.name+' ('+file.size+'b)';}catch(x){try{window.__lb_up='';}catch(y){}return 'ERR '+x;}})()")
    }

    /** EXEC path (already in a coroutine). Max 12 MB (photos OK). */
    suspend fun uploadInput(sel: String, file: java.io.File): String {
        if (!file.isFile) return "ERR not-a-file"
        if (file.length() > 12L * 1024 * 1024) return "ERR file too big (>12MB)"
        val data = try { file.readBytes() } catch (e: Exception) { return "ERR read: ${e.message}" }
        return uploadCore(sel, data, file.name, mimeFor(file.name)) { js ->
            eval(js, timeoutMs = 20_000, maxChars = 2000)
        }
    }

    /** PTY/worker path (never Main — waits like other blocking ops). */
    fun uploadInputBlocking(sel: String, file: java.io.File): String {
        return try {
            kotlinx.coroutines.runBlocking(kotlinx.coroutines.Dispatchers.IO) { uploadInput(sel, file) }
        } catch (_: Exception) { "ERR upload failed" }
    }

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
              function vis(e){if(!e||!e.getBoundingClientRect)return false;try{var cs=getComputedStyle(e);if(cs.display==='none'||cs.visibility==='hidden'||cs.opacity==='0')return false;}catch(x){}var r=e.getBoundingClientRect();return r.width>4&&r.height>4&&r.bottom>0&&r.top<window.innerHeight;}
              var els=Array.prototype.slice.call(document.querySelectorAll('a,button,input,select,textarea,[role=button],[onclick]'));
              els=els.filter(function(e){return vis(e);}).slice(0,200);
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
            locate:function(t){var s=refs[t]||t;var e=null;try{e=document.querySelector(s);}catch(err){return 'ERR bad-sel';}if(!e)return 'ERR no-node';try{var b=e.getBoundingClientRect();var vv=(window.visualViewport||{});var z=vv.scale||1;var ox=vv.offsetLeft||0;var oy=vv.offsetTop||0;var cx=Math.round((b.left+b.right)/2+ox),cy=Math.round((b.top+b.bottom)/2+oy);return JSON.stringify({x:cx,y:cy,w:Math.round(b.width),h:Math.round(b.height),left:Math.round(b.left),top:Math.round(b.top),scrollX:Math.round(window.scrollX),scrollY:Math.round(window.scrollY),z:z,dpr:window.devicePixelRatio||1,vw:window.innerWidth,vh:window.innerHeight});}catch(err){return 'ERR '+err;}},
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
                  window.__lb_recTarget.removeEventListener('touchstart',window.__lb_recTouch,true);
                  try{if(window.__lb_recTouchMove)window.__lb_recTarget.removeEventListener('touchmove',window.__lb_recTouchMove,true);}catch(x){}
                  window.__lb_recTarget.removeEventListener('touchend',window.__lb_recTouchEnd,true);
                  window.__lb_recHandler=null;
                }
                if(!on) return 'OK';
                var h=function(ev){
                  try{
                    // Touch taps already arrive as op=tap below — skip the
                    // duplicate click or replay would double-fire.
                    if(ev.type==='click'&&window.__lb_touchTs&&(Date.now()-window.__lb_touchTs)<600)return;
                    var d=window.LightAgent.describe(ev.target);
                    d.op=(ev.type==='input')?'fill':'click';
                    if(ev.type==='input'){d.value=(ev.target.value||'').slice(0,200);}
                    d.x=Math.round(ev.clientX||0);d.y=Math.round(ev.clientY||0);
                    d.sx=Math.round(window.scrollX);d.sy=Math.round(window.scrollY);
                    d.vw=window.innerWidth;d.vh=window.innerHeight;
                    console.log('__LB_REC__:'+JSON.stringify(d));
                  }catch(err){}
                };
                var tsx=0,tsy=0,tst=0;
                var trail=[];
                var ts=function(ev){
                  try{var t=ev.changedTouches[0];tsx=t.clientX;tsy=t.clientY;tst=Date.now();trail=[Math.round(t.clientX)+','+Math.round(t.clientY)];}catch(x){}
                };
                var tm=function(ev){
                  try{
                    if(!trail||trail.length===0)return;
                    var t=ev.changedTouches[0];
                    var lx=0,ly=0;
                    try{var lp=trail[trail.length-1].split(',');lx=parseFloat(lp[0]);ly=parseFloat(lp[1]);}catch(x){}
                    var dx=t.clientX-lx,dy=t.clientY-ly;
                    if(dx*dx+dy*dy<36)return;
                    trail.push(Math.round(t.clientX)+','+Math.round(t.clientY));
                    if(trail.length>64)trail.shift();
                  }catch(x){}
                };
                var te=function(ev){
                  try{
                    var t=ev.changedTouches[0];
                    var dx=t.clientX-tsx,dy=t.clientY-tsy,dt=Date.now()-tst;
                    var dist=Math.sqrt(dx*dx+dy*dy);
                    window.__lb_touchTs=Date.now();
                    var el=null;
                    try{el=document.elementFromPoint(t.clientX,t.clientY);}catch(x){}
                    var d=window.LightAgent.describe(el||ev.target);
                    d.x=Math.round(t.clientX);d.y=Math.round(t.clientY);
                    d.sx=Math.round(window.scrollX);d.sy=Math.round(window.scrollY);
                    d.vw=window.innerWidth;d.vh=window.innerHeight;
                    // Freeform trail (circle/doodle/human path): ≥3 tracked
                    // points + real travel ⇒ op=gesture with the full path so
                    // the agent can humanize + replay it as random data.
                    try{
                      if(trail&&trail.length>=3&&dist>=24){
                        d.op='gesture';
                        d.path=trail.join(' ');
                        d.n=trail.length;
                        d.ms=Math.min(Math.max(dt,100),5000);
                        console.log('__LB_REC__:'+JSON.stringify(d));
                        trail=[];
                        return;
                      }
                    }catch(x){}
                    trail=[];
                    if(dist<12){d.op='tap';}
                    else{d.op='swipe';d.x1=Math.round(tsx);d.y1=Math.round(tsy);
                      d.x2=Math.round(t.clientX);d.y2=Math.round(t.clientY);
                      d.ms=Math.min(Math.max(dt,50),3000);}
                    console.log('__LB_REC__:'+JSON.stringify(d));
                  }catch(x){try{trail=[];}catch(y){}}
                };
                window.__lb_recHandler=h;
                window.__lb_recTouch=ts;
                window.__lb_recTouchMove=tm;
                window.__lb_recTouchEnd=te;
                window.__lb_recTarget=document;
                document.addEventListener('click',h,true);
                document.addEventListener('input',h,true);
                document.addEventListener('touchstart',ts,true);
                document.addEventListener('touchmove',tm,{passive:true,capture:true});
                document.addEventListener('touchend',te,true);
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
    private val _recPaused = MutableStateFlow(false)
    val recPaused: StateFlow<Boolean> = _recPaused.asStateFlow()
    /** True while a `b shot` PixelCopy is in flight — Compose overlays hide. */
    private val _shotHideOverlay = MutableStateFlow(false)
    val shotHideOverlay: StateFlow<Boolean> = _shotHideOverlay.asStateFlow()
    private val recEvents = mutableListOf<JSONObject>()
    private var recStartUrl = ""
    private var recStartMs = 0L
    @Volatile
    private var recCoverPath: String? = null

    fun isRecording(): Boolean = _recording.value

    fun startRecording() {
        recEvents.clear()
        recCoverPath = null
        _recPaused.value = false
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
                // Cover shot: page context for the rec file. Worker thread —
                // captureShot() blocks its caller on a latch, never Main.
                Thread {
                    try { recCoverPath = captureShot() } catch (_: Exception) {}
                }.also { it.isDaemon = true }.start()
            } catch (_: Exception) {}
        }
    }

    fun stopRecording(): String? {
        _recording.value = false
        _recPaused.value = false
        mainHandler.post {
            try {
                webViewProvider?.invoke()?.evaluateJavascript(
                    "(function(){try{if(window.LightAgent)window.LightAgent.record(false);}catch(e){}})()", null
                )
            } catch (_: Exception) {}
        }
        // Auto-save so Stop always leaves a file (asked). Synchronous: a few
        // hundred small events write in ms. Clears on success for a clean slate.
        return try {
            if (recEvents.isEmpty()) null
            else saveRecording("rec")?.also { recEvents.clear() }
        } catch (_: Exception) { null }
    }

    /** Pause capture (events dropped, shim stays armed); resume reopens the stream. */
    fun pauseRecording() { _recPaused.value = true }

    fun resumeRecording() { _recPaused.value = false }

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
        if (!_recording.value || _recPaused.value) return
        // Shape/rate guard: page could forge __LB_REC__ console lines.
        if (json.length > 4_000) return
        try {
            val o = JSONObject(json)
            // click/fill (mouse+keyboard) + tap/swipe (touch) + gesture
            // (freeform touch trail: circle/doodle/human path) — the replay set.
            val op = o.optString("op", "")
            if (op != "click" && op != "fill" && op != "tap" && op != "swipe" && op != "gesture") return
            val sel = o.optString("selector", "")
            if (sel.isBlank() || sel.length > 500) return
            if (op == "gesture") {
                // Validate the recorded trail now so replay never chokes on
                // forged console lines: needs a parseable path of 2..64 pts.
                val path = o.optString("path", "")
                val pts = try { GestureGen.parsePath(path) } catch (_: Exception) { null }
                if (pts == null || pts.size < 2) return
                o.put("path", GestureGen.formatPath(pts))
                o.put("n", pts.size)
                if (o.optInt("ms", 0) <= 0) o.put("ms", 600)
            }
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
            root.put("v", 3)
            root.put("startUrl", recStartUrl)
            root.put("startedAt", recStartMs)
            try { recCoverPath?.let { root.put("cover", it) } } catch (_: Exception) {}
            // Touch replay set: tap (x,y) + swipe (x1,y1→x2,y2,ms), each with
            // selector/tag/text/rect + scroll/viewport context.
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
        return try { f.get(timeoutS, TimeUnit.SECONDS) ?: "ERR timeout" } catch (_: Exception) { "ERR timeout" } finally {
            try { if (!f.isDone) f.cancel(true) } catch (_: Exception) {}
        }
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
        try { acceptThread?.interrupt() } catch (_: Exception) {}
        acceptThread = null
        _serverRunning.value = false
        _serverLabel.value = ""
        token = ""
        clearTokenFile()
    }

    /** Single serialized metrics.log writer (EXEC + PTY both land here —
     *  the old dual writers raced full-file read/rewrite and lost entries). */
    private val metricsLock = Any()
    fun appendMetrics(rep: JSONObject): String {
        return try {
            val appCtx = try { AppCtx.ctx } catch (_: Exception) { null } ?: return "log failed: no ctx"
            synchronized(metricsLock) {
                val dir = java.io.File(appCtx.filesDir, "sandbox/agent_metrics").apply { mkdirs() }
                val log = java.io.File(dir, "metrics.log")
                log.appendText(rep.toString() + "\n", Charsets.UTF_8)
                val lines = try { log.readLines(Charsets.UTF_8) } catch (_: Exception) { emptyList() }
                if (lines.size > 400) {
                    try { log.writeText(lines.takeLast(300).joinToString("\n") + "\n", Charsets.UTF_8) } catch (_: Exception) {}
                }
                "logged → ~/agent_metrics/metrics.log (${minOf(lines.size, 400)} kept)"
            }
        } catch (e: Exception) { "log failed: ${e.message}" }
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
            BufferedReader(InputStreamReader(sock.getInputStream(), Charsets.UTF_8)).use { reader ->
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
            }
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
                if (com.rg.webloom.ui.terminal.BBlock.blocksUrl(url)) {
                    return """{"ok":false,"err":"blocked by user: ${com.rg.webloom.ui.terminal.BBlock.normalize(url)}"}"""
                }
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
                val sel = com.rg.webloom.ui.terminal.BStore.resolve(q["sel"] ?: return """{"ok":false,"err":"missing sel"}""")
                val raw = locateBlocking(sel, 12).take(4_000)
                JSONObject().put("ok", !raw.startsWith("ERR")).put("rect", raw).toString()
            }
            "/box" -> {
                // Rich geometry for AI variation: center + bounds + safe inset
                // points (CSS px — feed any of them straight to /tap).
                val sel = com.rg.webloom.ui.terminal.BStore.resolve(q["sel"] ?: return """{"ok":false,"err":"missing sel"}""")
                val raw = locateBlocking(sel, 12).take(4_000)
                if (raw.startsWith("ERR")) return JSONObject().put("ok", false).put("err", raw.take(200)).toString()
                try {
                    var s = raw.trim()
                    repeat(2) {
                        if (s.startsWith("\"") && s.endsWith("\"") && s.length >= 2) {
                            s = try { org.json.JSONObject("{\"v\":$s}").optString("v", s) } catch (_: Exception) { s }
                        }
                    }
                    val o = org.json.JSONObject(s)
                    val l = o.optInt("left", 0); val t = o.optInt("top", 0)
                    val w = o.optInt("w", 0); val h = o.optInt("h", 0)
                    val cx = o.optInt("x", -1); val cy = o.optInt("y", -1)
                    val mx = maxOf((w * 0.15).toInt(), 2); val my = maxOf((h * 0.15).toInt(), 2)
                    val safe = org.json.JSONArray()
                    listOf(cx to cy, (l + mx) to (t + my), (l + w - mx) to (t + my),
                        (l + mx) to (t + h - my), (l + w - mx) to (t + h - my)
                    ).forEach { (px, py) ->
                        safe.put(org.json.JSONObject().put("x", px).put("y", py))
                    }
                    JSONObject().put("ok", cx >= 0 && cy >= 0)
                        .put("x", cx).put("y", cy)
                        .put("bounds", org.json.JSONObject().put("left", l).put("top", t).put("w", w).put("h", h))
                        .put("safe", safe)
                        .put("vw", o.optInt("vw", -1)).put("vh", o.optInt("vh", -1))
                        .put("sx", o.optInt("scrollX", 0)).put("sy", o.optInt("scrollY", 0))
                        .put("z", o.optDouble("z", 1.0)).put("dpr", o.optDouble("dpr", 1.0)).toString()
                } catch (_: Exception) { """{"ok":false,"err":"parse failed"}""" }
            }
            "/tap" -> {
                val x = q["x"]?.toFloatOrNull()
                val y = q["y"]?.toFloatOrNull()
                if (x == null || y == null) return """{"ok":false,"err":"missing x/y"}"""
                // CSS px (same numbers /pos and /box return). Blocks until the
                // queued gesture delivers, so ok:false means it never landed.
                val r = tapSync(x, y)
                JSONObject().put("ok", r.delivered).put("reason", r.reason).toString()
            }
            "/swipe" -> {
                val x1 = q["x1"]?.toFloatOrNull()
                val y1 = q["y1"]?.toFloatOrNull()
                val x2 = q["x2"]?.toFloatOrNull()
                val y2 = q["y2"]?.toFloatOrNull()
                if (x1 == null || y1 == null || x2 == null || y2 == null) return """{"ok":false,"err":"missing x1/y1/x2/y2"}"""
                val r = swipeSync(x1, y1, x2, y2, q["ms"]?.toLongOrNull()?.coerceIn(50, 2000) ?: 300)
                JSONObject().put("ok", r.delivered).put("reason", r.reason).toString()
            }
            "/stroke" -> {
                // Freeform polyline: path="x1,y1 x2,y2 …" (CSS px, ≤64 pts).
                val raw = q["path"] ?: return """{"ok":false,"err":"missing path"}"""
                val pts = try { GestureGen.parsePath(raw) } catch (_: Exception) { null }
                if (pts == null) return """{"ok":false,"err":"bad path (need 'x1,y1 x2,y2 …')"}"""
                val r = strokeSync(pts, q["ms"]?.toLongOrNull()?.coerceIn(100, 5000) ?: 600)
                JSONObject().put("ok", r.delivered).put("reason", r.reason).put("n", pts.size).toString()
            }
            "/circle" -> {
                val cx = q["cx"]?.toFloatOrNull()
                val cy = q["cy"]?.toFloatOrNull()
                val r0 = q["r"]?.toFloatOrNull()
                if (cx == null || cy == null || r0 == null) return """{"ok":false,"err":"missing cx/cy/r"}"""
                val pts = try { GestureGen.circle(cx, cy, r0, q["n"]?.toIntOrNull()?.coerceIn(8, 64) ?: 28) } catch (_: Exception) { null }
                if (pts == null) return """{"ok":false,"err":"bad circle"}"""
                val r = strokeSync(pts, q["ms"]?.toLongOrNull()?.coerceIn(100, 5000) ?: 900)
                JSONObject().put("ok", r.delivered).put("reason", r.reason).put("n", pts.size).toString()
            }
            "/gesture" -> {
                // Alias of /stroke (recorded human trails replay as-is; add
                // seed=… to humanize: translate/scale/rotate/tempo jitter).
                val raw = q["path"] ?: return """{"ok":false,"err":"missing path"}"""
                val pts0 = try { GestureGen.parsePath(raw) } catch (_: Exception) { null }
                if (pts0 == null) return """{"ok":false,"err":"bad path (need 'x1,y1 x2,y2 …')"}"""
                val seedQ = q["seed"]?.toLongOrNull()
                val (pts, ms) = if (seedQ != null) {
                    try { GestureGen.humanize(pts0, q["ms"]?.toLongOrNull()?.coerceIn(100, 5000) ?: 600, seedQ) } catch (_: Exception) { pts0 to 600L }
                } else pts0 to (q["ms"]?.toLongOrNull()?.coerceIn(100, 5000) ?: 600)
                val r = strokeSync(pts, ms)
                val o = JSONObject().put("ok", r.delivered).put("reason", r.reason).put("n", pts.size)
                if (seedQ != null) o.put("seed", seedQ)
                o.toString()
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
                val sel = com.rg.webloom.ui.terminal.BStore.resolve(q["sel"] ?: return """{"ok":false,"err":"missing sel"}""")
                val esc = sel.replace("\\", "\\\\").replace("'", "\\'").take(500)
                val raw = evalBlocking("(function(){try{return window.LightAgent?window.LightAgent.click('$esc'):'ERR no-shim';}catch(e){return 'ERR '+e;}})()", 12)
                JSONObject().put("ok", raw.contains("OK")).put("result", raw).toString()
            }
            "/fill" -> {                val sel = com.rg.webloom.ui.terminal.BStore.resolve(q["sel"] ?: return """{"ok":false,"err":"missing sel"}""")
                val value = q["value"] ?: ""
                val e1 = sel.replace("\\", "\\\\").replace("'", "\\'").take(500)
                val e2 = value.replace("\\", "\\\\").replace("'", "\\'").take(2000)
                val raw = evalBlocking("(function(){try{return window.LightAgent?window.LightAgent.fill('$e1','$e2'):'ERR no-shim';}catch(e){return 'ERR '+e;}})()", 12)
                JSONObject().put("ok", raw.contains("OK")).put("result", raw).toString()
            }
            "/upload" -> {
                val sel = com.rg.webloom.ui.terminal.BStore.resolve(q["sel"] ?: return """{"ok":false,"err":"missing sel"}""")
                val path = q["path"] ?: return """{"ok":false,"err":"missing path"}"""
                val f = sandboxFile(path) ?: return """{"ok":false,"err":"not found in sandbox: ${path.take(120)}"}"""
                val raw = uploadInputBlocking(sel, f)
                JSONObject().put("ok", raw.contains("OK")).put("result", raw.take(300)).toString()
            }
            "/fill-file" -> {
                val sel = com.rg.webloom.ui.terminal.BStore.resolve(q["sel"] ?: return """{"ok":false,"err":"missing sel"}""")
                val path = q["path"] ?: return """{"ok":false,"err":"missing path"}"""
                val f = sandboxFile(path) ?: return """{"ok":false,"err":"not found in sandbox: ${path.take(120)}"}"""
                val text = try { f.readText(Charsets.UTF_8) } catch (e: Exception) { return """{"ok":false,"err":"read failed: ${e.message}"}""" }
                if (text.length > 200_000) return """{"ok":false,"err":"file too big (>200KB text)"}"""
                val e1 = sel.replace("\\", "\\\\").replace("'", "\\'").take(500)
                val lit = JSONObject.quote(text)
                val raw = evalBlocking("(function(){try{var e=document.querySelector('$e1');if(!e)return 'ERR no-node';var v=$lit;var t=e.tagName;if(t==='INPUT'||t==='TEXTAREA'){e.focus();e.value=v;e.dispatchEvent(new Event('input',{bubbles:true}));e.dispatchEvent(new Event('change',{bubbles:true}));}else if(e.isContentEditable){e.focus();e.textContent=v;e.dispatchEvent(new Event('input',{bubbles:true}));}else return 'ERR not-fillable';return 'OK '+((''+v).length)+' chars';}catch(x){return 'ERR '+x;}})()", 15)
                JSONObject().put("ok", raw.contains("OK")).put("result", raw.take(300)).toString()
            }
            "/js-file" -> {
                val path = q["path"] ?: return """{"ok":false,"err":"missing path"}"""
                val f = sandboxFile(path) ?: return """{"ok":false,"err":"not found in sandbox: ${path.take(120)}"}"""
                val js = try { f.readText(Charsets.UTF_8) } catch (e: Exception) { return """{"ok":false,"err":"read failed: ${e.message}"}""" }
                if (js.isBlank()) return """{"ok":false,"err":"empty file"}"""
                if (js.length > 100_000) return """{"ok":false,"err":"file too big (>100KB)"}"""
                val raw = evalBlocking(js, 15).take(60_000)
                JSONObject().put("ok", !raw.startsWith("ERR")).put("result", raw).toString()
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
                val sel = com.rg.webloom.ui.terminal.BStore.resolve(q["sel"] ?: "")
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
                val CP = com.rg.webloom.data.CookieProfiles
                fun reqHost(): String {
                    val h = CP.normalizeHost(q["host"] ?: q["url"] ?: "")
                    if (h.isNotBlank()) return h
                    return CP.normalizeHost(webViewProvider?.invoke()?.url ?: "")
                }
                when (op) {
                    "set" -> {
                        val v = (q["value"] ?: q["name"] ?: return@awaitMain """{"ok":false,"err":"missing value"}""")
                        val url = (q["url"] ?: "").ifBlank { webViewProvider?.invoke()?.url ?: "" }
                        try {
                            cm.setCookie(url, v)
                            try { cm.flush() } catch (_: Exception) {}
                            """{"ok":true}"""
                        } catch (e: Exception) { """{"ok":false,"err":"${e.message}"}""" }
                    }
                    "save" -> {
                        val profile = q["name"] ?: q["value"] ?: return@awaitMain """{"ok":false,"err":"missing name"}"""
                        if (!CP.validProfile(profile)) return@awaitMain """{"ok":false,"err":"bad profile name"}"""
                        val host = reqHost()
                        if (host.isBlank()) return@awaitMain """{"ok":false,"err":"no host"}"""
                        val ck = try { cm.getCookie("https://$host/") } catch (_: Exception) { null }
                        if (ck.isNullOrBlank()) return@awaitMain """{"ok":false,"err":"no cookies for host"}"""
                        if (!CP.save(host, profile, ck)) return@awaitMain """{"ok":false,"err":"save failed"}"""
                        try { cm.flush() } catch (_: Exception) {}
                        JSONObject().put("ok", true).put("host", host).put("profile", profile).toString()
                    }
                    "load", "switch" -> {
                        val profile = q["name"] ?: q["value"] ?: return@awaitMain """{"ok":false,"err":"missing name"}"""
                        val host = reqHost()
                        if (host.isBlank()) return@awaitMain """{"ok":false,"err":"no host"}"""
                        val saved = CP.load(host, profile) ?: return@awaitMain """{"ok":false,"err":"no such profile"}"""
                        CP.expireAll("https://$host/")
                        val n = CP.applyTo("https://$host/", saved)
                        JSONObject().put("ok", true).put("host", host).put("profile", profile).put("cookies", n).toString()
                    }
                    "profiles", "list" -> {
                        val host = reqHost()
                        if (host.isBlank()) {
                            JSONObject().put("ok", true).put("hosts", org.json.JSONArray(CP.hosts().toList())).toString()
                        } else {
                            val o = JSONObject()
                            CP.list(host).forEach { (k, v) -> o.put(k, v) }
                            JSONObject().put("ok", true).put("host", host).put("profiles", o).toString()
                        }
                    }
                    "del", "delete", "remove" -> {
                        val profile = q["name"] ?: q["value"] ?: return@awaitMain """{"ok":false,"err":"missing name"}"""
                        val host = reqHost()
                        if (host.isBlank()) return@awaitMain """{"ok":false,"err":"no host"}"""
                        JSONObject().put("ok", CP.delete(host, profile)).toString()
                    }
                    "clear-host" -> {
                        val host = reqHost()
                        if (host.isBlank()) return@awaitMain """{"ok":false,"err":"no host"}"""
                        val n = CP.expireAll("https://$host/")
                        JSONObject().put("ok", true).put("host", host).put("cleared", n).toString()
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
                val sel = com.rg.webloom.ui.terminal.BStore.resolve(q["sel"] ?: return """{"ok":false,"err":"missing sel"}""")
                val e1 = sel.replace("\\", "\\\\").replace("'", "\\'").take(500)
                val raw = evalBlocking("(function(){try{var e=document.querySelector('$e1');if(!e)return 'ERR no-node';var r=e.getBoundingClientRect();['mouseover','mouseenter','mousemove'].forEach(function(t){e.dispatchEvent(new MouseEvent(t,{bubbles:true,cancelable:true,clientX:r.left+r.width/2,clientY:r.top+r.height/2}));});try{e.focus();}catch(x){}return 'OK hover '+Math.round(r.left)+','+Math.round(r.top);}catch(e){return 'ERR '+e;}})()", 12)
                JSONObject().put("ok", raw.contains("OK")).put("result", raw).toString()
            }
            "/select" -> {
                val sel = com.rg.webloom.ui.terminal.BStore.resolve(q["sel"] ?: return """{"ok":false,"err":"missing sel"}""")
                val value = q["value"] ?: ""
                val e1 = sel.replace("\\", "\\\\").replace("'", "\\'").take(500)
                val e2 = value.replace("\\", "\\\\").replace("'", "\\'").take(500)
                val raw = evalBlocking("(function(){try{var e=document.querySelector('$e1');if(!e)return 'ERR no-node';if(e.tagName!=='SELECT')return 'ERR not-a-select';var v='$e2';var hit=false;for(var i=0;i<e.options.length;i++){if(e.options[i].value===v||e.options[i].text.trim()===v){e.selectedIndex=i;hit=true;break;}}if(!hit)e.value=v;e.dispatchEvent(new Event('input',{bubbles:true}));e.dispatchEvent(new Event('change',{bubbles:true}));return 'OK selected '+e.selectedIndex;}catch(e){return 'ERR '+e;}})()", 12)
                JSONObject().put("ok", raw.contains("OK")).put("result", raw).toString()
            }
            "/store" -> {
                val S = com.rg.webloom.ui.terminal.BStore
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
                    com.rg.webloom.ui.browser.TabBus.listTabs?.invoke()?.forEach { t ->
                        arr.put(JSONObject().put("i", t.index).put("url", t.url)
                            .put("title", t.title).put("current", t.current))
                    }
                } catch (_: Exception) {}
                JSONObject().put("ok", true).put("tabs", arr).toString()
            }
            "/new" -> {
                val url = q["url"] ?: return """{"ok":false,"err":"missing url"}"""
                if (com.rg.webloom.ui.terminal.BBlock.blocksUrl(url)) {
                    return """{"ok":false,"err":"blocked by user: ${com.rg.webloom.ui.terminal.BBlock.normalize(url)}"}"""
                }
                mainHandler.post {
                    try { com.rg.webloom.ui.browser.TabBus.openInNewTab?.invoke(url) } catch (_: Exception) {}
                }
                """{"ok":true}"""
            }
            "/close" -> {
                val i = q["i"]?.toIntOrNull() ?: -1
                mainHandler.post {
                    try { com.rg.webloom.ui.browser.TabBus.closeTabAt?.invoke(i) } catch (_: Exception) {}
                }
                """{"ok":true}"""
            }
            "/switch" -> {
                val i = q["i"]?.toIntOrNull() ?: return """{"ok":false,"err":"missing i"}"""
                mainHandler.post {
                    try { com.rg.webloom.ui.browser.TabBus.selectTab?.invoke(i) } catch (_: Exception) {}
                }
                """{"ok":true}"""
            }
            "/submit" -> {
                val sel = com.rg.webloom.ui.terminal.BStore.resolve(q["sel"] ?: return """{"ok":false,"err":"missing sel"}""")
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
                        com.rg.webloom.data.HistoryStorage.all(ctx).takeLast(n).reversed().forEach { h ->
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
                    try { com.rg.webloom.ui.browser.TabBus.openHome?.invoke() } catch (_: Exception) {}
                }
                """{"ok":true}"""
            }
            // ── Parity routes (PTY `b()` matches EXEC `b`) ──
            "/dom" -> {
                val sel = com.rg.webloom.ui.terminal.BStore.resolve(q["sel"] ?: "body")
                val esc = sel.replace("\\", "\\\\").replace("'", "\\'").take(500)
                val raw = evalBlocking("(function(){try{var e=document.querySelector('$esc');return e?e.outerHTML.slice(0,20000):'ERR no-node';}catch(e){return 'ERR '+e;}})()", 12)
                JSONObject().put("ok", !raw.startsWith("ERR")).put("html", raw).toString()
            }
            "/url" -> awaitMain {
                JSONObject().put("ok", true)
                    .put("url", webViewProvider?.invoke()?.url ?: JSONObject.NULL).toString()
            }
            "/title" -> {
                val raw = evalBlockingJs("(function(){return document.title;})()", 12)
                JSONObject().put("ok", true).put("title", raw).toString()
            }
            "/next" -> {
                try { findNext(true) } catch (_: Exception) {}
                """{"ok":true}"""
            }
            "/prev" -> {
                try { findNext(false) } catch (_: Exception) {}
                """{"ok":true}"""
            }
            "/stores" -> {
                val o = JSONObject()
                com.rg.webloom.ui.terminal.BStore.all().forEach { (k, v) -> o.put(k, v) }
                JSONObject().put("ok", true).put("stores", o).toString()
            }
            "/unstore" -> {
                com.rg.webloom.ui.terminal.BStore.remove(q["name"] ?: "")
                """{"ok":true}"""
            }
            "/serve" -> {
                when (q["op"] ?: "status") {
                    "start", "on" -> {
                        try { startServer() } catch (e: Exception) {
                            return """{"ok":false,"err":"start failed: ${e.message}"}"""
                        }
                        JSONObject().put("ok", true).put("label", serverLabel.value).toString()
                    }
                    "stop", "off" -> {
                        try { stopServer() } catch (_: Exception) {}
                        """{"ok":true}"""
                    }
                    else -> JSONObject().put("ok", true)
                        .put("running", serverRunning.value)
                        .put("label", serverLabel.value).toString()
                }
            }
            "/block" -> {
                // EXEC-only: the blocklist is a privacy control — PTY shells
                // (curl + shared token file) must not mutate it. Read-only
                // listing stays so `b blocks` can still audit from PTY.
                val B = com.rg.webloom.ui.terminal.BBlock
                when (q["op"] ?: "list") {
                    "list" -> {
                        val arr = org.json.JSONArray()
                        B.all().sorted().forEach { arr.put(it) }
                        JSONObject().put("ok", true).put("blocked", arr).toString()
                    }
                    else -> """{"ok":false,"err":"EXEC-only: run b block/unblock in EXEC mode"}"""
                }
            }
            "/alias" -> {                val A = com.rg.webloom.ui.terminal.BrowserAliases
                when (q["op"] ?: "list") {
                    "set" -> {
                        val name = q["name"] ?: return """{"ok":false,"err":"missing name"}"""
                        val expansion = q["expansion"] ?: ""
                        if (!name.matches(Regex("[a-z0-9_-]+"))) return """{"ok":false,"err":"bad name"}"""
                        if (expansion.isBlank()) return """{"ok":false,"err":"missing expansion"}"""
                        A.set(name, expansion)
                        """{"ok":true}"""
                    }
                    "remove" -> {
                        A.remove(q["name"] ?: "")
                        """{"ok":true}"""
                    }
                    else -> {
                        val o = JSONObject()
                        A.all().forEach { (k, v) -> o.put(k, v) }
                        JSONObject().put("ok", true).put("aliases", o).toString()
                    }
                }
            }
            "/record" -> {
                when (q["op"] ?: "status") {
                    "start" -> {
                        try { startRecording() } catch (e: Exception) {
                            return """{"ok":false,"err":"${e.message}"}"""
                        }
                        """{"ok":true}"""
                    }
                    "stop" -> {
                        val n = recCount()
                        val saved = try { stopRecording() } catch (_: Exception) { null }
                        val o = JSONObject().put("ok", true).put("count", n)
                        try { if (saved != null) o.put("saved", saved) } catch (_: Exception) {}
                        o.toString()
                    }
                    "pause" -> {
                        try { pauseRecording() } catch (_: Exception) {}
                        """{"ok":true}"""
                    }
                    "resume" -> {
                        try { resumeRecording() } catch (_: Exception) {}
                        """{"ok":true}"""
                    }
                    "save" -> {
                        val path = try {
                            saveRecording((q["name"] ?: "rec").ifBlank { "rec" })
                        } catch (_: Exception) { null }
                        if (path != null) JSONObject().put("ok", true).put("path", path).toString()
                        else """{"ok":false,"err":"nothing to save"}"""
                    }
                    "list" -> {
                        val arr = org.json.JSONArray()
                        try {
                            listRecordings().take(10).forEach { (f, n) ->
                                arr.put(JSONObject().put("file", f).put("actions", n))
                            }
                        } catch (_: Exception) {}
                        JSONObject().put("ok", true).put("recordings", arr).toString()
                    }
                    else -> JSONObject().put("ok", true).put("recording", recording.value)
                        .put("count", recCount()).toString()
                }
            }
            "/metrics" -> {
                // Geometry audit for tap-accuracy tests (mirrors EXEC
                // `b metrics`, which additionally appends to metrics.log).
                val ctx = webViewProvider?.invoke()?.context
                val dm = try { ctx?.resources?.displayMetrics } catch (_: Exception) { null }
                val url = try { currentUrl() } catch (_: Exception) { "?" }
                var s = evalBlockingJs("(function(){try{var d=document.documentElement;return JSON.stringify({vw:window.innerWidth,vh:window.innerHeight,dpr:window.devicePixelRatio||1,sx:window.scrollX,sy:window.scrollY,cw:Math.max(d?d.scrollWidth:0,document.body?document.body.scrollWidth:0),ch:Math.max(d?d.scrollHeight:0,document.body?document.body.scrollHeight:0)});}catch(e){return 'ERR '+e;}})()", 12).trim()
                repeat(2) {
                    if (s.startsWith("\"") && s.endsWith("\"") && s.length >= 2) {
                        s = try { org.json.JSONObject("{\"v\":$s}").optString("v", s) } catch (_: Exception) { s }
                    }
                }
                val page = try { org.json.JSONObject(s) } catch (_: Exception) { org.json.JSONObject() }
                val rep = JSONObject()
                    .put("ok", true)
                    .put("ts", System.currentTimeMillis())
                    .put("screen", JSONObject()
                        .put("w", dm?.widthPixels ?: -1)
                        .put("h", dm?.heightPixels ?: -1)
                        .put("density", ((dm?.density ?: -1f).toDouble()))
                        .put("dpi", dm?.densityDpi ?: -1))
                    .put("page", JSONObject()
                        .put("url", url ?: "?")
                        .put("vw", page.optInt("vw", -1))
                        .put("vh", page.optInt("vh", -1))
                        .put("dpr", page.optDouble("dpr", -1.0))
                        .put("sx", page.optInt("sx", 0))
                        .put("sy", page.optInt("sy", 0))
                        .put("cw", page.optInt("cw", -1))
                        .put("ch", page.optInt("ch", -1)))
                try {
                    lastTap?.let { rep.put("lastTap", org.json.JSONObject(it)) }
                } catch (_: Exception) {}
                // On-screen WebView box: view x/y are WebView-relative
                // (screen = view + box); lastTap carries both spaces + delivered.
                try {
                    val box = webViewBoxBlocking(8)
                    try { rep.put("view", org.json.JSONObject(box)) }
                    catch (_: Exception) { rep.put("viewErr", box.take(80)) }
                } catch (_: Exception) {}
                // Same trail EXEC `b metrics` keeps: single serialized writer.
                try {
                    rep.put("logged", appendMetrics(rep).startsWith("logged"))
                } catch (_: Exception) {
                    try { rep.put("logged", false) } catch (_: Exception) {}
                }
                rep.toString()
            }
            "/links" -> {
                val max = q["max"]?.toIntOrNull()?.coerceIn(1, 200) ?: 100
                val raw = evalBlocking(
                    "(function(){try{var a=Array.prototype.slice.call(document.querySelectorAll('a[href]'),0,$max)" +
                        ".map(function(e){return{text:(e.innerText||'').trim().slice(0,80),href:(e.href||'').slice(0,500)}});" +
                        "return JSON.stringify(a);}catch(e){return 'ERR '+e;}})()", 12
                )
                if (raw.startsWith("ERR")) """{"ok":false,"err":"${raw.take(200)}"}"""
                else {
                    var s = raw.trim()
                    repeat(2) {
                        if (s.startsWith("\"") && s.endsWith("\"") && s.length >= 2) {
                            s = try { org.json.JSONObject("{\"v\":$s}").optString("v", s) } catch (_: Exception) { s }
                        }
                    }
                    try {
                        JSONObject().put("ok", true).put("links", org.json.JSONArray(s)).toString()
                    } catch (_: Exception) { """{"ok":false,"err":"parse failed"}""" }
                }
            }
            "/forms" -> {
                val raw = evalBlocking(
                    "(function(){try{" +
                        "function ps(e){try{if(e.id)return '#'+e.id;var p=e.parentNode;if(!p)return e.tagName.toLowerCase();" +
                        "var sibs=Array.prototype.filter.call(p.children,function(x){return x.tagName===e.tagName;});" +
                        "return e.tagName.toLowerCase()+':nth-of-type('+(sibs.indexOf(e)+1)+')';}catch(x){return '?';}}" +
                        "var out=[];var els=document.querySelectorAll('input,select,textarea');" +
                        "for(var i=0;i<els.length&&i<100;i++){var e=els[i];" +
                        "var f=e.form;var fi=-1;if(f){var fs=document.forms;for(var k=0;k<fs.length;k++){if(fs[k]===f){fi=k;break;}}}" +
                        "out.push({form:fi,type:(e.type||e.tagName.toLowerCase()),name:(e.name||'')," +
                        "label:((e.placeholder||e.getAttribute('aria-label')||'')+'').slice(0,60),sel:ps(e)});}" +
                        "return JSON.stringify(out);}catch(e){return 'ERR '+e;}})()", 12
                )
                if (raw.startsWith("ERR")) """{"ok":false,"err":"${raw.take(200)}"}"""
                else {
                    var s = raw.trim()
                    repeat(2) {
                        if (s.startsWith("\"") && s.endsWith("\"") && s.length >= 2) {
                            s = try { org.json.JSONObject("{\"v\":$s}").optString("v", s) } catch (_: Exception) { s }
                        }
                    }
                    try {
                        JSONObject().put("ok", true).put("fields", org.json.JSONArray(s)).toString()
                    } catch (_: Exception) { """{"ok":false,"err":"parse failed"}""" }
                }
            }
            "/wait" -> {
                // Worker-safe poll: text present or selector exists. For macros.
                val mode = q["mode"] ?: "text"
                val v = q["v"] ?: return """{"ok":false,"err":"missing v"}"""
                val timeout = q["timeout"]?.toLongOrNull()?.coerceIn(1000, 60000) ?: 10000L
                val t0 = System.currentTimeMillis()
                var found = false
                while (System.currentTimeMillis() - t0 < timeout) {
                    try {
                        found = if (mode == "sel") {
                            val esc = v.replace("\\", "\\\\").replace("'", "\\'").take(500)
                            val r = evalBlocking(
                                "(function(){try{return document.querySelector('$esc')?'YES':'NO';}catch(e){return 'ERR';}})()", 8
                            )
                            r.contains("YES")
                        } else {
                            try { pageTextBlocking(2000).contains(v) } catch (_: Exception) { false }
                        }
                    } catch (_: Exception) { false }
                    if (found) break
                    try { Thread.sleep(500) } catch (_: Exception) { break }
                }
                JSONObject().put("ok", found).put("found", found)
                    .put("elapsedMs", System.currentTimeMillis() - t0).toString()
            }
            "/survey" -> {
                // One-shot page bundle for agents: nav + viewport + errors + taps.
                val url = try { currentUrl() } catch (_: Exception) { "?" }
                val title = try {
                    evalBlockingJs("(function(){return document.title;})()", 8)
                } catch (_: Exception) { "?" }
                var s = evalBlockingJs("(function(){try{return JSON.stringify({vw:window.innerWidth,vh:window.innerHeight,dpr:window.devicePixelRatio||1,sx:window.scrollX,sy:window.scrollY});}catch(e){return 'ERR '+e;}})()", 8).trim()
                repeat(2) {
                    if (s.startsWith("\"") && s.endsWith("\"") && s.length >= 2) {
                        s = try { org.json.JSONObject("{\"v\":$s}").optString("v", s) } catch (_: Exception) { s }
                    }
                }
                val vp = try { org.json.JSONObject(s) } catch (_: Exception) { org.json.JSONObject() }
                val cons = org.json.JSONArray()
                try { consoleTail(8).forEach { cons.put(it.take(300)) } } catch (_: Exception) {}
                val tabs = try {
                    com.rg.webloom.ui.browser.TabBus.listTabs?.invoke()?.size ?: -1
                } catch (_: Exception) { -1 }
                val rep = JSONObject().put("ok", true)
                    .put("url", url ?: "?").put("title", title)
                    .put("viewport", vp).put("console", cons).put("tabs", tabs)
                try {
                    val box = webViewBoxBlocking(8)
                    try { rep.put("view", org.json.JSONObject(box)) }
                    catch (_: Exception) { rep.put("viewErr", box.take(80)) }
                } catch (_: Exception) {}
                try {
                    lastTap?.let { rep.put("lastTap", org.json.JSONObject(it)) }
                } catch (_: Exception) {}
                rep.toString()
            }
            "/save" -> {                val name = q["name"] ?: return """{"ok":false,"err":"missing name"}"""
                val ctx = webViewProvider?.invoke()?.context
                    ?: return """{"ok":false,"err":"no webview"}"""
                val asHtml = name.lowercase().endsWith(".html")
                val expr = if (asHtml) {
                    "(function(){try{return document.documentElement.outerHTML.slice(0,400000);}catch(e){return 'ERR '+e;}})()"
                } else {
                    "(function(){try{return document.body?document.body.innerText.slice(0,200000):'ERR no-body';}catch(e){return 'ERR '+e;}})()"
                }
                var r = evalBlockingJs(expr, 15).take(420_000)
                try {
                    var s = r.trim()
                    repeat(2) {
                        if (s.startsWith("\"") && s.endsWith("\"") && s.length >= 2) {
                            s = try { org.json.JSONObject("{\"v\":$s}").optString("v", s) } catch (_: Exception) { s }
                        }
                    }
                    r = s
                } catch (_: Exception) {}
                if (r.startsWith("ERR")) return """{"ok":false,"err":"${r.take(200)}"}"""
                return try {
                    val dir = java.io.File(ctx.filesDir, "sandbox/Downloads").apply { mkdirs() }
                    val safe = name.replace("/", "_").take(80).ifBlank { "page.txt" }
                    val outFile = java.io.File(dir, safe)
                    outFile.writeText(r, Charsets.UTF_8)
                    JSONObject().put("ok", true).put("path", outFile.absolutePath)
                        .put("bytes", r.length).toString()
                } catch (e: Exception) { """{"ok":false,"err":"save failed: ${e.message}"}""" }
            }
            "/reload-hard" -> {
                runOnPage { try { it.clearCache(true); it.reload() } catch (_: Exception) {} }
                """{"ok":true}"""
            }
            "/ua" -> awaitMain {
                val wv = webViewProvider?.invoke()
                    ?: return@awaitMain """{"ok":false,"err":"no webview"}"""
                when (q["op"] ?: "get") {
                    "mobile" -> {
                        try { wv.settings.userAgentString = null } catch (_: Exception) {}
                        """{"ok":true}"""
                    }
                    "desktop" -> {
                        try { wv.settings.userAgentString = com.rg.webloom.ui.browser.DESKTOP_UA } catch (_: Exception) {}
                        """{"ok":true}"""
                    }
                    "set" -> {
                        val v = q["value"] ?: return@awaitMain """{"ok":false,"err":"missing value"}"""
                        try { wv.settings.userAgentString = v.take(500) } catch (e: Exception) {
                            return@awaitMain """{"ok":false,"err":"${e.message}"}"""
                        }
                        """{"ok":true}"""
                    }
                    else -> JSONObject().put("ok", true)
                        .put("ua", try { wv.settings.userAgentString } catch (_: Exception) { JSONObject.NULL }).toString()
                }
            }
            "/viewport" -> {
                val raw = evalBlockingJs("(function(){try{return JSON.stringify({vw:window.innerWidth,vh:window.innerHeight,dpr:window.devicePixelRatio||1});}catch(e){return 'ERR '+e;}})()", 8)
                JSONObject().put("ok", !raw.startsWith("ERR")).put("viewport", raw.take(500)).toString()
            }
            "/zoom" -> {
                val op = q["op"] ?: "get"
                val raw = when (op) {
                    "in" -> evalBlockingJs("(function(){try{document.body.style.zoom=((parseFloat(document.body.style.zoom)||1)*1.2).toFixed(2);return 'OK '+document.body.style.zoom;}catch(e){return 'ERR '+e;}})()", 8)
                    "out" -> evalBlockingJs("(function(){try{document.body.style.zoom=((parseFloat(document.body.style.zoom)||1)/1.2).toFixed(2);return 'OK '+document.body.style.zoom;}catch(e){return 'ERR '+e;}})()", 8)
                    "reset" -> evalBlockingJs("(function(){try{document.body.style.zoom='';return 'OK 1';}catch(e){return 'ERR '+e;}})()", 8)
                    else -> evalBlockingJs("(function(){try{return 'OK '+(document.body.style.zoom||'1');}catch(e){return 'ERR '+e;}})()", 8)
                }
                JSONObject().put("ok", raw.contains("OK")).put("result", raw.take(200)).toString()
            }
            "/netlog" -> {
                val max = q["max"]?.toIntOrNull()?.coerceIn(1, 200) ?: 50
                val raw = evalBlockingJs("(function(){try{var es=(performance.getEntriesByType('resource')||[]).slice(-$max).map(function(e){return{u:(e.name||'').slice(0,300),t:e.initiatorType||'',d:Math.round(e.duration||0),s:e.transferSize||0};});return JSON.stringify(es);}catch(e){return 'ERR '+e;}})()", 10)
                if (raw.startsWith("ERR")) """{"ok":false,"err":"${raw.take(200)}"}"""
                else {
                    var s = raw.trim()
                    repeat(2) {
                        if (s.startsWith("\"") && s.endsWith("\"") && s.length >= 2) {
                            s = try { org.json.JSONObject("{\"v\":$s}").optString("v", s) } catch (_: Exception) { s }
                        }
                    }
                    try { JSONObject().put("ok", true).put("resources", org.json.JSONArray(s)).toString() }
                    catch (_: Exception) { """{"ok":false,"err":"parse failed"}""" }
                }
            }
            "/clear-data" -> awaitMain {
                val what = (q["what"] ?: "all").lowercase()
                try {
                    val cm = try { android.webkit.CookieManager.getInstance() } catch (_: Exception) { null }
                    val wv = webViewProvider?.invoke()
                    if (what == "cookies" || what == "all") {
                        try { cm?.removeAllCookies(null) } catch (_: Exception) {}
                        try { cm?.flush() } catch (_: Exception) {}
                    }
                    if (what == "cache" || what == "all") {
                        try { wv?.clearCache(true) } catch (_: Exception) {}
                    }
                    if (what == "storage" || what == "all") {
                        try { android.webkit.WebStorage.getInstance().deleteAllData() } catch (_: Exception) {}
                    }
                    if (what == "history" || what == "all") {
                        try { wv?.clearHistory() } catch (_: Exception) {}
                    }
                    """{"ok":true}"""
                } catch (e: Exception) { """{"ok":false,"err":"${e.message}"}""" }
            }
            "/tabdup" -> {
                mainHandler.post {
                    try {
                        val url = webViewProvider?.invoke()?.url ?: return@post
                        com.rg.webloom.ui.browser.TabBus.openInNewTab(url)
                    } catch (_: Exception) {}
                }
                """{"ok":true}"""
            }
            "/shot-el" -> {
                val sel = com.rg.webloom.ui.terminal.BStore.resolve(q["sel"] ?: return """{"ok":false,"err":"missing sel"}""")
                val esc = sel.replace("\\", "\\\\").replace("'", "\\'").take(500)
                val raw = evalBlockingJs("(function(){try{var e=document.querySelector('$esc');if(!e)return 'ERR no-node';try{e.scrollIntoView({block:'center'});}catch(x){}var r=e.getBoundingClientRect();return JSON.stringify({x:Math.round((r.left+r.right)/2),y:Math.round((r.top+r.bottom)/2)});}catch(e){return 'ERR '+e;}})()", 10)
                JSONObject().put("ok", !raw.startsWith("ERR")).put("pos", raw.take(500)).toString()
            }
            else -> """{"ok":false,"err":"unknown path. try /status /open /new /tabs /switch /close /home /url /title /text /read /dom /snap /js /links /forms /wait /survey /click /fill /upload /fill-file /js-file /submit /key /hover /select /store /stores /unstore /pos /box /tap /swipe /stroke /circle /gesture /scroll /scrollto /back /forward /reload /reload-hard /ua /viewport /zoom /netlog /clear-data /tabdup /shot-el /stop /find /next /prev /console /cookies /shot /history /downloads /save /metrics /serve /record /alias /block(list-only)"}"""
        }
    }
}
