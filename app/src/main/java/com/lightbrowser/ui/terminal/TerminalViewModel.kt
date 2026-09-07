package com.lightbrowser.ui.terminal

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lightbrowser.data.AlpineEnv
import com.lightbrowser.data.AppCtx
import com.lightbrowser.data.HistoryStorage
import com.lightbrowser.data.Prefs
import com.lightbrowser.data.ScriptStorage
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class TermLine(val text: String, val kind: Int = 0) { // 0 normal, 1 ok-green, 2 error, 3 prompt-echo
    companion object {
        const val NORMAL = 0
        const val OK = 1
        const val ERROR = 2
        const val ECHO = 3
    }
}

class TerminalViewModel : ViewModel() {

    private val _lines = MutableStateFlow<List<TermLine>>(emptyList())
    val lines: StateFlow<List<TermLine>> = _lines.asStateFlow()

    private val _input = MutableStateFlow(TextFieldValue(""))
    val input: StateFlow<TextFieldValue> = _input.asStateFlow()

    private val _status = MutableStateFlow("idle")
    val status: StateFlow<String> = _status.asStateFlow()

    private val _prompt = MutableStateFlow("~ $ ")
    val prompt: StateFlow<String> = _prompt.asStateFlow()

    private val history = mutableListOf<String>()
    private var histIndex = -1
    private var running: Process? = null

    var sandboxDir: File? = null
        private set
    private var currentDir: File? = null
    var alpineInstalled = false
        private set

    fun init() {
        if (sandboxDir != null) return
        try {
            val app = AppCtx.ctx
            val sd = File(app.filesDir, "sandbox").apply { if (!exists()) mkdirs() }
            sandboxDir = sd
            currentDir = sd
            alpineInstalled = AlpineEnv.isInstalled(sd)
            append(TermLine("LightBrowser Terminal (Alpine sandbox)", TermLine.OK))
            append(TermLine(if (alpineInstalled) "Alpine Linux ready" else "Run 'install-alpine' for Alpine", TermLine.NORMAL))
            updatePrompt()
        } catch (e: Exception) {
            append(TermLine("Sandbox init failed: ${e.message}", TermLine.ERROR))
        }
    }

    fun onInputChange(v: TextFieldValue) {
        _input.value = v
    }

    /** Cursor scrub / arrows move the cursor inside the CURRENT input — the whole editable line. */
    fun moveCursor(delta: Int) {
        val v = _input.value
        val pos = (v.selection.start + delta).coerceIn(0, v.text.length)
        _input.value = v.copy(selection = TextRange(pos))
    }

    fun moveCursorTo(pos: Int) {
        val v = _input.value
        _input.value = v.copy(selection = TextRange(pos.coerceIn(0, v.text.length)))
    }

    fun moveWord(backward: Boolean) {
        val v = _input.value
        var pos = v.selection.start
        val t = v.text
        if (backward) {
            while (pos > 0 && t[pos - 1] == ' ') pos--
            while (pos > 0 && t[pos - 1] != ' ') pos--
        } else {
            while (pos < t.length && t[pos] != ' ') pos++
            while (pos < t.length && t[pos] == ' ') pos++
        }
        _input.value = v.copy(selection = TextRange(pos))
    }

    fun insertText(s: String) {
        val v = _input.value
        val start = v.selection.start.coerceIn(0, v.text.length)
        val end = v.selection.end.coerceIn(0, v.text.length)
        val ns = v.text.substring(0, minOf(start, end)) + s + v.text.substring(maxOf(start, end))
        val pos = minOf(start, end) + s.length
        _input.value = TextFieldValue(ns, TextRange(pos))
    }

    fun historyUp() = browseHistory(-1)
    fun historyDown() = browseHistory(1)

    private fun browseHistory(dir: Int) {
        if (history.isEmpty()) return
        histIndex = (if (histIndex < 0) history.size else histIndex) + dir
        histIndex = histIndex.coerceIn(0, history.size)
        val t = if (histIndex >= history.size) "" else history[histIndex]
        _input.value = TextFieldValue(t, TextRange(t.length))
    }

    fun submit() {
        val cmd = _input.value.text.trim()
        if (cmd.isEmpty()) return
        if (history.isEmpty() || history.last() != cmd) history.add(cmd)
        histIndex = history.size
        append(TermLine("${_prompt.value}$cmd", TermLine.ECHO))
        _input.value = TextFieldValue("")
        execCmd(cmd)
    }

    fun killRunning() {
        try {
            running?.destroyForcibly()
            append(TermLine("Killed running process", TermLine.ERROR))
        } catch (_: Exception) {}
        running = null
        _status.value = "idle"
    }

