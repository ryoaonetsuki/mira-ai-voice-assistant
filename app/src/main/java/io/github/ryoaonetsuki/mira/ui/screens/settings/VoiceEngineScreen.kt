package io.github.ryoaonetsuki.mira.ui.screens.settings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.RecordVoiceOver
import androidx.compose.material.icons.rounded.VerifiedUser
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

    val diagnosticsData by io.github.ryoaonetsuki.mira.voice.LiveVoiceDiagnostics.data.collectAsState()
    val restClient = remember { GeminiRestClient() }
    val diagnosticRunner = remember { io.github.ryoaonetsuki.mira.voice.LiveDiagnosticRunner(context) }

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
            val outcome = restClient.checkLiveCapability(key, liveModel)
            isCheckingCapability = false
            isLiveCapable = outcome.isCapable
            capabilityMessage = outcome.message
            io.github.ryoaonetsuki.mira.voice.LiveVoiceDiagnostics.updateLiveCapability(outcome.message)
        }
    }

    LaunchedEffect(Unit) {
        runCapabilityCheck()
    }

    val voiceRouting by userPrefs.voiceRoutingFlow.collectAsState(initial = "RELAY")
    var showVoicePickerDialog by remember { mutableStateOf(false) }
    var selectedDiagRoute by remember { mutableStateOf(io.github.ryoaonetsuki.mira.voice.DiagnosticRoute.RELAY) }
    var selectedDiagVariant by remember { mutableStateOf(io.github.ryoaonetsuki.mira.voice.DiagnosticSetupVariant.STANDARD) }
    var showFrameLogs by remember { mutableStateOf(false) }

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

            // Voice Connection Routing (Round 15)
            item {
                MiraCard(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = "Voice Connection Routing",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = palette.textPrimary
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Primary routing uses Cloudflare Live Relay to bypass ISP/carrier blocks. Direct Google API is selectable as fallback.",
                        fontSize = 12.sp,
                        color = palette.textSecondary
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        FilterChip(
                            selected = voiceRouting == "RELAY",
                            onClick = { scope.launch { userPrefs.setVoiceRouting("RELAY") } },
                            label = { Text("Cloudflare Relay (Primary)", fontSize = 11.sp) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = palette.primary,
                                selectedLabelColor = Color.Black
                            )
                        )
                        FilterChip(
                            selected = voiceRouting == "DIRECT",
                            onClick = { scope.launch { userPrefs.setVoiceRouting("DIRECT") } },
                            label = { Text("Direct Google API", fontSize = 11.sp) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = palette.primary,
                                selectedLabelColor = Color.Black
                            )
                        )
                    }
                }
            }

            // Voice Diagnostics Card (spec §2, §3.4)
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
                    Text("• Model: $liveModel (Direct Native WebSocket)", fontSize = 12.sp, color = palette.textSecondary)
                    Text("• Audio: Full-duplex 16kHz capture, 24kHz playback", fontSize = 12.sp, color = palette.textSecondary)

                    Spacer(modifier = Modifier.height(14.dp))
                    HorizontalDivider(color = palette.surfaceVariant.copy(alpha = 0.5f))
                    Spacer(modifier = Modifier.height(12.dp))

                    Text("Live Diagnostic Test & Variants (Round 15)", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = palette.textPrimary)
                    Text(
                        text = "Runs an isolated 8-stage mic-free diagnostic test. Compare direct vs Cloudflare relay paths and test setup payload variants with full frame logging (45s timeout).",
                        fontSize = 11.sp,
                        color = palette.textSecondary,
                        lineHeight = 15.sp
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    Text("Network Route:", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = palette.primary)
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                    ) {
                        for (r in io.github.ryoaonetsuki.mira.voice.DiagnosticRoute.values()) {
                            FilterChip(
                                selected = selectedDiagRoute == r,
                                onClick = { selectedDiagRoute = r },
                                label = { Text(r.label, fontSize = 11.sp) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = palette.primary,
                                    selectedLabelColor = Color.Black
                                )
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    Text("Setup Variant:", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = palette.primary)
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                    ) {
                        for (v in io.github.ryoaonetsuki.mira.voice.DiagnosticSetupVariant.values()) {
                            FilterChip(
                                selected = selectedDiagVariant == v,
                                onClick = { selectedDiagVariant = v },
                                label = { Text(v.label, fontSize = 10.sp) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = palette.primary,
                                    selectedLabelColor = Color.Black
                                )
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    Button(
                        onClick = {
                            val key = securePrefs.getGeminiApiKey() ?: ""
                            if (key.isBlank()) {
                                io.github.ryoaonetsuki.mira.voice.LiveVoiceDiagnostics.finishDiagnostic("Failed: No Gemini API key saved — add one in Setup or Settings")
                            } else {
                                scope.launch {
                                    diagnosticRunner.runDiagnostic(
                                        apiKey = key,
                                        model = liveModel,
                                        route = selectedDiagRoute,
                                        variant = selectedDiagVariant
                                    )
                                }
                            }
                        },
                        enabled = !diagnosticsData.isDiagnosticRunning && !isCheckingCapability,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        if (diagnosticsData.isDiagnosticRunning) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                color = MaterialTheme.colorScheme.onPrimary,
                                strokeWidth = 2.dp
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Running (${selectedDiagRoute.label})...")
                        } else {
                            Text("Run Live Diagnostic (${selectedDiagRoute.label})")
                        }
                    }

                    // Diagnostic stages list if stages are present
                    if (diagnosticsData.diagnosticStages.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(12.dp))
                        diagnosticsData.diagnosticStages.forEach { stage ->
                            val statusColor = when (stage.status) {
                                io.github.ryoaonetsuki.mira.voice.StageStatus.SUCCESS -> Color(0xFF10B981)
                                io.github.ryoaonetsuki.mira.voice.StageStatus.FAILED -> Color(0xFFEF4444)
                                io.github.ryoaonetsuki.mira.voice.StageStatus.RUNNING -> palette.primary
                                io.github.ryoaonetsuki.mira.voice.StageStatus.SKIPPED -> palette.textSecondary.copy(alpha = 0.5f)
                                io.github.ryoaonetsuki.mira.voice.StageStatus.PENDING -> palette.textSecondary.copy(alpha = 0.5f)
                            }
                            val statusIcon = when (stage.status) {
                                io.github.ryoaonetsuki.mira.voice.StageStatus.SUCCESS -> "✓"
                                io.github.ryoaonetsuki.mira.voice.StageStatus.FAILED -> "✗"
                                io.github.ryoaonetsuki.mira.voice.StageStatus.RUNNING -> "●"
                                io.github.ryoaonetsuki.mira.voice.StageStatus.SKIPPED -> "○"
                                io.github.ryoaonetsuki.mira.voice.StageStatus.PENDING -> "○"
                            }
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 3.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = statusIcon,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = statusColor,
                                    modifier = Modifier.width(18.dp)
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            text = "${stage.stageNumber}. ${stage.name}",
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Medium,
                                            color = palette.textPrimary
                                        )
                                        if (stage.durationMs != null) {
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text(
                                                text = "(${stage.durationMs}ms)",
                                                fontSize = 10.sp,
                                                color = palette.textSecondary
                                            )
                                        }
                                    }
                                    if (!stage.detail.isNullOrBlank()) {
                                        Text(
                                            text = stage.detail,
                                            fontSize = 11.sp,
                                            color = if (stage.status == io.github.ryoaonetsuki.mira.voice.StageStatus.FAILED) Color(0xFFEF4444) else palette.textSecondary,
                                            lineHeight = 14.sp
                                        )
                                    }
                                }
                            }
                        }
                    }

                    if (!diagnosticsData.diagnosticOutcome.isNullOrBlank()) {
                        Spacer(modifier = Modifier.height(10.dp))
                        val isPass = diagnosticsData.diagnosticOutcome?.startsWith("PASSED", ignoreCase = true) == true
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = if (isPass) Color(0xFF10B981).copy(alpha = 0.15f) else Color(0xFFEF4444).copy(alpha = 0.15f),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = diagnosticsData.diagnosticOutcome!!,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = if (isPass) Color(0xFF10B981) else Color(0xFFEF4444),
                                modifier = Modifier.padding(10.dp)
                            )
                        }
                    }

                    // Expandable Frame Logs
                    if (diagnosticsData.frameLogs.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(10.dp))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { showFrameLogs = !showFrameLogs }
                                .padding(vertical = 4.dp)
                        ) {
                            Text(
                                text = if (showFrameLogs) "Hide Frame Logs (${diagnosticsData.frameLogs.size})" else "View Frame Logs (${diagnosticsData.frameLogs.size})",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = palette.primary,
                                modifier = Modifier.weight(1f)
                            )
                        }
                        if (showFrameLogs) {
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = palette.surfaceVariant.copy(alpha = 0.6f),
                                modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
                            ) {
                                Column(modifier = Modifier.padding(8.dp)) {
                                    diagnosticsData.frameLogs.takeLast(30).forEach { log ->
                                        Text(
                                            text = log,
                                            fontSize = 10.sp,
                                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                            color = palette.textSecondary,
                                            lineHeight = 13.sp
                                        )
                                    }
                                }
                            }
                        }
                    }
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
    }
}
