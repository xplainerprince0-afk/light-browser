package com.rg.webloom.ui.terminal

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.activity.compose.BackHandler
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.Badge
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
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
import com.rg.webloom.data.Prefs
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
    active: Boolean = true,
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

    var sticky by rememberSaveable { mutableStateOf<String?>(null) }
    var follow by rememberSaveable { mutableStateOf(true) }
    var ptyMode by rememberSaveable { mutableStateOf(false) }
    var ptyForceShell by rememberSaveable { mutableStateOf(false) }
    var showAgent by remember { mutableStateOf(false) }
    val recording by com.rg.webloom.data.BrowserAgent.recording.collectAsState()
    var renameId by remember { mutableStateOf<String?>(null) }
    var renameText by remember { mutableStateOf("") }
    var fontScale by rememberSaveable { mutableStateOf(try { Prefs.terminalFontScale } catch (_: Exception) { 1f }) }
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
    // Focus only while this tab is frontmost. The screen stays composed
    // offscreen, so an unconditional requestFocus opened the keyboard at
    // app launch (and stole it on every return). Leaving clears it.
    LaunchedEffect(active, ptyMode) {
        try {
            if (active && !ptyMode) {
                kotlinx.coroutines.delay(150)
                focus.requestFocus()
            } else {
                focus.freeFocus()
            }
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
                if (t.isEmpty()) {
                    scope.launch { snacks.showSnackbar("Clipboard empty") }
                    return
                }
                if (ptyMode) {
                    try { ptyCtl.pasteText?.invoke(t) } catch (_: Exception) {}
                    try { ptyCtl.showKeyboard?.invoke() } catch (_: Exception) {}
                } else vm.insertText(t)
                scope.launch { snacks.showSnackbar("Pasted ${t.length} chars") }
            } ?: scope.launch { snacks.showSnackbar("Clipboard empty") }
        } catch (_: Exception) {}
    }

    // PTY owns focus (a View): release the EXEC editor so typing can't land
    // in the hidden field (the "invisible input" bug). Refocus the PTY view
    // on entering (no keyboard force — it opens on tap/⌨).
    LaunchedEffect(ptyMode) {
        if (ptyMode) {
            try { focusManager.clearFocus() } catch (_: Exception) {}
            try { kotlinx.coroutines.delay(150); ptyCtl.refocus?.invoke() } catch (_: Exception) {}
        }
    }
    // Back disarms a stuck CTRL/ALT before anything else (drawer/sheets
    // auto-dismiss via the framework first; exit arm is the activity's job).
    BackHandler(enabled = active && sticky != null) { sticky = null }

    // Drawer opens on left-edge long-press (forwarded by MainActivity's
    // edge strip via drawerAsk — the old corner strip sat under the edge
    // zones and lost the gesture race).
    val drawerAsk = InsetDebug.drawerAsk
    LaunchedEffect(drawerAsk) {
        if (drawerAsk > 0) openDrawer()
    }
    // Drawer closed in PTY → hand focus back (toggles steal it → invisible typing).
    LaunchedEffect(drawerState.currentValue) {
        if (ptyMode && !drawerState.isOpen) {
            try { ptyCtl.refocus?.invoke() } catch (_: Exception) {}
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        // No swipe capture: middle-screen drags must reach the terminal
        // (was: laggy conflicts). Corner long-press strip only (below).
        gesturesEnabled = false,
        modifier = modifier.fillMaxSize(),
        drawerContent = {
            ModalDrawerSheet(
                drawerContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                modifier = Modifier.widthIn(max = 320.dp)
            ) {
                // Scrollable: sessions (up to 8) + actions overflow on small phones.
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    "Terminal",
                    color = MaterialTheme.colorScheme.onSurface,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 14.sp,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)
                )
                // Mode switch lives here (Termux style).
                NavigationDrawerItem(
                    label = { Text(if (ptyMode) "✓ PTY terminal" else "PTY terminal", fontSize = 13.sp) },
                    selected = ptyMode,
                    onClick = { ptyMode = true; closeDrawer() },
                    modifier = Modifier.padding(horizontal = 12.dp)
                )
                NavigationDrawerItem(
                    label = { Text(if (!ptyMode) "✓ EXEC terminal" else "EXEC terminal", fontSize = 13.sp) },
                    selected = !ptyMode,
                    onClick = { ptyMode = false; closeDrawer() },
                    modifier = Modifier.padding(horizontal = 12.dp)
                )
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                if (ptyMode) {
                    NavigationDrawerItem(
                        label = { Text(if (ptyForceShell) "PTY runs: shell" else "PTY runs: opencode", fontSize = 13.sp) },
                        selected = false,
                        onClick = { ptyForceShell = !ptyForceShell; closeDrawer() },
                        modifier = Modifier.padding(horizontal = 12.dp)
                    )
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                }
                Text(
                    "Sessions",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                                IconButton(
                                    onClick = { vm.closeSession(s.id) },
                                    modifier = Modifier.size(48.dp)
                                ) {
                                    Icon(Icons.Filled.Close, "Close session ${s.name}", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
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
                    badge = { if (recording) Badge(containerColor = MaterialTheme.colorScheme.error) },
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
                NavigationDrawerItem(
                    label = { Text(if (ptyMode) "Copy (selection)" else "Copy all output", fontSize = 13.sp) },
                    selected = false,
                    onClick = {
                        if (ptyMode) {
                            try {
                                val t = ptyCtl.copyAll?.invoke()
                                if (t.isNullOrEmpty()) scope.launch { snacks.showSnackbar("Nothing selected — long-press to select") }
                                else scope.launch { snacks.showSnackbar("Copied ${t.length} chars") }
                            } catch (_: Exception) {}
                        } else {
                            clipboard.setText(AnnotatedString(vm.fullLog().take(100_000)))
                            scope.launch { snacks.showSnackbar("Log copied") }
                        }
                        closeDrawer()
                    },
                    modifier = Modifier.padding(horizontal = 12.dp)
                )
                if (!ptyMode) {
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
        }
    ) {
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(snacks) },
        containerColor = MaterialTheme.colorScheme.surfaceContainerLowest
    ) { innerPad ->
        // Measured lift lives on the keys themselves (keyboardLift()):
        // visible-frame height covers suggestion strips that IME insets
        // omit. Consume navigationBars only — consuming IME here would zero
        // the live keys-level IME read and bury Row 2 (see InsetDebug).
        // Scaffold pad applied so content never hides under system bars.
        Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainerLowest).padding(innerPad)) {
        Column(
            modifier = Modifier.fillMaxSize()
                .consumeWindowInsets(WindowInsets.navigationBars)
        ) {
            if (ptyMode) {
                PtyTab(
                    ctl = ptyCtl,
                    forceShell = ptyForceShell,
                    fontScale = fontScale,
                    onShellFallback = { ptyForceShell = true },
                    onExitToExec = { ptyMode = false },
                    modifier = Modifier.weight(1f).fillMaxWidth()
                )
            } else {
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            Column(modifier = Modifier.fillMaxSize()) {
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
                        if (mod == "CTRL" && status != "idle") {
                            try { vm.interrupt() } catch (_: Exception) {}
                            sticky = null
                            return@KeyboardActions
                        }
                        // Idle CTRL/ALT+Enter: convert/insert only, don't submit
                        // (was: converted the char AND submitted it as a command).
                        var consumed = false
                        if (mod == "CTRL") {
                            consumed = try { vm.consumeCtrlChar() } catch (_: Exception) { false }
                        } else if (mod == "ALT") {
                            try { vm.insertText("\u001B") } catch (_: Exception) {}
                            consumed = true
                        }
                        sticky = null
                        if (consumed) return@KeyboardActions
                    }
                    vm.submit()
                })
            )

            // ── Keys ride the measured keyboard top (keyboardLift(): ──
            // visible-frame height, suggestion strip included) and scroll ──
            // sideways for the full set ──
            androidx.compose.material3.HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Column(modifier = Modifier.fillMaxWidth().keyboardLift()) {
                TermKeyRow(
                    keys = listOf(
                        // Char keys route through sticky (CTRL+letter → control
                        // byte, ALT+x → ESC x). Nav keys clear a stale sticky
                        // and act plain — never leave CTRL armed (was: stuck).
                        "ESC" to { if (vm.applyStickyKey(sticky, "")) sticky = null },
                        "TAB" to { if (vm.applyStickyKey(sticky, "\t")) sticky = null },
                        "/" to { if (vm.applyStickyKey(sticky, "/")) sticky = null },
                        "-" to { if (vm.applyStickyKey(sticky, "-")) sticky = null },
                        "HOME" to { if (sticky == "ALT") { vm.insertText("[H"); sticky = null } else { sticky = null; vm.moveLineHome() } },
                        "END" to { if (sticky == "ALT") { vm.insertText("[F"); sticky = null } else { sticky = null; vm.moveLineEnd() } },
                        "PGUP" to { sticky = null; vm.moveCursorTo(0) },
                        "PGDN" to { sticky = null; vm.moveCursorTo(999999) },
                        "|" to { if (vm.applyStickyKey(sticky, "|")) sticky = null }
                    ),
                    sticky = sticky
                )
                TermKeyRow(
                    keys = listOf(
                        "CTRL" to { sticky = if (sticky == "CTRL") null else "CTRL" },
                        "ALT" to { sticky = if (sticky == "ALT") null else "ALT" },
                        "←" to { sticky = null; vm.moveCursor(-1) },
                        "↑" to { sticky = null; vm.historyUp() },
                        "↓" to { sticky = null; vm.historyDown() },
                        "→" to { sticky = null; vm.moveCursor(1) },
                        "~" to { if (vm.applyStickyKey(sticky, "~")) sticky = null },
                        ":" to { if (vm.applyStickyKey(sticky, ":")) sticky = null },
                        ";" to { if (vm.applyStickyKey(sticky, ";")) sticky = null }
                    ),
                    sticky = sticky
                )
            }
            } // inner Column
            // Status badge for EXEC (non-interactive).
            Box(
                modifier = Modifier.align(Alignment.TopEnd).padding(top = 6.dp, end = 8.dp)
            ) {
                Badge(containerColor = MaterialTheme.colorScheme.tertiary)
            }
            } // exec Box
            } // else: exec mode
        } // content Column
        } // outer Box
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

