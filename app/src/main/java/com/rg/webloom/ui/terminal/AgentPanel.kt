package com.rg.webloom.ui.terminal

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
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
import com.rg.webloom.data.BrowserAgent
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
                color = if (recordingNow) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.weight(1f)
            )
            if (recordingNow) {
                val paused by BrowserAgent.recPaused.collectAsState()
                TextButton(onClick = {
                    try {
                        if (paused) BrowserAgent.resumeRecording() else BrowserAgent.pauseRecording()
                    } catch (_: Exception) {}
                }) { Text(if (paused) "Resume" else "Pause") }
            }
            TextButton(onClick = {
                scope.launch(Dispatchers.IO) {
                    try {
                        if (recordingNow) BrowserAgent.stopRecording()
                        else BrowserAgent.startRecording()
                    } catch (_: Exception) {}
                    // List reloads via recordingNow change below; no bump needed.
                }
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
                    FilledTonalButton(onClick = {
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
                FilledTonalButton(onClick = {
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
            "b tabdup — duplicate current tab" to "b tabdup",
            "b home | b back | b forward | b reload | b reload-hard | b stop" to "b ",
            "b ua [mobile|desktop] | b viewport | b zoom [in|out|reset]" to "b ua ",
            "b snap — page refs + text" to "b snap",
            "b click <ref> — tap it" to "b click ",
            "b key [sel] — tap nearest button" to "b key ",
            "b tab <n> — switch tab" to "b tab ",
            "b read — article text" to "b read ",
            "b fill <ref> <val> — type it" to "b fill ",
            "b upload <ref> <file> — attach sandbox file" to "b upload ",
            "b find <text> | b find-clear | b scroll-top | b scroll-bottom" to "b find ",
            "b js <expr> — run JS" to "b js ",
            "b netlog — page resources" to "b netlog",
            "b cookies save|load|profiles — per-site logins" to "b cookies ",
            "b clear-data [all] — cookies/cache/storage" to "b clear-data ",
            "b shot — save screenshot | b shot-el <ref>" to "b shot",
            "b console — JS logs" to "b console",
            "b record start|stop|pause|save <name>|list — capture taps+swipes" to "b record ",
            "b box <ref> — coords + bounds + safe points" to "b box ",
            "b block — AI no-go sites (EXEC-only)" to "b block "
        ).forEach { (label, insert) ->
            ListItem(
                headlineContent = {
                    Text("• $label", style = MaterialTheme.typography.bodySmall)
                },
                colors = androidx.compose.material3.ListItemDefaults.colors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    headlineColor = MaterialTheme.colorScheme.onSurfaceVariant
                ),
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { onInsert(insert) }
            )
        }
        Spacer(Modifier.height(8.dp))
        Text("Your commands (same store as the terminal)", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(4.dp))
        var aliases by remember { mutableStateOf(BrowserAliases.all()) }
        var exts by remember { mutableStateOf<List<String>>(emptyList()) }
        var macros by remember { mutableStateOf<List<String>>(emptyList()) }
        var blocked by remember { mutableStateOf<List<String>>(emptyList()) }
        fun refreshLocal() {
            scope.launch(Dispatchers.IO) {
                val a = try { BrowserAliases.all() } catch (_: Exception) { emptyMap() }
                val e = try {
                    val sd = com.rg.webloom.data.AppCtx.ctx.filesDir.let { java.io.File(it, "sandbox/.b-ext") }
                    sd.listFiles { f -> f.isFile && f.name.endsWith(".sh") }
                        ?.map { it.name.removeSuffix(".sh") }?.sorted() ?: emptyList()
                } catch (_: Exception) { emptyList() }
                val m = try {
                    val sd = com.rg.webloom.data.AppCtx.ctx.filesDir.let { java.io.File(it, "sandbox/.b-cmd") }
                    sd.listFiles { f -> f.isFile && f.name.endsWith(".b") }
                        ?.map { it.name.removeSuffix(".b") }?.sorted() ?: emptyList()
                } catch (_: Exception) { emptyList() }
                val b = try { BBlock.all().sorted() } catch (_: Exception) { emptyList() }
                withContext(Dispatchers.Main) { aliases = a; exts = e; macros = m; blocked = b }
            }
        }
        LaunchedEffect(Unit) { refreshLocal() }
        if (aliases.isEmpty() && exts.isEmpty() && macros.isEmpty()) {
            Text(
                "None yet — add one below, or run b mkcmd / b mkext <name> in the terminal.",
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
                FilledTonalButton(
                    onClick = {
                        scope.launch(Dispatchers.IO) {
                            try { BrowserAliases.remove(name) } catch (_: Exception) {}
                            withContext(Dispatchers.Main) { refreshLocal() }
                        }
                    },
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer
                    )
                ) { Text("Delete") }
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
                FilledTonalButton(
                    onClick = {
                        scope.launch(Dispatchers.IO) {
                            try {
                                val sd = com.rg.webloom.data.AppCtx.ctx.filesDir.let { java.io.File(it, "sandbox/.b-ext") }
                                java.io.File(sd, "$name.sh").delete()
                            } catch (_: Exception) {}
                            withContext(Dispatchers.Main) { refreshLocal() }
                        }
                    },
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer
                    )
                ) { Text("Delete") }
            }
        }
        macros.forEach { name ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "• b $name  (macro)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f).clickable { onInsert("b $name") }
                )
                FilledTonalButton(
                    onClick = {
                        scope.launch(Dispatchers.IO) {
                            try {
                                val sd = com.rg.webloom.data.AppCtx.ctx.filesDir.let { java.io.File(it, "sandbox/.b-cmd") }
                                java.io.File(sd, "$name.b").delete()
                            } catch (_: Exception) {}
                            withContext(Dispatchers.Main) { refreshLocal() }
                        }
                    },
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer
                    )
                ) { Text("Delete") }
            }
        }
        Spacer(Modifier.height(8.dp))
        var newName by remember { mutableStateOf("") }
        var newExp by remember { mutableStateOf("") }
        var newKind by remember { mutableStateOf(0) } // 0 alias, 1 script, 2 macro
        var formErr by remember { mutableStateOf("") }
        Text("New alias, script or macro", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(4.dp))
        OutlinedTextField(
            value = newName,
            onValueChange = { newName = it.trim().lowercase(); formErr = "" },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Name (a-z 0-9 _ -)") },
            singleLine = true
        )
        Spacer(Modifier.height(4.dp))
        if (newKind == 1) {
            Text(
                "Scripts run as shell with B_PORT/B_KEY exported — edit code in Files → Sandbox → .b-ext.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else if (newKind == 2) {
            OutlinedTextField(
                value = newExp,
                onValueChange = { newExp = it; formErr = "" },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("b-commands, one per line (# comments)") },
                singleLine = false,
                minLines = 3
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
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            listOf("Alias", "Script", "Macro").forEachIndexed { index, label ->
                SegmentedButton(
                    selected = newKind == index,
                    onClick = { newKind = index; formErr = "" },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = 3),
                    label = { Text(label) }
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Spacer(Modifier.weight(1f))
            FilledTonalButton(onClick = {
                val n = newName.trim()
                if (!n.matches(Regex("[a-z0-9_-]+"))) { formErr = "Name must be [a-z0-9_-]"; return@FilledTonalButton }
                if (isBBlocked(n)) { formErr = "'$n' is built-in — pick another name"; return@FilledTonalButton }
                scope.launch(Dispatchers.IO) {
                    var err = ""
                    try {
                        if (newKind == 1) {
                            val sd = com.rg.webloom.data.AppCtx.ctx.filesDir.let { java.io.File(it, "sandbox/.b-ext") }
                            sd.mkdirs()
                            val f = java.io.File(sd, "$n.sh")
                            if (f.exists()) err = "Exists already"
                            else {
                                f.writeText(
                                    "#!/bin/sh\n# custom b command — args in \$1..\n" +
                                        "# agent server: \$B_PORT / \$B_KEY (server must be on).\n\n" +
                                        "echo \"TODO: edit \$HOME/.b-ext/$n.sh\"\n",
                                    Charsets.UTF_8
                                )
                                try { f.setExecutable(true) } catch (_: Exception) {}
                            }
                        } else if (newKind == 2) {
                            val lines = newExp.lines().map { it.trim() }
                                .filter { it.isNotEmpty() && !it.startsWith("#") }
                            if (lines.isEmpty()) err = "Macro is empty"
                            else {
                                val sd = com.rg.webloom.data.AppCtx.ctx.filesDir.let { java.io.File(it, "sandbox/.b-cmd") }
                                sd.mkdirs()
                                val f = java.io.File(sd, "$n.b")
                                if (f.exists()) err = "Exists already"
                                else {
                                    f.writeText(
                                        "# macro: b $n\n" + lines.joinToString("\n") + "\n",
                                        Charsets.UTF_8
                                    )
                                }
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
        Spacer(Modifier.height(8.dp))
        Text("Blocked sites (AI no-go)", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(4.dp))
        if (blocked.isEmpty()) {
            Text(
                "None — b block <domain> keeps agents off settings, billing, …",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        blocked.forEach { host ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "⛔ $host",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = {
                    scope.launch(Dispatchers.IO) {
                        try { BBlock.remove(host) } catch (_: Exception) {}
                        withContext(Dispatchers.Main) { refreshLocal() }
                    }
                }) { Text("Unblock") }
            }
        }
        var blockField by remember { mutableStateOf("") }
        var blockErr by remember { mutableStateOf("") }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = blockField,
                onValueChange = { blockField = it; blockErr = "" },
                modifier = Modifier.weight(1f),
                label = { Text("Domain to block") },
                singleLine = true
            )
            TextButton(onClick = {
                scope.launch(Dispatchers.IO) {
                    var err = ""
                    try {
                        if (!BBlock.add(blockField)) err = "Need a domain like example.com"
                    } catch (_: Exception) { err = "failed" }
                    withContext(Dispatchers.Main) {
                        if (err.isEmpty()) { blockField = ""; blockErr = ""; refreshLocal() }
                        else blockErr = err
                    }
                }
            }) { Text("Block") }
        }
        if (blockErr.isNotEmpty()) {
            Text(blockErr, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        Spacer(Modifier.height(24.dp))
    }
}
