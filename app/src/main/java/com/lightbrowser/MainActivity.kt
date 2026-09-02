package com.lightbrowser

import android.os.Bundle
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.ActionBarDrawerToggle
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.view.GravityCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
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
            R.id.nav_scripts -> ScriptsFragment()
            R.id.nav_downloads -> DownloadsFragment()
            else -> BrowserFragment()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val themePrefs = getSharedPreferences("app_theme", MODE_PRIVATE)
        AppCompatDelegate.setDefaultNightMode(
            themePrefs.getInt("theme_mode", AppCompatDelegate.MODE_NIGHT_YES)
        )
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        com.lightbrowser.data.AppCtx.init(this)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        drawerToggle = ActionBarDrawerToggle(
            this, binding.drawerLayout, null,
            R.string.open_drawer, R.string.close_drawer
        )
        binding.drawerLayout.addDrawerListener(drawerToggle)
        drawerToggle.syncState()

        binding.navView.setNavigationItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_drawer_browser -> switchTab(R.id.nav_browser)
                R.id.nav_drawer_sandbox -> switchTab(R.id.nav_filemanager)
                R.id.nav_drawer_player -> switchTab(R.id.nav_music)
                R.id.nav_drawer_terminal -> switchTab(R.id.nav_terminal)
                R.id.nav_drawer_settings -> switchTab(R.id.nav_settings)
                R.id.nav_drawer_scripts -> switchTab(R.id.nav_scripts)
                R.id.nav_drawer_downloads -> switchTab(R.id.nav_downloads)
                R.id.nav_drawer_history -> {
                    switchTab(R.id.nav_browser)
                    (fragments[R.id.nav_browser] as? BrowserFragment)?.showHistory()
                }
                R.id.nav_drawer_bookmarks -> {
                    switchTab(R.id.nav_browser)
                    (fragments[R.id.nav_browser] as? BrowserFragment)?.showBookmarks()
                }
                R.id.nav_drawer_about -> showAboutDialog()
            }
            binding.drawerLayout.closeDrawer(GravityCompat.START)
            true
        }

        // Bottom nav stays fixed; only content area responds to keyboard IME
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            binding.container.updatePadding(
                top = systemBars.top,
                bottom = ime.bottom
            )
            binding.bottomNav.updatePadding(bottom = systemBars.bottom)
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
                    is SettingsFragment -> R.id.nav_settings
                    is ScriptsFragment -> R.id.nav_scripts
                    is DownloadsFragment -> R.id.nav_downloads
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

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (binding.drawerLayout.isDrawerOpen(GravityCompat.START)) {
                    binding.drawerLayout.closeDrawer(GravityCompat.START)
                    return
                }
                val current = fragments[currentId]
                if (current is BrowserFragment && current.canGoBack()) {
                    current.goBack()
                } else if (currentId != R.id.nav_browser) {
                    switchTab(R.id.nav_browser)
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                    isEnabled = true
                }
            }
        })
    }

    override fun onPostCreate(savedInstanceState: Bundle?) {
        super.onPostCreate(savedInstanceState)
        drawerToggle.syncState()
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        drawerToggle.onConfigurationChanged(newConfig)
    }

    fun openDrawer() {
        binding.drawerLayout.openDrawer(GravityCompat.START)
    }

    fun switchTab(id: Int) {
        try {
            if (currentId == id && fragments.containsKey(id) && fragments[id]?.isAdded == true) return
            if (supportFragmentManager.isStateSaved) {
                val tx2 = supportFragmentManager.beginTransaction()
                    .setCustomAnimations(R.anim.fade_in, R.anim.fade_out, R.anim.fade_in, R.anim.fade_out)
                fragments[currentId]?.let { if (it.isAdded) try { tx2.hide(it) } catch (_: Exception) {} }
                val n2 = getFrag(id)
                if (n2.isAdded) try { tx2.show(n2) } catch (_: Exception) {} else try { tx2.add(R.id.container, n2, id.toString()) } catch (_: Exception) {}
                try { tx2.commitAllowingStateLoss() } catch (_: Exception) {}
                currentId = id
                syncBottomNav(id)
                return
            }
            val tx = supportFragmentManager.beginTransaction()
                .setCustomAnimations(R.anim.fade_in, R.anim.fade_out, R.anim.fade_in, R.anim.fade_out)
            fragments[currentId]?.let { if (it.isAdded) try { tx.hide(it) } catch (_: Exception) {} }
            val next = getFrag(id)
            try {
                if (next.isAdded) tx.show(next) else tx.add(R.id.container, next, id.toString())
            } catch (_: Exception) {
                try { if (next.isAdded) tx.show(next) else tx.add(R.id.container, next, id.toString()) } catch (_: Exception) {}
            }
            try { tx.commit() } catch (_: Exception) { try { tx.commitAllowingStateLoss() } catch (_: Exception) {} }
            currentId = id
            syncBottomNav(id)
        } catch (e: Exception) {
            try { android.util.Log.e("LightBrowser", "switchTab $id", e) } catch (_: Exception) {}
            try { Toast.makeText(this, "Tab error: ${e.message}", Toast.LENGTH_SHORT).show() } catch (_: Exception) {}
        }
    }

    private fun syncBottomNav(id: Int) {
        val bottomNavIds = setOf(
            R.id.nav_browser, R.id.nav_filemanager, R.id.nav_music, R.id.nav_terminal
        )
        try {
            if (id in bottomNavIds) {
                if (binding.bottomNav.selectedItemId != id) {
                    isNavSyncing = true
                    binding.bottomNav.selectedItemId = id
                }
            }
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

    private fun showAboutDialog() {
        android.app.AlertDialog.Builder(this)
            .setTitle("About LightBrowser")
            .setMessage(
                "LightBrowser v2.3\n\n" +
                "Browser · Sandbox · Terminal (Alpine) · Player\n\n" +
                "Material 3 · Kotlin"
            )
            .setPositiveButton("OK", null)
            .show()
    }
}
