package com.lightbrowser.ui.terminal

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
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
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

val TermGreen = Color(0xFF00E676)
val TermDim = Color(0xFF88CC88)
val TermRed = Color(0xFFFF8A80)
val TermWhite = Color(0xFFE8E8E8)

private data class Seg(val text: String, val color: Color)

data class TermSessionMeta(val id: String, val name: String)

/**
 * ONE live editor model: the whole transcript + the "$" prompt live in a single
 * TextFieldValue. The cursor roams the entire buffer (up into old output, like a
 * live linux terminal); Enter submits the last line. Colors via spans.
 */
private data class Sess(
    val id: String = UUID.randomUUID().toString(),
    var name: String,
    val segs: MutableList<Seg> = mutableListOf(),
    var built: AnnotatedString = AnnotatedString(""),
    val history: MutableList<String> = mutableListOf(),
    var histIndex: Int = -1,
    var dir: File? = null,
    var editor: TextFieldValue = TextFieldValue(AnnotatedString("")),
    var promptText: String = ""
)

class TerminalViewModel : ViewModel() {

    private val _editor = MutableStateFlow(TextFieldValue(AnnotatedString("")))
    val editor: StateFlow<TextFieldValue> = _editor.asStateFlow()

    private val _status = MutableStateFlow("idle")
    val status: StateFlow<String> = _status.asStateFlow()

    private val _sessions = MutableStateFlow<List<TermSessionMeta>>(emptyList())
    val sessions: StateFlow<List<TermSessionMeta>> = _sessions.asStateFlow()

    private val _activeId = MutableStateFlow("")
    val activeId: StateFlow<String> = _activeId.asStateFlow()

    private val store = mutableListOf<Sess>()
    private var sessionCounter = 0

    private var running: Process? = null
    /** Offset before which the transcript is frozen while a command runs (-1 idle). */
    private var lockBefore: Int = -1

    var sandboxDir: File? = null
        private set
    var alpineInstalled = false
        private set

    private fun active(): Sess {
        if (store.isEmpty()) {
            val sd = sandboxDir ?: try { AppCtx.ctx.let { File(it.filesDir, "sandbox").apply { mkdirs() } } } catch (_: Exception) { null }
            val s = Sess(name = "main", dir = sd)
            store.add(s)
            try { _activeId.value = s.id } catch (_: Exception) {}
            return s
        }
        return store.firstOrNull { it.id == _activeId.value } ?: store.first()
    }

    private fun emitSessions() {
        _sessions.value = store.map { TermSessionMeta(it.id, it.name) }
    }

    fun init() {
        if (sandboxDir != null) return
        try {
            val app = AppCtx.ctx
            val sd = File(app.filesDir, "sandbox").apply { if (!exists()) mkdirs() }
            sandboxDir = sd
            alpineInstalled = AlpineEnv.isInstalled(sd)
            val s = Sess(name = "main", dir = sd)
            store.add(s)
            _activeId.value = s.id
            refreshPrompt()
            print("LightBrowser Terminal (Alpine sandbox)\n", TermGreen)
            print(if (alpineInstalled) "Alpine Linux ready\n" else "Run 'install-alpine' for Alpine\n", TermDim)
            printPrompt()
            emitSessions()
        } catch (e: Exception) {
            print("Sandbox init failed: ${e.message}\n", TermRed)
        }
    }

    // ── Sessions ──
    fun newSession() {
        val s = Sess(name = "sh${++sessionCounter + 1}", dir = active().dir ?: sandboxDir)
        store.add(s)
        switchSession(s.id)
        refreshPrompt()
        print("New session '${s.name}' — type 'help'\n", TermGreen)
        printPrompt()
    }

    fun switchSession(id: String) {
        try {
            active().editor = _editor.value
        } catch (_: Exception) {}
        val s = store.firstOrNull { it.id == id } ?: return
        _activeId.value = id
        _editor.value = s.editor
        emitSessions()
    }

    fun closeSession(id: String) {
        if (store.size <= 1) return
        val i = store.indexOfFirst { it.id == id }
        if (i < 0) return
        store.removeAt(i)
        if (_activeId.value == id) {
            val next = store[(i - 1).coerceAtLeast(0)]
            _activeId.value = next.id
            _editor.value = next.editor
        }
        emitSessions()
    }

    fun renameSession(id: String, name: String) {
        store.firstOrNull { it.id == id }?.let { it.name = name.ifBlank { it.name } }
        emitSessions()
    }

    // ── Buffer primitives ──
    private fun rebuild(s: Sess) {
        val b = AnnotatedString.Builder()
        s.segs.forEach { seg ->
            b.pushStyle(SpanStyle(color = seg.color, fontFamily = FontFamily.Monospace))
            b.append(seg.text)
            b.pop()
        }
        s.built = b.toAnnotatedString()
    }

