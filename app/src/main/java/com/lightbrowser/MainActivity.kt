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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppCtx.init(this)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        if (savedInstanceState == null) switchTab(R.id.nav_browser)

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
        val frag: Fragment = when (id) {
            R.id.nav_browser -> BrowserFragment()
            R.id.nav_scripts -> ScriptsFragment()
            R.id.nav_downloads -> DownloadsFragment()
            R.id.nav_settings -> SettingsFragment()
            else -> BrowserFragment()
        }
        supportFragmentManager.beginTransaction()
            .replace(R.id.container, frag)
            .commit()
    }

    override fun onBackPressed() {
        val current = supportFragmentManager.findFragmentById(R.id.container)
        if (current is BrowserFragment && current.canGoBack()) {
            current.goBack()
        } else {
            super.onBackPressed()
        }
    }
}
