package com.lightbrowser.ui.terminal

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
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
private val TermWhite = Color(0xFFE8E8E8)
private val TermGreen = Color(0xFF00E676)
private val TermDim = Color(0xFF88CC88)
private val TermRed = Color(0xFFFF8A80)
private val TermKeyBg = Color(0xFF0A0A0A)

/**
 * Termux-style terminal: full-black fullscreen, flat two-row key grid,
 * tap-anywhere focuses input, long-press selects/copies, sessions on top.
 * The keyboard overlays everything below the input (no push-up weirdness).
 */
@Composable
fun TerminalScreen(
    modifier: Modifier = Modifier,
    vm: TerminalViewModel = viewModel()
) {
    val lines by vm.lines.collectAsState()
    val input by vm.input.collectAsState()
    val status by vm.status.collectAsState()
    val prompt by vm.prompt.collectAsState()
    val sessions by vm.sessions.collectAsState()
    val activeId by vm.activeId.collectAsState()
    val scope = rememberCoroutineScope()
    val snacks = remember { SnackbarHostState() }
    val clipboard = LocalClipboardManager.current
    val focusManager = LocalFocusManager.current
    val inputFocus = remember { FocusRequester() }

    var sticky by remember { mutableStateOf<String?>(null) }
    var follow by remember { mutableStateOf(true) }
    var overflow by remember { mutableStateOf(false) }
    var renameId by remember { mutableStateOf<String?>(null) }
    var renameText by remember { mutableStateOf("") }
    var fontScale by remember { mutableStateOf(try { Prefs.terminalFontScale } catch (_: Exception) { 1f }) }
    val listState = rememberLazyListState()

    LaunchedEffect(Unit) { vm.init() }
    LaunchedEffect(lines.size, activeId) {
        try {
            if (follow && lines.isNotEmpty()) listState.animateScrollToItem(lines.size - 1)
        } catch (_: Exception) {}
    }

    fun lineColor(kind: Int): Color = when (kind) {
        TermLine.OK -> TermGreen
        TermLine.ERROR -> TermRed
        TermLine.ECHO -> Color.White
        else -> TermDim
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(snacks) },
        containerColor = TermBlack
    ) { _ ->
        Column(modifier = Modifier.fillMaxSize().background(TermBlack)) {
            // ── Slim session strip + status + overflow ──
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp),
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
                                .padding(horizontal = 8.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                s.name,
                                color = if (sel) TermWhite else Color(0xFF888888),
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.sp
                            )
                            if (sessions.size > 1) {
                                Spacer(Modifier.width(4.dp))
                                IconButton(onClick = { vm.closeSession(s.id) }, modifier = Modifier.size(18.dp)) {
                                    Icon(Icons.Filled.Close, "Close", tint = Color(0xFF888888), modifier = Modifier.size(12.dp))
                                }
                            }
                        }
                    }
                    IconButton(onClick = vm::newSession, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Filled.Add, "New session", tint = TermWhite, modifier = Modifier.size(16.dp))
                    }
                }
                Text(
                    "●",
                    color = if (status == "idle") Color(0xFF444444) else TermGreen,
                    fontSize = 10.sp,
                    modifier = Modifier.padding(horizontal = 4.dp)
                )
                Box {
                    IconButton(onClick = { overflow = true }, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Filled.MoreVert, "Options", tint = TermWhite, modifier = Modifier.size(16.dp))
                    }
                    DropdownMenu(expanded = overflow, onDismissRequest = { overflow = false }) {
                        DropdownMenuItem(
                            text = { Text(if (follow) "✓ Follow output" else "Follow output") },
                            onClick = { follow = !follow }
                        )
                        DropdownMenuItem(text = { Text("Text bigger") }, onClick = {
                            fontScale = (fontScale + 0.15f).coerceAtMost(1.8f)
                            try { Prefs.terminalFontScale = fontScale } catch (_: Exception) {}
                        })
                        DropdownMenuItem(text = { Text("Text smaller") }, onClick = {
                            fontScale = (fontScale - 0.15f).coerceAtLeast(0.7f)
                            try { Prefs.terminalFontScale = fontScale } catch (_: Exception) {}
                        })
                        DropdownMenuItem(text = { Text("Rename session") }, onClick = {
                            overflow = false
                            renameId = activeId
                            renameText = sessions.firstOrNull { it.id == activeId }?.name ?: ""
                        })
                        DropdownMenuItem(text = { Text("Copy all output") }, onClick = {
                            overflow = false
                            clipboard.setText(AnnotatedString(vm.fullLog().take(100_000)))
                            scope.launch { snacks.showSnackbar("Log copied") }
                        })
                        DropdownMenuItem(text = { Text("Paste") }, onClick = {
                            overflow = false
                            try {
                                clipboard.getText()?.text?.let { t ->
                                    if (t.isNotEmpty()) vm.insertText(t)
                                }
                            } catch (_: Exception) {}
                        })
                        DropdownMenuItem(text = { Text("Clear") }, onClick = { overflow = false; vm.clear() })
                        if (status != "idle") DropdownMenuItem(
                            text = { Text("Kill process") },
                            onClick = { overflow = false; vm.killRunning() }
                        )
                    }
                }
            }

            // ── Output: tap focuses input, long-press selects ──
            SelectionContainer(modifier = Modifier.weight(1f)) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .clickable(
                            interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                            indication = null,
                            onClick = { try { inputFocus.requestFocus() } catch (_: Exception) {} }
                        )
                        .padding(horizontal = 8.dp)
                ) {
                    items(lines.takeLast(500), key = { it.hashCode().toString() + it.text.hashCode() }) { line ->
                        Text(
                            line.text,
                            color = lineColor(line.kind),
                            fontFamily = FontFamily.Monospace,
                            fontSize = (13 * fontScale).sp,
                            lineHeight = (18 * fontScale).sp
                        )
                    }
                }
            }

            // ── Keys + editor ride above the keyboard as one block ──
            Column(modifier = Modifier.fillMaxWidth().imePadding()) {
                TermKeyRow(
                    keys = listOf(
                        "ESC" to { vm.insertText("") },
                        "/" to { applySticky(sticky, { sticky = null }, vm, "/") },
                        "-" to { applySticky(sticky, { sticky = null }, vm, "-") },
                        "HOME" to { vm.moveCursorTo(0) },
                        "↑" to { vm.historyUp() },
                        "END" to { vm.moveCursorTo(vm.input.value.text.length) },
                        "PGUP" to { scope.launch { try { listState.animateScrollToItem(0) } catch (_: Exception) {} } }
                    ),
                    sticky = null
                )
                TermKeyRow(
                    keys = listOf(
                        "⇥" to { vm.insertText("\t") },
                        "CTRL" to { sticky = if (sticky == "CTRL") null else "CTRL" },
                        "ALT" to { sticky = if (sticky == "ALT") null else "ALT" },
                        "←" to { vm.moveCursor(-1) },
                        "↓" to { vm.historyDown() },
                        "→" to { vm.moveCursor(1) },
                        "PGDN" to { scope.launch { try { listState.animateScrollToItem(maxOf(0, lines.size - 1)) } catch (_: Exception) {} } }
                    ),
                    sticky = sticky
                )
                // ── ONE terminal line: the "$" prompt lives INSIDE the editor, so you
                // type directly in the terminal like linux — no separate mini box.
                // Tap anywhere in it to place the cursor; double-tap selects a word.
                BasicTextField(
                    value = input,
                    onValueChange = vm::onInputChange,
                    modifier = Modifier.fillMaxWidth().focusRequester(inputFocus),
                    textStyle = TextStyle(
                        color = TermWhite,
                        fontFamily = FontFamily.Monospace,
                        fontSize = (14 * fontScale).sp,
                        lineHeight = (20 * fontScale).sp
                    ),
                    cursorBrush = SolidColor(Color.White),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = {
                        val mod = sticky
                        if (mod != null) {
                            val cur = vm.input.value.text
                            if (mod == "CTRL" && cur.length == 1) {
                                val code = cur[0].lowercaseChar() - 'a' + 1
                                if (code in 1..26) {
                                    vm.onInputChange(
                                        androidx.compose.ui.text.input.TextFieldValue(
                                            String(Character.toChars(code)),
                                            androidx.compose.ui.text.TextRange(1)
                                        )
                                    )
                                }
                            }
                            sticky = null
                        }
                        vm.submit()
                    }),
                    decorationBox = { inner ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                prompt,
                                color = Color.White,
                                fontFamily = FontFamily.Monospace,
                                fontSize = (14 * fontScale).sp
                            )
                            // Full-width editor: prompt + caret share one line like a real
                            // linux terminal. Paste/send moved to the ⋮ menu + keyboard Go.
                            Box(modifier = Modifier.weight(1f)) { inner() }
                        }
                    }
                )
            }
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
private fun TermKeyRow(
    keys: List<Pair<String, () -> Unit>>,
    sticky: String?
) {
    Row(modifier = Modifier.fillMaxWidth().background(TermKeyBg)) {
        keys.forEach { (label, onTap) ->
            val armed = (label == "CTRL" && sticky == "CTRL") || (label == "ALT" && sticky == "ALT")
            Box(
                modifier = Modifier
                    .weight(1f)
                    .background(if (armed) TermWhite else Color.Transparent)
                    .clickable(onClick = onTap)
                    .padding(vertical = 10.dp),
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

private fun applySticky(sticky: String?, clear: () -> Unit, vm: TerminalViewModel, ins: String) {
    if (sticky != null) {
        vm.insertText(if (sticky == "CTRL") "^$ins" else "M-$ins")
        clear()
    } else vm.insertText(ins)
}
