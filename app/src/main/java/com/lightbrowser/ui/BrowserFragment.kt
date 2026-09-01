package com.lightbrowser.ui

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Message
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.webkit.*
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.webkit.ServiceWorkerController
import androidx.webkit.ServiceWorkerClient
import com.lightbrowser.data.AppCtx
import com.lightbrowser.data.DownloadHelper
import com.lightbrowser.data.Prefs
import com.lightbrowser.data.ScriptStorage
import com.lightbrowser.databinding.FragmentBrowserBinding

class BrowserFragment : Fragment() {
    private var _binding: FragmentBrowserBinding? = null
    private val binding get() = _binding!!

    companion object {
        var pendingUrl: String? = null
        private const val TAG = "LightBrowser"
        private const val DESKTOP_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentBrowserBinding.inflate(inflater, container, false)
        return binding.root
    }

    @SuppressLint("SetJavaScriptEnabled", "AddJavascriptInterface")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        try { AppCtx.init(requireContext()) } catch (_: Exception) {}

        // === Wibgar fq1:311 ServiceWorker pre-config (critical for Worker blob: + fetch) ===
        try {
            val sw = ServiceWorkerController.getInstance()
            sw.setServiceWorkerClient(object : ServiceWorkerClient() {
                override fun shouldInterceptRequest(request: WebResourceRequest): WebResourceResponse? = null
            })
            sw.serviceWorkerWebSettings.apply {
                allowContentAccess = true
                allowFileAccess = true
                // blockNetworkLoads = false default
            }
        } catch (e: Exception) { Log.w(TAG, "ServiceWorker sw failed", e) }

        val wv = binding.webView
        // === Wibgar fq1:390-394 + 498 hardware layer ===
        wv.setLayerType(View.LAYER_TYPE_HARDWARE, null)

