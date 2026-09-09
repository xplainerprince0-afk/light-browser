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

    // 16-color ANSI palette tuned for the black transcript background.
    private val AnsiPalette = listOf(
        Color(0xFF9E9E9E), Color(0xFFFF6B68), Color(0xFF4CAF50), Color(0xFFFFD54F),
        Color(0xFF64B5F6), Color(0xFFCE93D8), Color(0xFF4DD0E1), Color(0xFFE0E0E0),
        Color(0xFF757575), Color(0xFFFF8A80), Color(0xFF69F0AE), Color(0xFFFFE57F),
        Color(0xFF82B1FF), Color(0xFFEA80FC), Color(0xFF84FFFF), Color(0xFFFFFFFF)
    )

    /**
     * Print shell output with ANSI SGR colors. Handles `clear` escapes by wiping
     * the buffer, strips cursor/OSC sequences, folds \r progress lines.
     * (A hand-rolled SGR pass: the Termux emulator/view libs would add a full
     * View-system renderer + GPLv3 + foreground-service rewrite for the same
     * visible result — see the research note in the commit message.)
     */
    fun printAnsi(raw: String, default: Color) {
        try {
            var s = raw
            // Clear-screen escapes wipe the transcript (then we print what follows).
            if (s.contains("\u001B[2J") || s.contains("\u001B[3J") || s.contains("\u001Bc")) {
                try {
                    val sess = active()
                    sess.segs.clear()
                    sess.built = AnnotatedString("")
                    sess.editor = TextFieldValue(AnnotatedString(""))
                    _editor.value = sess.editor
                } catch (_: Exception) {}
                s = s.replace(Regex("\u001B\\[(2J|3J|H|2[H])"), "").replace("\u001Bc", "")
            }
            // Drop OSC sequences + other CSI/charset escapes (keep SGR `m` for below).
            s = s.replace(Regex("\u001B\\][^\u0007]*\u0007"), "")
            s = s.replace(Regex("\u001B[()#][0-9A-B]"), "")
            s = s.replace(Regex("\u001B[?0-9;]*[A-ORZcf-nq-uy=><]"), "")
            // Progress-bar \r: keep the last segment of each line.
            s = s.split("\n").joinToString("\n") { it.substringAfterLast("\r") }
            if (!s.endsWith("\n")) s += "\n"
            if (!s.contains("\u001B[")) {
                if (s.isNotEmpty()) print(s, default)
                return
            }
            var cur: Color = default
            val parts = s.split(Regex("\u001B\\[([0-9;]*)m"))
            // split keeps: text, codes, text, codes… — even indices are text.
            parts.forEachIndexed { idx, part ->
                if (idx % 2 == 0) {
                    if (part.isNotEmpty()) print(part, cur)
                } else {
                    val nums = part.split(";").mapNotNull { it.toIntOrNull() }
                    var i = 0
                    if (nums.isEmpty()) cur = default
                    while (i < nums.size) {
                        when (val n = nums[i]) {
                            0 -> cur = default
                            1 -> { /* bold: single-color transcript, ignore */ }
                            22, 39 -> cur = default
                            in 30..37 -> cur = AnsiPalette[n - 30]
                            in 90..97 -> cur = AnsiPalette[n - 90 + 8]
                            38 -> {
                                // 38;5;n and 38;2;r;g;b true/256-color.
                                when (nums.getOrNull(i + 1)) {
                                    5 -> {
                                        val c = nums.getOrNull(i + 2)
                                        if (c != null && c in 0..15) cur = AnsiPalette[c]
                                        i += 2
                                    }
                                    2 -> {
                                        val r = nums.getOrNull(i + 2); val g = nums.getOrNull(i + 3); val b = nums.getOrNull(i + 4)
                                        if (r != null && g != null && b != null) {
                                            cur = Color(r.coerceIn(0, 255), g.coerceIn(0, 255), b.coerceIn(0, 255))
                                        }
                                        i += 4
                                    }
                                    else -> i += 1
                                }
                            }
                            else -> { /* bg colors etc: ignore on black bg */ }
                        }
                        i++
                    }
                }
            }
        } catch (_: Exception) {
            try { print(raw, default) } catch (_: Exception) {}
        }
    }

    /** Append output text; keeps the caret glued to the end only if it was there. */
    fun print(text: String, color: Color) {        try {
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

    /** Replace a lone typed char on the current line with its CTRL code.
     *  Returns false when there is nothing to convert (caller then submits). */
    fun consumeCtrlChar(): Boolean {
        return try {
            val s = active()
            val cur = _editor.value
            val raw = cur.text.substringAfterLast("\n")
            val typed = if (raw.startsWith(s.promptText)) raw.removePrefix(s.promptText) else raw
            if (typed.length != 1) return false
            val code = typed[0].lowercaseChar() - 'a' + 1
            if (code !in 1..26) return false
            val base = cur.text.substringBeforeLast("\n").let { if (cur.text.contains("\n")) "$it\n" else "" }
            val prompt = if (raw.startsWith(s.promptText)) s.promptText else ""
            onEditorChange(
                TextFieldValue(
                    AnnotatedString(base + prompt + String(Character.toChars(code))),
                    TextRange((base + prompt).length + 1)
                )
            )
            true
        } catch (_: Exception) { false }
    }

    /**
     * CTRL+C аналог: without a PTY there are no signals, so interrupting means
     * killing the running process. Idle + empty line: fresh prompt (like a tty).
     */
    fun interrupt() {
        try {
            if (running != null || lockBefore >= 0) {
                killRunning()
                print("^C\n", TermDim)
                printPrompt()
            } else {
                print("^C\n", TermDim)
                printPrompt()
            }
        } catch (_: Exception) {}
    }

    /** CTRL+D аналог: EOF on an idle line clears the screen. */
    fun sendEof() {
        try {
            if (running != null || lockBefore >= 0) {
                print("(busy — ^C to interrupt)\n", TermDim)
                return
            }
            clear()
        } catch (_: Exception) {}
    }

    /**
     * Sticky-key application with REAL bytes (was "^x"/"M-x" caret notation).
     * CTRL+letter → control byte, CTRL+/ → US (\u001F), ALT+x → ESC+x.
     * Returns true when consumed (caller clears the sticky).
     */
    fun applyStickyKey(sticky: String?, ins: String): Boolean {
        if (sticky == null) {
            insertText(ins)
            return false
        }
        try {
            when (sticky) {
                "CTRL" -> {
                    if (ins.length == 1) {
                        val c = ins[0]
                        val code = when {
                            c.lowercaseChar() in 'a'..'z' -> c.lowercaseChar() - 'a' + 1
                            c == '/' -> 0x1F
                            else -> -1
                        }
                        if (code >= 0) {
                            insertText(String(Character.toChars(code)))
                            return true
                        }
                    }
                    insertText(ins)
                    return true
                }
                else -> { // ALT: ESC prefix, like a real Meta key.
                    insertText("\u001B$ins")
                    return true
                }
            }
        } catch (_: Exception) {
            try { insertText(ins) } catch (_: Exception) {}
            return true
        }
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
        if (s.history.isEmpty() || s.history.last() != trimmed) {
            s.history.add(trimmed)
            if (s.history.size > 200) s.history.removeAt(0)
        }
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
        // Reset the lock: submit() sets it before execCmd, so a guarded early-return
        // here permanently bricked the terminal after every `clear` command.
        lockBefore = -1
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
                    print("help/clear/history/scripts/install-alpine/alpine-status\nls [path]  cd  pwd  cat  mkdir  rm [-r]  cp  mv\nsh <cmd>  ping  curl  echo  cache  b (browser agent)\nopencode-install | opencode-status | opencode <args> (AI agent, needs install first)\ntoolbox | toolbox-install <name|essentials|agent> (dev tools via apk)\nKeys: CTRL+Enter=interrupt(^C)  ^C key=kills  ^D=clear  ALT=sends ESC\n", TermDim)
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
                    if (arg.isBlank()) { print("Usage: rm [-r] <file>\n", TermRed); afterCommand(); return }
                    val toks = splitArgs(arg)
                    val recursive = toks.any { it.startsWith("-") && it.contains("r") }
                    val target = toks.lastOrNull { !it.startsWith("-") } ?: ""
                    if (target.isBlank()) { print("Usage: rm [-r] <file>\n", TermRed); afterCommand(); return }
                    val st = resolve(target)
                    if (st == null) { print("Access denied\n", TermRed); afterCommand(); return }
                    if (st.isDirectory && !recursive) { print("rm: is a directory (use rm -r)\n", TermRed); afterCommand(); return }
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
                "opencode-install", "opencode-update" -> installOpencode()
                "opencode-status", "oc-status" -> {
                    try {
                        val app = AppCtx.ctx
                        if (com.lightbrowser.data.OpencodeManager.isInstalled(app)) {
                            val v = com.lightbrowser.data.OpencodeManager.installedVersion(app) ?: "?"
                            val kb = com.lightbrowser.data.OpencodeManager.binFile(app).length() / 1024
                            print("opencode $v installed (${kb}KB)\n`opencode --help`, `opencode run \"task\"`\n", TermGreen)
                        } else print("opencode not installed — run `opencode-install` (~50MB, aarch64 only)\n", TermDim)
                    } catch (e: Exception) { print("status error: ${e.message}\n", TermRed) }
                    afterCommand()
                }
                "opencode", "oc" -> {
                    if (arg.isBlank()) {
                        print("Usage: opencode --help | opencode run \"task\" | opencode-status\nThe interactive TUI needs a PTY — use non-interactive `run`.\n", TermDim)
                        afterCommand()
                    } else {
                        val app = AppCtx.ctx
                        if (!com.lightbrowser.data.OpencodeManager.isInstalled(app)) {
                            print("opencode not installed — run `opencode-install` first\n", TermRed)
                            afterCommand()
                        } else {
                            val bin = com.lightbrowser.data.OpencodeManager.binFile(app).absolutePath
                            // Long timeout: agent runs take minutes. Redirect to file for more output.
                            runShell("$bin $arg", timeoutSec = 300)
                        }
                    }
                }
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
                "toolbox", "tools" -> {
                    print(com.lightbrowser.data.ToolboxManager.listText() + "\ntoolbox-install <name|essentials|agent>  toolbox-remove <apk>  toolbox-update\n", TermDim)
                    afterCommand()
                }
                "toolbox-install", "tool-install" -> {
                    if (!alpineInstalled) {
                        print("Install Alpine first: install-alpine\n", TermRed); afterCommand(); return
                    }
                    val sd = sandboxDir
                    if (sd == null) { print("No sandbox\n", TermRed); afterCommand(); return }
                    val pkgs = com.lightbrowser.data.ToolboxManager.resolve(arg)
                    if (pkgs.isEmpty()) { print("Unknown tool: $arg — try `toolbox`\n", TermRed); afterCommand(); return }
                    if (!com.lightbrowser.data.ToolboxManager.ensureNetFiles(sd)) {
                        print("Could not seed apk config\n", TermRed); afterCommand(); return
                    }
                    print("Installing ${pkgs.joinToString(" ")} (~apk download)…\n", TermWhite)
                    // Long timeout: apk pulls (esp. node/python) take minutes.
                    runShell("apk update && apk add --no-cache ${pkgs.joinToString(" ")}", timeoutSec = 180)
                }
                "toolbox-remove", "tool-remove" -> {
                    if (!alpineInstalled) {
                        print("Install Alpine first: install-alpine\n", TermRed); afterCommand(); return
                    }
                    if (arg.isBlank()) { print("Usage: toolbox-remove <apk>\n", TermRed); afterCommand(); return }
                    runShell("apk del $arg", timeoutSec = 60)
                }
                "toolbox-update" -> {
                    if (!alpineInstalled) {
                        print("Install Alpine first: install-alpine\n", TermRed); afterCommand(); return
                    }
                    runShell("apk update && apk upgrade", timeoutSec = 180)
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

    private var ocBusy = false

    private fun installOpencode() {
        val sd = sandboxDir ?: return
        if (ocBusy || _status.value == "installing") {
            print("Already installing…\n", TermDim)
            afterCommand()
            return
        }
        ocBusy = true
        print("Installing opencode-termux (AI agent, ~50MB)…\n", TermWhite)
        _status.value = "installing"
        viewModelScope.launch(Dispatchers.IO) {
            val ok = try {
                com.lightbrowser.data.OpencodeManager.install(AppCtx.ctx) { msg ->
                    viewModelScope.launch(Dispatchers.Main) { print("$msg\n", TermDim) }
                }
            } catch (e: Exception) {
                viewModelScope.launch(Dispatchers.Main) { print("Install failed: ${e.message}\n", TermRed) }
                false
            }
            withContext(Dispatchers.Main) {
                ocBusy = false
                if (ok) print("Use: `opencode --help` or `opencode run \"your task\"`\n", TermGreen)
                afterCommand()
                _status.value = "idle"
                try { sd.resolve("bin").mkdirs() } catch (_: Exception) {}
            }
        }
    }

    private fun installAlpine() {
        val sd = sandboxDir ?: return
        if (_status.value == "installing") {
            print("Already installing…\n", TermDim)
            afterCommand()
            return
        }
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

    /** Built-ins win over user aliases. */
    private fun isBuiltinB(head: String): Boolean = BuiltinB.contains(head.trim().lowercase())

    /** Lenient URL resolver for `b open/new` (localhost + bare domains). */
    private fun resolveUrlish(t: String): String? {        val s = t.trim()
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

    private fun runShell(cmd: String, timeoutSec: Int = 15) {
        val sd = sandboxDir ?: return
        val cwd = try { active().dir } catch (_: Exception) { null } ?: sd
        _status.value = "running"
        val wallMs = (timeoutSec.coerceIn(5, 600) * 1000).toLong()
        viewModelScope.launch(Dispatchers.IO) {
            var process: Process? = null
            try {
                val fullCmd = AlpineEnv.shellPrefix(sd) + cmd
                val env = AlpineEnv.buildEnvironment(sd, cwd)
                process = Runtime.getRuntime().exec(arrayOf("sh", "-c", fullCmd), env, cwd)
                running = process
                // Nothing ever writes to stdin: close it so readers can't hang on it.
                try { process.outputStream.close() } catch (_: Exception) {}
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
                            if (System.currentTimeMillis() - start > wallMs) break
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
                    if (System.currentTimeMillis() - start > wallMs) { timedOut = true; break }
                    try { Thread.sleep(50) } catch (_: Exception) { break }
                    // If killed externally, stop waiting (killRunning destroys process).
                    if (running == null) break
                }
                try {
                    if (timedOut || !process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) {
                        try { process.destroyForcibly() } catch (_: Exception) {}
                        synchronized(outBuf) { outBuf.appendLine(if (timedOut) "…timed out (${wallMs / 1000}s)" else "…killed after wait") }
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
                    // ANSI-aware: colors + `clear` + progress-bar folding.
                    if (finalResult.isNotEmpty()) printAnsi(finalResult, TermWhite)
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
                // User-alias expansion (max 3 hops, builtins always win) so new
                // `b` commands can be added inside the terminal, no app update.
                var cmd = line
                var hops = 0
                while (hops < 3) {
                    val head = cmd.substringBefore(" ").trim()
                    if (head.isEmpty() || isBuiltinB(head)) break
                    val exp = BrowserAliases.expand(cmd) ?: break
                    cmd = exp; hops++
                }
                if (hops > 0) out("→ $cmd\n", TermDim)
                val parts = cmd.split(" ", limit = 3)
                when (parts.getOrNull(0) ?: "") {
                    "", "help" -> out(
                        "b open <url> | back | forward | reload | stop | url | title | home\n" +
                            "b tabs | new <url> | close [n] — tab control\n" +
                            "b js <expr> | text [max] | dom [css] | snap\n" +
                            "b click <ref|css> | fill <ref|css> <val> [--submit]\n" +
                            "b pos <ref|css> → coords | b tap <x> <y> | b swipe <x1> <y1> <x2> <y2> [ms]\n" +
                            "b find <text> | next | prev | b scroll-to <x> <y> | b scroll [px]\n" +
                            "b shot | console [n] | cookies | save <name>\n" +
                            "b alias [name expansion] | unalias <name> — your own cmds, no update needed\n" +
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
                    "pos" -> {
                        // Resolve ref/css → screen coords (CSS px). Feed them to `b tap`.
                        val sel = parts.getOrNull(1) ?: ""
                        if (sel.isBlank()) out("Usage: b pos <ref|css>  (try b snap first)\n", TermRed)
                        else {
                            val raw = com.lightbrowser.data.BrowserAgent.locateBlocking(sel)
                            if (raw.startsWith("ERR")) out("$raw\n", TermRed)
                            else {
                                try {
                                    // Double-encoded: evaluateJavascript quotes + locate's JSON.stringify.
                                    var s = raw.trim()
                                    repeat(2) {
                                        if (s.startsWith("\"") && s.endsWith("\"") && s.length >= 2) {
                                            s = try { org.json.JSONObject("{\"v\":$s}").optString("v", s) } catch (_: Exception) { s }
                                        }
                                    }
                                    val o = org.json.JSONObject(s)
                                    val x = o.optInt("x", -1); val y = o.optInt("y", -1)
                                    if (x < 0 || y < 0) out("$raw\n", TermRed)
                                    else out("x=$x y=$y w=${o.optInt("w")} h=${o.optInt("h")}  →  b tap $x $y\n", TermGreen)
                                } catch (_: Exception) { out("$raw\n", TermWhite) }
                            }
                        }
                    }
                    "tap" -> {
                        val x = parts.getOrNull(1)?.toFloatOrNull()
                        val rest = parts.getOrNull(2)?.split(" ")?.firstOrNull()?.toFloatOrNull()
                        if (x == null || rest == null) out("Usage: b tap <x> <y>  (get coords via b pos)\n", TermRed)
                        else {
                            com.lightbrowser.data.BrowserAgent.tapAt(x, rest)
                            out("Tapped $x,$rest\n", TermGreen)
                        }
                    }
                    "swipe" -> {
                        // b swipe x1 y1 x2 y2 [ms] — drags: scrolls, sliders, drawers.
                        val nums = line.substringAfter("swipe").trim().split(Regex("\\s+")).mapNotNull { it.toFloatOrNull() }
                        if (nums.size < 4) out("Usage: b swipe <x1> <y1> <x2> <y2> [ms]\n", TermRed)
                        else {
                            com.lightbrowser.data.BrowserAgent.swipe(nums[0], nums[1], nums[2], nums[3], nums.getOrNull(4)?.toLong()?.coerceIn(50, 2000) ?: 300)
                            out("Swiped\n", TermGreen)
                        }
                    }
                    "scroll-to" -> {
                        val nums = line.substringAfter("scroll-to").trim().split(Regex("\\s+")).mapNotNull { it.toIntOrNull() }
                        if (nums.size < 2) out("Usage: b scroll-to <x> <y>\n", TermRed)
                        else {
                            val r = com.lightbrowser.data.BrowserAgent.eval("(function(){try{window.scrollTo(${nums[0]},${nums[1]});return 'OK '+window.scrollX+','+window.scrollY;}catch(e){return 'ERR '+e;}})()")
                            out("$r\n", TermGreen)
                        }
                    }
                    "alias" -> {
                        val sub = parts.getOrNull(1) ?: ""
                        if (sub.isBlank()) {
                            val all = BrowserAliases.all()
                            if (all.isEmpty()) out("No aliases. Define: b alias <name> <expansion with \$1 \$2 \$@>\n", TermDim)
                            else all.forEach { (k, v) -> out("$k  →  $v\n", TermWhite) }
                        } else {
                            val expansion = cmd.substringAfter(sub).trim()
                            if (expansion.isBlank()) out("Usage: b alias <name> <expansion>\n", TermRed)
                            else if (!sub.matches(Regex("[a-z0-9_-]+"))) out("Name must be [a-z0-9_-]+\n", TermRed)
                            else if (isBuiltinB(sub)) out("'$sub' is built-in — aliases can't shadow builtins.\n", TermRed)
                            else {
                                BrowserAliases.set(sub, expansion)
                                out("Alias '$sub' saved\n", TermGreen)
                            }
                        }
                    }
                    "unalias" -> {
                        val sub = parts.getOrNull(1) ?: ""
                        if (sub.isBlank()) out("Usage: b unalias <name>\n", TermRed)
                        else {
                            BrowserAliases.remove(sub)
                            out("Removed '$sub'\n", TermGreen)
                        }
                    }
                    "url" -> out((com.lightbrowser.data.BrowserAgent.currentUrl() ?: "(none)") + "\n", TermWhite)
                    "title" -> {
                        val r = com.lightbrowser.data.BrowserAgent.eval("(function(){return document.title;})()")
                        out("$r\n", TermWhite)
                    }
                    "js" -> {
                        val expr = cmd.removePrefix("js").trim()
                        if (expr.isBlank()) out("Usage: b js <expr>\n", TermRed)
                        else {
                            val r = com.lightbrowser.data.BrowserAgent.eval("(function(){try{return JSON.stringify(eval(" + expr + "));}catch(e){return 'ERR '+e;}})()")
                            wrapped(com.lightbrowser.data.BrowserAgent.currentUrl() ?: "?", r)
                        }
                    }
                    "text" -> {
                        val max = parts.getOrNull(1)?.toIntOrNull()?.coerceIn(100, 60_000) ?: 8000
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
                        var rest = cmd.removePrefix("fill").trim()
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
                        when (parts.getOrNull(1)) {
                            "on", "start" -> {
                                try { com.lightbrowser.data.BrowserAgent.startServer() } catch (e: Exception) {
                                    out("Start failed: ${e.message}\n", TermRed)
                                    return@launch
                                }
                                out("Agent server: ${com.lightbrowser.data.BrowserAgent.serverLabel.value}\n", TermGreen)
                            }
                            "off", "stop" -> {
                                try { com.lightbrowser.data.BrowserAgent.stopServer() } catch (_: Exception) {}
                                out("Server stopped.\n", TermDim)
                            }
                            else -> {
                                if (com.lightbrowser.data.BrowserAgent.serverRunning.value) {
                                    out("Agent server: ${com.lightbrowser.data.BrowserAgent.serverLabel.value}\nFrom Termux: curl 'http://127.0.0.1:8089/text?token=…'\n", TermGreen)
                                } else out("Server is OFF — b serve on to start it.\n", TermDim)
                            }
                        }
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
                                    s = try { org.json.JSONObject("{\"v\":$s}").optString("v", s) } catch (_: Exception) {
                                        s.substring(1, s.length - 1).replace("\\n", "\n").replace("\\\"", "\"")
                                    }
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
                    else -> out("Unknown b command. Try: b help (or define your own: b alias name expansion)\n", TermRed)
                }
            } catch (e: Exception) {
                out("b error: ${e.message}\n", TermRed)
            }
            withContext(Dispatchers.Main) { afterCommand() }
        }
    }

        private fun isAllowed(path: File): Boolean {        val sd = sandboxDir ?: return false
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

/** Built-in `b` command heads — aliases may not shadow these. */
private val BuiltinB = setOf(
    "help", "open", "new", "tabs", "close", "home", "back", "fwd", "forward",
    "reload", "stop", "url", "title", "js", "text", "dom", "snap", "click",
    "fill", "pos", "tap", "swipe", "scroll", "scroll-to", "find", "next",
    "prev", "shot", "console", "cookies", "save", "serve", "record",
    "alias", "unalias"
)

/**
 * User-defined `b` aliases, stored in SharedPreferences so new terminal
 * commands can be added on-device without an app update.
 * Expansion supports $1..$9 and $@ (all args). Example:
 *   b alias read "text 4000"      →  b read runs `text 4000`
 *   b alias g "open google.com"   →  b g runs `open google.com`
 */
object BrowserAliases {
    private const val PREF = "term_aliases"
    private const val KEY = "aliases"

    private fun prefs() = try {
        com.lightbrowser.data.AppCtx.ctx.getSharedPreferences(PREF, android.content.Context.MODE_PRIVATE)
    } catch (_: Exception) { null }

    fun all(): Map<String, String> {
        return try {
            val raw = prefs()?.getString(KEY, null) ?: return emptyMap()
            val o = org.json.JSONObject(raw)
            buildMap {
                o.keys().forEach { k ->
                    try { put(k, o.optString(k, "")) } catch (_: Exception) {}
                }
            }
        } catch (_: Exception) { emptyMap() }
    }

    fun set(name: String, expansion: String) {
        try {
            val o = org.json.JSONObject()
            all().forEach { (k, v) -> o.put(k, v) }
            o.put(name, expansion.take(500))
            prefs()?.edit()?.putString(KEY, o.toString())?.apply()
        } catch (_: Exception) {}
    }

    fun remove(name: String) {
        try {
            val o = org.json.JSONObject()
            all().filterKeys { it != name }.forEach { (k, v) -> o.put(k, v) }
            prefs()?.edit()?.putString(KEY, o.toString())?.apply()
        } catch (_: Exception) {}
    }

    /** Expand `name arg1 arg2…` via stored template ($1..$9, $@). Null if no alias. */
    fun expand(line: String): String? {
        val t = line.trim()
        if (t.isEmpty()) return null
        val name = t.substringBefore(" ").trim()
        val args = t.substringAfter(" ", "").trim()
            .split(Regex("\\s+")).filter { it.isNotEmpty() }
        var template = all()[name] ?: return null
        if (template.startsWith("b ")) template = template.removePrefix("b ").trim()
        for (i in args.indices.take(9)) template = template.replace("\$${i + 1}", args[i])
        template = template.replace(Regex("\\$[1-9]"), "")
        template = template.replace("\$@", args.joinToString(" "))
        return template.trim().take(1000).ifBlank { null }
    }
}