    fun clear() {
        _lines.value = emptyList()
        append(TermLine("LightBrowser Terminal — type 'help'", TermLine.OK))
    }

    fun fullLog(): String = _lines.value.joinToString("\n") { it.text }

    private fun append(line: TermLine) {
        _lines.update {
            val l = it + line
            if (l.size > 2000) l.takeLast(2000) else l
        }
    }

    private fun updatePrompt() {
        val sd = sandboxDir ?: return
        val cwd = currentDir ?: sd
        val rel = cwd.absolutePath.removePrefix(sd.absolutePath).trim('/').trimStart('/')
        val display = if (rel.isEmpty()) "~" else "~/$rel"
        val prefix = if (alpineInstalled) "alpine:" else "sh:"
        _prompt.value = "$prefix$display $ "
    }

    private fun isAllowed(path: File): Boolean {
        val sd = sandboxDir ?: return false
        return try {
            path.canonicalFile.absolutePath.startsWith(sd.canonicalFile.absolutePath)
        } catch (_: Exception) { false }
    }

    private fun resolve(input: String): File? {
        val sd = sandboxDir ?: return null
        if (input.isBlank()) return currentDir ?: sd
        val f = if (input.startsWith("/")) File(input) else File(currentDir ?: sd, input)
        return if (isAllowed(f)) f else null
    }

    private fun execCmd(raw: String) {
        try {
            val parts = raw.split(" ", limit = 2)
            val cmd = parts[0].lowercase()
            val arg = if (parts.size > 1) parts[1] else ""
            when (cmd) {
                "help" -> append(
                    TermLine(
                        "help/clear/history/scripts/install-alpine/alpine-status\n" +
                            "ls [path]  cd  pwd  cat  mkdir  rm  cp  mv\nsh <cmd>  ping  curl  echo  js <code>  cache  ua",
                        TermLine.NORMAL
                    )
                )
                "clear" -> clear()
                "history" -> {
                    val list = try { HistoryStorage.all(AppCtx.ctx) } catch (_: Exception) { emptyList() }
                    if (list.isEmpty()) append(TermLine("No browsing history", TermLine.NORMAL))
                    else list.take(10).forEach { append(TermLine("• ${it.title} – ${it.url}", TermLine.NORMAL)) }
                }
                "scripts" -> {
                    val list = try { ScriptStorage.all(AppCtx.ctx) } catch (_: Exception) { emptyList() }
                    if (list.isEmpty()) append(TermLine("No userscripts", TermLine.NORMAL))
                    else list.forEach { append(TermLine("• ${it.name} [${if (it.enabled) "ON" else "OFF"}]", TermLine.NORMAL)) }
                }
                "ls" -> {
                    val t = resolve(arg) ?: sandboxDir
                    if (t == null) append(TermLine("Path denied", TermLine.ERROR))
                    else runShell("ls -la \"${t.absolutePath}\"")
                }
                "cd" -> {
                    val t = resolve(arg)
                    if (t != null && t.exists() && t.isDirectory) {
                        currentDir = t
                        updatePrompt()
                    } else append(TermLine("cd: no such directory: $arg", TermLine.ERROR))
                }
                "pwd" -> append(TermLine(currentDir?.absolutePath ?: "unknown", TermLine.NORMAL))
                "cat" -> {
                    if (arg.isBlank()) append(TermLine("Usage: cat <file>", TermLine.ERROR))
                    else resolve(arg)?.let { runShell("cat \"${it.absolutePath}\"") }
                        ?: append(TermLine("Access denied", TermLine.ERROR))
                }
                "mkdir" -> {
                    resolve(arg)?.let {
                        append(TermLine(if (it.mkdirs()) "Created ${it.name}" else "Failed", TermLine.NORMAL))
                    } ?: append(TermLine("Access denied", TermLine.ERROR))
                }
                "rm" -> {
                    resolve(arg)?.let {
                        val ok = if (it.isDirectory) it.deleteRecursively() else it.delete()
                        append(TermLine(if (ok) "Deleted" else "Failed", TermLine.NORMAL))
                    } ?: append(TermLine("Access denied", TermLine.ERROR))
                }
                "mv", "cp" -> {
                    val a = arg.split(" ")
                    if (a.size < 2) append(TermLine("Usage: $cmd <src> <dst>", TermLine.ERROR))
                    else {
                        val src = resolve(a[0])
                        val dst = resolve(a[1])
                        if (src != null && dst != null) {
                            try {
                                if (cmd == "cp") {
                                    if (src.isDirectory) src.copyRecursively(dst, overwrite = true)
                                    else src.copyTo(dst, overwrite = true)
                                } else src.renameTo(dst)
                                append(TermLine("OK", TermLine.OK))
                            } catch (e: Exception) { append(TermLine(e.message ?: "error", TermLine.ERROR)) }
                        } else append(TermLine("Access denied", TermLine.ERROR))
                    }
                }
                "install-alpine", "alpine-install" -> installAlpine()
                "alpine-status" -> {
                    val sd = sandboxDir
                    if (sd == null) append(TermLine("No sandbox", TermLine.ERROR))
                    else {
                        append(TermLine("Alpine installed: $alpineInstalled", TermLine.NORMAL))
                        append(TermLine("Root: ${AlpineEnv.alpineDir(sd).absolutePath}", TermLine.NORMAL))
                    }
                }
                "apk" -> {
                    if (!alpineInstalled) append(TermLine("Install Alpine first: install-alpine", TermLine.ERROR))
                    else runShell("apk $arg")
                }
                "sh", "shell", "exec" -> {
                    if (arg.isBlank()) append(TermLine("Usage: sh <cmd>", TermLine.ERROR))
                    else runShell(arg)
                }
                "ping" -> runShell("ping -c 3 ${arg.ifBlank { "8.8.8.8" }}")
                "curl" -> {
                    if (arg.isBlank()) append(TermLine("Usage: curl <url>", TermLine.ERROR))
                    else runShell("curl -I $arg")
                }
                "cache" -> {
                    try {
                        val dir = AppCtx.ctx.cacheDir
                        val size = dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
                        append(TermLine("Cache: ${size / 1024} KB", TermLine.NORMAL))
                    } catch (e: Exception) { append(TermLine(e.message ?: "", TermLine.ERROR)) }
                }
                "echo" -> append(TermLine(arg, TermLine.NORMAL))
                "ua", "js" -> append(TermLine("Run from Browser tab", TermLine.NORMAL))
                else -> runShell(raw)
            }
        } catch (e: Exception) {
            append(TermLine("exec error: ${e.message}", TermLine.ERROR))
        }
    }

