package com.lightbrowser.ui

import android.graphics.Color
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.util.Log
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.lightbrowser.MainActivity
import com.lightbrowser.R
import com.lightbrowser.data.AlpineEnv
import com.lightbrowser.data.HistoryStorage
import com.lightbrowser.data.ScriptStorage
import com.lightbrowser.databinding.FragmentTerminalBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

class TerminalFragment : Fragment() {

    private var _b: FragmentTerminalBinding? = null
    private val logs = mutableListOf<String>()
    private val commandHistory = mutableListOf<String>()
    private var historyBrowseIndex = -1
    private val scope = CoroutineScope(Dispatchers.Main)

    private var sandboxDir: File? = null
    private var currentDir: File? = null
    private var alpineInstalled = false

    override fun onCreateView(inflater: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        return try {
            _b = FragmentTerminalBinding.inflate(inflater, c, false)
            _b!!.root
        } catch (e: Exception) {
            Log.e("Terminal", "onCreateView", e)
            android.widget.TextView(requireContext()).apply { text = "Terminal unavailable: ${e.message}" }
        }
    }

    override fun onViewCreated(v: View, s: Bundle?) {
        try {
            val bb = _b ?: return
            initSandbox()
            updatePrompt()

            bb.etInput.setOnEditorActionListener { _, actionId, _ ->
                if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEND) {
                    exec()
                    true
                } else false
            }

            bb.etInput.setOnKeyListener { _, keyCode, event ->
                if (event.action == KeyEvent.ACTION_DOWN) {
                    when (keyCode) {
                        KeyEvent.KEYCODE_DPAD_UP -> { historyUp(); return@setOnKeyListener true }
                        KeyEvent.KEYCODE_DPAD_DOWN -> { historyDown(); return@setOnKeyListener true }
                    }
                }
                false
            }

            bb.btnHistUp.setOnClickListener { historyUp() }
            bb.btnHistDown.setOnClickListener { historyDown() }

            bb.btnClear.setOnClickListener {
                logs.clear()
                bb.tvLogs.text = ""
                appendWelcome()
            }

            bb.btnCopy.setOnClickListener {
                try {
                    val cm = requireContext().getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("terminal", bb.tvLogs.text))
                    Toast.makeText(requireContext(), "Copied", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) { appendError("copy error: ${e.message}") }
            }

            bb.btnScripts.setOnClickListener { openScripts() }

            appendWelcome()
        } catch (e: Exception) {
            Log.e("Terminal", "onViewCreated", e)
            Toast.makeText(requireContext(), "Terminal error: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun historyUp() {
        if (commandHistory.isEmpty()) return
        if (historyBrowseIndex < 0) historyBrowseIndex = commandHistory.size
        if (historyBrowseIndex > 0) {
            historyBrowseIndex--
            _b?.etInput?.setText(commandHistory[historyBrowseIndex])
            _b?.etInput?.setSelection(commandHistory[historyBrowseIndex].length)
        }
    }

    private fun historyDown() {
        if (commandHistory.isEmpty()) return
        if (historyBrowseIndex < commandHistory.size - 1) {
            historyBrowseIndex++
            _b?.etInput?.setText(commandHistory[historyBrowseIndex])
            _b?.etInput?.setSelection(commandHistory[historyBrowseIndex].length)
        } else {
            historyBrowseIndex = commandHistory.size
            _b?.etInput?.text?.clear()
        }
    }

    private fun openScripts() {
        val list = try { ScriptStorage.all(requireContext()) } catch (_: Exception) { emptyList() }
        if (list.isEmpty()) {
            (activity as? MainActivity)?.switchToTab(R.id.nav_scripts)
        } else {
            list.forEach { append("• ${it.name} [${if (it.enabled) "ON" else "OFF"}] @${it.runAt}") }
            scrollBottom()
        }
    }

    private fun appendWelcome() {
        val alpine = if (alpineInstalled) "Alpine Linux ready" else "Run 'install-alpine' for Alpine (~3MB)"
        appendColored(getString(R.string.terminal_welcome), Color.parseColor("#88CC88"))
        appendColored(alpine, Color.WHITE)
        updatePrompt()
    }

    private fun initSandbox() {
        try {
            sandboxDir = File(requireContext().filesDir, "sandbox").apply { if (!exists()) mkdirs() }
            currentDir = sandboxDir
            alpineInstalled = AlpineEnv.isInstalled(sandboxDir!!)
        } catch (e: Exception) {
            appendError("Sandbox init failed: ${e.message}")
        }
    }

    private fun updatePrompt() {
        val sd = sandboxDir ?: return
        val cwd = currentDir ?: sd
        val rel = cwd.absolutePath.removePrefix(sd.absolutePath).ifBlank { "" }.trimStart('/')
        val display = if (rel.isEmpty()) "~" else "~/$rel"
        val prefix = if (alpineInstalled) "alpine:" else "sh:"
        _b?.tvPrompt?.text = "$prefix$display $ "
    }

    private fun isPathAllowed(path: File): Boolean {
        val sd = sandboxDir ?: return false
        return try {
            path.canonicalFile.absolutePath.startsWith(sd.canonicalFile.absolutePath)
        } catch (_: Exception) { false }
    }

    private fun resolvePath(input: String): File? {
        val sd = sandboxDir ?: return null
        if (input.isBlank()) return currentDir ?: sd
        val inputFile = if (input.startsWith("/")) File(input) else File(currentDir ?: sd, input)
        return if (isPathAllowed(inputFile)) inputFile else null
    }

    private fun exec() {
        val bb = _b ?: return
        val cmd = bb.etInput.text.toString().trim()
        if (cmd.isEmpty()) return
        if (commandHistory.isEmpty() || commandHistory.last() != cmd) {
            commandHistory.add(cmd)
        }
        historyBrowseIndex = commandHistory.size
        appendColored("${_b?.tvPrompt?.text}$cmd", Color.WHITE)
        bb.etInput.text.clear()
        execCmd(cmd)
    }

    private fun execCmd(raw: String) {
        try {
            val parts = raw.split(" ", limit = 2)
            val cmd = parts[0].lowercase()
            val arg = if (parts.size > 1) parts[1] else ""
            when (cmd) {
                "help" -> showHelp()
                "clear" -> {
                    logs.clear()
                    _b?.tvLogs?.text = ""
                    appendWelcome()
                }
                "scripts" -> openScripts()
                "history" -> {
                    val list = try { HistoryStorage.all(requireContext()) } catch (_: Exception) { emptyList() }
                    if (list.isEmpty()) append("No browsing history")
                    else list.take(10).forEach { append("• ${it.title} – ${it.url}") }
                }
                "js" -> {
                    if (arg.isBlank()) { append("Usage: js <code>"); return }
                    findBrowser()?.runJs(arg) { res -> append("← $res"); scrollBottom() } ?: append("No WebView")
                }
                "sh", "shell", "exec" -> {
                    if (arg.isBlank()) { append("Usage: sh <cmd>"); return }
                    runShell(arg)
                }
                "ping" -> runShell("ping -c 3 ${arg.ifBlank { "8.8.8.8" }}")
                "curl" -> {
                    if (arg.isBlank()) { append("Usage: curl <url>"); return }
                    runShell("curl -I $arg")
                }
                "ls" -> {
                    val target = resolvePath(arg) ?: sandboxDir
                    target?.let { runShell("ls -la \"${it.absolutePath}\"") } ?: appendError("Path denied")
                }
                "cd" -> {
                    val target = resolvePath(arg) ?: sandboxDir
                    if (target != null && target.exists() && target.isDirectory) {
                        currentDir = target
                        updatePrompt()
                    } else {
                        appendError("cd: no such directory: $arg")
                    }
                }
                "pwd" -> append(currentDir?.absolutePath ?: sandboxDir?.absolutePath ?: "unknown")
                "cat" -> {
                    if (arg.isBlank()) { append("Usage: cat <file>"); return }
                    resolvePath(arg)?.let { runShell("cat \"${it.absolutePath}\"") } ?: appendError("Access denied")
                }
                "mkdir" -> {
                    if (arg.isBlank()) { append("Usage: mkdir <dir>"); return }
                    resolvePath(arg)?.let {
                        append(if (it.mkdirs()) "Created ${it.name}" else "Failed")
                    } ?: appendError("Access denied")
                }
                "rm" -> {
                    if (arg.isBlank()) { append("Usage: rm <path>"); return }
                    resolvePath(arg)?.let {
                        append(if ((if (it.isDirectory) it.deleteRecursively() else it.delete())) "Deleted" else "Failed")
                    } ?: appendError("Access denied")
                }
                "mv", "cp" -> {
                    val args = arg.split(" ")
                    if (args.size < 2) { append("Usage: $cmd <src> <dst>"); return }
                    val src = resolvePath(args[0])
                    val dst = resolvePath(args[1])
                    if (src != null && dst != null) {
                        try {
                            if (cmd == "cp") {
                                if (src.isDirectory) copyDir(src, dst) else src.copyTo(dst, overwrite = true)
                            } else src.renameTo(dst)
                            append("OK")
                        } catch (e: Exception) { appendError(e.message ?: "error") }
                    } else appendError("Access denied")
                }
                "install-alpine", "alpine-install" -> installAlpine()
                "alpine-status" -> showAlpineStatus()
                "apk" -> {
                    if (!alpineInstalled) { append("Install Alpine first: install-alpine"); return }
                    runShell("apk $arg")
                }
                "ua" -> findBrowser()?.runJs("navigator.userAgent") { append("UA: $it") } ?: append("No WebView")
                "cache" -> {
                    val dir = requireContext().cacheDir
                    val size = dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
                    append("Cache: ${size / 1024} KB")
                }
                "echo" -> append(arg)
                else -> runShell(raw)
            }
            scrollBottom()
        } catch (e: Exception) { appendError("exec error: ${e.message}") }
    }

    private fun showHelp() {
        append("""
            |LightBrowser Terminal (Alpine sandbox)
            |  help              – this help
            |  clear             – clear screen
            |  scripts           – list userscripts / open manager
            |  install-alpine    – bootstrap Alpine Linux (~3MB)
            |  alpine-status     – show Alpine install status
            |  ls [path]  cd  pwd  cat  mkdir  rm  cp  mv
            |  sh <cmd>          – run shell in sandbox
            |  ping  curl  echo  js <code>  history  cache
            |
            |Sandbox: ${sandboxDir?.absolutePath}
            |All commands restricted to sandbox.
        """.trimMargin())
    }

    private fun installAlpine() {
        val sd = sandboxDir ?: return
        appendColored("Installing Alpine Linux…", Color.WHITE)
        scope.launch(Dispatchers.IO) {
            val ok = AlpineEnv.install(sd) { msg -> withContext(Dispatchers.Main) { append(msg) } }
            withContext(Dispatchers.Main) {
                alpineInstalled = ok
                if (ok) appendColored("✓ Alpine ready. Try: apk info, ls, cat /etc/alpine-release", Color.parseColor("#00FF41"))
                updatePrompt()
                scrollBottom()
            }
        }
    }

    private fun showAlpineStatus() {
        val sd = sandboxDir ?: return
        append("Alpine installed: $alpineInstalled")
        append("Root: ${AlpineEnv.alpineDir(sd).absolutePath}")
        if (alpineInstalled) {
            val rel = File(AlpineEnv.alpineDir(sd), "etc/alpine-release")
            append(if (rel.exists()) rel.readText().trim() else "unknown")
        } else {
            append("Run: install-alpine")
        }
    }

    private fun runShell(cmd: String) {
        val sd = sandboxDir ?: return
        val cwd = currentDir ?: sd
        scope.launch(Dispatchers.IO) {
            try {
                val prefix = AlpineEnv.shellPrefix(sd)
                val fullCmd = "$prefix$cmd"
                val env = AlpineEnv.buildEnvironment(sd, cwd)
                val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", fullCmd), env, cwd)
                val out = BufferedReader(InputStreamReader(process.inputStream))
                val err = BufferedReader(InputStreamReader(process.errorStream))
                val output = StringBuilder()
                var line: String?
                val start = System.currentTimeMillis()
                while (out.readLine().also { line = it } != null) {
                    output.appendLine(line)
                    if (System.currentTimeMillis() - start > 15_000) break
                }
                while (err.readLine().also { line = it } != null) {
                    output.appendLine(line)
                }
                process.waitFor()
                val result = output.toString().trimEnd()
                withContext(Dispatchers.Main) {
                    if (result.isNotEmpty()) {
                        val trimmed = if (result.length > 4000) result.take(4000) + "\n…truncated" else result
                        append(trimmed)
                    }
                    scrollBottom()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { appendError("sh: ${e.message}") }
            }
        }
    }

    private fun copyDir(src: File, dst: File) {
        if (src.isDirectory) {
            dst.mkdirs()
            src.listFiles()?.forEach { copyDir(it, File(dst, it.name)) }
        } else {
            src.copyTo(dst, overwrite = true)
        }
    }

    private fun append(line: String) {
        logs.add(line)
        if (logs.size > 2000) logs.removeAt(0)
        renderLogs()
    }

    private fun appendColored(line: String, color: Int) {
        logs.add(line)
        if (logs.size > 2000) logs.removeAt(0)
        renderLogs(lastLineColor = color)
    }

    private fun appendError(msg: String) {
        appendColored(msg, Color.parseColor("#FF6B6B"))
    }

    private fun renderLogs(lastLineColor: Int? = null) {
        val bb = _b ?: return
        val sb = SpannableStringBuilder()
        logs.forEachIndexed { i, logLine ->
            val start = sb.length
            sb.append(logLine).append("\n")
            if (lastLineColor != null && i == logs.size - 1) {
                sb.setSpan(ForegroundColorSpan(lastLineColor), start, sb.length, 0)
            }
        }
        bb.tvLogs.text = sb
        scrollBottom()
    }

    private fun scrollBottom() {
        _b?.svLogs?.post {
            try { _b?.svLogs?.fullScroll(View.FOCUS_DOWN) } catch (_: Exception) {}
        }
    }

    private fun findBrowser(): BrowserFragment? {
        return try {
            val fm = (activity as? MainActivity)?.supportFragmentManager ?: parentFragmentManager
            fm.fragments.find { it is BrowserFragment } as? BrowserFragment
                ?: fm.findFragmentByTag(R.id.nav_browser.toString()) as? BrowserFragment
        } catch (_: Exception) { null }
    }

    override fun onDestroyView() { _b = null; super.onDestroyView() }
}
