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
import com.lightbrowser.ui.FileManagerFragment
import com.lightbrowser.ui.MusicPlayerFragment
import com.lightbrowser.ui.SettingsFragment

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private var currentId: Int = R.id.nav_browser
    private var isNavSyncing = false

    private val fragments = mutableMapOf<Int, Fragment>()

    private fun getFrag(id: Int): Fragment = fragments.getOrPut(id) {
        when (id) {
            R.id.nav_browser -> BrowserFragment()
            R.id.nav_filemanager -> FileManagerFragment()
            R.id.nav_music -> MusicPlayerFragment()
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

        // Phase 1 fix: Edge-to-edge – container must sit BELOW status bar.
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val statusBars = insets.getInsets(WindowInsetsCompat.Type.statusBars())
            val navBars = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            binding.container.updatePadding(top = statusBars.top, bottom = if (ime.bottom > 0) 0 else navBars.bottom)
            binding.bottomNav.updatePadding(bottom = navBars.bottom)
            insets
        }
        ViewCompat.requestApplyInsets(binding.root)

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
            if (isNavSyncing) return@setOnItemSelectedListener true
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
        try {
            if (currentId == id && fragments.containsKey(id) && fragments[id]?.isAdded == true) return
            // Prevent re-entrant add of same fragment before commit (bottomNav sync triggers listener)
            if (currentId == id && fragments.containsKey(id)) return
            if (supportFragmentManager.isStateSaved) {
                val tx2 = supportFragmentManager.beginTransaction()
                fragments[currentId]?.let { if (it.isAdded) try { tx2.hide(it) } catch (_: Exception) {} }
                val n2 = getFrag(id)
                if (n2.isAdded) try { tx2.show(n2) } catch (_: Exception) {} else try { tx2.add(R.id.container, n2, id.toString()) } catch (_: Exception) {}
                try { tx2.commitAllowingStateLoss() } catch (_: Exception) {}
                currentId = id
                syncBottomNav(id)
                return
            }
            val tx = supportFragmentManager.beginTransaction()
            fragments[currentId]?.let { if (it.isAdded) try { tx.hide(it) } catch (_: Exception) {} }
            val next = getFrag(id)
            try { if (next.isAdded) tx.show(next) else tx.add(R.id.container, next, id.toString()) } catch (_: Exception) { try { if (next.isAdded) tx.show(next) else tx.add(R.id.container, next, id.toString()) } catch (_: Exception) {} }
            try { tx.commit() } catch (_: Exception) { try { tx.commitAllowingStateLoss() } catch (_: Exception) {} }
            currentId = id
            syncBottomNav(id)
        } catch (e: Exception) {
            try { android.util.Log.e("LightBrowser", "switchTab $id", e) } catch (_: Exception) {}
            try { android.widget.Toast.makeText(this, "Tab error: ${e.message}", android.widget.Toast.LENGTH_SHORT).show() } catch (_: Exception) {}
        }
    }

    private fun syncBottomNav(id: Int) {
        if (id != R.id.nav_browser && id != R.id.nav_filemanager && id != R.id.nav_music && id != R.id.nav_settings) return
        try {
            if (binding.bottomNav.selectedItemId == id) return
            isNavSyncing = true
            binding.bottomNav.selectedItemId = id
        } catch (_: Exception) {} finally {
            // post to avoid immediate re-entrance
            try { binding.bottomNav.post { isNavSyncing = false } } catch (_: Exception) { isNavSyncing = false }
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