    /** Append output text; keeps the caret glued to the end only if it was there. */
    fun print(text: String, color: Color) {
        try {
            val s = active()
            val wasAtEnd = s.editor.selection.start >= s.editor.text.length
            s.segs.add(Seg(text, color))
            // Cap scrollback: drop oldest lines past ~600
            var lineCount = 0
            s.segs.forEach { seg -> lineCount += seg.text.count { c -> c == '\n' } }
            if (lineCount > 600) {
                var drop = 0
                var dropped = 0
                while (drop < s.segs.size && lineCount - dropped > 500) {
                    dropped += s.segs[drop].text.count { c -> c == '\n' }
                    drop++
                }
                repeat(drop) { s.segs.removeAt(0) }
                rebuild(s)
            } else {
                val b = AnnotatedString.Builder(s.built)
                b.pushStyle(SpanStyle(color = color, fontFamily = FontFamily.Monospace))
                b.append(text)
                b.pop()
                s.built = b.toAnnotatedString()
            }
            val newText = s.built.text
            s.editor = if (wasAtEnd || _editor.value.text.isEmpty()) {
                TextFieldValue(s.built, TextRange(newText.length))
            } else {
                // Cursor parked mid-buffer: keep it, clamp into new length
                val cur = _editor.value
                val len = newText.length
                cur.copy(
                    annotatedString = s.built,
                    selection = TextRange(cur.selection.start.coerceIn(0, len), cur.selection.end.coerceIn(0, len))
                )
            }
            if (s.id == _activeId.value) _editor.value = s.editor
        } catch (_: Exception) {}
    }

    private fun refreshPrompt() {
        val sd = sandboxDir ?: return
        val s = try { active() } catch (_: Exception) { return }
        val cwd = s.dir ?: sd
        val rel = cwd.absolutePath.removePrefix(sd.absolutePath).trim('/').trimStart('/')
        val display = if (rel.isEmpty()) "~" else "~/$rel"
        s.promptText = (if (alpineInstalled) "alpine:" else "sh:") + display + " $ "
    }

    private fun printPrompt() {
        val s = try { active() } catch (_: Exception) { return }
        print(s.promptText, TermWhite)
    }

    fun onEditorChange(v: TextFieldValue) {
        try {
            val s = active()
            val old = s.editor
            val p = s.promptText
            // Command zone = everything from the trailing prompt onward (idle only).
            val lastNl = old.text.lastIndexOf('\n')
            val oldLast = if (lastNl == -1) old.text else old.text.substring(lastNl + 1)
            val idlePrompt = p.isNotEmpty() && oldLast.startsWith(p)
            val promptStart = if (idlePrompt) lastNl + 1 + p.length else old.text.length

            if (v.text == old.text) {
                // Caret-only move: collapsed caret may not park inside locked output;
                // ranged selections stay free so long-press copy keeps working.
                val sel = v.selection
                val fixed = if (sel.collapsed && sel.start < promptStart) {
                    v.copy(selection = TextRange(promptStart))
                } else v
                s.editor = fixed
                if (s.id == _activeId.value) _editor.value = fixed
                return
            }
            // While a command runs, the whole transcript is frozen (our shell has
            // no stdin anyway) — caret may roam, text may not change.
            if (lockBefore >= 0) {
                val fixed = old.copy(selection = v.selection)
                s.editor = fixed
                if (s.id == _activeId.value) _editor.value = fixed
                return
            }
            // Idle text edit: find where it starts; a change starting inside the
            // locked zone (output or the "$" itself) is blocked. Appends and
            // edits after the prompt always start at/after promptStart.
            if (idlePrompt) {
                var common = 0
                val n = minOf(old.text.length, v.text.length)
                while (common < n && old.text[common] == v.text[common]) common++
                if (common < promptStart) {
                    // Touched locked zone: restore text, park caret at prompt end.
                    val fixed = old.copy(selection = TextRange(promptStart))
                    s.editor = fixed
                    if (s.id == _activeId.value) _editor.value = fixed
                    return
                }
            }
            // Re-apply our spans over whatever the user typed (keeps colors alive).
            val merged = mergeSpans(s.built, v.text)
            s.editor = v.copy(annotatedString = merged)
            s.built = merged
            if (s.id == _activeId.value) _editor.value = s.editor
        } catch (_: Exception) {
            _editor.value = v
        }
    }

    /** Rebuild spans after an edit: keep old colors for the untouched prefix. */
    private fun mergeSpans(old: AnnotatedString, newText: String): AnnotatedString {
        if (newText.isEmpty()) return AnnotatedString("")
        val b = AnnotatedString.Builder()
        val spans = old.spanStyles
        var pos = 0
        // Walk old spans in order while they still match the new text
        for (span in spans) {
            if (span.start >= newText.length) break
            val end = minOf(span.end, newText.length)
            if (end <= pos) continue
            // Only reuse if the underlying chars are unchanged
            var ok = true
            if (span.start < old.text.length) {
                val oldSlice = old.text.substring(span.start, minOf(span.end, old.text.length))
                val newSlice = if (span.start < newText.length) {
                    newText.substring(span.start, minOf(end, newText.length))
                } else ""
                ok = oldSlice == newSlice
            }
            if (!ok) break
            b.pushStyle(span.item)
            b.append(newText.substring(pos, end))
            b.pop()
            pos = end
        }
        if (pos < newText.length) {
            b.pushStyle(SpanStyle(color = TermWhite, fontFamily = FontFamily.Monospace))
            b.append(newText.substring(pos))
            b.pop()
        }
        return b.toAnnotatedString()
    }

