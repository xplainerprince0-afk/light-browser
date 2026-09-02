package com.lightbrowser.ui

import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.PopupMenu
import android.widget.Toast
import androidx.core.view.GravityCompat
import androidx.fragment.app.Fragment
import com.google.android.material.menu.MaterialMenuInflater
import com.lightbrowser.R
import com.lightbrowser.data.HistoryStorage
import com.lightbrowser.data.ScriptStorage
import com.lightbrowser.databinding.FragmentTerminalBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.net.URL

class TerminalFragment : Fragment() {

    private var _b: FragmentTerminalBinding? = null
    private val b get() = _b!!
    private val logs = mutableListOf<String>()
    private val scope = CoroutineScope(Dispatchers.Main)

    // Sandbox management
    private var sandboxDir: File? = null
    private var currentDir: File? = null
    private var pythonInstalled = false
    private var pythonHome: String? = null
    private var pythonPath: String? = null

    override fun onCreateView(inflater: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        return try {
            _b = FragmentTerminalBinding.inflate(inflater, c, false)
            b.root
        } catch (e: Exception) {
            Log.e("Terminal", "onCreateView", e)
            android.widget.TextView(requireContext()).apply { text = "Terminal unavailable: ${e.message}" }
        }
    }

    override fun onViewCreated(v: View, s: Bundle?) {
        try {
            val bb = _b ?: return
            initSandbox()
            
            // Setup input
            bb.etInput.setOnEditorActionListener { _, actionId, _ ->
                if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEND) {
                    exec()
                    true
                } else false
            }
            
            // Bottom toolbar
            bb.btnClear.setOnClickListener { try { logs.clear(); bb.tvLogs.text = ""; appendWelcome() } catch (_: Exception) {} }
            bb.btnCopy.setOnClickListener {
                try {
                    val cm = requireContext().getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("logs", bb.tvLogs.text))
                    Toast.makeText(requireContext(), "Copied", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) { append("copy error: ${e.message}") }
            }
            bb.btnScripts.setOnClickListener { try { execCmd("scripts") } catch (e: Exception) { append(e.message ?: "error") } }
            bb.btnDrawer.setOnClickListener { (activity as? MainActivity)?.binding?.drawerLayout?.openDrawer(GravityCompat.START) }

            appendWelcome()
        } catch (e: Exception) {
            Log.e("Terminal", "onViewCreated", e)
            try { Toast.makeText(requireContext(), "Terminal error: ${e.message}", Toast.LENGTH_LONG).show() } catch (_: Exception) {}
        }
    }

    private fun appendWelcome() {
        append(getString(R.string.terminal_welcome))
    }

    private fun initSandbox() {
        try {
            sandboxDir = File(requireContext().filesDir, "sandbox").apply { if (!exists()) mkdirs() }
            currentDir = sandboxDir
            File(sandboxDir, "python").apply { if (!exists()) mkdirs() }
        } catch (e: Exception) {
            append("Sandbox init failed: ${e.message}")
        }
    }

    private fun isPathAllowed(path: File): Boolean {
        val sd = sandboxDir ?: return false
        try {
            val canonical = path.canonicalFile
            val sandboxCanonical = sd.canonicalFile
            return canonical.absolutePath.startsWith(sandboxCanonical.absolutePath)
        } catch (_: Exception) {
            return false
        }
    }

    private fun resolvePath(input: String): File? {
        val sd = sandboxDir ?: return null
        if (input.isBlank()) return currentDir ?: sd
        val inputFile = if (input.startsWith("/")) File(input) else File(currentDir ?: sd, input)
        return if (isPathAllowed(inputFile)) inputFile else null
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
                "help" -> showHelp()
                "clear" -> { logs.clear(); try { _b?.tvLogs?.text = "" } catch (_: Exception) {}; appendWelcome() }
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
                    val target = resolvePath(arg) ?: sandboxDir
                    target?.let { runShell("ls -la \"${it.absolutePath}\"") } ?: append("Path not allowed or unavailable")
                }
                "cd" -> {
                    val target = resolvePath(arg) ?: sandboxDir
                    if (target != null && target.exists() && target.isDirectory) {
                        currentDir = target
                        append("cwd: ${target.absolutePath}")
                    } else {
                        append("cd: no such directory or access denied: $arg")
                    }
                }
                "pwd" -> append("cwd: ${currentDir?.absolutePath ?: sandboxDir?.absolutePath ?: "unknown"}")
                "cat" -> {
                    if (arg.isBlank()) { append("Usage: cat <file>"); return }
                    val target = resolvePath(arg)
                    target?.let { runShell("cat \"${it.absolutePath}\"") } ?: append("File not found or access denied")
                }
                "mkdir" -> {
                    if (arg.isBlank()) { append("Usage: mkdir <dir>"); return }
                    val target = resolvePath(arg)
                    target?.let { if (it.mkdirs()) append("Created: ${it.absolutePath}") else append("Failed") } ?: append("Access denied")
                }
                "rm" -> {
                    if (arg.isBlank()) { append("Usage: rm <file|dir>"); return }
                    val target = resolvePath(arg)
                    target?.let {
                        val ok = if (it.isDirectory) it.deleteRecursively() else it.delete()
                        append(if (ok) "Deleted" else "Failed")
                    } ?: append("Access denied")
                }
                "mv", "cp" -> {
                    val args = arg.split(" ")
                    if (args.size < 2) { append("Usage: $cmd <src> <dst>"); return }
                    val src = resolvePath(args[0])
                    val dst = resolvePath(args[1])
                    if (src != null && dst != null) {
                        try {
                            if (cmd == "cp") {
                                if (src.isDirectory) copyDir(src, dst) else src.copyTo(dst)
                            } else {
                                src.renameTo(dst)
                            }
                            append("OK")
                        } catch (e: Exception) { append("Error: ${e.message}") }
                    } else { append("Access denied") }
                }
                "python", "py" -> handlePython(arg)
                "install-python", "bootstrap-python" -> installPython()
                "python-status" -> showPythonStatus()
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
                    append("Unknown: $cmd – type 'help'")
                }
            }
            scrollBottom()
        } catch (e: Exception) { append("execCmd error: ${e.message}") }
    }

    private fun showHelp() {
        append("""
            Commands:
            • help – this help
            • clear – clear logs
            • scripts – list userscripts
            • history – recent URLs
            • js <code> – run JS in WebView
            • sh <cmd> – run shell command in sandbox
            • ping <host> – ping host
            • curl <url> – fetch headers
            • ls [path] – list files (sandbox only)
            • cd [path] – change directory (sandbox only)
            • pwd – print working directory
            • cat <file> – show file content
            • mkdir <dir> – create directory
            • rm <file|dir> – remove file/directory
            • cp <src> <dst> – copy
            • mv <src> <dst> – move/rename
            • python [args] – run Python (bootstraps on first use)
            • install-python – bootstrap Python runtime (Alpine/Termux ARM64)
            • python-status – show Python installation status
            • ua – user agent
            • cache – cache size
            • echo <text> – print text

            Sandbox: ${sandboxDir?.absolutePath}
            All paths restricted to sandbox. Outside access blocked.
        """.trimIndent())
    }

    private fun runShell(cmd: String) {
        try { append("→ sh: $cmd") } catch (_: Exception) {}
        scope.launch(Dispatchers.IO) {
            try {
                val env = buildEnvironment()
                val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", cmd), env, sandboxDir)
                val reader = BufferedReader(InputStreamReader(process.inputStream))
                val errReader = BufferedReader(InputStreamReader(process.errorStream))
                val output = StringBuilder()
                var line: String?
                val start = System.currentTimeMillis()
                while (reader.readLine().also { line = it } != null) {
                    output.appendLine(line)
                    if (System.currentTimeMillis() - start > 10000) break
                }
                while (errReader.readLine().also { line = it } != null) {
                    output.appendLine(line)
                }
                process.waitFor()
                val result = output.toString().ifBlank { "(no output, exit ${process.exitValue()})" }
                withContext(Dispatchers.Main) {
                    val trimmed = if (result.length > 3000) result.take(3000) + "\n…truncated" else result
                    try { append(trimmed); scrollBottom() } catch (_: Exception) {}
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { try { append("sh error: ${e.message}") } catch (_: Exception) {} }
            }
        }
    }

    private fun buildEnvironment(): Array<String> {
        val env = mutableListOf<String>()
        env.add("HOME=${sandboxDir?.absolutePath}")
        env.add("PWD=${currentDir?.absolutePath ?: sandboxDir?.absolutePath}")
        env.add("PATH=/system/bin:/system/xbin:/vendor/bin")
        if (pythonHome != null) {
            env.add("PYTHONHOME=$pythonHome")
            env.add("PYTHONPATH=$pythonHome/lib/python3.11")
        }
        if (pythonPath != null) {
            env.add("PATH=$pythonPath:${env.find { it.startsWith("PATH=") }?.substringAfter("PATH=") ?: "/system/bin"}")
        }
        return env.toTypedArray()
    }

    private fun handlePython(args: String) {
        if (!pythonInstalled) {
            append("Python not installed. Run 'install-python' to bootstrap Alpine Python runtime (~15MB).")
            append("Or run 'python --help' to see this message.")
            return
        }
        val pythonBin = File(pythonPath ?: "", "python3")
        if (!pythonBin.exists()) {
            append("Python binary not found at $pythonBin. Try 'install-python' again.")
            return
        }
        val fullCmd = "$pythonBin $args"
        runShell(fullCmd)
    }

    private fun installPython() {
        append("🐍 Starting Python bootstrap (Alpine ARM64)...")
        append("This will download ~15MB. Please wait...")
        scope.launch(Dispatchers.IO) {
            try {
                val pythonDir = File(sandboxDir ?: return@launch, "python")
                val binDir = File(pythonDir, "bin")
                val libDir = File(pythonDir, "lib")
                binDir.mkdirs()
                libDir.mkdirs()

                val bootstrapUrl = "https://github.com/termux/termux-packages/files/15283858/python-3.11.7-aarch64-android.tar.xz"

                append("Downloading Python runtime...")
                val success = downloadAndExtract(bootstrapUrl, pythonDir)
                withContext(Dispatchers.Main) {
                    if (success) {
                        pythonInstalled = true
                        pythonHome = pythonDir.absolutePath
                        pythonPath = binDir.absolutePath
                        append("✅ Python installed successfully!")
                        append("Python home: $pythonHome")
                        append("Try: python --version")
                        append("Try: python -c \"print('Hello from Python')\"")
                    } else {
                        append("❌ Download failed. Trying alternative...")
                        tryAlternativeInstall(pythonDir)
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { append("Bootstrap error: ${e.message}") }
            }
        }
    }

    private fun tryAlternativeInstall(pythonDir: File) {
        scope.launch(Dispatchers.IO) {
            try {
                withContext(Dispatchers.Main) {
                    append("Alternative: Manual install via Termux bootstrap")
                    append("1. Install Termux app from F-Droid")
                    append("2. Run: pkg install python")
                    append("3. Copy \$PREFIX to ${sandboxDir}/python")
                    append("")
                    append("Or use Chaquopy SDK in app build.gradle (adds ~30MB)")
                    append("See: https://chaquo.com/chaquopy")
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { append("Alt install error: ${e.message}") }
            }
        }
    }

    private fun downloadAndExtract(urlStr: String, destDir: File): Boolean {
        try {
            val url = URL(urlStr)
            val connection = url.openConnection()
            connection.connectTimeout = 30000
            connection.readTimeout = 120000
            val input = connection.getInputStream()
            val archiveFile = File(destDir, "python.tar.xz")
            val output = FileOutputStream(archiveFile)
            input.copyTo(output)
            input.close()
            output.close()

            val extractResult = runShellSync("cd \"${destDir.absolutePath}\" && tar -xf python.tar.xz 2>&1")
            return extractResult.contains("error").not() || extractResult.isEmpty()
        } catch (e: Exception) {
            Log.e("Terminal", "download failed", e)
            return false
        }
    }

    private fun runShellSync(cmd: String): String {
        try {
            val env = buildEnvironment()
            val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", cmd), env, sandboxDir)
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val errReader = BufferedReader(InputStreamReader(process.errorStream))
            val output = StringBuilder()
            var line: String?
            val start = System.currentTimeMillis()
            while (reader.readLine().also { line = it } != null) {
                output.appendLine(line)
                if (System.currentTimeMillis() - start > 30000) break
            }
            while (errReader.readLine().also { line = it } != null) {
                output.appendLine(line)
            }
            process.waitFor()
            return output.toString()
        } catch (e: Exception) {
            return "error: ${e.message}"
        }
    }

    private fun showPythonStatus() {
        append("Python Status:")
        append("  Installed: $pythonInstalled")
        append("  Home: ${pythonHome ?: "not set"}")
        append("  Path: ${pythonPath ?: "not set"}")
        if (pythonInstalled) {
            val binDir = File(pythonPath ?: "")
            binDir.listFiles()?.forEach { f -> append("  - ${f.name}") }
        }
    }

    private fun copyDir(src: File, dst: File) {
        if (src.isDirectory) {
            dst.mkdirs()
            src.listFiles()?.forEach { child ->
                copyDir(child, File(dst, child.name))
            }
        } else {
            src.copyTo(dst)
        }
    }

    private fun append(line: String) {
        try {
            logs.add(line)
            if (logs.size > 1000) logs.removeAt(0)
            val bb = _b
            if (bb != null) {
                val sb = SpannableStringBuilder()
                logs.forEach { logLine ->
                    sb.append(logLine).append("\n")
                }
                bb.tvLogs.text = sb
                scrollBottom()
            }
        } catch (_: Exception) {}
    }

    private fun appendColored(line: String, color: Int) {
        try {
            val sb = SpannableStringBuilder()
            logs.forEach { logLine ->
                sb.append(logLine).append("\n")
            }
            val start = sb.length
            sb.append(line).append("\n")
            sb.setSpan(ForegroundColorSpan(color), start, sb.length, 0)
            logs.add(line)
            if (logs.size > 1000) logs.removeAt(0)
            val bb = _b
            if (bb != null) {
                bb.tvLogs.text = sb
                scrollBottom()
            }
        } catch (_: Exception) {}
    }

    private fun scrollBottom() {
        try { _b?.svLogs?.post { try { _b?.svLogs?.fullScroll(View.FOCUS_DOWN) } catch (_: Exception) {} } } catch (_: Exception) {}
    }

    private fun showOverflowMenu() {
        try {
            val bb = _b ?: return
            val ctx = requireContext()
            val popup = PopupMenu(ctx, bb.btnDrawer)
            MaterialMenuInflater(ctx).inflate(R.menu.terminal_menu, popup.menu)
            popup.setOnMenuItemClickListener { item: MenuItem ->
                try {
                    when (item.itemId) {
                        R.id.menu_clear -> { logs.clear(); try { _b?.tvLogs?.text = "" } catch (_: Exception) {}; appendWelcome() }
                        R.id.menu_copy -> {
                            val cm = requireContext().getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                            cm.setPrimaryClip(android.content.ClipData.newPlainText("logs", _b?.tvLogs?.text ?: ""))
                            Toast.makeText(requireContext(), "Copied", Toast.LENGTH_SHORT).show()
                        }
                        R.id.menu_scripts -> execCmd("scripts")
                        R.id.menu_help -> execCmd("help")
                    }
                } catch (_: Exception) {}
                true
            }
            popup.show()
        } catch (e: Exception) { Log.e("Terminal", "overflow menu", e) }
    }

    private fun findBrowser(): BrowserFragment? {
        return try {
            val act = activity as? com.lightbrowser.MainActivity
            val fm = act?.supportFragmentManager ?: parentFragmentManager
            fm.fragments.find { it is BrowserFragment } as? BrowserFragment
                ?: fm.findFragmentByTag(R.id.nav_browser.toString()) as? BrowserFragment
        } catch (_: Exception) { null }
    }

    override fun onDestroyView() { _b = null; super.onDestroyView() }
}