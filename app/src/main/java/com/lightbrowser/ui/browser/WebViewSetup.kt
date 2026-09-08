package com.lightbrowser.ui.browser

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import com.lightbrowser.data.Adblock
import com.lightbrowser.data.AppCtx
import com.lightbrowser.data.BrowserAgent
import com.lightbrowser.data.BrowserProfile
import com.lightbrowser.data.DownloadHelper
import com.lightbrowser.data.Prefs
import com.lightbrowser.data.SitePrefs
import java.io.ByteArrayInputStream

const val DESKTOP_UA =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"

class BrowserCallbacks(
    val onStarted: (String) -> Unit,
    val onProgress: (Int) -> Unit,
    val onFinished: (url: String, title: String) -> Unit,
    val onLongPressUrl: (String) -> Unit,
    val inject: (webView: WebView, url: String, runAt: String) -> Unit
)

@SuppressLint("SetJavaScriptEnabled", "AddJavascriptInterface")
fun setupLightWebView(wv: WebView, cb: BrowserCallbacks): WebView {
    val app = AppCtx.ctx
    BrowserProfile.configure(app, wv)
    try {
        if (Prefs.desktopMode) wv.settings.userAgentString = DESKTOP_UA
    } catch (_: Exception) {}

    try {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
            val sw = android.webkit.ServiceWorkerController.getInstance()
            sw.setServiceWorkerClient(object : android.webkit.ServiceWorkerClient() {
                override fun shouldInterceptRequest(request: WebResourceRequest): WebResourceResponse? {
                    return try {
                        val u = request.url?.toString() ?: return null
                        if (Prefs.adBlock && Adblock.isAdUrl(u)) {
                            WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
                        } else null
                    } catch (_: Exception) { null }
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
    try {
        settings.loadsImagesAutomatically = true
        settings.blockNetworkImage = false
        settings.loadWithOverviewMode = true
    } catch (_: Exception) {}

    val bridge = DownloadHelper.BlobBridge(app)
    try {
        wv.addJavascriptInterface(bridge, "BlobDownloader")
        wv.addJavascriptInterface(bridge, "LightBlobBridge")
    } catch (_: Exception) {}

    wv.webViewClient = object : WebViewClient() {
        override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
            try {
                val u = request?.url?.toString() ?: return super.shouldInterceptRequest(view, request)
                val host = request.url?.host ?: ""
                val pageHost = try { view?.url?.let { SitePrefs.hostOf(it) } ?: host } catch (_: Exception) { host }
                if (SitePrefs.effectiveAdblock(app, pageHost) && Adblock.isAdUrl(u)) {
                    return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
                }
            } catch (_: Exception) {}
            return super.shouldInterceptRequest(view, request)
        }

        override fun onPageStarted(v: WebView?, url: String?, favicon: Bitmap?) {
            super.onPageStarted(v, url, favicon)
            if (url != null) {
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
                        else if (!wantDesk && v.settings.userAgentString == DESKTOP_UA) v.settings.userAgentString = null
                    }
                    // Render layer per site: hardware everywhere for smooth scroll,
                    // software only where it fixes flicker (WTR fixed panels).
                    if (v != null) {
                        val soft = host.contains("wtr-lab.com")
                        v.setLayerType(
                            if (soft) android.view.View.LAYER_TYPE_SOFTWARE else android.view.View.LAYER_TYPE_HARDWARE,
                            null
                        )
                    }
                } catch (_: Exception) {}
                cb.onStarted(url)
                try { v?.let { cb.inject(it, url, "document_start") } } catch (_: Exception) {}
            }
        }

        override fun onPageFinished(v: WebView?, url: String?) {
            super.onPageFinished(v, url)
            if (url != null && v != null) {
                cb.onFinished(url, v.title ?: url)
                try { BrowserAgent.ensureShim(v) } catch (_: Exception) {}
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

        override fun shouldOverrideUrlLoading(v: WebView?, req: WebResourceRequest?): Boolean {
            val raw = req?.url?.toString() ?: return false
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
                        if (u.startsWith("http://") || u.startsWith("https://")) {
                            try { com.lightbrowser.ui.browser.TabBus.openInNewTab(u) } catch (_: Exception) {
                                try { view?.context?.let { c -> android.content.Intent(android.content.Intent.ACTION_VIEW, req?.url).let { c.startActivity(it) } } } catch (_: Exception) {}
                            }
                        }
                        try { tmp.destroy() } catch (_: Exception) {}
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
                if (src.contains("challenges.cloudflare.com") || src.contains("turnstile")) return@let
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
    v.evaluateJavascript(
        """(function(){
          try{
            Object.defineProperty(navigator,'userAgent',{get:function(){return "$DESKTOP_UA";},configurable:true});
            Object.defineProperty(navigator,'platform',{get:function(){return 'Win32';},configurable:true});
          }catch(e){}
        })();""".trimIndent(), null
    )
}
