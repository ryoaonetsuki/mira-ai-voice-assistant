package io.github.ryoaonetsuki.mira.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.ryoaonetsuki.mira.ui.components.MiraCard
import io.github.ryoaonetsuki.mira.ui.theme.LocalMiraPalette

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OptionalSettingsScreen(
    onBack: () -> Unit
) {
    val palette = LocalMiraPalette.current

    var selectedImageProvider by remember { mutableStateOf("Pollinations (Free)") }
    var selectedSearchEngine by remember { mutableStateOf("DuckDuckGo (Free)") }
    var mapsApiKey by remember { mutableStateOf("") }
    var searchApiKey by remember { mutableStateOf("") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Optional Integrations", fontWeight = FontWeight.Bold, color = palette.textPrimary) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Rounded.ArrowBack, contentDescription = "Back", tint = palette.textPrimary)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = palette.background)
            )
        },
        containerColor = palette.background
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                Text("Image Studio Provider", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = palette.primary)
            }

            item {
                MiraCard(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        val providers = listOf("Pollinations (Free)", "Gemini BYOK")
                        for (p in providers) {
                            FilterChip(
                                selected = selectedImageProvider == p,
                                onClick = { selectedImageProvider = p },
                                label = { Text(p) },
                                colors = FilterChipDefaults.filterChipColors(selectedContainerColor = palette.primary, selectedLabelColor = androidx.compose.ui.graphics.Color.Black)
                            )
                        }
                    }
                }
            }

            item {
                Text("Web Search Engine", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = palette.primary)
            }

            item {
                MiraCard(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        val engines = listOf("DuckDuckGo (Free)", "Tavily", "Brave")
                        for (e in engines) {
                            FilterChip(
                                selected = selectedSearchEngine == e,
                                onClick = { selectedSearchEngine = e },
                                label = { Text(e) },
                                colors = FilterChipDefaults.filterChipColors(selectedContainerColor = palette.primary, selectedLabelColor = androidx.compose.ui.graphics.Color.Black)
                            )
                        }
                    }
                    if (selectedSearchEngine != "DuckDuckGo (Free)") {
                        Spacer(modifier = Modifier.height(10.dp))
                        OutlinedTextField(
                            value = searchApiKey,
                            onValueChange = { searchApiKey = it },
                            label = { Text("$selectedSearchEngine API Key") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }

            item {
                Text("Google Maps & Places API", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = palette.primary)
            }

            item {
                MiraCard(modifier = Modifier.fillMaxWidth()) {
                    OutlinedTextField(
                        value = mapsApiKey,
                        onValueChange = { mapsApiKey = it },
                        label = { Text("Maps API Key (Optional)") },
                        placeholder = { Text("AIza...") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    }
}
