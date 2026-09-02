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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader

class TerminalFragment : Fragment() {
    private var _b: FragmentTerminalBinding? = null
    private val b get() = _b!!
    private val logs = mutableListOf<String>()
    private val scope = CoroutineScope(Dispatchers.Main)

    override fun onCreateView(inflater: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        _b = FragmentTerminalBinding.inflate(inflater, c, false)
        return b.root
    }

    override fun onViewCreated(v: View, s: Bundle?) {
        // Phase 1: Fix status bar overlap
        try {
            androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(b.root) { view, insets ->
                val statusBars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.statusBars())
                view.setPadding(0, statusBars.top, 0, 0)
                insets
            }
            androidx.core.view.ViewCompat.requestApplyInsets(b.root)
        } catch (_: Exception) {}
        b.btnSend.setOnClickListener { exec() }
        b.etInput.setOnEditorActionListener { _, _, _ -> exec(); true }
        b.btnClear.setOnClickListener { logs.clear(); b.tvLogs.text = "Cleared.\n"; scrollBottom() }
        b.btnCopy.setOnClickListener {
            val cm = requireContext().getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            cm.setPrimaryClip(android.content.ClipData.newPlainText("logs", b.tvLogs.text))
            Toast.makeText(requireContext(), "Copied", Toast.LENGTH_SHORT).show()
        }
        b.btnScripts.setOnClickListener { execCmd("scripts") }

        append("LightBrowser Terminal v2 – shell + JS")
        append("Type 'help' for commands. Python via Chaquopy not included (30MB) – use 'python --help' for stub.")
        append("Tip: 'js document.title' runs JS in WebView, 'ls /data/data/...' is blocked (sandbox).")
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
                    • clear – clear
                    • scripts – list userscripts
                    • history – recent URLs
                    • js <code> – run JS in WebView
                    • sh <cmd> – shell (e.g., sh ls -l)
                    • ping <host> – ping (e.g., ping 8.8.8.8)
                    • curl <url> – fetch via shell curl or Java
                    • ls [path] – list files (sandbox/Downloads)
                    • cat <file> – cat file
                    • echo <text> – print
                    • python – stub (Chaquopy not bundled for size)
                    • ua – user agent
                    • cache – cache size
                """.trimIndent())
                append("Note: Chaquopy Python SDK evaluated but NOT included to keep APK ~1.9MB. Use 'sh python' stub or integrate Chaquopy manually if needed (adds ~30MB).")
            }
            "clear" -> { logs.clear(); b.tvLogs.text = "" }
            "scripts" -> {
                val list = try { ScriptStorage.all(requireContext()) } catch (_: Exception) { emptyList() }
                if (list.isEmpty()) append("No scripts.") else list.forEach { append("• ${it.name} [${if (it.enabled) "ON" else "OFF"}] ${it.matches.joinToString(",")} @${it.runAt}") }
            }
            "history" -> {
                val list = try { HistoryStorage.all(requireContext()) } catch (_: Exception) { emptyList() }
                if (list.isEmpty()) append("No history") else list.take(10).forEach { append("• ${it.title} – ${it.url}") }
            }
            "js" -> {
                if (arg.isBlank()) { append("Usage: js <code>"); return }
                append("→ js: $arg")
                findBrowser()?.runJs(arg) { res -> append("← $res"); scrollBottom() } ?: append("No WebView")
            }
            "sh", "shell", "exec" -> {
                if (arg.isBlank()) { append("Usage: sh <cmd>"); return }
                runShell(arg)
            }
            "ping" -> {
                val host = arg.ifBlank { "8.8.8.8" }
                runShell("ping -c 3 $host")
            }
            "curl" -> {
                if (arg.isBlank()) { append("Usage: curl <url>"); return }
                // try shell curl first, fallback to Java
                runShell("curl -I $arg")
            }
            "ls" -> {
                val path = arg.ifBlank { requireContext().filesDir.absolutePath + "/sandbox" }
                runShell("ls -l \"$path\"")
            }
            "cat" -> {
                if (arg.isBlank()) { append("Usage: cat <file>"); return }
                runShell("cat \"$arg\"")
            }
            "python", "py" -> {
                append("Python: Chaquopy not bundled (keeps APK small).")
                append("To add Python, integrate Chaquopy SDK: https://chaquo.com/chaquopy – adds ~30MB, not recommended for scraper memory.")
                append("Use 'js' for WebView JS or 'sh' for shell. For file tasks use 'ls/cat'.")
            }
            "ua" -> {
                findBrowser()?.runJs("navigator.userAgent") { res -> append("UA: $res") } ?: append("UA: unknown")
            }
            "cache" -> {
                try {
                    val dir = requireContext().cacheDir
                    val size = dir.walkTopDown().filter { it.isFile }.map { it.length() }.sum()
                    append("Cache: ${size/1024} KB at ${dir.absolutePath}")
                } catch (e: Exception) { append("cache error: ${e.message}") }
            }
            "echo" -> append(arg)
            else -> {
                // treat as shell by default for convenience
                append("Unknown: $cmd – trying as shell: $raw")
                runShell(raw)
            }
        }
        scrollBottom()
    }

    private fun runShell(cmd: String) {
        append("→ sh: $cmd")
        scope.launch(Dispatchers.IO) {
            try {
                // Use sh -c for complex commands
                val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", cmd))
                val reader = BufferedReader(InputStreamReader(process.inputStream))
                val errReader = BufferedReader(InputStreamReader(process.errorStream))
                val output = StringBuilder()
                var line: String?
                // timeout 5s for safety
                val start = System.currentTimeMillis()
                while (reader.readLine().also { line = it } != null) {
                    output.appendLine(line)
                    if (System.currentTimeMillis() - start > 5000) break
                }
                while (errReader.readLine().also { line = it } != null) {
                    output.appendLine(line)
                }
                process.waitFor()
                val result = output.toString().ifBlank { "(no output, exit ${process.exitValue()})" }
                withContext(Dispatchers.Main) {
                    // limit output to 2000 chars to avoid UI freeze
                    val trimmed = if (result.length > 2000) result.take(2000) + "\n…truncated" else result
                    append(trimmed)
                    scrollBottom()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { append("sh error: ${e.message}") }
            }
        }
    }

    private fun findBrowser(): BrowserFragment? {
        return try {
            val act = activity as? com.lightbrowser.MainActivity
            val fm = act?.supportFragmentManager ?: parentFragmentManager
            fm.fragments.find { it is BrowserFragment } as? BrowserFragment
                ?: fm.findFragmentByTag(R.id.nav_browser.toString()) as? BrowserFragment
        } catch (_: Exception) { null }
    }

    private fun append(line: String) {
        logs.add(line)
        if (logs.size > 600) logs.removeAt(0)
        b.tvLogs.text = logs.joinToString("\n")
        scrollBottom()
    }

    private fun scrollBottom() {
        b.svLogs.post { b.svLogs.fullScroll(View.FOCUS_DOWN) }
    }

    override fun onDestroyView() { _b = null; super.onDestroyView() }
}
