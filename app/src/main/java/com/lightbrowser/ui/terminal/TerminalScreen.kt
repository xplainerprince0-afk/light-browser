package com.lightbrowser.ui.terminal

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lightbrowser.data.Prefs
import kotlinx.coroutines.launch

private val TermBlack = Color(0xFF000000)

/**
 * ONE live editor: transcript + prompt in a single field. Cursor roams the whole
 * buffer (tap to place, double-tap selects a word); Enter submits the last line.
 * Long-press gives the system copy/paste toolbar. Keys sit tight above the
 * keyboard with no dead gap.
 */
@Composable
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
fun TerminalScreen(
    modifier: Modifier = Modifier,
    vm: TerminalViewModel = viewModel()
) {
    val editor by vm.editor.collectAsState()
    val status by vm.status.collectAsState()
    val sessions by vm.sessions.collectAsState()
    val activeId by vm.activeId.collectAsState()
    val scope = rememberCoroutineScope()
    val snacks = remember { SnackbarHostState() }
    val clipboard = LocalClipboardManager.current
    val focus = remember { FocusRequester() }
    val bringer = remember { BringIntoViewRequester() }

    var sticky by remember { mutableStateOf<String?>(null) }
    var follow by remember { mutableStateOf(true) }
    var overflow by remember { mutableStateOf(false) }
    var ptyMode by remember { mutableStateOf(false) }
    var ptyOpencode by remember { mutableStateOf(false) }
    var showAgent by remember { mutableStateOf(false) }
    val recording by com.lightbrowser.data.BrowserAgent.recording.collectAsState()
    var renameId by remember { mutableStateOf<String?>(null) }
    var renameText by remember { mutableStateOf("") }
    var fontScale by remember { mutableStateOf(try { Prefs.terminalFontScale } catch (_: Exception) { 1f }) }
    val scroll = rememberScrollState()

    // Live inset readings for `kbd-diag` (diagnose keys-vs-keyboard spacing).
    val density = LocalDensity.current
    val imeBottom = WindowInsets.ime.getBottom(density)
    val navBottom = WindowInsets.navigationBars.getBottom(density)
    val imeVis = WindowInsets.isImeVisible
    SideEffect {
        InsetDebug.imeBottomPx = imeBottom
        InsetDebug.navBottomPx = navBottom
        InsetDebug.imeVisible = imeVis
    }

    LaunchedEffect(Unit) { vm.init() }
    LaunchedEffect(Unit) {
        try {
            kotlinx.coroutines.delay(300)
            focus.requestFocus()
        } catch (_: Exception) {}
    }
    // Keep the caret visible when output lands; typing at the end is already there.
    LaunchedEffect(editor.text) {
        if (!follow) return@LaunchedEffect
        try {
            // New text hasn't laid out yet on this frame — maxValue is stale
            // and the scroll lands short (prompt stays hidden). Wait one frame.
            withFrameNanos {}
            scroll.scrollTo(scroll.maxValue)
        } catch (_: Exception) {}
        try {
            bringer.bringIntoView()
        } catch (_: Exception) {}
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(snacks) },
        containerColor = TermBlack
    ) { _ ->
        Column(modifier = Modifier.fillMaxSize().background(TermBlack)) {
            // ── Slim session strip + status + overflow ──
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    sessions.forEach { s ->
                        val sel = s.id == activeId
                        Row(
                            modifier = Modifier
                                .clickable { vm.switchSession(s.id) }
                                .background(if (sel) Color(0xFF1A1A1A) else Color.Transparent)
                                .padding(horizontal = 8.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                s.name,
                                color = if (sel) TermWhite else Color(0xFF888888),
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.sp
                            )
                            if (sessions.size > 1) {
                                IconButton(onClick = { vm.closeSession(s.id) }, modifier = Modifier.size(18.dp)) {
                                    Icon(Icons.Filled.Close, "Close", tint = Color(0xFF888888), modifier = Modifier.size(12.dp))
                                }
                            }
                        }
                    }
                    IconButton(onClick = vm::newSession, modifier = Modifier.size(30.dp)) {
                        Icon(Icons.Filled.Add, "New session", tint = TermWhite, modifier = Modifier.size(16.dp))
                    }
                }
                Text("●", color = if (status == "idle") Color(0xFF444444) else TermGreen, fontSize = 10.sp)
                TextButton(
                    onClick = { ptyMode = !ptyMode },
                    modifier = Modifier.padding(horizontal = 0.dp)
                ) {
                    Text(
                        if (ptyMode) "EXEC" else "PTY",
                        color = if (ptyMode) TermGreen else TermWhite,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp
                    )
                }
                IconButton(onClick = { showAgent = true }, modifier = Modifier.size(30.dp)) {
                    Icon(
                        Icons.Filled.SmartToy, "Agent bridge",
                        tint = if (recording) Color.Red else TermWhite,
                        modifier = Modifier.size(16.dp)
                    )
                }
                Box {
                    IconButton(onClick = { overflow = true }, modifier = Modifier.size(30.dp)) {
                        Icon(Icons.Filled.MoreVert, "Options", tint = TermWhite, modifier = Modifier.size(16.dp))
                    }
                    DropdownMenu(expanded = overflow, onDismissRequest = { overflow = false }) {
                        DropdownMenuItem(
                            text = { Text(if (follow) "✓ Follow output" else "Follow output") },
                            onClick = { overflow = false; follow = !follow }
                        )
                        DropdownMenuItem(text = { Text("Text bigger") }, onClick = {
                            overflow = false
                            fontScale = (fontScale + 0.15f).coerceAtMost(1.8f)
                            try { Prefs.terminalFontScale = fontScale } catch (_: Exception) {}
                        })
                        DropdownMenuItem(text = { Text("Text smaller") }, onClick = {
                            overflow = false
                            fontScale = (fontScale - 0.15f).coerceAtLeast(0.7f)
                            try { Prefs.terminalFontScale = fontScale } catch (_: Exception) {}
                        })
                        DropdownMenuItem(text = { Text("Rename session") }, onClick = {
                            overflow = false
                            renameId = activeId
                            renameText = sessions.firstOrNull { it.id == activeId }?.name ?: ""
                        })
                        DropdownMenuItem(text = { Text("Paste") }, onClick = {
                            overflow = false
                            try {
                                clipboard.getText()?.text?.let { t ->
                                    if (t.isNotEmpty()) vm.insertText(t)
                                }
                            } catch (_: Exception) {}
                        })
                        DropdownMenuItem(text = { Text("Agent bridge") }, onClick = {
                            overflow = false
                            showAgent = true
                        })
                        DropdownMenuItem(
                            text = { Text(if (ptyMode) "✓ PTY terminal" else "PTY terminal") },
                            onClick = { overflow = false; ptyMode = !ptyMode }
                        )
                        DropdownMenuItem(text = { Text("Copy all output") }, onClick = {
                            overflow = false
                            clipboard.setText(AnnotatedString(vm.fullLog().take(100_000)))
                            scope.launch { snacks.showSnackbar("Log copied") }
                        })
                        DropdownMenuItem(text = { Text("Clear") }, onClick = { overflow = false; vm.clear() })
                        if (status != "idle") DropdownMenuItem(
                            text = { Text("Kill process") },
                            onClick = { overflow = false; vm.killRunning() }
                        )
                    }
                }
            }

            if (ptyMode) {
                PtyTab(
                    useOpencode = ptyOpencode,
                    onToggleTarget = { ptyOpencode = !ptyOpencode },
                    onExitToExec = { ptyMode = false },
                    modifier = Modifier.weight(1f).fillMaxWidth()
                )
            } else {
            // ── THE editor: everything is one field ──
            BasicTextField(
                value = editor,
                onValueChange = vm::onEditorChange,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(scroll)
                    .focusRequester(focus)
                    .bringIntoViewRequester(bringer)
                    .padding(horizontal = 8.dp),
                textStyle = TextStyle(
                    color = TermWhite,
                    fontFamily = FontFamily.Monospace,
                    fontSize = (13 * fontScale).sp,
                    lineHeight = (18 * fontScale).sp
                ),
                cursorBrush = SolidColor(Color.White),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = {
                    val mod = sticky
                    if (mod != null) {
                        // CTRL+Enter while busy = interrupt (the ^C everyone reaches for).
                        // Otherwise convert the lone char (prompt-aware) then submit.
                        if (mod == "CTRL" && status != "idle") {
                            try { vm.interrupt() } catch (_: Exception) {}
                        } else if (mod == "CTRL") {
                            try { vm.consumeCtrlChar() } catch (_: Exception) {}
                        } else if (mod == "ALT") {
                            try { vm.insertText("\u001B") } catch (_: Exception) {}
                        }
                        sticky = null
                        if (mod == "CTRL" && status != "idle") return@KeyboardActions
                    }
                    vm.submit()
                })
            )

            // ── Keys hug the keyboard (scoped imePadding: lifts ONLY this ──
            // terminal zone — the bottom nav stays pinned behind the keyboard) ──
            Column(modifier = Modifier.fillMaxWidth().imePadding()) {
                TermKeyRow(
                    keys = listOf(
                        "ESC" to { vm.insertText("\u001B") },
                        "/" to { if (vm.applyStickyKey(sticky, "/")) sticky = null },
                        "-" to { if (vm.applyStickyKey(sticky, "-")) sticky = null },
                        "HOME" to { vm.moveLineHome() },
                        "↑" to { vm.historyUp() },
                        "END" to { vm.moveLineEnd() },
                        "PGUP" to { vm.moveCursorTo(0) }
                    ),
                    sticky = null
                )
                TermKeyRow(
                    keys = listOf(
                        "CTRL" to { sticky = if (sticky == "CTRL") null else "CTRL" },
                        "ALT" to { sticky = if (sticky == "ALT") null else "ALT" },
                        "^C" to { try { vm.interrupt() } catch (_: Exception) {} },
                        "^D" to { try { vm.sendEof() } catch (_: Exception) {} },
                        "←" to { vm.moveCursor(-1) },
                        "↓" to { vm.historyDown() },
                        "→" to { vm.moveCursor(1) }
                    ),
                    sticky = sticky
                )
            }
            } // else: exec mode
        }
    }

    if (showAgent) {
        ModalBottomSheet(
            onDismissRequest = { showAgent = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ) {
            AgentPanel(
                onClose = { showAgent = false },
                onInsert = { cmd ->
                    showAgent = false
                    try { vm.insertText(cmd) } catch (_: Exception) {}
                }
            )
        }
    }

    renameId?.let { id ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { renameId = null },
            title = { Text("Rename session") },
            text = { OutlinedTextField(value = renameText, onValueChange = { renameText = it }, singleLine = true) },
            confirmButton = {
                TextButton(onClick = {
                    renameId = null
                    if (renameText.isNotBlank()) vm.renameSession(id, renameText.trim())
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { renameId = null }) { Text("Cancel") } }
        )
    }
}

/** One flat Termux key row: 7 full-width cells, sticky CTRL/ALT invert when armed. */
@Composable
internal fun TermKeyRow(
    keys: List<Pair<String, () -> Unit>>,
    sticky: String?
) {
    Row(modifier = Modifier.fillMaxWidth().background(Color(0xFF0A0A0A))) {
        keys.forEach { (label, onTap) ->
            val armed = (label == "CTRL" && sticky == "CTRL") || (label == "ALT" && sticky == "ALT")
            Box(
                modifier = Modifier
                    .weight(1f)
                    .background(if (armed) TermWhite else Color.Transparent)
                    .clickable(onClick = onTap)
                    .padding(vertical = 9.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    label,
                    color = if (armed) TermBlack else TermWhite,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp
                )
            }
        }
    }
}
