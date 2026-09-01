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

    private val consoleLogs = mutableListOf<String>()
    private var lastInjectInfo = "No inject yet"

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
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                val sw = android.webkit.ServiceWorkerController.getInstance()
                sw.setServiceWorkerClient(object : android.webkit.ServiceWorkerClient() {
                    override fun shouldInterceptRequest(request: WebResourceRequest): WebResourceResponse? = null
                })
                sw.serviceWorkerWebSettings.apply {
                    allowContentAccess = true
                    allowFileAccess = true
                }
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
            try { @Suppress("DEPRECATION") s.databasePath = requireContext().getDir("databases", 0).path } catch (_: Exception) {}
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
                    val msg = "${it.messageLevel()} ${it.sourceId()}:${it.lineNumber()} ${it.message()}"
                    Log.d(TAG, "JS $msg")
                    consoleLogs.add("[${it.messageLevel()}] ${it.message()} @ ${it.sourceId()}:${it.lineNumber()}")
                    if (consoleLogs.size > 120) consoleLogs.removeAt(0)
                    // show error toast for quick feedback
                    if (it.messageLevel() == ConsoleMessage.MessageLevel.ERROR) {
                        // don't spam; just log
                    }
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
        binding.btnTest.setOnClickListener { showTestDialog() }

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
            val msg = "No scripts match $url (have ${all.size}, patterns=${all.map { it.matches }})"
            Log.d(TAG, msg)
            lastInjectInfo = msg
            consoleLogs.add(msg)
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
        lastInjectInfo = "Inject ${toInject.size} @ $runAt for $url: ${toInject.joinToString(","){it.name}} at ${java.text.SimpleDateFormat("HH:mm:ss").format(java.util.Date())}"
        Log.d(TAG, lastInjectInfo)
        consoleLogs.add(lastInjectInfo)
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

    private fun showTestDialog() {
        val ctx = requireContext()
        val wv = binding.webView
        val currentUrl = wv.url ?: binding.urlBar.text.toString()
        val all = try { ScriptStorage.all(ctx) } catch (_: Exception) { emptyList() }
        val matched = all.filter { it.enabled && com.lightbrowser.data.UserScript.matchesUrl(it.matches, currentUrl) }

        val scroll = android.widget.ScrollView(ctx)
        val container = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(32, 24, 32, 24)
        }

        fun addTitle(t: String) {
            val tv = android.widget.TextView(ctx).apply {
                text = t; setTextColor(android.graphics.Color.parseColor("#FFC084FC"))
                textSize = 12f; setTypeface(null, android.graphics.Typeface.BOLD)
                setPadding(0, 16, 0, 6)
            }
            container.addView(tv)
        }
        fun addText(t: String, mono: Boolean = false) {
            val tv = android.widget.TextView(ctx).apply {
                text = t; setTextColor(android.graphics.Color.parseColor("#FFC8CDF3"))
                textSize = 11f
                if (mono) typeface = android.graphics.Typeface.MONOSPACE
                setTextIsSelectable(true)
            }
            container.addView(tv)
        }
        fun addBtn(label: String, onClick: () -> Unit) {
            val b = com.google.android.material.button.MaterialButton(ctx).apply {
                text = label; textSize = 11f
            }
            b.setOnClickListener { onClick() }
            container.addView(b)
        }

        addTitle("Current URL")
        addText(currentUrl, true)
        addTitle("Scripts (${all.size} total, ${matched.size} matched)")
        if (all.isEmpty()) addText("No scripts saved. Go to Scripts tab → + Add and paste WTR script.")
        else {
            all.forEach { sc ->
                val isMatched = matched.contains(sc)
                val mark = if (!sc.enabled) "⭕ disabled" else if (isMatched) "✅ matched" else "❌ no-match"
                addText("• ${sc.name} [$mark] runAt=${sc.runAt} matches=${sc.matches.joinToString(",").ifEmpty { "<all_urls>" }}")
            }
        }
        addTitle("Last inject info")
        addText(lastInjectInfo, true)

        addTitle("Console logs (${consoleLogs.size})")
        val logsTv = android.widget.TextView(ctx).apply {
            text = if (consoleLogs.isEmpty()) "No logs yet. Try Re-inject." else consoleLogs.takeLast(60).joinToString("\n")
            setTextColor(android.graphics.Color.parseColor("#FF99FFFFFF"))
            textSize = 10f; typeface = android.graphics.Typeface.MONOSPACE
            setTextIsSelectable(true)
            setPadding(12, 12, 12, 12)
            setBackgroundColor(android.graphics.Color.parseColor("#FF0D0F1A"))
        }
        val logScroll = android.widget.ScrollView(ctx).apply {
            layoutParams = android.widget.LinearLayout.LayoutParams(android.widget.LinearLayout.LayoutParams.MATCH_PARENT, 420)
            addView(logsTv)
        }
        container.addView(logScroll)

        // custom JS input
        addTitle("Run custom JS (manual test)")
        val etJs = android.widget.EditText(ctx).apply {
            hint = "e.g. document.getElementById('wtr-panel') ? 'found' : 'NOT found'"
            setText("document.getElementById('wtr-panel') ? 'WTR panel FOUND' : 'WTR panel NOT found – script not injected / error'")
            setTextColor(android.graphics.Color.WHITE)
            textSize = 11f
        }
        container.addView(etJs)

        scroll.addView(container)

        val dlg = android.app.AlertDialog.Builder(ctx)
            .setTitle("🧪 Script Tester")
            .setView(scroll)
            .setPositiveButton("Close", null)
            .create()

        // add bottom actions after show so we can keep dialog open
        dlg.setOnShowListener {
            // we will add extra buttons via container, not dialog buttons, to keep it open
        }
        dlg.show()

        // programmatically add action buttons inside container after logs
        // Note: we already added container, now add functional buttons
        // Need to add after the scroll view – we already added logs, now add buttons below
        // To avoid rebuild, add now:
        addBtn("🔄 Re-inject NOW (document_idle)") {
            val url = wv.url ?: currentUrl
            lastInjectInfo = "Manual re-inject at ${java.text.SimpleDateFormat("HH:mm:ss").format(java.util.Date())} for $url"
            injectScripts(wv, url, "document_idle")
            logsTv.text = "Triggered inject for $url\nCheck logs after 1s…"
            wv.postDelayed({
                logsTv.text = consoleLogs.takeLast(60).joinToString("\n").ifEmpty { "Still no logs – check if script matched URL.\nMatched: ${matched.map { it.name }}" }
            }, 800)
        }
        addBtn("🧹 Clear logs") {
            consoleLogs.clear()
            logsTv.text = "Cleared"
            lastInjectInfo = "Logs cleared"
        }
        addBtn("📋 Copy logs") {
            val cm = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            cm.setPrimaryClip(android.content.ClipData.newPlainText("logs", consoleLogs.joinToString("\n")))
            Toast.makeText(ctx, "Copied ${consoleLogs.size} lines", Toast.LENGTH_SHORT).show()
        }
        addBtn("▶ Run custom JS") {
            val code = etJs.text.toString()
            if (code.isBlank()) return@addBtn
            wv.evaluateJavascript(code) { res ->
                val out = "Result: $res"
                consoleLogs.add(out)
                logsTv.text = consoleLogs.takeLast(60).joinToString("\n")
                Toast.makeText(ctx, out.take(200), Toast.LENGTH_LONG).show()
            }
        }
        addBtn("🔍 Check WTR panel") {
            wv.evaluateJavascript("(function(){ var p=document.getElementById('wtr-panel'); return p ? 'FOUND: '+p.outerHTML.slice(0,200) : 'NOT FOUND'; })();") { res ->
                logsTv.text = "WTR check: $res\n" + consoleLogs.takeLast(40).joinToString("\n")
                consoleLogs.add("WTR check: $res")
            }
        }
        addBtn("🌐 Test fetch permission") {
            wv.evaluateJavascript("fetch('https://wtr-lab.com/api/chapters/test',{method:'GET',credentials:'include'}).then(r=>r.text().then(t=> 'fetch ok '+r.status+' '+t.slice(0,120))).catch(e=>'fetch err '+e);") { res ->
                logsTv.text = "Fetch test callback: $res\n" + consoleLogs.takeLast(40).joinToString("\n")
            }
            // also do evaluate with console
            wv.evaluateJavascript("fetch('https://wtr-lab.com/api/chapters/test').then(r=>console.log('fetch then '+r.status)).catch(e=>console.error('fetch catch '+e)); console.log('fetch sent');", null)
        }
    }

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
