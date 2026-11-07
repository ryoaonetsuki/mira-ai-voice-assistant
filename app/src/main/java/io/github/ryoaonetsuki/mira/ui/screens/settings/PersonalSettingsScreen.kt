package io.github.ryoaonetsuki.mira.ui.screens.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.ryoaonetsuki.mira.R
import io.github.ryoaonetsuki.mira.data.preferences.SecurePreferences
import io.github.ryoaonetsuki.mira.data.preferences.UserPreferences
import io.github.ryoaonetsuki.mira.network.GeminiRestClient
import io.github.ryoaonetsuki.mira.network.KeyValidationOutcome
import io.github.ryoaonetsuki.mira.network.normalizeApiKey
import io.github.ryoaonetsuki.mira.ui.components.MiraButton
import io.github.ryoaonetsuki.mira.ui.components.MiraCard
import io.github.ryoaonetsuki.mira.ui.theme.LocalMiraPalette
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

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

    val savedName by userPrefs.userNameFlow.collectAsState(initial = "User")
    val savedMusicApp by userPrefs.musicAppFlow.collectAsState(initial = "Spotify")
    val savedSong by userPrefs.favoriteSongFlow.collectAsState(initial = "Starboy")

    var nameInput by remember { mutableStateOf(savedName) }
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

    LaunchedEffect(savedName, savedSong) {
        nameInput = savedName
        favoriteSongInput = savedSong
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Personal Profile",
                        fontSize = 19.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = palette.textPrimary
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_fluent_arrow_left_24_regular),
                            contentDescription = "Back",
                            tint = palette.textPrimary
                        )
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
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "Your Identity",
                    fontSize = 13.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = palette.primary
                )

                Spacer(modifier = Modifier.height(6.dp))

                MiraCard(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(38.dp)
                                .clip(CircleShape)
                                .background(palette.primary.copy(alpha = 0.15f))
                        ) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_fluent_person_24_regular),
                                contentDescription = null,
                                tint = palette.primary,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Preferred Name",
                                fontSize = 15.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = palette.textPrimary
                            )
                            Text(
                                text = "How Mira addresses you in speech and chat",
                                fontSize = 12.sp,
                                color = palette.textSecondary
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    OutlinedTextField(
                        value = nameInput,
                        onValueChange = {
                            nameInput = it
                            scope.launch { userPrefs.setUserName(it.trim()) }
                        },
                        label = { Text("Your Name") },
                        placeholder = { Text("Enter your name") },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = palette.primary,
                            unfocusedBorderColor = palette.borderSubtle
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            // API Key Card
            item {
                Text(
                    text = "API Key Management",
                    fontSize = 13.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = palette.primary
                )

                Spacer(modifier = Modifier.height(6.dp))

                MiraCard(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(38.dp)
                                .clip(CircleShape)
                                .background(palette.secondary.copy(alpha = 0.15f))
                        ) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_fluent_key_24_regular),
                                contentDescription = null,
                                tint = palette.secondary,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Gemini Key Status",
                                fontSize = 15.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = palette.textPrimary
                            )
                            Text(
                                text = activeModeText,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                                color = palette.primary
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    OutlinedTextField(
                        value = apiKeyInput,
                        onValueChange = {
                            apiKeyInput = it
                            testResultMsg = null
                            validationState = PersonalKeyValidationState.IDLE
                        },
                        label = { Text("Gemini API Key") },
                        placeholder = { Text("Paste key starting with AQ...") },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = palette.primary,
                            unfocusedBorderColor = palette.borderSubtle
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "New keys from Google AI Studio start with AQ. — paste the whole key.",
                        fontSize = 11.5.sp,
                        color = palette.textSecondary,
                        lineHeight = 16.sp
                    )

                    if (testResultMsg != null) {
                        Spacer(modifier = Modifier.height(10.dp))
                        val msgColor = when (validationState) {
                            PersonalKeyValidationState.VALID -> Color(0xFF10B981)
                            PersonalKeyValidationState.TRANSIENT -> Color(0xFFF59E0B)
                            PersonalKeyValidationState.INVALID -> Color(0xFFEF4444)
                            PersonalKeyValidationState.CHECKING, PersonalKeyValidationState.IDLE -> palette.textSecondary
                        }
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = msgColor.copy(alpha = 0.12f),
                            border = BorderStroke(1.dp, msgColor.copy(alpha = 0.35f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = testResultMsg!!,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                                color = msgColor,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

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
                                testResultMsg = "Validating key with Google..."
                                scope.launch {
                                    val outcome = withTimeoutOrNull(25_000L) {
                                        restClient.validateApiKeyAndChat(normalized)
                                    } ?: KeyValidationOutcome.TransientServerBusy()
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
                        Spacer(modifier = Modifier.height(10.dp))
                        OutlinedButton(
                            onClick = {
                                securePrefs.clearGeminiApiKey()
                                apiKeyInput = ""
                                testResultMsg = "Cleared personal key. Falling back to server key."
                                validationState = PersonalKeyValidationState.VALID
                                refreshStatus()
                            },
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(1.dp, palette.borderSubtle),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Use server key instead", color = palette.textSecondary)
                        }
                    }
                }
            }

            // Music Preferences Card
            item {
                Text(
                    text = "Music & Media",
                    fontSize = 13.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = palette.primary
                )

                Spacer(modifier = Modifier.height(6.dp))

                MiraCard(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = "Preferred Music App",
                        fontSize = 14.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = palette.textPrimary
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        val apps = listOf("Spotify", "YouTube Music", "YouTube")
                        for (app in apps) {
                            val isSelected = savedMusicApp == app
                            FilterChip(
                                selected = isSelected,
                                onClick = { scope.launch { userPrefs.setMusicApp(app) } },
                                label = { Text(app, fontSize = 12.sp) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = palette.primary,
                                    selectedLabelColor = if (palette.isDark) palette.background else Color.White
                                ),
                                border = FilterChipDefaults.filterChipBorder(
                                    enabled = true,
                                    selected = isSelected,
                                    borderColor = palette.borderSubtle,
                                    selectedBorderColor = palette.primary
                                )
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    OutlinedTextField(
                        value = favoriteSongInput,
                        onValueChange = {
                            favoriteSongInput = it
                            scope.launch { userPrefs.setFavoriteSong(it.trim()) }
                        },
                        label = { Text("Favorite Song / Track") },
                        placeholder = { Text("e.g. Starboy") },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = palette.primary,
                            unfocusedBorderColor = palette.borderSubtle
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            item {
                Spacer(modifier = Modifier.height(20.dp))
            }
        }
    }
}
