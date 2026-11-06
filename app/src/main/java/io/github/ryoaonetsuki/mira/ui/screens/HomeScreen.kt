package io.github.ryoaonetsuki.mira.ui.screens

import io.github.ryoaonetsuki.mira.ui.icons.Lucide
import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import io.github.ryoaonetsuki.mira.data.preferences.SecurePreferences
import io.github.ryoaonetsuki.mira.data.preferences.UserPreferences
import io.github.ryoaonetsuki.mira.network.WeatherClient
import io.github.ryoaonetsuki.mira.network.WeatherData
import io.github.ryoaonetsuki.mira.service.LiveState
import io.github.ryoaonetsuki.mira.service.MicService
import io.github.ryoaonetsuki.mira.ui.components.AnimatedOrb
import io.github.ryoaonetsuki.mira.ui.components.MiraCard
import io.github.ryoaonetsuki.mira.ui.navigation.Screen
import io.github.ryoaonetsuki.mira.ui.theme.LocalMiraPalette
import java.text.SimpleDateFormat
import java.util.*

data class QuickChip(val label: String, val icon: ImageVector, val prompt: String)

@Composable
fun HomeScreen(
    onNavigate: (String) -> Unit
) {
    val context = LocalContext.current
    val palette = LocalMiraPalette.current
    val userPrefs = remember { UserPreferences(context) }
    val securePrefs = remember { SecurePreferences(context) }
    val cachedConfig = remember { securePrefs.getCachedRemoteConfig() }
    val weatherClient = remember { WeatherClient() }

    val liveState by MicService.liveState.collectAsState()
    val voiceStage by MicService.voiceStage.collectAsState()
    val isFallbackMode by MicService.isFallbackMode.collectAsState()
    val audioChunksSent by MicService.audioChunksSent.collectAsState()
    val audioChunksReceived by MicService.audioChunksReceived.collectAsState()
    val lastVoiceDiagnostic by MicService.lastVoiceDiagnostic.collectAsState()
    val micAmp by MicService.micAmplitude.collectAsState()
    val userName by userPrefs.userNameFlow.collectAsState(initial = "Alex")
    val orbStyle by userPrefs.orbStyleFlow.collectAsState(initial = "Mira Neon")
    val errorMessage by MicService.errorMessage.collectAsState()
    val userTranscript by MicService.userTranscript.collectAsState()

    var weatherData by remember { mutableStateOf(WeatherData("24°C", "60%", "Clear")) }

    // Fetch weather once on load
    LaunchedEffect(Unit) {
        weatherData = weatherClient.getCurrentWeather()
    }

    // Voice error notification: short toast only, never permanent Home text
    LaunchedEffect(liveState) {
        if (liveState == LiveState.ERROR) {
            val msg = errorMessage ?: "Voice session error"
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
        }
    }

    // Time-of-day greeting
    val greeting = remember {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        when (hour) {
            in 5..11 -> "Good morning"
            in 12..16 -> "Good afternoon"
            in 17..21 -> "Good evening"
            else -> "Good night"
        }
    }

    // Current date formatted
    val dateString = remember {
        val sdf = SimpleDateFormat("EEE, d MMM", Locale.getDefault())
        sdf.format(Date())
    }

    val quickChips = listOf(
        QuickChip("Music", Lucide.eighthNote, "Let's play some music to match the mood."),
        QuickChip("Study", Lucide.graduationCap, "Help me study and explain key concepts."),
        QuickChip("Journal", Lucide.book, "Let's do a quick journal entry about today."),
        QuickChip("News", Lucide.newspaper, "What are the top news headlines right now?"),
        QuickChip("Tasks", Lucide.circleCheck, "What tasks should I focus on next?"),
        QuickChip("Reminder", Lucide.alarmClock, "Set a reminder for my upcoming schedule."),
        QuickChip("Focus", Lucide.focus, "Let's begin a 25-minute Pomodoro focus session."),
        QuickChip("Vent", Lucide.heart, "I need someone to listen and talk things through.")
    )

    val isLive = liveState == LiveState.CONNECTING || liveState == LiveState.LISTENING || liveState == LiveState.SPEAKING
    val orbDisplayState = if (liveState == LiveState.ERROR) LiveState.IDLE else liveState

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(palette.background)
            .statusBarsPadding()
    ) {
        // Scrollable main content
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // In-app remote banner
            if (!cachedConfig?.bannerText.isNullOrBlank()) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = palette.surfaceVariant.copy(alpha = 0.8f),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 10.dp)
                ) {
                    Text(
                        text = cachedConfig?.bannerText ?: "",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = palette.primary,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
                    )
                }
            }

            // Big greeting text (at least 30sp bold, clean with no error text)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp)
            ) {
                Text(
                    text = "$greeting, $userName",
                    fontSize = 32.sp,
                    fontWeight = FontWeight.Bold,
                    color = palette.textPrimary
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Info Cards Row (Weather, Today, Mood) — STAY
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Weather
                MiraCard(
                    modifier = Modifier.weight(1f),
                    cornerRadius = 14.dp
                ) {
                    Icon(
                        imageVector = Lucide.sun,
                        contentDescription = "Weather",
                        tint = palette.primary,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = weatherData.temperature,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = palette.textPrimary
                    )
                    Text(
                        text = weatherData.condition,
                        fontSize = 11.sp,
                        color = palette.textSecondary
                    )
                }

                // Date
                MiraCard(
                    modifier = Modifier.weight(1f),
                    cornerRadius = 14.dp
                ) {
                    Icon(
                        imageVector = Lucide.calendar,
                        contentDescription = "Date",
                        tint = palette.secondary,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = dateString,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = palette.textPrimary
                    )
                    Text(
                        text = "Today",
                        fontSize = 11.sp,
                        color = palette.textSecondary
                    )
                }

                // Mood
                MiraCard(
                    modifier = Modifier.weight(1f),
                    cornerRadius = 14.dp
                ) {
                    Icon(
                        imageVector = Lucide.faceSlightlySmiling,
                        contentDescription = "Mood",
                        tint = Color(0xFF10B981),
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Upbeat",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = palette.textPrimary
                    )
                    Text(
                        text = "All good",
                        fontSize = 11.sp,
                        color = palette.textSecondary
                    )
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // The Big Animated Orb (prominent, never sits in red error state)
            AnimatedOrb(
                state = orbDisplayState,
                amplitude = if (liveState == LiveState.ERROR) 0f else micAmp,
                orbStyle = orbStyle
            )

            Spacer(modifier = Modifier.height(14.dp))

            // SMALL status line UNDER the orb only ('Connecting...' / 'Listening...' / 'Speaking...' / idle 'Tap the mic to talk')
            val statusText = when (liveState) {
                LiveState.CONNECTING -> "Connecting..."
                LiveState.LISTENING -> "Listening..."
                LiveState.SPEAKING -> "Speaking..."
                LiveState.IDLE, LiveState.ERROR -> "Tap the mic to talk"
            }
            Text(
                text = statusText,
                fontSize = 13.sp,
                color = palette.textSecondary
            )

            // Voice diagnostics card: shown ONLY while a session is active (liveState != IDLE)
            if (liveState != LiveState.IDLE) {
                Spacer(modifier = Modifier.height(12.dp))
                VoiceDiagnosticsCard(
                    isFallback = isFallbackMode,
                    stage = if (liveState == LiveState.ERROR) "Error" else voiceStage.label,
                    micAmplitude = micAmp,
                    transcript = userTranscript,
                    chunksSent = audioChunksSent,
                    chunksReceived = audioChunksReceived,
                    lastError = lastVoiceDiagnostic ?: errorMessage,
                    palette = palette
                )
            }

            Spacer(modifier = Modifier.height(16.dp))
        }

        // Fixed chips row footer pinned directly above HomeBottomNavBar (never floats mid-screen)
        LazyRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp)
                .padding(top = 8.dp, bottom = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(quickChips) { chip ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clip(RoundedCornerShape(20.dp))
                        .background(palette.surface)
                        .clickable {
                            onNavigate(Screen.Chat.route)
                        }
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    Icon(
                        imageVector = chip.icon,
                        contentDescription = chip.label,
                        tint = palette.primary,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = chip.label,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = palette.textPrimary
                    )
                }
            }
        }

        // Bottom Navigation Bar (Home | Scan | BIG centre Voice | Extensions | Chat)
        HomeBottomNavBar(
            isLive = isLive,
            context = context,
            palette = palette,
            onNavigate = onNavigate
        )
    }
}

