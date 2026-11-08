package io.github.ryoaonetsuki.mira.ui.screens

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import io.github.ryoaonetsuki.mira.R
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
import kotlin.math.PI
import kotlin.math.sin

@Composable
fun HomeScreen(
    modifier: Modifier = Modifier,
    onNavigate: (String) -> Unit
) {
    val context = LocalContext.current
    val palette = LocalMiraPalette.current
    val userPrefs = remember { UserPreferences(context) }
    val securePrefs = remember { SecurePreferences(context) }
    val cachedConfig = remember { securePrefs.getCachedRemoteConfig() }
    val weatherClient = remember { WeatherClient() }

    val liveState by MicService.liveState.collectAsState()
    val micAmp by MicService.micAmplitude.collectAsState()
    val orbStyle by userPrefs.orbStyleFlow.collectAsState(initial = "Mira Neon")
    val errorMessage by MicService.errorMessage.collectAsState()

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

    // Current date formatted
    val dateString = remember {
        val sdf = SimpleDateFormat("EEE, d MMM", Locale.getDefault())
        sdf.format(Date())
    }

    val isLive = liveState == LiveState.CONNECTING || liveState == LiveState.LISTENING || liveState == LiveState.SPEAKING
    val orbDisplayState = if (liveState == LiveState.ERROR) LiveState.IDLE else liveState

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(palette.background)
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
            // In-app remote banner with smooth animation and subtle glow
            AnimatedVisibility(
                visible = !cachedConfig?.bannerText.isNullOrBlank(),
                enter = fadeIn(animationSpec = tween(300)) + expandVertically(animationSpec = tween(300))
            ) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = palette.surfaceVariant.copy(alpha = 0.85f),
                    border = BorderStroke(1.dp, palette.primary.copy(alpha = 0.35f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp)
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

            // Info Cards Row (Weather, Today) — harmonized to 84dp height & borderSubtle
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Weather Card
                MiraCard(
                    modifier = Modifier
                        .weight(1f)
                        .height(84.dp),
                    cornerRadius = 14.dp,
                    containerColor = palette.surface,
                    borderColor = palette.borderSubtle,
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp)
                ) {
                    Column(
                        modifier = Modifier.fillMaxHeight(),
                        verticalArrangement = Arrangement.SpaceBetween
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_fluent_weather_sunny_24_regular),
                            contentDescription = "Weather",
                            tint = palette.primary,
                            modifier = Modifier.size(18.dp)
                        )
                        Column {
                            Text(
                                text = weatherData.temperature,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold,
                                color = palette.textPrimary,
                                maxLines = 1
                            )
                            Text(
                                text = weatherData.condition,
                                fontSize = 11.sp,
                                color = palette.textSecondary,
                                maxLines = 1
                            )
                        }
                    }
                }

                // Date Card
                MiraCard(
                    modifier = Modifier
                        .weight(1f)
                        .height(84.dp),
                    cornerRadius = 14.dp,
                    containerColor = palette.surface,
                    borderColor = palette.borderSubtle,
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp)
                ) {
                    Column(
                        modifier = Modifier.fillMaxHeight(),
                        verticalArrangement = Arrangement.SpaceBetween
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_fluent_calendar_ltr_24_regular),
                            contentDescription = "Date",
                            tint = palette.secondary,
                            modifier = Modifier.size(18.dp)
                        )
                        Column {
                            Text(
                                text = dateString,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = palette.textPrimary,
                                maxLines = 1
                            )
                            Text(
                                text = "Today",
                                fontSize = 11.sp,
                                color = palette.textSecondary,
                                maxLines = 1
                            )
                        }
                    }
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

            // SMALL status line UNDER the orb only ('Connecting...' / 'Listening...' / 'Speaking...' / idle 'Tap the voice button to start')
            val statusText = when (liveState) {
                LiveState.CONNECTING -> "Connecting..."
                LiveState.LISTENING -> "Listening..."
                LiveState.SPEAKING -> "Speaking..."
                LiveState.IDLE, LiveState.ERROR -> "Tap the voice button to start"
            }
            Crossfade(
                targetState = statusText,
                animationSpec = tween(200),
                label = "orb-status"
            ) { text ->
                Text(
                    text = text,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Center,
                    color = palette.textSecondary
                )
            }

            // ROUND 6: on-screen voice diagnostics card REMOVED (Salman's order) —
            // Home matches the v1.0.0 layout again (orb + small status line only).
            // Diagnostics still go to logcat via MicService (Log.i/w/e at every
            // _lastVoiceDiagnostic update) — never on screen.
            Spacer(modifier = Modifier.height(16.dp))
        }

        // Floating Bottom Navigation Bar (Home | Vision | BIG centre Voice | Memory | Chat)
        HomeBottomNavBar(
            isLive = isLive,
            micAmplitude = micAmp,
            context = context,
            palette = palette,
            onNavigate = onNavigate
        )
    }
}

