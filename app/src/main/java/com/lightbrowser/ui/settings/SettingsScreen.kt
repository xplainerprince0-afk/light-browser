package com.lightbrowser.ui.settings

import android.webkit.CookieManager
import android.webkit.WebStorage
import android.webkit.WebView
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.rememberCoroutineScope
import com.lightbrowser.data.Backup
import com.lightbrowser.data.BookmarkStorage
import com.lightbrowser.data.HistoryStorage
import com.lightbrowser.data.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val LangModes = listOf(
    "system" to "System",
    "en" to "English",
    "hi" to "हिन्दी",
    "es" to "Español"
)

private val SearchEngines = listOf(
    "google" to "Google",
    "bing" to "Bing",
    "duckduckgo" to "DDG",
    "brave" to "Brave",
    "yahoo" to "Yahoo",
    "ecosia" to "Ecosia"
)

private val ThemeModes = listOf(
    "system" to "System",
    "dark" to "Dark",
    "light" to "Light"
)

fun applyLang(ctx: android.content.Context, key: String) {
    try {
        val tags = if (key == "system" || key.isBlank()) "" else key
        androidx.appcompat.app.AppCompatDelegate.setApplicationLocales(
            androidx.core.os.LocaleListCompat.forLanguageTags(tags)
        )
    } catch (_: Exception) {}
}

