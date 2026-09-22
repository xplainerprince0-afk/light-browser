package com.rg.webloom.ui.browser

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.Uri
import android.webkit.ConsoleMessage
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import com.rg.webloom.data.AppCtx
import com.rg.webloom.data.BrowserAgent
import com.rg.webloom.data.BrowserProfile
import com.rg.webloom.data.DownloadHelper
import com.rg.webloom.data.Prefs
import com.rg.webloom.data.SitePrefs

const val DESKTOP_UA =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

/** System WebView UA captured on first setup — restoring this beats `null` (some OEMs keep stale overrides). */
private object DefaultUa {
    @Volatile var value: String? = null
}

/**
 * Anti-bot realism: the stock WebView UA contains "; wv" which screams
 * "embedded WebView" to Reddit/Cloudflare/bot walls (degraded pages, hidden
 * login buttons). Strip it so we look like real Chrome. Everything else
 * (platform, touch, DPR) stays truthful — spoofing those spikes scores.
 */
fun stripWv(ua: String?): String? {
    if (ua.isNullOrBlank()) return ua
    return try {
        ua.replace("; wv", "").replace(";wv", "").trim()
    } catch (_: Exception) { ua }
}

/** Stripped (no "; wv") system UA captured at first setup, or null if unknown. */
fun strippedDefaultUa(): String? = try { stripWv(DefaultUa.value) } catch (_: Exception) { null }

/**
 * Human file-upload bridge: a WebView `<input type=file>` has NO chooser by
 * default — taps silently die (the "can't choose anything" bug). BrowserScreen
 * installs [openChooser] (SAF picker); the WebChromeClient below routes
 * [WebChromeClient.onShowFileChooser] through it. Agent uploads (`b upload`)
 * bypass this entirely (DataTransfer injection, no chooser needed).
 */
object FileChooserBus {
    var openChooser: ((ValueCallback<Array<Uri>>, WebChromeClient.FileChooserParams?) -> Unit)? = null
    var pending: ValueCallback<Array<Uri>>? = null
}

class BrowserCallbacks(
    val onStarted: (String) -> Unit,
    val onProgress: (Int) -> Unit,
    val onFinished: (url: String, title: String) -> Unit,
    val onLongPressUrl: (String) -> Unit,
    val inject: (webView: WebView, url: String, runAt: String) -> Unit,
    val onVisited: (String) -> Unit = {}
)

/**
 * Main-thread-tracked page host per WebView.
 * shouldInterceptRequest runs on a background thread where touching the WebView
 * (even getUrl()) is a StrictMode violation — so the host is recorded here on
 * the Main thread (onPageStarted/onPageFinished/doUpdateVisitedHistory) and
 * only read off-Main. Weak keys: dead tabs drop out by themselves.
 */
private object PageHosts {
    private val map = java.util.Collections.synchronizedMap(java.util.WeakHashMap<WebView, String>())
    fun set(wv: WebView?, url: String) {
        if (wv == null || url.isBlank()) return
        try {
            val h = SitePrefs.hostOf(url)
            if (h.isNotBlank()) map[wv] = h
        } catch (_: Exception) {}
    }
    fun get(wv: WebView?): String? = try { wv?.let { map[it] } } catch (_: Exception) { null }
}

