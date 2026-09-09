package com.lightbrowser.ui.terminal

import android.util.Log
import android.util.TypedValue
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.inputmethod.InputMethodManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.lightbrowser.data.AlpineEnv
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient
import java.io.File

private const val PTY_TAG = "PtyTab"

/** Bridge so the slim top bar (drawer/⌨) can drive the PTY view. */
class PtyControl {
    var showKeyboard: (() -> Unit)? = null
    var pasteText: ((String) -> Unit)? = null
}

/**
 * True PTY terminal (Termux emulator+view, Apache-2.0): full-screen TUIs like
 * `opencode` work here — raw mode, alt-screen, resize, real signals.
 * Runs with cwd=sandbox so agents act inside the sandbox.
 */
@Composable
fun PtyTab(
    useOpencode: Boolean,
    ctl: PtyControl,
    onExitToExec: () -> Unit,
    modifier: Modifier = Modifier
) {
    val ctx = LocalContext.current
    val app = remember(ctx) { ctx.applicationContext }
    var sticky by remember { mutableStateOf<String?>(null) }
    var exited by remember { mutableStateOf<Int?>(null) }
    var gen by remember { mutableIntStateOf(0) }
    var session by remember { mutableStateOf<TerminalSession?>(null) }
    var termView by remember { mutableStateOf<TerminalView?>(null) }

    val sd = remember(app) { File(app.filesDir, "sandbox").apply { mkdirs() } }
    val ocBin = remember(sd) { File(sd, "bin/opencode") }
    // Don't gate on canExecute(): SELinux can report +x yet refuse direct
    // execve (W^X) — we launch via the system linker instead (see OpencodeManager).
    val opencodeOk = useOpencode && ocBin.exists() && ocBin.length() > 1_000_000
    val sysLinker = remember {
        listOf("/system/bin/linker64", "/system/bin/linker").firstOrNull { File(it).exists() }
    }
    val shellPath = when {
        opencodeOk && sysLinker != null -> sysLinker
        opencodeOk -> ocBin.absolutePath
        else -> "/system/bin/sh"
    }
    val shellArgs = when {
        opencodeOk && sysLinker != null -> arrayOf(sysLinker, ocBin.absolutePath)
        opencodeOk -> arrayOf(ocBin.absolutePath)
        else -> arrayOf("sh")
    }
    val env = remember(sd) {
        // Saved `export`s apply to new PTY sessions too (plus ~/.profile via $ENV).
        val saved = try { com.lightbrowser.data.TermEnv.all() } catch (_: Exception) { emptyMap() }
        val base = AlpineEnv.buildEnvironment(sd, sd).toMutableList()
        saved["PATH"]?.let { p ->
            if (p.isNotBlank()) {
                val i = base.indexOfFirst { it.startsWith("PATH=") }
                if (i >= 0) base[i] = "PATH=$p" else base.add("PATH=$p")
            }
        }
        (base + saved.filterKeys { it != "PATH" }.map { (k, v) -> "$k=$v" } +
            "COLORTERM=truecolor").toTypedArray()
    }
    // setTextSize() takes RAW PX (its "dp" javadoc lies) — Termux multiplies by
    // density. 13px raw ≈ 4dp: the tiny-text + broken-TUI-grid bug.
    val fontPx = remember(ctx) {
        try {
            TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, 13f, ctx.resources.displayMetrics
            ).toInt().coerceAtLeast(13)
        } catch (_: Exception) { 39 }
    }
    val imm = remember(ctx) {
        ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
    }

    val sessionClient = remember {
        object : TerminalSessionClient {
            override fun onTextChanged(changedSession: TerminalSession) {
                try { termView?.onScreenUpdated() } catch (_: Exception) {}
            }
            override fun onTitleChanged(changedSession: TerminalSession) {}
            override fun onSessionFinished(finishedSession: TerminalSession) {
                // Snapshot writes must happen on Main (binder thread otherwise).
                try {
                    val code = try { finishedSession.exitStatus } catch (_: Exception) { -1 }
                    Handler(Looper.getMainLooper()).post { exited = code }
                } catch (_: Exception) {}
            }
            override fun onCopyTextToClipboard(session: TerminalSession, text: String) {}
            override fun onPasteTextFromClipboard(session: TerminalSession?) {}
            override fun onBell(session: TerminalSession) {}
            override fun onColorsChanged(session: TerminalSession) {}
            override fun onTerminalCursorStateChange(state: Boolean) {}
            override fun getTerminalCursorStyle(): Int = 0
            override fun setTerminalShellPid(session: TerminalSession, pid: Int) {}
            override fun logError(tag: String, message: String) { Log.e(PTY_TAG, "$tag: $message") }
            override fun logWarn(tag: String, message: String) { Log.w(PTY_TAG, "$tag: $message") }
            override fun logInfo(tag: String, message: String) {}
            override fun logDebug(tag: String, message: String) {}
            override fun logVerbose(tag: String, message: String) {}
            override fun logStackTraceWithMessage(tag: String, message: String, e: Exception) {
                Log.e(PTY_TAG, "$tag: $message", e)
            }
            override fun logStackTrace(tag: String, e: Exception) { Log.e(PTY_TAG, tag, e) }
        }
    }
    val viewClient = remember {
        object : TerminalViewClient {
            override fun onScale(scale: Float): Float = scale
            override fun onSingleTapUp(e: MotionEvent) {}
            override fun shouldBackButtonBeMappedToEscape(): Boolean = true
            override fun shouldEnforceCharBasedInput(): Boolean = true
            override fun shouldUseCtrlSpaceWorkaround(): Boolean = false
            override fun isTerminalViewSelected(): Boolean = true
            override fun copyModeChanged(copyMode: Boolean) {}
            override fun onKeyDown(keyCode: Int, e: KeyEvent, session: TerminalSession): Boolean = false
            override fun onKeyUp(keyCode: Int, e: KeyEvent): Boolean = false
            override fun onLongPress(event: MotionEvent): Boolean = false
            override fun readControlKey(): Boolean = sticky == "CTRL"
            override fun readAltKey(): Boolean = sticky == "ALT"
            override fun readShiftKey(): Boolean = false
            override fun readFnKey(): Boolean = false
            override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession): Boolean {
                return try {
                    // Soft keyboards arrive here with ctrlDown=false, so the
                    // hardware-only readControlKey() never fires for them —
                    // honor OUR sticky state explicitly (upper+lowercase).
                    val st = sticky
                    if (st == "CTRL" && codePoint in 65..90) {
                        val b = byteArrayOf((codePoint - 64).toByte())
                        session.write(b, 0, 1)
                    } else if (st == "CTRL" && codePoint in 97..122) {
                        val b = byteArrayOf((codePoint - 96).toByte())
                        session.write(b, 0, 1)
                    } else if (st == "CTRL" && codePoint == '/'.code) {
                        session.write(byteArrayOf(0x1F), 0, 1)
                    } else if (st == "ALT") {
                        session.writeCodePoint(true, codePoint)
                    } else if (ctrlDown && codePoint in 97..122) {
                        val b = byteArrayOf((codePoint - 96).toByte())
                        session.write(b, 0, 1)
                    } else {
                        session.writeCodePoint(false, codePoint)
                    }
                    sticky = null
                    true
                } catch (_: Exception) { false }
            }
            override fun onEmulatorSet() {}
            override fun logError(tag: String, message: String) { Log.e(PTY_TAG, "$tag: $message") }
            override fun logWarn(tag: String, message: String) { Log.w(PTY_TAG, "$tag: $message") }
            override fun logInfo(tag: String, message: String) { Log.i(PTY_TAG, "$tag: $message") }
            override fun logDebug(tag: String, message: String) {}
            override fun logVerbose(tag: String, message: String) {}
            override fun logStackTraceWithMessage(tag: String, message: String, e: Exception) {
                Log.e(PTY_TAG, "$tag: $message", e)
            }
            override fun logStackTrace(tag: String, e: Exception) { Log.e(PTY_TAG, tag, e) }
        }
    }

    fun refocus() {
        // Tapping any Compose button steals View focus → typed keys would go
        // nowhere (the "invisible input" bug). Focus back WITHOUT showing the
        // keyboard (it's already open; showing it re-triggers inset races).
        try { termView?.requestFocus() } catch (_: Exception) {}
    }

    fun writeBytes(b: ByteArray) {
        try { session?.write(b, 0, b.size) } catch (_: Exception) {}
        sticky = null
        refocus()
    }
    fun writeText(s: String) = writeBytes(s.toByteArray(Charsets.UTF_8))

    /** Char key with sticky: CTRL+letter → control byte, ALT+x → ESC x. */
    fun sendChar(c: String) {
        when (sticky) {
            "CTRL" -> {
                val ch = c.firstOrNull() ?: return writeText(c)
                val code = when {
                    ch.lowercaseChar() in 'a'..'z' -> ch.lowercaseChar() - 'a' + 1
                    ch == '/' -> 0x1F
                    else -> -1
                }
                if (code >= 0) writeBytes(byteArrayOf(code.toByte())) else writeText(c)
            }
            "ALT" -> writeText("\u001B$c")
            else -> writeText(c)
        }
    }

    /** Escape-sequence key with sticky: ALT prefixes ESC, CTRL passes through. */
    fun sendSeq(seq: String) {
        if (sticky == "ALT") writeText("\u001B$seq") else writeText(seq)
    }

    DisposableEffect(shellPath, gen) {
        exited = null
        val s = try {
            TerminalSession(shellPath, sd.absolutePath, shellArgs, env, 2000, sessionClient)
        } catch (e: Exception) {
            Log.e(PTY_TAG, "session create", e)
            null
        }
        session = s
        onDispose {
            try { s?.finishIfRunning() } catch (_: Exception) {}
            session = null
        }
    }

    DisposableEffect(termView) {
        ctl.showKeyboard = {
            try {
                termView?.requestFocus()
                try {
                    termView?.let { imm.showSoftInput(it, 0) }
                } catch (_: Exception) {}
            } catch (_: Exception) {}
        }
        ctl.pasteText = { t ->
            try {
                val b = t.toByteArray(Charsets.UTF_8)
                session?.write(b, 0, b.size)
            } catch (_: Exception) {}
            refocus()
        }
        onDispose { ctl.showKeyboard = null; ctl.pasteText = null }
    }

    Column(modifier = modifier.fillMaxSize().background(Color.Black)) {
        // Slim status line (toggles live in the top bar now — space matters).
        Text(
            when {
                useOpencode && opencodeOk -> "● PTY · opencode"
                useOpencode -> "● PTY · shell (opencode missing — opencode-install)"
                else -> "● PTY · shell"
            },
            color = Color(0xFF4CAF50),
            fontSize = 11.sp,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 1.dp)
        )
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            AndroidView(
                factory = { c ->
                    TerminalView(c, null).also { v ->
                        v.setTerminalViewClient(viewClient)
                        try { v.setTextSize(fontPx) } catch (_: Exception) {}
                        try { v.setBackgroundColor(Color.Black.toArgb()) } catch (_: Exception) {}
                        v.isFocusable = true
                        v.isFocusableInTouchMode = true
                        termView = v
                    }
                },
                update = { v ->
                    v.setTerminalViewClient(viewClient)
                    // Idempotent (no-op when the same session is attached);
                    // updateSize() inside initializes the emulator once the
                    // view has a non-zero size. No focus here — keyboard opens
                    // on user tap / ⌨ only (forced focus double-lifts keys).
                    session?.let { s -> try { v.attachSession(s) } catch (_: Exception) {} }
                },
                modifier = Modifier.fillMaxSize()
            )
            if (exited != null) {
                Column(
                    modifier = Modifier.fillMaxSize().background(Color(0xCC000000)).padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        "Session exited (${exited})",
                        color = Color.White,
                        style = MaterialTheme.typography.titleMedium
                    )
                    Row(modifier = Modifier.padding(top = 12.dp)) {
                        Button(onClick = { gen++ }) { Text("Restart") }
                        OutlinedButton(
                            onClick = onExitToExec,
                            modifier = Modifier.padding(start = 8.dp)
                        ) { Text("Exec mode") }
                    }
                }
            }
        }
        // Keys hug the keyboard (ime minus nav — see keyboardHug) and scroll
        // sideways for the full set.
        Column(modifier = Modifier.fillMaxWidth().keyboardHug()) {
            TermKeyRow(
                keys = listOf(
                    "ESC" to { sendChar("\u001B") },
                    "TAB" to { sendChar("\t") },
                    "/" to { sendChar("/") },
                    "-" to { sendChar("-") },
                    "HOME" to { sendSeq("\u001B[H") },
                    "↑" to { sendSeq("\u001B[A") },
                    "END" to { sendSeq("\u001B[F") },
                    "PGUP" to { sendSeq("\u001B[5~") },
                    "PGDN" to { sendSeq("\u001B[6~") },
                    "|" to { sendChar("|") }
                ),
                sticky = null
            )
            TermKeyRow(
                keys = listOf(
                    "CTRL" to { sticky = if (sticky == "CTRL") null else "CTRL"; refocus() },
                    "ALT" to { sticky = if (sticky == "ALT") null else "ALT"; refocus() },
                    "^C" to { writeBytes(byteArrayOf(0x03)) },
                    "^D" to { writeBytes(byteArrayOf(0x04)) },
                    "←" to { sendSeq("\u001B[D") },
                    "↓" to { sendSeq("\u001B[B") },
                    "→" to { sendSeq("\u001B[C") },
                    "~" to { sendChar("~") },
                    ":" to { sendChar(":") },
                    ";" to { sendChar(";") }
                ),
                sticky = sticky
            )
        }
    }

    // Focus happens on user tap / ⌨ (see above), never on composition.
}
