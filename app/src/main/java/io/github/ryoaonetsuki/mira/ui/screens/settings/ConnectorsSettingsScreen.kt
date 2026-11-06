package io.github.ryoaonetsuki.mira.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class ConnectorConfig(
    val id: String,
    val name: String,
    val hint: String,
    val description: String,
    val whatMiraCanDo: String,
    val setupSteps: String,
    val requiresToken: Boolean = true
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
        ConnectorConfig(
            id = "github",
            name = "GitHub",
            hint = "Personal Access Token (ghp_...)",
            description = "Read repos, view commits, and manage repositories",
            whatMiraCanDo = "List your top repositories with private/public visibility",
            setupSteps = "Create a Personal Access Token (classic) on GitHub with 'repo' scope at github.com/settings/tokens, then paste it here."
        ),
        ConnectorConfig(
            id = "gitlab",
            name = "GitLab",
            hint = "Personal Access Token (glpat-...)",
            description = "Access projects and code repositories on GitLab",
            whatMiraCanDo = "List your GitLab projects with visibility status",
            setupSteps = "In GitLab User Settings -> Access Tokens, create a personal access token with 'read_api' scope and paste it here."
        ),
        ConnectorConfig(
            id = "todoist",
            name = "Todoist",
            hint = "API Token",
            description = "Sync tasks, daily habits, and reminders",
            whatMiraCanDo = "List pending tasks and create new tasks directly",
            setupSteps = "In Todoist, go to Settings -> Integrations -> Developer and copy your API token."
        ),
        ConnectorConfig(
            id = "notion",
            name = "Notion",
            hint = "Internal Integration Secret (secret_...)",
            description = "Search workspace notes and databases",
            whatMiraCanDo = "Search pages and databases across your Notion workspace",
            setupSteps = "Create an internal integration at notion.so/my-integrations and share your workspace pages with that integration."
        ),
        ConnectorConfig(
            id = "linear",
            name = "Linear",
            hint = "Personal API Key (lin_api_...)",
            description = "Track engineering issues and projects",
            whatMiraCanDo = "List issues assigned to you with their status",
            setupSteps = "In Linear, go to Settings -> Account -> Security & Access -> Personal API Keys and paste your key."
        ),
        ConnectorConfig(
            id = "vercel",
            name = "Vercel",
            hint = "Personal Access Token",
            description = "Monitor deployments and project statuses",
            whatMiraCanDo = "List active projects and deployment statuses",
            setupSteps = "In Vercel Account Settings -> Tokens, generate a Personal Access Token and paste it here."
        ),
        ConnectorConfig(
            id = "drive",
            name = "Google Drive",
            hint = "OAuth Access Token (ya29...)",
            description = "Read documents and inspect stored files",
            whatMiraCanDo = "List recent files and document types from your Drive",
            setupSteps = "Open Google OAuth Playground (https://developers.google.com/oauthplayground). Under Drive API v3, select 'https://www.googleapis.com/auth/drive.readonly'. Authorize and exchange code for tokens, then paste the Access Token here. (Note: expires in ~1 hour; re-paste when needed)."
        ),
        ConnectorConfig(
            id = "youtube",
            name = "YouTube",
            hint = "YouTube Data API Key",
            description = "Search video content, channels, and playlists",
            whatMiraCanDo = "Search YouTube videos with channel and title information",
            setupSteps = "In Google Cloud Console, enable 'YouTube Data API v3', create an API key under Credentials, and paste it here."
        ),
        ConnectorConfig(
            id = "telegram",
            name = "Telegram",
            hint = "BotFather Bot Token (123456:ABC-DEF...)",
            description = "Send instant messages and alerts via your bot",
            whatMiraCanDo = "Send messages and notifications to your detected Telegram chat",
            setupSteps = "Create a bot using @BotFather on Telegram, paste token here, send a message to your bot in Telegram, then tap 'Detect chat'."
        ),
        ConnectorConfig(
            id = "maps",
            name = "Google Maps / Places",
            hint = "Places API Key",
            description = "Search locations, venues, and addresses",
            whatMiraCanDo = "Find places and lookup formatted addresses",
            setupSteps = "In Google Cloud Console, enable 'Places API', create an API key under Credentials, and paste it here."
        ),
        ConnectorConfig(
            id = "yahoo_finance",
            name = "Yahoo Finance",
            hint = "No key needed",
            description = "Live stock prices and market quotes",
            whatMiraCanDo = "Get real-time quotes, prices, and daily ranges for ticker symbols",
            setupSteps = "No API key needed. Public financial data endpoint.",
            requiresToken = false
        ),
        ConnectorConfig(
            id = "open_meteo",
            name = "Open-Meteo",
            hint = "No key needed",
            description = "Global weather forecasts and conditions",
            whatMiraCanDo = "Report current temperature, weather conditions, wind, and humidity",
            setupSteps = "No API key needed. Free open-source weather API.",
            requiresToken = false
        ),
        ConnectorConfig(
            id = "gmail",
            name = "Gmail",
            hint = "OAuth Access Token (ya29...)",
            description = "Read inbox messages and send emails",
            whatMiraCanDo = "Check recent inbox emails and send new messages",
            setupSteps = "Open Google OAuth Playground (https://developers.google.com/oauthplayground). Select scope 'https://mail.google.com/' (read + send). Authorize and exchange code for tokens, then paste the Access Token here. (Note: expires in ~1 hour; re-paste when needed)."
        )
    )

    var searchQuery by remember { mutableStateOf("") }
    var activeDialogConnector by remember { mutableStateOf<ConnectorConfig?>(null) }
    var connectorToDisconnect by remember { mutableStateOf<ConnectorConfig?>(null) }
    var tokenInput by remember { mutableStateOf("") }
    var isVerifying by remember { mutableStateOf(false) }
    var verificationMsg by remember { mutableStateOf<String?>(null) }
    var isVerifiedSuccess by remember { mutableStateOf(false) }

    var isDetectingChat by remember { mutableStateOf(false) }
    var telegramDetectMsg by remember { mutableStateOf<String?>(null) }

    // Map of connectorId -> status
    val statusMap = remember { mutableStateMapOf<String, String>() }
    var telegramChatId by remember { mutableStateOf(securePrefs.getTelegramChatId()) }

    LaunchedEffect(Unit) {
        for (c in connectors) {
            val token = securePrefs.getConnectorToken(c.id)
            if (!token.isNullOrBlank()) {
                statusMap[c.id] = "Connected"
            }
        }
    }

    val filteredConnectors = remember(searchQuery, connectors) {
        if (searchQuery.isBlank()) connectors
        else connectors.filter {
            it.name.contains(searchQuery, ignoreCase = true) ||
                    it.description.contains(searchQuery, ignoreCase = true) ||
                    it.whatMiraCanDo.contains(searchQuery, ignoreCase = true)
        }
    }

    val connectedList = filteredConnectors.filter { statusMap[it.id] != null }
    val availableList = filteredConnectors.filter { statusMap[it.id] == null }

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

            Spacer(modifier = Modifier.height(10.dp))

            // Search Bar
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text("Search connectors") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(12.dp))

            if (telegramDetectMsg != null) {
                Surface(
                    color = palette.surface,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = telegramDetectMsg!!,
                            fontSize = 12.sp,
                            color = palette.primary,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = { telegramDetectMsg = null }) {
                            Text("OK", fontSize = 12.sp)
                        }
                    }
                }
            }

            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Connected Section
                if (connectedList.isNotEmpty()) {
                    item {
                        Text(
                            text = "Connected (${connectedList.size})",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = palette.primary,
                            modifier = Modifier.padding(vertical = 4.dp)
                        )
                    }

                    items(connectedList) { item ->
                        val lastUsed = securePrefs.getConnectorLastUsed(item.id)
                        val lastUsedStr = if (lastUsed <= 0L) {
                            "Never"
                        } else {
                            SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()).format(Date(lastUsed))
                        }

                        MiraCard(modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.fillMaxWidth()) {
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
                                    }

                                    OutlinedButton(
                                        onClick = { connectorToDisconnect = item },
                                        shape = RoundedCornerShape(10.dp),
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                                    ) {
                                        Text("Disconnect", fontSize = 11.sp, color = Color(0xFFEF4444))
                                    }
                                }

                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "Connected · last used $lastUsedStr",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFF10B981)
                                )

                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "WHAT MIRA CAN DO: ${item.whatMiraCanDo}",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = palette.primary
                                )

                                // Telegram "Detect chat" row
                                if (item.id == "telegram") {
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Text(
                                            text = if (!telegramChatId.isNullOrBlank()) "Chat: $telegramChatId" else "Chat: Not detected yet",
                                            fontSize = 11.sp,
                                            color = palette.textSecondary,
                                            modifier = Modifier.weight(1f)
                                        )
                                        Button(
                                            onClick = {
                                                val token = securePrefs.getConnectorToken("telegram") ?: ""
                                                if (token.isNotBlank()) {
                                                    isDetectingChat = true
                                                    scope.launch {
                                                        val res = client.detectTelegramChat(token)
                                                        isDetectingChat = false
                                                        if (res.isSuccess) {
                                                            val (chatId, chatName) = res.getOrThrow()
                                                            securePrefs.setTelegramChatId(chatId)
                                                            telegramChatId = chatId
                                                            telegramDetectMsg = "Chat detected: $chatId ($chatName)"
                                                        } else {
                                                            telegramDetectMsg = res.exceptionOrNull()?.message ?: "Chat detection failed"
                                                        }
                                                    }
                                                }
                                            },
                                            shape = RoundedCornerShape(8.dp),
                                            enabled = !isDetectingChat,
                                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                                        ) {
                                            Text(
                                                text = if (isDetectingChat) "Detecting..." else "Detect chat",
                                                fontSize = 11.sp
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // Available Section
                if (availableList.isNotEmpty()) {
                    item {
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "Available (${availableList.size})",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = palette.textSecondary,
                            modifier = Modifier.padding(vertical = 4.dp)
                        )
                    }

                    items(availableList) { item ->
                        MiraCard(modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.fillMaxWidth()) {
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
                                    }

                                    Button(
                                        onClick = {
                                            if (!item.requiresToken) {
                                                // Real no-auth live verification directly
                                                scope.launch {
                                                    val res = if (item.id == "yahoo_finance") {
                                                        client.verifyYahooFinance()
                                                    } else {
                                                        client.verifyOpenMeteo()
                                                    }
                                                    if (res.isSuccess) {
                                                        securePrefs.setConnectorToken(item.id, "connected")
                                                        statusMap[item.id] = "Connected"
                                                    } else {
                                                        telegramDetectMsg = res.exceptionOrNull()?.message ?: "Verification failed"
                                                    }
                                                }
                                            } else {
                                                activeDialogConnector = item
                                                tokenInput = securePrefs.getConnectorToken(item.id) ?: ""
                                                verificationMsg = null
                                            }
                                        },
                                        shape = RoundedCornerShape(10.dp),
                                        colors = ButtonDefaults.buttonColors(containerColor = palette.primary),
                                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                                    ) {
                                        Text("Connect", fontSize = 12.sp, color = Color.Black)
                                    }
                                }

                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = if (!item.requiresToken) "○ No key needed" else "○ Not configured",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = palette.textSecondary
                                )

                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "WHAT MIRA CAN DO: ${item.whatMiraCanDo}",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = palette.textSecondary
                                )
                            }
                        }
                    }
                }

                // Privacy Note at the bottom
                item {
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = "Connector credentials stay on this phone, encrypted. Mira never sends them anywhere, and disconnecting deletes them.",
                        fontSize = 12.sp,
                        color = palette.textSecondary,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp)
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                }
            }
        }

        // Token Input & Real Live Verification Dialog
        if (activeDialogConnector != null) {
            val c = activeDialogConnector!!
            AlertDialog(
                onDismissRequest = { activeDialogConnector = null },
                title = { Text("Connect ${c.name}") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(c.description, fontSize = 13.sp, color = palette.textPrimary)
                        Text(
                            text = c.setupSteps,
                            fontSize = 12.sp,
                            color = palette.textSecondary
                        )

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
                                        "gitlab" -> client.verifyGitLab(token)
                                        "telegram" -> client.verifyTelegram(token)
                                        "notion" -> client.verifyNotion(token)
                                        "todoist" -> client.verifyTodoist(token)
                                        "linear" -> client.verifyLinear(token)
                                        "vercel" -> client.verifyVercel(token)
                                        "drive" -> client.verifyGoogleDrive(token)
                                        "youtube" -> client.verifyYouTube(token)
                                        "maps" -> client.verifyGoogleMaps(token)
                                        "yahoo_finance" -> client.verifyYahooFinance()
                                        "open_meteo" -> client.verifyOpenMeteo()
                                        "gmail" -> client.verifyGmail(token)
                                        else -> Result.failure(Exception("Unknown connector ${c.name}"))
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

        // Disconnect Confirmation Dialog
        if (connectorToDisconnect != null) {
            val c = connectorToDisconnect!!
            AlertDialog(
                onDismissRequest = { connectorToDisconnect = null },
                title = { Text("Disconnect ${c.name}?") },
                text = {
                    Text(
                        text = "Removes the credential from this phone. The token stays alive in your ${c.name} account until you revoke it there.",
                        fontSize = 13.sp,
                        color = palette.textSecondary
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            securePrefs.removeConnectorToken(c.id)
                            statusMap.remove(c.id)
                            if (c.id == "telegram") {
                                telegramChatId = null
                            }
                            connectorToDisconnect = null
                        }
                    ) {
                        Text("Disconnect", color = Color(0xFFEF4444), fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { connectorToDisconnect = null }) {
                        Text("Cancel")
                    }
                },
                containerColor = palette.surface,
                titleContentColor = palette.textPrimary,
                textContentColor = palette.textSecondary
            )
        }
    }
}
