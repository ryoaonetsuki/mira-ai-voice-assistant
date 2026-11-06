package io.github.ryoaonetsuki.mira.ui.screens

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
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
    val weatherClient = remember { WeatherClient() }

    val liveState by MicService.liveState.collectAsState()
    val micAmp by MicService.micAmplitude.collectAsState()
    val userName by userPrefs.userNameFlow.collectAsState(initial = "Alex")
    val orbStyle by userPrefs.orbStyleFlow.collectAsState(initial = "Mira Neon")
    val errorMessage by MicService.errorMessage.collectAsState()

    var weatherData by remember { mutableStateOf(WeatherData("24°C", "60%", "Clear")) }

    // Fetch weather once on load
    LaunchedEffect(Unit) {
        weatherData = weatherClient.getCurrentWeather()
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
        QuickChip("Music", Icons.Rounded.MusicNote, "Let's play some music to match the mood."),
        QuickChip("Study", Icons.Rounded.School, "Help me study and explain key concepts."),
        QuickChip("Journal", Icons.Rounded.Book, "Let's do a quick journal entry about today."),
        QuickChip("News", Icons.Rounded.Newspaper, "What are the top news headlines right now?"),
        QuickChip("Tasks", Icons.Rounded.CheckCircle, "What tasks should I focus on next?"),
        QuickChip("Reminder", Icons.Rounded.Alarm, "Set a reminder for my upcoming schedule."),
        QuickChip("Focus", Icons.Rounded.CenterFocusStrong, "Let's begin a 25-minute Pomodoro focus session."),
        QuickChip("Vent", Icons.Rounded.Favorite, "I need someone to listen and talk things through.")
    )

    val isLive = liveState != LiveState.IDLE && liveState != LiveState.ERROR

    Column(
        modifier = Modifier
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
            // Greeting & Status line
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp)
            ) {
                Text(
                    text = "$greeting, $userName",
                    fontSize = 26.sp,
                    fontWeight = FontWeight.Bold,
                    color = palette.textPrimary
                )
                Spacer(modifier = Modifier.height(4.dp))

                // Error message display — shows real error when in ERROR state
                if (liveState == LiveState.ERROR && errorMessage != null) {
                    Text(
                        text = errorMessage ?: "Voice session error",
                        fontSize = 13.sp,
                        color = Color(0xFFEF4444)
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    // Retry button
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "Retry",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = palette.primary,
                            modifier = Modifier.clickable {
                                MicService.stopService(context)
                                MicService.startService(context)
                            }
                        )
                    }
                } else {
                    Text(
                        text = when (liveState) {
                            LiveState.IDLE -> "Mira is ready to assist you"
                            LiveState.CONNECTING -> "Connecting to live voice..."
                            LiveState.LISTENING -> "Listening to your voice..."
                            LiveState.SPEAKING -> "Speaking..."
                            LiveState.ERROR -> "Voice session error — check key"
                        },
                        fontSize = 13.sp,
                        color = palette.textSecondary
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Info Cards Row (Weather, Today, Mood)
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
                        imageVector = Icons.Rounded.WbSunny,
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
                        imageVector = Icons.Rounded.CalendarToday,
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
                        imageVector = Icons.Rounded.Mood,
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

            // The Animated Orb
            AnimatedOrb(
                state = liveState,
                amplitude = micAmp,
                orbStyle = orbStyle
            )

            Spacer(modifier = Modifier.height(22.dp))

            // "Ask Mira anything…" input bar (above chips)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(palette.surface)
                    .clickable { onNavigate(Screen.Chat.route) }
                    .padding(horizontal = 14.dp, vertical = 12.dp)
            ) {
                Icon(
                    imageVector = Icons.Rounded.ChatBubbleOutline,
                    contentDescription = null,
                    tint = palette.textSecondary,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = "Ask Mira anything…",
                    fontSize = 14.sp,
                    color = palette.textSecondary,
                    modifier = Modifier.weight(1f)
                )
                Icon(
                    imageVector = Icons.Rounded.Send,
                    contentDescription = "Send",
                    tint = palette.primary,
                    modifier = Modifier.size(18.dp)
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Quick action chips — directly above the bottom nav bar
            LazyRow(
                modifier = Modifier.fillMaxWidth(),
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

            Spacer(modifier = Modifier.height(8.dp))
        }

        // Bottom Navigation Bar
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
    context: android.content.Context,
    palette: io.github.ryoaonetsuki.mira.ui.theme.MiraPalette,
    onNavigate: (String) -> Unit
) {
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
                icon = Icons.Rounded.Home,
                label = "Home",
                selected = true,
                palette = palette,
                onClick = { /* already on home */ }
            )

            // Scan tab
            BottomNavItem(
                icon = Icons.Rounded.QrCodeScanner,
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
                            MicService.startService(context)
                        }
                    }
            ) {
                Icon(
                    imageVector = if (isLive) Icons.Rounded.Stop else Icons.Rounded.Mic,
                    contentDescription = if (isLive) "Stop Session" else "Talk to Mira",
                    tint = Color.White,
                    modifier = Modifier.size(26.dp)
                )
            }

            // Extensions tab
            BottomNavItem(
                icon = Icons.Rounded.Extension,
                label = "Extensions",
                selected = false,
                palette = palette,
                onClick = { onNavigate(Screen.Extensions.route) }
            )

            // Chat tab
            BottomNavItem(
                icon = Icons.Rounded.Chat,
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
