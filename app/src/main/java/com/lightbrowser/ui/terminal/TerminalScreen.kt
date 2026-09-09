package com.lightbrowser.ui.terminal

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.rememberDrawerState
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
import androidx.compose.ui.platform.LocalFocusManager
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
    SideEffect {
        InsetDebug.imeBottomPx = imeBottom
        InsetDebug.navBottomPx = navBottom
        InsetDebug.imeVisible = imeBottom > 0
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

    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val ptyCtl = remember { PtyControl() }
    val focusManager = LocalFocusManager.current
    fun openDrawer() { scope.launch { try { drawerState.open() } catch (_: Exception) {} } }
    fun closeDrawer() { scope.launch { try { drawerState.close() } catch (_: Exception) {} } }
    fun pasteFromClipboard() {
        try {
            clipboard.getText()?.text?.let { t ->
                if (t.isEmpty()) return
                if (ptyMode) { try { ptyCtl.pasteText?.invoke(t) } catch (_: Exception) {} }
                else vm.insertText(t)
            }
        } catch (_: Exception) {}
    }

    // PTY owns focus (a View): release the EXEC editor so typing can't land
    // in the hidden field (the "invisible input" bug).
    LaunchedEffect(ptyMode) {
        if (ptyMode) { try { focusManager.clearFocus() } catch (_: Exception) {} }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = true,
        modifier = modifier.fillMaxSize(),
        drawerContent = {
            ModalDrawerSheet(drawerContainerColor = Color(0xFF111111)) {
                Text(
                    "Terminal",
                    color = TermWhite,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 14.sp,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)
                )
                Text(
                    "Sessions",
                    color = Color(0xFF888888),
                    fontSize = 11.sp,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)
                )
                sessions.forEach { s ->
                    NavigationDrawerItem(
                        label = { Text(s.name, fontFamily = FontFamily.Monospace, fontSize = 13.sp) },
                        selected = s.id == activeId,
                        onClick = { try { vm.switchSession(s.id) } catch (_: Exception) {}; closeDrawer() },
                        badge = {
                            if (sessions.size > 1) {
                                IconButton(onClick = { vm.closeSession(s.id) }, modifier = Modifier.size(22.dp)) {
                                    Icon(Icons.Filled.Close, "Close", tint = Color(0xFF888888), modifier = Modifier.size(13.dp))
                                }
                            }
                        },
                        modifier = Modifier.padding(horizontal = 12.dp)
                    )
                }
                NavigationDrawerItem(
                    label = { Text("+ New session", fontFamily = FontFamily.Monospace, fontSize = 13.sp) },
                    selected = false,
                    onClick = { try { vm.newSession() } catch (_: Exception) {}; closeDrawer() },
                    modifier = Modifier.padding(horizontal = 12.dp)
                )
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                NavigationDrawerItem(
                    label = { Text("Agent bridge", fontSize = 13.sp) },
                    selected = false,
                    badge = { if (recording) Text("●", color = Color.Red, fontSize = 12.sp) },
                    onClick = { closeDrawer(); showAgent = true },
                    modifier = Modifier.padding(horizontal = 12.dp)
                )
                NavigationDrawerItem(
                    label = { Text(if (follow) "✓ Follow output" else "Follow output", fontSize = 13.sp) },
                    selected = false,
                    onClick = { follow = !follow; closeDrawer() },
                    modifier = Modifier.padding(horizontal = 12.dp)
                )
                NavigationDrawerItem(
                    label = { Text("Text bigger", fontSize = 13.sp) },
                    selected = false,
                    onClick = {
                        fontScale = (fontScale + 0.15f).coerceAtMost(1.8f)
                        try { Prefs.terminalFontScale = fontScale } catch (_: Exception) {}
                        closeDrawer()
                    },
                    modifier = Modifier.padding(horizontal = 12.dp)
                )
                NavigationDrawerItem(
                    label = { Text("Text smaller", fontSize = 13.sp) },
                    selected = false,
                    onClick = {
                        fontScale = (fontScale - 0.15f).coerceAtLeast(0.7f)
                        try { Prefs.terminalFontScale = fontScale } catch (_: Exception) {}
                        closeDrawer()
                    },
                    modifier = Modifier.padding(horizontal = 12.dp)
                )
                if (!ptyMode) {
                    NavigationDrawerItem(
                        label = { Text("Rename session", fontSize = 13.sp) },
                        selected = false,
                        onClick = {
                            renameId = activeId
                            renameText = sessions.firstOrNull { it.id == activeId }?.name ?: ""
                            closeDrawer()
                        },
                        modifier = Modifier.padding(horizontal = 12.dp)
                    )
                } else {
                    NavigationDrawerItem(
                        label = { Text("⌨ Keyboard", fontSize = 13.sp) },
                        selected = false,
                        onClick = { try { ptyCtl.showKeyboard?.invoke() } catch (_: Exception) {}; closeDrawer() },
                        modifier = Modifier.padding(horizontal = 12.dp)
                    )
                }
                NavigationDrawerItem(
                    label = { Text("Paste", fontSize = 13.sp) },
                    selected = false,
                    onClick = { pasteFromClipboard(); closeDrawer() },
                    modifier = Modifier.padding(horizontal = 12.dp)
                )
                if (!ptyMode) {
                    NavigationDrawerItem(
                        label = { Text("Copy all output", fontSize = 13.sp) },
                        selected = false,
                        onClick = {
                            clipboard.setText(AnnotatedString(vm.fullLog().take(100_000)))
                            scope.launch { snacks.showSnackbar("Log copied") }
                            closeDrawer()
                        },
                        modifier = Modifier.padding(horizontal = 12.dp)
                    )
                    NavigationDrawerItem(
                        label = { Text("Clear", fontSize = 13.sp) },
                        selected = false,
                        onClick = { vm.clear(); closeDrawer() },
                        modifier = Modifier.padding(horizontal = 12.dp)
                    )
                    if (status != "idle") NavigationDrawerItem(
                        label = { Text("Kill process", fontSize = 13.sp) },
                        selected = false,
                        onClick = { vm.killRunning(); closeDrawer() },
                        modifier = Modifier.padding(horizontal = 12.dp)
                    )
                }
            }
        }
    ) {
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(snacks) },
        containerColor = TermBlack
    ) { _ ->
        Column(modifier = Modifier.fillMaxSize().background(TermBlack)) {
            // ── Slim bar: drawer + mode + context. Everything else → drawer. ──
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Tap OR long-press the corner → drawer (Termux-style).
                Box(
                    modifier = Modifier
                        .combinedClickable(onClick = { openDrawer() }, onLongClick = { openDrawer() })
                        .padding(10.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Filled.Menu, "Menu", tint = TermWhite, modifier = Modifier.size(18.dp))
                }
                if (ptyMode) {
                    ModeChip("EXEC") { ptyMode = false }
                    ModeChip(if (ptyOpencode) "Shell" else "opencode") { ptyOpencode = !ptyOpencode }
                    Spacer(modifier = Modifier.weight(1f))
                    TextButton(onClick = { try { ptyCtl.showKeyboard?.invoke() } catch (_: Exception) {} }) {
                        Text("⌨", color = TermWhite, fontSize = 14.sp)
                    }
                } else {
                    ModeChip("PTY") { ptyMode = true }
                    Spacer(modifier = Modifier.weight(1f))
                    Text("●", color = if (status == "idle") Color(0xFF444444) else TermGreen, fontSize = 10.sp, modifier = Modifier.padding(end = 12.dp))
                }
            }

            if (ptyMode) {
                PtyTab(
                    useOpencode = ptyOpencode,
                    ctl = ptyCtl,
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

            // ── Keys hug the keyboard (ime minus nav — see keyboardHug) and ──
            // scroll sideways for the full set ──
            Column(modifier = Modifier.fillMaxWidth().keyboardHug()) {
                TermKeyRow(
                    keys = listOf(
                        "ESC" to { vm.insertText("\u001B") },
                        "TAB" to { vm.insertText("\t") },
                        "/" to { if (vm.applyStickyKey(sticky, "/")) sticky = null },
                        "-" to { if (vm.applyStickyKey(sticky, "-")) sticky = null },
                        "HOME" to { vm.moveLineHome() },
                        "↑" to { vm.historyUp() },
                        "END" to { vm.moveLineEnd() },
                        "PGUP" to { vm.moveCursorTo(0) },
                        "PGDN" to { vm.moveCursorTo(999999) },
                        "|" to { if (vm.applyStickyKey(sticky, "|")) sticky = null }
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
                        "→" to { vm.moveCursor(1) },
                        "~" to { if (vm.applyStickyKey(sticky, "~")) sticky = null },
                        ":" to { if (vm.applyStickyKey(sticky, ":")) sticky = null },
                        ";" to { if (vm.applyStickyKey(sticky, ";")) sticky = null }
                    ),
                    sticky = sticky
                )
            }
            } // else: exec mode
        }
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

/** Small mode chip for the slim bar. */
@Composable
private fun ModeChip(label: String, onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.padding(horizontal = 0.dp)) {
        Text(label, color = TermWhite, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
    }
}

/** Swipeable Termux key row: fixed cells, scroll sideways for the full set. */
@Composable
internal fun TermKeyRow(
    keys: List<Pair<String, () -> Unit>>,
    sticky: String?
) {
    Row(
        modifier = Modifier.fillMaxWidth().background(Color(0xFF0A0A0A))
            .horizontalScroll(rememberScrollState())
    ) {
        keys.forEach { (label, onTap) ->
            val armed = (label == "CTRL" && sticky == "CTRL") || (label == "ALT" && sticky == "ALT")
            Box(
                modifier = Modifier
                    .width(64.dp)
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