@SuppressLint("SetJavaScriptEnabled", "AddJavascriptInterface")
fun setupLightWebView(wv: WebView, cb: BrowserCallbacks): WebView {
    val app = AppCtx.ctx
    BrowserProfile.configure(app, wv)
    try {
        if (DefaultUa.value.isNullOrBlank()) DefaultUa.value = stripWv(wv.settings.userAgentString)
        val stripped = stripWv(DefaultUa.value ?: wv.settings.userAgentString)
        if (Prefs.desktopMode) wv.settings.userAgentString = DESKTOP_UA
        else stripped?.let { if (it.isNotBlank() && wv.settings.userAgentString != it) wv.settings.userAgentString = it }
    } catch (_: Exception) {}

    try {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
            val sw = android.webkit.ServiceWorkerController.getInstance()
            // No in-app request blocking: the homegrown Adblock.kt (host+path
            // substring matching) broke Cloudflare challenges (cdn-cgi/,
            // cloudflareinsights) and caused white screens. Rely on system-level
            // AdAway / DNS filtering instead. Seam for a future maintained engine
            // (e.g. Brave-based + EasyList): plug its shouldIntercept here and in
            // WebViewClient.shouldInterceptRequest below, fail-open on exception.
            sw.setServiceWorkerClient(object : android.webkit.ServiceWorkerClient() {
                override fun shouldInterceptRequest(request: WebResourceRequest): WebResourceResponse? {
                    return null
                }
            })
            sw.serviceWorkerWebSettings.apply {
                allowContentAccess = true
                allowFileAccess = false
            }
        }
    } catch (_: Exception) {}

    val settings = wv.settings
    // Cache respects Settings toggle (BrowserProfile already applied it) — don't force LOAD_DEFAULT.
    // Smoothness-first: hardware layer + high render priority (extra RAM accepted).
    try {
        settings.loadsImagesAutomatically = true
        settings.blockNetworkImage = false
        // Keep false (see BrowserProfile): overview + empty-shell SPAs gave
        // 0-height layout viewports (vh=0) on some ROMs.
        settings.loadWithOverviewMode = false
        @Suppress("DEPRECATION")
        settings.setRenderPriority(android.webkit.WebSettings.RenderPriority.HIGH)
    } catch (_: Exception) {}
    try {
        wv.setLayerType(android.view.View.LAYER_TYPE_HARDWARE, null)
        try { BrowserProfile.applyLayer(wv, null) } catch (_: Exception) {}
        wv.isVerticalScrollBarEnabled = true
    } catch (_: Exception) {}

    val bridge = DownloadHelper.BlobBridge(app)
    try {
        wv.addJavascriptInterface(bridge, "BlobDownloader")
        wv.addJavascriptInterface(bridge, "LightBlobBridge")
    } catch (_: Exception) {}

    wv.webViewClient = object : WebViewClient() {
        override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
            // Fail-open: no in-app blocking (see ServiceWorker note above).
            // PageHosts is kept so a future engine can do first-party vs
            // third-party checks without touching the WebView off-Main.
            return super.shouldInterceptRequest(view, request)
        }

        override fun onPageStarted(v: WebView?, url: String?, favicon: Bitmap?) {
            super.onPageStarted(v, url, favicon)
            if (url != null) {
                try { if (v != null) PageHosts.set(v, url) } catch (_: Exception) {}
                // Per-site overrides win over global switches.
                try {
                    val host = SitePrefs.hostOf(url)
                    val wantJs = SitePrefs.effectiveJs(app, host)
                    if (v != null && v.settings.javaScriptEnabled != wantJs) {
                        v.settings.javaScriptEnabled = wantJs
                    }
                    val wantDesk = SitePrefs.effectiveDesktop(app, host)
                    if (v != null) {
                        if (wantDesk && v.settings.userAgentString != DESKTOP_UA) v.settings.userAgentString = DESKTOP_UA
                        else if (!wantDesk) {
                            val def = stripWv(DefaultUa.value)
                            if (!def.isNullOrBlank() && v.settings.userAgentString != def) {
                                v.settings.userAgentString = def
                            } else if (def.isNullOrBlank() && v.settings.userAgentString == DESKTOP_UA) {
                                v.settings.userAgentString = null
                            }
                        }
                    }
                    // Rendering layer per navigation: private/local hosts always
                    // software (never black), global toggle forces software
                    // everywhere (custom-ROM GPU fix), else hardware.
                    if (v != null) {
                        try { BrowserProfile.applyLayer(v, url) } catch (_: Exception) {
                            try { v.setLayerType(android.view.View.LAYER_TYPE_HARDWARE, null) } catch (_: Exception) {}
                        }
                    }
                } catch (_: Exception) {}
                cb.onStarted(url)
                try { v?.let { cb.inject(it, url, "document_start") } } catch (_: Exception) {}
            }
        }

        override fun doUpdateVisitedHistory(v: WebView?, url: String?, isReload: Boolean) {
            super.doUpdateVisitedHistory(v, url, isReload)
            // Fires on pushState/replaceState too (no onPageStarted there) — keeps the
            // tab's URL in sync for SPAs so switching tabs doesn't reload them.
            // Runs on Main: safe to touch the WebView, but we don't need to.
            if (url != null && !isReload) {
                try { cb.onVisited(url) } catch (_: Exception) {}
            }
        }

        override fun onPageFinished(v: WebView?, url: String?) {
            super.onPageFinished(v, url)
            if (url != null && v != null) {
                try { PageHosts.set(v, url) } catch (_: Exception) {}
                cb.onFinished(url, v.title ?: url)
                try { BrowserAgent.ensureShim(v) } catch (_: Exception) {}
                try { injectRealism(v) } catch (_: Exception) {}
                try { BrowserAgent.rearmRecorder(v) } catch (_: Exception) {}
                try {
                    val host = SitePrefs.hostOf(url)
                    if (!SitePrefs.effectiveDesktop(app, host)) injectMobileViewport(v)
                    else injectDesktop(v)
                } catch (_: Exception) { injectMobileViewport(v) }
                injectVisibilityHack(v, url)
                v.postDelayed({
                    try {
                        // Single call: injectAll filters by exact runAt, so call idle once.
                        // document_end scripts run on the immediate inject below via end pass.
                        cb.inject(v, url, "document_end")
                    } catch (_: Exception) {}
                    try {
                        v.postDelayed({ try { cb.inject(v, url, "document_idle") } catch (_: Exception) {} }, 350)
                    } catch (_: Exception) {}
                }, 150)
            }
        }

        override fun onReceivedError(
            v: WebView?, req: WebResourceRequest?, err: android.webkit.WebResourceError?
        ) {
            super.onReceivedError(v, req, err)
            try {
                if (req == null || req.isForMainFrame) {
                    val code = try { err?.errorCode ?: -1 } catch (_: Exception) { -1 }
                    val desc = try { err?.description?.toString() ?: "load error" } catch (_: Exception) { "load error" }
                    val url = try { req?.url?.toString() ?: "" } catch (_: Exception) { "" }
                    BrowserAgent.logConsole("[page-error] $code $desc @ $url")
                    // No toast: custom-ROM CA/clock spam made bottom popups
                    // irritating. Details stay in b console/netlog.
                }
            } catch (_: Exception) {}
        }

        override fun onReceivedHttpError(
            v: WebView?, req: WebResourceRequest?, resp: WebResourceResponse?
        ) {
            super.onReceivedHttpError(v, req, resp)
            try {
                if (req == null || req.isForMainFrame) {
                    val code = try { resp?.statusCode ?: -1 } catch (_: Exception) { -1 }
                    val url = try { req?.url?.toString() ?: "" } catch (_: Exception) { "" }
                    BrowserAgent.logConsole("[http-error] $code @ $url")
                    // No toast for 403/429/503 (was: "Blocked — try reload-hard").
                    // Cloudflare/bot denies now surface only in b console/netlog.
                }
            } catch (_: Exception) {}
        }

        override fun onReceivedSslError(
            v: WebView?, handler: android.webkit.SslErrorHandler?, err: android.net.http.SslError?
        ) {
            try {
                BrowserAgent.logConsole("[ssl-error] ${err?.primaryError ?: -1} @ ${err?.url ?: ""}")
                // No toast: custom-ROM CA/clock issues spammed "SSL error" on
                // every site. Page is still cancelled (secure); see b console.
            } catch (_: Exception) {}
            try { handler?.cancel() } catch (_: Exception) {}
        }

        override fun onSafeBrowsingHit(
            v: WebView?, req: WebResourceRequest?,
            threat: Int, cb2: android.webkit.SafeBrowsingResponse?
        ) {
            try {
                BrowserAgent.logConsole("[safe-browsing] threat=$threat @ ${req?.url}")
                // No toast — logged to b console/netlog only.
            } catch (_: Exception) {}
            try { cb2?.proceed(false) } catch (_: Exception) {
                try { super.onSafeBrowsingHit(v, req, threat, cb2) } catch (_: Exception) {}
            }
        }

        override fun shouldOverrideUrlLoading(v: WebView?, req: WebResourceRequest?): Boolean {            val raw = req?.url?.toString() ?: return false
            val scheme = try { req?.url?.scheme?.lowercase() ?: "" } catch (_: Exception) { "" }
            // External schemes → system handler, not WebView.
            if (scheme == "tel" || scheme == "mailto" || scheme == "sms" || scheme == "smsto" ||
                scheme == "geo" || scheme == "intent" || scheme == "market" || scheme == "whatsapp"
            ) {
                try {
                    val i = android.content.Intent(android.content.Intent.ACTION_VIEW, req?.url)
                    i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    app.startActivity(i)
                } catch (_: Exception) {
                    try { android.widget.Toast.makeText(app, "No app for $scheme link", android.widget.Toast.LENGTH_SHORT).show() } catch (_: Exception) {}
                }
                return true
            }
            if (!raw.startsWith("http://") && !raw.startsWith("https://") && !raw.startsWith("lb://")) return true
            // User blocklist (b block): clicks, JS navs and b-driven loads die
            // here with a popup — the AI sees "blocked by user", never the page.
            val blockedHost = try {
                if (com.rg.webloom.ui.terminal.BBlock.blocksUrl(raw)) {
                    com.rg.webloom.ui.terminal.BBlock.normalize(raw)
                } else null
            } catch (_: Exception) { null }
            if (blockedHost != null) {
                try {
                    android.widget.Toast.makeText(app, "Blocked by you: $blockedHost (b unblock $blockedHost)", android.widget.Toast.LENGTH_LONG).show()
                } catch (_: Exception) {}
                return true
            }
            return false
        }
    }

    wv.webChromeClient = object : WebChromeClient() {
        // Throttle progress → StateFlow to avoid recompose per 1%.
        private var lastP = -1
        private var lastT = 0L
        override fun onProgressChanged(v: WebView?, p: Int) {
            val now = System.currentTimeMillis()
            if (p == 100 || p - lastP >= 5 || now - lastT > 400) {
                lastP = p; lastT = now
                cb.onProgress(p)
            }
        }
        // <input type=file> → SAF picker. Without this override the tap is
        // swallowed (no chooser, no error). Previous callback is cancelled so
        // a double-tap can't leak a dangling ValueCallback.
        override fun onShowFileChooser(
            v: WebView?,
            filePathCallback: ValueCallback<Array<Uri>>?,
            fileChooserParams: FileChooserParams?
        ): Boolean {
            return try {
                try { FileChooserBus.pending?.onReceiveValue(null) } catch (_: Exception) {}
                FileChooserBus.pending = filePathCallback
                val h = FileChooserBus.openChooser
                if (filePathCallback == null || h == null) {
                    try { filePathCallback?.onReceiveValue(null) } catch (_: Exception) {}
                    FileChooserBus.pending = null
                    false
                } else {
                    h(filePathCallback, fileChooserParams)
                    true
                }
            } catch (_: Exception) {
                try { filePathCallback?.onReceiveValue(null) } catch (_: Exception) {}
                FileChooserBus.pending = null
                false
            }
        }
        // Popup / target=_blank / window.open (OAuth, checkout) → open in new tab.
        override fun onCreateWindow(v: WebView?, isDialog: Boolean, isUserGesture: Boolean, resultMsg: android.os.Message?): Boolean {
            try {
                val transport = resultMsg?.obj as? WebView.WebViewTransport ?: return false
                val ctx = v?.context ?: return false
                val tmp = WebView(ctx)
                transport.webView = tmp
                resultMsg.sendToTarget()
                tmp.webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView?, req: WebResourceRequest?): Boolean {
                        val u = req?.url?.toString() ?: return true
                        if (!u.startsWith("http://") && !u.startsWith("https://")) return true
                        val blockedHost = try {
                            if (com.rg.webloom.ui.terminal.BBlock.blocksUrl(u)) {
                                com.rg.webloom.ui.terminal.BBlock.normalize(u)
                            } else null
                        } catch (_: Exception) { null }
                        if (blockedHost != null) {
                            try {
                                android.widget.Toast.makeText(view?.context ?: app, "Blocked by you: $blockedHost", android.widget.Toast.LENGTH_LONG).show()
                            } catch (_: Exception) {}
                            try { tmp.destroy() } catch (_: Exception) {}
                            return true
                        }
                        try {
                            com.rg.webloom.ui.browser.TabBus.openInNewTab(u)
                        } catch (_: Exception) {
                            try {
                                val c = view?.context
                                if (c != null) {
                                    val i = android.content.Intent(android.content.Intent.ACTION_VIEW, req?.url)
                                    c.startActivity(i)
                                }
                            } catch (_: Exception) {}
                        } finally {
                            try { tmp.destroy() } catch (_: Exception) {}
                        }
                        return true
                    }
                }
                return true
            } catch (_: Exception) { return false }
        }

        override fun onConsoleMessage(cm: ConsoleMessage?): Boolean {
            // Filter known-noisy sources so logcat stays useful
            cm?.let {
                val src = it.sourceId() ?: ""
                val msg = it.message() ?: ""
                // Click-recorder events ride the console: parse, don't print.
                // Length/shape guard here too (BrowserAgent.recordEvent also guards).
                if (msg.startsWith("__LB_REC__:")) {
                    try {
                        val payload = msg.removePrefix("__LB_REC__:")
                        if (payload.length <= 4_000) BrowserAgent.recordEvent(payload)
                    } catch (_: Exception) {}
                    return@let
                }
                // Cloudflare Turnstile / challenge logs are kept now (they were
                // silently dropped before, which made blocks look like white screens).
                // Only drop known-noisy painting probes.
                if (msg.contains("font-size:0;color:transparent") || msg == "NaN") return@let
                try {
                    BrowserAgent.logConsole("[${it.messageLevel()}] $msg @ $src:${it.lineNumber()}")
                } catch (_: Exception) {}
            }
            return super.onConsoleMessage(cm)
        }
    }

    wv.setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
        if (url.startsWith("blob:")) {
            // Escape quotes/newlines — unescaped interpolation breaks JS and can inject.
            fun jsStr(s: String): String = s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r").take(2000)
            val js = """
                (function(){
                  try{
                    var xhr=new XMLHttpRequest();
                    xhr.open('GET', "${jsStr(url)}", true);
                    xhr.responseType='blob';
                    xhr.onload=function(){
                      if(this.status==200){
                        var reader=new FileReader();
                        reader.readAsDataURL(this.response);
                        reader.onloadend=function(){
                          try{window.BlobDownloader.onBlobDownload(reader.result, "${jsStr(mimeType ?: "")}", "${jsStr(contentDisposition ?: "")}");}catch(e){console.error('blob-bridge',e);}
                        };
                        reader.onerror=function(){console.error('blob-read-failed');};
                      } else { console.error('blob-xhr-status:'+this.status); }
                    };
                    xhr.onerror=function(){console.error('blob-xhr-error');};
                    xhr.send();
                  }catch(e){console.error('blob-setup',e);}
                })();
            """.trimIndent()
            wv.evaluateJavascript(js, null)
            Toast.makeText(app, "Capturing blob…", Toast.LENGTH_SHORT).show()
        } else {
            DownloadHelper.enqueue(app, url, userAgent, contentDisposition, mimeType)
        }
    }

    wv.setOnLongClickListener { v ->
        val result = (v as WebView).hitTestResult
        if (result.type == WebView.HitTestResult.SRC_ANCHOR_TYPE ||
            result.type == WebView.HitTestResult.IMAGE_TYPE ||
            result.type == WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE
        ) {
            result.extra?.let {
                cb.onLongPressUrl(it)
                return@setOnLongClickListener true
            }
        }
        false
    }
    return wv
}

