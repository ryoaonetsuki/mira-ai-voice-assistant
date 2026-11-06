package io.github.ryoaonetsuki.mira.ui.screens.settings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.RecordVoiceOver
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import io.github.ryoaonetsuki.mira.ui.components.MiraSwitch
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.ryoaonetsuki.mira.data.model.LiveVoiceModel
import io.github.ryoaonetsuki.mira.data.model.VoiceCatalog
import io.github.ryoaonetsuki.mira.data.preferences.SecurePreferences
import io.github.ryoaonetsuki.mira.data.preferences.UserPreferences
import io.github.ryoaonetsuki.mira.network.GeminiRestClient
import io.github.ryoaonetsuki.mira.network.GeminiTtsClient
import io.github.ryoaonetsuki.mira.ui.components.MiraButton
import io.github.ryoaonetsuki.mira.ui.components.MiraCard
import io.github.ryoaonetsuki.mira.ui.theme.LocalMiraPalette
import io.github.ryoaonetsuki.mira.voice.AudioPlayer
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoiceEngineScreen(
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val palette = LocalMiraPalette.current
    val scope = rememberCoroutineScope()
    val userPrefs = remember { UserPreferences(context) }
    val securePrefs = remember { SecurePreferences(context) }

    val liveModel by userPrefs.liveModelFlow.collectAsState(initial = "models/gemini-3.8-live")
    val selectedVoice by userPrefs.voiceNameFlow.collectAsState(initial = "Aoede")
    val fastListening by userPrefs.fastListeningFlow.collectAsState(initial = true)
    val echoGuard by userPrefs.echoGuardFlow.collectAsState(initial = true)
    val alternativeVoiceEnabled by userPrefs.alternativeVoiceEnabledFlow.collectAsState(initial = false)

    val diagnosticsData by io.github.ryoaonetsuki.mira.voice.LiveVoiceDiagnostics.data.collectAsState()
    val restClient = remember { GeminiRestClient() }

    var isCheckingCapability by remember { mutableStateOf(false) }
    var capabilityMessage by remember { mutableStateOf<String?>(null) }
    var isLiveCapable by remember { mutableStateOf(false) }

    fun runCapabilityCheck() {
        val key = securePrefs.getGeminiApiKey() ?: ""
        if (key.isBlank()) {
            capabilityMessage = "No Gemini API key saved — add one in Setup or Personal Profile"
            isLiveCapable = false
            return
        }
        isCheckingCapability = true
        scope.launch {
            val outcome = restClient.checkLiveCapability(key)
            isCheckingCapability = false
            isLiveCapable = outcome.isCapable
            capabilityMessage = outcome.message
            io.github.ryoaonetsuki.mira.voice.LiveVoiceDiagnostics.updateLiveCapability(outcome.message)
        }
    }

    LaunchedEffect(Unit) {
        runCapabilityCheck()
    }

    var groqKeyInput by remember { mutableStateOf(securePrefs.getGroqApiKey() ?: "") }
    var isGroqKeyVisible by remember { mutableStateOf(false) }
    var deepgramKeyInput by remember { mutableStateOf(securePrefs.getDeepgramApiKey() ?: "") }
    var isDeepgramKeyVisible by remember { mutableStateOf(false) }
    var isDeepgramSaved by remember { mutableStateOf(securePrefs.getDeepgramApiKey() != null) }
    var showVoicePickerDialog by remember { mutableStateOf(false) }
    var showGuardianDialog by remember { mutableStateOf(false) }
    var voiceprintLabel by remember { mutableStateOf(securePrefs.getEnrolledFaceName() ?: "Owner (Alex)") }

    val ttsClient = remember { GeminiTtsClient(context) }
    var activeAudioPlayer by remember { mutableStateOf<AudioPlayer?>(null) }
    var playingVoice by remember { mutableStateOf<String?>(null) }
    var loadingVoice by remember { mutableStateOf<String?>(null) }
    var previewError by remember { mutableStateOf<String?>(null) }
    var playbackJob by remember { mutableStateOf<Job?>(null) }

    fun stopPreview() {
        playbackJob?.cancel()
        playbackJob = null
        activeAudioPlayer?.stopImmediatelyAndFlush()
        activeAudioPlayer?.release()
        activeAudioPlayer = null
        playingVoice = null
        loadingVoice = null
    }

    DisposableEffect(Unit) {
        onDispose {
            stopPreview()
        }
    }

    fun startPreview(voiceName: String) {
        stopPreview()
        val resolver = io.github.ryoaonetsuki.mira.backend.ApiKeyResolver(context)
        loadingVoice = voiceName
        previewError = null
        playbackJob = scope.launch {
            val keySource = resolver.resolveKey()
            if (keySource is io.github.ryoaonetsuki.mira.backend.KeySource.NoKey) {
                loadingVoice = null
                previewError = "Add your API key first to hear voice previews"
                return@launch
            }
            val rawApiKey = (keySource as? io.github.ryoaonetsuki.mira.backend.KeySource.UserKey)?.key ?: (securePrefs.getGeminiApiKey() ?: "")
            val useServer = keySource is io.github.ryoaonetsuki.mira.backend.KeySource.ServerKey
            val result = ttsClient.generatePreview(rawApiKey, voiceName, useServerProxy = useServer)
            if (result.isSuccess) {
                val pcmBytes = result.getOrThrow()
                loadingVoice = null
                playingVoice = voiceName
                val player = AudioPlayer { isSpeaking ->
                    if (!isSpeaking && playingVoice == voiceName) {
                        playingVoice = null
                    }
                }
                activeAudioPlayer = player
                val chunkSize = 4096
                var offset = 0
                while (offset < pcmBytes.size && isActive) {
                    val length = minOf(chunkSize, pcmBytes.size - offset)
                    player.playAudioChunk(pcmBytes.copyOfRange(offset, offset + length))
                    offset += length
                    delay(10)
                }
            } else {
                loadingVoice = null
                previewError = result.exceptionOrNull()?.message ?: "Voice preview failed"
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Voice Engine", fontWeight = FontWeight.Bold, color = palette.textPrimary) },
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
            // Live-Capability Check Card (spec §3.7)
            item {
                MiraCard(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "Live Voice Capability",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = palette.textPrimary,
                            modifier = Modifier.weight(1f)
                        )
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(
                                    if (isCheckingCapability) palette.surfaceVariant
                                    else if (isLiveCapable) Color(0xFF10B981).copy(alpha = 0.2f)
                                    else Color(0xFFF59E0B).copy(alpha = 0.2f)
                                )
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Text(
                                text = if (isCheckingCapability) "Checking..." else if (isLiveCapable) "Available" else "Not Supported",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (isCheckingCapability) palette.textSecondary else if (isLiveCapable) Color(0xFF10B981) else Color(0xFFF59E0B)
                            )
                        }
                    }
                    if (capabilityMessage != null) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = capabilityMessage!!,
                            fontSize = 12.sp,
                            color = if (isLiveCapable) Color(0xFF10B981) else Color(0xFFF59E0B),
                            lineHeight = 17.sp
                        )
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                    OutlinedButton(
                        onClick = { runCapabilityCheck() },
                        enabled = !isCheckingCapability,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(if (isCheckingCapability) "Checking with Google..." else "Check Key Capability")
                    }
                }
            }

            item {
                Text("Normal Mode — The Three Minds", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = palette.primary)
            }

            // 3 Mind Cards: Swift, Soul, Mind
            items(LiveVoiceModel.values()) { model ->
                val isSelected = liveModel == model.modelId
                MiraCard(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        scope.launch { userPrefs.setLiveModel(model.modelId) }
                    }
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = model.displayName,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (isSelected) palette.primary else palette.textPrimary
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = model.subtitle,
                                fontSize = 12.sp,
                                color = palette.textSecondary
                            )
                        }
                        RadioButton(
                            selected = isSelected,
                            onClick = { scope.launch { userPrefs.setLiveModel(model.modelId) } },
                            colors = RadioButtonDefaults.colors(selectedColor = palette.primary)
                        )
                    }
                }
            }

            item {
                Spacer(modifier = Modifier.height(4.dp))
                Text("Voice Personality (30 Voices)", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = palette.primary)
            }

            // Voice Picker
            item {
                val currentVoiceInfo = remember(selectedVoice) {
                    VoiceCatalog.allVoices.find { it.name == selectedVoice } ?: VoiceCatalog.femaleVoices.first()
                }

                MiraCard(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { showVoicePickerDialog = true }
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Rounded.RecordVoiceOver,
                            contentDescription = null,
                            tint = palette.primary,
                            modifier = Modifier.size(26.dp)
                        )
                        Spacer(modifier = Modifier.width(14.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Active Voice: ${currentVoiceInfo.name}",
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold,
                                color = palette.textPrimary
                            )
                            Text(
                                text = "${if (currentVoiceInfo.isFemale) "Female" else "Male"} • Style: ${currentVoiceInfo.style}",
                                fontSize = 12.sp,
                                color = palette.textSecondary
                            )
                        }
                        Icon(Icons.Rounded.ChevronRight, contentDescription = null, tint = palette.textSecondary)
                    }
                }
            }

            item {
                Spacer(modifier = Modifier.height(4.dp))
                Text("Voice Session Toggles", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = palette.primary)
            }

            // Fast Listening Toggle
            item {
                MiraCard(modifier = Modifier.fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Fast Listening", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = palette.textPrimary)
                            Text("Streams speech continuously with low audio latency", fontSize = 12.sp, color = palette.textSecondary)
                        }
                        MiraSwitch(
                            checked = fastListening,
                            onCheckedChange = { scope.launch { userPrefs.setFastListening(it) } }
                        )
                    }
                }
            }

            // Echo Guard
            item {
                MiraCard(modifier = Modifier.fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Echo Guard", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = palette.textPrimary)
                            Text("Stops Mira replying to her own voice during loudspeaker playback", fontSize = 12.sp, color = palette.textSecondary)
                        }
                        MiraSwitch(
                            checked = echoGuard,
                            onCheckedChange = { scope.launch { userPrefs.setEchoGuard(it) } }
                        )
                    }
                }
            }

            // Alternative Voice Toggle (Server Voice) — Gated default OFF (spec §3.6)
            item {
                MiraCard(modifier = Modifier.fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("Alternative voice (Server Voice)", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = palette.textPrimary)
                                Spacer(modifier = Modifier.width(6.dp))
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(palette.surfaceVariant)
                                        .padding(horizontal = 6.dp, vertical = 2.dp)
                                ) {
                                    Text("Experimental", fontSize = 10.sp, color = palette.textSecondary, fontWeight = FontWeight.Bold)
                                }
                            }
                            Spacer(modifier = Modifier.height(2.dp))
                            Text("Legacy streaming voice via Cloudflare Worker. Keep OFF for Native Gemini Live.", fontSize = 12.sp, color = palette.textSecondary)
                        }
                        MiraSwitch(
                            checked = alternativeVoiceEnabled,
                            onCheckedChange = { scope.launch { userPrefs.setAlternativeVoiceEnabled(it) } }
                        )
                    }
                }
            }

            // Server Voice (Groq) — Gated behind alternativeVoiceEnabled
            item {
                MiraCard(modifier = Modifier.fillMaxWidth()) {
                    Text("Server Voice (Groq)", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = palette.textPrimary)
                    Spacer(modifier = Modifier.height(4.dp))
                    if (!alternativeVoiceEnabled) {
                        Text("Alternative voice is currently disabled. Mira uses Native Gemini Live directly as the primary voice engine without needing a Groq key.", fontSize = 12.sp, color = palette.textSecondary)
                    } else {
                        Text("Free at console.groq.com — no card, your own quota", fontSize = 12.sp, color = palette.textSecondary)
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(
                            value = groqKeyInput,
                            onValueChange = {
                                groqKeyInput = it
                                if (it.isBlank()) {
                                    securePrefs.clearGroqApiKey()
                                } else {
                                    securePrefs.setGroqApiKey(it.trim())
                                }
                            },
                            placeholder = { Text("Groq API Key (starts with gsk_)") },
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
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }

            // Deepgram Voice (Optional)
            item {
                MiraCard(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "Deepgram Voice (Optional)",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = palette.textPrimary,
                            modifier = Modifier.weight(1f)
                        )
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (isDeepgramSaved) Color(0xFF10B981).copy(alpha = 0.2f) else palette.surfaceVariant)
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Text(
                                text = if (isDeepgramSaved) "Saved" else "Not Saved",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (isDeepgramSaved) Color(0xFF10B981) else palette.textSecondary
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Free $200 credit at console.deepgram.com — optional key addable for speech-to-text",
                        fontSize = 12.sp,
                        color = palette.textSecondary
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = deepgramKeyInput,
                        onValueChange = { deepgramKeyInput = it },
                        placeholder = { Text("Deepgram API Key (optional)") },
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
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        OutlinedButton(
                            onClick = {
                                securePrefs.clearDeepgramApiKey()
                                deepgramKeyInput = ""
                                isDeepgramSaved = false
                            },
                            shape = RoundedCornerShape(12.dp),
                            enabled = isDeepgramSaved || deepgramKeyInput.isNotBlank(),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Clear")
                        }
                        MiraButton(
                            text = "Save Key",
                            onClick = {
                                val trimmed = deepgramKeyInput.trim()
                                if (trimmed.isNotBlank()) {
                                    securePrefs.setDeepgramApiKey(trimmed)
                                    isDeepgramSaved = true
                                } else {
                                    securePrefs.clearDeepgramApiKey()
                                    isDeepgramSaved = false
                                }
                            },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }

            // Voice Guardian Enrolment
            item {
                MiraCard(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { showGuardianDialog = true }
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.VerifiedUser, contentDescription = null, tint = palette.primary, modifier = Modifier.size(26.dp))
                        Spacer(modifier = Modifier.width(14.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Voice Guardian", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = palette.textPrimary)
                            Text("Enrolled Voiceprint: $voiceprintLabel", fontSize = 12.sp, color = palette.textSecondary)
                        }
                        Icon(Icons.Rounded.ChevronRight, contentDescription = null, tint = palette.textSecondary)
                    }
                }
            }

            // Voice Diagnostics Card (spec §3.4)
            item {
                MiraCard(modifier = Modifier.fillMaxWidth()) {
                    Text("Voice Diagnostics", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = palette.primary)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("• Last Session Stage: ${diagnosticsData.lastStage}", fontSize = 12.sp, color = palette.textSecondary)
                    Text("• WebSocket Status: ${diagnosticsData.lastWsCode?.let { "HTTP $it" } ?: "Not connected"}", fontSize = 12.sp, color = palette.textSecondary)
                    Text("• Setup Duration: ${diagnosticsData.setupDurationMs?.let { "${it}ms" } ?: "N/A"}", fontSize = 12.sp, color = palette.textSecondary)
                    Text("• Chunks Sent: ${diagnosticsData.chunksSent} (16kHz PCM16)", fontSize = 12.sp, color = palette.textSecondary)
                    Text("• Chunks Received: ${diagnosticsData.chunksReceived} (24kHz PCM16)", fontSize = 12.sp, color = palette.textSecondary)
                    Text("• Last Error: ${diagnosticsData.lastError ?: "None"}", fontSize = 12.sp, color = if (diagnosticsData.lastError != null) Color(0xFFEF4444) else palette.textSecondary)
                    Text("• Key Live Capability: ${diagnosticsData.liveCapabilityResult}", fontSize = 12.sp, color = palette.textSecondary)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text("• Model: models/gemini-3.8-live (Direct Native WebSocket)", fontSize = 12.sp, color = palette.textSecondary)
                    Text("• Audio: Full-duplex 16kHz capture, 24kHz playback", fontSize = 12.sp, color = palette.textSecondary)
                }
            }
        }

        // Voice Picker Dialog (All 30 Voices)
        if (showVoicePickerDialog) {
            AlertDialog(
                onDismissRequest = {
                    stopPreview()
                    showVoicePickerDialog = false
                },
                title = { Text("Select Voice (30 Voices)") },
                text = {
                    Column {
                        if (previewError != null) {
                            Text(
                                text = previewError!!,
                                fontSize = 12.sp,
                                color = Color(0xFFEF4444),
                                modifier = Modifier.padding(bottom = 6.dp)
                            )
                        }

                        LazyColumn(modifier = Modifier.height(340.dp)) {
                            item {
                                Text("Female Voices (13)", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = palette.primary, modifier = Modifier.padding(vertical = 4.dp))
                            }
                            items(VoiceCatalog.femaleVoices) { v ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            scope.launch { userPrefs.setVoiceName(v.name) }
                                            stopPreview()
                                            showVoicePickerDialog = false
                                        }
                                        .padding(vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = "${v.name} • ${v.style}",
                                            fontSize = 14.sp,
                                            fontWeight = if (selectedVoice == v.name) FontWeight.Bold else FontWeight.Normal,
                                            color = if (selectedVoice == v.name) palette.primary else palette.textPrimary
                                        )
                                        if (playingVoice == v.name) {
                                            Text(
                                                text = "Playing preview...",
                                                fontSize = 11.sp,
                                                color = palette.primary
                                            )
                                        }
                                    }

                                    IconButton(
                                        onClick = {
                                            if (playingVoice == v.name || loadingVoice == v.name) {
                                                stopPreview()
                                            } else {
                                                startPreview(v.name)
                                            }
                                        },
                                        modifier = Modifier.size(36.dp)
                                    ) {
                                        when {
                                            loadingVoice == v.name -> {
                                                CircularProgressIndicator(
                                                    modifier = Modifier.size(16.dp),
                                                    strokeWidth = 2.dp,
                                                    color = palette.primary
                                                )
                                            }
                                            playingVoice == v.name -> {
                                                Canvas(modifier = Modifier.size(14.dp)) {
                                                    drawRect(color = palette.primary)
                                                }
                                            }
                                            else -> {
                                                Canvas(modifier = Modifier.size(14.dp)) {
                                                    val path = androidx.compose.ui.graphics.Path().apply {
                                                        moveTo(2f, 1f)
                                                        lineTo(size.width - 1f, size.height / 2f)
                                                        lineTo(2f, size.height - 1f)
                                                        close()
                                                    }
                                                    drawPath(path, color = if (selectedVoice == v.name) palette.primary else palette.textSecondary)
                                                }
                                            }
                                        }
                                    }
                                }
                            }

                            item {
                                Spacer(modifier = Modifier.height(8.dp))
                                Text("Male Voices (17)", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = palette.secondary, modifier = Modifier.padding(vertical = 4.dp))
                            }
                            items(VoiceCatalog.maleVoices) { v ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            scope.launch { userPrefs.setVoiceName(v.name) }
                                            stopPreview()
                                            showVoicePickerDialog = false
                                        }
                                        .padding(vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = "${v.name} • ${v.style}",
                                            fontSize = 14.sp,
                                            fontWeight = if (selectedVoice == v.name) FontWeight.Bold else FontWeight.Normal,
                                            color = if (selectedVoice == v.name) palette.primary else palette.textPrimary
                                        )
                                        if (playingVoice == v.name) {
                                            Text(
                                                text = "Playing preview...",
                                                fontSize = 11.sp,
                                                color = palette.primary
                                            )
                                        }
                                    }

                                    IconButton(
                                        onClick = {
                                            if (playingVoice == v.name || loadingVoice == v.name) {
                                                stopPreview()
                                            } else {
                                                startPreview(v.name)
                                            }
                                        },
                                        modifier = Modifier.size(36.dp)
                                    ) {
                                        when {
                                            loadingVoice == v.name -> {
                                                CircularProgressIndicator(
                                                    modifier = Modifier.size(16.dp),
                                                    strokeWidth = 2.dp,
                                                    color = palette.primary
                                                )
                                            }
                                            playingVoice == v.name -> {
                                                Canvas(modifier = Modifier.size(14.dp)) {
                                                    drawRect(color = palette.primary)
                                                }
                                            }
                                            else -> {
                                                Canvas(modifier = Modifier.size(14.dp)) {
                                                    val path = androidx.compose.ui.graphics.Path().apply {
                                                        moveTo(2f, 1f)
                                                        lineTo(size.width - 1f, size.height / 2f)
                                                        lineTo(2f, size.height - 1f)
                                                        close()
                                                    }
                                                    drawPath(path, color = if (selectedVoice == v.name) palette.primary else palette.textSecondary)
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        stopPreview()
                        showVoicePickerDialog = false
                    }) { Text("Close") }
                },
                containerColor = palette.surface,
                titleContentColor = palette.textPrimary,
                textContentColor = palette.textSecondary
            )
        }

        // Guardian Dialog
        if (showGuardianDialog) {
            AlertDialog(
                onDismissRequest = { showGuardianDialog = false },
                title = { Text("Voice Guardian Enrolment") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = "Voice Guardian restricts sensitive actions so Mira responds only to your enrolled voiceprint.",
                            fontSize = 13.sp,
                            color = palette.textSecondary
                        )
                        OutlinedTextField(
                            value = voiceprintLabel,
                            onValueChange = { voiceprintLabel = it },
                            label = { Text("Voiceprint Label") },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        securePrefs.setEnrolledFaceName(voiceprintLabel)
                        showGuardianDialog = false
                    }) {
                        Text("Save")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showGuardianDialog = false }) { Text("Cancel") }
                },
                containerColor = palette.surface,
                titleContentColor = palette.textPrimary,
                textContentColor = palette.textSecondary
            )
        }
    }
}