@Composable
private fun HomeBottomNavBar(
    isLive: Boolean,
    context: Context,
    palette: io.github.ryoaonetsuki.mira.ui.theme.MiraPalette,
    onNavigate: (String) -> Unit
) {
    val micPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            MicService.startService(context)
        } else {
            Toast.makeText(
                context,
                "Microphone permission is needed for voice",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    val micButtonGradient = if (isLive) {
        Brush.verticalGradient(listOf(Color(0xFFEF4444), Color(0xFFDC2626)))
    } else {
        Brush.verticalGradient(listOf(Color(0xFF22D3EE), Color(0xFF8B5CF6)))
    }

    Surface(
        color = palette.surface,
        shadowElevation = 12.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .height(64.dp)
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            // Home tab
            BottomNavItem(
                icon = Lucide.house,
                label = "Home",
                selected = true,
                palette = palette,
                onClick = { /* already on home */ }
            )

            // Scan tab
            BottomNavItem(
                icon = Lucide.scanQrCode,
                label = "Scan",
                selected = false,
                palette = palette,
                onClick = { onNavigate(Screen.Scan.route) }
            )

            // Centre BIG Voice button
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(56.dp)
                    .shadow(elevation = 8.dp, shape = CircleShape)
                    .clip(CircleShape)
                    .background(micButtonGradient)
                    .clickable {
                        if (isLive) {
                            MicService.stopService(context)
                        } else {
                            val hasMicPermission = ContextCompat.checkSelfPermission(
                                context,
                                Manifest.permission.RECORD_AUDIO
                            ) == PackageManager.PERMISSION_GRANTED
                            if (hasMicPermission) {
                                MicService.startService(context)
                            } else {
                                micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            }
                        }
                    }
            ) {
                Icon(
                    imageVector = if (isLive) Lucide.square else Lucide.mic,
                    contentDescription = if (isLive) "Stop Session" else "Talk to Mira",
                    tint = Color.White,
                    modifier = Modifier.size(26.dp)
                )
            }

            // Extensions tab
            BottomNavItem(
                icon = Lucide.puzzle,
                label = "Extensions",
                selected = false,
                palette = palette,
                onClick = { onNavigate(Screen.Extensions.route) }
            )

            // Chat tab
            BottomNavItem(
                icon = Lucide.messageCircle,
                label = "Chat",
                selected = false,
                palette = palette,
                onClick = { onNavigate(Screen.Chat.route) }
            )
        }
    }
}

