package com.lightbrowser.ui.terminal

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AssistChip
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SelectionContainer
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lightbrowser.data.Prefs
import kotlinx.coroutines.launch

/**
 * Terminal: output is selectable (long-press any text to copy), the input is a
 * real multi-line-capable field with full cursor control — arrows, word jumps,
 * tap-to-place, and a scrub slider. Sticky CTRL/ALT send visual markers only
 * for `sh` (documented); extra keys insert real escape chars (ESC/TAB).
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
    val scope = rememberCoroutineScope()
    val snacks = remember { SnackbarHostState() }
    val clipboard = LocalClipboardManager.current
    val ctx = LocalContext.current

    var sticky by remember { mutableStateOf<String?>(null) }
    var showFont by remember { mutableStateOf(false) }
    var fontScale by remember { mutableStateOf(try { Prefs.terminalFontScale } catch (_: Exception) { 1f }) }
    val listState = rememberLazyListState()

    LaunchedEffect(Unit) { vm.init() }
    LaunchedEffect(lines.size) {
        try {
            if (lines.isNotEmpty()) listState.animateScrollToItem(lines.size - 1)
        } catch (_: Exception) {}
    }

    val green = Color(0xFF00E676)
    val dimGreen = Color(0xFF88CC88)
    val red = Color(0xFFFF8A80)

    fun lineColor(kind: Int): Color = when (kind) {
        TermLine.OK -> green
        TermLine.ERROR -> red
        TermLine.ECHO -> Color.White
        else -> dimGreen
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(snacks) },
        containerColor = Color(0xFF060A12)
    ) { _ ->
        Column(modifier = Modifier.fillMaxSize()) {
            // Status row
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "● $status",
                    color = if (status == "idle") dimGreen else green,
                    style = MaterialTheme.typography.labelMedium,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.weight(1f)
                )
                if (status != "idle") {
                    androidx.compose.material3.TextButton(onClick = vm::killRunning) {
                        Text("Kill", color = red)
                    }
                }
                IconButton(onClick = {
                    clipboard.setText(AnnotatedString(vm.fullLog().take(100_000)))
                    scope.launch { snacks.showSnackbar("Log copied") }
                }) { Icon(Icons.Filled.ContentCopy, "Copy log", tint = dimGreen) }
                IconButton(onClick = vm::clear) { Icon(Icons.Filled.Delete, "Clear", tint = dimGreen) }
                IconButton(onClick = { showFont = !showFont }) {
                    Text("A±", color = dimGreen, style = MaterialTheme.typography.labelLarge)
                }
            }

            if (showFont) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Text size", color = dimGreen, style = MaterialTheme.typography.labelMedium)
                    Spacer(Modifier.width(12.dp))
                    Slider(
                        value = fontScale,
                        onValueChange = { fontScale = it },
                        onValueChangeFinished = {
                            try { Prefs.terminalFontScale = fontScale } catch (_: Exception) {}
                        },
                        valueRange = 0.7f..1.8f,
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            // Output — selectable so ANY text (not just input) is reachable by cursor/selection
            SelectionContainer(modifier = Modifier.weight(1f)) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp)
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

            // Cursor scrub slider (basic feature: drag to move caret across the whole input)
            if (input.text.isNotEmpty()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("⇤", color = dimGreen)
                    Slider(
                        value = input.selection.start.toFloat(),
                        onValueChange = { vm.moveCursorTo(it.toInt()) },
                        valueRange = 0f..input.text.length.coerceAtLeast(1).toFloat(),
                        steps = 0,
                        modifier = Modifier.weight(1f)
                    )
                    Text("⇥", color = dimGreen)
                }
            }

            // Extra keys — sticky CTRL/ALT, cursor cluster, symbols
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                FilterChip(
                    selected = sticky == "CTRL",
                    onClick = { sticky = if (sticky == "CTRL") null else "CTRL" },
                    label = { Text("CTRL") }
                )
                FilterChip(
                    selected = sticky == "ALT",
                    onClick = { sticky = if (sticky == "ALT") null else "ALT" },
                    label = { Text("ALT") }
                )
                listOf("ESC" to "\u001B", "TAB" to "\t", "|" to "|", "/" to "/", "-" to "-", "~" to "~").forEach { (label, ins) ->
                    AssistChip(onClick = {
                        val mod = sticky
                        if (mod != null) {
                            vm.insertText(if (mod == "CTRL") "^$ins" else "M-$ins")
                            sticky = null
                        } else vm.insertText(ins)
                    }, label = { Text(label, fontFamily = FontFamily.Monospace) })
                }
                AssistChip(onClick = { vm.moveCursor(-1) }, label = { Text("◄") })
                AssistChip(onClick = { vm.moveCursor(1) }, label = { Text("►") })
                AssistChip(onClick = { vm.moveWord(true) }, label = { Text("⇤") })
                AssistChip(onClick = { vm.moveWord(false) }, label = { Text("⇥") })
                AssistChip(onClick = { vm.moveCursorTo(0) }, label = { Text("HOME") })
                AssistChip(onClick = { vm.moveCursorTo(vm.input.value.text.length) }, label = { Text("END") })
                AssistChip(onClick = vm::historyUp, label = { Text("▲") })
                AssistChip(onClick = vm::historyDown, label = { Text("▼") })
            }

            // Input row — imePadding ONLY here (no double-count black gap)
            Surface(
                color = Color(0xFF0A0A0A),
                modifier = Modifier.fillMaxWidth().imePadding()
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        prompt,
                        color = Color.White,
                        fontFamily = FontFamily.Monospace,
                        fontSize = (14 * fontScale).sp
                    )
                    OutlinedTextField(
                        value = input,
                        onValueChange = vm::onInputChange,
                        modifier = Modifier.weight(1f),
                        textStyle = androidx.compose.ui.text.TextStyle(
                            color = green,
                            fontFamily = FontFamily.Monospace,
                            fontSize = (14 * fontScale).sp
                        ),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = { vm.submit() }),
                        shape = MaterialTheme.shapes.extraLarge
                    )
                    IconButton(onClick = vm::historyUp) { Icon(Icons.Filled.ArrowUpward, "History up", tint = dimGreen) }
                    IconButton(onClick = vm::historyDown) { Icon(Icons.Filled.ArrowDownward, "History down", tint = dimGreen) }
                    IconButton(onClick = { vm.onInputChange(androidx.compose.ui.text.input.TextFieldValue("")) }) {
                        Icon(Icons.Filled.Close, "Clear input", tint = dimGreen)
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
        }
    }
}
