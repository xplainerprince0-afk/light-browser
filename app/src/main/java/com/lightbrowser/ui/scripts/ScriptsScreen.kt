package com.lightbrowser.ui.scripts

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lightbrowser.data.ScriptStorage
import com.lightbrowser.data.UserScript
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun ScriptsScreen(modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val scripts = remember { mutableStateListOf<UserScript>() }

    var query by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf<UserScript?>(null) }
    var showAdd by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<UserScript?>(null) }

    fun refresh() {
        scope.launch(Dispatchers.IO) {
            val list = try { ScriptStorage.all(ctx) } catch (_: Exception) { mutableListOf() }
            withContext(Dispatchers.Main) {
                scripts.clear()
                scripts.addAll(list)
            }
        }
    }

    LaunchedEffect(Unit) { refresh() }

    val filtered = remember(scripts.toList(), query) {
        val q = query.trim().lowercase()
        if (q.isBlank()) scripts.toList()
        else scripts.filter {
            it.name.lowercase().contains(q) ||
                it.description.lowercase().contains(q) ||
                it.code.lowercase().contains(q) ||
                it.matches.any { m -> m.lowercase().contains(q) }
        }
    }

    fun setAll(enabled: Boolean) {
        scope.launch(Dispatchers.IO) {
            try {
                val updated = scripts.map { it.copy(enabled = enabled) }
                ScriptStorage.saveAll(ctx, updated)
                withContext(Dispatchers.Main) {
                    scripts.clear()
                    scripts.addAll(updated)
                }
            } catch (_: Exception) {}
        }
    }

    fun toggleScript(sc: UserScript, enabled: Boolean) {
        scope.launch(Dispatchers.IO) {
            try {
                ScriptStorage.update(ctx, sc.copy(enabled = enabled))
                withContext(Dispatchers.Main) {
                    val idx = scripts.indexOfFirst { it.id == sc.id }
                    if (idx >= 0) scripts[idx] = sc.copy(enabled = enabled)
                }
            } catch (_: Exception) {}
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Scripts")
                        Text(
                            "${filtered.size} of ${scripts.size}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                actions = {
                    TextButton(onClick = { setAll(true) }) { Text("All on") }
                    TextButton(onClick = { setAll(false) }) { Text("All off") }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                )
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showAdd = true }) {
                Icon(Icons.Filled.Add, contentDescription = "Add script")
            }
        },
        snackbarHost = { SnackbarHost(snackbar) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .consumeWindowInsets(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Search scripts") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { query = "" }) {
                            Icon(Icons.Filled.Close, contentDescription = "Clear search")
                        }
                    }
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                shape = MaterialTheme.shapes.extraLarge
            )

            Text(
                text = "Tap + to add a Violentmonkey script · toggle to enable",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (filtered.isEmpty()) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                    )
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            Icons.Filled.Code,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            if (scripts.isEmpty()) "No userscripts yet"
                            else "No scripts match \"$query\"",
                            style = MaterialTheme.typography.titleSmall
                        )
                        Text(
                            "Tap + to add a Violentmonkey script",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(filtered, key = { it.id }) { sc ->
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                            )
                        ) {
                            ListItem(
                                headlineContent = {
                                    Text(sc.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                },
                                supportingContent = {
                                    Column {
                                        val desc = if (sc.description.isNotBlank()) sc.description
                                        else sc.code.take(120).replace("\n", " ")
                                        Text(desc, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                        Spacer(Modifier.height(2.dp))
                                        Text(
                                            matchSubtitle(sc),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.primary,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                },
                                leadingContent = {
                                    Box(
                                        modifier = Modifier
                                            .size(44.dp)
                                            .clip(androidx.compose.foundation.shape.RoundedCornerShape(14.dp))
                                            .background(
                                                if (sc.enabled) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                                                else MaterialTheme.colorScheme.surfaceVariant
                                            ),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            Icons.Filled.Code,
                                            contentDescription = null,
                                            tint = if (sc.enabled) MaterialTheme.colorScheme.primary
                                            else MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                },
                                trailingContent = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        IconButton(onClick = { editing = sc }) {
                                            Icon(Icons.Filled.Edit, contentDescription = "Edit ${sc.name}")
                                        }
                                        IconButton(onClick = { pendingDelete = sc }) {
                                            Icon(
                                                Icons.Filled.Delete,
                                                contentDescription = "Delete ${sc.name}",
                                                tint = MaterialTheme.colorScheme.error
                                            )
                                        }
                                        Spacer(Modifier.width(4.dp))
                                        Switch(
                                            checked = sc.enabled,
                                            onCheckedChange = { toggleScript(sc, it) }
                                        )
                                    }
                                },
                                modifier = Modifier.clickable { editing = sc }
                            )
                        }
                    }
                }
            }
        }
    }

    if (showAdd || editing != null) {
        ScriptEditorDialog(
            existing = editing,
            snackbar = snackbar,
            onDismiss = { showAdd = false; editing = null },
            onSaved = {
                showAdd = false
                editing = null
                refresh()
            }
        )
    }

    pendingDelete?.let { sc ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete \"${sc.name}\"?") },
            text = { Text("This userscript will be removed permanently.") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch(Dispatchers.IO) {
                        try { ScriptStorage.delete(ctx, sc.id) } catch (_: Exception) {}
                        withContext(Dispatchers.Main) {
                            scripts.removeAll { it.id == sc.id }
                            pendingDelete = null
                        }
                    }
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("Cancel") }
            }
        )
    }
}

