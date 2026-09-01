package com.lightbrowser

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
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

    private val fragments = mutableMapOf<Int, Fragment>()

    private fun getFrag(id: Int): Fragment = fragments.getOrPut(id) {
        when (id) {
            R.id.nav_browser -> BrowserFragment()
            R.id.nav_filemanager -> FileManagerFragment()
            R.id.nav_terminal -> TerminalFragment()
            R.id.nav_music -> MusicPlayerFragment()
            R.id.nav_scripts -> ScriptsFragment()
            R.id.nav_downloads -> DownloadsFragment()
            R.id.nav_settings -> SettingsFragment()
            else -> BrowserFragment()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Phase 1: edge-to-edge with proper insets – status bar not overlapped, nav bar handled
        WindowCompat.setDecorFitsSystemWindows(window, false)
        AppCtx.init(this)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Phase 1: Apply WindowInsets to prevent overlap
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val statusBars = insets.getInsets(WindowInsetsCompat.Type.statusBars())
            val navBars = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            // container gets status bar top padding is handled in BrowserFragment toolbar, nav bar bottom padding for bottom_nav
            binding.container.updatePadding(bottom = if (ime.bottom > 0) 0 else navBars.bottom)
            binding.bottomNav.updatePadding(bottom = navBars.bottom)
            insets
        }

        if (savedInstanceState == null) {
            switchTab(R.id.nav_browser)
        } else {
            supportFragmentManager.fragments.forEach { f ->
                val id = when (f) {
                    is BrowserFragment -> R.id.nav_browser
                    is FileManagerFragment -> R.id.nav_filemanager
                    is TerminalFragment -> R.id.nav_terminal
                    is MusicPlayerFragment -> R.id.nav_music
                    else -> null
                }
                if (id != null) fragments[id] = f
            }
            fragments.entries.find { it.value.isVisible }?.let { currentId = it.key }
            // ensure bottom nav reflects current
            try { binding.bottomNav.selectedItemId = currentId } catch (_: Exception) {}
        }

        binding.bottomNav.setOnItemSelectedListener { item ->
            switchTab(item.itemId)
            true
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
        // only update bottom nav if id is in bottom nav menu (Browser/Files/Terminal/Music)
        if (id == R.id.nav_browser || id == R.id.nav_filemanager || id == R.id.nav_terminal || id == R.id.nav_music) {
            try { binding.bottomNav.selectedItemId = id } catch (_: Exception) {}
        }
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