    private fun installAlpine() {
        val sd = sandboxDir ?: return
        append(TermLine("Installing Alpine Linux…", TermLine.NORMAL))
        _status.value = "installing"
        viewModelScope.launch(Dispatchers.IO) {
            val ok = AlpineEnv.install(sd) { msg ->
                viewModelScope.launch(Dispatchers.Main) { append(TermLine(msg, TermLine.NORMAL)) }
            }
            withContext(Dispatchers.Main) {
                alpineInstalled = ok
                if (ok) append(TermLine("✓ Alpine ready", TermLine.OK))
                updatePrompt()
                _status.value = "idle"
            }
        }
    }

    private fun runShell(cmd: String) {
        val sd = sandboxDir ?: return
        val cwd = currentDir ?: sd
        _status.value = "running"
        viewModelScope.launch(Dispatchers.IO) {
            var process: Process? = null
            try {
                val fullCmd = AlpineEnv.shellPrefix(sd) + cmd
                val env = AlpineEnv.buildEnvironment(sd, cwd)
                process = Runtime.getRuntime().exec(arrayOf("sh", "-c", fullCmd), env, cwd)
                running = process
                val out = BufferedReader(InputStreamReader(process.inputStream))
                val err = BufferedReader(InputStreamReader(process.errorStream))
                val output = StringBuilder()
                var line: String?
                val start = System.currentTimeMillis()
                while (out.readLine().also { line = it } != null) {
                    output.appendLine(line)
                    if (output.length > 8000) {
                        output.append("\n…truncated")
                        break
                    }
                    if (System.currentTimeMillis() - start > 15_000) {
                        output.append("\n…timed out (15s)")
                        break
                    }
                }
                while (err.readLine().also { line = it } != null && output.length < 8000) {
                    output.appendLine(line)
                }
                try {
                    if (!process.waitFor(5, TimeUnit.SECONDS)) {
                        try { process.destroyForcibly() } catch (_: Exception) {}
                        output.appendLine("…killed after 20s")
                    }
                } catch (_: Exception) {
                    try { process.destroy() } catch (_: Exception) {}
                }
                val result = output.toString().trimEnd()
                withContext(Dispatchers.Main) {
                    if (result.isNotEmpty()) {
                        val t = if (result.length > 4000) result.take(4000) + "\n…truncated" else result
                        append(TermLine(t, TermLine.NORMAL))
                    }
                    _status.value = "idle"
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    append(TermLine("sh: ${e.message}", TermLine.ERROR))
                    _status.value = "idle"
                }
            } finally {
                running = null
                try { process?.destroy() } catch (_: Exception) {}
            }
        }
    }
}
