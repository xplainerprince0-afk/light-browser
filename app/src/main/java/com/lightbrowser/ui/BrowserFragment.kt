package com.lightbrowser.ui

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Message
import android.text.format.DateUtils
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AnimationUtils
import android.view.inputmethod.EditorInfo
import android.webkit.*
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.lightbrowser.MainActivity
import com.lightbrowser.R
import com.lightbrowser.data.AppCtx
import com.lightbrowser.data.BookmarkStorage
import com.lightbrowser.data.BrowserProfile
import com.lightbrowser.data.DownloadHelper
import com.lightbrowser.data.HistoryEntry
import com.lightbrowser.data.HistoryStorage
import com.lightbrowser.data.Prefs
import com.lightbrowser.data.ScriptStorage
import com.lightbrowser.databinding.FragmentBrowserBinding
import java.text.SimpleDateFormat
import java.util.*

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

    // Tab management (simple single-webview multi-url list for now)
    private val tabUrls = mutableListOf<String>()
    private var currentTabIndex = 0

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentBrowserBinding.inflate(inflater, container, false)
        return binding.root
    }

    @SuppressLint("SetJavaScriptEnabled", "AddJavascriptInterface", "ClickableViewAccessibility")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        try { AppCtx.init(requireContext()) } catch (_: Exception) {}

        // === ServiceWorker pre-config ===
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
        BrowserProfile.configure(requireContext(), wv)
        try {
            if (Prefs.desktopMode) wv.settings.userAgentString = DESKTOP_UA
        } catch (e: Exception) { Log.e(TAG, "UA fail", e) }

        wv.addJavascriptInterface(DownloadHelper.BlobBridge(requireContext()), "BlobDownloader")
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
                // Update collapsed bar domain
                url?.let {
                    updateCollapsedBar(it)
                    // keep EditText in sync even if hidden
                    binding.urlBar.setText(it)
                }
                if (url != null) injectScripts(v, url, "document_start")
            }

            override fun onPageFinished(v: WebView?, url: String?) {
                super.onPageFinished(v, url)
                binding.progress.visibility = View.GONE
                // Reload icon when page finishes
                binding.btnGo.text = "↻"
                url?.let {
                    updateCollapsedBar(it)
                    binding.urlBar.setText(it)
                    try {
                        val title = v?.title ?: it
                        HistoryStorage.add(requireContext(), it, title)
                    } catch (_: Exception) {}
                    updateBookmarkIcon(it)
                    // update tab list
                    if (currentTabIndex < tabUrls.size) tabUrls[currentTabIndex] = it
                    else { tabUrls.add(it); currentTabIndex = tabUrls.size - 1 }
                    updateTabCount()
                }
                injectVisibilityHack(v, url)
                if (Prefs.desktopMode) injectDesktop(v)
                v?.postDelayed({
                    if (url != null) injectScripts(v, url, "document_end")
                    if (url != null) injectScripts(v, url, "document_idle")
                    injectBlobHook(v)
                }, 350)
            }

            override fun shouldOverrideUrlLoading(v: WebView?, req: WebResourceRequest?): Boolean = false

            override fun onReceivedError(v: WebView?, req: WebResourceRequest?, err: WebResourceError?) {
                // Show stop icon on error
                binding.btnGo.text = "↻"
                binding.progress.visibility = View.GONE
            }
        }

        wv.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(v: WebView?, p: Int) {
                if (p < 100) {
                    binding.progress.visibility = View.VISIBLE
                    binding.progress.progress = p
                    binding.btnGo.text = "✕"  // loading → show stop
                } else {
                    binding.progress.visibility = View.GONE
                    binding.btnGo.text = "↻"
                }
            }

            override fun onConsoleMessage(cm: ConsoleMessage?): Boolean {
                cm?.let {
                    val src = it.sourceId() ?: ""
                    val msg = it.message() ?: ""
                    if (src.contains("challenges.cloudflare.com") || src.contains("turnstile") || msg.contains("challenges.cloudflare")) {
                        Log.d(TAG, "JS [filtered Turnstile] $msg @ $src"); return@let
                    }
                    if (msg.contains("font-size:0;color:transparent") || msg == "NaN" || msg.trim() == "1") {
                        Log.d(TAG, "JS [filtered spam] $msg"); return@let
                    }
                    val full = "[${it.messageLevel()}] ${it.message()} @ ${it.sourceId()}:${it.lineNumber()}"
                    Log.d(TAG, "JS $full")
                    consoleLogs.add(full)
                    if (consoleLogs.size > 150) consoleLogs.removeAt(0)
                }
                return super.onConsoleMessage(cm)
            }

            override fun onCreateWindow(view: WebView?, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message?): Boolean {
                val href = view?.hitTestResult?.extra
                if (href != null) { view.loadUrl(href); return true }
                val newView = WebView(view!!.context)
                newView.webViewClient = WebViewClient()
                val transport = resultMsg?.obj as? WebView.WebViewTransport
                transport?.webView = newView
                resultMsg?.sendToTarget()
                return true
            }
        }

        wv.setDownloadListener { url, userAgent, contentDisposition, mimeType, contentLength ->
            if (url.startsWith("blob:")) {
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

        // --- URL bar collapse/expand behaviour ---
        binding.urlCollapsed.setOnClickListener { expandUrlBar() }

        binding.urlBar.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) collapseUrlBar()
        }

        binding.urlBar.setOnEditorActionListener { _, id, _ ->
            if (id == EditorInfo.IME_ACTION_GO || id == EditorInfo.IME_ACTION_SEARCH) {
                loadFromBar()
                collapseUrlBar()
                true
            } else false
        }

        // Reload/stop on btnGo tap
        binding.btnGo.setOnClickListener {
            if (binding.btnGo.text == "✕") {
                wv.stopLoading()
                binding.btnGo.text = "↻"
                binding.progress.visibility = View.GONE
            } else {
                wv.reload()
            }
        }

        binding.btnBack.setOnClickListener { if (wv.canGoBack()) wv.goBack() }
        binding.btnForward.setOnClickListener { if (wv.canGoForward()) wv.goForward() }
        binding.btnTabCount.setOnClickListener { showTabSwitcher() }
        binding.btnMore.setOnClickListener { showMoreMenuSlideIn(it) }

        val start = pendingUrl?.also { pendingUrl = null } ?: Prefs.homePage
        if (savedInstanceState == null) {
            tabUrls.add(start)
            currentTabIndex = 0
            updateTabCount()
            wv.loadUrl(start, mapOf("X-Requested-With" to ""))
        }
    }

    // ──── URL bar collapse/expand ─────────────────────────────────────────────

    private fun updateCollapsedBar(url: String) {
        try {
            val uri = android.net.Uri.parse(url)
            val domain = uri.host ?: url
            binding.tvDomain.text = domain
            val isHttps = url.startsWith("https://")
            binding.tvLock.text = if (isHttps) "🔒" else "⚠️"
        } catch (_: Exception) {
            binding.tvDomain.text = url
        }
    }

    private fun expandUrlBar() {
        if (binding.urlBar.visibility == View.VISIBLE) return
        binding.urlCollapsed.visibility = View.GONE
        binding.urlBar.visibility = View.VISIBLE
        binding.urlBar.requestFocus()
        binding.urlBar.selectAll()
        val imm = requireContext().getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
        imm.showSoftInput(binding.urlBar, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
    }

    private fun collapseUrlBar() {
        if (binding.urlCollapsed.visibility == View.VISIBLE) return
        binding.urlBar.visibility = View.GONE
        binding.urlCollapsed.visibility = View.VISIBLE
        val imm = requireContext().getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
        imm.hideSoftInputFromWindow(binding.urlBar.windowToken, 0)
    }

    // ──── Tab management ─────────────────────────────────────────────────────

    private fun updateTabCount() {
        val count = tabUrls.size.coerceAtLeast(1)
        binding.btnTabCount.text = count.toString()
    }

    private fun showTabSwitcher() {
        val ctx = requireContext()
        val dlg = android.app.AlertDialog.Builder(ctx)
        val items = tabUrls.mapIndexed { i, url ->
            val mark = if (i == currentTabIndex) "● " else "  "
            val domain = try { android.net.Uri.parse(url).host ?: url } catch (_: Exception) { url }
            "$mark Tab ${i+1}: $domain"
        }.toTypedArray()

        android.app.AlertDialog.Builder(ctx)
            .setTitle("Open tabs (${tabUrls.size})")
            .setItems(items) { _, which ->
                currentTabIndex = which
                val url = tabUrls[which]
                binding.webView.loadUrl(url, mapOf("X-Requested-With" to ""))
            }
            .setPositiveButton("New Tab") { _, _ ->
                openNewTab(Prefs.homePage)
            }
            .setNeutralButton("Close Tab") { _, _ ->
                closeCurrentTab()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun openNewTab(url: String) {
        tabUrls.add(url)
        currentTabIndex = tabUrls.size - 1
        updateTabCount()
        binding.webView.loadUrl(url, mapOf("X-Requested-With" to ""))
    }

    private fun closeCurrentTab() {
        if (tabUrls.size <= 1) {
            Toast.makeText(requireContext(), "Can't close the last tab", Toast.LENGTH_SHORT).show()
            return
        }
        tabUrls.removeAt(currentTabIndex)
        currentTabIndex = (currentTabIndex - 1).coerceAtLeast(0)
        updateTabCount()
        binding.webView.loadUrl(tabUrls[currentTabIndex], mapOf("X-Requested-With" to ""))
    }

    // ──── History dialog (Chrome-style grouped) ───────────────────────────────

    fun showHistory() = showHistoryDialog()

    private fun showHistoryDialog() {
        try {
            val ctx = requireContext()
            val list = HistoryStorage.all(ctx)
            if (list.isEmpty()) { Toast.makeText(ctx, "No history yet", Toast.LENGTH_SHORT).show(); return }

            val dlgView = LayoutInflater.from(ctx).inflate(android.R.layout.list_content, null)
            val recycler = RecyclerView(ctx)
            recycler.layoutManager = LinearLayoutManager(ctx)

            // Group by date
            val now = System.currentTimeMillis()
            val todayStart = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY,0); set(Calendar.MINUTE,0); set(Calendar.SECOND,0); set(Calendar.MILLISECOND,0) }.timeInMillis
            val yesterdayStart = todayStart - 86400_000L
            val weekStart = todayStart - 6 * 86400_000L

            // Build sections: header strings + entries
            data class HistoryRow(val isHeader: Boolean, val headerText: String = "", val entry: HistoryEntry? = null)
            val rows = mutableListOf<HistoryRow>()
            var lastSection = ""
            for (e in list) {
                val sec = when {
                    e.time >= todayStart    -> "Today"
                    e.time >= yesterdayStart -> "Yesterday"
                    e.time >= weekStart     -> "Last 7 Days"
                    else                   -> "Older"
                }
                if (sec != lastSection) { rows.add(HistoryRow(true, sec)); lastSection = sec }
                rows.add(HistoryRow(false, entry = e))
            }

            val sdf = SimpleDateFormat("HH:mm", Locale.getDefault())

            recycler.adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
                inner class HeaderVH(v: View) : RecyclerView.ViewHolder(v)
                inner class EntryVH(v: View) : RecyclerView.ViewHolder(v)

                override fun getItemViewType(position: Int) = if (rows[position].isHeader) 0 else 1

                override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
                    return if (viewType == 0) {
                        val tv = TextView(ctx).apply {
                            textSize = 12f
                            setTextColor(0xFF94A3B8.toInt())
                            setPadding(48, 24, 16, 8)
                            setTypeface(null, android.graphics.Typeface.BOLD)
                        }
                        HeaderVH(tv)
                    } else {
                        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_history, parent, false)
                        EntryVH(v)
                    }
                }

                override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
                    val row = rows[position]
                    if (row.isHeader) {
                        (holder.itemView as TextView).text = row.headerText
                    } else {
                        val e = row.entry ?: return
                        val domain = try { android.net.Uri.parse(e.url).host ?: e.url } catch (_: Exception) { e.url }
                        holder.itemView.findViewById<TextView>(R.id.tvFavicon).text = domain.firstOrNull()?.uppercaseChar()?.toString() ?: "🌐"
                        holder.itemView.findViewById<TextView>(R.id.tvHistTitle).text = e.title.ifBlank { e.url }
                        holder.itemView.findViewById<TextView>(R.id.tvHistUrl).text = e.url
                        holder.itemView.findViewById<TextView>(R.id.tvHistTime).text = sdf.format(Date(e.time))
                        holder.itemView.setOnClickListener { loadUrl(e.url); }
                        holder.itemView.findViewById<TextView>(R.id.btnHistDelete).setOnClickListener {
                            val all = HistoryStorage.all(ctx)
                            all.removeAll { it.url == e.url }
                            HistoryStorage.saveList(ctx, all)
                            rows.removeAt(position)
                            notifyItemRemoved(position)
                        }
                    }
                }

                override fun getItemCount() = rows.size
            }

            val scrollView = android.widget.ScrollView(ctx)
            scrollView.addView(recycler)

            android.app.AlertDialog.Builder(ctx)
                .setTitle("History")
                .setView(recycler)
                .setPositiveButton("Close", null)
                .setNeutralButton("Clear All") { _, _ ->
                    HistoryStorage.clear(ctx)
                    Toast.makeText(ctx, "History cleared", Toast.LENGTH_SHORT).show()
                }
                .show()
        } catch (e: Exception) { Toast.makeText(requireContext(), e.message, Toast.LENGTH_LONG).show() }
    }

    // ──── Bookmarks ──────────────────────────────────────────────────────────

    fun showBookmarks() = showBookmarksDialog()

    private fun showBookmarksDialog() {
        try {
            val ctx = requireContext()
            val list = BookmarkStorage.all(ctx)
            if (list.isEmpty()) { Toast.makeText(ctx, "No bookmarks – use ⭐ Bookmark in menu to add", Toast.LENGTH_SHORT).show(); return }
            val items = list.map { "${it.title}\n${it.url}" }.toTypedArray()
            android.app.AlertDialog.Builder(ctx)
                .setTitle("Bookmarks (${list.size})")
                .setItems(items) { _, which -> loadUrl(list[which].url) }
                .setPositiveButton("Close", null)
                .setNeutralButton("Clear") { _, _ ->
                    BookmarkStorage.clear(ctx)
                    Toast.makeText(ctx, "Bookmarks cleared", Toast.LENGTH_SHORT).show()
                }
                .show()
        } catch (e: Exception) { Toast.makeText(requireContext(), e.message, Toast.LENGTH_LONG).show() }
    }

    // ──── Slide-in more menu ─────────────────────────────────────────────────

    private fun showMoreMenuSlideIn(anchor: View) {
        val ctx = requireContext()
        val menuView = LayoutInflater.from(ctx).inflate(R.layout.slidein_browser_menu, null)
        val overlay = menuView.findViewById<View>(R.id.menuOverlay)
        val panel = menuView.findViewById<LinearLayout>(R.id.menuPanel)
        val recycler = menuView.findViewById<RecyclerView>(R.id.menuRecycler)
        val decorView = activity?.window?.decorView?.rootView as? ViewGroup
        decorView?.addView(menuView)

        recycler.layoutManager = LinearLayoutManager(ctx)
        val tabsLabel = "New Tab"
        val menuItems = listOf(
            MenuItemData("↻", "Refresh", R.drawable.ic_refresh, { binding.webView.reload(); closeSlideInMenu(panel, overlay, decorView) }),
            MenuItemData("＋", tabsLabel, R.drawable.ic_web, { openNewTab(Prefs.homePage); closeSlideInMenu(panel, overlay, decorView) }),
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

        recycler.adapter = object : RecyclerView.Adapter<MenuViewHolder>() {
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MenuViewHolder {
                val v = LayoutInflater.from(parent.context).inflate(R.layout.item_menu, parent, false)
                return MenuViewHolder(v)
            }
            override fun onBindViewHolder(holder: MenuViewHolder, position: Int) {
                val item = menuItems[position]
                holder.icon.setImageResource(item.iconRes)
                holder.title.text = item.title
                holder.itemView.setOnClickListener { item.action.invoke() }
            }
            override fun getItemCount() = menuItems.size
        }

        animateSlideIn(panel, overlay)
        overlay.setOnClickListener { closeSlideInMenu(panel, overlay, decorView) }
    }

    private fun animateSlideIn(panel: LinearLayout, overlay: View) {
        overlay.visibility = View.VISIBLE
        overlay.animate().alpha(1f).setDuration(200).start()
        panel.animate().translationX(0f).setDuration(250).setInterpolator(android.view.animation.DecelerateInterpolator()).start()
    }

    private fun closeSlideInMenu(panel: LinearLayout, overlay: View, decorView: ViewGroup?) {
        val root = panel.parent as? View
        panel.animate()
            .translationX(panel.width.toFloat())
            .setDuration(200)
            .setInterpolator(android.view.animation.AccelerateInterpolator())
            .withEndAction { (root?.parent as? ViewGroup)?.removeView(root) }
            .start()
        overlay.animate()
            .alpha(0f)
            .setDuration(200)
            .withEndAction { overlay.visibility = View.GONE }
            .start()
    }

    // ──── Misc helpers ───────────────────────────────────────────────────────

    data class MenuItemData(val iconPrefix: String, val title: String, val iconRes: Int, val action: () -> Unit)
    class MenuViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val icon: ImageView = view.findViewById(R.id.menuIcon)
        val title: TextView = view.findViewById(R.id.menuTitle)
    }

    private fun showScriptsDialog() {
        try { (activity as? MainActivity)?.switchToTab(R.id.nav_scripts) } catch (_: Exception) {}
    }
    private fun showDownloadsDialog() {
        try { (activity as? MainActivity)?.switchToTab(R.id.nav_downloads) } catch (_: Exception) {}
    }
    private fun showSettingsDialog() {
        try { (activity as? MainActivity)?.switchToTab(R.id.nav_settings) } catch (_: Exception) {}
    }

    fun loadUrl(url: String) {
        try {
            _binding?.webView?.loadUrl(url, mapOf("X-Requested-With" to ""))
            _binding?.urlBar?.setText(url)
            updateCollapsedBar(url)
            collapseUrlBar()
        } catch (_: Exception) {}
    }

    private fun updateCollapsedBar(url: String) {
        try {
            val uri = android.net.Uri.parse(url)
            val domain = uri.host ?: url
            _binding?.tvDomain?.text = domain
            val isHttps = url.startsWith("https://")
            _binding?.tvLock?.text = if (isHttps) "🔒" else "⚠️"
        } catch (_: Exception) {}
    }

    private fun updateBookmarkIcon(url: String) {
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

    private fun loadFromBar() {
        val input = binding.urlBar.text.toString().trim()
        if (input.isEmpty()) return
        val url = when {
            input.startsWith("http://") || input.startsWith("https://") -> input
            input.contains(".") && !input.contains(" ") -> "https://$input"
            else -> Prefs.buildSearchUrl(input)
        }
        binding.webView.loadUrl(url, mapOf("X-Requested-With" to ""))
    }

    // ──── Script injection ───────────────────────────────────────────────────

    private fun injectVisibilityHack(v: WebView?, url: String?) {
        if (v == null || url == null) return
        val lower = url.lowercase()
        if (!lower.contains("youtube.com") && !lower.contains("youtu.be") && !lower.contains("soundcloud.com") && !lower.contains("wtr-lab.com")) {
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
                        if (type === 'visibilitychange' || type === 'webkitvisibilitychange') return;
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
                try {
                    let viewport = document.querySelector('meta[name="viewport"]');
                    if (!viewport) {
                        viewport = document.createElement('meta');
                        viewport.name = 'viewport';
                        document.head.appendChild(viewport);
                    }
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
            Log.d(TAG, msg); lastInjectInfo = msg; consoleLogs.add(msg); return
        }
        val toInject = matched.filter { sc ->
            when (sc.runAt) {
                "document_start" -> runAt == "document_start"
                "document_end" -> runAt == "document_end" || runAt == "document_idle"
                else -> runAt == "document_idle" || runAt == "document_end"
            }
        }
        if (toInject.isEmpty()) { Log.d(TAG, "Matched ${matched.size} but none for runAt=$runAt"); return }
        lastInjectInfo = "Inject ${toInject.size} @ $runAt for $url: ${toInject.joinToString(","){it.name}}"
        Log.d(TAG, lastInjectInfo); consoleLogs.add(lastInjectInfo)
        try { Toast.makeText(requireContext(), lastInjectInfo.take(120), Toast.LENGTH_SHORT).show() } catch (_: Exception) {}
        toInject.forEach { sc -> injectSingle(v, sc) }
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
        val code = sc.code
        val wrapped = "(function(){ try{\n$marker\n$gmPolyfill\n$code\n}catch(e){console.error('Custom script error:', e);} })();"
        v.evaluateJavascript(wrapped) { result -> Log.d(TAG, "inject result ${sc.name}: $result") }
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
                container.addView(android.widget.TextView(ctx).apply {
                    text = t; setTextColor(android.graphics.Color.parseColor("#FFC084FC"))
                    textSize = 12f; setTypeface(null, android.graphics.Typeface.BOLD)
                    setPadding(0, 16, 0, 6)
                })
            }
            fun addText(t: String, mono: Boolean = false) {
                container.addView(android.widget.TextView(ctx).apply {
                    text = t; setTextColor(android.graphics.Color.parseColor("#FFC8CDF3"))
                    textSize = 11f
                    if (mono) typeface = android.graphics.Typeface.MONOSPACE
                    setTextIsSelectable(true)
                })
            }
            addTitle("Current URL"); addText(currentUrl, true)
            addTitle("Scripts (${all.size} total, ${matched.size} matched)")
            if (all.isEmpty()) addText("No scripts saved.")
            else all.forEach { sc ->
                val isMatched = matched.contains(sc)
                val mark = if (!sc.enabled) "⭕ disabled" else if (isMatched) "✅ matched" else "❌ no-match"
                addText("• ${sc.name} [$mark] runAt=${sc.runAt} matches=${sc.matches.joinToString(",").ifEmpty { "<all_urls>" }}")
            }
            addTitle("Last inject info"); addText(lastInjectInfo, true)
            addTitle("Console logs (${consoleLogs.size})")
            val logsTv = android.widget.TextView(ctx).apply {
                text = if (consoleLogs.isEmpty()) "No logs yet." else consoleLogs.takeLast(60).joinToString("\n")
                setTextColor(android.graphics.Color.parseColor("#FFE0E0E0"))
                textSize = 10f; typeface = android.graphics.Typeface.MONOSPACE
                setTextIsSelectable(true); setPadding(12, 12, 12, 12)
                setBackgroundColor(android.graphics.Color.parseColor("#FF0D0F1A"))
            }
            val logScroll = android.widget.ScrollView(ctx).apply {
                layoutParams = android.widget.LinearLayout.LayoutParams(android.widget.LinearLayout.LayoutParams.MATCH_PARENT, 420)
                addView(logsTv)
            }
            container.addView(logScroll)

            val etJs = android.widget.EditText(ctx).apply {
                hint = "e.g. document.title"
                setText("document.getElementById('wtr-panel') ? 'WTR panel FOUND' : 'WTR panel NOT found'")
                setTextColor(android.graphics.Color.WHITE); textSize = 11f
            }
            container.addView(etJs)
            val btnReinject = com.google.android.material.button.MaterialButton(ctx).apply { text = "🔄 Re-inject NOW"; textSize = 11f }
            container.addView(btnReinject)

            val scroll = android.widget.ScrollView(ctx).apply { addView(container) }
            val dlg = android.app.AlertDialog.Builder(ctx)
                .setTitle("🧪 Script Tester")
                .setView(scroll)
                .setPositiveButton("Close", null)
                .create()
            dlg.show()

            btnReinject.setOnClickListener {
                val url = wv.url ?: currentUrl
                lastInjectInfo = "Manual re-inject at ${java.text.SimpleDateFormat("HH:mm:ss").format(java.util.Date())} for $url"
                injectScripts(wv, url, "document_idle")
                logsTv.text = "Triggered inject for $url\nCheck logs after 1s…"
                wv.postDelayed({ logsTv.text = consoleLogs.takeLast(60).joinToString("\n").ifEmpty { "Still no logs." } }, 900)
            }
        } catch (e: Exception) {
            Log.e(TAG, "showTestDialog crash", e)
            try { Toast.makeText(requireContext(), "Tester error: ${e.message}", Toast.LENGTH_LONG).show() } catch (_: Exception) {}
        }
    }

    // ──── WebView lifecycle ──────────────────────────────────────────────────

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (hidden) BrowserProfile.onWebViewPause(_binding?.webView)
        else BrowserProfile.onWebViewResume(_binding?.webView)
    }

    fun canGoBack() = _binding?.webView?.canGoBack() == true
    fun goBack() { _binding?.webView?.goBack() }

    override fun onPause() {
        super.onPause()
        BrowserProfile.onWebViewPause(_binding?.webView)
    }

    override fun onResume() {
        super.onResume()
        BrowserProfile.onWebViewResume(_binding?.webView)
    }

    override fun onDestroyView() {
        _binding?.webView?.destroy()
        _binding = null
        super.onDestroyView()
    }
}
