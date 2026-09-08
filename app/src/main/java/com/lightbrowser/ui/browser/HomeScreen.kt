package com.lightbrowser.ui.browser

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private data class Shortcut(val name: String, val url: String, val tint: Long)

private val SHORTCUTS = listOf(
    Shortcut("Google", "https://www.google.com", 0xFF4285F4),
    Shortcut("YouTube", "https://m.youtube.com", 0xFFFF0000),
    Shortcut("Wikipedia", "https://www.wikipedia.org", 0xFF636363),
    Shortcut("GitHub", "https://github.com", 0xFF6E40C9),
    Shortcut("DuckGo", "https://duckduckgo.com", 0xFFDE5833),
    Shortcut("WTR Lab", "https://wtr-lab.com", 0xFF0F766E),
    Shortcut("Bing", "https://www.bing.com", 0xFF008373),
    Shortcut(" brave".trim(), "https://search.brave.com", 0xFFFB542B)
)

/** Firefox-style start page: search, shortcuts, recents, bookmarks. */
@Composable
fun HomeScreen(
    modifier: Modifier = Modifier,
    vm: BrowserViewModel,
    onNavigate: (String) -> Unit
) {
    val history by vm.history.collectAsState()
    val bookmarks by vm.bookmarks.collectAsState()
    var query by remember { mutableStateOf("") }
    val focus = LocalFocusManager.current

    fun go(raw: String) {
        val url = vm.resolveInput(raw)
        if (url.isNotEmpty()) {
            focus.clearFocus()
            onNavigate(url)
        }
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        item {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                Spacer(Modifier.height(24.dp))
                Text(
                    "LightBrowser",
                    fontSize = 30.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    textAlign = TextAlign.Center
                )
                Text(
                    "light • fast • yours",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(16.dp))
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("Search or enter address") },
                    leadingIcon = { Icon(Icons.Filled.Search, null) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { go(query) }),
                    shape = MaterialTheme.shapes.extraLarge
                )
            }
        }
        item {
            Text("Shortcuts", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(8.dp))
            LazyVerticalGrid(
                columns = GridCells.Fixed(4),
                modifier = Modifier.fillMaxWidth().height(190.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(SHORTCUTS) { s ->
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.clickable { go(s.url) }
                    ) {
                        Box(
                            modifier = Modifier
                                .size(52.dp)
                                .clip(RoundedCornerShape(16.dp))
                                .background(androidx.compose.ui.graphics.Color(s.tint).copy(alpha = 0.15f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                s.name.trim().take(1).uppercase(),
                                fontWeight = FontWeight.Bold,
                                fontSize = 20.sp,
                                color = androidx.compose.ui.graphics.Color(s.tint)
                            )
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(s.name.trim(), style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
        if (history.isNotEmpty()) {
            item { Text("Recent", style = MaterialTheme.typography.titleSmall) }
            items(history.take(6), key = { it.url + it.time }) { h ->
                Card(
                    onClick = { onNavigate(h.url) },
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    ListItem(
                        headlineContent = { Text(h.title.ifBlank { h.url }, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        supportingContent = { Text(h.url, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        leadingContent = { Icon(Icons.Filled.History, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                    )
                }
            }
        }
        if (bookmarks.isNotEmpty()) {
            item { Text("Bookmarks", style = MaterialTheme.typography.titleSmall) }
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(bookmarks.take(10), key = { it.url }) { b ->
                        Card(
                            onClick = { onNavigate(b.url) },
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            Row(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Filled.Bookmark, null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(b.title.ifBlank { b.url }, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelLarge)
                            }
                        }
                    }
                }
            }
        }
    }
}
