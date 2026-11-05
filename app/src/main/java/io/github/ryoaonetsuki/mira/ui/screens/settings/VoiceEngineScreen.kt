package io.github.ryoaonetsuki.mira.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import io.github.ryoaonetsuki.mira.ui.components.MiraButton
import io.github.ryoaonetsuki.mira.ui.components.MiraCard
import io.github.ryoaonetsuki.mira.ui.theme.LocalMiraPalette
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

    var deepgramKeyInput by remember { mutableStateOf(securePrefs.getDeepgramKey() ?: "") }
    var showVoicePickerDialog by remember { mutableStateOf(false) }
    var showGuardianDialog by remember { mutableStateOf(false) }
    var voiceprintLabel by remember { mutableStateOf(securePrefs.getEnrolledFaceName() ?: "Owner (Alex)") }

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
                Text("Voice Personality (30 Gemini Voices)", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = palette.primary)
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
                        Switch(
                            checked = fastListening,
                            onCheckedChange = { scope.launch { userPrefs.setFastListening(it) } },
                            colors = SwitchDefaults.colors(checkedThumbColor = palette.primary)
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
                        Switch(
                            checked = echoGuard,
                            onCheckedChange = { scope.launch { userPrefs.setEchoGuard(it) } },
                            colors = SwitchDefaults.colors(checkedThumbColor = palette.primary)
                        )
                    }
                }
            }

            // Deepgram Backup Transcriber
            item {
                MiraCard(modifier = Modifier.fillMaxWidth()) {
                    Text("Backup Transcriber (Deepgram Nova-3)", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = palette.textPrimary)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text("Optional STT fallback if Google rate limits are encountered.", fontSize = 12.sp, color = palette.textSecondary)
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = deepgramKeyInput,
                        onValueChange = {
                            deepgramKeyInput = it
                            securePrefs.setDeepgramKey(it.trim())
                        },
                        placeholder = { Text("Deepgram API Key (Optional)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
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

            // Diagnostics & App Info
            item {
                MiraCard(modifier = Modifier.fillMaxWidth()) {
                    Text("Session Diagnostics", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = palette.textPrimary)
                    Spacer(modifier = Modifier.height(6.dp))
                    Text("• Microphone Capture: 16,000 Hz Mono PCM16", fontSize = 12.sp, color = palette.textSecondary)
                    Text("• Model Audio Playback: 24,000 Hz Mono PCM16", fontSize = 12.sp, color = palette.textSecondary)
                    Text("• Protocol: Bidirectional WebSocket BidiGenerateContent", fontSize = 12.sp, color = palette.textSecondary)
                    Text("• Active Model Ladder: 3.8-live → 2.5-flash → 3.1-flash", fontSize = 12.sp, color = palette.textSecondary)
                }
            }
        }

        // Voice Picker Dialog (All 30 Voices)
        if (showVoicePickerDialog) {
            AlertDialog(
                onDismissRequest = { showVoicePickerDialog = false },
                title = { Text("Select Voice (30 Gemini Voices)") },
                text = {
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
                                        showVoicePickerDialog = false
                                    }
                                    .padding(vertical = 8.dp)
                            ) {
                                Text(
                                    text = "${v.name} • ${v.style}",
                                    fontSize = 14.sp,
                                    color = if (selectedVoice == v.name) palette.primary else palette.textPrimary
                                )
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
                                        showVoicePickerDialog = false
                                    }
                                    .padding(vertical = 8.dp)
                            ) {
                                Text(
                                    text = "${v.name} • ${v.style}",
                                    fontSize = 14.sp,
                                    color = if (selectedVoice == v.name) palette.primary else palette.textPrimary
                                )
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showVoicePickerDialog = false }) { Text("Close") }
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
