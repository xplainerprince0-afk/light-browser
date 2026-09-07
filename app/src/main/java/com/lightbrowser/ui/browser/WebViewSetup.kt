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
import com.lightbrowser.data.AppCtx
import com.lightbrowser.data.BrowserProfile
import com.lightbrowser.data.DownloadHelper
import com.lightbrowser.data.Prefs
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
                override fun shouldInterceptRequest(request: WebResourceRequest): WebResourceResponse? = null
            })
            sw.serviceWorkerWebSettings.apply {
                allowContentAccess = true
                allowFileAccess = true
            }
        }
    } catch (_: Exception) {}

    val bridge = DownloadHelper.BlobBridge(app)
    try {
        wv.addJavascriptInterface(bridge, "BlobDownloader")
        wv.addJavascriptInterface(bridge, "LightBlobBridge")
    } catch (_: Exception) {}

    val adHosts = setOf(
        "doubleclick.net", "googlesyndication.com", "googletagmanager.com",
        "facebook.net", "adsystem", "googletagservices.com"
    )

    wv.webViewClient = object : WebViewClient() {
        override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
            if (Prefs.adBlock) {
                val host = request?.url?.host ?: ""
                if (adHosts.any { host.contains(it, ignoreCase = true) }) {
                    return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
                }
            }
            return super.shouldInterceptRequest(view, request)
        }

        override fun onPageStarted(v: WebView?, url: String?, favicon: Bitmap?) {
            super.onPageStarted(v, url, favicon)
            if (url != null) {
                cb.onStarted(url)
                try { cb.inject(v!!, url, "document_start") } catch (_: Exception) {}
            }
        }

        override fun onPageFinished(v: WebView?, url: String?) {
            super.onPageFinished(v, url)
            if (url != null && v != null) {
                cb.onFinished(url, v.title ?: url)
                injectVisibilityHack(v, url)
                if (Prefs.desktopMode) injectDesktop(v)
                v.postDelayed({
                    try {
                        cb.inject(v, url, "document_end")
                        cb.inject(v, url, "document_idle")
                    } catch (_: Exception) {}
                }, 350)
            }
        }

        override fun shouldOverrideUrlLoading(v: WebView?, req: WebResourceRequest?): Boolean = false
    }

    wv.webChromeClient = object : WebChromeClient() {
        override fun onProgressChanged(v: WebView?, p: Int) {
            cb.onProgress(p)
        }

        override fun onConsoleMessage(cm: ConsoleMessage?): Boolean {
            // Filter known-noisy sources so logcat stays useful
            cm?.let {
                val src = it.sourceId() ?: ""
                val msg = it.message() ?: ""
                if (src.contains("challenges.cloudflare.com") || src.contains("turnstile")) return@let
                if (msg.contains("font-size:0;color:transparent") || msg == "NaN") return@let
            }
            return super.onConsoleMessage(cm)
        }
    }

    wv.setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
        if (url.startsWith("blob:")) {
            val js = """
                (function(){
                  try{
                    var xhr=new XMLHttpRequest();
                    xhr.open('GET', "$url", true);
                    xhr.responseType='blob';
                    xhr.onload=function(){
                      if(this.status==200){
                        var reader=new FileReader();
                        reader.readAsDataURL(this.response);
                        reader.onloadend=function(){
                          try{window.BlobDownloader.onBlobDownload(reader.result, "$mimeType", "$contentDisposition");}catch(e){console.error(e);}
                        };
                      }
                    };
                    xhr.send();
                  }catch(e){console.error(e);}
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
