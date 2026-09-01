package com.lightbrowser.ui

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.os.Bundle
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
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentBrowserBinding.inflate(inflater, container, false)
        return binding.root
    }

    @SuppressLint("SetJavaScriptEnabled", "AddJavascriptInterface")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        try { AppCtx.init(requireContext()) } catch (_: Exception) {}
        val wv = binding.webView
        val s = wv.settings
        s.javaScriptEnabled = Prefs.jsEnabled
        s.domStorageEnabled = true
        s.databaseEnabled = true
        // WTR script uses Worker via blob: + indexedDB + fetch → need file access
        s.allowFileAccess = true
        s.allowContentAccess = true
        s.allowFileAccessFromFileURLs = true
        s.allowUniversalAccessFromFileURLs = true
        s.useWideViewPort = true
        s.loadWithOverviewMode = true
        s.builtInZoomControls = true
        s.displayZoomControls = false
        s.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        s.cacheMode = WebSettings.LOAD_DEFAULT
        s.mediaPlaybackRequiresUserGesture = false
        s.javaScriptCanOpenWindowsAutomatically = true
        // important for IndexedDB / localStorage on some OEMs
        try { s.setGeolocationEnabled(false) } catch (_: Exception) {}
        if (Prefs.desktopMode) {
            s.userAgentString = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"
        }

        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true)

        wv.addJavascriptInterface(DownloadHelper.BlobBridge(requireContext()), "LightBlobBridge")

        wv.webViewClient = object : WebViewClient() {
            private val adHosts = setOf("doubleclick.net","googlesyndication.com","googletagmanager.com","facebook.net","adsystem","googletagservices.com")
            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): android.webkit.WebResourceResponse? {
                if (Prefs.adBlock) {
                    val host = request?.url?.host ?: ""
                    if (adHosts.any { host.contains(it, ignoreCase = true) }) {
                        return android.webkit.WebResourceResponse("text/plain","utf-8", java.io.ByteArrayInputStream("".toByteArray()))
                    }
                }
                return super.shouldInterceptRequest(view, request)
            }

            override fun onPageStarted(v: WebView?, url: String?, favicon: Bitmap?) {
                binding.progress.visibility = View.VISIBLE
                binding.progress.progress = 10
                url?.let { binding.urlBar.setText(it) }
                if (url != null) injectScripts(v, url, "document_start")
            }
            override fun onPageFinished(v: WebView?, url: String?) {
                binding.progress.visibility = View.GONE
                url?.let { binding.urlBar.setText(it) }
                if (Prefs.desktopMode) injectDesktop(v)
                // WTR needs DOM ready; small delay ensures document.body exists
                v?.postDelayed({
                    if (url != null) injectScripts(v, url, "document_end")
                    if (url != null) injectScripts(v, url, "document_idle")
                    injectBlobHook(v)
                }, 400)
            }
            override fun shouldOverrideUrlLoading(v: WebView?, req: WebResourceRequest?): Boolean { return false }
            override fun onReceivedError(v: WebView?, req: WebResourceRequest?, err: WebResourceError?) {
                Log.e(TAG, "onReceivedError ${err?.description} url=${req?.url}")
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
                    // surface script errors to user for debugging WTR
                    if (it.messageLevel() == ConsoleMessage.MessageLevel.ERROR) {
                        // don't toast spam, just log; uncomment to debug:
                        // Toast.makeText(requireContext(), "JS: ${it.message()}", Toast.LENGTH_SHORT).show()
                    }
                }
                return super.onConsoleMessage(cm)
            }
        }
        wv.setDownloadListener { url, ua, cd, mime, _ ->
            if (url.startsWith("blob:")) {
                Toast.makeText(requireContext(), "Blob download – capturing...", Toast.LENGTH_SHORT).show()
                wv.evaluateJavascript("""
                    (function(){
                      try{
                        var url="$url";
                        fetch(url).then(r=>r.blob()).then(b=>{
                          var r=new FileReader();
                          r.onload=function(){ LightBlobBridge.onBlobData(r.result, "download.bin", b.type); };
                          r.readAsDataURL(b);
                        }).catch(e=>{ console.log("blob fetch err",e)});
                      }catch(e){ console.error(e)}
                    })();
                """.trimIndent(), null)
            } else {
                DownloadHelper.enqueue(requireContext(), url, ua, cd, mime)
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
        if (savedInstanceState == null) wv.loadUrl(start)
    }

    private fun injectDesktop(v: WebView?) {
        if (v == null) return
        val js = """
            (function(){
              try{
                let m=document.querySelector('meta[name="viewport"]');
                if(!m){m=document.createElement('meta');m.name='viewport';document.head.appendChild(m);}
                m.content='width=1280, initial-scale=0.8, minimum-scale=0.1, maximum-scale=5.0, user-scalable=yes';
                Object.defineProperty(navigator,'userAgent',{get:()=>'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36',configurable:true});
                Object.defineProperty(navigator,'platform',{get:()=>'Win32',configurable:true});
              }catch(e){console.error(e)}
            })();
        """.trimIndent()
        v.evaluateJavascript(js, null)
    }

    private fun injectBlobHook(v: WebView?) {
        if (v == null) return
        val js = """
            (function(){
              if(window.__lb_blobHook) return; window.__lb_blobHook=true;
              console.log("LB: blob hook installed");
            })();
        """.trimIndent()
        v.evaluateJavascript(js, null)
    }

    private fun injectScripts(v: WebView?, url: String?, runAt: String) {
        if (v == null || url == null) return
        val all = try { ScriptStorage.all(requireContext()) } catch (_: Exception) { emptyList() }
        if (all.isEmpty()) return
        val matched = all.filter { it.enabled && com.lightbrowser.data.UserScript.matchesUrl(it.matches, url) }
        if (matched.isEmpty()) {
            Log.d(TAG, "No scripts match $url (have ${all.size} total, patterns=${all.map { it.matches }})")
            return
        }
        val toInject = matched.filter { sc ->
            when (sc.runAt) {
                "document_start" -> runAt == "document_start"
                "document_end" -> runAt == "document_end" || runAt == "document_idle"
                else -> runAt == "document_idle" || runAt == "document_end" // document_idle scripts run at idle/end
            }
        }
        if (toInject.isEmpty()) {
            Log.d(TAG, "Matched ${matched.size} but none for runAt=$runAt url=$url")
            return
        }
        Log.d(TAG, "Injecting ${toInject.size} script(s) at $runAt for $url: ${toInject.map { it.name }}")
        toInject.forEach { sc ->
            injectSingle(v, sc, url)
        }
    }

    private fun injectSingle(v: WebView, sc: com.lightbrowser.data.UserScript, url: String) {
        // GM polyfill only if @grant asks for it (WTR has @grant none → skip)
        val needsGM = sc.grants.any { it.startsWith("GM_") } && !sc.grants.contains("none")
        val gmPolyfill = if (needsGM) """
            window.GM_info={script:{name:'${escapeJs(sc.name)}',version:'1.1'}};
            window.GM_log=function(x){console.log(x)};
            window.GM_setValue=function(k,v){try{localStorage.setItem('GM_'+k, JSON.stringify(v))}catch(e){}};
            window.GM_getValue=function(k,d){try{var v=localStorage.getItem('GM_'+k); return v===null?d:JSON.parse(v)}catch(e){return d}};
            window.GM_addStyle=function(css){var s=document.createElement('style');s.textContent=css;document.head.appendChild(s);return s};
            window.GM_xmlhttpRequest=function(o){fetch(o.url,{method:o.method||'GET',headers:o.headers,body:o.data,credentials:'include'}).then(r=>r.text().then(t=>o.onload&&o.onload({responseText:t,status:r.status,responseHeaders:r.headers}))).catch(e=>o.onerror&&o.onerror(e))};
            window.unsafeWindow=window;
        """.trimIndent() else ""

        // Use separate evaluate per script to isolate errors and avoid huge payload issues
        // Wrap in try/catch and log
        val code = sc.code
        // Escape ` and $ for Kotlin string? Already in code. For JS injection, we need to avoid breaking evaluateJavascript string.
        // evaluateJavascript takes a JS snippet, not a JSON string, so we can send raw. To be safe, we base64-encode large scripts and decode in page?
        // For WTR (~30k), raw is okay, but to be robust, use encode via evaluated string with JSON.stringify? Simpler: send raw, WebView handles.
        // Add a console marker so user can see injection.
        val marker = "console.log('LB inject: ${escapeJs(sc.name)} @ ${escapeJs(sc.runAt)} for '+location.href);"
        val wrapped = """
            (function(){
              $marker
              try{
                $gmPolyfill
                ${code}
              }catch(e){ console.error('LB script error ${escapeJs(sc.name)}', e); }
            })();
        """.trimIndent()
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
        binding.webView.loadUrl(url)
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
