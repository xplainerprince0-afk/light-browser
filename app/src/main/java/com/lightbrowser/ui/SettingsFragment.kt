package com.lightbrowser.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebStorage
import android.widget.Toast
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
        // init prefs if needed
        try { AppCtx.init(requireContext()) } catch (_: Exception) {}

        b.etHome.setText(Prefs.homePage)
        b.swJs.isChecked = Prefs.jsEnabled
        b.swDesktop.isChecked = Prefs.desktopMode
        b.swAdblock.isChecked = Prefs.adBlock

        b.etHome.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                val u = b.etHome.text.toString().trim()
                if (u.isNotEmpty()) { Prefs.homePage = u; Toast.makeText(requireContext(), "Homepage saved", Toast.LENGTH_SHORT).show() }
            }
        }
        b.swJs.setOnCheckedChangeListener { _, c -> Prefs.jsEnabled = c }
        b.swDesktop.setOnCheckedChangeListener { _, c -> Prefs.desktopMode = c; Toast.makeText(requireContext(), if(c) "Desktop mode ON (restart browser tab)" else "Desktop OFF", Toast.LENGTH_SHORT).show() }
        b.swAdblock.setOnCheckedChangeListener { _, c -> Prefs.adBlock = c }

        b.btnClearCache.setOnClickListener {
            try {
                CookieManager.getInstance().removeAllCookies(null)
                CookieManager.getInstance().flush()
                WebStorage.getInstance().deleteAllData()
                // clear WebView cache is done per fragment; also clear app cache
                requireContext().cacheDir.deleteRecursively()
                Toast.makeText(requireContext(), "Cache & cookies cleared", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) { Toast.makeText(requireContext(), e.message, Toast.LENGTH_LONG).show() }
        }
        b.btnClearHistory.setOnClickListener {
            try {
                // clear prefs for demo (not scripts)
                requireContext().getSharedPreferences("lb_prefs", 0).edit().clear().apply()
                // reapply defaults
                Prefs.homePage = "https://www.google.com"
                b.etHome.setText(Prefs.homePage)
                b.swJs.isChecked = true
                b.swDesktop.isChecked = false
                Toast.makeText(requireContext(), "Browsing prefs reset", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) { Toast.makeText(requireContext(), e.message, Toast.LENGTH_LONG).show() }
        }
    }

    override fun onDestroyView() { _b = null; super.onDestroyView() }
}