private fun injectRealism(v: WebView) {
    // Look like real Chrome to bot walls (Reddit hidden login, CF challenges).
    // Truthful where it matters (platform/touch/DPR untouched — spoofing those
    // spikes scores). Only fills gaps WebView leaves empty vs desktop Chrome:
    // webdriver=false, window.chrome stub, plugins non-empty, userAgentData
    // brands matching Chrome/131. Fail-open single eval, no navigation impact.
    v.evaluateJavascript(
        """(function(){
          try{
            try{Object.defineProperty(navigator,'webdriver',{get:function(){return false;},configurable:true});}catch(e){}
            try{
              if(!window.chrome) window.chrome={};
              if(!window.chrome.runtime) window.chrome.runtime={};
              if(!window.chrome.loadTimes) window.chrome.loadTimes=function(){};
              if(!window.chrome.csi) window.chrome.csi=function(){};
            }catch(e){}
            try{
              if(!navigator.plugins||navigator.plugins.length===0){
                Object.defineProperty(navigator,'plugins',{get:function(){return [{name:'PDF Viewer',filename:'internal-pdf-viewer'},{name:'Chrome PDF Viewer',filename:'mhjfbmdgcfjbbpaeojofohoefgiehjai'}];},configurable:true});
              }
            }catch(e){}
            try{
              if(!navigator.userAgentData){
                Object.defineProperty(navigator,'userAgentData',{get:function(){return {brands:[{brand:'Chromium',version:'131'},{brand:'Google Chrome',version:'131'},{brand:'Not-A.Brand',version:'99'}],mobile:false,platform:'Windows'};},configurable:true});
              }
            }catch(e){}
            try{
              if(!navigator.languages||navigator.languages.length===0){
                Object.defineProperty(navigator,'languages',{get:function(){return ['en-US','en'];},configurable:true});
              }
            }catch(e){}
          }catch(e){}
        })();""".trimIndent(), null
    )
}