@Composable
private fun HomeBottomNavBar(
    isLive: Boolean,
    micAmplitude: Float,
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

    val micButtonGradient = remember(palette.primary, palette.secondary) {
        Brush.verticalGradient(listOf(palette.primary, palette.secondary))
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = 10.dp)
    ) {
        Surface(
            color = palette.surface.copy(alpha = 0.97f),
            shadowElevation = 6.dp,
            shape = RoundedCornerShape(30.dp),
            border = BorderStroke(1.dp, palette.borderSubtle.copy(alpha = 0.6f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(68.dp)
                    .padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                // Home tab
                BottomNavItem(
                    regularIcon = R.drawable.ic_fluent_home_24_regular,
                    filledIcon = R.drawable.ic_fluent_home_24_filled,
                    label = "Home",
                    selected = true,
                    palette = palette,
                    onClick = { /* already on home */ }
                )

                // Vision tab (Camera, never QR scanner)
                BottomNavItem(
                    regularIcon = R.drawable.ic_fluent_camera_24_regular,
                    filledIcon = R.drawable.ic_fluent_camera_24_filled,
                    label = "Vision",
                    selected = false,
                    palette = palette,
                    onClick = { onNavigate(Screen.Scan.route) }
                )

                // Centre BIG Voice button (behaviour unchanged)
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(56.dp)
                        .shadow(elevation = 4.dp, shape = CircleShape)
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
                    if (isLive) {
                        VoiceWaveformBars(
                            amplitude = micAmplitude,
                            modifier = Modifier.size(24.dp)
                        )
                    } else {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_fluent_mic_24_regular),
                            contentDescription = "Talk to Mira",
                            tint = Color.White,
                            modifier = Modifier.size(26.dp)
                        )
                    }
                }

                // Memory tab
                BottomNavItem(
                    regularIcon = R.drawable.ic_fluent_sparkle_24_regular,
                    filledIcon = R.drawable.ic_fluent_sparkle_24_filled,
                    label = "Memory",
                    selected = false,
                    palette = palette,
                    onClick = { onNavigate(Screen.Memories.route) }
                )

                // Chat tab
                BottomNavItem(
                    regularIcon = R.drawable.ic_fluent_chat_24_regular,
                    filledIcon = R.drawable.ic_fluent_chat_24_filled,
                    label = "Chat",
                    selected = false,
                    palette = palette,
                    onClick = { onNavigate(Screen.Chat.route) }
                )
            }
        }
    }
}

@Composable
private fun VoiceWaveformBars(
    amplitude: Float,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "VoiceBarsPhaseTransition")
    val phase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = (2 * PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "VoiceBarsPhase"
    )

    val smoothedAmp by animateFloatAsState(
        targetValue = amplitude.coerceIn(0f, 1f),
        animationSpec = spring(
            dampingRatio = 0.8f,
            stiffness = Spring.StiffnessHigh
        ),
        label = "VoiceBarsSmoothedAmp"
    )

    Canvas(modifier = modifier) {
        val barCount = 5
        val barWidth = 3.dp.toPx()
        val totalBarsWidth = barWidth * barCount
        val spacing = (size.width - totalBarsWidth) / (barCount - 1)

        val profile = floatArrayOf(0.45f, 0.75f, 1.0f, 0.75f, 0.45f)
        val baseMin = 4.dp.toPx()
        val baseMax = 7.dp.toPx()
        val maxHeight = (size.height - 2.dp.toPx()).coerceAtLeast(baseMax)

        for (i in 0 until barCount) {
            val barPhase = phase + i * 1.25f
            val wave = sin(barPhase)
            val base = baseMin + (baseMax - baseMin) * profile[i] + 1.2.dp.toPx() * wave
            val variation = (maxHeight - baseMax) * profile[i] * (1f + 0.2f * wave)
            val barHeight = (base + smoothedAmp * variation).coerceIn(baseMin, maxHeight)
            val x = i * (barWidth + spacing)
            val y = (size.height - barHeight) / 2f

            drawRoundRect(
                color = Color.White,
                topLeft = Offset(x, y),
                size = Size(barWidth, barHeight),
                cornerRadius = CornerRadius(barWidth / 2f, barWidth / 2f)
            )
        }
    }
}

@Composable
private fun BottomNavItem(
    @DrawableRes regularIcon: Int,
    @DrawableRes filledIcon: Int = regularIcon,
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
            painter = painterResource(id = if (selected) filledIcon else regularIcon),
            contentDescription = label,
            tint = if (selected) palette.primary else palette.textSecondary,
            modifier = Modifier.size(22.dp)
        )
        Spacer(modifier = Modifier.height(3.dp))
        Text(
            text = label,
            fontSize = 10.sp,
            color = if (selected) palette.primary else palette.textSecondary,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
        )
    }
}
