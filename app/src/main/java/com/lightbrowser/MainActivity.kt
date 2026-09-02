package com.lightbrowser

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.ActionBarDrawerToggle
import androidx.core.view.GravityCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.drawerlayout.widget.DrawerLayout
import androidx.fragment.app.Fragment
import com.google.android.material.navigation.NavigationView
import com.lightbrowser.data.AppCtx
import com.lightbrowser.databinding.ActivityMainBinding
import com.lightbrowser.ui.BrowserFragment
import com.lightbrowser.ui.FileManagerFragment
import com.lightbrowser.ui.MusicPlayerFragment
import com.lightbrowser.ui.SettingsFragment
import com.lightbrowser.ui.TerminalFragment

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var currentId: Int = R.id.nav_browser
    private var isNavSyncing = false
    private lateinit var drawerToggle: ActionBarDrawerToggle

    private val fragments = mutableMapOf<Int, Fragment>()

    private fun getFrag(id: Int): Fragment = fragments.getOrPut(id) {
        when (id) {
            R.id.nav_browser -> BrowserFragment()
            R.id.nav_filemanager -> FileManagerFragment()
            R.id.nav_music -> MusicPlayerFragment()
            R.id.nav_terminal -> TerminalFragment()
            R.id.nav_settings -> SettingsFragment()
            else -> BrowserFragment()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Edge-to-edge with proper insets
        WindowCompat.setDecorFitsSystemWindows(window, false)
        AppCtx.init(this)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Setup drawer toggle
        drawerToggle = ActionBarDrawerToggle(
            this, binding.drawerLayout, null,
            R.string.open_drawer, R.string.close_drawer
        )
        binding.drawerLayout.addDrawerListener(drawerToggle)
        drawerToggle.syncState()

        // Handle navigation item clicks
        binding.navView.setNavigationItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_drawer_browser -> switchTab(R.id.nav_browser)
                R.id.nav_drawer_sandbox -> switchTab(R.id.nav_filemanager)
                R.id.nav_drawer_player -> switchTab(R.id.nav_music)
                R.id.nav_drawer_terminal -> switchTab(R.id.nav_terminal)
                R.id.nav_drawer_settings -> switchTab(R.id.nav_settings)
                R.id.nav_drawer_scripts -> {
                    // Switch to scripts tab (not in bottom nav)
                    Toast.makeText(this, "Userscripts", Toast.LENGTH_SHORT).show()
                    true
                }
                R.id.nav_drawer_downloads -> {
                    // Switch to downloads tab (not in bottom nav)
                    Toast.makeText(this, "Downloads", Toast.LENGTH_SHORT).show()
                    true
                }
                R.id.nav_drawer_history -> {
                    // Switch to history tab (not in bottom nav)
                    Toast.makeText(this, "History", Toast.LENGTH_SHORT).show()
                    true
                }
                R.id.nav_drawer_bookmarks -> {
                    // Switch to bookmarks tab (not in bottom nav)
                    Toast.makeText(this, "Bookmarks", Toast.LENGTH_SHORT).show()
                    true
                }
                R.id.nav_drawer_about -> showAboutDialog()
            }
            binding.drawerLayout.closeDrawer(GravityCompat.START)
            true
        }

        // Handle window insets for edge-to-edge and keyboard
        // adjustResize in manifest handles keyboard, we just manage status/nav bars
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val statusBars = insets.getInsets(WindowInsetsCompat.Type.statusBars())
            val navBars = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            
            // Container: top padding for status bar, bottom padding for bottom nav
            binding.container.updatePadding(
                top = statusBars.top,
                bottom = navBars.bottom
            )
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
                    is MusicPlayerFragment -> R.id.nav_music
                    is TerminalFragment -> R.id.nav_terminal
                    else -> null
                }
                if (id != null) fragments[id] = f
            }
            fragments.entries.find { it.value.isVisible }?.let { currentId = it.key }
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

    override fun onPostCreate(savedInstanceState: Bundle?) {
        super.onPostCreate(savedInstanceState)
        drawerToggle.syncState()
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        drawerToggle.onConfigurationChanged(newConfig)
    }

    fun switchTab(id: Int) {
        try {
            if (currentId == id && fragments.containsKey(id) && fragments[id]?.isAdded == true) return
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
        if (id != R.id.nav_browser && id != R.id.nav_filemanager && id != R.id.nav_music && id != R.id.nav_terminal && id != R.id.nav_settings) return
        try {
            if (binding.bottomNav.selectedItemId == id) return
            isNavSyncing = true
            binding.bottomNav.selectedItemId = id
            // Update navigation view checked state
            val navItemId = when (id) {
                R.id.nav_browser -> R.id.nav_drawer_browser
                R.id.nav_filemanager -> R.id.nav_drawer_sandbox
                R.id.nav_music -> R.id.nav_drawer_player
                R.id.nav_terminal -> R.id.nav_drawer_terminal
                R.id.nav_settings -> R.id.nav_drawer_settings
                else -> -1
            }
            if (navItemId != -1) {
                binding.navView.menu.findItem(navItemId)?.isChecked = true
            }
        } catch (_: Exception) {} finally {
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
        if (binding.drawerLayout.isDrawerOpen(GravityCompat.START)) {
            binding.drawerLayout.closeDrawer(GravityCompat.START)
        } else {
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

    private fun showAboutDialog() {
        android.app.AlertDialog.Builder(this)
            .setTitle("About LightBrowser")
            .setMessage("LightBrowser v1.0\n\nA lightweight browser with terminal, file manager, and audiobook player.\n\nBuilt with Material 3 and Kotlin.")
            .setPositiveButton("OK", null)
            .show()
    }

    private fun getFrag(id: Int): Fragment = fragments.getOrPut(id) {
        when (id) {
            R.id.nav_browser -> BrowserFragment()
            R.id.nav_filemanager -> FileManagerFragment()
            R.id.nav_music -> MusicPlayerFragment()
            R.id.nav_terminal -> TerminalFragment()
            R.id.nav_settings -> SettingsFragment()
            else -> BrowserFragment()
        }
    }
}