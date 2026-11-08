package io.github.ryoaonetsuki.mira.ui.screens.settings

import androidx.compose.ui.res.painterResource
import io.github.ryoaonetsuki.mira.BuildConfig
import io.github.ryoaonetsuki.mira.R
import io.github.ryoaonetsuki.mira.service.MicService
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
import io.github.ryoaonetsuki.mira.data.model.VoiceCatalog
import io.github.ryoaonetsuki.mira.data.preferences.SecurePreferences
import io.github.ryoaonetsuki.mira.data.preferences.UserPreferences
import io.github.ryoaonetsuki.mira.network.GeminiRestClient
import io.github.ryoaonetsuki.mira.network.GeminiTtsClient
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
    val echoGuard by userPrefs.echoGuardFlow.collectAsState(initial = true)

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

    var showVoicePickerDialog by remember { mutableStateOf(false) }

    val ttsClient = remember { GeminiTtsClient(context) }
    var activeAudioPlayer by remember { mutableStateOf<AudioPlayer?>(null) }
    var playingVoice by remember { mutableStateOf<String?>(null) }
    var loadingVoice by remember { mutableStateOf<String?>(null) }
    var previewError by remember { mutableStateOf<String?>(null) }
    var playbackJob by remember { mutableStateOf<Job?>(null) }

    fun stopPreview() {
        playbackJob?.cancel()
        playbackJob = null
        activeAudioPlayer?.stopPreviewCleanly()
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
                player.beginPreview()
                val rampedBytes = AudioPlayer.applyPreviewFadeRamps(pcmBytes)
                val chunkSize = 4096
                var offset = 0
                while (offset < rampedBytes.size && isActive) {
                    val length = minOf(chunkSize, rampedBytes.size - offset)
                    player.playAudioChunk(rampedBytes.copyOfRange(offset, offset + length))
                    offset += length
                    delay(10)
                }
                if (isActive) {
                    player.endPreviewCleanly()
                    if (playingVoice == voiceName) {
                        playingVoice = null
                    }
                    if (activeAudioPlayer == player) {
                        activeAudioPlayer = null
                    }
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
                title = {
                    Text(
                        text = "Voice Engine",
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
                    if (BuildConfig.DEBUG) {
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedButton(
                            onClick = { MicService.simulateGoAwayForTesting() },
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Simulate GoAway (Auto-Reconnect)")
                        }
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
                            painter = painterResource(id = R.drawable.ic_fluent_sound_wave_circle_24_regular),
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
                        Icon(
                            painter = painterResource(id = R.drawable.ic_fluent_chevron_right_24_regular),
                            contentDescription = null,
                            tint = palette.textSecondary
                        )
                    }
                }
            }

            item {
                Spacer(modifier = Modifier.height(4.dp))
                Text("Voice Session Toggles", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = palette.primary)
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
                                                Icon(
                                                    painter = painterResource(id = R.drawable.ic_fluent_stop_24_filled),
                                                    contentDescription = "Stop",
                                                    tint = palette.primary,
                                                    modifier = Modifier.size(16.dp)
                                                )
                                            }
                                            else -> {
                                                Icon(
                                                    painter = painterResource(id = R.drawable.ic_fluent_play_24_regular),
                                                    contentDescription = "Play",
                                                    tint = if (selectedVoice == v.name) palette.primary else palette.textSecondary,
                                                    modifier = Modifier.size(16.dp)
                                                )
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
                                                Icon(
                                                    painter = painterResource(id = R.drawable.ic_fluent_stop_24_filled),
                                                    contentDescription = "Stop",
                                                    tint = palette.primary,
                                                    modifier = Modifier.size(16.dp)
                                                )
                                            }
                                            else -> {
                                                Icon(
                                                    painter = painterResource(id = R.drawable.ic_fluent_play_24_regular),
                                                    contentDescription = "Play",
                                                    tint = if (selectedVoice == v.name) palette.primary else palette.textSecondary,
                                                    modifier = Modifier.size(16.dp)
                                                )
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