private fun injectMobileViewport(v: WebView) {
    // Pages without a viewport meta render desktop-wide on phones (tiny text,
    // sideways scroll). Insert a sane one so they fit the phone ratio.
    v.evaluateJavascript(
        """(function(){
          try{
            var m=document.querySelector('meta[name="viewport"]');
            if(!m){
              m=document.createElement('meta');
              m.name='viewport';
              m.content='width=device-width, initial-scale=1.0, maximum-scale=5.0, user-scalable=yes';
              (document.head||document.documentElement).appendChild(m);
            }
          }catch(e){}
        })();""".trimIndent(), null
    )
}

private fun injectVisibilityHack(v: WebView, url: String) {
    val lower = url.lowercase()
    if (!lower.contains("youtube.com") && !lower.contains("youtu.be") &&
        !lower.contains("soundcloud.com") && !lower.contains("wtr-lab.com")
    ) return
    v.evaluateJavascript(
        """(function(){
          if(window.__lb_vis)return;window.__lb_vis=true;
          try{
            Object.defineProperty(document,'visibilityState',{value:'visible',configurable:true});
            Object.defineProperty(document,'hidden',{value:false,configurable:true});
            document.dispatchEvent(new Event('visibilitychange'));
          }catch(e){}
        })();""".trimIndent(), null
    )
}

private fun injectDesktop(v: WebView) {
    // Keep JS UA consistent with the native override, but do NOT spoof
    // navigator.platform (Win32 on a touch Android device spikes bot scores
    // on Cloudflare / bot walls). Touch points + DPR stay truthful.
    v.evaluateJavascript(
        """(function(){
          try{
            Object.defineProperty(navigator,'userAgent',{get:function(){return "$DESKTOP_UA";},configurable:true});
          }catch(e){}
        })();""".trimIndent(), null
    )
}
