package com.lightbrowser

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import com.lightbrowser.data.AppCtx
import com.lightbrowser.databinding.ActivityMainBinding
import com.lightbrowser.ui.BrowserFragment
import com.lightbrowser.ui.DownloadsFragment
import com.lightbrowser.ui.ScriptsFragment
import com.lightbrowser.ui.SettingsFragment

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private var currentId: Int = R.id.nav_browser

    // Keep fragments alive to preserve WebView state (no destroy on tab switch)
    private val fragments = mutableMapOf<Int, Fragment>()

    private fun getFrag(id: Int): Fragment = fragments.getOrPut(id) {
        when (id) {
            R.id.nav_browser -> BrowserFragment()
            R.id.nav_scripts -> ScriptsFragment()
            R.id.nav_downloads -> DownloadsFragment()
            R.id.nav_settings -> SettingsFragment()
            else -> BrowserFragment()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppCtx.init(this)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // pre-create browser fragment
        if (savedInstanceState == null) {
            switchTab(R.id.nav_browser)
        } else {
            // restore fragments from manager
            supportFragmentManager.fragments.forEach { f ->
                val id = when (f) {
                    is BrowserFragment -> R.id.nav_browser
                    is ScriptsFragment -> R.id.nav_scripts
                    is DownloadsFragment -> R.id.nav_downloads
                    is SettingsFragment -> R.id.nav_settings
                    else -> null
                }
                if (id != null) fragments[id] = f
            }
        }

        binding.bottomNav.setOnItemSelectedListener { item ->
            switchTab(item.itemId)
            true
        }

        intent?.data?.toString()?.let { url ->
            if (url.startsWith("http")) {
                BrowserFragment.pendingUrl = url
                binding.bottomNav.selectedItemId = R.id.nav_browser
            }
        }
    }

    private fun switchTab(id: Int) {
        if (currentId == id && fragments.containsKey(id)) return
        val tx = supportFragmentManager.beginTransaction()
        // hide current
        fragments[currentId]?.let { if (it.isAdded) tx.hide(it) }
        val next = getFrag(id)
        if (next.isAdded) tx.show(next) else tx.add(R.id.container, next, id.toString())
        tx.commit()
        currentId = id
    }

    fun switchToBrowser(url: String? = null) {
        url?.let { BrowserFragment.pendingUrl = it }
        binding.bottomNav.selectedItemId = R.id.nav_browser
        // if url provided and browser already visible, load it
        if (url != null) {
            (fragments[R.id.nav_browser] as? BrowserFragment)?.loadUrl(url)
        }
    }

    override fun onBackPressed() {
        val current = fragments[currentId]
        if (current is BrowserFragment && current.canGoBack()) {
            current.goBack()
        } else {
            super.onBackPressed()
        }
    }
}
