package com.lightbrowser

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import com.lightbrowser.databinding.ActivityMainBinding
import com.lightbrowser.ui.BrowserFragment
import com.lightbrowser.ui.PlaceholderFragment

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        if (savedInstanceState == null) switchTab(R.id.nav_browser)

        binding.bottomNav.setOnItemSelectedListener { item ->
            switchTab(item.itemId)
            true
        }

        // handle VIEW intents
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
            R.id.nav_scripts -> PlaceholderFragment.newInstance(
                "Scripts", getString(R.string.desc_placeholder_scripts), "🧩"
            )
            R.id.nav_downloads -> PlaceholderFragment.newInstance(
                "Downloads", getString(R.string.desc_placeholder_downloads), "⬇️"
            )
            R.id.nav_settings -> PlaceholderFragment.newInstance(
                "Settings", getString(R.string.desc_placeholder_settings), "⚙️"
            )
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
