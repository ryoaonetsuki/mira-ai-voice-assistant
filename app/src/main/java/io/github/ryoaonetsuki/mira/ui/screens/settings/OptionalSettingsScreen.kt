package io.github.ryoaonetsuki.mira.ui.screens.settings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
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
    val context = androidx.compose.ui.platform.LocalContext.current
    val palette = LocalMiraPalette.current
    val securePrefs = remember { io.github.ryoaonetsuki.mira.data.preferences.SecurePreferences(context) }

    var mapsApiKey by remember { mutableStateOf(securePrefs.getConnectorToken("maps") ?: "") }

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
                Text("Google Maps & Places API", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = palette.primary)
            }

            item {
                MiraCard(modifier = Modifier.fillMaxWidth()) {
                    OutlinedTextField(
                        value = mapsApiKey,
                        onValueChange = {
                            mapsApiKey = it
                            securePrefs.setConnectorToken("maps", it)
                        },
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