private fun matchSubtitle(sc: UserScript): String {
    val count = sc.matches.size
    val matchPart = if (count == 0) "<all_urls> (1 implicit)"
    else if (count == 1) "1 match: ${sc.matches.first()}"
    else "${count} matches: ${sc.matches.take(2).joinToString(", ")}${if (count > 2) "…" else ""}"
    return "$matchPart · ${sc.runAt}"
}

@Composable
private fun ScriptEditorDialog(
    modifier: Modifier = Modifier,
    existing: UserScript?,
    snackbar: SnackbarHostState,
    onDismiss: () -> Unit,
    onSaved: () -> Unit
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var name by remember(existing?.id) { mutableStateOf(existing?.name ?: "") }
    var code by remember(existing?.id) { mutableStateOf(existing?.code ?: "") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) "Add Userscript" else "Edit ${existing.name}") },
        text = {
            Column(
                modifier = modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Name (blank = parse from @name)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next)
                )
                OutlinedTextField(
                    value = code,
                    onValueChange = { code = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Script code") },
                    placeholder = {
                        Text("// ==UserScript==\n// @name My Script\n// @match *://*/*\n// ==/UserScript==")
                    },
                    minLines = 10,
                    maxLines = 20
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (code.isBlank()) {
                    Toast.makeText(ctx, "Code empty", Toast.LENGTH_SHORT).show()
                    return@TextButton
                }
                scope.launch(Dispatchers.IO) {
                    try {
                        val parsed = UserScript.fromCode(code)
                        val finalName = name.ifBlank { parsed.name }
                        if (existing == null) {
                            val toSave = parsed.copy(name = finalName)
                            ScriptStorage.add(ctx, toSave)
                        } else {
                            val toSave = existing.copy(
                                name = finalName,
                                code = code,
                                description = parsed.description,
                                matches = parsed.matches,
                                runAt = parsed.runAt,
                                grants = parsed.grants
                            )
                            ScriptStorage.update(ctx, toSave)
                        }
                        withContext(Dispatchers.Main) { onSaved() }
                    } catch (e: Exception) {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(ctx, "Save failed: ${e.message}", Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }) { Text("Save") }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = {
                    val parsed = try { UserScript.fromCode(code) } catch (_: Exception) { null }
                    scope.launch {
                        snackbar.showSnackbar(
                            if (parsed == null) "Could not parse script"
                            else "matches=${parsed.matches} runAt=${parsed.runAt}"
                        )
                    }
                }) { Text("Test match") }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        }
    )
}