        val s = wv.settings
        // === Wibgar fq1:595-730 exact WebSettings ===
        try {
            s.javaScriptEnabled = Prefs.jsEnabled
            s.domStorageEnabled = true // localStorage for WTR
            s.databaseEnabled = true // IndexedDB for WTR
            s.allowFileAccess = true // !isIncognito – WTR Worker via blob needs true
            s.allowContentAccess = true
            try { s.databasePath = requireContext().getDir("databases", 0).path } catch (_: Exception) {}
            // deprecated but Wibgar keeps it
            @Suppress("DEPRECATION") s.renderPriority = WebSettings.RenderPriority.HIGH
            s.setSupportZoom(true)
            s.builtInZoomControls = true
            s.displayZoomControls = false
            s.useWideViewPort = true
            s.loadWithOverviewMode = true
            s.setSupportMultipleWindows(true) // target="_blank" + blob downloads
            s.cacheMode = WebSettings.LOAD_DEFAULT
            @Suppress("DEPRECATION") s.layoutAlgorithm = WebSettings.LayoutAlgorithm.NARROW_COLUMNS
            s.javaScriptCanOpenWindowsAutomatically = true
            s.mediaPlaybackRequiresUserGesture = false
            s.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW // 0 – WTR fetch http from https
            s.setGeolocationEnabled(false)
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                try { s.safeBrowsingEnabled = true } catch (_: Exception) {}
            }
            if (Prefs.desktopMode) {
                s.userAgentString = DESKTOP_UA
            }
        } catch (e: Exception) { Log.e(TAG, "WebSettings fail", e) }

        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true)

        // === Wibgar fq1:581 BlobDownloader bridge ===
        wv.addJavascriptInterface(DownloadHelper.BlobBridge(requireContext()), "BlobDownloader")
        // keep alias for old scripts
        wv.addJavascriptInterface(DownloadHelper.BlobBridge(requireContext()), "LightBlobBridge")

        wv.webViewClient = object : WebViewClient() {
            private val adHosts = setOf("doubleclick.net","googlesyndication.com","googletagmanager.com","facebook.net","adsystem","googletagservices.com")
            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                if (Prefs.adBlock) {
                    val host = request?.url?.host ?: ""
                    if (adHosts.any { host.contains(it, ignoreCase = true) }) {
                        return WebResourceResponse("text/plain","utf-8", java.io.ByteArrayInputStream("".toByteArray()))
                    }
                }
                return super.shouldInterceptRequest(view, request)
            }

            override fun onPageStarted(v: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(v, url, favicon)
                binding.progress.visibility = View.VISIBLE
                binding.progress.progress = 10
                url?.let { binding.urlBar.setText(it) }
                if (url != null) injectScripts(v, url, "document_start")
            }

            override fun onPageFinished(v: WebView?, url: String?) {
                super.onPageFinished(v, url)
                binding.progress.visibility = View.GONE
                url?.let { binding.urlBar.setText(it) }

                // Wibgar aw1:708 visibility override for youtube etc – keep for background fetch survival (WTR auto-scrape throttled if hidden)
                injectVisibilityHack(v, url)

                if (Prefs.desktopMode) injectDesktop(v)

                // Wibgar aw1:792 delay inject: need DOM ready → postDelayed 350ms
                v?.postDelayed({
                    if (url != null) injectScripts(v, url, "document_end")
                    if (url != null) injectScripts(v, url, "document_idle")
                    injectBlobHook(v)
                }, 350)
            }

            override fun shouldOverrideUrlLoading(v: WebView?, req: WebResourceRequest?): Boolean {
                // keep inside
                return false
            }
        }

        wv.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(v: WebView?, p: Int) {
                if (p < 100) { binding.progress.visibility = View.VISIBLE; binding.progress.progress = p }
                else binding.progress.visibility = View.GONE
            }

            override fun onConsoleMessage(cm: ConsoleMessage?): Boolean {
                cm?.let {
                    Log.d(TAG, "JS ${it.messageLevel()} ${it.sourceId()}:${it.lineNumber()} ${it.message()}")
                }
                return super.onConsoleMessage(cm)
            }

            // Wibgar zv1: handling target="_blank" for downloads/blobs
            override fun onCreateWindow(view: WebView?, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message?): Boolean {
                val href = view?.hitTestResult?.extra
                if (href != null) {
                    view.loadUrl(href)
                    return true
                }
                // For WebViewTransport case (Wibgar zv1)
                val newView = WebView(view!!.context)
                newView.webViewClient = WebViewClient()
                val transport = resultMsg?.obj as? WebView.WebViewTransport
                transport?.webView = newView
                resultMsg?.sendToTarget()
                return true
            }
        }

        // === Wibgar gr1 DownloadListener with blob XHR bridge ===
        wv.setDownloadListener { url, userAgent, contentDisposition, mimeType, contentLength ->
            if (url.startsWith("blob:")) {
                // Wibgar gr1:1402 XHR bridge → onBlobDownload
                val js = """
                    (function(){
                      var blobUrl="$url";
                      var mime="$mimeType";
                      var disp="$contentDisposition";
                      try{
                        var xhr=new XMLHttpRequest();
                        xhr.open('GET', blobUrl, true);
                        xhr.responseType='blob';
                        xhr.onload=function(e){
                          if(this.status==200){
                            var blob=this.response;
                            var reader=new FileReader();
                            reader.readAsDataURL(blob);
                            reader.onloadend=function(){
                              var base64data=reader.result;
                              try{ window.BlobDownloader.onBlobDownload(base64data, blob.type||mime, disp); }catch(e){ console.error('BlobDownloader fail',e)}
                              try{ window.LightBlobBridge.onBlobData(base64data, 'download', blob.type||mime); }catch(e){}
                            };
                          } else { console.error('blob XHR status', this.status); }
                        };
                        xhr.onerror=function(e){ console.error('blob XHR error', e); };
                        xhr.send();
                      }catch(e){ console.error('blob hook error', e); }
                    })();
                """.trimIndent()
                wv.evaluateJavascript(js, null)
                Toast.makeText(requireContext(), "Capturing blob...", Toast.LENGTH_SHORT).show()
            } else {
                DownloadHelper.enqueue(requireContext(), url, userAgent, contentDisposition, mimeType)
            }
        }

        wv.setOnLongClickListener { v ->
            val result = (v as WebView).hitTestResult
            if (result.type == WebView.HitTestResult.SRC_ANCHOR_TYPE || result.type == WebView.HitTestResult.IMAGE_TYPE || result.type == WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE) {
                result.extra?.let { url ->
                    android.app.AlertDialog.Builder(requireContext())
                        .setTitle("Link")
                        .setMessage(url)
                        .setPositiveButton("Open") { _, _ -> wv.loadUrl(url) }
                        .setNegativeButton("Download") { _, _ -> DownloadHelper.enqueue(requireContext(), url, null, null, null) }
                        .setNeutralButton("Copy") { _, _ ->
                            val cm = requireContext().getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                            cm.setPrimaryClip(android.content.ClipData.newPlainText("url", url))
                            Toast.makeText(requireContext(), "Copied", Toast.LENGTH_SHORT).show()
                        }.show()
                    return@setOnLongClickListener true
                }
            }
            false
        }

        binding.btnGo.setOnClickListener { loadFromBar() }
        binding.urlBar.setOnEditorActionListener { _, id, _ ->
            if (id == EditorInfo.IME_ACTION_GO || id == EditorInfo.IME_ACTION_SEARCH) { loadFromBar(); true } else false
        }
        binding.btnBack.setOnClickListener { if (wv.canGoBack()) wv.goBack() }
        binding.btnForward.setOnClickListener { if (wv.canGoForward()) wv.goForward() }
        binding.btnRefresh.setOnClickListener { wv.reload() }

        val start = pendingUrl?.also { pendingUrl = null } ?: Prefs.homePage
        if (savedInstanceState == null) {
            val headers = mapOf("X-Requested-With" to "")
            wv.loadUrl(start, headers)
        }
    }

    // Wibgar aw1:708
    private fun injectVisibilityHack(v: WebView?, url: String?) {
        if (v == null || url == null) return
        val lower = url.lowercase()
        if (!lower.contains("youtube.com") && !lower.contains("youtu.be") && !lower.contains("soundcloud.com") && !lower.contains("wtr-lab.com")) {
            // still inject for WTR to prevent background throttle during long scrape
            // WTR long fetch loop suspends if document.hidden -> inject anyway for wtr-lab
            if (!lower.contains("wtr-lab.com")) return
        }
        val js = """
            (function() {
                if (window.__visibility_override_hooked) return;
                window.__visibility_override_hooked = true;
                try {
                    Object.defineProperty(document, 'visibilityState', { value: 'visible', writable: false, configurable: true });
                    Object.defineProperty(document, 'hidden', { value: false, writable: false, configurable: true });
                    document.dispatchEvent(new Event('visibilitychange'));
                    document.dispatchEvent(new Event('webkitvisibilitychange'));
                    const originalAddEventListener = document.addEventListener;
                    document.addEventListener = function(type, listener, options) {
                        if (type === 'visibilitychange' || type === 'webkitvisibilitychange') {
                            return;
                        }
                        return originalAddEventListener.apply(this, arguments);
                    };
                } catch(e) { console.error(e); }
            })();
        """.trimIndent()
        v.evaluateJavascript(js, null)
    }

    private fun injectDesktop(v: WebView?) {
        if (v == null) return
        val js = """
            (function() {
                'use strict';
                function forceDesktopView() {
                    let viewport = document.querySelector('meta[name="viewport"]');
                    if (!viewport) {
                        viewport = document.createElement('meta');
                        viewport.name = 'viewport';
                        document.head.appendChild(viewport);
                    }
                    viewport.content = 'width=1280, initial-scale=0.8, minimum-scale=0.1, maximum-scale=5.0, user-scalable=yes';
                }
                try {
                    const desktopAgent = "$DESKTOP_UA";
                    Object.defineProperty(navigator, 'userAgent', { get: () => desktopAgent, configurable: true });
                    Object.defineProperty(navigator, 'platform', { get: () => 'Win32', configurable: true });
                    Object.defineProperty(navigator, 'maxTouchPoints', { get: () => 0, configurable: true });
                } catch (e) { console.error("Could not override device platform info:", e); }
                forceDesktopView();
                const observer = new MutationObserver((mutations) => {
                    mutations.forEach((mutation) => {
                        if (mutation.type === 'childList') {
                            forceDesktopView();
                        }
                    });
                });
                if (document.head) {
                    observer.observe(document.head, { childList: true, subtree: true });
                } else {
                    window.addEventListener('DOMContentLoaded', () => {
                        observer.observe(document.head, { childList: true, subtree: true });
                    });
                }
            })();
        """.trimIndent()
        v.evaluateJavascript(js, null)
    }

    private fun injectBlobHook(v: WebView?) {
        if (v == null) return
        v.evaluateJavascript("(function(){ if(window.__lb_blobHook) return; window.__lb_blobHook=true; console.log('LB: blob hook installed'); })();", null)
    }

    private fun injectScripts(v: WebView?, url: String?, runAt: String) {
        if (v == null || url == null) return
        val all = try { ScriptStorage.all(requireContext()) } catch (_: Exception) { emptyList() }
        if (all.isEmpty()) return
        val matched = all.filter { it.enabled && com.lightbrowser.data.UserScript.matchesUrl(it.matches, url) }
        if (matched.isEmpty()) {
            Log.d(TAG, "No scripts match $url (have ${all.size})")
            return
        }
        val toInject = matched.filter { sc ->
            when (sc.runAt) {
                "document_start" -> runAt == "document_start"
                "document_end" -> runAt == "document_end" || runAt == "document_idle"
                else -> runAt == "document_idle" || runAt == "document_end"
            }
        }
        if (toInject.isEmpty()) {
            Log.d(TAG, "Matched ${matched.size} but none for runAt=$runAt")
            return
        }
        Log.d(TAG, "Injecting ${toInject.size} at $runAt for $url: ${toInject.map { it.name }}")
        // Wibgar aw1:1993 joins with "\n" then wraps once; we do per-script to isolate errors but same wrapper
        // Use Wibgar wrapper: (function(){ try{ code }catch(e){console.error('Custom script error:', e);}})();
        toInject.forEach { sc ->
            injectSingle(v, sc)
        }
    }

    private fun injectSingle(v: WebView, sc: com.lightbrowser.data.UserScript) {
        val needsGM = sc.grants.any { it.startsWith("GM_") } && !sc.grants.contains("none")
        val gmPolyfill = if (needsGM) """
            window.GM_info={script:{name:'${escapeJs(sc.name)}'}};
            window.GM_setValue=function(k,v){try{localStorage.setItem('GM_'+k, JSON.stringify(v))}catch(e){}};
            window.GM_getValue=function(k,d){try{var v=localStorage.getItem('GM_'+k); return v===null?d:JSON.parse(v)}catch(e){return d}};
            window.GM_addStyle=function(css){var s=document.createElement('style');s.textContent=css;document.head.appendChild(s);return s};
            window.GM_xmlhttpRequest=function(o){fetch(o.url,{method:o.method||'GET',headers:o.headers,body:o.data,credentials:'include'}).then(r=>r.text().then(t=>o.onload&&o.onload({responseText:t,status:r.status})) ).catch(e=>o.onerror&&o.onerror(e))};
            window.unsafeWindow=window;
        """.trimIndent() else ""
        val marker = "console.log('LB inject: ${escapeJs(sc.name)} @ ${escapeJs(sc.runAt)}');"
        // Wibgar style wrapper
        val code = sc.code
        val wrapped = "(function(){ try{\n$marker\n$gmPolyfill\n$code\n}catch(e){console.error('Custom script error:', e);} })();"
        v.evaluateJavascript(wrapped) { result ->
            Log.d(TAG, "inject result ${sc.name}: $result")
        }
    }

    private fun escapeJs(s: String) = s.replace("\\","\\\\").replace("'","\\'").replace("\n","\\n").replace("\"","\\\"")

    private fun loadFromBar() {
        var input = binding.urlBar.text.toString().trim()
        if (input.isEmpty()) return
        val url = when {
            input.startsWith("http://") || input.startsWith("https://") -> input
            input.contains(".") && !input.contains(" ") -> "https://$input"
            else -> "https://www.google.com/search?q=" + Uri.encode(input)
        }
        binding.webView.loadUrl(url, mapOf("X-Requested-With" to ""))
    }

    fun canGoBack() = _binding?.webView?.canGoBack() == true
    fun goBack() { _binding?.webView?.goBack() }

    override fun onPause() { super.onPause(); _binding?.webView?.onPause() }
    override fun onResume() { super.onResume(); _binding?.webView?.onResume() }
    override fun onDestroyView() {
        _binding?.webView?.destroy()
        _binding = null
        super.onDestroyView()
    }
}
private object Uri { fun encode(s: String)=java.net.URLEncoder.encode(s,"UTF-8") }
