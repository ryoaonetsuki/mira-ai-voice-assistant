package io.github.ryoaonetsuki.mira.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.ryoaonetsuki.mira.data.preferences.SecurePreferences
import io.github.ryoaonetsuki.mira.network.ConnectorClient
import io.github.ryoaonetsuki.mira.ui.components.MiraButton
import io.github.ryoaonetsuki.mira.ui.components.MiraCard
import io.github.ryoaonetsuki.mira.ui.theme.LocalMiraPalette
import kotlinx.coroutines.launch

data class ConnectorConfig(
    val id: String,
    val name: String,
    val hint: String,
    val description: String
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectorsSettingsScreen(
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val palette = LocalMiraPalette.current
    val scope = rememberCoroutineScope()
    val securePrefs = remember { SecurePreferences(context) }
    val client = remember { ConnectorClient() }

    val connectors = listOf(
        ConnectorConfig("github", "GitHub", "Personal Access Token (ghp_...)", "Read repos, create issues, and manage gists"),
        ConnectorConfig("telegram", "Telegram", "BotFather Bot Token (123456:ABC-DEF...)", "Send alerts and interact via your private bot"),
        ConnectorConfig("notion", "Notion", "Internal Integration Secret (secret_...)", "Search notes, databases, and insert thoughts"),
        ConnectorConfig("todoist", "Todoist", "API Token (from integrations page)", "Sync tasks, daily habits, and reminders"),
        ConnectorConfig("linear", "Linear", "Personal API Key (lin_api_...)", "Track engineering issues and projects"),
        ConnectorConfig("spotify", "Spotify", "Spotify Client Access Token", "Queue and play songs directly from voice prompts"),
        ConnectorConfig("drive", "Google Drive", "OAuth Token / Service Account Key", "Read documents and upload export backups"),
        ConnectorConfig("youtube", "YouTube", "YouTube Data API Key", "Search videos, channels, and playlists")
    )

    var activeDialogConnector by remember { mutableStateOf<ConnectorConfig?>(null) }
    var tokenInput by remember { mutableStateOf("") }
    var isVerifying by remember { mutableStateOf(false) }
    var verificationMsg by remember { mutableStateOf<String?>(null) }
    var isVerifiedSuccess by remember { mutableStateOf(false) }

    // Map of connectorId -> status
    val statusMap = remember { mutableStateMapOf<String, String>() }

    LaunchedEffect(Unit) {
        for (c in connectors) {
            val token = securePrefs.getConnectorToken(c.id)
            if (!token.isNullOrBlank()) {
                statusMap[c.id] = "Connected"
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Connectors", fontWeight = FontWeight.Bold, color = palette.textPrimary) },
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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
        ) {
            Text(
                text = "Connect external services. Credentials are encrypted on this device and tested directly against official APIs.",
                fontSize = 13.sp,
                color = palette.textSecondary
            )

            Spacer(modifier = Modifier.height(14.dp))

            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(connectors) { item ->
                    val isConnected = statusMap[item.id] != null

                    MiraCard(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = item.name,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = palette.textPrimary
                                )
                                Text(
                                    text = item.description,
                                    fontSize = 12.sp,
                                    color = palette.textSecondary
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = if (isConnected) "● Connected" else "○ Not configured",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (isConnected) Color(0xFF10B981) else palette.textSecondary
                                )
                            }

                            if (isConnected) {
                                OutlinedButton(
                                    onClick = {
                                        securePrefs.removeConnectorToken(item.id)
                                        statusMap.remove(item.id)
                                    },
                                    shape = RoundedCornerShape(10.dp),
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                                ) {
                                    Text("Disconnect", fontSize = 11.sp, color = Color(0xFFEF4444))
                                }
                            } else {
                                Button(
                                    onClick = {
                                        activeDialogConnector = item
                                        tokenInput = securePrefs.getConnectorToken(item.id) ?: ""
                                        verificationMsg = null
                                    },
                                    shape = RoundedCornerShape(10.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = palette.primary),
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                                ) {
                                    Text("Connect", fontSize = 12.sp, color = Color.Black)
                                }
                            }
                        }
                    }
                }
            }
        }

        // Token Input & Real Verification Dialog
        if (activeDialogConnector != null) {
            val c = activeDialogConnector!!
            AlertDialog(
                onDismissRequest = { activeDialogConnector = null },
                title = { Text("Connect ${c.name}") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(c.description, fontSize = 13.sp, color = palette.textSecondary)
                        OutlinedTextField(
                            value = tokenInput,
                            onValueChange = {
                                tokenInput = it
                                verificationMsg = null
                            },
                            label = { Text("API Token / Key") },
                            placeholder = { Text(c.hint) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        if (verificationMsg != null) {
                            Text(
                                text = verificationMsg!!,
                                fontSize = 12.sp,
                                color = if (isVerifiedSuccess) Color(0xFF10B981) else Color(0xFFEF4444)
                            )
                        }
                    }
                },
                confirmButton = {
                    MiraButton(
                        text = "Verify & Save",
                        isLoading = isVerifying,
                        onClick = {
                            if (tokenInput.isNotBlank()) {
                                isVerifying = true
                                verificationMsg = "Validating token with ${c.name}..."
                                scope.launch {
                                    val token = tokenInput.trim()
                                    val result = when (c.id) {
                                        "github" -> client.verifyGitHub(token)
                                        "telegram" -> client.verifyTelegram(token)
                                        "notion" -> client.verifyNotion(token)
                                        "todoist" -> client.verifyTodoist(token)
                                        "linear" -> client.verifyLinear(token)
                                        else -> client.verifyGeneric(c.name, token)
                                    }
                                    isVerifying = false
                                    if (result.isSuccess) {
                                        isVerifiedSuccess = true
                                        verificationMsg = result.getOrThrow()
                                        securePrefs.setConnectorToken(c.id, token)
                                        statusMap[c.id] = "Connected"
                                        activeDialogConnector = null
                                    } else {
                                        isVerifiedSuccess = false
                                        verificationMsg = result.exceptionOrNull()?.message ?: "Verification failed"
                                    }
                                }
                            }
                        }
                    )
                },
                dismissButton = {
                    TextButton(onClick = { activeDialogConnector = null }) { Text("Cancel") }
                },
                containerColor = palette.surface,
                titleContentColor = palette.textPrimary,
                textContentColor = palette.textSecondary
            )
        }
    }
}
