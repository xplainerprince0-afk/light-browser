package com.lightbrowser.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.lightbrowser.R
import com.lightbrowser.data.HistoryStorage
import com.lightbrowser.data.ScriptStorage
import com.lightbrowser.databinding.FragmentTerminalBinding

class TerminalFragment : Fragment() {
    private var _b: FragmentTerminalBinding? = null
    private val b get() = _b!!
    private val logs = mutableListOf<String>()

    override fun onCreateView(inflater: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        _b = FragmentTerminalBinding.inflate(inflater, c, false)
        return b.root
    }

    override fun onViewCreated(v: View, s: Bundle?) {
        b.btnSend.setOnClickListener { exec() }
        b.etInput.setOnEditorActionListener { _, _, _ -> exec(); true }
        b.btnClear.setOnClickListener { logs.clear(); b.tvLogs.text = "Cleared.\n"; scrollBottom() }
        b.btnCopy.setOnClickListener {
            val cm = requireContext().getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            cm.setPrimaryClip(android.content.ClipData.newPlainText("logs", b.tvLogs.text))
            Toast.makeText(requireContext(), "Copied", Toast.LENGTH_SHORT).show()
        }
        b.btnScripts.setOnClickListener { execCmd("scripts") }

        // show initial help
        append("> help – available: help, clear, scripts, history, js <code>, ua, cache")
        // hook to browser logs if available (poll)
        b.tvLogs.postDelayed({ syncBrowserLogs() }, 800)
    }

    private fun syncBrowserLogs() {
        try {
            val frag = parentFragmentManager.findFragmentByTag(R.id.nav_browser.toString()) as? BrowserFragment
                ?: activity?.supportFragmentManager?.findFragmentByTag(R.id.nav_browser.toString()) as? BrowserFragment
            // fallback: try to find via activity fragments
            val browser = (activity as? com.lightbrowser.MainActivity)?.let {
                // use reflection to get fragment
                try {
                    val f = it.supportFragmentManager.findFragmentByTag(R.id.nav_browser.toString())
                    f as? BrowserFragment
                } catch (_: Exception) { null }
            }
            // If we can't get BrowserFragment, just show own logs
        } catch (_: Exception) {}
    }

    private fun exec() {
        val cmd = b.etInput.text.toString().trim()
        if (cmd.isEmpty()) return
        append("$ $cmd")
        b.etInput.text.clear()
        execCmd(cmd)
    }

    private fun execCmd(raw: String) {
        val parts = raw.split(" ", limit = 2)
        val cmd = parts[0].lowercase()
        val arg = if (parts.size > 1) parts[1] else ""
        when (cmd) {
            "help" -> {
                append("""
                    help – commands:
                    • help – this
                    • clear – clear console
                    • scripts – list userscripts
                    • history – recent URLs
                    • js <code> – run JS in WebView (e.g., js document.title)
                    • ua – show user agent
                    • cache – show cache size
                    • echo <text> – print
                """.trimIndent())
            }
            "clear" -> { logs.clear(); b.tvLogs.text = "" }
            "scripts" -> {
                val list = try { ScriptStorage.all(requireContext()) } catch (_: Exception) { emptyList() }
                if (list.isEmpty()) append("No scripts. Add via Scripts menu.")
                else list.forEach { append("• ${it.name} [${if (it.enabled) "ON" else "OFF"}] ${it.matches.joinToString(",")} @${it.runAt}") }
            }
            "history" -> {
                val list = try { HistoryStorage.all(requireContext()) } catch (_: Exception) { emptyList() }
                if (list.isEmpty()) append("No history")
                else list.take(10).forEach { append("• ${it.title} – ${it.url}") }
            }
            "js" -> {
                if (arg.isBlank()) { append("Usage: js <code>"); return }
                append("→ js: $arg")
                // try to run in browser WebView
                val browser = findBrowser()
                if (browser != null) {
                    browser.runJs(arg) { res -> append("← $res"); scrollBottom() }
                } else append("No browser WebView found (open a page first)")
            }
            "ua" -> {
                val browser = findBrowser()
                if (browser != null) {
                    browser.runJs("navigator.userAgent") { res -> append("UA: $res") }
                } else append("UA: unknown")
            }
            "cache" -> {
                try {
                    val dir = requireContext().cacheDir
                    val size = dir.walkTopDown().filter { it.isFile }.map { it.length() }.sum()
                    append("Cache: ${size/1024} KB at ${dir.absolutePath}")
                } catch (e: Exception) { append("cache error: ${e.message}") }
            }
            "echo" -> append(arg)
            else -> append("Unknown: $cmd – type help")
        }
        scrollBottom()
    }

    private fun findBrowser(): BrowserFragment? {
        return try {
            // MainActivity keeps fragments map, we can find via tag
            val act = activity as? com.lightbrowser.MainActivity
            val fm = act?.supportFragmentManager ?: parentFragmentManager
            // try to find by tag or by id
            fm.fragments.find { it is BrowserFragment } as? BrowserFragment
                ?: fm.findFragmentByTag(R.id.nav_browser.toString()) as? BrowserFragment
        } catch (_: Exception) { null }
    }

    private fun append(line: String) {
        logs.add(line)
        if (logs.size > 400) logs.removeAt(0)
        b.tvLogs.text = logs.joinToString("\n")
        scrollBottom()
    }

    private fun scrollBottom() {
        b.svLogs.post { b.svLogs.fullScroll(View.FOCUS_DOWN) }
    }

    override fun onDestroyView() { _b = null; super.onDestroyView() }
}
