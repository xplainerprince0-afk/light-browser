package com.lightbrowser.ui

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.os.Bundle
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
        s.allowFileAccess = false
        s.allowContentAccess = true
        s.useWideViewPort = true
        s.loadWithOverviewMode = true
        s.builtInZoomControls = true
        s.displayZoomControls = false
        s.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        s.cacheMode = WebSettings.LOAD_DEFAULT
        s.mediaPlaybackRequiresUserGesture = false
        s.javaScriptCanOpenWindowsAutomatically = true
        if (Prefs.desktopMode) {
            s.userAgentString = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"
            s.useWideViewPort = true
        }

        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true)

        // blob bridge
        wv.addJavascriptInterface(DownloadHelper.BlobBridge(requireContext()), "LightBlobBridge")

        wv.webViewClient = object : WebViewClient() {
            // adblock simple host list
            private val adHosts = setOf("doubleclick.net","googlesyndication.com","googletagmanager.com","facebook.net","adsystem")
            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): android.webkit.WebResourceResponse? {
                if (Prefs.adBlock) {
                    val host = request?.url?.host ?: ""
                    if (adHosts.any { host.contains(it) }) {
                        return android.webkit.WebResourceResponse("text/plain","utf-8", java.io.ByteArrayInputStream("".toByteArray()))
                    }
                }
                return super.shouldInterceptRequest(view, request)
            }

            override fun onPageStarted(v: WebView?, url: String?, favicon: Bitmap?) {
                binding.progress.visibility = View.VISIBLE
                binding.progress.progress = 10
                url?.let { binding.urlBar.setText(it) }
                // document_start scripts
                injectScripts(v, url, "document_start")
            }
            override fun onPageFinished(v: WebView?, url: String?) {
                binding.progress.visibility = View.GONE
                url?.let { binding.urlBar.setText(it) }
                if (Prefs.desktopMode) injectDesktop(v)
                injectScripts(v, url, "document_idle")
                injectBlobHook(v)
            }
            override fun shouldOverrideUrlLoading(v: WebView?, req: WebResourceRequest?): Boolean { return false }
        }
        wv.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(v: WebView?, p: Int) {
                if (p < 100) { binding.progress.visibility = View.VISIBLE; binding.progress.progress = p }
                else binding.progress.visibility = View.GONE
            }
        }
        wv.setDownloadListener { url, ua, cd, mime, _ ->
            if (url.startsWith("blob:")) {
                Toast.makeText(requireContext(), "Blob download – capturing...", Toast.LENGTH_SHORT).show()
                wv.evaluateJavascript("""
                    (function(){
                      var url="$url";
                      fetch(url).then(r=>r.blob()).then(b=>{
                        var r=new FileReader();
                        r.onload=function(){ LightBlobBridge.onBlobData(r.result, "download.bin", b.type); };
                        r.readAsDataURL(b);
                      }).catch(e=>{ console.log(e)});
                    })();
                """.trimIndent(), null)
            } else {
                DownloadHelper.enqueue(requireContext(), url, ua, cd, mime)
            }
        }

        wv.setOnLongClickListener { v ->
            val result = (v as WebView).hitTestResult
            if (result.type == WebView.HitTestResult.SRC_ANCHOR_TYPE || result.type == WebView.HitTestResult.IMAGE_TYPE) {
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
              }catch(e){}
            })();
        """.trimIndent()
        v.evaluateJavascript(js, null)
    }

    private fun injectBlobHook(v: WebView?) {
        if (v == null) return
        // hook blob clicks for download
        val js = """
            (function(){
              if(window.__lb_blobHook) return; window.__lb_blobHook=true;
              document.addEventListener('click', function(e){
                var a=e.target.closest('a');
                if(a && a.href && a.href.startsWith('blob:')){ /* let download listener handle */ }
              }, true);
            })();
        """.trimIndent()
        v.evaluateJavascript(js, null)
    }

    private fun injectScripts(v: WebView?, url: String?, runAt: String) {
        if (v == null || url == null) return
        val list = try { ScriptStorage.enabledForUrl(requireContext(), url) } catch (_: Exception) { emptyList() }
        if (list.isEmpty()) return
        val toInject = list.filter { it.runAt == runAt || (runAt=="document_idle" && it.runAt=="document_end") || (it.runAt=="document_idle" && runAt=="document_idle") }
        if (toInject.isEmpty()) return
        // build concatenated + GM polyfill
        val gmPolyfill = """
            window.GM_info={script:{name:'LightBrowser',version:'1.1'}};
            window.GM_log=function(x){console.log(x)};
            window.GM_setValue=function(k,v){localStorage.setItem('GM_'+k, v)};
            window.GM_getValue=function(k,d){var v=localStorage.getItem('GM_'+k); return v===null?d:v};
            window.GM_addStyle=function(css){var s=document.createElement('style');s.textContent=css;document.head.appendChild(s);return s};
            window.GM_xmlhttpRequest=function(o){fetch(o.url,{method:o.method||'GET',headers:o.headers,body:o.data}).then(r=>r.text().then(t=>o.onload&&o.onload({responseText:t,status:r.status})) ).catch(e=>o.onerror&&o.onerror(e))};
            window.unsafeWindow=window;
        """.trimIndent()
        val code = toInject.joinToString("\n") { it.code }
        val wrapped = "(function(){ try{ $gmPolyfill\n$code\n }catch(e){console.error('LB script error',e)} })();"
        v.evaluateJavascript(wrapped, null)
    }

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
