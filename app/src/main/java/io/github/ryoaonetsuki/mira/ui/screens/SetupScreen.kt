package io.github.ryoaonetsuki.mira.ui.screens

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Accessibility
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.MarkChatUnread
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import io.github.ryoaonetsuki.mira.data.preferences.SecurePreferences
import io.github.ryoaonetsuki.mira.network.GeminiRestClient
import io.github.ryoaonetsuki.mira.network.KeyValidationOutcome
import io.github.ryoaonetsuki.mira.network.normalizeApiKey
import io.github.ryoaonetsuki.mira.service.MiraAccessibilityService
import io.github.ryoaonetsuki.mira.ui.components.MiraButton
import io.github.ryoaonetsuki.mira.ui.components.MiraCard
import io.github.ryoaonetsuki.mira.ui.navigation.Screen
import io.github.ryoaonetsuki.mira.ui.theme.LocalMiraPalette
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

private enum class SetupKeyStatus {
    IDLE,
    CHECKING,
    VALID,
    TRANSIENT,
    INVALID
}

@Composable
fun SetupScreen(
    startStep: Int = 1,
    onSetupComplete: () -> Unit
) {
    val context = LocalContext.current
    val palette = LocalMiraPalette.current
    val scope = rememberCoroutineScope()
    val securePrefs = remember { SecurePreferences(context) }
    val restClient = remember { GeminiRestClient() }
    val serverVoiceClient = remember { io.github.ryoaonetsuki.mira.network.ServerVoiceClient() }
    val resolver = remember { io.github.ryoaonetsuki.mira.backend.ApiKeyResolver(context) }

    var currentStep by remember { mutableIntStateOf(startStep) }
    var apiKeyInput by remember { mutableStateOf(securePrefs.getGeminiApiKey() ?: "") }
    var isKeyVisible by remember { mutableStateOf(false) }
    var isCheckingKey by remember { mutableStateOf(false) }
    var keyValidationMessage by remember { mutableStateOf<String?>(null) }
    var keyStatus by remember { mutableStateOf(SetupKeyStatus.IDLE) }
    var isServerKeyAvailable by remember { mutableStateOf(false) }

    var groqKeyInput by remember { mutableStateOf(securePrefs.getGroqApiKey() ?: "") }
    var isGroqKeyVisible by remember { mutableStateOf(false) }
    var isCheckingGroqKey by remember { mutableStateOf(false) }
    var groqValidationMessage by remember { mutableStateOf<String?>(null) }
    var groqKeyStatus by remember { mutableStateOf(SetupKeyStatus.IDLE) }

    var deepgramKeyInput by remember { mutableStateOf(securePrefs.getDeepgramApiKey() ?: "") }
    var isDeepgramKeyVisible by remember { mutableStateOf(false) }

    var liveCapabilityMessage by remember { mutableStateOf<String?>(null) }
    var isLiveCapable by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        isServerKeyAvailable = resolver.isServerKeyConfigured()
    }

    val testAndSaveKeys: () -> Unit = {
        val normalizedGemini = normalizeApiKey(apiKeyInput)
        val trimmedGroq = groqKeyInput.trim()

        if (normalizedGemini.isBlank()) {
            keyStatus = SetupKeyStatus.INVALID
            keyValidationMessage = "Gemini API key is required — please enter your Gemini key"
            return@testAndSaveKeys
        }

        if (!securePrefs.isSecureStorageAvailable()) {
            keyStatus = SetupKeyStatus.INVALID
            keyValidationMessage = "Secure storage unavailable on this device — keys were not saved"
            return@testAndSaveKeys
        }

        isCheckingKey = true
        keyStatus = SetupKeyStatus.CHECKING
        keyValidationMessage = "Validating key with Google..."
        liveCapabilityMessage = null

        scope.launch {
            // Fast 12s cap validation per constraints
            val geminiOutcome = withTimeoutOrNull(12_000L) {
                restClient.validateApiKeyAndChat(normalizedGemini)
            } ?: KeyValidationOutcome.TransientServerBusy()

            // In-app Live-capability check (spec §3.7)
            val liveOutcome = restClient.checkLiveCapability(normalizedGemini)
            isLiveCapable = liveOutcome.isCapable
            liveCapabilityMessage = liveOutcome.message
            io.github.ryoaonetsuki.mira.voice.LiveVoiceDiagnostics.updateLiveCapability(liveOutcome.message)

            // Validate Groq ONLY if user provided a key (Groq is optional, spec §3.3)
            if (trimmedGroq.isNotBlank()) {
                isCheckingGroqKey = true
                groqKeyStatus = SetupKeyStatus.CHECKING
                groqValidationMessage = "Validating Groq key..."
                val groqOutcome = serverVoiceClient.validateGroqKey(trimmedGroq)
                isCheckingGroqKey = false
                when (groqOutcome) {
                    is KeyValidationOutcome.Valid -> {
                        groqKeyStatus = SetupKeyStatus.VALID
                        groqValidationMessage = "Valid Groq Key! Stored encrypted on device."
                        securePrefs.setGroqApiKey(trimmedGroq)
                    }
                    is KeyValidationOutcome.TransientServerBusy -> {
                        groqKeyStatus = SetupKeyStatus.TRANSIENT
                        groqValidationMessage = groqOutcome.message
                        securePrefs.setGroqApiKey(trimmedGroq)
                    }
                    is KeyValidationOutcome.InvalidKey -> {
                        groqKeyStatus = SetupKeyStatus.INVALID
                        groqValidationMessage = groqOutcome.userFacingMessage
                    }
                }
            } else {
                securePrefs.clearGroqApiKey()
            }

            isCheckingKey = false

            val isGeminiOk = geminiOutcome is KeyValidationOutcome.Valid || geminiOutcome is KeyValidationOutcome.TransientServerBusy

            when (geminiOutcome) {
                is KeyValidationOutcome.Valid -> {
                    keyStatus = SetupKeyStatus.VALID
                    keyValidationMessage = "Valid Gemini API Key! Stored encrypted on device."
                }
                is KeyValidationOutcome.TransientServerBusy -> {
                    keyStatus = SetupKeyStatus.TRANSIENT
                    keyValidationMessage = "Google's servers are busy right now — key saved, try again in a bit"
                    Toast.makeText(
                        context,
                        "Google's servers are busy right now — key saved, try again in a bit",
                        Toast.LENGTH_LONG
                    ).show()
                }
                is KeyValidationOutcome.InvalidKey -> {
                    keyStatus = SetupKeyStatus.INVALID
                    keyValidationMessage = geminiOutcome.userFacingMessage
                }
            }

            // Save Deepgram key if provided
            val trimmedDeepgram = deepgramKeyInput.trim()
            if (trimmedDeepgram.isNotBlank()) {
                securePrefs.setDeepgramApiKey(trimmedDeepgram)
            }

            // Only Gemini key is REQUIRED to proceed (spec §3.3, §3.6)
            if (isGeminiOk) {
                val savedGemini = securePrefs.setGeminiApiKey(normalizedGemini)
                if (savedGemini) {
                    currentStep = 2
                } else {
                    keyStatus = SetupKeyStatus.INVALID
                    keyValidationMessage = "Secure storage unavailable on this device — keys could not be saved"
                }
            } else {
                Toast.makeText(
                    context,
                    "Cannot proceed: invalid Gemini key",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    val testGroqKeyOnly: () -> Unit = {
        val trimmed = groqKeyInput.trim()
        if (trimmed.isBlank()) {
            groqKeyStatus = SetupKeyStatus.INVALID
            groqValidationMessage = "Groq API key is required — please enter your Groq key"
        } else if (!SecurePreferences.isPlausibleGroqKey(trimmed)) {
            groqKeyStatus = SetupKeyStatus.INVALID
            groqValidationMessage = "Groq key must start with 'gsk_' and be at least 20 characters"
        } else if (!securePrefs.isSecureStorageAvailable()) {
            groqKeyStatus = SetupKeyStatus.INVALID
            groqValidationMessage = "Secure storage unavailable on this device — key was not saved"
        } else {
            isCheckingGroqKey = true
            groqKeyStatus = SetupKeyStatus.CHECKING
            groqValidationMessage = "Validating key with Groq..."
            scope.launch {
                val outcome = serverVoiceClient.validateGroqKey(trimmed)
                isCheckingGroqKey = false
                when (outcome) {
                    is KeyValidationOutcome.Valid -> {
                        val saved = securePrefs.setGroqApiKey(trimmed)
                        if (saved) {
                            groqKeyStatus = SetupKeyStatus.VALID
                            groqValidationMessage = "Valid Groq Key! Stored encrypted on device."
                        } else {
                            groqKeyStatus = SetupKeyStatus.INVALID
                            groqValidationMessage = "Secure storage unavailable on this device — key was not saved"
                        }
                    }
                    is KeyValidationOutcome.TransientServerBusy -> {
                        val saved = securePrefs.setGroqApiKey(trimmed)
                        if (saved) {
                            groqKeyStatus = SetupKeyStatus.TRANSIENT
                            groqValidationMessage = outcome.message
                        } else {
                            groqKeyStatus = SetupKeyStatus.INVALID
                            groqValidationMessage = "Groq servers busy. Key was NOT rejected — try again shortly."
                        }
                    }
                    is KeyValidationOutcome.InvalidKey -> {
                        groqKeyStatus = SetupKeyStatus.INVALID
                        groqValidationMessage = outcome.userFacingMessage
                    }
                }
            }
        }
    }

    // Permission states
    var hasMicPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        )
    }

    val powerManager = remember { context.getSystemService(Context.POWER_SERVICE) as PowerManager }
    var isBatteryIgnoringOptimizations by remember {
        mutableStateOf(powerManager.isIgnoringBatteryOptimizations(context.packageName))
    }

    var hasFileAccess by remember {
        mutableStateOf(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Environment.isExternalStorageManager()
            } else true
        )
    }

    var isAccessibilityEnabled by remember {
        mutableStateOf(MiraAccessibilityService.isEnabled(context))
    }

    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                hasMicPermission = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                isBatteryIgnoringOptimizations = powerManager.isIgnoringBatteryOptimizations(context.packageName)
                hasFileAccess = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    Environment.isExternalStorageManager()
                } else true
                isAccessibilityEnabled = MiraAccessibilityService.isEnabled(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    val micPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasMicPermission = granted
    }

    val multiPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        hasMicPermission = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(palette.background)
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(20.dp)
    ) {
        // Step Indicator
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = "Step $currentStep of 2",
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = palette.primary,
                letterSpacing = 1.sp
            )
            Spacer(modifier = Modifier.weight(1f))
            Text(
                text = if (currentStep == 1) "API Key" else "Permissions",
                fontSize = 13.sp,
                color = palette.textSecondary
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        if (currentStep == 1) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                // STEP 1: API Keys
                Text(
                    text = "Add Your API Keys",
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    color = palette.textPrimary
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Gemini and Groq keys are required — free, fast, and no credit card needed.",
                    fontSize = 14.sp,
                    color = palette.textSecondary,
                    lineHeight = 20.sp
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Guide Card
                MiraCard {
                    Text(
                        text = "Quick Setup Guide",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = palette.primary
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "1. Gemini key (required) — gives Mira her intelligence and voice\n2. Groq key (required) — gives real-time speech recognition for voice\n3. Deepgram key (optional) — optional speech engine, addable anytime",
                        fontSize = 13.sp,
                        color = palette.textSecondary,
                        lineHeight = 20.sp
                    )
                }

                Spacer(modifier = Modifier.height(20.dp))

                // FIELD 1: Gemini API Key (REQUIRED)
                OutlinedTextField(
                    value = apiKeyInput,
                    onValueChange = {
                        apiKeyInput = it
                        keyValidationMessage = null
                        keyStatus = SetupKeyStatus.IDLE
                    },
                    label = { Text("Gemini API Key (Required)") },
                    placeholder = { Text("Paste your Gemini key — starts with AQ.") },
                    visualTransformation = if (isKeyVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { isKeyVisible = !isKeyVisible }) {
                            Icon(
                                imageVector = if (isKeyVisible) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                                contentDescription = "Toggle Visibility",
                                tint = palette.textSecondary
                            )
                        }
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = palette.primary,
                        unfocusedBorderColor = palette.surfaceVariant,
                        focusedTextColor = palette.textPrimary,
                        unfocusedTextColor = palette.textPrimary,
                        focusedContainerColor = palette.surface,
                        unfocusedContainerColor = palette.surface
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                // Hint line under the Gemini field
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "New keys from Google AI Studio start with AQ. — that is correct, paste the whole key.",
                    fontSize = 12.sp,
                    color = palette.textSecondary,
                    lineHeight = 17.sp
                )

                if (keyValidationMessage != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    val msgColor = when (keyStatus) {
                        SetupKeyStatus.VALID -> Color(0xFF10B981)
                        SetupKeyStatus.TRANSIENT -> Color(0xFFF59E0B)
                        SetupKeyStatus.INVALID -> Color(0xFFEF4444)
                        SetupKeyStatus.CHECKING, SetupKeyStatus.IDLE -> palette.textSecondary
                    }
                    Text(
                        text = keyValidationMessage!!,
                        fontSize = 13.sp,
                        color = msgColor
                    )
                }

                if (liveCapabilityMessage != null) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = liveCapabilityMessage!!,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = if (isLiveCapable) Color(0xFF10B981) else Color(0xFFF59E0B)
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Gemini Action Buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://aistudio.google.com/apikey"))
                            browserIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                            context.startActivity(browserIntent)
                        },
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = palette.primary),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Get Free Key")
                    }

                    MiraButton(
                        text = "Test & Save",
                        onClick = testAndSaveKeys,
                        isLoading = isCheckingKey || isCheckingGroqKey,
                        modifier = Modifier.weight(1f)
                    )
                }

                Spacer(modifier = Modifier.height(20.dp))

                // FIELD 2: Groq API Key (OPTIONAL for alternative voice)
                OutlinedTextField(
                    value = groqKeyInput,
                    onValueChange = {
                        groqKeyInput = it
                        groqValidationMessage = null
                        groqKeyStatus = SetupKeyStatus.IDLE
                    },
                    label = { Text("Groq API Key (Optional — alternative voice)") },
                    placeholder = { Text("Paste your Groq key (optional) — starts with gsk_") },
                    visualTransformation = if (isGroqKeyVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { isGroqKeyVisible = !isGroqKeyVisible }) {
                            Icon(
                                imageVector = if (isGroqKeyVisible) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                                contentDescription = "Toggle Visibility",
                                tint = palette.textSecondary
                            )
                        }
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = palette.primary,
                        unfocusedBorderColor = palette.surfaceVariant,
                        focusedTextColor = palette.textPrimary,
                        unfocusedTextColor = palette.textPrimary,
                        focusedContainerColor = palette.surface,
                        unfocusedContainerColor = palette.surface
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                // Hint line under the Groq field
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "Optional for alternative voice. Native Gemini Live only requires a Gemini API key.",
                    fontSize = 12.sp,
                    color = palette.textSecondary,
                    lineHeight = 17.sp
                )

                if (groqValidationMessage != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    val gMsgColor = when (groqKeyStatus) {
                        SetupKeyStatus.VALID -> Color(0xFF10B981)
                        SetupKeyStatus.TRANSIENT -> Color(0xFFF59E0B)
                        SetupKeyStatus.INVALID -> Color(0xFFEF4444)
                        SetupKeyStatus.CHECKING, SetupKeyStatus.IDLE -> palette.textSecondary
                    }
                    Text(
                        text = groqValidationMessage!!,
                        fontSize = 13.sp,
                        color = gMsgColor
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Groq Action Buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://console.groq.com/keys"))
                            browserIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                            context.startActivity(browserIntent)
                        },
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = palette.primary),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Get Free Key")
                    }

                    MiraButton(
                        text = "Test Groq Key",
                        onClick = testGroqKeyOnly,
                        isLoading = isCheckingGroqKey,
                        modifier = Modifier.weight(1f)
                    )
                }

                Spacer(modifier = Modifier.height(20.dp))

                // FIELD 3: Deepgram API Key (OPTIONAL)
                OutlinedTextField(
                    value = deepgramKeyInput,
                    onValueChange = { deepgramKeyInput = it },
                    label = { Text("Deepgram API Key (optional)") },
                    placeholder = { Text("Paste your Deepgram key (optional)") },
                    visualTransformation = if (isDeepgramKeyVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { isDeepgramKeyVisible = !isDeepgramKeyVisible }) {
                            Icon(
                                imageVector = if (isDeepgramKeyVisible) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                                contentDescription = "Toggle Visibility",
                                tint = palette.textSecondary
                            )
                        }
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = palette.primary,
                        unfocusedBorderColor = palette.surfaceVariant,
                        focusedTextColor = palette.textPrimary,
                        unfocusedTextColor = palette.textPrimary,
                        focusedContainerColor = palette.surface,
                        unfocusedContainerColor = palette.surface
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                // Hint line under Deepgram field
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "Optional — gives you $200 free credit at console.deepgram.com/signup. Can be added later in Settings.",
                    fontSize = 12.sp,
                    color = palette.textSecondary,
                    lineHeight = 17.sp
                )

                Spacer(modifier = Modifier.height(14.dp))

                OutlinedButton(
                    onClick = {
                        val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://console.deepgram.com/signup"))
                        browserIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        context.startActivity(browserIntent)
                    },
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = palette.primary),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Get Free Key")
                }

                Spacer(modifier = Modifier.height(24.dp))

                // Primary Save & Continue Button
                MiraButton(
                    text = "Save Keys & Continue →",
                    onClick = testAndSaveKeys,
                    isLoading = isCheckingKey || isCheckingGroqKey,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(16.dp))

                if (isServerKeyAvailable) {
                    Spacer(modifier = Modifier.height(8.dp))
                    TextButton(
                        onClick = { currentStep = 2 },
                        modifier = Modifier.align(Alignment.CenterHorizontally)
                    ) {
                        Text("Mira server key available — Continue with server key →", color = palette.primary)
                    }
                } else if (securePrefs.getGeminiApiKey() != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    TextButton(
                        onClick = { currentStep = 2 },
                        modifier = Modifier.align(Alignment.CenterHorizontally)
                    ) {
                        Text("Gemini key already saved — Continue to Step 2 →", color = palette.primary)
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
            }
        } else {
            // STEP 2: Permissions Wizard
            if (keyValidationMessage != null && (keyStatus == SetupKeyStatus.VALID || keyStatus == SetupKeyStatus.TRANSIENT)) {
                val bannerBg = if (keyStatus == SetupKeyStatus.TRANSIENT) {
                    Color(0xFFF59E0B).copy(alpha = 0.15f)
                } else {
                    Color(0xFF10B981).copy(alpha = 0.15f)
                }
                val bannerTextColor = if (keyStatus == SetupKeyStatus.TRANSIENT) {
                    Color(0xFFF59E0B)
                } else {
                    Color(0xFF10B981)
                }
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = bannerBg,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = keyValidationMessage!!,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        color = bannerTextColor,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
                    )
                }
                Spacer(modifier = Modifier.height(14.dp))
            }

            Text(
                text = "Keep Mira Alive",
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                color = palette.textPrimary
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "Microphone, Battery optimization & File Management are required for real-time voice and file creation. The rest enable tools and features.",
                fontSize = 14.sp,
                color = palette.textSecondary
            )

            Spacer(modifier = Modifier.height(16.dp))

            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Required 1: Microphone
                item {
                    PermissionRow(
                        title = "Microphone (Required)",
                        subtitle = "Required to speak to Mira in real-time",
                        icon = Icons.Rounded.Mic,
                        isGranted = hasMicPermission,
                        isRequired = true,
                        onClick = {
                            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        }
                    )
                }

                // Required 2: Battery Optimization Off
                item {
                    PermissionRow(
                        title = "Battery: Unrestricted (Required)",
                        subtitle = "Prevents Android from killing Mira's voice in the background",
                        icon = Icons.Rounded.BatteryChargingFull,
                        isGranted = isBatteryIgnoringOptimizations,
                        isRequired = true,
                        onClick = {
                            try {
                                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                                    data = Uri.parse("package:${context.packageName}")
                                }
                                context.startActivity(intent)
                            } catch (e: Exception) {
                                val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                                context.startActivity(intent)
                            }
                        }
                    )
                }

                // Required 3: File Management
                item {
                    PermissionRow(
                        title = "File Management (Required)",
                        subtitle = "Mira needs file access to save websites, code and files you ask her to build",
                        icon = Icons.Rounded.Folder,
                        isGranted = hasFileAccess,
                        isRequired = true,
                        onClick = {
                            try {
                                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                                    data = Uri.parse("package:${context.packageName}")
                                }
                                context.startActivity(intent)
                            } catch (e: Exception) {
                                val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                                context.startActivity(intent)
                            }
                        }
                    )
                }

                // Optional: Notifications
                item {
                    PermissionRow(
                        title = "Notifications",
                        subtitle = "Display foreground session and background updates",
                        icon = Icons.Rounded.Notifications,
                        isGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
                        } else true,
                        onClick = {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                micPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                            }
                        }
                    )
                }

                // Optional: Notification Access (WhatsApp)
                item {
                    PermissionRow(
                        title = "Notification Access (WhatsApp)",
                        subtitle = "Allows reading WhatsApp messages for auto-reply",
                        icon = Icons.Rounded.MarkChatUnread,
                        isGranted = false,
                        onClick = {
                            val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                            context.startActivity(intent)
                        }
                    )
                }

                // Optional: Accessibility
                item {
                    PermissionRow(
                        title = "Accessibility Service (Macros)",
                        subtitle = "Allows Mira to operate the screen for WhatsApp and YouTube macros",
                        icon = Icons.Rounded.Accessibility,
                        isGranted = isAccessibilityEnabled,
                        onClick = {
                            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                            context.startActivity(intent)
                        }
                    )
                }

                // Optional: Overlay
                item {
                    PermissionRow(
                        title = "Display Over Other Apps",
                        subtitle = "Allows floating orb over apps and home screen",
                        icon = Icons.Rounded.Layers,
                        isGranted = Settings.canDrawOverlays(context),
                        onClick = {
                            val intent = Intent(
                                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:${context.packageName}")
                            )
                            context.startActivity(intent)
                        }
                    )
                }

                // Batch prompt for standard permissions
                item {
                    PermissionRow(
                        title = "Standard Integrations",
                        subtitle = "Camera, Contacts, Phone calls, SMS, Location",
                        icon = Icons.Rounded.Apps,
                        isGranted = false,
                        onClick = {
                            val perms = mutableListOf(
                                Manifest.permission.CAMERA,
                                Manifest.permission.READ_CONTACTS,
                                Manifest.permission.CALL_PHONE,
                                Manifest.permission.SEND_SMS,
                                Manifest.permission.ACCESS_FINE_LOCATION
                            )
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                perms.add(Manifest.permission.READ_MEDIA_IMAGES)
                            }
                            multiPermissionLauncher.launch(perms.toTypedArray())
                        }
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Refresh status on resume
            LaunchedEffect(Unit) {
                hasMicPermission = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                isBatteryIgnoringOptimizations = powerManager.isIgnoringBatteryOptimizations(context.packageName)
                hasFileAccess = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    Environment.isExternalStorageManager()
                } else true
            }

            val isReady = hasMicPermission && isBatteryIgnoringOptimizations && hasFileAccess
            MiraButton(
                text = "Finish & Start Talking",
                enabled = isReady,
                onClick = {
                    onSetupComplete()
                },
                modifier = Modifier.fillMaxWidth()
            )

            val missingList = mutableListOf<String>().apply {
                if (!hasMicPermission) add("Microphone")
                if (!isBatteryIgnoringOptimizations) add("Battery")
                if (!hasFileAccess) add("File Management")
            }
            if (missingList.isNotEmpty()) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "* ${missingList.joinToString(", ")} permission is required to continue",
                    fontSize = 12.sp,
                    color = Color(0xFFEF4444),
                    modifier = Modifier.align(Alignment.CenterHorizontally)
                )
            }
        }
    }
}

@Composable
fun PermissionRow(
    title: String,
    subtitle: String,
    icon: ImageVector,
    isGranted: Boolean,
    isRequired: Boolean = false,
    onClick: () -> Unit
) {
    val palette = LocalMiraPalette.current

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(palette.surface)
            .clickable { onClick() }
            .padding(14.dp)
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(if (isGranted) Color(0xFF10B981).copy(alpha = 0.15f) else palette.surfaceVariant)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (isGranted) Color(0xFF10B981) else palette.textPrimary,
                modifier = Modifier.size(20.dp)
            )
        }

        Spacer(modifier = Modifier.width(14.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = palette.textPrimary
            )
            Text(
                text = subtitle,
                fontSize = 12.sp,
                color = palette.textSecondary
            )
        }

        Spacer(modifier = Modifier.width(8.dp))

        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(if (isGranted) Color(0xFF10B981).copy(alpha = 0.2f) else palette.surfaceVariant)
                .padding(horizontal = 8.dp, vertical = 4.dp)
        ) {
            Text(
                text = if (isGranted) "Allowed" else if (isRequired) "Required" else "Pending",
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = if (isGranted) Color(0xFF10B981) else if (isRequired) Color(0xFFEF4444) else palette.textSecondary
            )
        }
    }
}