@Composable
fun SettingsScreen(modifier: Modifier = Modifier, onThemeChange: (String) -> Unit = {}) {
    val ctx = LocalContext.current
    val focusManager = LocalFocusManager.current

    var home by remember { mutableStateOf(safeGet { Prefs.homePage } ?: "https://www.google.com") }
    var js by remember { mutableStateOf(safeGet { Prefs.jsEnabled } ?: true) }
    var desktop by remember { mutableStateOf(safeGet { Prefs.desktopMode } ?: false) }
    var adblock by remember { mutableStateOf(safeGet { Prefs.adBlock } ?: false) }
    var saveSiteData by remember { mutableStateOf(safeGet { Prefs.saveSiteData } ?: true) }
    var cache by remember { mutableStateOf(safeGet { Prefs.cacheEnabled } ?: true) }
    var engine by remember { mutableStateOf(safeGet { Prefs.searchEngine } ?: "google") }
    var themeMode by remember { mutableStateOf(safeGet { Prefs.themeMode } ?: "system") }
    var trueBlack by remember { mutableStateOf(safeGet { Prefs.trueBlack } ?: false) }
    var uiScale by remember { mutableFloatStateOf(safeGet { Prefs.uiFontScale } ?: 1f) }
    var lang by remember { mutableStateOf(safeGet { Prefs.appLang } ?: "system") }
    var termScale by remember { mutableFloatStateOf(safeGet { Prefs.terminalFontScale } ?: 1f) }
    var playerSpeed by remember { mutableFloatStateOf(safeGet { Prefs.playerSpeed } ?: 1f) }
    val backupScope = rememberCoroutineScope()

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) backupScope.launch(Dispatchers.IO) {
            try {
                val json = Backup.export(ctx).toString()
                ctx.contentResolver.openOutputStream(uri)?.use { it.write(json.toByteArray()) }
                withContext(Dispatchers.Main) { Toast.makeText(ctx, "Backup saved", Toast.LENGTH_SHORT).show() }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { Toast.makeText(ctx, "Export failed: ${e.message}", Toast.LENGTH_LONG).show() }
            }
        }
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) backupScope.launch(Dispatchers.IO) {
            try {
                val text = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
                val n = Backup.import(ctx, org.json.JSONObject(text))
                withContext(Dispatchers.Main) {
                    Toast.makeText(ctx, "Restored $n stores — restart app", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { Toast.makeText(ctx, "Import failed: ${e.message}", Toast.LENGTH_LONG).show() }
            }
        }
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            SettingsCard(title = "Browser") {
                OutlinedTextField(
                    value = home,
                    onValueChange = {
                        home = it
                        // Auto-save on change (was IME-Done only — back/gesture lost input).
                        val u = it.trim()
                        if (u.isNotEmpty()) {
                            val norm = when {
                                u.startsWith("lb://") -> u
                                u.startsWith("http://") || u.startsWith("https://") -> u
                                u.contains(".") && !u.contains(" ") -> "https://$u"
                                else -> u
                            }
                            try { safeSet { Prefs.homePage = norm } } catch (_: Exception) {}
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Homepage") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = {
                        val u = home.trim()
                        if (u.isNotEmpty()) {
                            val norm = when {
                                u.startsWith("lb://") -> u
                                u.startsWith("http://") || u.startsWith("https://") -> u
                                u.contains(".") && !u.contains(" ") -> "https://$u"
                                else -> u
                            }
                            safeSet { Prefs.homePage = norm }
                            home = norm
                            Toast.makeText(ctx, "Homepage saved", Toast.LENGTH_SHORT).show()
                        }
                        focusManager.clearFocus()
                    })
                )
                SwitchRow(label = "JavaScript", checked = js, onChange = {
                    js = it
                    safeSet { Prefs.jsEnabled = it }
                })
                SwitchRow(label = "Desktop mode", checked = desktop, onChange = {
                    desktop = it
                    safeSet { Prefs.desktopMode = it }
                    Toast.makeText(
                        ctx,
                        if (it) "Desktop mode ON (reload tab)" else "Desktop OFF",
                        Toast.LENGTH_SHORT
                    ).show()
                })
                SwitchRow(label = "Ad block", checked = adblock, onChange = {
                    adblock = it
                    safeSet { Prefs.adBlock = it }
                })
                SwitchRow(label = "Save site data (logins)", checked = saveSiteData, onChange = {
                    saveSiteData = it
                    safeSet { Prefs.saveSiteData = it }
                })
                SwitchRow(label = "HTTP cache", checked = cache, onChange = {
                    cache = it
                    safeSet { Prefs.cacheEnabled = it }
                })
                Text("Search engine", style = MaterialTheme.typography.labelLarge)
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    SearchEngines.forEachIndexed { index, (key, label) ->
                        SegmentedButton(
                            selected = engine == key,
                            onClick = {
                                engine = key
                                safeSet { Prefs.searchEngine = key }
                                Toast.makeText(ctx, "Search: $key", Toast.LENGTH_SHORT).show()
                            },
                            shape = SegmentedButtonDefaults.itemShape(
                                index = index,
                                count = SearchEngines.size
                            ),
                            label = { Text(label, style = MaterialTheme.typography.labelSmall) }
                        )
                    }
                }
            }
        }

        item {
            SettingsCard(title = "Appearance") {
                Text("Theme", style = MaterialTheme.typography.labelLarge)
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    ThemeModes.forEachIndexed { index, (key, label) ->
                        SegmentedButton(
                            selected = themeMode == key,
                            onClick = {
                                themeMode = key
                                safeSet { Prefs.themeMode = key }
                                onThemeChange(key)
                            },
                            shape = SegmentedButtonDefaults.itemShape(
                                index = index,
                                count = ThemeModes.size
                            ),
                            label = { Text(label) }
                        )
                    }
                }
                SwitchRow(label = "True black (AMOLED)", checked = trueBlack, onChange = {
                    trueBlack = it
                    safeSet { Prefs.trueBlack = it }
                    onThemeChange(themeMode)
                })
                Text("Interface size: ${"%.2f".format(uiScale)}×", style = MaterialTheme.typography.labelLarge)
                Slider(
                    value = uiScale,
                    onValueChange = { uiScale = it },
                    onValueChangeFinished = { safeSet { Prefs.uiFontScale = uiScale } },
                    valueRange = 0.85f..1.3f
                )
                Text("Language", style = MaterialTheme.typography.labelLarge)
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    LangModes.forEachIndexed { index, (key, label) ->
                        SegmentedButton(
                            selected = lang == key,
                            onClick = {
                                lang = key
                                safeSet { Prefs.appLang = key }
                                applyLang(ctx, key)
                                Toast.makeText(ctx, "Language applied", Toast.LENGTH_SHORT).show()
                            },
                            shape = SegmentedButtonDefaults.itemShape(index = index, count = LangModes.size),
                            label = { Text(label, style = MaterialTheme.typography.labelSmall) }
                        )
                    }
                }
            }
        }

        item {
            SettingsCard(title = "Terminal") {
                Text(
                    "Font scale: ${"%.2f".format(termScale)}×",
                    style = MaterialTheme.typography.labelLarge
                )
                Slider(
                    value = termScale,
                    onValueChange = { termScale = it },
                    onValueChangeFinished = { safeSet { Prefs.terminalFontScale = termScale } },
                    valueRange = 0.7f..1.8f
                )
            }
        }

        item {
            SettingsCard(title = "Player") {
                Text(
                    "Playback speed: ${"%.2f".format(playerSpeed)}×",
                    style = MaterialTheme.typography.labelLarge
                )
                Slider(
                    value = playerSpeed,
                    onValueChange = { playerSpeed = it },
                    onValueChangeFinished = { safeSet { Prefs.playerSpeed = playerSpeed } },
                    valueRange = 0.5f..2.0f,
                    steps = 5
                )
            }
        }

        item {
            SettingsCard(title = "Data") {
                FilledTonalButton(
                    onClick = {
                        try {
                            CookieManager.getInstance().removeAllCookies(null)
                            CookieManager.getInstance().flush()
                            WebStorage.getInstance().deleteAllData()
                            try {
                                WebView(ctx).apply {
                                    clearCache(true)
                                    destroy()
                                }
                            } catch (_: Exception) {}
                            Toast.makeText(ctx, "Cache & cookies cleared", Toast.LENGTH_SHORT).show()
                        } catch (e: Exception) {
                            Toast.makeText(ctx, e.message, Toast.LENGTH_LONG).show()
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Clear cache & cookies") }
                FilledTonalButton(
                    onClick = {
                        try {
                            HistoryStorage.clear(ctx)
                            Toast.makeText(ctx, "History cleared", Toast.LENGTH_SHORT).show()
                        } catch (e: Exception) {
                            Toast.makeText(ctx, e.message, Toast.LENGTH_LONG).show()
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Clear history") }
                FilledTonalButton(
                    onClick = {
                        try {
                            BookmarkStorage.clear(ctx)
                            Toast.makeText(ctx, "Bookmarks cleared", Toast.LENGTH_SHORT).show()
                        } catch (e: Exception) {
                            Toast.makeText(ctx, e.message, Toast.LENGTH_LONG).show()
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Clear bookmarks") }
                FilledTonalButton(
                    onClick = { exportLauncher.launch("lightbrowser-backup.json") },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Export backup (JSON)") }
                FilledTonalButton(
                    onClick = { importLauncher.launch(arrayOf("application/json")) },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Import backup") }
            }
        }

        item {
            SettingsCard(title = "About") {
                Text(
                    "LightBrowser 3.0-expressive",
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    "Compose + Material 3 Expressive",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(4.dp))
                Button(
                    onClick = {
                        safeSet {
                            Prefs.homePage = "lb://home"
                            Prefs.jsEnabled = true
                            Prefs.desktopMode = false
                            Prefs.adBlock = false
                            Prefs.saveSiteData = true
                            Prefs.cacheEnabled = true
                            Prefs.searchEngine = "google"
                            Prefs.themeMode = "system"
                            Prefs.trueBlack = false
                            Prefs.uiFontScale = 1f
                            Prefs.appLang = "system"
                            Prefs.terminalFontScale = 1f
                            Prefs.playerShuffle = false
                            Prefs.playerRepeat = 0
                            Prefs.playerSpeed = 1f
                        }
                        home = "lb://home"
                        js = true
                        desktop = false
                        adblock = false
                        saveSiteData = true
                        cache = true
                        engine = "google"
                        themeMode = "system"
                        trueBlack = false
                        uiScale = 1f
                        lang = "system"
                        applyLang(ctx, "system")
                        termScale = 1f
                        playerSpeed = 1f
                        onThemeChange("system")
                        Toast.makeText(ctx, "Preferences reset", Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Filled.Refresh, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Reset preferences")
                }
            }
        }
    }
}

@Composable
private fun SettingsCard(
    modifier: Modifier = Modifier,
    title: String,
    content: @Composable () -> Unit
) {
    Card(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            content()
        }
    }
}

@Composable
private fun SwitchRow(
    modifier: Modifier = Modifier,
    label: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable { onChange(!checked) }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f)
        )
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

private fun <T> safeGet(block: () -> T): T? = try { block() } catch (_: Exception) { null }

private fun safeSet(block: () -> Unit) {
    try { block() } catch (_: Exception) {}
}