    // ── Cursor moves across the WHOLE editor ──
    fun moveCursor(delta: Int) {
        val v = _editor.value
        val pos = (v.selection.start + delta).coerceIn(0, v.text.length)
        onEditorChange(v.copy(selection = TextRange(pos)))
    }

    fun moveCursorTo(pos: Int) {
        val v = _editor.value
        onEditorChange(v.copy(selection = TextRange(pos.coerceIn(0, v.text.length))))
    }

    fun moveLineHome() {
        val v = _editor.value
        val cur = v.selection.start
        val start = v.text.lastIndexOf('\n', cur - 1) + 1
        onEditorChange(v.copy(selection = TextRange(start)))
    }

    fun moveLineEnd() {
        val v = _editor.value
        val cur = v.selection.start
        val nl = v.text.indexOf('\n', cur)
        onEditorChange(v.copy(selection = TextRange(if (nl == -1) v.text.length else nl)))
    }

    fun moveLineVertical(down: Boolean) {
        val v = _editor.value
        val t = v.text
        val cur = v.selection.start
        val lineStart = t.lastIndexOf('\n', cur - 1) + 1
        val col = cur - lineStart
        val targetStart = if (down) {
            val nl = t.indexOf('\n', cur)
            if (nl == -1) return
            nl + 1
        } else {
            if (lineStart == 0) return
            val prev = t.lastIndexOf('\n', lineStart - 2) + 1
            prev
        }
        val targetEnd = t.indexOf('\n', targetStart).let { if (it == -1) t.length else it }
        onEditorChange(v.copy(selection = TextRange((targetStart + col).coerceAtMost(targetEnd))))
    }

    fun moveWord(backward: Boolean) {
        val v = _editor.value
        var pos = v.selection.start
        val t = v.text
        if (backward) {
            while (pos > 0 && t[pos - 1] == ' ') pos--
            while (pos > 0 && t[pos - 1] != ' ') pos--
        } else {
            while (pos < t.length && t[pos] != ' ') pos++
            while (pos < t.length && t[pos] == ' ') pos++
        }
        onEditorChange(v.copy(selection = TextRange(pos)))
    }

    fun insertText(s: String) {
        val v = _editor.value
        val start = v.selection.start.coerceIn(0, v.text.length)
        val end = v.selection.end.coerceIn(0, v.text.length)
        val ns = v.text.substring(0, minOf(start, end)) + s + v.text.substring(maxOf(start, end))
        val pos = minOf(start, end) + s.length
        onEditorChange(TextFieldValue(AnnotatedString(ns), TextRange(pos)))
    }

    fun historyUp() = browseHistory(-1)
    fun historyDown() = browseHistory(1)

    private fun browseHistory(dir: Int) {
        val s = active()
        if (s.history.isEmpty()) return
        s.histIndex = (if (s.histIndex < 0) s.history.size else s.histIndex) + dir
        s.histIndex = s.histIndex.coerceIn(0, s.history.size)
        val t = if (s.histIndex >= s.history.size) "" else s.history[s.histIndex]
        replaceLastLine(s.promptText + t)
    }

    /** Replace everything after the last newline with [line], caret to end. */
    private fun replaceLastLine(line: String) {
        val v = _editor.value
        val idx = v.text.lastIndexOf('\n')
        val ns = (if (idx == -1) "" else v.text.substring(0, idx + 1)) + line
        onEditorChange(TextFieldValue(AnnotatedString(ns), TextRange(ns.length)))
    }

    fun submit() {
        val s = try { active() } catch (_: Exception) { return }
        // Guard: ignore Enter while a command is running (was concurrent runShell + leaked handle).
        if (lockBefore >= 0 || running != null) {
            print("(busy — Ctrl+C to kill)\n", TermDim)
            return
        }
        val full = _editor.value.text
        val lastLine = full.substringAfterLast("\n")
        val cmd = if (lastLine.startsWith(s.promptText)) lastLine.removePrefix(s.promptText) else lastLine
        val trimmed = cmd.trim()
        // Move caret to absolute end, ensure trailing newline before output
        onEditorChange(_editor.value.copy(selection = TextRange(full.length)))
        if (trimmed.isEmpty()) {
            print("\n", TermWhite)
            printPrompt()
            return
        }
        if (s.history.isEmpty() || s.history.last() != trimmed) s.history.add(trimmed)
        s.histIndex = s.history.size
        print("\n", TermWhite)
        // Freeze the transcript (prompt + command line included) until done.
        lockBefore = _editor.value.text.length
        execCmd(trimmed)
    }

    fun killRunning() {
        try {
            running?.destroyForcibly()
            print("Killed running process\n", TermRed)
            printPrompt()
        } catch (_: Exception) {}
        running = null
        lockBefore = -1
        _status.value = "idle"
    }

    fun clear() {
        // Don't wipe transcript mid-run (left lock/running dangling before).
        if (lockBefore >= 0 || running != null) {
            print("(busy — Ctrl+C to kill before clear)\n", TermDim)
            return
        }
        try {
            val s = active()
            s.segs.clear()
            s.built = AnnotatedString("")
            s.editor = TextFieldValue(AnnotatedString(""))
            _editor.value = s.editor
        } catch (_: Exception) {}
        print("LightBrowser Terminal — type 'help'\n", TermGreen)
        printPrompt()
    }

