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
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.RecyclerView
import com.lightbrowser.R
import com.lightbrowser.data.AppCtx
import com.lightbrowser.data.BookmarkStorage
import com.lightbrowser.data.DownloadHelper
import com.lightbrowser.data.HistoryStorage
import com.lightbrowser.data.Prefs
import com.lightbrowser.data.ScriptStorage
import com.lightbrowser.databinding.FragmentBrowserBinding
import com.google.android.material.bottomsheet.BottomSheetDialog

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
        // Wibgar uses HARDWARE (2) but fixed panels (WTR) flicker on some GPUs – use SOFTWARE for wtr-lab, HARDWARE otherwise
        // We'll set to SOFTWARE initially to avoid flicker, can toggle via settings if needed
        wv.setLayerType(View.LAYER_TYPE_SOFTWARE, null)

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
                url?.let {
                    binding.urlBar.setText(it)
                    // save history + update bookmark star
                    try {
                        val title = v?.title ?: it
                        HistoryStorage.add(requireContext(), it, title)
                    } catch (_: Exception) {}
                    updateBookmarkIcon(it)
                }

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
                    val src = it.sourceId() ?: ""
                    val msg = it.message() ?: ""
                    // Filter out Cloudflare Turnstile noise (challenges.cloudflare.com) – not relevant to WTR script
                    if (src.contains("challenges.cloudflare.com") || src.contains("turnstile") || msg.contains("challenges.cloudflare")) {
                        // still log to Logcat for completeness but don't spam UI console
                        Log.d(TAG, "JS [filtered Turnstile] $msg @ $src")
                        return@let
                    }
                    // Filter out obfuscated %c%d transparent spam from Turnstile
                    if (msg.contains("font-size:0;color:transparent") || msg == "NaN" || msg.trim() == "1") {
                        Log.d(TAG, "JS [filtered spam] $msg")
                        return@let
                    }
                    val full = "[${it.messageLevel()}] ${it.message()} @ ${it.sourceId()}:${it.lineNumber()}"
                    Log.d(TAG, "JS $full")
                    consoleLogs.add(full)
                    if (consoleLogs.size > 150) consoleLogs.removeAt(0)
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

        // Phase 1 fix: container now provides top inset, so toolbarCard hack removed (was doubling and unreliable).
        try {
            androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(binding.webView) { v, insets ->
                val ime = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.ime())
                val bottom = ime.bottom
                v.setPadding(v.paddingLeft, v.paddingTop, v.paddingRight, bottom)
                insets
            }
            androidx.core.view.ViewCompat.requestApplyInsets(binding.webView)
        } catch (_: Exception) {}

        binding.btnGo.setOnClickListener { loadFromBar() }
        binding.urlBar.setOnEditorActionListener { _, id, _ ->
            if (id == EditorInfo.IME_ACTION_GO || id == EditorInfo.IME_ACTION_SEARCH) { loadFromBar(); true } else false
        }
        binding.btnBack.setOnClickListener { if (wv.canGoBack()) wv.goBack() }
        binding.btnForward.setOnClickListener { if (wv.canGoForward()) wv.goForward() }
        binding.btnMore.setOnClickListener { showMoreMenuSlideIn(it) }

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
        // Fix flicker: don't use MutationObserver that constantly resets viewport on wtr-lab (causes fixed panel reflow)
        // Just set once, no observer
        val js = """
            (function() {
                try {
                    let viewport = document.querySelector('meta[name="viewport"]');
                    if (!viewport) {
                        viewport = document.createElement('meta');
                        viewport.name = 'viewport';
                        document.head.appendChild(viewport);
                    }
                    // keep original viewport but allow zoom, don't force 1280 which breaks WTR layout and causes flicker
                    if (!viewport.content.includes('1280')) {
                        viewport.content = 'width=device-width, initial-scale=1.0, maximum-scale=5.0, user-scalable=yes';
                    }
                    const desktopAgent = "$DESKTOP_UA";
                    Object.defineProperty(navigator, 'userAgent', { get: () => desktopAgent, configurable: true });
                    Object.defineProperty(navigator, 'platform', { get: () => 'Win32', configurable: true });
                    Object.defineProperty(navigator, 'maxTouchPoints', { get: () => 0, configurable: true });
                } catch (e) { console.error(e); }
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
        try { Toast.makeText(requireContext(), lastInjectInfo.take(120), Toast.LENGTH_SHORT).show() } catch (_: Exception) {}
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

    fun runJs(code: String, callback: (String?) -> Unit = {}) {
        try { _binding?.webView?.evaluateJavascript(code, callback) } catch (_: Exception) {}
    }

    private fun escapeJs(s: String) = s.replace("\\","\\\\").replace("'","\\'").replace("\n","\\n").replace("\"","\\\"")

    private fun showTestDialog() {
        try {
            val ctx = requireContext()
            val wv = _binding?.webView ?: return
            val currentUrl = try { wv.url ?: binding.urlBar.text.toString() } catch (_: Exception) { binding.urlBar.text.toString() }
            val all = try { ScriptStorage.all(ctx) } catch (_: Exception) { emptyList() }
            val matched = all.filter { it.enabled && com.lightbrowser.data.UserScript.matchesUrl(it.matches, currentUrl) }

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
                setTextColor(android.graphics.Color.parseColor("#FFE0E0E0"))
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

            addTitle("Run custom JS (manual test)")
            val etJs = android.widget.EditText(ctx).apply {
                hint = "e.g. document.title"
                setText("document.getElementById('wtr-panel') ? 'WTR panel FOUND' : 'WTR panel NOT found – script not injected / error'")
                setTextColor(android.graphics.Color.WHITE)
                textSize = 11f
            }
            container.addView(etJs)

            // --- buttons built BEFORE dialog creation to avoid crash after show() ---
            val btnReinject = com.google.android.material.button.MaterialButton(ctx).apply { text = "🔄 Re-inject NOW"; textSize = 11f }
            val btnClear = com.google.android.material.button.MaterialButton(ctx).apply { text = "🧹 Clear logs"; textSize = 11f }
            val btnCopy = com.google.android.material.button.MaterialButton(ctx).apply { text = "📋 Copy logs"; textSize = 11f }
            val btnRun = com.google.android.material.button.MaterialButton(ctx).apply { text = "▶ Run custom JS"; textSize = 11f }
            val btnCheck = com.google.android.material.button.MaterialButton(ctx).apply { text = "🔍 Check WTR panel"; textSize = 11f }
            val btnFetch = com.google.android.material.button.MaterialButton(ctx).apply { text = "🌐 Test fetch"; textSize = 11f }
            val btnForce = com.google.android.material.button.MaterialButton(ctx).apply { text = "⚡ Force inject (ignore @match)"; textSize = 11f }

            container.addView(btnReinject)
            container.addView(btnForce)
            container.addView(btnClear)
            container.addView(btnCopy)
            container.addView(btnRun)
            container.addView(btnCheck)
            container.addView(btnFetch)

            val scroll = android.widget.ScrollView(ctx).apply { addView(container) }

            val dlg = android.app.AlertDialog.Builder(ctx)
                .setTitle("🧪 Script Tester")
                .setView(scroll)
                .setPositiveButton("Close", null)
                .create()
            dlg.show()

            btnReinject.setOnClickListener {
                try {
                    val url = wv.url ?: currentUrl
                    lastInjectInfo = "Manual re-inject at ${java.text.SimpleDateFormat("HH:mm:ss").format(java.util.Date())} for $url"
                    injectScripts(wv, url, "document_idle")
                    logsTv.text = "Triggered inject for $url\nCheck logs after 1s…"
                    wv.postDelayed({
                        logsTv.text = consoleLogs.takeLast(60).joinToString("\n").ifEmpty { "Still no logs – check if script matched URL.\nMatched: ${matched.map { it.name }}" }
                    }, 900)
                } catch (e: Exception) { Toast.makeText(ctx, "Re-inject error: ${e.message}", Toast.LENGTH_LONG).show() }
            }
            btnClear.setOnClickListener {
                consoleLogs.clear()
                logsTv.text = "Cleared"
                lastInjectInfo = "Logs cleared"
            }
            btnCopy.setOnClickListener {
                try {
                    val cm = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("logs", consoleLogs.joinToString("\n")))
                    Toast.makeText(ctx, "Copied ${consoleLogs.size} lines", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) { Toast.makeText(ctx, e.message, Toast.LENGTH_LONG).show() }
            }
            btnRun.setOnClickListener {
                val code = etJs.text.toString()
                if (code.isBlank()) return@setOnClickListener
                try {
                    wv.evaluateJavascript(code) { res ->
                        val out = "Result: $res"
                        consoleLogs.add(out)
                        logsTv.text = consoleLogs.takeLast(60).joinToString("\n")
                        Toast.makeText(ctx, out.take(200), Toast.LENGTH_LONG).show()
                    }
                } catch (e: Exception) { Toast.makeText(ctx, "JS error: ${e.message}", Toast.LENGTH_LONG).show() }
            }
            btnCheck.setOnClickListener {
                try {
                    wv.evaluateJavascript("(function(){ var p=document.getElementById('wtr-panel'); return p ? 'FOUND: '+p.outerHTML.slice(0,220) : 'NOT FOUND'; })();") { res ->
                        val msg = "WTR check: $res"
                        consoleLogs.add(msg)
                        logsTv.text = "$msg\n" + consoleLogs.takeLast(40).joinToString("\n")
                    }
                } catch (e: Exception) { Toast.makeText(ctx, e.message, Toast.LENGTH_LONG).show() }
            }
            btnFetch.setOnClickListener {
                try {
                    wv.evaluateJavascript("fetch('https://wtr-lab.com/api/chapters/test',{method:'GET',credentials:'include'}).then(r=>r.text().then(t=> 'fetch ok '+r.status+' '+t.slice(0,120))).catch(e=>'fetch err '+e);") { res ->
                        logsTv.text = "Fetch test callback: $res\n" + consoleLogs.takeLast(40).joinToString("\n")
                    }
                    wv.evaluateJavascript("fetch('https://wtr-lab.com/api/chapters/test').then(r=>console.log('fetch then '+r.status)).catch(e=>console.error('fetch catch '+e)); console.log('fetch sent');", null)
                } catch (e: Exception) { Toast.makeText(ctx, e.message, Toast.LENGTH_LONG).show() }
            }
            btnForce.setOnClickListener {
                try {
                    val allEnabled = all.filter { it.enabled }
                    if (allEnabled.isEmpty()) { Toast.makeText(ctx, "No enabled scripts", Toast.LENGTH_SHORT).show(); return@setOnClickListener }
                    var count = 0
                    allEnabled.forEach { sc ->
                        injectSingle(wv, sc)
                        count++
                    }
                    val msg = "Force injected $count script(s) ignoring @match"
                    consoleLogs.add(msg)
                    logsTv.text = "$msg\n" + consoleLogs.takeLast(60).joinToString("\n")
                    Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
                } catch (e: Exception) { Toast.makeText(ctx, "Force inject error: ${e.message}", Toast.LENGTH_LONG).show() }
            }
        } catch (e: Exception) {
            Log.e(TAG, "showTestDialog crash", e)
            try { Toast.makeText(requireContext(), "Tester error: ${e.message}", Toast.LENGTH_LONG).show() } catch (_: Exception) {}
        }
    }

    private fun showMoreMenuSlideIn(anchor: View) {
        val ctx = requireContext()
        
        // Inflate the slide-in menu layout
        val menuView = LayoutInflater.from(ctx).inflate(R.layout.slidein_browser_menu, null)
        
        // Get references
        val overlay = menuView.findViewById<View>(R.id.menuOverlay)
        val panel = menuView.findViewById<LinearLayout>(R.id.menuPanel)
        val recycler = menuView.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.menuRecycler)
        
        // Add to root view (activity's decor view)
        val decorView = activity?.window?.decorView?.rootView as? ViewGroup
        decorView?.addView(menuView)
        
        // Setup RecyclerView
        recycler.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(ctx)
        
        val menuItems = listOf(
            MenuItemData("↻", "Refresh", R.drawable.ic_refresh, { binding.webView.reload(); closeSlideInMenu(panel, overlay, decorView) }),
            MenuItemData("⭐", "Bookmark this page", R.drawable.ic_bookmark, { toggleBookmark(); closeSlideInMenu(panel, overlay, decorView) }),
            MenuItemData("🧪", "Tester", R.drawable.ic_bug_report, { showTestDialog(); closeSlideInMenu(panel, overlay, decorView) }),
            MenuItemData("📜", "Scripts", R.drawable.ic_code, { showScriptsDialog(); closeSlideInMenu(panel, overlay, decorView) }),
            MenuItemData("⬇️", "Downloads", R.drawable.ic_download, { showDownloadsDialog(); closeSlideInMenu(panel, overlay, decorView) }),
            MenuItemData("⚙️", "Settings", R.drawable.ic_settings, { showSettingsDialog(); closeSlideInMenu(panel, overlay, decorView) }),
            MenuItemData("🕘", "History", R.drawable.ic_history, { showHistoryDialog(); closeSlideInMenu(panel, overlay, decorView) }),
            MenuItemData("⭐", "Bookmarks", R.drawable.ic_bookmark, { showBookmarksDialog(); closeSlideInMenu(panel, overlay, decorView) }),
            MenuItemData("🖥️", "Desktop: ${if (Prefs.desktopMode) "ON" else "OFF"}", R.drawable.ic_desktop, { 
                Prefs.desktopMode = !Prefs.desktopMode
                Toast.makeText(ctx, if (Prefs.desktopMode) "Desktop ON – reload" else "Desktop OFF – reload", Toast.LENGTH_SHORT).show()
                binding.webView.reload()
                closeSlideInMenu(panel, overlay, decorView)
            }),
            MenuItemData("🧹", "Clear cache", R.drawable.ic_clear, { 
                try {
                    CookieManager.getInstance().removeAllCookies(null)
                    android.webkit.WebStorage.getInstance().deleteAllData()
                    ctx.cacheDir.deleteRecursively()
                    Toast.makeText(ctx, "Cache cleared", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) { Toast.makeText(ctx, e.message, Toast.LENGTH_LONG).show() }
                closeSlideInMenu(panel, overlay, decorView)
            })
        )
        
        recycler.adapter = object : androidx.recyclerview.widget.RecyclerView.Adapter<MenuViewHolder>() {
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MenuViewHolder {
                val v = LayoutInflater.from(parent.context).inflate(R.layout.item_menu, parent, false)
                return MenuViewHolder(v)
            }
            override fun onBindViewHolder(holder: MenuViewHolder, position: Int) {
                val item = menuItems[position]
                holder.icon.setImageResource(item.iconRes)
                holder.title.text = item.title
                holder.itemView.setOnClickListener {
                    item.action.invoke()
                }
            }
            override fun getItemCount() = menuItems.size
        }
        
        // Animate in
        animateSlideIn(panel, overlay)
        
        // Close on overlay click
        overlay.setOnClickListener { closeSlideInMenu(panel, overlay, decorView) }
    }
    
    private fun animateSlideIn(panel: LinearLayout, overlay: View) {
        overlay.visibility = View.VISIBLE
        overlay.animate().alpha(1f).setDuration(200).start()
        panel.animate().translationX(0f).setDuration(250).setInterpolator(android.view.animation.DecelerateInterpolator()).start()
    }
    
    private fun closeSlideInMenu(panel: LinearLayout, overlay: View, decorView: ViewGroup?) {
        panel.animate()
            .translationX(panel.width.toFloat())
            .setDuration(200)
            .setInterpolator(android.view.animation.AccelerateInterpolator())
            .withEndAction {
                decorView?.removeView(panel.parent as? View)
            }
            .start()
        overlay.animate()
            .alpha(0f)
            .setDuration(200)
            .withEndAction {
                overlay.visibility = View.GONE
            }
            .start()
    }

    private fun showMoreMenuLegacy(anchor: View) {
        try {
            val ctx = requireContext()
            val items = arrayOf("↻ Refresh", "⭐ Bookmark this page", "🧪 Tester", "📜 Scripts", "⬇️ Downloads", "⚙️ Settings", "🕘 History", "⭐ Bookmarks", "🖥️ Desktop: ${if (Prefs.desktopMode) "ON" else "OFF"}", "🧹 Clear cache")
            val dlg = com.google.android.material.dialog.MaterialAlertDialogBuilder(ctx)
                .setTitle("Menu")
                .setItems(items) { _, which ->
                    when (which) {
                        0 -> binding.webView.reload()
                        1 -> toggleBookmark()
                        2 -> showTestDialog()
                        3 -> showScriptsDialog()
                        4 -> showDownloadsDialog()
                        5 -> showSettingsDialog()
                        6 -> showHistoryDialog()
                        7 -> showBookmarksDialog()
                        8 -> {
                            Prefs.desktopMode = !Prefs.desktopMode
                            Toast.makeText(ctx, if (Prefs.desktopMode) "Desktop ON – reload" else "Desktop OFF – reload", Toast.LENGTH_SHORT).show()
                            binding.webView.reload()
                        }
                        9 -> {
                            try {
                                CookieManager.getInstance().removeAllCookies(null)
                                android.webkit.WebStorage.getInstance().deleteAllData()
                                ctx.cacheDir.deleteRecursively()
                                Toast.makeText(ctx, "Cache cleared", Toast.LENGTH_SHORT).show()
                            } catch (e: Exception) { Toast.makeText(ctx, e.message, Toast.LENGTH_LONG).show() }
                        }
                    }
                }
                .create()
            try { dlg.window?.setBackgroundDrawableResource(R.color.surface) } catch (_: Exception) {}
            dlg.show()
        } catch (e: Exception) { Toast.makeText(requireContext(), e.message, Toast.LENGTH_LONG).show() }
    }

    data class MenuItemData(
        val iconPrefix: String,
        val title: String,
        val iconRes: Int,
        val action: () -> Unit
    )

    class MenuViewHolder(view: View) : androidx.recyclerview.widget.RecyclerView.ViewHolder(view) {
        val icon: ImageView = view.findViewById(R.id.menuIcon)
        val title: TextView = view.findViewById(R.id.menuTitle)
    }

    private fun showScriptsDialog() {
        try { (activity as? com.lightbrowser.MainActivity)?.switchToTab(R.id.nav_scripts) } catch (_: Exception) {
            Toast.makeText(requireContext(), "Scripts", Toast.LENGTH_SHORT).show()
        }
    }
    private fun showDownloadsDialog() {
        try { (activity as? com.lightbrowser.MainActivity)?.switchToTab(R.id.nav_downloads) } catch (_: Exception) {
            Toast.makeText(requireContext(), "Downloads", Toast.LENGTH_SHORT).show()
        }
    }
    private fun showSettingsDialog() {
        try { (activity as? com.lightbrowser.MainActivity)?.switchToTab(R.id.nav_settings) } catch (_: Exception) {}
    }

    // Public for MainActivity to load url without recreation (cache retain)
    fun loadUrl(url: String) {
        try {
            _binding?.webView?.loadUrl(url, mapOf("X-Requested-With" to ""))
            _binding?.urlBar?.setText(url)
        } catch (_: Exception) {}
    }

    private fun updateBookmarkIcon(url: String) {
        // toolbar bookmark star removed in Material3 clean-up – now in 3-dot menu, just log
        try {
            val marked = BookmarkStorage.isBookmarked(requireContext(), url)
            Log.d(TAG, "bookmark $url marked=$marked")
        } catch (_: Exception) {}
    }

    private fun toggleBookmark() {
        try {
            val wv = _binding?.webView ?: return
            val url = wv.url ?: binding.urlBar.text.toString()
            if (url.isBlank() || url.startsWith("about:")) return
            val title = wv.title ?: url
            val nowMarked = BookmarkStorage.toggle(requireContext(), url, title)
            Toast.makeText(requireContext(), if (nowMarked) "★ Bookmarked" else "☆ Removed", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) { Toast.makeText(requireContext(), e.message, Toast.LENGTH_SHORT).show() }
    }

    private fun showHistoryDialog() {
        try {
            val ctx = requireContext()
            val list = HistoryStorage.all(ctx)
            if (list.isEmpty()) { Toast.makeText(ctx, "No history yet", Toast.LENGTH_SHORT).show(); return }
            val items = list.map { "${it.title}\n${it.url} (${java.text.SimpleDateFormat("MM-dd HH:mm").format(java.util.Date(it.time))})" }.toTypedArray()
            android.app.AlertDialog.Builder(ctx)
                .setTitle("History (${list.size})")
                .setItems(items) { _, which ->
                    val entry = list[which]
                    loadUrl(entry.url)
                }
                .setPositiveButton("Close", null)
                .setNeutralButton("Clear") { _, _ ->
                    HistoryStorage.clear(ctx)
                    Toast.makeText(ctx, "History cleared", Toast.LENGTH_SHORT).show()
                }
                .show()
        } catch (e: Exception) { Toast.makeText(requireContext(), e.message, Toast.LENGTH_LONG).show() }
    }

    private fun showBookmarksDialog() {
        try {
            val ctx = requireContext()
            val list = BookmarkStorage.all(ctx)
            if (list.isEmpty()) { Toast.makeText(ctx, "No bookmarks – tap ☆ to add", Toast.LENGTH_SHORT).show(); return }
            val items = list.map { "${it.title}\n${it.url}" }.toTypedArray()
            android.app.AlertDialog.Builder(ctx)
                .setTitle("Bookmarks (${list.size}) – long-press History for this")
                .setItems(items) { _, which -> loadUrl(list[which].url) }
                .setPositiveButton("Close", null)
                .setNeutralButton("Clear") { _, _ ->
                    BookmarkStorage.clear(ctx)
                    updateBookmarkIcon(binding.webView.url ?: "")
                    Toast.makeText(ctx, "Bookmarks cleared", Toast.LENGTH_SHORT).show()
                }
                .show()
        } catch (e: Exception) { Toast.makeText(requireContext(), e.message, Toast.LENGTH_LONG).show() }
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (hidden) _binding?.webView?.onPause() else _binding?.webView?.onResume()
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
