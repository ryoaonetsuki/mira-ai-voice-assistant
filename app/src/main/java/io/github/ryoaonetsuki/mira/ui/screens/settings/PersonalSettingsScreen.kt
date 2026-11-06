package io.github.ryoaonetsuki.mira.ui.screens.settings

import io.github.ryoaonetsuki.mira.ui.icons.Lucide
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
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
import io.github.ryoaonetsuki.mira.data.preferences.UserPreferences
import io.github.ryoaonetsuki.mira.network.GeminiRestClient
import io.github.ryoaonetsuki.mira.network.KeyValidationOutcome
import io.github.ryoaonetsuki.mira.network.normalizeApiKey
import io.github.ryoaonetsuki.mira.ui.components.MiraButton
import io.github.ryoaonetsuki.mira.ui.components.MiraCard
import io.github.ryoaonetsuki.mira.ui.theme.LocalMiraPalette
import kotlinx.coroutines.launch

private enum class PersonalKeyValidationState {
    IDLE,
    CHECKING,
    VALID,
    TRANSIENT,
    INVALID
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PersonalSettingsScreen(
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val palette = LocalMiraPalette.current
    val scope = rememberCoroutineScope()
    val securePrefs = remember { SecurePreferences(context) }
    val userPrefs = remember { UserPreferences(context) }
    val restClient = remember { GeminiRestClient() }
    val resolver = remember { io.github.ryoaonetsuki.mira.backend.ApiKeyResolver(context) }

    val savedName by userPrefs.userNameFlow.collectAsState(initial = "Alex")
    val savedPhone by userPrefs.userPhoneFlow.collectAsState(initial = "")
    val savedMusicApp by userPrefs.musicAppFlow.collectAsState(initial = "Spotify")
    val savedSong by userPrefs.favoriteSongFlow.collectAsState(initial = "Starboy")

    var nameInput by remember { mutableStateOf(savedName) }
    var phoneInput by remember { mutableStateOf(savedPhone) }
    var apiKeyInput by remember { mutableStateOf(securePrefs.getGeminiApiKey() ?: "") }
    var favoriteSongInput by remember { mutableStateOf(savedSong) }

    var isTestingKey by remember { mutableStateOf(false) }
    var testResultMsg by remember { mutableStateOf<String?>(null) }
    var validationState by remember { mutableStateOf(PersonalKeyValidationState.IDLE) }
    var activeModeText by remember { mutableStateOf("Checking...") }

    fun refreshStatus() {
        scope.launch {
            val source = resolver.resolveKey()
            activeModeText = when (source) {
                is io.github.ryoaonetsuki.mira.backend.KeySource.UserKey -> "Using your own key"
                is io.github.ryoaonetsuki.mira.backend.KeySource.ServerKey -> "Using Mira server key"
                is io.github.ryoaonetsuki.mira.backend.KeySource.NoKey -> "No key set"
            }
        }
    }

    LaunchedEffect(Unit) {
        refreshStatus()
    }

    LaunchedEffect(savedName, savedPhone, savedSong) {
        nameInput = savedName
        phoneInput = savedPhone
        favoriteSongInput = savedSong
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Personal Profile", fontWeight = FontWeight.Bold, color = palette.textPrimary) },
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
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                Text("Your Details", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = palette.primary)
            }

            // Name
            item {
                OutlinedTextField(
                    value = nameInput,
                    onValueChange = {
                        nameInput = it
                        scope.launch { userPrefs.setUserName(it.trim()) }
                    },
                    label = { Text("Your Name") },
                    placeholder = { Text("e.g. Alex") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            // Phone
            item {
                OutlinedTextField(
                    value = phoneInput,
                    onValueChange = {
                        phoneInput = it
                        scope.launch { userPrefs.setUserPhone(it.trim()) }
                    },
                    label = { Text("Phone Number (Optional)") },
                    placeholder = { Text("+1 ...") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            item {
                Spacer(modifier = Modifier.height(4.dp))
                Text("API Key", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = palette.primary)
            }

            // API Key Card
            item {
                MiraCard(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = "Current Status: $activeModeText",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = palette.primary
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    OutlinedTextField(
                        value = apiKeyInput,
                        onValueChange = {
                            apiKeyInput = it
                            testResultMsg = null
                            validationState = PersonalKeyValidationState.IDLE
                        },
                        label = { Text("API Key") },
                        placeholder = { Text("Paste your API key — new keys start with AQ.") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    // Hint line
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "New keys start with AQ. — that is correct, paste the whole key.",
                        fontSize = 12.sp,
                        color = palette.textSecondary,
                        lineHeight = 17.sp
                    )

                    if (testResultMsg != null) {
                        Spacer(modifier = Modifier.height(6.dp))
                        val msgColor = when (validationState) {
                            PersonalKeyValidationState.VALID -> Color(0xFF10B981)
                            PersonalKeyValidationState.TRANSIENT -> Color(0xFFF59E0B)
                            PersonalKeyValidationState.INVALID -> Color(0xFFEF4444)
                            PersonalKeyValidationState.CHECKING, PersonalKeyValidationState.IDLE -> palette.textSecondary
                        }
                        Text(
                            text = testResultMsg!!,
                            fontSize = 12.sp,
                            color = msgColor
                        )
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    MiraButton(
                        text = "Test & Save Key",
                        isLoading = isTestingKey,
                        onClick = {
                            val normalized = normalizeApiKey(apiKeyInput)
                            if (normalized.isNotBlank()) {
                                if (!securePrefs.isSecureStorageAvailable()) {
                                    validationState = PersonalKeyValidationState.INVALID
                                    testResultMsg = "Secure storage unavailable on this device — key was not saved"
                                    return@MiraButton
                                }
                                isTestingKey = true
                                validationState = PersonalKeyValidationState.CHECKING
                                testResultMsg = "Validating key with Google... (retrying if Google is busy)"
                                scope.launch {
                                    val outcome = restClient.validateApiKeyAndChat(normalized)
                                    isTestingKey = false
                                    when (outcome) {
                                        is KeyValidationOutcome.Valid -> {
                                            val saved = securePrefs.setGeminiApiKey(normalized)
                                            if (saved) {
                                                validationState = PersonalKeyValidationState.VALID
                                                testResultMsg = "Valid API Key! Stored encrypted on device."
                                                refreshStatus()
                                            } else {
                                                validationState = PersonalKeyValidationState.INVALID
                                                testResultMsg = "Secure storage unavailable on this device — key was not saved"
                                            }
                                        }
                                        is KeyValidationOutcome.TransientServerBusy -> {
                                            val saved = securePrefs.setGeminiApiKey(normalized)
                                            if (saved) {
                                                validationState = PersonalKeyValidationState.TRANSIENT
                                                testResultMsg = "Google's servers are busy right now — key saved, try again in a bit"
                                                refreshStatus()
                                            } else {
                                                validationState = PersonalKeyValidationState.INVALID
                                                testResultMsg = "Google's servers are busy right now (HTTP 503 — high demand). Your key was NOT rejected — please try again in a bit."
                                            }
                                        }
                                        is KeyValidationOutcome.InvalidKey -> {
                                            validationState = PersonalKeyValidationState.INVALID
                                            testResultMsg = outcome.userFacingMessage
                                        }
                                    }
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    )

                    if (apiKeyInput.isNotBlank() || securePrefs.getGeminiApiKey() != null) {
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedButton(
                            onClick = {
                                securePrefs.clearGeminiApiKey()
                                apiKeyInput = ""
                                testResultMsg = "Cleared personal key. Falling back to server key."
                                validationState = PersonalKeyValidationState.VALID
                                refreshStatus()
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Use server key instead")
                        }
                    }
                }
            }

            item {
                Spacer(modifier = Modifier.height(4.dp))
                Text("Music Preferences", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = palette.primary)
            }

            // Music App & Song
            item {
                MiraCard(modifier = Modifier.fillMaxWidth()) {
                    Text("Preferred Music App", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = palette.textPrimary)
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        val apps = listOf("Spotify", "YouTube Music", "YouTube")
                        for (app in apps) {
                            FilterChip(
                                selected = savedMusicApp == app,
                                onClick = { scope.launch { userPrefs.setMusicApp(app) } },
                                label = { Text(app) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = palette.primary,
                                    selectedLabelColor = Color.Black
                                )
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    OutlinedTextField(
                        value = favoriteSongInput,
                        onValueChange = {
                            favoriteSongInput = it
                            scope.launch { userPrefs.setFavoriteSong(it.trim()) }
                        },
                        label = { Text("Favorite Song / Track") },
                        placeholder = { Text("e.g. Starboy") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    }
}
