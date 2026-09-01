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
import com.lightbrowser.databinding.FragmentBrowserBinding

class BrowserFragment : Fragment() {
    private var _binding: FragmentBrowserBinding? = null
    private val binding get() = _binding!!

    companion object {
        var pendingUrl: String? = null
        const val HOME = "https://www.google.com"
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentBrowserBinding.inflate(inflater, container, false)
        return binding.root
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val wv = binding.webView
        val settings = wv.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.databaseEnabled = true
        settings.allowFileAccess = false
        settings.allowContentAccess = true
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = true
        settings.builtInZoomControls = true
        settings.displayZoomControls = false
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        settings.cacheMode = WebSettings.LOAD_DEFAULT
        // minimal UA
        // settings.userAgentString = settings.userAgentString

        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true)

        wv.webViewClient = object : WebViewClient() {
            override fun onPageStarted(v: WebView?, url: String?, favicon: Bitmap?) {
                binding.progress.visibility = View.VISIBLE
                binding.progress.progress = 10
                url?.let { binding.urlBar.setText(it) }
            }
            override fun onPageFinished(v: WebView?, url: String?) {
                binding.progress.visibility = View.GONE
                url?.let { binding.urlBar.setText(it) }
                // placeholder for future userscript injection (Violentmonkey style)
                // injected after page load via evaluateJavascript - see Wibgar analysis
                // e.g. v?.evaluateJavascript("(function(){/* GM polyfill */})()", null)
            }
            override fun shouldOverrideUrlLoading(v: WebView?, req: WebResourceRequest?): Boolean {
                return false // stay inside
            }
            override fun onReceivedError(v: WebView?, req: WebResourceRequest?, err: WebResourceError?) {
                // keep minimal
            }
        }
        wv.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(v: WebView?, p: Int) {
                if (p < 100) {
                    binding.progress.visibility = View.VISIBLE
                    binding.progress.progress = p
                } else binding.progress.visibility = View.GONE
            }
        }
        wv.setDownloadListener { url, ua, cd, mime, _ ->
            Toast.makeText(requireContext(), "Download: $url", Toast.LENGTH_SHORT).show()
        }

        binding.btnGo.setOnClickListener { loadFromBar() }
        binding.urlBar.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO || actionId == EditorInfo.IME_ACTION_SEARCH) {
                loadFromBar(); true
            } else false
        }
        binding.btnBack.setOnClickListener { if (wv.canGoBack()) wv.goBack() }
        binding.btnForward.setOnClickListener { if (wv.canGoForward()) wv.goForward() }
        binding.btnRefresh.setOnClickListener { wv.reload() }

        // load initial
        val start = pendingUrl?.also { pendingUrl = null } ?: HOME
        if (savedInstanceState == null) wv.loadUrl(start)
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

// tiny Uri helper to avoid full import
private object Uri {
    fun encode(s: String) = java.net.URLEncoder.encode(s, "UTF-8")
}