    fun fullLog(): String = try { active().built.text } catch (_: Exception) { "" }

    private fun afterCommand() {
        refreshPrompt()
        printPrompt()
        lockBefore = -1
    }

    private fun execCmd(raw: String) {
        // ── Agentic browser bridge: `b …` never reaches the shell ──
        if (raw == "b" || raw.startsWith("b ")) {
            agentCmd(raw.removePrefix("b").trim())
            return
        }
        try {
            val parts = raw.split(" ", limit = 2)
            val cmd = parts[0].lowercase()
            val arg = if (parts.size > 1) parts[1] else ""
            when (cmd) {
                "help" -> {
                    print("help/clear/history/scripts/install-alpine/alpine-status\nls [path]  cd  pwd  cat  mkdir  rm  cp  mv\nsh <cmd>  ping  curl  echo  cache  b (browser agent)\n", TermDim)
                    afterCommand()
                }
                "clear" -> clear()
                "history" -> {
                    val list = try { HistoryStorage.all(AppCtx.ctx) } catch (_: Exception) { emptyList() }
                    if (list.isEmpty()) print("No browsing history\n", TermDim)
                    else list.take(10).forEach { print("• ${it.title} – ${it.url}\n", TermDim) }
                    afterCommand()
                }
                "scripts" -> {
                    val list = try { ScriptStorage.all(AppCtx.ctx) } catch (_: Exception) { emptyList() }
                    if (list.isEmpty()) print("No userscripts\n", TermDim)
                    else list.forEach { print("• ${it.name} [${if (it.enabled) "ON" else "OFF"}]\n", TermDim) }
                    afterCommand()
                }
                "ls" -> {
                    val t = resolve(arg) ?: sandboxDir
                    if (t == null) {
                        print("Path denied\n", TermRed); afterCommand()
                    } else runShell("ls -la ${shQuote(t.absolutePath)}")
                }
                "cd" -> {
                    if (arg.isBlank()) {
                        // Bare cd → sandbox home (was: stay, confusing).
                        try { sandboxDir?.let { active().dir = it } } catch (_: Exception) {}
                        afterCommand()
                        return
                    }
                    val t = resolve(arg)
                    if (t != null && t.exists() && t.isDirectory) {
                        try { active().dir = t } catch (_: Exception) {}
                    } else print("cd: no such directory: $arg\n", TermRed)
                    afterCommand()
                }
                "pwd" -> {
                    print(((try { active().dir } catch (_: Exception) { null })?.absolutePath ?: "unknown") + "\n", TermWhite)
                    afterCommand()
                }
                "cat" -> {
                    if (arg.isBlank()) {
                        print("Usage: cat <file>\n", TermRed); afterCommand()
                    } else resolve(arg)?.let { runShell("cat ${shQuote(it.absolutePath)}") }
                        ?: run { print("Access denied\n", TermRed); afterCommand() }
                }
                "mkdir" -> {
                    if (arg.isBlank()) { print("Usage: mkdir <dir>\n", TermRed); afterCommand(); return }
                    resolve(arg)?.let {
                        print((if (it.mkdirs()) "Created ${it.name}" else "Failed") + "\n", TermDim)
                    } ?: print("Access denied\n", TermRed)
                    afterCommand()
                }
                "rm" -> {
                    if (arg.isBlank()) { print("Usage: rm <file> (use rm -r for dirs)\n", TermRed); afterCommand(); return }
                    val st = resolve(arg)
                    if (st == null) { print("Access denied\n", TermRed); afterCommand(); return }
                    if (st.isDirectory && !arg.contains("-r")) { print("rm: is a directory (use explicit path)\n", TermRed); afterCommand(); return }
                    val ok = if (st.isDirectory) st.deleteRecursively() else st.delete()
                    print((if (ok) "Deleted" else "Failed") + "\n", TermDim)
                    afterCommand()
                }
                "mv", "cp" -> {
                    val a = splitArgs(arg)
                    if (a.size < 2) {
                        print("Usage: $cmd <src> <dst>\n", TermRed)
                    } else {
                        val src = resolve(a[0])
                        val dst = resolve(a[1])
                        if (src != null && dst != null) {
                            try {
                                val ok = if (cmd == "cp") {
                                    if (src.isDirectory) { src.copyRecursively(dst, overwrite = false); true }
                                    else { src.copyTo(dst, overwrite = false); true }
                                } else src.renameTo(dst)
                                print(if (ok) "OK\n" else "Failed (exists?)\n", if (ok) TermGreen else TermRed)
                            } catch (e: Exception) { print((e.message ?: "error") + "\n", TermRed) }
                        } else print("Access denied\n", TermRed)
                    }
                    afterCommand()
                }
                "install-alpine", "alpine-install" -> installAlpine()
                "alpine-status" -> {
                    val sd = sandboxDir
                    if (sd == null) print("No sandbox\n", TermRed)
                    else {
                        print("Alpine installed: $alpineInstalled\nRoot: ${AlpineEnv.alpineDir(sd).absolutePath}\n", TermDim)
                    }
                    afterCommand()
                }
                "apk" -> {
                    if (!alpineInstalled) {
                        print("Install Alpine first: install-alpine\n", TermRed); afterCommand()
                    } else runShell("apk $arg")
                }
                "sh", "shell", "exec" -> {
                    if (arg.isBlank()) {
                        print("Usage: sh <cmd>\n", TermRed); afterCommand()
                    } else runShell(arg)
                }
                "ping" -> runShell("ping -c 3 ${arg.ifBlank { "8.8.8.8" }}")
                "curl" -> {
                    if (arg.isBlank()) {
                        print("Usage: curl <url>\n", TermRed); afterCommand()
                    } else runShell("curl -I $arg")
                }
                "cache" -> {
                    try {
                        val dir = AppCtx.ctx.cacheDir
                        val size = dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
                        print("Cache: ${size / 1024} KB\n", TermDim)
                    } catch (e: Exception) { print((e.message ?: "") + "\n", TermRed) }
                    afterCommand()
                }
                "echo" -> {
                    print("$arg\n", TermWhite); afterCommand()
                }
                "ua", "js" -> {
                    print("Run from Browser tab\n", TermDim); afterCommand()
                }
                else -> runShell(raw)
            }
        } catch (e: Exception) {
            print("exec error: ${e.message}\n", TermRed)
            afterCommand()
        }
    }

