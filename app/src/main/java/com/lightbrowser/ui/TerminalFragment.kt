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
        return try {
            _b = FragmentTerminalBinding.inflate(inflater, c, false)
            b.root
        } catch (e: Exception) {
            android.util.Log.e("Terminal", "onCreateView", e)
            android.widget.TextView(requireContext()).apply { text = "Terminal unavailable: ${e.message}" }
        }
    }

    override fun onViewCreated(v: View, s: Bundle?) {
        try {
            val bb = _b ?: return
            try { bb.btnSend.setOnClickListener { try { exec() } catch (e: Exception) { append("error: ${e.message}") } } } catch (_: Exception) {}
            try { bb.etInput.setOnEditorActionListener { _, _, _ -> try { exec() } catch (_: Exception) {}; true } } catch (_: Exception) {}
            try { bb.btnClear.setOnClickListener { try { logs.clear(); bb.tvLogs.text = "Cleared.\n"; scrollBottom() } catch (_: Exception) {} } } catch (_: Exception) {}
            try {
                bb.btnCopy.setOnClickListener {
                    try {
                        val cm = requireContext().getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                        cm.setPrimaryClip(android.content.ClipData.newPlainText("logs", bb.tvLogs.text))
                        Toast.makeText(requireContext(), "Copied", Toast.LENGTH_SHORT).show()
                    } catch (e: Exception) { append("copy error: ${e.message}") }
                }
            } catch (_: Exception) {}
            try { bb.btnScripts.setOnClickListener { try { execCmd("scripts") } catch (e: Exception) { append(e.message ?: "error") } } } catch (_: Exception) {}

            append("LightBrowser Terminal v2 – shell + JS")
            append("Type 'help' for commands. Python via Chaquopy not included (30MB) – use 'python --help' for stub.")
            append("Tip: 'js document.title' runs JS in WebView, 'ls /data/data/...' is blocked (sandbox).")
        } catch (e: Exception) {
            android.util.Log.e("Terminal", "onViewCreated", e)
            try { Toast.makeText(requireContext(), "Terminal error: ${e.message}", Toast.LENGTH_LONG).show() } catch (_: Exception) {}
        }
    }

    private fun exec() {
        try {
            val bb = _b ?: return
            val cmd = bb.etInput.text.toString().trim()
            if (cmd.isEmpty()) return
            append("$ $cmd")
            bb.etInput.text.clear()
            execCmd(cmd)
        } catch (e: Exception) { append("exec error: ${e.message}") }
    }

    private fun execCmd(raw: String) {
        try {
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
                "clear" -> { logs.clear(); try { _b?.tvLogs?.text = "" } catch (_: Exception) {} }
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
                    runShell("curl -I $arg")
                }
                "ls" -> {
                    val path = arg.ifBlank { try { requireContext().filesDir.absolutePath + "/sandbox" } catch (_: Exception) { "/data/data/com.lightbrowser/files/sandbox" } }
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
                    append("Unknown: $cmd – trying as shell: $raw")
                    runShell(raw)
                }
            }
            scrollBottom()
        } catch (e: Exception) { append("execCmd error: ${e.message}") }
    }

    private fun runShell(cmd: String) {
        try { append("→ sh: $cmd") } catch (_: Exception) {}
        scope.launch(Dispatchers.IO) {
            try {
                val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", cmd))
                val reader = BufferedReader(InputStreamReader(process.inputStream))
                val errReader = BufferedReader(InputStreamReader(process.errorStream))
                val output = StringBuilder()
                var line: String?
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
                    val trimmed = if (result.length > 2000) result.take(2000) + "\n…truncated" else result
                    try { append(trimmed); scrollBottom() } catch (_: Exception) {}
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { try { append("sh error: ${e.message}") } catch (_: Exception) {} }
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
        try {
            logs.add(line)
            if (logs.size > 600) logs.removeAt(0)
            _b?.tvLogs?.text = logs.joinToString("\n")
            scrollBottom()
        } catch (_: Exception) {}
    }

    private fun scrollBottom() {
        try { _b?.svLogs?.post { try { _b?.svLogs?.fullScroll(View.FOCUS_DOWN) } catch (_: Exception) {} } } catch (_: Exception) {}
    }

    override fun onDestroyView() { _b = null; super.onDestroyView() }
}
