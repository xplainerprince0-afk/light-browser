package com.rg.webloom.ui.terminal

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rg.webloom.data.AlpineEnv
import com.rg.webloom.data.AppCtx
import com.rg.webloom.data.HistoryStorage
import com.rg.webloom.data.Prefs
import com.rg.webloom.data.ScriptStorage
import com.rg.webloom.data.TermEnv
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

    // Volatile: written on IO (spawn/teardown), read on Main (busy guard).
    // A stale read used to allow concurrent runShell + leaked handles.
    @Volatile
    private var running: Process? = null
    /** Offset before which the transcript is frozen while a command runs (-1 idle). */
    private var lockBefore: Int = -1

    /**
     * Batch runner for `b do` / `b run` / `b replay`: queued b-lines drained
     * one per afterCommand ( chaining without refactoring the dispatcher).
     * Steps print `› line`; failures stop the batch unless `!`-prefixed.
     */
    private data class BStep(val line: String, val soft: Boolean)
    private var bQueue: ArrayDeque<BStep>? = null
    private var bQueueDelayMs: Long = 250
    private var batchFailed = false
    private var batchCurSoft = false

    var sandboxDir: File? = null
        private set
    var alpineInstalled = false
        private set

    override fun onCleared() {
        // Tear down any live child + drain threads (was: process, fds and
        // two blocking readLine() threads leaked on rotate/exit).
        try {
            running?.destroyForcibly()
        } catch (_: Exception) {}
        running = null
        lockBefore = -1
        super.onCleared()
    }

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
            try { AlpineEnv.ensureRuntimeFiles(sd) } catch (_: Exception) {}
            try { AlpineEnv.ensureBFunction(sd) } catch (_: Exception) {}
            alpineInstalled = AlpineEnv.isInstalled(sd)
            val s = Sess(name = "main", dir = sd)
            store.add(s)
            _activeId.value = s.id
            refreshPrompt()
            print("Webloom Terminal (Alpine sandbox)\n", TermGreen)
            print(if (alpineInstalled) "Alpine Linux ready\n" else "Run 'install-alpine' for Alpine\n", TermDim)
            printPrompt()
            emitSessions()
        } catch (e: Exception) {
            print("Sandbox init failed: ${e.message}\n", TermRed)
        }
    }

    // ── Sessions ──
    fun newSession() {
        if (store.size >= 8) {
            print("Session limit reached (8) — close one first\n", TermRed)
            printPrompt()
            return
        }
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
        // ^C stops automation too.
        bQueue = null
        batchFailed = false
    }

    fun clear() {
        // Reset the lock: submit() sets it before execCmd, so a guarded early-return
        // here permanently bricked the terminal after every `clear` command.
        lockBefore = -1
        // Fresh transcript stops any running batch as well.
        bQueue = null
        batchFailed = false
        try {
            val s = active()
            s.segs.clear()
            s.built = AnnotatedString("")
            s.editor = TextFieldValue(AnnotatedString(""))
            _editor.value = s.editor
        } catch (_: Exception) {}
        print("Webloom Terminal — type 'help'\n", TermGreen)
        printPrompt()
    }

    fun fullLog(): String = try { active().built.text } catch (_: Exception) { "" }

    private fun afterCommand() {
        refreshPrompt()
        printPrompt()
        lockBefore = -1
        maybeNextQueued()
    }

    /** Drain one queued b-line per completed command (Main thread only). */
    private fun maybeNextQueued() {
        val q = bQueue ?: return
        if (q.isEmpty()) { bQueue = null; return }
        if (lockBefore >= 0 || running != null) return
        if (batchFailed && !batchCurSoft) {
            bQueue = null
            batchFailed = false
            print("(batch stopped on error — prefix a step with ! to skip past it)\n", TermRed)
            return
        }
        if (batchFailed && batchCurSoft) { batchFailed = false }
        val step = q.removeFirst()
        if (q.isEmpty()) bQueue = null
        batchCurSoft = step.soft
        viewModelScope.launch(Dispatchers.Main) {
            if (bQueueDelayMs > 0) kotlinx.coroutines.delay(bQueueDelayMs)
            if (lockBefore >= 0 || running != null) {
                // User started something meanwhile — requeue at front.
                val qq = bQueue ?: ArrayDeque()
                qq.addFirst(step)
                bQueue = qq
                batchCurSoft = false
                return@launch
            }
            print("› ${step.line}\n", TermDim)
            try { execCmd(step.line) } catch (_: Exception) {}
        }
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
                    print("help/clear/history/scripts/install-alpine/alpine-status\nls [path]  cd  pwd  cat  mkdir  rm [-r]  cp  mv\nsh <cmd>  ping  curl  echo  cache  b (browser agent)\nopencode-install | opencode-fix | opencode-diag | opencode-status | opencode <args> (AI agent, needs install first)\ntoolbox | toolbox-install <name|essentials|agent|opencode> (dev tools via apk)  b-setup (b for PTY)\nKeys: CTRL+Enter=interrupt(^C)  ^C key=kills  ^D=clear  ALT=sends ESC\n", TermDim)
                    afterCommand()
                }
                "clear" -> clear()
                "history" -> {
                    // History JSON parses on IO (was: up to 200 entries on Main).
                    // Unlock first: this branch is async like agentCmd.
                    lockBefore = -1
                    viewModelScope.launch(Dispatchers.IO) {
                        val list = try { HistoryStorage.all(AppCtx.ctx) } catch (_: Exception) { emptyList() }
                        withContext(Dispatchers.Main) {
                            if (list.isEmpty()) print("No browsing history\n", TermDim)
                            else list.take(10).forEach { print("• ${it.title} – ${it.url}\n", TermDim) }
                            afterCommand()
                        }
                    }
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
                    val abs = try { active().dir?.absolutePath } catch (_: Exception) { null }
                    print(homeify(abs ?: "unknown") + "\n", TermWhite)
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
                    // Never wipe the sandbox root itself (rm -r . / absolute sandbox path).
                    try {
                        val sd = sandboxDir
                        if (sd != null && st.canonicalFile.absolutePath == sd.canonicalFile.absolutePath) {
                            print("Refusing to delete sandbox root\n", TermRed); afterCommand(); return
                        }
                    } catch (_: Exception) {}
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
                "opencode-fix" -> {
                    try {
                        val msg = com.rg.webloom.data.OpencodeManager.fixInstall(AppCtx.ctx)
                        val bad = msg.contains("FAILED") || msg.contains("MISSING") || msg.contains("missing")
                        print("opencode-fix:\n$msg\n", if (bad) TermRed else TermGreen)
                    } catch (e: Exception) { print("fix error: ${e.message}\n", TermRed) }
                    afterCommand()
                }
                "opencode-diag" -> {
                    try {
                        print(com.rg.webloom.data.OpencodeManager.diagnose(AppCtx.ctx) + "\n", TermDim)
                    } catch (e: Exception) { print("diag error: ${e.message}\n", TermRed) }
                    afterCommand()
                }
                "opencode-status", "oc-status" -> {
                    try {
                        val app = AppCtx.ctx
                        if (com.rg.webloom.data.OpencodeManager.isInstalled(app)) {
                            val v = com.rg.webloom.data.OpencodeManager.installedVersion(app) ?: "?"
                            val kb = com.rg.webloom.data.OpencodeManager.binFile(app).length() / 1024
                            print("opencode $v installed (${kb}KB)\n`opencode --help`, `opencode run \"task\"`\n", TermGreen)
                        } else print("opencode not installed — run `opencode-install` (~50MB, aarch64 only)\n", TermDim)
                    } catch (e: Exception) { print("status error: ${e.message}\n", TermRed) }
                    afterCommand()
                }
                "opencode", "oc" -> {
                    if (arg.isBlank()) {
                        print("Usage: opencode --help | opencode run \"task\" | opencode-status\nThe full-screen TUI can't render here — switch to the PTY tab and tap `opencode`.\n", TermDim)
                        afterCommand()
                    } else {
                        val app = AppCtx.ctx
                        val bin = com.rg.webloom.data.OpencodeManager.binFile(app)
                        if (!bin.exists()) {
                            print("opencode not installed — run `opencode-install` first\n", TermRed)
                            afterCommand()
                        } else {
                            val cmd = buildLaunch(bin, arg)
                            if (cmd == null) {
                                print("Can't launch — run `opencode-fix` then `opencode-diag`\n", TermRed)
                                afterCommand()
                            } else {
                                // Long timeout: agent runs take minutes. Redirect to file for more output.
                                runShell(cmd, timeoutSec = 300)
                            }
                        }
                    }
                }
                "alpine-status" -> {
                    val sd = sandboxDir
                    if (sd == null) print("No sandbox\n", TermRed)
                    else {
                        print("Alpine installed: $alpineInstalled\nRoot: ~\n", TermDim)
                    }
                    afterCommand()
                }
                "apk" -> {
                    if (!alpineInstalled) {
                        print("Install Alpine first: install-alpine\n", TermRed); afterCommand()
                    } else runShell("apk $arg")
                }
                "toolbox", "tools" -> {
                    // Annotated with installed state (apk query on IO).
                    lockBefore = -1
                    viewModelScope.launch(Dispatchers.IO) {
                        val inst = apkInstalled()
                        val alp = try {
                            sandboxDir?.let { AlpineEnv.isInstalled(it) } ?: false
                        } catch (_: Exception) { false }
                        withContext(Dispatchers.Main) {
                            if (!alp) {
                                print("Alpine not installed — run `install-alpine` first\n", TermRed)
                            } else {
                                val sb = StringBuilder("Tools (runtime download, apk — ✓ installed, ○ missing):\n")
                                for (t in com.rg.webloom.data.ToolboxManager.TOOLS) {
                                    val got = t.pkgs.count { p -> inst.any { verPkgInstalled(it, p) } }
                                    val mark = when {
                                        got >= t.pkgs.size -> "✓"
                                        got > 0 -> "◐"
                                        else -> "○"
                                    }
                                    sb.append("$mark ${t.name} (~${t.approxMb}MB) — ${t.desc}\n")
                                }
                                sb.append(com.rg.webloom.data.ToolboxManager.setsText())
                                sb.append("\ntoolbox-install <name|essentials|agent|opencode>  toolbox-info <name>  toolbox-remove <apk>  toolbox-update")
                                print(sb.toString() + "\n", TermDim)
                            }
                            afterCommand()
                        }
                    }
                }
                "toolbox-info", "tool-info" -> {
                    val t = com.rg.webloom.data.ToolboxManager.byName(arg)
                    if (t == null) {
                        val s = suggestB(
                            arg.lowercase().split(Regex("\\s+")).firstOrNull() ?: "",
                            com.rg.webloom.data.ToolboxManager.TOOLS.map { it.name }
                        )
                        print(
                            if (s != null) "Unknown tool: $arg — did you mean '$s'?\n"
                            else "Unknown tool: $arg — try `toolbox`\n",
                            TermRed
                        )
                        afterCommand()
                        return
                    }
                    lockBefore = -1
                    viewModelScope.launch(Dispatchers.IO) {
                        val inst = apkInstalled()
                        withContext(Dispatchers.Main) {
                            print("${t.name} (~${t.approxMb}MB) — ${t.desc}\napk: ${t.pkgs.joinToString(" ")}\n", TermWhite)
                            t.pkgs.forEach { p ->
                                val ok = inst.any { verPkgInstalled(it, p) }
                                print("${if (ok) "✓" else "○"} $p ${if (ok) "installed" else "missing"}\n", if (ok) TermGreen else TermDim)
                            }
                            afterCommand()
                        }
                    }
                }
                "toolbox-install", "tool-install" -> {
                    if (!alpineInstalled) {
                        print("Install Alpine first: install-alpine\n", TermRed); afterCommand(); return
                    }
                    val sd = sandboxDir
                    if (sd == null) { print("No sandbox\n", TermRed); afterCommand(); return }
                    val pkgs = com.rg.webloom.data.ToolboxManager.resolve(arg)
                    if (pkgs.isEmpty()) {
                        val s = suggestB(
                            arg.lowercase().split(Regex("\\s+")).firstOrNull() ?: "",
                            com.rg.webloom.data.ToolboxManager.TOOLS.map { it.name } +
                                listOf("essentials", "agent", "opencode", "all")
                        )
                        print(
                            if (s != null) "Unknown tool: $arg — did you mean '$s'?\n"
                            else "Unknown tool: $arg — try `toolbox`\n",
                            TermRed
                        )
                        afterCommand(); return
                    }
                    // Disk-space guard before big pulls (build-base alone ≈170MB).
                    try {
                        val needMb = com.rg.webloom.data.ToolboxManager.estMb(arg)
                        val freeMb = (sd.usableSpace / (1024 * 1024)).toInt()
                        if (needMb > 0 && freeMb < (needMb * 1.5 + 50).toInt()) {
                            print("Not enough space: need ~${needMb}MB (+cache), have ${freeMb}MB free\n", TermRed)
                            afterCommand(); return
                        }
                    } catch (_: Exception) {}
                    if (!com.rg.webloom.data.ToolboxManager.ensureNetFiles(sd)) {
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
                "b-setup" -> {
                    val sd = sandboxDir
                    if (sd == null) { print("No sandbox\n", TermRed); afterCommand(); return }
                    val ok = try { AlpineEnv.ensureBFunction(sd) } catch (_: Exception) { false }
                    print(if (ok) "`b` ready for PTY shells (~/.profile) — start the server first (Agent panel or EXEC `b serve on`)\n"
                    else "Could not write ~/.profile\n", if (ok) TermGreen else TermRed)
                    afterCommand()
                }
                "sh", "shell", "exec" -> {
                    if (arg.isBlank()) {
                        print("Usage: sh <cmd>\n", TermRed); afterCommand()
                    } else runShell(arg)
                }
                "run" -> {
                    // Generic ELF/script runner: ELF → system linker (noexec
                    // workaround), #! script → its interpreter, else error.
                    if (arg.isBlank()) {
                        print("Usage: run <program> [args] — ELFs + scripts in sandbox\n", TermRed); afterCommand(); return
                    }
                    val toks = splitArgs(arg)
                    val target = resolveBin(toks[0])
                    if (target == null || !target.exists()) {
                        print("Not found in sandbox: ${toks[0]}\n", TermRed); afterCommand(); return
                    }
                    val rest = toks.drop(1).joinToString(" ") { shQuote(it) }
                    val cmd = buildLaunch(target, rest)
                    if (cmd == null) {
                        print("Can't launch ${toks[0]} (need ELF or #! script)\n", TermRed); afterCommand(); return
                    }
                    runShell(cmd, timeoutSec = 60)
                }
                "ping" -> runShell("ping -c 3 ${arg.ifBlank { "8.8.8.8" }}")
                "curl" -> {
                    if (arg.isBlank()) {
                        print("Usage: curl <url>\n", TermRed); afterCommand()
                    } else runShell("curl -I $arg")
                }
                "cache" -> {
                    // File walk on IO (was: walkTopDown synchronously on Main).
                    lockBefore = -1
                    viewModelScope.launch(Dispatchers.IO) {
                        var msg = ""
                        var color = TermDim
                        try {
                            val dir = AppCtx.ctx.cacheDir
                            val size = dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
                            msg = "Cache: ${size / 1024} KB\n"
                        } catch (e: Exception) {
                            msg = (e.message ?: "") + "\n"
                            color = TermRed
                        }
                        val out = msg
                        val col = color
                        withContext(Dispatchers.Main) {
                            print(out, col)
                            afterCommand()
                        }
                    }
                }
                "kbd-diag" -> {
                    print(
                        "imeBottom=${InsetDebug.imeBottomPx}px visible=${InsetDebug.imeVisible} " +
                            "navBottom=${InsetDebug.navBottomPx}px outerPad=${InsetDebug.outerPadPx}px\n" +
                            "kbMeasured=${InsetDebug.kbHeightPx.intValue}px sysNav=${InsetDebug.sysNavPx.intValue}px " +
                            "rootIme=${InsetDebug.composeImePx.intValue}px " +
                            "(lift = max(kb,rootIme,ime) - outerPad; keys hug iff keysBottom == screenH - lift)\n",
                        TermDim
                    )
                    afterCommand()
                }
                "echo" -> {
                    print("$arg\n", TermWhite); afterCommand()
                }
                "export" -> {
                    // Persistent: EXEC runs every command in a fresh sh, so
                    // bare `export` would die with the process. Assignments
                    // here are saved and re-applied to every command + PTY.
                    if (arg.isBlank()) {
                        val saved = TermEnv.all()
                        if (saved.isEmpty()) print("(no saved vars — export NAME=value to persist)\n", TermDim)
                        else saved.forEach { (k, v) -> print("$k=$v\n", TermWhite) }
                        afterCommand(); return
                    }
                    if (arg.contains(";") || arg.contains("&&") || arg.contains("||")) {
                        runShell(raw); return
                    }
                    val saved = mutableListOf<String>()
                    var ok = true
                    for (t in splitArgs(arg)) {
                        val eq = t.indexOf('=')
                        val name = if (eq > 0) t.substring(0, eq) else ""
                        val value = if (eq > 0) t.substring(eq + 1) else ""
                        // PATH is spliced into shell text unquoted — keep it
                        // injection-free (other vars travel via exec env).
                        if (eq <= 0 || !TermEnv.validName(name) ||
                            (name == "PATH" && !value.matches(Regex("^[A-Za-z0-9_/:.,+@%=$~-]+$")))
                        ) { ok = false; break }
                        TermEnv.set(name, value)
                        saved.add(name)
                    }
                    if (!ok || saved.isEmpty()) {
                        print("Usage: export NAME=value [NAME=value …] (saved persistently)\n", TermRed)
                    } else print("Saved: ${saved.joinToString(" ")} (applies to every command + new PTY)\n", TermGreen)
                    afterCommand()
                }
                "unset" -> {
                    if (arg.isBlank()) { print("Usage: unset NAME [NAME …]\n", TermRed); afterCommand(); return }
                    val gone = splitArgs(arg).filter { TermEnv.remove(it) }
                    print(if (gone.isEmpty()) "Nothing saved under those names\n" else "Unset: ${gone.joinToString(" ")}\n", TermDim)
                    afterCommand()
                }
                "env" -> {
                    val saved = TermEnv.all()
                    if (saved.isEmpty()) print("(no saved vars — see `export`)\n", TermDim)
                    else saved.forEach { (k, v) -> print("$k=$v\n", TermWhite) }
                    afterCommand()
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
                com.rg.webloom.data.OpencodeManager.install(AppCtx.ctx) { msg ->
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

    /**
     * Home-style display: sandbox-absolute paths print as ~/… so transcripts
     * (and agents reading them) see home, never the app-private absolute path.
     */
    private fun homeify(path: String): String {
        return try {
            val sd = sandboxDir ?: return path
            val root = sd.canonicalFile.absolutePath.trimEnd('/')
            // Fast reject: relative names and outside paths never mention root.
            if (!path.contains(root)) return path
            val p = try { java.io.File(path).canonicalFile.absolutePath } catch (_: Exception) { return path }
            if (p == root) "~"
            else if (p.startsWith("$root/")) "~/" + p.removePrefix("$root/")
            else path
        } catch (_: Exception) { path }
    }

    /** True when an `apk info` line means [pkg] is installed (name-version lines). */
    private fun verPkgInstalled(line: String, pkg: String): Boolean {
        val t = line.trim()
        if (t.isEmpty()) return false
        if (t == pkg) return true
        if (!t.startsWith("$pkg-")) return false
        // Version part starts with a digit (avoids git-perl matching git).
        return t.removePrefix("$pkg-").firstOrNull()?.isDigit() == true
    }

    /**
     * Installed apk package lines, queried synchronously (call on IO).
     * Empty when Alpine is missing or the query fails — callers degrade
     * to the unannotated list instead of erroring.
     */
    private fun apkInstalled(): Set<String> {
        var proc: Process? = null
        return try {
            val sd = sandboxDir ?: return emptySet()
            if (!AlpineEnv.isInstalled(sd)) return emptySet()
            val saved = try { TermEnv.all() } catch (_: Exception) { emptyMap() }
            val prefix = try {
                val p = saved["PATH"]
                if (!p.isNullOrBlank()) "export PATH=$p; " else AlpineEnv.shellPrefix(sd)
            } catch (_: Exception) { AlpineEnv.shellPrefix(sd) }
            val env = AlpineEnv.buildEnvironment(sd, sd) +
                saved.filterKeys { it != "PATH" }.map { (k, v) -> "$k=$v" }.toTypedArray()
            proc = Runtime.getRuntime().exec(arrayOf("sh", "-c", prefix + "apk info"), env, sd)
            try { proc.outputStream.close() } catch (_: Exception) {}
            val ok = try {
                proc.waitFor(20, java.util.concurrent.TimeUnit.SECONDS)
            } catch (_: Exception) { false }
            if (!ok) {
                try { proc.destroyForcibly() } catch (_: Exception) {}
                return emptySet()
            }
            try {
                proc.inputStream.bufferedReader().readLines().toSet()
            } catch (_: Exception) { emptySet() }
        } catch (_: Exception) { emptySet() }
        finally { try { proc?.destroy() } catch (_: Exception) {} }
    }

    /** Bare names resolve to sandbox/bin first, paths via the jail. */
    private fun resolveBin(name: String): File? {
        val sd = sandboxDir ?: return null
        return if ("/" in name) resolve(name)
        else {
            val b = File(sd, "bin/$name")
            if (b.exists()) b else resolve(name)
        }
    }

    /** 1 = ELF, 2 = #! script, 0 = neither. */
    private fun sniffKind(f: File): Int {
        return try {
            f.inputStream().use {
                val b = ByteArray(4)
                if (it.read(b) < 4) return 0
                if (b[0] == 0x7F.toByte() && b[1] == 'E'.code.toByte() &&
                    b[2] == 'L'.code.toByte() && b[3] == 'F'.code.toByte()
                ) return 1
                if (b[0] == '#'.code.toByte() && b[1] == '!'.code.toByte()) return 2
                0
            }
        } catch (_: Exception) { 0 }
    }

    /**
     * Build a shell command line that launches [f] despite noexec/W^X:
     * ELF → system linker, #! script → its interpreter. Null = can't launch.
     * [rawArgs] is appended verbatim (already quoted by the caller).
     */
    private fun buildLaunch(f: File, rawArgs: String): String? {
        val tail = if (rawArgs.isBlank()) "" else " $rawArgs"
        return when (sniffKind(f)) {
            1 -> {
                if (!f.canExecute()) {
                    try { com.rg.webloom.data.OpencodeManager.ensureExecutable(f) } catch (_: Exception) {}
                }
                com.rg.webloom.data.OpencodeManager.launchArgv(f) + tail
            }
            2 -> {
                val line = try {
                    f.bufferedReader().readLine()?.removePrefix("#!")?.trim()
                } catch (_: Exception) { null }
                if (line.isNullOrBlank()) return null
                val sp = line.split(Regex("\\s+"), limit = 2)
                val interp = sp[0]
                val interpArg = if (sp.size > 1) " ${sp[1]}" else ""
                "$interp$interpArg ${shQuote(f.absolutePath)}$tail"
            }
            else -> null
        }
    }

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

    /**
     * Split `head <selector…> [rest]` honoring quotes: CSS selectors contain
     * spaces (`div .btn`), which naive tokenizing mangles. `"a b" c` → (a b, c).
     */
    private fun splitSel(cmd: String, head: String): Pair<String, String> {
        val r = cmd.removePrefix(head).trim()
        if (r.isEmpty()) return "" to ""
        val q = r[0]
        if (q == '"' || q == '\'') {
            val end = r.indexOf(q, 1)
            if (end < 0) return r.substring(1) to ""
            return r.substring(1, end) to r.substring(end + 1).trim()
        }
        val sp = r.indexOf(' ')
        if (sp < 0) return r to ""
        return r.substring(0, sp) to r.substring(sp + 1)
    }

    /** Quote a selector/value for generated b-lines (replay). */
    private fun bq(s: String): String {
        if (s.isEmpty() || s.any { it.isWhitespace() || it == '"' || it == '\'' }) {
            return "'" + s.replace("'", "'\\''") + "'"
        }
        return s
    }

    /** Unwrap evaluateJavascript double-encoding → JSONArray, or null. */
    private fun parseJsonArray(raw: String): org.json.JSONArray? {
        return try {
            var s = raw.trim()
            repeat(2) {
                if (s.startsWith("\"") && s.endsWith("\"") && s.length >= 2) {
                    s = try { org.json.JSONObject("{\"v\":$s}").optString("v", s) } catch (_: Exception) { s }
                }
            }
            org.json.JSONArray(s)
        } catch (_: Exception) { null }
    }

    /** Split batch text on `;` respecting single/double quotes. */
    private fun splitBatch(raw: String): List<String> {
        val out = mutableListOf<String>()
        val cur = StringBuilder()
        var q: Char? = null
        var i = 0
        while (i < raw.length) {
            val c = raw[i]
            if (q != null) {
                cur.append(c)
                if (c == q) q = null
            } else if (c == '"' || c == '\'') {
                q = c
                cur.append(c)
            } else if (c == ';') {
                out.add(cur.toString())
                cur.clear()
            } else cur.append(c)
            i++
        }
        out.add(cur.toString())
        return out.map { it.trim() }.filter { it.isNotEmpty() }
    }

    /** Normalize one batch line to a full `b …` line (null = not a b command). */
    private fun normalizeBLine(t: String): String? {
        if (t == "b" || t.startsWith("b ")) return t
        val head = t.substringBefore(" ").trim().lowercase()
        if (head.isEmpty()) return null
        if (!isBuiltinB(head) && !BrowserAliases.all().containsKey(head)) {
            // Extension scripts count too.
            val ok = try {
                val sd = sandboxDir ?: AppCtx.ctx.let { java.io.File(it.filesDir, "sandbox") }
                java.io.File(sd, ".b-ext/$head.sh").let { it.isFile && it.canExecute() }
            } catch (_: Exception) { false }
            if (!ok) return null
        }
        return "b $t"
    }

    private fun enqueueBatch(steps: List<BStep>, delayMs: Long) {
        val q = bQueue ?: ArrayDeque<BStep>().also { bQueue = it }
        q.addAll(steps)
        bQueueDelayMs = delayMs
        batchFailed = false
        batchCurSoft = false
    }

    private fun runShell(cmd: String, timeoutSec: Int = 15) {
        val sd = sandboxDir ?: return
        val cwd = try { active().dir } catch (_: Exception) { null } ?: sd
        _status.value = "running"
        val wallMs = (timeoutSec.coerceIn(5, 600) * 1000).toLong()
        viewModelScope.launch(Dispatchers.IO) {
            var process: Process? = null
            try {
                // Saved exports (see `export`): PATH override honored in the
                // prefix, everything else appended to the env (wins over defaults).
                val saved = try { TermEnv.all() } catch (_: Exception) { emptyMap() }
                val prefix = try {
                    val p = saved["PATH"]
                    if (!p.isNullOrBlank()) "export PATH=$p; " else AlpineEnv.shellPrefix(sd)
                } catch (_: Exception) { AlpineEnv.shellPrefix(sd) }
                val fullCmd = prefix + cmd
                val env = AlpineEnv.buildEnvironment(sd, cwd) +
                    saved.filterKeys { it != "PATH" }.map { (k, v) -> "$k=$v" }.toTypedArray()
                process = Runtime.getRuntime().exec(arrayOf("sh", "-c", fullCmd), env, cwd)
                running = process
                // Nothing ever writes to stdin: close it so readers can't hang on it.
                try { process.outputStream.close() } catch (_: Exception) {}
                // Drain stdout+stderr CONCURRENTLY (serial drain deadlocks when stderr fills).
                val outBuf = StringBuilder()
                val tOut = Thread {
                    try {
                        BufferedReader(InputStreamReader(process.inputStream)).use { r ->
                            var l: String?
                            val start = System.currentTimeMillis()
                            while (r.readLine().also { l = it } != null) {
                                synchronized(outBuf) {
                                    outBuf.appendLine(l)
                                    if (outBuf.length > 8000) { outBuf.append("\n…truncated"); break }
                                }
                                if (System.currentTimeMillis() - start > wallMs) break
                            }
                        }
                    } catch (_: Exception) {}
                }.also { it.isDaemon = true; it.start() }
                val tErr = Thread {
                    try {
                        BufferedReader(InputStreamReader(process.errorStream)).use { r ->
                            var l: String?
                            val start = System.currentTimeMillis()
                            while (r.readLine().also { l = it } != null) {
                                synchronized(outBuf) {
                                    if (outBuf.length < 8000) outBuf.appendLine(l)
                                }
                                if (System.currentTimeMillis() - start > wallMs) break
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
            // Output modifiers parsed first: trailing `> file` / `>> file`
            // (jailed to sandbox) buffers everything instead of printing;
            // trailing --json unwraps page markers for machine parsing.
            var jsonMode = false
            var redir: java.io.File? = null
            var redirAppend = false
            // ext scripts print straight to the transcript (runShell), so
            // they bypass the redirect buffer — flagged to skip the flush.
            var extRan = false
            val fileBuf = StringBuilder()
            fun out(t: String, c: Color = TermWhite) {
                if (redir != null) {
                    fileBuf.append(t)
                    return
                }
                // Batch fail-fast signal: error-ish SHORT lines stop `b do/run`
                // at the next drain (Main FIFO keeps this ordered before it).
                // Length gate: page bodies can start with "Error…" — not failures.
                if (bQueue != null && !batchFailed && t.length < 500) {
                    val tl = t.trimStart()
                    if (tl.startsWith("ERR", ignoreCase = true) ||
                        tl.startsWith("Unknown b command", ignoreCase = true) ||
                        tl.startsWith("Usage:", ignoreCase = true) ||
                        tl.contains("failed", ignoreCase = true) ||
                        tl.contains("TIMEOUT", ignoreCase = true) ||
                        tl.startsWith("Nothing to save", ignoreCase = true) ||
                        tl.startsWith("Shot failed", ignoreCase = true) ||
                        tl.startsWith("(no tabs", ignoreCase = true) ||
                        tl.startsWith("Access denied", ignoreCase = true) ||
                        tl.startsWith("save failed", ignoreCase = true)
                    ) batchFailed = true
                }
                viewModelScope.launch(Dispatchers.Main) { print(t, c) }
            }
            fun wrapped(origin: String, body: String) {
                if (jsonMode) {
                    out(body.take(12_000) + "\n", TermWhite)
                    return
                }
                out("--- PAGE CONTENT origin=$origin ---\n", TermDim)
                out(body.take(12_000) + "\n", TermWhite)
                out("--- END PAGE CONTENT ---\n", TermDim)
            }
            fun extDir(): java.io.File? = try {
                val sd = sandboxDir ?: AppCtx.ctx.let { java.io.File(it.filesDir, "sandbox") }
                java.io.File(sd, ".b-ext")
            } catch (_: Exception) { null }
            fun macroDir(): java.io.File? = try {
                val sd = sandboxDir ?: AppCtx.ctx.let { java.io.File(it.filesDir, "sandbox") }
                java.io.File(sd, ".b-cmd")
            } catch (_: Exception) { null }
            /** Read a macro file's b-lines (full `b …` lines, # comments, ! soft). */
            fun macroLines(f: java.io.File): List<BStep>? {
                return try {
                    if (!f.isFile) return null
                    val out = mutableListOf<BStep>()
                    f.readLines(Charsets.UTF_8).take(200).forEach { rawLine ->
                        var t = rawLine.trim()
                        if (t.isEmpty() || t.startsWith("#")) return@forEach
                        var soft = false
                        if (t.startsWith("!")) { soft = true; t = t.substring(1).trim() }
                        if (t.isEmpty()) return@forEach
                        val full = normalizeBLine(t) ?: return null
                        out.add(BStep(full, soft))
                    }
                    out
                } catch (_: Exception) { null }
            }
            try {
                var cmd = line
                // Trailing `> file` / `>> file`: buffer output into a jailed
                // sandbox file (parsed before aliases so expansions can't
                // smuggle a redirect target).
                Regex("""^(.*)\s+(>>?)\s*([A-Za-z0-9._-]{1,80})\s*$""")
                    .matchEntire(cmd)?.let { m ->
                        val target = m.groupValues[3]
                        val resolved = try { resolve(target) } catch (_: Exception) { null }
                        if (resolved == null) {
                            out("Access denied: $target (stays inside sandbox)\n", TermRed)
                            withContext(Dispatchers.Main) { afterCommand() }
                            return@launch
                        }
                        redir = resolved
                        redirAppend = m.groupValues[2] == ">>"
                        cmd = m.groupValues[1].trimEnd()
                    }
                // Trailing --json / -j: machine-readable output.
                Regex("""^(.*)\s+(--json|-j)\s*$""").matchEntire(cmd)?.let { m ->
                    jsonMode = true
                    cmd = m.groupValues[1].trimEnd()
                }
                // User-alias expansion (max 3 hops, builtins always win) so new
                // `b` commands can be added inside the terminal, no app update.
                var hops = 0
                while (hops < 3) {
                    val head = cmd.substringBefore(" ").trim()
                    if (head.isEmpty() || isBuiltinB(head)) break
                    val exp = BrowserAliases.expand(cmd) ?: break
                    cmd = exp; hops++
                }
                if (hops > 0 && redir == null) out("→ $cmd\n", TermDim)
                // `scrollto` (PTY-canonical spelling) also works here.
                if (cmd == "scrollto" || cmd.startsWith("scrollto ")) {
                    cmd = "scroll-to" + cmd.removePrefix("scrollto")
                }
                val parts = cmd.split(" ", limit = 3)
                when (parts.getOrNull(0) ?: "") {
                    "", "help" -> out(
                            "b open <url> | back | forward | reload | reload-hard | stop | url | title | home\n" +
                            "b ua [mobile|desktop|<string>|get] | viewport | zoom [in|out|reset]\n" +
                            "b tabs | tab <n> | new <url> | close [n] | tabdup — duplicate tab\n" +
                            "b js <expr> | text [max] | read [max] — article text only | dom [css] | snap\n" +
                            "b click <ref|css|name> | fill <..> <val> [--submit] | submit <form> | key [sel]\n" +
                            "b hover <ref|css> — reveal menus | b select <sel> <val> — dropdowns\n" +
                            "b store <name> <css> | stores | unstore <name> — named selectors\n" +
                            "b pos <ref|css> → coords | b box <ref|css> → coords+bounds+safe points\n" +
                            "b tap <x> <y> [--click] | b swipe <x1> <y1> <x2> <y2> [ms]  (CSS px from pos/box)\n" +
                            "b shot [--full] | shot-el <ref|css> — locate element for shots\n" +
                            "b find <text> | find-clear | next | prev | b scroll-to (scrollto) <x> <y> | b scroll [px]\n" +
                            "b scroll-top | scroll-bottom — page ends without magic numbers\n" +
                            "b cookies [get [url] | set \"k=v\" [url] | clear] | clear-data [cookies|cache|history|storage|all]\n" +
                            "b netlog [n] — resource URLs/timings | console [n]\n" +
                            "b history [n] | downloads | save <name> | metrics (auto-logged)\n" +
                            "b wait <text|css:sel> [ms] | links [n] | forms | survey — automation senses\n" +
                            "b do \"c1; c2\" | run <file> | replay <rec> | queue — macros (! skips errors)\n" +
                            "b block <domain> | unblock <domain> | blocks — AI no-go sites (EXEC-only)\n" +
                            "b mkcmd <name> [\"c1; c2\"] | cmds — your macro folder (~/.b-cmd/)\n" +
                            "b alias [name expansion] | unalias <name> — your own cmds, no update needed\n" +
                            "b ext | mkext <name> — your own SCRIPT commands (~/.b-ext/, both modes)\n" +
                            "b record start|stop|pause|resume|save <n>|list | serve [on|off]\n" +
                            "Modifiers: append --json (raw output) or `> file` / `>> file` (sandboxed)\n", TermDim
                    )
                    "open" -> {
                        val url = parts.getOrNull(1) ?: ""
                        val fixed = resolveUrlish(url)
                        if (fixed == null) out("Usage: b open <url>\n", TermRed)
                        else if (BBlock.blocksUrl(fixed)) {
                            out("⛔ Blocked by you: ${BBlock.normalize(fixed)} (b unblock ${BBlock.normalize(fixed)} to allow)\n", TermRed)
                        } else {
                            com.rg.webloom.data.BrowserAgent.navigate(fixed)
                            out("Opening $fixed\n", TermGreen)
                        }
                    }
                    "new" -> {
                        val url = parts.getOrNull(1) ?: ""
                        val fixed = resolveUrlish(url)
                        if (fixed == null) out("Usage: b new <url>\n", TermRed)
                        else if (BBlock.blocksUrl(fixed)) {
                            out("⛔ Blocked by you: ${BBlock.normalize(fixed)} (b unblock ${BBlock.normalize(fixed)} to allow)\n", TermRed)
                        } else {
                            com.rg.webloom.ui.browser.TabBus.openInNewTab(fixed)
                            out("New tab: $fixed\n", TermGreen)
                        }
                    }
                    "block" -> {
                        val target = parts.getOrNull(1) ?: ""
                        val h = BBlock.normalize(target)
                        if (h.isEmpty() || "." !in h) {
                            out("Usage: b block <domain-or-url>  (blocks host + subdomains)\n", TermRed)
                        } else if (BBlock.add(h)) {
                            out("⛔ Blocked $h — AI and b commands can't open it (b unblock $h)\n", TermGreen)
                        } else out("Couldn't block '$target'\n", TermRed)
                    }
                    "unblock" -> {
                        val target = parts.getOrNull(1) ?: ""
                        if (target.isBlank()) out("Usage: b unblock <domain>\n", TermRed)
                        else if (BBlock.remove(target)) out("Unblocked ${BBlock.normalize(target)}\n", TermGreen)
                        else out("Wasn't blocked: $target\n", TermDim)
                    }
                    "blocks" -> {
                        val all = BBlock.all().sorted()
                        if (all.isEmpty()) out("(nothing blocked — b block <domain>)\n", TermDim)
                        else if (jsonMode) {
                            val arr = org.json.JSONArray()
                            all.forEach { arr.put(it) }
                            out(org.json.JSONObject().put("blocked", arr).toString() + "\n", TermWhite)
                        } else all.forEach { out("⛔ $it (+subdomains)\n", TermWhite) }
                    }
                    "tabs" -> {
                        val list = try { com.rg.webloom.ui.browser.TabBus.listTabs?.invoke() } catch (_: Exception) { null }
                        if (list.isNullOrEmpty()) out("(no tabs? open the Browser tab first)\n", TermDim)
                        else if (jsonMode) {
                            val arr = org.json.JSONArray()
                            list.forEach { t ->
                                arr.put(org.json.JSONObject().put("i", t.index)
                                    .put("url", t.url).put("title", t.title).put("current", t.current))
                            }
                            out(org.json.JSONObject().put("tabs", arr).toString() + "\n", TermWhite)
                        } else list.forEach { t ->
                            out("[${t.index}]${if (t.current) "●" else " "} ${(t.title.ifBlank { t.url }).take(60)} — ${t.url.take(80)}\n", TermWhite)
                        }
                    }
                    "close" -> {
                        val arg = parts.getOrNull(1)
                        if (arg != null && arg.isNotBlank() && arg != "current") {
                            val n = arg.toIntOrNull()
                            if (n == null) out("Usage: b close [n]\n", TermRed)
                            else {
                                com.rg.webloom.ui.browser.TabBus.closeTabAt?.invoke(n)
                                out("Closed tab $n\n", TermGreen)
                            }
                        } else {
                            com.rg.webloom.ui.browser.TabBus.closeTabAt?.invoke(-1)
                            out("Closed current tab\n", TermGreen)
                        }
                    }
                    "tab" -> {
                        val n = parts.getOrNull(1)?.toIntOrNull()
                        if (n == null) out("Usage: b tab <n>  (see b tabs)\n", TermRed)
                        else {
                            try { com.rg.webloom.ui.browser.TabBus.selectTab?.invoke(n) } catch (_: Exception) {}
                            out("Switched to tab $n\n", TermGreen)
                        }
                    }
                    "home" -> {
                        com.rg.webloom.ui.browser.TabBus.openHome?.invoke()
                        out("Home\n", TermGreen)
                    }
                    "back" -> com.rg.webloom.data.BrowserAgent.runOnPage {
                        try { if (it.canGoBack()) it.goBack() } catch (_: Exception) {}
                    }
                    "fwd", "forward" -> com.rg.webloom.data.BrowserAgent.runOnPage {
                        try { if (it.canGoForward()) it.goForward() } catch (_: Exception) {}
                    }
                    "reload" -> com.rg.webloom.data.BrowserAgent.runOnPage {
                        try { it.reload() } catch (_: Exception) {}
                    }
                    "reload-hard" -> {
                        com.rg.webloom.data.BrowserAgent.runOnPage {
                            try { it.clearCache(true); it.reload() } catch (_: Exception) {}
                        }
                        out("Hard reload (cache cleared)\n", TermGreen)
                    }
                    "ua" -> {
                        val sub = (parts.getOrNull(1) ?: "get").lowercase()
                        when {
                            sub == "get" || sub == "" -> {
                                val r = com.rg.webloom.data.BrowserAgent.eval("(function(){return navigator.userAgent;})()")
                                out("$r\n", TermWhite)
                            }
                            sub == "mobile" -> {
                                com.rg.webloom.data.BrowserAgent.runOnPage {
                                    try { it.settings.userAgentString = null } catch (_: Exception) {}
                                }
                                out("UA → mobile default\n", TermGreen)
                            }
                            sub == "desktop" -> {
                                com.rg.webloom.data.BrowserAgent.runOnPage {
                                    try { it.settings.userAgentString = com.rg.webloom.ui.browser.DESKTOP_UA } catch (_: Exception) {}
                                }
                                out("UA → desktop\n", TermGreen)
                            }
                            else -> {
                                val custom = cmd.removePrefix("ua").trim().take(500)
                                com.rg.webloom.data.BrowserAgent.runOnPage {
                                    try { it.settings.userAgentString = custom } catch (_: Exception) {}
                                }
                                out("UA set\n", TermGreen)
                            }
                        }
                    }
                    "viewport" -> {
                        val r = com.rg.webloom.data.BrowserAgent.eval(
                            "(function(){try{return JSON.stringify({vw:window.innerWidth,vh:window.innerHeight,dpr:window.devicePixelRatio||1});}catch(e){return 'ERR '+e;}})()"
                        )
                        out("$r\n", TermWhite)
                    }
                    "zoom" -> {
                        val sub = (parts.getOrNull(1) ?: "get").lowercase()
                        val expr = when (sub) {
                            "in" -> "(function(){try{document.body.style.zoom=((parseFloat(document.body.style.zoom)||1)*1.2).toFixed(2);return 'OK '+document.body.style.zoom;}catch(e){return 'ERR '+e;}})()"
                            "out" -> "(function(){try{document.body.style.zoom=((parseFloat(document.body.style.zoom)||1)/1.2).toFixed(2);return 'OK '+document.body.style.zoom;}catch(e){return 'ERR '+e;}})()"
                            "reset" -> "(function(){try{document.body.style.zoom='';return 'OK 1';}catch(e){return 'ERR '+e;}})()"
                            else -> "(function(){try{return 'OK '+(document.body.style.zoom||'1');}catch(e){return 'ERR '+e;}})()"
                        }
                        val r = com.rg.webloom.data.BrowserAgent.eval(expr)
                        out("$r\n", if (r.contains("OK")) TermGreen else TermRed)
                    }
                    "scroll-top" -> {
                        val r = com.rg.webloom.data.BrowserAgent.eval("(function(){try{window.scrollTo(0,0);return 'OK 0,0';}catch(e){return 'ERR '+e;}})()")
                        out("$r\n", TermGreen)
                    }
                    "scroll-bottom" -> {
                        val r = com.rg.webloom.data.BrowserAgent.eval("(function(){try{window.scrollTo(0,document.body.scrollHeight);return 'OK '+window.scrollX+','+window.scrollY;}catch(e){return 'ERR '+e;}})()")
                        out("$r\n", TermGreen)
                    }
                    "find-clear" -> {
                        com.rg.webloom.data.BrowserAgent.findInPage("")
                        out("Find cleared\n", TermGreen)
                    }
                    "netlog" -> {
                        val max = parts.getOrNull(1)?.toIntOrNull()?.coerceIn(1, 200) ?: 50
                        val raw = com.rg.webloom.data.BrowserAgent.eval(
                            "(function(){try{var es=(performance.getEntriesByType('resource')||[]).slice(-$max).map(function(e){return{u:(e.name||'').slice(0,200),t:e.initiatorType||'',d:Math.round(e.duration||0)});});return JSON.stringify(es);}catch(e){return 'ERR '+e;}})()"
                        )
                        if (raw.trimStart().startsWith("ERR")) out("$raw\n", TermRed)
                        else {
                            val arr = parseJsonArray(raw)
                            if (arr == null) out("$raw\n", TermRed)
                            else if (jsonMode) out(arr.toString() + "\n", TermWhite)
                            else if (arr.length() == 0) out("(no resources logged)\n", TermDim)
                            else for (i in 0 until arr.length()) {
                                val o = arr.optJSONObject(i) ?: continue
                                out("• [${o.optString("t")}] ${o.optInt("d")}ms ${o.optString("u").take(100)}\n", TermWhite)
                            }
                        }
                    }
                    "clear-data" -> {
                        val what = (parts.getOrNull(1) ?: "all").lowercase()
                        try {
                            val cm = android.webkit.CookieManager.getInstance()
                            if (what == "cookies" || what == "all") {
                                try { cm.removeAllCookies(null) } catch (_: Exception) {}
                                try { cm.flush() } catch (_: Exception) {}
                            }
                            if (what == "cache" || what == "all") {
                                com.rg.webloom.data.BrowserAgent.runOnPage {
                                    try { it.clearCache(true) } catch (_: Exception) {}
                                }
                            }
                            if (what == "storage" || what == "all") {
                                try { android.webkit.WebStorage.getInstance().deleteAllData() } catch (_: Exception) {}
                            }
                            if (what == "history" || what == "all") {
                                com.rg.webloom.data.BrowserAgent.runOnPage {
                                    try { it.clearHistory() } catch (_: Exception) {}
                                }
                            }
                            out("Cleared $what\n", TermGreen)
                        } catch (e: Exception) { out("clear-data failed: ${e.message}\n", TermRed) }
                    }
                    "tabdup", "tab-dup" -> {
                        try {
                            val url = com.rg.webloom.data.BrowserAgent.currentUrl()
                            if (url.isNullOrBlank()) out("(no page to duplicate)\n", TermDim)
                            else {
                                com.rg.webloom.ui.browser.TabBus.openInNewTab(url)
                                out("Duplicated: $url\n", TermGreen)
                            }
                        } catch (e: Exception) { out("tabdup failed: ${e.message}\n", TermRed) }
                    }
                    "shot-el" -> {
                        val sel = BStore.resolve(splitSel(cmd, "shot-el").first)
                        if (sel.isBlank()) out("Usage: b shot-el <ref|css>\n", TermRed)
                        else {
                            val esc = sel.replace("\\", "\\\\").replace("'", "\\'")
                            val r = com.rg.webloom.data.BrowserAgent.eval(
                                "(function(){try{var e=document.querySelector('$esc');if(!e)return 'ERR no-node';try{e.scrollIntoView({block:'center'});}catch(x){}var r=e.getBoundingClientRect();return JSON.stringify({x:Math.round((r.left+r.right)/2),y:Math.round((r.top+r.bottom)/2)});}catch(e){return 'ERR '+e;}})()"
                            )
                            out("$r — use b tap with these coords, b shot to verify\n", TermWhite)
                        }
                    }
                    "stop" -> {
                        com.rg.webloom.data.BrowserAgent.stopLoad()
                        out("Stopped\n", TermDim)
                    }
                    "find" -> {
                        val q = line.removePrefix("find").trim()
                        com.rg.webloom.data.BrowserAgent.findInPage(q)
                        out(if (q.isBlank()) "Find cleared\n" else "Finding \"$q\"\n", TermGreen)
                    }
                    "next" -> com.rg.webloom.data.BrowserAgent.findNext(true)
                    "prev" -> com.rg.webloom.data.BrowserAgent.findNext(false)
                    "pos" -> {
                        // Resolve ref/css → CSS-px coords. Feed them to `b tap`.
                        val sel = BStore.resolve(splitSel(cmd, "pos").first)
                        if (sel.isBlank()) out("Usage: b pos <ref|css>  (try b snap first, quote sels with spaces)\n", TermRed)
                        else {
                            val raw = com.rg.webloom.data.BrowserAgent.locateBlocking(sel)
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
                                    else if (jsonMode) out(o.toString() + "\n", TermGreen)
                                    else out("x=$x y=$y w=${o.optInt("w")} h=${o.optInt("h")}  →  b tap $x $y\n", TermGreen)
                                } catch (_: Exception) { out("$raw\n", TermWhite) }
                            }
                        }
                    }
                    "box" -> {
                        // Rich geometry for AI variation: center + bounds + safe
                        // inset points (all CSS px — feed any straight to b tap).
                        val sel = BStore.resolve(splitSel(cmd, "box").first)
                        if (sel.isBlank()) out("Usage: b box <ref|css>  (try b snap first, quote sels with spaces)\n", TermRed)
                        else {
                            val raw = com.rg.webloom.data.BrowserAgent.locateBlocking(sel)
                            if (raw.startsWith("ERR")) out("$raw\n", TermRed)
                            else {
                                try {
                                    var s = raw.trim()
                                    repeat(2) {
                                        if (s.startsWith("\"") && s.endsWith("\"") && s.length >= 2) {
                                            s = try { org.json.JSONObject("{\"v\":$s}").optString("v", s) } catch (_: Exception) { s }
                                        }
                                    }
                                    val o = org.json.JSONObject(s)
                                    val l = o.optInt("left", 0); val t = o.optInt("top", 0)
                                    val w = o.optInt("w", 0); val h = o.optInt("h", 0)
                                    val cx = o.optInt("x", -1); val cy = o.optInt("y", -1)
                                    if (cx < 0 || cy < 0) out("$raw\n", TermRed)
                                    else if (jsonMode) out(o.toString() + "\n", TermGreen)
                                    else {
                                        val mx = maxOf((w * 0.15).toInt(), 2); val my = maxOf((h * 0.15).toInt(), 2)
                                        out("center $cx,$cy  bounds l=$l t=$t w=$w h=$h\n", TermGreen)
                                        out("safe: ($cx,$cy) (${l + mx},${t + my}) (${l + w - mx},${t + my}) " +
                                            "(${l + mx},${t + h - my}) (${l + w - mx},${t + h - my})\n", TermWhite)
                                        out("→ b tap $cx $cy  (any safe point works)\n", TermDim)
                                    }
                                } catch (_: Exception) { out("$raw\n", TermWhite) }
                            }
                        }
                    }
                    "tap" -> {
                        val click = cmd.contains("--click")
                        val nums = line.substringAfter("tap").trim().replace("--click", "").trim()
                            .split(Regex("\\s+")).mapNotNull { it.toFloatOrNull() }
                        if (nums.size < 2) out("Usage: b tap <x> <y> [--click]  (CSS px from b pos/box)\n", TermRed)
                        else {
                            val r = com.rg.webloom.data.BrowserAgent.tapSync(nums[0], nums[1])
                            if (!r.delivered) out("Tap dropped (${r.reason}) — page may be loading; retry or b metrics\n", TermRed)
                            else {
                                var msg = "Tapped ${nums[0]},${nums[1]} (delivered)"
                                if (click) {
                                    val cr = com.rg.webloom.data.BrowserAgent.eval(
                                        "(function(){try{var e=document.elementFromPoint(${nums[0]},${nums[1]});if(!e)return 'ERR no-node';e.click();return 'OK click';}catch(e){return 'ERR '+e;}})()"
                                    )
                                    msg += if (cr.contains("OK")) " + click" else " (click fallback: $cr)"
                                }
                                out("$msg\n", TermGreen)
                            }
                        }
                    }
                    "swipe" -> {
                        // b swipe x1 y1 x2 y2 [ms] — drags: scrolls, sliders, drawers.
                        val nums = line.substringAfter("swipe").trim().split(Regex("\\s+")).mapNotNull { it.toFloatOrNull() }
                        if (nums.size < 4) out("Usage: b swipe <x1> <y1> <x2> <y2> [ms]  (CSS px)\n", TermRed)
                        else {
                            val r = com.rg.webloom.data.BrowserAgent.swipeSync(nums[0], nums[1], nums[2], nums[3], nums.getOrNull(4)?.toLong()?.coerceIn(50, 2000) ?: 300)
                            if (r.delivered) out("Swiped (delivered)\n", TermGreen)
                            else out("Swipe dropped (${r.reason}) — retry or b metrics\n", TermRed)
                        }
                    }
                    "scroll-to" -> {
                        val nums = line.substringAfter("scroll-to").trim().split(Regex("\\s+")).mapNotNull { it.toIntOrNull() }
                        if (nums.size < 2) out("Usage: b scroll-to <x> <y>\n", TermRed)
                        else {
                            val r = com.rg.webloom.data.BrowserAgent.eval("(function(){try{window.scrollTo(${nums[0]},${nums[1]});return 'OK '+window.scrollX+','+window.scrollY;}catch(e){return 'ERR '+e;}})()")
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
                    "ext" -> {
                        val files = try {
                            extDir()?.listFiles { f -> f.isFile && f.name.endsWith(".sh") }
                                ?.sortedBy { it.name }?.take(30)
                        } catch (_: Exception) { null }
                        if (files.isNullOrEmpty()) out("(no extensions — create one: b mkext <name>)\n", TermDim)
                        else files.forEach { f -> out("• b ${f.name.removeSuffix(".sh")}\n", TermWhite) }
                    }
                    "mkext" -> {
                        val name = parts.getOrNull(1) ?: ""
                        if (!name.matches(Regex("[a-z0-9_-]+"))) out("Usage: b mkext <name>  (creates sandbox/.b-ext/<name>.sh)\n", TermRed)
                        else {
                            try {
                                val dir = extDir() ?: throw IllegalStateException("no sandbox")
                                dir.mkdirs()
                                val f = java.io.File(dir, "$name.sh")
                                if (f.exists()) out("Exists: ${homeify(f.absolutePath)}\n", TermRed)
                                else {
                                    f.writeText(
                                        "#!/bin/sh\n" +
                                            "# custom b command: b $name <args> runs this file (EXEC and PTY)\n" +
                                            "# args arrive in \$1..\n" +
                                            "# PTY only: \$B_PORT/\$B_KEY reach the agent server (server must be on).\n" +
                                            "# example — list tabs:\n" +
                                            "#   curl -s --get \"http://127.0.0.1:\$B_PORT/tabs\" --data-urlencode \"token=\$B_KEY\"; echo\n" +
                                            "\n" +
                                            "echo \"TODO: edit \$HOME/.b-ext/$name.sh\"\n"
                                    )
                                    try { f.setExecutable(true) } catch (_: Exception) {}
                                    out("Created ${homeify(f.absolutePath)}\nEdit it (Files → Sandbox → .b-ext) then run: b $name\n", TermGreen)
                                }
                            } catch (e: Exception) { out("mkext failed: ${e.message}\n", TermRed) }
                        }
                    }
                    "cmds" -> {
                        val files = try {
                            macroDir()?.listFiles { f -> f.isFile && f.name.endsWith(".b") }
                                ?.sortedBy { it.name }?.take(30)
                        } catch (_: Exception) { null }
                        if (files.isNullOrEmpty()) out("(no macros — create one: b mkcmd <name>)\n", TermDim)
                        else if (jsonMode) {
                            val arr = org.json.JSONArray()
                            files.forEach { arr.put(it.name.removeSuffix(".b")) }
                            out(org.json.JSONObject().put("cmds", arr).toString() + "\n", TermWhite)
                        } else files.forEach { f ->
                            val n = try {
                                f.readLines(Charsets.UTF_8).take(100).count {
                                    val t = it.trim()
                                    t.isNotEmpty() && !t.startsWith("#")
                                }
                            } catch (_: Exception) { -1 }
                            out("• b ${f.name.removeSuffix(".b")}" + (if (n >= 0) " ($n steps)" else "") + "\n", TermWhite)
                        }
                    }
                    "mkcmd" -> {
                        // b mkcmd <name> ["c1; c2"] — macro file of plain b-lines.
                        val rest = cmd.removePrefix("mkcmd").trim()
                        val sp = rest.indexOf(' ')
                        val name = if (sp < 0) rest else rest.substring(0, sp)
                        var body = if (sp < 0) "" else rest.substring(sp + 1).trim().removeSurrounding("\"")
                        if (!name.matches(Regex("[a-z0-9_-]+"))) {
                            out("Usage: b mkcmd <name> [\"cmd1; cmd2\"]  (macro file, plain b-lines)\n", TermRed)
                        } else if (isBuiltinB(name) || BrowserAliases.all().containsKey(name)) {
                            out("'$name' is taken (built-in/alias) — pick another name\n", TermRed)
                        } else {
                            try {
                                val dir = macroDir() ?: throw IllegalStateException("no sandbox")
                                dir.mkdirs()
                                val f = java.io.File(dir, "$name.b")
                                if (f.exists()) out("Exists: ${homeify(f.absolutePath)}\n", TermRed)
                                else {
                                    if (body.isBlank()) {
                                        body = "# macro: b $name — one b-command per line (# comments, ! = skip errors)\n" +
                                            "# example:\n# b open https://example.com\n# b wait \"Welcome\"\n# b snap\n"
                                    } else {
                                        // Validate now so a broken macro never gets saved.
                                        val steps = splitBatch(body)
                                        var badStep: String? = null
                                        if (steps.isEmpty()) badStep = "(empty)"
                                        else for (s in steps) {
                                            var t = s.trim()
                                            if (t.startsWith("!")) t = t.substring(1).trim()
                                            if (normalizeBLine(t) == null) { badStep = s; break }
                                        }
                                        if (badStep != null) {
                                            out("Not a b command: $badStep\n", TermRed)
                                            withContext(Dispatchers.Main) { afterCommand() }
                                            return@launch
                                        }
                                        body = steps.joinToString("\n") { s ->
                                            var t = s.trim()
                                            val soft = t.startsWith("!")
                                            if (soft) t = t.substring(1).trim()
                                            val full = normalizeBLine(t) ?: t
                                            (if (soft) "! " else "") + full
                                        } + "\n"
                                    }
                                    f.writeText(body, Charsets.UTF_8)
                                    out("Created ${homeify(f.absolutePath)}\nRun it: b $name (or b run $name)\n", TermGreen)
                                }
                            } catch (e: Exception) { out("mkcmd failed: ${e.message}\n", TermRed) }
                        }
                    }
                    "url" -> out((com.rg.webloom.data.BrowserAgent.currentUrl() ?: "(none)") + "\n", TermWhite)
                    "title" -> {
                        val r = com.rg.webloom.data.BrowserAgent.eval("(function(){return document.title;})()")
                        out("$r\n", TermWhite)
                    }
                    "js" -> {
                        val expr = cmd.removePrefix("js").trim()
                        if (expr.isBlank()) out("Usage: b js <expr>\n", TermRed)
                        else {
                            val r = com.rg.webloom.data.BrowserAgent.eval("(function(){try{return JSON.stringify(eval(" + expr + "));}catch(e){return 'ERR '+e;}})()")
                            wrapped(com.rg.webloom.data.BrowserAgent.currentUrl() ?: "?", r)
                        }
                    }
                    "text" -> {
                        val max = parts.getOrNull(1)?.toIntOrNull()?.coerceIn(100, 60_000) ?: 8000
                        val r = com.rg.webloom.data.BrowserAgent.pageText(max)
                        wrapped(com.rg.webloom.data.BrowserAgent.currentUrl() ?: "?", r)
                    }
                    "dom" -> {
                        val sel = BStore.resolve(splitSel(cmd, "dom").first.ifBlank { "body" })
                        val esc = sel.replace("\\", "\\\\").replace("'", "\\'")
                        val r = com.rg.webloom.data.BrowserAgent.eval("(function(){try{var e=document.querySelector('$esc');return e?e.outerHTML.slice(0,20000):'ERR no-node';}catch(e){return 'ERR '+e;}})()")
                        wrapped(com.rg.webloom.data.BrowserAgent.currentUrl() ?: "?", r)
                    }
                    "snap" -> {
                        val r = com.rg.webloom.data.BrowserAgent.snapshot()
                        wrapped(com.rg.webloom.data.BrowserAgent.currentUrl() ?: "?", r)
                    }
                    "click" -> {
                        // Selectors may contain spaces — quote them: b click "div .btn".
                        val sel = BStore.resolve(splitSel(cmd, "click").first)
                        if (sel.isBlank()) out("Usage: b click <ref|css>  (quote sels with spaces)\n", TermRed)
                        else {
                            val esc = sel.replace("\\", "\\\\").replace("'", "\\'")
                            val r = com.rg.webloom.data.BrowserAgent.eval("(function(){try{return window.LightAgent.click('$esc');}catch(e){return 'ERR '+e;}})()")
                            out("$r\n", if (r.contains("OK")) TermGreen else TermRed)
                        }
                    }
                    "fill" -> {
                        var rest = splitSel(cmd, "fill")
                        var submit = false
                        var selRaw = rest.first
                        var v = rest.second
                        if (v.endsWith("--submit")) {
                            submit = true
                            v = v.removeSuffix("--submit").trim()
                        }
                        if (selRaw.isBlank() || v.isEmpty()) out("Usage: b fill <ref|css> <value> [--submit]  (quote sels with spaces)\n", TermRed)
                        else {
                            val sel = BStore.resolve(selRaw).replace("\\", "\\\\").replace("'", "\\'")
                            val vv = v.replace("\\", "\\\\").replace("'", "\\'")
                            var r = com.rg.webloom.data.BrowserAgent.eval("(function(){try{return window.LightAgent.fill('$sel','$vv');}catch(e){return 'ERR '+e;}})()")
                            if (r.contains("OK") && submit) {
                                val r2 = com.rg.webloom.data.BrowserAgent.eval("(function(){try{var e=document.querySelector('$sel');var f=e?(e.form||e.closest('form')):null;if(!f)return 'ERR no-form';f.submit();return 'OK submitted';}catch(e){return 'ERR '+e;}})()")
                                r = "$r / $r2"
                            }
                            out("$r\n", if (r.contains("OK")) TermGreen else TermRed)
                        }
                    }
                    "scroll" -> {
                        val y = parts.getOrNull(1)?.toIntOrNull() ?: 500
                        val r = com.rg.webloom.data.BrowserAgent.eval("(function(){try{window.scrollBy(0,$y);return 'OK';}catch(e){return 'ERR '+e;}})()")
                        out("$r\n", TermGreen)
                    }
                    "serve" -> {
                        when (parts.getOrNull(1)) {
                            "on", "start" -> {
                                try { com.rg.webloom.data.BrowserAgent.startServer() } catch (e: Exception) {
                                    out("Start failed: ${e.message}\n", TermRed)
                                    return@launch
                                }
                                out("Agent server: ${com.rg.webloom.data.BrowserAgent.serverLabel.value}\n", TermGreen)
                            }
                            "off", "stop" -> {
                                try { com.rg.webloom.data.BrowserAgent.stopServer() } catch (_: Exception) {}
                                out("Server stopped.\n", TermDim)
                            }
                            else -> {
                                if (com.rg.webloom.data.BrowserAgent.serverRunning.value) {
                                    out("Agent server: ${com.rg.webloom.data.BrowserAgent.serverLabel.value}\nFrom Termux: curl 'http://127.0.0.1:8089/text?token=…'\n", TermGreen)
                                } else out("Server is OFF — b serve on to start it.\n", TermDim)
                            }
                        }
                    }
                    "record" -> {
                        val sub = parts.getOrNull(1) ?: ""
                        when (sub) {
                            "start" -> {
                                com.rg.webloom.data.BrowserAgent.startRecording()
                                out("● Recording taps + touches — switch to the Browser tab. Pause: b record pause. Stop auto-saves.\n", TermGreen)
                            }
                            "stop" -> {
                                val n = com.rg.webloom.data.BrowserAgent.recCount()
                                val saved = try {
                                    com.rg.webloom.data.BrowserAgent.stopRecording()
                                } catch (_: Exception) { null }
                                out(
                                    if (saved != null) "Stopped. Saved $n action(s) → ${homeify(saved)}\n"
                                    else "Stopped. No actions captured.\n",
                                    if (saved != null) TermGreen else TermDim
                                )
                            }
                            "pause" -> {
                                try { com.rg.webloom.data.BrowserAgent.pauseRecording() } catch (_: Exception) {}
                                out("Paused — touches dropped until resume (events kept).\n", TermDim)
                            }
                            "resume" -> {
                                try { com.rg.webloom.data.BrowserAgent.resumeRecording() } catch (_: Exception) {}
                                out("Resumed.\n", TermGreen)
                            }
                            "save" -> {
                                val name = parts.getOrNull(2) ?: ""
                                val path = com.rg.webloom.data.BrowserAgent.saveRecording(name.ifBlank { "rec" })
                                if (path != null) out("Saved ${homeify(path)}\n", TermGreen)
                                else out("Nothing to save (record first).\n", TermRed)
                            }
                            "list" -> {
                                val recs = com.rg.webloom.data.BrowserAgent.listRecordings()
                                if (recs.isEmpty()) out("(no saved recordings — sandbox/agent_recs)\n", TermDim)
                                else recs.take(10).forEach { (f, n) -> out("• $f ($n actions)\n", TermWhite) }
                            }
                            else -> out("Usage: b record start|stop|pause|resume|save <name>|list\n", TermRed)
                        }
                    }
                    "wait" -> {
                        // b wait <text|css:sel> [timeoutMs] — poll for SPA loads
                        // and post-click navigation so macros don't race the page.
                        val rawArg = cmd.removePrefix("wait").trim()
                        if (rawArg.isBlank()) {
                            out("Usage: b wait <text|css:sel> [timeoutMs]\n", TermRed)
                        } else {
                            val toks = rawArg.split(Regex("\\s+"))
                            val target: String
                            val timeout: Long
                            if (toks.size >= 2 && toks.last().toLongOrNull() != null) {
                                target = toks.dropLast(1).joinToString(" ")
                                timeout = toks.last().toLong().coerceIn(1000, 60000)
                            } else {
                                target = rawArg
                                timeout = 10000L
                            }
                            val selMode = target.startsWith("css:")
                            val query = if (selMode) target.removePrefix("css:") else target
                            if (selMode && query.isBlank()) {
                                out("Usage: b wait <text|css:sel> [timeoutMs]\n", TermRed)
                            } else {
                                out("Waiting ${if (selMode) "for $query" else "for \"$target\""} (up to ${timeout}ms)…\n", TermDim)
                                val t0 = System.currentTimeMillis()
                                var found = false
                                while (System.currentTimeMillis() - t0 < timeout) {
                                    try {
                                        found = if (selMode) {
                                            val esc = query.replace("\\", "\\\\").replace("'", "\\'")
                                            val r = com.rg.webloom.data.BrowserAgent.eval(
                                                "(function(){try{return document.querySelector('$esc')?'YES':'NO';}catch(e){return 'ERR';}})()"
                                            )
                                            r.contains("YES")
                                        } else {
                                            try { com.rg.webloom.data.BrowserAgent.pageText(2000).contains(target) } catch (_: Exception) { false }
                                        }
                                    } catch (_: Exception) { found = false }
                                    if (found) break
                                    try { kotlinx.coroutines.delay(500) } catch (_: Exception) { break }
                                }
                                val el = System.currentTimeMillis() - t0
                                if (found) out("FOUND in ${el}ms\n", TermGreen)
                                else out("TIMEOUT after ${el}ms\n", TermRed)
                            }
                        }
                    }
                    "links" -> {
                        val max = parts.getOrNull(1)?.toIntOrNull()?.coerceIn(1, 200) ?: 100
                        val raw = com.rg.webloom.data.BrowserAgent.eval(
                            "(function(){try{var a=Array.prototype.slice.call(document.querySelectorAll('a[href]'),0,$max)" +
                                ".map(function(e){return{text:(e.innerText||'').trim().slice(0,80),href:(e.href||'').slice(0,500)}});" +
                                "return JSON.stringify(a);}catch(e){return 'ERR '+e;}})()"
                        )
                        if (raw.trimStart().startsWith("ERR")) out("$raw\n", TermRed)
                        else {
                            val arr = parseJsonArray(raw)
                            if (arr == null) out("$raw\n", TermRed)
                            else if (jsonMode) out(arr.toString() + "\n", TermWhite)
                            else {
                                if (arr.length() == 0) out("(no links on this page)\n", TermDim)
                                else for (i in 0 until arr.length()) {
                                    val o = arr.optJSONObject(i) ?: continue
                                    out("• ${o.optString("text").ifBlank { "(no text)" }.take(60)} → ${o.optString("href").take(100)}\n", TermWhite)
                                }
                            }
                        }
                    }
                    "forms" -> {
                        val raw = com.rg.webloom.data.BrowserAgent.eval(
                            "(function(){try{" +
                                "function ps(e){try{if(e.id)return '#'+e.id;var p=e.parentNode;if(!p)return e.tagName.toLowerCase();" +
                                "var sibs=Array.prototype.filter.call(p.children,function(x){return x.tagName===e.tagName;});" +
                                "return e.tagName.toLowerCase()+':nth-of-type('+(sibs.indexOf(e)+1)+')';}catch(x){return '?';}}" +
                                "var out=[];var els=document.querySelectorAll('input,select,textarea');" +
                                "for(var i=0;i<els.length&&i<100;i++){var e=els[i];" +
                                "var f=e.form;var fi=-1;if(f){var fs=document.forms;for(var k=0;k<fs.length;k++){if(fs[k]===f){fi=k;break;}}}" +
                                "out.push({form:fi,type:(e.type||e.tagName.toLowerCase()),name:(e.name||'')," +
                                "label:((e.placeholder||e.getAttribute('aria-label')||'')+'').slice(0,60),sel:ps(e)});}" +
                                "return JSON.stringify(out);}catch(e){return 'ERR '+e;}})()"
                        )
                        if (raw.trimStart().startsWith("ERR")) out("$raw\n", TermRed)
                        else {
                            val arr = parseJsonArray(raw)
                            if (arr == null) out("$raw\n", TermRed)
                            else if (jsonMode) out(arr.toString() + "\n", TermWhite)
                            else {
                                if (arr.length() == 0) out("(no form fields on this page)\n", TermDim)
                                else for (i in 0 until arr.length()) {
                                    val o = arr.optJSONObject(i) ?: continue
                                    out("form#${o.optInt("form")} [${o.optString("type")}] ${o.optString("name").ifBlank { o.optString("label") }} → ${o.optString("sel")}\n", TermWhite)
                                }
                            }
                        }
                    }
                    "survey" -> {
                        // One-shot bundle for agents: nav + viewport + errors + taps.
                        val url = com.rg.webloom.data.BrowserAgent.currentUrl() ?: "?"
                        val title = try {
                            com.rg.webloom.data.BrowserAgent.eval("(function(){return document.title;})()")
                        } catch (_: Exception) { "?" }
                        var s = try {
                            com.rg.webloom.data.BrowserAgent.eval(
                                "(function(){try{return JSON.stringify({vw:window.innerWidth,vh:window.innerHeight,dpr:window.devicePixelRatio||1,sx:window.scrollX,sy:window.scrollY});}catch(e){return 'ERR '+e;}})()"
                            )
                        } catch (_: Exception) { "ERR" }
                        s = s.trim()
                        repeat(2) {
                            if (s.startsWith("\"") && s.endsWith("\"") && s.length >= 2) {
                                s = try { org.json.JSONObject("{\"v\":$s}").optString("v", s) } catch (_: Exception) { s }
                            }
                        }
                        val vp = try { org.json.JSONObject(s) } catch (_: Exception) { org.json.JSONObject() }
                        val cons = org.json.JSONArray()
                        try { com.rg.webloom.data.BrowserAgent.consoleTail(8).forEach { cons.put(it.take(300)) } } catch (_: Exception) {}
                        val tabs = try {
                            com.rg.webloom.ui.browser.TabBus.listTabs?.invoke()?.size ?: -1
                        } catch (_: Exception) { -1 }
                        val rep = org.json.JSONObject().put("url", url).put("title", title)
                            .put("viewport", vp).put("console", cons).put("tabs", tabs)
                        try {
                            val box = com.rg.webloom.data.BrowserAgent.webViewBoxBlocking(8)
                            try { rep.put("view", org.json.JSONObject(box)) }
                            catch (_: Exception) { rep.put("viewErr", box.take(80)) }
                        } catch (_: Exception) {}
                        try {
                            com.rg.webloom.data.BrowserAgent.lastTap?.let { rep.put("lastTap", org.json.JSONObject(it)) }
                        } catch (_: Exception) {}
                        if (jsonMode) out(rep.toString() + "\n", TermWhite)
                        else out(rep.toString(1) + "\n", TermWhite)
                    }
                    "do" -> {
                        // b do "cmd1; cmd2" — chain steps (! prefix = skip past errors).
                        var rest = cmd.removePrefix("do").trim().removeSurrounding("\"")
                        if (rest.isBlank()) {
                            out("Usage: b do \"cmd1; cmd2\"  (! prefix skips errors)\n", TermRed)
                        } else {
                            val norm = mutableListOf<BStep>()
                            var bad: String? = null
                            for (s in splitBatch(rest)) {
                                var t = s.trim()
                                var soft = false
                                if (t.startsWith("!")) { soft = true; t = t.substring(1).trim() }
                                if (t.isEmpty()) continue
                                val full = normalizeBLine(t)
                                if (full == null) { bad = s; break }
                                norm.add(BStep(full, soft))
                            }
                            if (bad != null) out("Not a b command: $bad\n", TermRed)
                            else if (norm.isEmpty()) out("Nothing to run\n", TermRed)
                            else {
                                enqueueBatch(norm, 250)
                                out("Queued ${norm.size} step(s)\n", TermGreen)
                            }
                        }
                    }
                    "run" -> {
                        // b run <file> — b-script from the sandbox, one command per line.
                        // Bare names also match the macro folder (~/.b-cmd/<name>[.b]).
                        val name = parts.getOrNull(1) ?: ""
                        if (name.isBlank()) { out("Usage: b run <file|macro>  (sandbox-jailed, # comments)\n", TermRed) }
                        else {
                            val f = if ("/" in name) resolve(name)
                            else {
                                val m = try {
                                    macroDir()?.let { d ->
                                        java.io.File(d, "$name.b").takeIf { it.isFile }
                                            ?: java.io.File(d, name).takeIf { it.isFile }
                                    }
                                } catch (_: Exception) { null }
                                m ?: resolve(name)
                            }
                            if (f == null || !f.isFile) { out("Not found in sandbox: $name (try b cmds)\n", TermRed) }
                            else {
                                val lines = try { f.readLines(Charsets.UTF_8) } catch (_: Exception) { emptyList() }
                                val norm = mutableListOf<BStep>()
                                var bad: String? = null
                                for (rawLine in lines) {
                                    var t = rawLine.trim()
                                    if (t.isEmpty() || t.startsWith("#")) continue
                                    var soft = false
                                    if (t.startsWith("!")) { soft = true; t = t.substring(1).trim() }
                                    if (t.isEmpty()) continue
                                    val full = normalizeBLine(t)
                                    if (full == null) { bad = rawLine.trim(); break }
                                    norm.add(BStep(full, soft))
                                }
                                if (bad != null) out("Not a b command: $bad\n", TermRed)
                                else if (norm.isEmpty()) out("Nothing to run in ${f.name}\n", TermRed)
                                else {
                                    enqueueBatch(norm, 250)
                                    out("Queued ${norm.size} step(s) from ${f.name}\n", TermGreen)
                                }
                            }
                        }
                    }
                    "replay" -> {
                        // b replay <name|file> — execute a saved recording (tap/swipe/click/fill).
                        val arg = parts.getOrNull(1) ?: ""
                        if (arg.isBlank()) { out("Usage: b replay <name|file>  (from b record save)\n", TermRed) }
                        else {
                            val f = if ("/" in arg) resolve(arg)
                            else {
                                val sd = sandboxDir ?: AppCtx.ctx.let { java.io.File(it.filesDir, "sandbox") }
                                val dir = java.io.File(sd, "agent_recs")
                                val withExt = if (arg.endsWith(".json")) arg else "$arg.json"
                                java.io.File(dir, withExt).takeIf { it.isFile }
                                    ?: dir.listFiles { x -> x.isFile && x.name.startsWith(arg) }
                                        ?.maxByOrNull { it.lastModified() }
                            }
                            if (f == null || !f.isFile) { out("Recording not found: $arg\n", TermRed) }
                            else {
                                try {
                                    val root = org.json.JSONObject(f.readText(Charsets.UTF_8))
                                    val arr = root.optJSONArray("actions")
                                    if (arr == null || arr.length() == 0) {
                                        out("No actions in ${f.name}\n", TermRed)
                                    } else {
                                        val steps = mutableListOf<BStep>()
                                        var lastUrl = com.rg.webloom.data.BrowserAgent.currentUrl() ?: ""
                                        for (i in 0 until arr.length()) {
                                            val o = arr.optJSONObject(i) ?: continue
                                            val aUrl = o.optString("url", "")
                                            if (aUrl.isNotBlank() && aUrl != lastUrl && !aUrl.startsWith("lb://")) {
                                                steps.add(BStep("b open ${bq(aUrl)}", false))
                                                lastUrl = aUrl
                                            }
                                            when (o.optString("op", "")) {
                                                "click" -> {
                                                    val s = o.optString("selector", "")
                                                    if (s.isNotBlank()) steps.add(BStep("b click ${bq(s)}", false))
                                                }
                                                "fill" -> {
                                                    val s = o.optString("selector", "")
                                                    val v = o.optString("value", "").replace("\r", " ").replace("\n", " ")
                                                    if (s.isNotBlank()) steps.add(BStep("b fill ${bq(s)} ${bq(v)}", false))
                                                }
                                                "tap" -> steps.add(
                                                    BStep("b tap ${o.optDouble("x", -1.0)} ${o.optDouble("y", -1.0)}", false)
                                                )
                                                "swipe" -> steps.add(
                                                    BStep(
                                                        "b swipe ${o.optDouble("x1", 0.0)} ${o.optDouble("y1", 0.0)} " +
                                                            "${o.optDouble("x2", 0.0)} ${o.optDouble("y2", 0.0)} ${o.optInt("ms", 300)}",
                                                        false
                                                    )
                                                )
                                            }
                                        }
                                        if (steps.isEmpty()) out("No replayable actions in ${f.name}\n", TermRed)
                                        else {
                                            enqueueBatch(steps, 1200)
                                            out("Replaying ${steps.size} step(s) from ${f.name} — don't type, ^C stops\n", TermGreen)
                                        }
                                    }
                                } catch (e: Exception) { out("replay failed: ${e.message}\n", TermRed) }
                            }
                        }
                    }
                    "queue" -> {
                        val q = bQueue
                        if (q.isNullOrEmpty()) out("(queue empty)\n", TermDim)
                        else q.forEachIndexed { i, s ->
                            out("${i + 1}. ${if (s.soft) "! " else ""}${s.line}\n", TermWhite)
                        }
                    }
                    "console" -> {
                        val n = parts.getOrNull(1)?.toIntOrNull() ?: 30
                        val lines = com.rg.webloom.data.BrowserAgent.consoleTail(n)
                        if (lines.isEmpty()) out("(console empty — JS logs appear here)\n", TermDim)
                        else if (jsonMode) {
                            val arr = org.json.JSONArray()
                            lines.forEach { arr.put(it.take(500)) }
                            out(org.json.JSONObject().put("console", arr).toString() + "\n", TermWhite)
                        } else {
                            out("--- CONSOLE (last ${lines.size}) ---\n", TermDim)
                            lines.forEach { out(it.take(500) + "\n", TermWhite) }
                            out("--- END CONSOLE ---\n", TermDim)
                        }
                    }
                    "shot" -> {
                        val full = cmd.contains("--full")
                        out(if (full) "Capturing full page…\n" else "Capturing…\n", TermDim)
                        val path = if (full) com.rg.webloom.data.BrowserAgent.captureFullShot()
                        else com.rg.webloom.data.BrowserAgent.captureShot()
                        if (path != null) out("Saved ${homeify(path)}\nOpen it in Files → Sandbox → shots.\n", TermGreen)
                        else out("Shot failed (open the Browser tab first).\n", TermRed)
                    }
                    "cookies" -> {
                        val sub = (parts.getOrNull(1) ?: "get").lowercase()
                        val rest = (parts.getOrNull(2) ?: "").trim().removeSurrounding("\"")
                        try {
                            val cm = android.webkit.CookieManager.getInstance()
                            when (sub) {
                                "get" -> {
                                    val url = rest.ifBlank { com.rg.webloom.data.BrowserAgent.currentUrl() ?: "" }
                                    val ck = try { cm.getCookie(url) } catch (_: Exception) { null }
                                    if (ck.isNullOrBlank()) out("No cookies for ${url.ifBlank { "(no page)" }}\n", TermDim)
                                    else if (jsonMode) out(org.json.JSONObject().put("url", url).put("cookies", ck).toString() + "\n", TermWhite)
                                    else wrapped(url, ck)
                                }
                                "set" -> {
                                    // b cookies set "name=value" [url]
                                    val sp2 = rest.indexOf(' ')
                                    val kv = if (sp2 < 0) rest else rest.substring(0, sp2)
                                    val url = (if (sp2 < 0) "" else rest.substring(sp2 + 1))
                                        .ifBlank { com.rg.webloom.data.BrowserAgent.currentUrl() ?: "" }
                                    if (kv.isBlank() || !kv.contains("=")) out("Usage: b cookies set \"name=value\" [url]\n", TermRed)
                                    else {
                                        try {
                                            cm.setCookie(url, kv)
                                            try { cm.flush() } catch (_: Exception) {}
                                            out("Cookie set for $url\n", TermGreen)
                                        } catch (e: Exception) { out("set failed: ${e.message}\n", TermRed) }
                                    }
                                }
                                "clear" -> {
                                    try { cm.removeAllCookies(null) } catch (_: Exception) {}
                                    try { cm.flush() } catch (_: Exception) {}
                                    out("Cookies cleared\n", TermGreen)
                                }
                                else -> out("Usage: b cookies [get [url] | set \"k=v\" [url] | clear]\n", TermRed)
                            }
                        } catch (e: Exception) { out("cookies error: ${e.message}\n", TermRed) }
                    }
                    "history" -> {
                        val n = parts.getOrNull(1)?.toIntOrNull()?.coerceIn(1, 100) ?: 15
                        try {
                            val list = com.rg.webloom.data.HistoryStorage.all(AppCtx.ctx).takeLast(n).reversed()
                            if (list.isEmpty()) out("(history empty)\n", TermDim)
                            else if (jsonMode) {
                                val arr = org.json.JSONArray()
                                list.forEach { h ->
                                    arr.put(org.json.JSONObject().put("url", h.url).put("title", h.title))
                                }
                                out(org.json.JSONObject().put("history", arr).toString() + "\n", TermWhite)
                            } else list.forEach { h ->
                                out("${h.title.ifBlank { h.url }.take(60)} — ${h.url.take(80)}\n", TermWhite)
                            }
                        } catch (e: Exception) { out("history error: ${e.message}\n", TermRed) }
                    }
                    "downloads" -> {
                        try {
                            val dir = java.io.File(AppCtx.ctx.filesDir, "sandbox/Downloads")
                            val files = dir.listFiles()?.sortedByDescending { it.lastModified() }?.take(30)
                            if (files.isNullOrEmpty()) out("(no downloads — sandbox/Downloads)\n", TermDim)
                            else if (jsonMode) {
                                val arr = org.json.JSONArray()
                                files.forEach { f ->
                                    arr.put(org.json.JSONObject().put("name", f.name).put("size", f.length()))
                                }
                                out(org.json.JSONObject().put("downloads", arr).toString() + "\n", TermWhite)
                            } else files.forEach { f -> out("• ${f.name} (${f.length() / 1024} KB)\n", TermWhite) }
                        } catch (e: Exception) { out("downloads error: ${e.message}\n", TermRed) }
                    }
                    "submit" -> {
                        val sel = BStore.resolve(splitSel(cmd, "submit").first)
                        if (sel.isBlank()) out("Usage: b submit <form|css>\n", TermRed)
                        else {
                            val esc = sel.replace("\\", "\\\\").replace("'", "\\'")
                            val r = com.rg.webloom.data.BrowserAgent.eval("(function(){try{var e=document.querySelector('$esc');var f=e?(e.form||e.closest('form')||(e.tagName==='FORM'?e:null)):null;if(!f)return 'ERR no-form';f.submit();return 'OK submitted';}catch(e){return 'ERR '+e;}})()")
                            out("$r\n", if (r.contains("OK")) TermGreen else TermRed)
                        }
                    }
                    "read" -> {
                        val max = parts.getOrNull(1)?.toIntOrNull()?.coerceIn(500, 60_000) ?: 6000
                        val r = com.rg.webloom.data.BrowserAgent.eval(
                            com.rg.webloom.data.BrowserAgent.readJs(max), maxChars = max + 4000
                        )
                        wrapped(com.rg.webloom.data.BrowserAgent.currentUrl() ?: "?", r)
                    }
                    "hover" -> {
                        val sel = BStore.resolve(splitSel(cmd, "hover").first)
                        if (sel.isBlank()) out("Usage: b hover <ref|css>  (quote sels with spaces)\n", TermRed)
                        else {
                            val esc = sel.replace("\\", "\\\\").replace("'", "\\'")
                            val r = com.rg.webloom.data.BrowserAgent.eval("(function(){try{var e=document.querySelector('$esc');if(!e)return 'ERR no-node';var r=e.getBoundingClientRect();['mouseover','mouseenter','mousemove'].forEach(function(t){e.dispatchEvent(new MouseEvent(t,{bubbles:true,cancelable:true,clientX:r.left+r.width/2,clientY:r.top+r.height/2}));});try{e.focus();}catch(x){}return 'OK hover '+Math.round(r.left)+','+Math.round(r.top);}catch(e){return 'ERR '+e;}})()")
                            out("$r\n", if (r.contains("OK")) TermGreen else TermRed)
                        }
                    }
                    "select" -> {
                        val (selRaw, vRaw) = splitSel(cmd, "select")
                        if (selRaw.isBlank() || vRaw.isEmpty()) out("Usage: b select <sel> <value-or-text>  (quote sels with spaces)\n", TermRed)
                        else {
                            val sel = BStore.resolve(selRaw).replace("\\", "\\\\").replace("'", "\\'")
                            val v = vRaw.replace("\\", "\\\\").replace("'", "\\'")
                            val r = com.rg.webloom.data.BrowserAgent.eval("(function(){try{var e=document.querySelector('$sel');if(!e)return 'ERR no-node';if(e.tagName!=='SELECT')return 'ERR not-a-select';var v='$v';var hit=false;for(var i=0;i<e.options.length;i++){if(e.options[i].value===v||e.options[i].text.trim()===v){e.selectedIndex=i;hit=true;break;}}if(!hit)e.value=v;e.dispatchEvent(new Event('input',{bubbles:true}));e.dispatchEvent(new Event('change',{bubbles:true}));return 'OK selected '+e.selectedIndex;}catch(e){return 'ERR '+e;}})()")
                            out("$r\n", if (r.contains("OK")) TermGreen else TermRed)
                        }
                    }
                    "store" -> {
                        val (name, cssRaw) = splitSel(cmd, "store")
                        val css = cssRaw.removeSurrounding("\"").removeSurrounding("'")
                        if (name.isBlank() || css.isBlank()) out("Usage: b store <name> <css>  (then: b click <name>)\n", TermRed)
                        else if (!name.matches(Regex("[a-zA-Z0-9_-]+"))) out("Name must be [a-zA-Z0-9_-]+\n", TermRed)
                        else {
                            BStore.set(name, css)
                            out("Stored '$name' → $css\n", TermGreen)
                        }
                    }
                    "key" -> {
                        val sel = BStore.resolve(splitSel(cmd, "key").first)
                        out("Probing for the button near the field…\n", TermDim)
                        val raw = com.rg.webloom.data.BrowserAgent.eval(
                            com.rg.webloom.data.BrowserAgent.keyProbeJs(sel)
                        )
                        try {
                            var s = raw.trim()
                            repeat(2) {
                                if (s.startsWith("\"") && s.endsWith("\"") && s.length >= 2) {
                                    s = try { org.json.JSONObject("{\"v\":$s}").optString("v", s) } catch (_: Exception) { s }
                                }
                            }
                            if (s.startsWith("ERR")) out("$s\n", TermRed)
                            else {
                                val o = org.json.JSONObject(s)
                                val x = o.optDouble("x", -1.0); val y = o.optDouble("y", -1.0)
                                if (x < 0 || y < 0) out("No button found near the field\n", TermRed)
                                else {
                                    val r = com.rg.webloom.data.BrowserAgent.tapSync(x.toFloat(), y.toFloat())
                                    if (r.delivered) out("Tapped '${o.optString("label", "button")}' at ${x.toInt()},${y.toInt()} (delivered)\n", TermGreen)
                                    else out("Tap dropped (${r.reason})\n", TermRed)
                                }
                            }
                        } catch (_: Exception) { out("$raw\n", TermWhite) }
                    }
                    "stores" -> {
                        val all = BStore.all()
                        if (all.isEmpty()) out("(no stored selectors — b store <name> <css>)\n", TermDim)
                        else if (jsonMode) {
                            val o = org.json.JSONObject()
                            all.forEach { (k, v) -> o.put(k, v) }
                            out(org.json.JSONObject().put("stores", o).toString() + "\n", TermWhite)
                        } else all.forEach { (k, v) -> out("$k  →  $v\n", TermWhite) }
                    }
                    "unstore" -> {
                        val name = parts.getOrNull(1) ?: ""
                        if (name.isBlank()) out("Usage: b unstore <name>\n", TermRed)
                        else {
                            BStore.remove(name)
                            out("Removed '$name'\n", TermGreen)
                        }
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
                            var r = com.rg.webloom.data.BrowserAgent.eval(expr, maxChars = 420_000)
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
                    "metrics" -> {
                        // Geometry audit for tap-accuracy tests: screen px vs
                        // page CSS px + the last tap's mapping. Every run is
                        // APPENDED to sandbox/agent_metrics/metrics.log so the
                        // test trail survives the transcript (cap 300 entries).
                        try {
                            val dm = try { AppCtx.ctx.resources.displayMetrics } catch (_: Exception) { null }
                            val url = com.rg.webloom.data.BrowserAgent.currentUrl() ?: "?"
                            val raw = com.rg.webloom.data.BrowserAgent.eval(
                                "(function(){try{var d=document.documentElement;return JSON.stringify({vw:window.innerWidth,vh:window.innerHeight,dpr:window.devicePixelRatio||1,sx:window.scrollX,sy:window.scrollY,cw:Math.max(d?d.scrollWidth:0,document.body?document.body.scrollWidth:0),ch:Math.max(d?d.scrollHeight:0,document.body?document.body.scrollHeight:0)});}catch(e){return 'ERR '+e;}})()"
                            )
                            var s = raw.trim()
                            repeat(2) {
                                if (s.startsWith("\"") && s.endsWith("\"") && s.length >= 2) {
                                    s = try { org.json.JSONObject("{\"v\":$s}").optString("v", s) } catch (_: Exception) { s }
                                }
                            }
                            val page = try { org.json.JSONObject(s) } catch (_: Exception) { org.json.JSONObject() }
                            val tap = try {
                                com.rg.webloom.data.BrowserAgent.lastTap?.let { org.json.JSONObject(it) }
                            } catch (_: Exception) { null }
                            val rep = org.json.JSONObject()
                                .put("ts", System.currentTimeMillis())
                                .put("screen", org.json.JSONObject()
                                    .put("w", dm?.widthPixels ?: -1)
                                    .put("h", dm?.heightPixels ?: -1)
                                    .put("density", (dm?.density ?: -1f).toDouble())
                                    .put("dpi", dm?.densityDpi ?: -1))
                                .put("page", org.json.JSONObject()
                                    .put("url", url)
                                    .put("vw", page.optInt("vw", -1))
                                    .put("vh", page.optInt("vh", -1))
                                    .put("dpr", page.optDouble("dpr", -1.0))
                                    .put("sx", page.optInt("sx", 0))
                                    .put("sy", page.optInt("sy", 0))
                                    .put("cw", page.optInt("cw", -1))
                                    .put("ch", page.optInt("ch", -1)))
                            if (tap != null) rep.put("lastTap", tap)
                            // On-screen WebView box: view x/y are WebView-relative
                            // (screen = view + box); lastTap carries both spaces.
                            try {
                                val box = com.rg.webloom.data.BrowserAgent.webViewBoxBlocking(8)
                                try { rep.put("view", org.json.JSONObject(box)) }
                                catch (_: Exception) { rep.put("viewErr", box.take(80)) }
                            } catch (_: Exception) {}
                            // Persist the trail (already on IO) — single writer.
                            var logInfo = ""
                            try {
                                logInfo = com.rg.webloom.data.BrowserAgent.appendMetrics(rep)
                            } catch (e: Exception) { logInfo = "log failed: ${e.message}" }
                            if (jsonMode) {
                                out(rep.toString() + "\n", TermWhite)
                            } else {
                                val d = dm
                                out("screen ${d?.widthPixels ?: "?"}x${d?.heightPixels ?: "?"} px" +
                                    " @${d?.density ?: "?"} (dpi ${d?.densityDpi ?: "?"})\n", TermWhite)
                                out("page $url\n  viewport ${page.optInt("vw", -1)}x${page.optInt("vh", -1)} css" +
                                    " dpr ${page.optDouble("dpr", -1.0)} scroll ${page.optInt("sx", 0)},${page.optInt("sy", 0)}" +
                                    " content ${page.optInt("cw", -1)}x${page.optInt("ch", -1)}\n", TermWhite)
                                try {
                                    val vb = rep.optJSONObject("view")
                                    if (vb != null) {
                                        out("view @${vb.optInt("x")},${vb.optInt("y")} ${vb.optInt("w")}x${vb.optInt("h")}" +
                                            " on ${vb.optInt("scrW")}x${vb.optInt("scrH")} (devY = WebView-top relative)\n", TermWhite)
                                    }
                                } catch (_: Exception) {}
                                if (tap != null) {
                                    out("last tap ${tap.optString("kind", "tap")} css ${tap.optDouble("cssX")},${tap.optDouble("cssY")}" +
                                        " → view ${tap.optDouble("viewX")},${tap.optDouble("viewY")}" +
                                        " (scale ${tap.optDouble("scale")}, delivered=${tap.optBoolean("delivered", false)})\n", TermGreen)
                                } else out("last tap: none yet (b tap something first)\n", TermDim)
                                out("$logInfo\n", TermDim)
                            }
                        } catch (e: Exception) { out("metrics error: ${e.message}\n", TermRed) }
                    }
                    else -> {
                        // Dispatch order: shell extensions (~/.b-ext/<cmd>.sh),
                        // then macro files (~/.b-cmd/<cmd>.b, plain b-lines).
                        // Runs in both modes — the PTY `b()` fn mirrors this.
                        val head = parts.getOrNull(0) ?: ""
                        val extFile = try {
                            val f = extDir()?.let { java.io.File(it, "$head.sh") }
                            if (f != null && f.isFile && f.canExecute()) f else null
                        } catch (_: Exception) { null }
                        if (extFile != null) {
                            val args = cmd.removePrefix(head).trim()
                                .split(Regex("\\s+")).filter { it.isNotEmpty() }
                                .joinToString(" ") { "'" + it.replace("'", "'\\''") + "'" }
                            if (redir == null) out("→ ext $head\n", TermDim)
                            extRan = true
                            runShell("sh " + extFile.absolutePath + (if (args.isNotBlank()) " $args" else ""), 30)
                        } else {
                            val macroFile = try {
                                macroDir()?.let { java.io.File(it, "$head.b") }
                                    ?.takeIf { it.isFile }
                            } catch (_: Exception) { null }
                            val steps = macroFile?.let { macroLines(it) }
                            if (macroFile != null && steps == null) {
                                out("Macro ${homeify(macroFile.absolutePath)} has a bad line (every line needs a b command)\n", TermRed)
                            } else if (macroFile != null) {
                                if (steps.isNullOrEmpty()) out("(empty macro — edit ${homeify(macroFile.absolutePath)})\n", TermDim)
                                else {
                                    val extra = cmd.removePrefix(head).trim()
                                    if (extra.isNotEmpty()) out("(macros take no args — ignoring '$extra'; use b alias for params)\n", TermDim)
                                    enqueueBatch(steps, 250)
                                    out("Running $head (${steps.size} step(s))\n", TermGreen)
                                }
                            } else {
                                val cands = BuiltinB + BrowserAliases.all().keys + try {
                                    extDir()?.listFiles { f -> f.isFile && f.name.endsWith(".sh") }
                                        ?.map { it.name.removeSuffix(".sh") } ?: emptyList()
                                } catch (_: Exception) { emptyList() } + try {
                                    macroDir()?.listFiles { f -> f.isFile && f.name.endsWith(".b") }
                                        ?.map { it.name.removeSuffix(".b") } ?: emptyList()
                                } catch (_: Exception) { emptyList() }
                                val s = suggestB(head, cands)
                                out(
                                    if (s != null) "Unknown b command '$head'. Did you mean 'b $s'?\n"
                                    else "Unknown b command. Try: b help (or define your own: b alias/mkcmd)\n",
                                    TermRed
                                )
                            }
                        }
                    }
                }
                // Buffered `> file` / `>> file`: flush on IO, announce on Main.
                // ext scripts bypass the buffer (see above) — don't write empties.
                val rf = redir
                if (rf != null && !extRan) {
                    try {
                        rf.parentFile?.mkdirs()
                        val body = fileBuf.toString()
                        if (redirAppend) rf.appendText(body, Charsets.UTF_8)
                        else rf.writeText(body, Charsets.UTF_8)
                        withContext(Dispatchers.Main) {
                            print("Saved ${body.length} chars to ${rf.name}\n", TermGreen)
                        }
                    } catch (e: Exception) {
                        withContext(Dispatchers.Main) { print("save failed: ${e.message}\n", TermRed) }
                    }
                }
            } catch (e: Exception) {
                out("b error: ${e.message}\n", TermRed)
            }
            withContext(Dispatchers.Main) { afterCommand() }
        }
    }

        private fun isAllowed(path: File): Boolean {        val sd = sandboxDir ?: return false
        return try {
            // Separator-anchored: a sibling like "sandbox_evil" must not pass
            // the prefix check (was: raw startsWith, escapable).
            val root = sd.canonicalFile.absolutePath.trimEnd('/') + '/'
            val p = path.canonicalFile.absolutePath
            p == root.trimEnd('/') || p.startsWith(root)
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
    "help", "open", "new", "tabs", "tab", "close", "home", "back", "fwd", "forward",
    "reload", "reload-hard", "ua", "viewport", "zoom", "scroll-top", "scroll-bottom",
    "find-clear", "netlog", "clear-data", "tabdup", "tab-dup", "shot-el",
    "stop", "url", "title", "js", "text", "read", "dom", "snap", "click",
    "fill", "submit", "key", "hover", "select", "store", "stores", "unstore",
    "pos", "box", "tap", "swipe", "scroll", "scroll-to", "scrollto", "find", "next",
    "prev", "shot", "console", "cookies", "history", "downloads", "save", "serve", "record",
    "alias", "unalias", "ext", "mkext", "metrics",
    "wait", "links", "forms", "survey", "do", "run", "replay", "queue",
    "block", "unblock", "blocks", "mkcmd", "cmds"
)

/** Levenshtein distance for `b` did-you-mean suggestions. */
private fun levDist(a: String, b: String): Int {
    if (a == b) return 0
    if (a.isEmpty()) return b.length
    if (b.isEmpty()) return a.length
    var prev = IntArray(b.length + 1) { it }
    var cur = IntArray(b.length + 1)
    for (i in 1..a.length) {
        cur[0] = i
        for (j in 1..b.length) {
            cur[j] = minOf(
                prev[j] + 1,
                cur[j - 1] + 1,
                prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1
            )
        }
        val t = prev; prev = cur; cur = t
    }
    return prev[b.length]
}
/** Closest candidate within distance 2 (min length 3), or null. */
private fun suggestB(head: String, cands: Collection<String>): String? {
    val h = head.trim().lowercase()
    if (h.length < 2) return null
    return cands.mapNotNull { c ->
        val cl = c.lowercase()
        val d = levDist(h, cl)
        if (d in 1..2 && cl.length >= 3) cl to d else null
    }.minByOrNull { it.second }?.first
}

/** True when [name] is a built-in `b` head — the Agent panel reuses this guard. */
internal fun isBBlocked(name: String): Boolean =
    BuiltinB.contains(name.trim().lowercase())

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
        com.rg.webloom.data.AppCtx.ctx.getSharedPreferences(PREF, android.content.Context.MODE_PRIVATE)
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

/**
 * Named selectors: `b store login "#user"` then `b click login`.
 * SharedPreferences-backed (survives restarts); resolved in click/fill/
 * pos/hover/select/submit/dom on EXEC and in HTTP routes for PTY.
 */
object BStore {
    private const val PREF = "b_store"
    private const val KEY = "stores"

    private fun prefs() = try {
        com.rg.webloom.data.AppCtx.ctx.getSharedPreferences(PREF, android.content.Context.MODE_PRIVATE)
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

    fun set(name: String, sel: String) {
        try {
            val o = org.json.JSONObject()
            all().forEach { (k, v) -> o.put(k, v) }
            o.put(name, sel.take(500))
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

    /** Stored name → selector, else the arg itself. */
    fun resolve(arg: String): String {
        val a = arg.trim()
        if (a.isEmpty()) return arg
        return all()[a] ?: arg
    }
}

/**
 * Blocked hosts: `b block discord.com` keeps the AI (and every b command)
 * off a site — privacy guard against agents wandering into settings/billing.
 * Enforced in WebViewClient.shouldOverrideUrlLoading (clicks, JS navs),
 * navigate()/open/new entries, and the human search bar. Matches the host
 * itself + all subdomains. SharedPreferences-backed (survives restarts).
 */
object BBlock {
    private const val PREF = "b_block"
    private const val KEY = "hosts"

    private fun prefs() = try {
        com.rg.webloom.data.AppCtx.ctx.getSharedPreferences(PREF, android.content.Context.MODE_PRIVATE)
    } catch (_: Exception) { null }

    /** Normalize a URL, bare domain, or host to a rule host ("" = invalid). */
    fun normalize(input: String): String {
        return try {
            var t = input.trim().lowercase()
            if (t.isEmpty()) return ""
            if ("://" !in t) t = "https://$t"
            val h = try {
                android.net.Uri.parse(t).host
            } catch (_: Exception) { null } ?: return ""
            h.trim().trimEnd('.').lowercase().take(253)
        } catch (_: Exception) { "" }
    }

    fun all(): Set<String> {
        return try {
            val raw = prefs()?.getString(KEY, null) ?: return emptySet()
            val o = org.json.JSONObject(raw)
            buildSet {
                o.keys().forEach { k ->
                    try { if (o.optBoolean(k, false)) add(k) } catch (_: Exception) {}
                }
            }
        } catch (_: Exception) { emptySet() }
    }

    fun add(host: String): Boolean {
        val h = normalize(host)
        if (h.isEmpty() || "." !in h) return false
        return try {
            val o = org.json.JSONObject()
            (all() + h).forEach { o.put(it, true) }
            prefs()?.edit()?.putString(KEY, o.toString())?.apply()
            true
        } catch (_: Exception) { false }
    }

    fun remove(host: String): Boolean {
        val h = normalize(host)
        if (h.isEmpty()) return false
        return try {
            val o = org.json.JSONObject()
            (all() - h).forEach { o.put(it, true) }
            prefs()?.edit()?.putString(KEY, o.toString())?.apply()
            true
        } catch (_: Exception) { false }
    }

    /** True when [host] is blocked (exact or any subdomain of a rule). */
    fun matches(host: String?): Boolean {
        val h = (host ?: "").trim().lowercase().trimEnd('.')
        if (h.isEmpty()) return false
        return all().any { r -> h == r || h.endsWith(".$r") }
    }

    /** True when this URL's host is blocked (http/https/lb only matter: host). */
    fun blocksUrl(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        return try {
            var t = url.trim()
            if ("://" !in t) t = "https://$t"
            val h = try { android.net.Uri.parse(t).host } catch (_: Exception) { null }
            matches(h)
        } catch (_: Exception) { false }
    }
}
