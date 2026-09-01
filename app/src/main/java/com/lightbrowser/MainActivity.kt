package com.lightbrowser

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.fragment.app.Fragment
import com.lightbrowser.data.AppCtx
import com.lightbrowser.databinding.ActivityMainBinding
import com.lightbrowser.ui.BrowserFragment
import com.lightbrowser.ui.DownloadsFragment
import com.lightbrowser.ui.FileManagerFragment
import com.lightbrowser.ui.MusicPlayerFragment
import com.lightbrowser.ui.ScriptsFragment
import com.lightbrowser.ui.SettingsFragment
import com.lightbrowser.ui.TerminalFragment

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private var currentId: Int = R.id.nav_browser

    // Keep fragments alive to preserve WebView state and workspace cache
    private val fragments = mutableMapOf<Int, Fragment>()

    private fun getFrag(id: Int): Fragment = fragments.getOrPut(id) {
        when (id) {
            R.id.nav_browser -> BrowserFragment()
            R.id.nav_scripts -> ScriptsFragment()
            R.id.nav_downloads -> DownloadsFragment()
            R.id.nav_settings -> SettingsFragment()
            R.id.nav_filemanager -> FileManagerFragment()
            R.id.nav_terminal -> TerminalFragment()
            R.id.nav_music -> MusicPlayerFragment()
            else -> BrowserFragment()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Fluid keyboard: edge-to-edge, not crushing workspace
        WindowCompat.setDecorFitsSystemWindows(window, false)
        AppCtx.init(this)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Handle window insets for keyboard – let WebView/workspaces handle it naturally
        // Root will be padded by system bars via fitsSystemWindows false + insets listener in fragments

        if (savedInstanceState == null) {
            switchTab(R.id.nav_browser)
        } else {
            supportFragmentManager.fragments.forEach { f ->
                val id = when (f) {
                    is BrowserFragment -> R.id.nav_browser
                    is ScriptsFragment -> R.id.nav_scripts
                    is DownloadsFragment -> R.id.nav_downloads
                    is SettingsFragment -> R.id.nav_settings
                    is FileManagerFragment -> R.id.nav_filemanager
                    is TerminalFragment -> R.id.nav_terminal
                    is MusicPlayerFragment -> R.id.nav_music
                    else -> null
                }
                if (id != null) fragments[id] = f
            }
            // find visible
            fragments.entries.find { it.value.isVisible }?.let { currentId = it.key }
        }

        intent?.data?.toString()?.let { url ->
            if (url.startsWith("http")) {
                BrowserFragment.pendingUrl = url
                switchTab(R.id.nav_browser)
            }
        }
    }

    fun switchTab(id: Int) {
        if (currentId == id && fragments.containsKey(id) && fragments[id]?.isAdded == true) return
        val tx = supportFragmentManager.beginTransaction()
        fragments[currentId]?.let { if (it.isAdded) tx.hide(it) }
        val next = getFrag(id)
        if (next.isAdded) tx.show(next) else tx.add(R.id.container, next, id.toString())
        tx.commit()
        currentId = id
    }

    fun switchToTab(id: Int) = switchTab(id)

    fun switchToBrowser(url: String? = null) {
        url?.let { BrowserFragment.pendingUrl = it }
        switchTab(R.id.nav_browser)
        if (url != null) {
            (fragments[R.id.nav_browser] as? BrowserFragment)?.loadUrl(url)
        }
    }

    override fun onBackPressed() {
        val current = fragments[currentId]
        if (current is BrowserFragment && current.canGoBack()) {
            current.goBack()
        } else if (currentId != R.id.nav_browser) {
            switchTab(R.id.nav_browser)
        } else {
            super.onBackPressed()
        }
    }
}
