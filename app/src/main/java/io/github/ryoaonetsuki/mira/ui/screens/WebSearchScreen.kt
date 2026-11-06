package io.github.ryoaonetsuki.mira.ui.screens

import io.github.ryoaonetsuki.mira.ui.icons.Lucide
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.ryoaonetsuki.mira.network.FreeWebSearch
import io.github.ryoaonetsuki.mira.ui.components.MiraCard
import io.github.ryoaonetsuki.mira.ui.theme.LocalMiraPalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class SearchResult(val title: String, val snippet: String, val url: String)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WebSearchScreen(
    onBack: () -> Unit,
    onOpenUrl: (String) -> Unit
) {
    val context = LocalContext.current
    val palette = LocalMiraPalette.current
    val scope = rememberCoroutineScope()

    var searchQuery by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<SearchResult>>(emptyList()) }
    var isSearching by remember { mutableStateOf(false) }

    fun performSearch(query: String) {
        if (query.isBlank()) return
        isSearching = true
        scope.launch {
            val list = mutableListOf<SearchResult>()
            try {
                // Real live results via the app's own built-in search (live Google
                // results via SearXNG, then DuckDuckGo fallbacks) — no key needed.
                val webResults = withContext(Dispatchers.IO) {
                    FreeWebSearch.search(query, maxResults = 10)
                }
                webResults.forEach { r ->
                    list.add(SearchResult(r.title, r.snippet, r.url))
                }

                // If the built-in search has no items, add direct web search result
                if (list.isEmpty()) {
                    list.add(
                        SearchResult(
                            title = "DuckDuckGo Web Search: $query",
                            snippet = "Tap to view full web search results on DuckDuckGo.",
                            url = "https://duckduckgo.com/?q=${Uri.encode(query)}"
                        )
                    )
                }
            } catch (e: Exception) {
                list.add(
                    SearchResult(
                        title = "Search on DuckDuckGo",
                        snippet = "Direct web search fallback for '$query'.",
                        url = "https://duckduckgo.com/?q=${Uri.encode(query)}"
                    )
                )
            } finally {
                results = list
                isSearching = false
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Web Search", fontWeight = FontWeight.Bold, color = palette.textPrimary) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Lucide.arrowLeft, contentDescription = "Back", tint = palette.textPrimary)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = palette.background)
            )
        },
        containerColor = palette.background
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
        ) {
            // Search Input Row
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text("Search the web…") },
                    shape = RoundedCornerShape(14.dp),
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = palette.surface,
                        unfocusedContainerColor = palette.surface,
                        focusedTextColor = palette.textPrimary,
                        unfocusedTextColor = palette.textPrimary,
                        focusedBorderColor = palette.primary,
                        unfocusedBorderColor = palette.surfaceVariant
                    ),
                    modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.width(8.dp))
                IconButton(
                    onClick = { performSearch(searchQuery) },
                    modifier = Modifier
                        .size(48.dp)
                        .background(palette.primary, RoundedCornerShape(14.dp))
                ) {
                    Icon(Lucide.search, contentDescription = "Search", tint = androidx.compose.ui.graphics.Color.Black)
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            if (isSearching) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = palette.primary)
                Spacer(modifier = Modifier.height(10.dp))
            }

            if (results.isEmpty() && !isSearching) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.weight(1f).fillMaxWidth()
                ) {
                    Text("Type a query to search privately.", color = palette.textSecondary)
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(results) { item ->
                        MiraCard(
                            modifier = Modifier.fillMaxWidth(),
                            onClick = {
                                if (item.url.isNotBlank()) {
                                    onOpenUrl(item.url)
                                }
                            }
                        ) {
                            Text(
                                text = item.title,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold,
                                color = palette.primary
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = item.snippet,
                                fontSize = 13.sp,
                                color = palette.textPrimary,
                                lineHeight = 18.sp
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = item.url,
                                fontSize = 11.sp,
                                color = palette.textSecondary,
                                maxLines = 1
                            )
                        }
                    }
                }
            }
        }
    }
}
