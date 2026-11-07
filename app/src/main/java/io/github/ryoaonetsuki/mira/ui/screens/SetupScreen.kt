package io.github.ryoaonetsuki.mira.ui.screens

import androidx.annotation.DrawableRes
import androidx.compose.ui.res.painterResource
import io.github.ryoaonetsuki.mira.R
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
    val resolver = remember { io.github.ryoaonetsuki.mira.backend.ApiKeyResolver(context) }

    var currentStep by remember { mutableIntStateOf(startStep) }
    var apiKeyInput by remember { mutableStateOf(securePrefs.getGeminiApiKey() ?: "") }
    var isKeyVisible by remember { mutableStateOf(false) }
    var isCheckingKey by remember { mutableStateOf(false) }
    var keyValidationMessage by remember { mutableStateOf<String?>(null) }
    var keyStatus by remember { mutableStateOf(SetupKeyStatus.IDLE) }
    var isServerKeyAvailable by remember { mutableStateOf(false) }


    var liveCapabilityMessage by remember { mutableStateOf<String?>(null) }
    var isLiveCapable by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        isServerKeyAvailable = resolver.isServerKeyConfigured()
    }

    val testAndSaveKeys: () -> Unit = testAndSaveKeys@{
        val normalizedGemini = normalizeApiKey(apiKeyInput)

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
                // STEP 1: API Key
                Text(
                    text = "Add Your API Key",
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    color = palette.textPrimary
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Only your Gemini key is needed for chat and voice.",
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
                        text = "Get your free Gemini key, paste it below, then save it to continue.",
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
                                painter = painterResource(id = if (isKeyVisible) R.drawable.ic_fluent_eye_off_24_regular else R.drawable.ic_fluent_eye_24_regular),
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

                // Get Free Key helper (not a save action — opens AI Studio)
                OutlinedButton(
                    onClick = {
                        val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://aistudio.google.com/apikey"))
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

                // The single save action: tests the key, saves it, continues.
                MiraButton(
                    text = "Save Key & Continue →",
                    onClick = testAndSaveKeys,
                    isLoading = isCheckingKey,
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
                        iconRes = R.drawable.ic_fluent_mic_24_regular,
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
                        iconRes = R.drawable.ic_fluent_flash_24_regular,
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
                        iconRes = R.drawable.ic_fluent_folder_open_24_regular,
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
                        iconRes = R.drawable.ic_fluent_alert_24_regular,
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
                        iconRes = R.drawable.ic_fluent_chat_24_regular,
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
                        iconRes = R.drawable.ic_fluent_person_24_regular,
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
                        iconRes = R.drawable.ic_fluent_options_24_regular,
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
                        iconRes = R.drawable.ic_fluent_settings_24_regular,
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
    @DrawableRes iconRes: Int,
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
                painter = painterResource(id = iconRes),
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