/** Swipeable Termux key row: fixed cells, scroll sideways for the full set. */
@Composable
internal fun TermKeyRow(
    keys: List<Pair<String, () -> Unit>>,
    sticky: String?
) {
    Row(
        modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerLow)
            .horizontalScroll(rememberScrollState())
    ) {
        keys.forEach { (label, onTap) ->
            val armed = (label == "CTRL" && sticky == "CTRL") || (label == "ALT" && sticky == "ALT")
            Box(
                modifier = Modifier
                    .width(64.dp)
                    .heightIn(min = 48.dp)
                    .background(if (armed) TermWhite else Color.Transparent)
                    .clickable(
                        onClickLabel = if (armed) "$label armed, tap to disarm" else "Send $label key",
                        role = androidx.compose.ui.semantics.Role.Button,
                        onClick = onTap
                    )
                    .semanticsForKey(label, armed)
                    .padding(vertical = 9.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    label,
                    color = if (armed) TermBlack else TermWhite,
                    style = MaterialTheme.typography.labelLarge,
                    fontFamily = FontFamily.Monospace
                )
            }
        }
    }
}

internal fun Modifier.semanticsForKey(label: String, armed: Boolean): Modifier =
    this.then(
        if (label == "CTRL" || label == "ALT") {
            Modifier.semantics(mergeDescendants = true) {
                this.contentDescription = if (armed) "$label sticky on" else "$label sticky off"
                this.selected = armed
            }
        } else {
            Modifier.semantics(mergeDescendants = true) {
                this.contentDescription = "$label key"
            }
        }
    )
