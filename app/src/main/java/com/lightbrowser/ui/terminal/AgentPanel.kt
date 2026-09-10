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
import androidx.compose.material3.OutlinedTextField
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
                            withContext(Dispatchers.Main) { recVersion++ }
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
                        withContext(Dispatchers.Main) { recVersion++ }
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
        Text("Your commands (same store as the terminal)", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(4.dp))
        var aliases by remember { mutableStateOf(BrowserAliases.all()) }
        var exts by remember { mutableStateOf<List<String>>(emptyList()) }
        fun refreshLocal() {
            scope.launch(Dispatchers.IO) {
                val a = try { BrowserAliases.all() } catch (_: Exception) { emptyMap() }
                val e = try {
                    val sd = com.lightbrowser.data.AppCtx.ctx.filesDir.let { java.io.File(it, "sandbox/.b-ext") }
                    sd.listFiles { f -> f.isFile && f.name.endsWith(".sh") }
                        ?.map { it.name.removeSuffix(".sh") }?.sorted() ?: emptyList()
                } catch (_: Exception) { emptyList() }
                withContext(Dispatchers.Main) { aliases = a; exts = e }
            }
        }
        LaunchedEffect(Unit) { refreshLocal() }
        if (aliases.isEmpty() && exts.isEmpty()) {
            Text(
                "None yet — add an alias below or run b mkext <name> in the terminal.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        aliases.forEach { (name, exp) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "• b $name  →  ${exp.take(60)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f).clickable { onInsert("b $name") }
                )
                TextButton(onClick = {
                    scope.launch(Dispatchers.IO) {
                        try { BrowserAliases.remove(name) } catch (_: Exception) {}
                        withContext(Dispatchers.Main) { refreshLocal() }
                    }
                }) { Text("Delete") }
            }
        }
        exts.forEach { name ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "• b $name  (script)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f).clickable { onInsert("b $name") }
                )
                TextButton(onClick = {
                    scope.launch(Dispatchers.IO) {
                        try {
                            val sd = com.lightbrowser.data.AppCtx.ctx.filesDir.let { java.io.File(it, "sandbox/.b-ext") }
                            java.io.File(sd, "$name.sh").delete()
                        } catch (_: Exception) {}
                        withContext(Dispatchers.Main) { refreshLocal() }
                    }
                }) { Text("Delete") }
            }
        }
        Spacer(Modifier.height(8.dp))
        var newName by remember { mutableStateOf("") }
        var newExp by remember { mutableStateOf("") }
        var newKindScript by remember { mutableStateOf(false) }
        var formErr by remember { mutableStateOf("") }
        Text("New alias or script", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(4.dp))
        OutlinedTextField(
            value = newName,
            onValueChange = { newName = it.trim().lowercase(); formErr = "" },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Name (a-z 0-9 _ -)") },
            singleLine = true
        )
        Spacer(Modifier.height(4.dp))
        if (newKindScript) {
            Text(
                "Scripts run as shell with B_PORT/B_KEY exported — edit code in Files → Sandbox → .b-ext.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            OutlinedTextField(
                value = newExp,
                onValueChange = { newExp = it; formErr = "" },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Expansion ($1…$9, $@)") },
                singleLine = false,
                minLines = 1
            )
        }
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { newKindScript = false; formErr = "" }) {
                Text(if (!newKindScript) "✓ Alias" else "Alias")
            }
            TextButton(onClick = { newKindScript = true; formErr = "" }) {
                Text(if (newKindScript) "✓ Script" else "Script")
            }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = {
                val n = newName.trim()
                if (!n.matches(Regex("[a-z0-9_-]+"))) { formErr = "Name must be [a-z0-9_-]"; return@TextButton }
                if (isBBlocked(n)) { formErr = "'$n' is built-in — pick another name"; return@TextButton }
                scope.launch(Dispatchers.IO) {
                    var err = ""
                    try {
                        if (newKindScript) {
                            val sd = com.lightbrowser.data.AppCtx.ctx.filesDir.let { java.io.File(it, "sandbox/.b-ext") }
                            sd.mkdirs()
                            val f = java.io.File(sd, "$n.sh")
                            if (f.exists()) err = "Exists already"
                            else {
                                f.writeText(
                                    "#!/bin/sh\n# custom b command: b $n <args> (EXEC and PTY)\n" +
                                        "# args arrive in \$1..\n# PTY only: \$B_PORT/\$B_KEY reach the agent server (server must be on).\n\n" +
                                        "echo \"TODO: edit ${f.absolutePath}\"\n",
                                    Charsets.UTF_8
                                )
                                try { f.setExecutable(true) } catch (_: Exception) {}
                            }
                        } else {
                            if (newExp.isBlank()) err = "Expansion is empty"
                            else BrowserAliases.set(n, newExp.trim().take(500))
                        }
                    } catch (e: Exception) { err = e.message ?: "failed" }
                    val msg = err
                    withContext(Dispatchers.Main) {
                        if (msg.isEmpty()) {
                            newName = ""; newExp = ""; formErr = ""
                            refreshLocal()
                        } else formErr = msg
                    }
                }
            }) { Text("Save") }
        }
        if (formErr.isNotEmpty()) {
            Text(formErr, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        Spacer(Modifier.height(24.dp))
    }
}
