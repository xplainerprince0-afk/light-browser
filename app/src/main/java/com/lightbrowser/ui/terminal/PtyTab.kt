package com.lightbrowser.ui.terminal

import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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

/**
 * True PTY terminal (Termux emulator+view, Apache-2.0): full-screen TUIs like
 * `opencode` work here — raw mode, alt-screen, resize, real signals.
 * Runs with cwd=sandbox so agents act inside the sandbox.
 */
@Composable
fun PtyTab(
    useOpencode: Boolean,
    onToggleTarget: () -> Unit,
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
    val opencodeOk = useOpencode && ocBin.exists() && ocBin.canExecute()
    val shellPath = if (opencodeOk) ocBin.absolutePath else "/system/bin/sh"
    val shellArgs = if (opencodeOk) arrayOf(ocBin.absolutePath) else arrayOf("sh")
    val env = remember(sd) { AlpineEnv.buildEnvironment(sd, sd) + "COLORTERM=truecolor" }

    val sessionClient = remember {
        object : TerminalSessionClient {
            override fun onTextChanged(changedSession: TerminalSession) {
                try { termView?.onScreenUpdated() } catch (_: Exception) {}
            }
            override fun onTitleChanged(changedSession: TerminalSession) {}
            override fun onSessionFinished(finishedSession: TerminalSession) {
                try { exited = finishedSession.exitStatus } catch (_: Exception) { exited = -1 }
            }
            override fun onCopyTextToClipboard(session: TerminalSession, text: String) {}
            override fun onPasteTextFromClipboard(session: TerminalSession?) {}
            override fun onBell(session: TerminalSession) {}
            override fun onColorsChanged(session: TerminalSession) {}
            override fun setTerminalShellPid(session: TerminalSession, pid: Int) {}
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
                    if (ctrlDown && codePoint in 97..122) {
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
        }
    }

    fun writeBytes(b: ByteArray) {
        try { session?.write(b, 0, b.size) } catch (_: Exception) {}
        sticky = null
    }
    fun writeText(s: String) = writeBytes(s.toByteArray(Charsets.UTF_8))

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

    Column(modifier = modifier.fillMaxSize().background(Color.Black)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                if (opencodeOk) "● PTY · opencode" else "● PTY · shell",
                color = Color(0xFF4CAF50),
                fontSize = 12.sp,
                modifier = Modifier.weight(1f)
            )
            OutlinedButton(onClick = onToggleTarget) {
                Text(if (useOpencode) "Shell" else "opencode", fontSize = 12.sp)
            }
        }
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            AndroidView(
                factory = { c ->
                    TerminalView(c, null).also { v ->
                        v.setTerminalViewClient(viewClient)
                        try { v.setTextSize(13) } catch (_: Exception) {}
                        try { v.setBackgroundColor(Color.Black.toArgb()) } catch (_: Exception) {}
                        v.isFocusable = true
                        v.isFocusableInTouchMode = true
                        termView = v
                    }
                },
                update = { v ->
                    v.setTerminalViewClient(viewClient)
                    session?.let { s -> try { v.attachSession(s) } catch (_: Exception) {} }
                    try { if (v.isFocused) Unit else v.requestFocus() } catch (_: Exception) {}
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
        // Keys hug the keyboard, same as exec mode.
        Column(modifier = Modifier.fillMaxWidth().imePadding()) {
            TermKeyRow(
                keys = listOf(
                    "ESC" to { writeText("\u001B") },
                    "/" to {
                        if (sticky == "CTRL") writeBytes(byteArrayOf(0x1F)) else writeText("/")
                    },
                    "-" to { writeText("-") },
                    "HOME" to { writeText("\u001B[H") },
                    "↑" to { writeText("\u001B[A") },
                    "END" to { writeText("\u001B[F") },
                    "PGUP" to { writeText("\u001B[5~") }
                ),
                sticky = null
            )
            TermKeyRow(
                keys = listOf(
                    "CTRL" to { sticky = if (sticky == "CTRL") null else "CTRL" },
                    "ALT" to { sticky = if (sticky == "ALT") null else "ALT" },
                    "^C" to { writeBytes(byteArrayOf(0x03)) },
                    "^D" to { writeBytes(byteArrayOf(0x04)) },
                    "←" to { writeText("\u001B[D") },
                    "↓" to { writeText("\u001B[B") },
                    "→" to { writeText("\u001B[C") }
                ),
                sticky = sticky
            )
        }
    }

    LaunchedEffect(termView) {
        try { termView?.requestFocus() } catch (_: Exception) {}
    }
}