    private fun installAlpine() {
        val sd = sandboxDir ?: return
        if (_status.value == "installing") { print("Already installing…\n", TermDim); lockBefore = -1; return }
        print("Installing Alpine Linux…\n", TermWhite)
        _status.value = "installing"
        viewModelScope.launch(Dispatchers.IO) {
            val ok = try {
                AlpineEnv.install(sd) { msg ->
                    viewModelScope.launch(Dispatchers.Main) { print("$msg\n", TermDim) }
                }
            } catch (e: Exception) {
                viewModelScope.launch(Dispatchers.Main) { print("Install failed: ${e.message}\n", TermRed) }
                false
            }
            withContext(Dispatchers.Main) {
                alpineInstalled = ok
                if (ok) print("✓ Alpine ready\n", TermGreen)
                else print("Install failed — retry install-alpine\n", TermRed)
                afterCommand()
                _status.value = "idle"
            }
        }
    }

    /** Single-quote shell escaping (filenames with " $ ` are crafted via import/zip). */
    private fun shQuote(s: String): String = "'" + s.replace("'", "'\\''") + "'"

    /** Lenient URL resolver for `b open/new` (localhost + bare domains). */
    private fun resolveUrlish(t: String): String? {
        val s = t.trim()
        if (s.isEmpty()) return null
        if (s.startsWith("http://") || s.startsWith("https://") || s.startsWith("lb://")) return s
        if (!s.contains(" ") && (s.contains(".") || s.startsWith("localhost") || s.startsWith("127."))) {
            return if (s.contains("://")) s else "https://$s"
        }
        return null
    }

    /** Split respecting single/double quotes (cp/mv with spaces). */
    private fun splitArgs(raw: String): List<String> {
        val out = mutableListOf<String>()
        val cur = StringBuilder()
        var q: Char? = null
        var i = 0
        while (i < raw.length) {
            val c = raw[i]
            if (q != null) {
                if (c == q) q = null else cur.append(c)
            } else if (c == '\'' || c == '"') q = c
            else if (c.isWhitespace()) {
                if (cur.isNotEmpty()) { out.add(cur.toString()); cur.clear() }
            } else cur.append(c)
            i++
        }
        if (cur.isNotEmpty()) out.add(cur.toString())
        return out
    }

    private fun runShell(cmd: String) {
        val sd = sandboxDir ?: return
        val cwd = try { active().dir } catch (_: Exception) { null } ?: sd
        _status.value = "running"
        viewModelScope.launch(Dispatchers.IO) {
            var process: Process? = null
            try {
                val fullCmd = AlpineEnv.shellPrefix(sd) + cmd
                val env = AlpineEnv.buildEnvironment(sd, cwd)
                process = Runtime.getRuntime().exec(arrayOf("sh", "-c", fullCmd), env, cwd)
                running = process
                // Drain stdout+stderr CONCURRENTLY (serial drain deadlocks when stderr fills).
                val outBuf = StringBuilder()
                val tOut = Thread {
                    try {
                        val r = BufferedReader(InputStreamReader(process.inputStream))
                        var l: String?
                        val start = System.currentTimeMillis()
                        while (r.readLine().also { l = it } != null) {
                            synchronized(outBuf) {
                                outBuf.appendLine(l)
                                if (outBuf.length > 8000) { outBuf.append("\n…truncated"); break }
                            }
                            if (System.currentTimeMillis() - start > 15_000) break
                        }
                    } catch (_: Exception) {}
                }.also { it.isDaemon = true; it.start() }
                val tErr = Thread {
                    try {
                        val r = BufferedReader(InputStreamReader(process.errorStream))
                        var l: String?
                        while (r.readLine().also { l = it } != null) {
                            synchronized(outBuf) {
                                if (outBuf.length < 8000) outBuf.appendLine(l)
                            }
                        }
                    } catch (_: Exception) {}
                }.also { it.isDaemon = true; it.start() }
                val start = System.currentTimeMillis()
                var timedOut = false
                while (tOut.isAlive || tErr.isAlive) {
                    if (System.currentTimeMillis() - start > 15_000) { timedOut = true; break }
                    try { Thread.sleep(50) } catch (_: Exception) { break }
                    // If killed externally, stop waiting (killRunning destroys process).
                    if (running == null) break
                }
                try {
                    if (timedOut || !process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) {
                        try { process.destroyForcibly() } catch (_: Exception) {}
                        synchronized(outBuf) { outBuf.appendLine(if (timedOut) "…timed out (15s)" else "…killed after 20s") }
                    }
                } catch (_: Exception) {
                    try { process.destroy() } catch (_: Exception) {}
                }
                try { tOut.join(1000); tErr.join(1000) } catch (_: Exception) {}
                var result: String
                synchronized(outBuf) { result = outBuf.toString().trimEnd() }
                if (result.length > 4000) result = result.take(4000) + "\n…truncated"
                val finalResult = result
                withContext(Dispatchers.Main) {
                    if (finalResult.isNotEmpty()) print("$finalResult\n", TermWhite)
                    // If killed while waiting, killRunning already printed prompt — don't double.
                    if (running != null) { afterCommand(); _status.value = "idle" }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    print("sh: ${e.message}\n", TermRed)
                    afterCommand()
                    _status.value = "idle"
                }
            } finally {
                running = null
                try { process?.destroy() } catch (_: Exception) {}
            }
        }
    }

