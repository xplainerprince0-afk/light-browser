package com.lightbrowser.ui.terminal

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.lightbrowser.data.BrowserAgent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Agent bridge panel, owned by the Terminal tab: server start/stop, tap
 * recorder, all `b` commands (tap to insert), and how to create new ones.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentPanel(onClose: () -> Unit, onInsert: (String) -> Unit) {
    val clipboard = LocalClipboardManager.current
    val running by BrowserAgent.serverRunning.collectAsState()
    val label by BrowserAgent.serverLabel.collectAsState()
    val recordingNow by BrowserAgent.recording.collectAsState()
    val scope = rememberCoroutineScope()
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.SmartToy, null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(8.dp))
            Text("Agent bridge", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            TextButton(onClick = onClose) { Text("Done") }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            "Drive this browser from the terminal below (b open, b snap…) or from your main Termux over localhost.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(12.dp))
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (running) "● Server running" else "○ Server stopped",
                color = if (running) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = {
                scope.launch(Dispatchers.IO) {
                    if (running) BrowserAgent.stopServer()
                    else BrowserAgent.startServer()
                }
            }) { Text(if (running) "Stop" else "Start server") }
        }
        if (running) {
            Spacer(Modifier.height(4.dp))
            Surface(
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(label, style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = {
                            clipboard.setText(AnnotatedString("http://127.0.0.1:${BrowserAgent.PORT}/text?token=${BrowserAgent.token}"))
                        }) { Text("Copy URL") }
                        TextButton(onClick = {
                            clipboard.setText(AnnotatedString(BrowserAgent.token))
                        }) { Text("Copy token") }
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "From Termux: curl 'http://127.0.0.1:8089/text?token=TOKEN' — or in this terminal: b serve",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (recordingNow) "● Recording taps (${BrowserAgent.recCount()} actions)" else "○ Click recorder",
                color = if (recordingNow) androidx.compose.ui.graphics.Color.Red else MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = {
                if (recordingNow) BrowserAgent.stopRecording()
                else BrowserAgent.startRecording()
            }) { Text(if (recordingNow) "Stop" else "Start") }
        }
        var recVersion by remember { mutableStateOf(0) }
        var recs by remember { mutableStateOf<List<Pair<String, Int>>>(emptyList()) }
        LaunchedEffect(recordingNow, recVersion) {
            try {
                recs = withContext(Dispatchers.IO) { BrowserAgent.listRecordings().take(5) }
            } catch (_: Exception) { recs = emptyList() }
        }
        if (recs.isNotEmpty()) {
            recs.forEach { (f, n) ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("• $f ($n)", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                    TextButton(onClick = {
                        scope.launch(Dispatchers.IO) {
                            try { BrowserAgent.saveRecording(f.substringBefore("_")) } catch (_: Exception) {}
                            recVersion++
                        }
                    }) { Text("Save") }
                }
            }
            Spacer(Modifier.height(4.dp))
        }
        if (!recordingNow && BrowserAgent.recCount() > 0) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Unsaved: ${BrowserAgent.recCount()} actions", style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = {
                    scope.launch(Dispatchers.IO) {
                        try { BrowserAgent.saveRecording("rec") } catch (_: Exception) {}
                        recVersion++
                    }
                }) { Text("Save now") }
            }
            Spacer(Modifier.height(4.dp))
        }
        Text("Terminal commands (tap to insert)", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(4.dp))
        listOf(
            "b serve on|off — start/stop this server" to "b serve ",
            "b open <url> — navigate" to "b open ",
            "b tabs — list tabs" to "b tabs",
            "b new <url> — open tab" to "b new ",
            "b close [n] — close tab" to "b close ",
            "b home | b back | b forward | b reload | b stop" to "b ",
            "b snap — page refs + text" to "b snap",
            "b click <ref> — tap it" to "b click ",
            "b key [sel] — tap nearest button" to "b key ",
            "b tab <n> — switch tab" to "b tab ",
            "b read — article text" to "b read ",
            "b fill <ref> <val> — type it" to "b fill ",
            "b find <text> — find in page" to "b find ",
            "b js <expr> — run JS" to "b js ",
            "b shot — save screenshot" to "b shot",
            "b console — JS logs" to "b console",
            "b record start|stop|save <name>|list — capture taps" to "b record "
        ).forEach { (label, insert) ->
            Text(
                "• $label",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().clickable { onInsert(insert) }.padding(vertical = 2.dp)
            )
        }
        Spacer(Modifier.height(8.dp))
        Text("Create your own", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(4.dp))
        Text(
            "• b alias <name> <expansion> — new command (\$1…\$9, \$@); tap to try:",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            "• b alias deploy 'b open https://example.com'",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.fillMaxWidth().clickable { onInsert("b alias deploy 'b open https://example.com'") }.padding(vertical = 2.dp)
        )
        Text(
            "• b unalias <name> — delete it",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth().clickable { onInsert("b unalias ") }.padding(vertical = 2.dp)
        )
        Spacer(Modifier.height(24.dp))
    }
}
