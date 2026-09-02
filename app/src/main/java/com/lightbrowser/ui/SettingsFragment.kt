package com.lightbrowser.ui

import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebStorage
import android.widget.Toast
import androidx.appcompat.app.AppCompatDelegate
import androidx.fragment.app.Fragment
import com.lightbrowser.data.AppCtx
import com.lightbrowser.data.Prefs
import com.lightbrowser.databinding.FragmentSettingsBinding

class SettingsFragment : Fragment() {
    private var _b: FragmentSettingsBinding? = null
    private val b get() = _b!!

    override fun onCreateView(inflater: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        _b = FragmentSettingsBinding.inflate(inflater, c, false)
        return b.root
    }

    override fun onViewCreated(v: View, s: Bundle?) {
        try { AppCtx.init(requireContext()) } catch (_: Exception) {}

        b.etHome.setText(Prefs.homePage)
        b.swJs.isChecked = Prefs.jsEnabled
        b.swDesktop.isChecked = Prefs.desktopMode
        b.swAdblock.isChecked = Prefs.adBlock
        b.swSaveSiteData.isChecked = Prefs.saveSiteData
        b.swCache.isChecked = Prefs.cacheEnabled
        
        val isDarkMode = isDarkModeEnabled()
        b.swDarkMode.isChecked = isDarkMode

        b.etHome.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                val u = b.etHome.text.toString().trim()
                if (u.isNotEmpty()) { Prefs.homePage = u; Toast.makeText(requireContext(), "Homepage saved", Toast.LENGTH_SHORT).show() }
            }
        }
        b.swJs.setOnCheckedChangeListener { _, c -> Prefs.jsEnabled = c }
        b.swDesktop.setOnCheckedChangeListener { _, c -> Prefs.desktopMode = c; Toast.makeText(requireContext(), if(c) "Desktop mode ON (restart browser tab)" else "Desktop OFF", Toast.LENGTH_SHORT).show() }
        b.swAdblock.setOnCheckedChangeListener { _, c -> Prefs.adBlock = c }
        b.swSaveSiteData.setOnCheckedChangeListener { _, c ->
            Prefs.saveSiteData = c
            Toast.makeText(requireContext(), if (c) "Login data will be saved" else "Site data won't persist", Toast.LENGTH_SHORT).show()
        }
        b.swCache.setOnCheckedChangeListener { _, c ->
            Prefs.cacheEnabled = c
            Toast.makeText(requireContext(), if (c) "Cache enabled" else "Cache disabled – reload browser tab", Toast.LENGTH_SHORT).show()
        }
        
        b.swDarkMode.setOnCheckedChangeListener { _, isChecked ->
            setDarkMode(isChecked)
            Toast.makeText(requireContext(), if (isChecked) "Dark mode enabled" else "Light mode enabled", Toast.LENGTH_SHORT).show()
        }

        b.btnClearCache.setOnClickListener {
            try {
                CookieManager.getInstance().removeAllCookies(null)
                CookieManager.getInstance().flush()
                WebStorage.getInstance().deleteAllData()
                requireContext().cacheDir.deleteRecursively()
                Toast.makeText(requireContext(), "Cache & cookies cleared", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) { Toast.makeText(requireContext(), e.message, Toast.LENGTH_LONG).show() }
        }
        b.btnClearHistory.setOnClickListener {
            try {
                requireContext().getSharedPreferences("lb_prefs", 0).edit().clear().apply()
                Prefs.homePage = "https://www.google.com"
                b.etHome.setText(Prefs.homePage)
                b.swJs.isChecked = true
                b.swDesktop.isChecked = false
                Toast.makeText(requireContext(), "Browsing prefs reset", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) { Toast.makeText(requireContext(), e.message, Toast.LENGTH_LONG).show() }
        }
    }

    private fun isDarkModeEnabled(): Boolean {
        val prefs = requireContext().getSharedPreferences("app_theme", Context.MODE_PRIVATE)
        return prefs.getInt("theme_mode", AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM) == AppCompatDelegate.MODE_NIGHT_YES
    }

    private fun setDarkMode(enabled: Boolean) {
        val prefs = requireContext().getSharedPreferences("app_theme", Context.MODE_PRIVATE)
        val mode = if (enabled) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO
        prefs.edit().putInt("theme_mode", mode).apply()
        AppCompatDelegate.setDefaultNightMode(mode)
        
        // Notify activity to recreate
        (activity as? com.lightbrowser.MainActivity)?.recreate()
    }

    override fun onDestroyView() { _b = null; super.onDestroyView() }
}