    // ── `b` browser-agent commands ──
    private fun agentCmd(line: String) {
        // Agent ops stream output without freezing typing.
        lockBefore = -1
        viewModelScope.launch(Dispatchers.IO) {
            fun out(t: String, c: Color = TermWhite) {
                viewModelScope.launch(Dispatchers.Main) { print(t, c) }
            }
            fun wrapped(origin: String, body: String) {
                out("--- PAGE CONTENT origin=$origin ---\n", TermDim)
                out(body.take(12_000) + "\n", TermWhite)
                out("--- END PAGE CONTENT ---\n", TermDim)
            }
            try {
                val parts = line.split(" ", limit = 3)
                when (parts.getOrNull(0) ?: "") {
                    "", "help" -> out(
                        "b open <url> | back | forward | reload | stop | url | title | home\n" +
                            "b tabs | new <url> | close [n] — tab control\n" +
                            "b js <expr> | text [max] | dom [css] | snap\n" +
                            "b click <ref|css> | fill <ref|css> <val> [--submit]\n" +
                            "b find <text> | next | prev — find in page\n" +
                            "b scroll [px] | shot | console [n] | cookies | save <name>\n" +
                            "b record start|stop|save <n>|list | serve\n", TermDim
                    )
                    "open" -> {
                        val url = parts.getOrNull(1) ?: ""
                        val fixed = resolveUrlish(url)
                        if (fixed == null) out("Usage: b open <url>\n", TermRed)
                        else {
                            com.lightbrowser.data.BrowserAgent.navigate(fixed)
                            out("Opening $fixed\n", TermGreen)
                        }
                    }
                    "new" -> {
                        val url = parts.getOrNull(1) ?: ""
                        val fixed = resolveUrlish(url)
                        if (fixed == null) out("Usage: b new <url>\n", TermRed)
                        else {
                            com.lightbrowser.ui.browser.TabBus.openInNewTab(fixed)
                            out("New tab: $fixed\n", TermGreen)
                        }
                    }
                    "tabs" -> {
                        val list = try { com.lightbrowser.ui.browser.TabBus.listTabs?.invoke() } catch (_: Exception) { null }
                        if (list.isNullOrEmpty()) out("(no tabs? open the Browser tab first)\n", TermDim)
                        else list.forEach { t ->
                            out("[${t.index}]${if (t.current) "●" else " "} ${(t.title.ifBlank { t.url }).take(60)} — ${t.url.take(80)}\n", TermWhite)
                        }
                    }
                    "close" -> {
                        val arg = parts.getOrNull(1)
                        if (arg != null && arg.isNotBlank() && arg != "current") {
                            val n = arg.toIntOrNull()
                            if (n == null) out("Usage: b close [n]\n", TermRed)
                            else {
                                com.lightbrowser.ui.browser.TabBus.closeTabAt?.invoke(n)
                                out("Closed tab $n\n", TermGreen)
                            }
                        } else {
                            com.lightbrowser.ui.browser.TabBus.closeTabAt?.invoke(-1)
                            out("Closed current tab\n", TermGreen)
                        }
                    }
                    "home" -> {
                        com.lightbrowser.ui.browser.TabBus.openHome?.invoke()
                        out("Home\n", TermGreen)
                    }
                    "back" -> com.lightbrowser.data.BrowserAgent.runOnPage {
                        try { if (it.canGoBack()) it.goBack() } catch (_: Exception) {}
                    }
                    "fwd", "forward" -> com.lightbrowser.data.BrowserAgent.runOnPage {
                        try { if (it.canGoForward()) it.goForward() } catch (_: Exception) {}
                    }
                    "reload" -> com.lightbrowser.data.BrowserAgent.runOnPage {
                        try { it.reload() } catch (_: Exception) {}
                    }
                    "stop" -> {
                        com.lightbrowser.data.BrowserAgent.stopLoad()
                        out("Stopped\n", TermDim)
                    }
                    "find" -> {
                        val q = line.removePrefix("find").trim()
                        com.lightbrowser.data.BrowserAgent.findInPage(q)
                        out(if (q.isBlank()) "Find cleared\n" else "Finding \"$q\"\n", TermGreen)
                    }
                    "next" -> com.lightbrowser.data.BrowserAgent.findNext(true)
                    "prev" -> com.lightbrowser.data.BrowserAgent.findNext(false)
                    "url" -> out((com.lightbrowser.data.BrowserAgent.currentUrl() ?: "(none)") + "\n", TermWhite)
                    "title" -> {
                        val r = com.lightbrowser.data.BrowserAgent.eval("(function(){return document.title;})()")
                        out("$r\n", TermWhite)
                    }
                    "js" -> {
                        val expr = line.removePrefix("js").trim()
                        if (expr.isBlank()) out("Usage: b js <expr>\n", TermRed)
                        else {
                            val r = com.lightbrowser.data.BrowserAgent.eval("(function(){try{return JSON.stringify(eval(" + expr + "));}catch(e){return 'ERR '+e;}})()")
                            wrapped(com.lightbrowser.data.BrowserAgent.currentUrl() ?: "?", r)
                        }
                    }
                    "text" -> {
                        val max = parts.getOrNull(1)?.toIntOrNull() ?: 8000
                        val r = com.lightbrowser.data.BrowserAgent.pageText(max)
                        wrapped(com.lightbrowser.data.BrowserAgent.currentUrl() ?: "?", r)
                    }
                    "dom" -> {
                        val sel = parts.getOrNull(1)?.takeIf { it.isNotBlank() } ?: "body"
                        val esc = sel.replace("\\", "\\\\").replace("'", "\\'")
                        val r = com.lightbrowser.data.BrowserAgent.eval("(function(){try{var e=document.querySelector('$esc');return e?e.outerHTML.slice(0,20000):'ERR no-node';}catch(e){return 'ERR '+e;}})()")
                        wrapped(com.lightbrowser.data.BrowserAgent.currentUrl() ?: "?", r)
                    }
                    "snap" -> {
                        val r = com.lightbrowser.data.BrowserAgent.snapshot()
                        wrapped(com.lightbrowser.data.BrowserAgent.currentUrl() ?: "?", r)
                    }
                    "click" -> {
                        val sel = parts.getOrNull(1) ?: ""
                        if (sel.isBlank()) out("Usage: b click <ref|css>\n", TermRed)
                        else {
                            val esc = sel.replace("\\", "\\\\").replace("'", "\\'")
                            val r = com.lightbrowser.data.BrowserAgent.eval("(function(){try{return window.LightAgent.click('$esc');}catch(e){return 'ERR '+e;}})()")
                            out("$r\n", if (r.contains("OK")) TermGreen else TermRed)
                        }
                    }
                    "fill" -> {
                        var rest = line.removePrefix("fill").trim()
                        var submit = false
                        if (rest.endsWith("--submit")) {
                            submit = true
                            rest = rest.removeSuffix("--submit").trim()
                        }
                        val sp = rest.indexOf(' ')
                        if (sp < 0) out("Usage: b fill <ref|css> <value> [--submit]\n", TermRed)
                        else {
                            val sel = rest.substring(0, sp).replace("\\", "\\\\").replace("'", "\\'")
                            val v = rest.substring(sp + 1).replace("\\", "\\\\").replace("'", "\\'")
                            var r = com.lightbrowser.data.BrowserAgent.eval("(function(){try{return window.LightAgent.fill('$sel','$v');}catch(e){return 'ERR '+e;}})()")
                            if (r.contains("OK") && submit) {
                                val r2 = com.lightbrowser.data.BrowserAgent.eval("(function(){try{var e=document.querySelector('$sel');var f=e?(e.form||e.closest('form')):null;if(!f)return 'ERR no-form';f.submit();return 'OK submitted';}catch(e){return 'ERR '+e;}})()")
                                r = "$r / $r2"
                            }
                            out("$r\n", if (r.contains("OK")) TermGreen else TermRed)
                        }
                    }
                    "scroll" -> {
                        val y = parts.getOrNull(1)?.toIntOrNull() ?: 500
                        val r = com.lightbrowser.data.BrowserAgent.eval("(function(){try{window.scrollBy(0,$y);return 'OK';}catch(e){return 'ERR '+e;}})()")
                        out("$r\n", TermGreen)
                    }
                    "serve" -> {
                        if (com.lightbrowser.data.BrowserAgent.serverRunning.value) {
                            out("Agent server: ${com.lightbrowser.data.BrowserAgent.serverLabel.value}\nFrom Termux: curl 'http://127.0.0.1:8089/text?token=…'\n", TermGreen)
                        } else out("Server is OFF — enable it in Browser ⋮ → Agent bridge.\n", TermDim)
                    }
                    "record" -> {
                        val sub = parts.getOrNull(1) ?: ""
                        when (sub) {
                            "start" -> {
                                com.lightbrowser.data.BrowserAgent.startRecording()
                                out("● Recording — switch to the Browser tab and tap/type. b record stop when done.\n", TermGreen)
                            }
                            "stop" -> {
                                com.lightbrowser.data.BrowserAgent.stopRecording()
                                val n = com.lightbrowser.data.BrowserAgent.recCount()
                                out("Stopped. Captured $n action(s). b record save <name> to keep.\n", TermGreen)
                            }
                            "save" -> {
                                val name = parts.getOrNull(2) ?: ""
                                val path = com.lightbrowser.data.BrowserAgent.saveRecording(name.ifBlank { "rec" })
                                if (path != null) out("Saved $path\n", TermGreen)
                                else out("Nothing to save (record first).\n", TermRed)
                            }
                            "list" -> {
                                val recs = com.lightbrowser.data.BrowserAgent.listRecordings()
                                if (recs.isEmpty()) out("(no saved recordings — sandbox/agent_recs)\n", TermDim)
                                else recs.take(10).forEach { (f, n) -> out("• $f ($n actions)\n", TermWhite) }
                            }
                            else -> out("Usage: b record start|stop|save <name>|list\n", TermRed)
                        }
                    }
                    "console" -> {
                        val n = parts.getOrNull(1)?.toIntOrNull() ?: 30
                        val lines = com.lightbrowser.data.BrowserAgent.consoleTail(n)
                        if (lines.isEmpty()) out("(console empty — JS logs appear here)\n", TermDim)
                        else {
                            out("--- CONSOLE (last ${lines.size}) ---\n", TermDim)
                            lines.forEach { out(it.take(500) + "\n", TermWhite) }
                            out("--- END CONSOLE ---\n", TermDim)
                        }
                    }
                    "shot" -> {
                        out("Capturing…\n", TermDim)
                        val path = com.lightbrowser.data.BrowserAgent.captureShot()
                        if (path != null) out("Saved $path\nOpen it in Files → Sandbox → shots.\n", TermGreen)
                        else out("Shot failed (open the Browser tab first).\n", TermRed)
                    }
                    "cookies" -> {
                        try {
                            val url = com.lightbrowser.data.BrowserAgent.currentUrl() ?: ""
                            val ck = try {
                                android.webkit.CookieManager.getInstance().getCookie(url)
                            } catch (_: Exception) { null }
                            if (ck.isNullOrBlank()) out("No cookies for ${url.ifBlank { "(no page)" }}\n", TermDim)
                            else wrapped(url, ck)
                        } catch (e: Exception) { out("cookies error: ${e.message}\n", TermRed) }
                    }
                    "save" -> {
                        val name = parts.getOrNull(1) ?: ""
                        if (name.isBlank()) out("Usage: b save <name.html|txt>\n", TermRed)
                        else {
                            val asHtml = name.lowercase().endsWith(".html")
                            val expr = if (asHtml) {
                                "(function(){try{return document.documentElement.outerHTML.slice(0,400000);}catch(e){return 'ERR '+e;}})()"
                            } else {
                                "(function(){try{return document.body?document.body.innerText.slice(0,200000):'ERR no-body';}catch(e){return 'ERR '+e;}})()"
                            }
                            var r = com.lightbrowser.data.BrowserAgent.eval(expr, maxChars = 420_000)
                            // Unwrap the JSON-string encoding evaluateJavascript adds.
                            try {
                                var s = r.trim()
                                if (s.startsWith("\"") && s.endsWith("\"") && s.length >= 2) {
                                    s = s.substring(1, s.length - 1).replace("\\n", "\n").replace("\\\"", "\"").replace("\\\\", "\\")
                                }
                                r = s
                            } catch (_: Exception) {}
                            if (r.startsWith("ERR")) out("$r\n", TermRed)
                            else {
                                try {
                                    val app = AppCtx.ctx
                                    val dir = java.io.File(app.filesDir, "sandbox/Downloads").apply { mkdirs() }
                                    val safe = name.replace("/", "_").take(80).ifBlank { "page.txt" }
                                    val out2 = java.io.File(dir, safe)
                                    out2.writeText(r, Charsets.UTF_8)
                                    out("Saved ${out2.name} (${r.length / 1024} KB) to sandbox/Downloads\n", TermGreen)
                                } catch (e: Exception) { out("save failed: ${e.message}\n", TermRed) }
                            }
                        }
                    }
                    else -> out("Unknown b command. Try: b help\n", TermRed)
                }
            } catch (e: Exception) {
                out("b error: ${e.message}\n", TermRed)
            }
            withContext(Dispatchers.Main) { afterCommand() }
        }
    }

    private fun isAllowed(path: File): Boolean {
        val sd = sandboxDir ?: return false
        return try {
            path.canonicalFile.absolutePath.startsWith(sd.canonicalFile.absolutePath)
        } catch (_: Exception) { false }
    }

    private fun resolve(input: String): File? {
        val sd = sandboxDir ?: return null
        val cwd = try { active().dir } catch (_: Exception) { null } ?: sd
        if (input.isBlank()) return cwd
        val f = if (input.startsWith("/")) File(input) else File(cwd, input)
        return if (isAllowed(f)) f else null
    }
}