@Composable
private fun BottomNavItem(
    icon: ImageVector,
    label: String,
    selected: Boolean,
    palette: io.github.ryoaonetsuki.mira.ui.theme.MiraPalette,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = if (selected) palette.primary else palette.textSecondary,
            modifier = Modifier.size(22.dp)
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = label,
            fontSize = 10.sp,
            color = if (selected) palette.primary else palette.textSecondary,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
        )
    }
}

@Composable
private fun VoiceDiagnosticsCard(
    isFallback: Boolean,
    stage: String,
    micAmplitude: Float,
    transcript: String,
    chunksSent: Long,
    chunksReceived: Long,
    lastError: String?,
    palette: io.github.ryoaonetsuki.mira.ui.theme.MiraPalette
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = palette.surfaceVariant.copy(alpha = 0.85f),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 4.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp)
        ) {
            // Header: Path Badge + Stage
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = if (isFallback) Color(0xFFF59E0B).copy(alpha = 0.2f) else palette.primary.copy(alpha = 0.2f)
                ) {
                    Text(
                        text = if (isFallback) "TAP-TO-TALK" else "LIVE",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (isFallback) Color(0xFFF59E0B) else palette.primary,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                    )
                }

                Text(
                    text = stage,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = palette.textPrimary
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Mic Level Bar
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "Mic",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    color = palette.textSecondary
                )
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(palette.surface)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .fillMaxWidth(fraction = micAmplitude.coerceIn(0f, 1f))
                            .clip(RoundedCornerShape(3.dp))
                            .background(
                                Brush.horizontalGradient(
                                    listOf(palette.primary, palette.secondary)
                                )
                            )
                    )
                }
                Text(
                    text = "${(micAmplitude.coerceIn(0f, 1f) * 100).toInt()}%",
                    fontSize = 10.sp,
                    color = palette.textSecondary
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Heard line
            Text(
                text = if (transcript.isNotBlank()) "Heard: $transcript" else "Heard: (listening...)",
                fontSize = 12.sp,
                color = if (transcript.isNotBlank()) palette.textPrimary else palette.textSecondary,
                maxLines = 3
            )

            Spacer(modifier = Modifier.height(6.dp))

            // Audio chunks sent / received counts
            val statsLabel = if (isFallback) {
                "Turns: $chunksSent sent • $chunksReceived replies"
            } else {
                "Audio chunks: $chunksSent sent • $chunksReceived recv"
            }
            Text(
                text = statsLabel,
                fontSize = 11.sp,
                color = palette.textSecondary
            )

            // Last error line (only if error exists)
            if (!lastError.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(6.dp))
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = Color(0xFFEF4444).copy(alpha = 0.15f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "Error: $lastError",
                        fontSize = 11.sp,
                        color = Color(0xFFEF4444),
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                        maxLines = 3
                    )
                }
            }
        }
    }
}
