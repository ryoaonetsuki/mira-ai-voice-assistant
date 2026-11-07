package io.github.ryoaonetsuki.mira.ui.screens

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.annotation.DrawableRes
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import io.github.ryoaonetsuki.mira.data.preferences.UserPreferences
import io.github.ryoaonetsuki.mira.ui.components.MiraCard
import io.github.ryoaonetsuki.mira.ui.theme.LocalMiraPalette
import io.github.ryoaonetsuki.mira.ui.theme.MiraMotion

data class AmbientTrack(
    val id: String,
    val name: String,
    val subtitle: String,
    @DrawableRes val iconRes: Int,
    val accentColor: Color
)

data class VibePreset(
    val title: String,
    val subtitle: String,
    @DrawableRes val iconRes: Int,
    val query: String,
    val accentColor: Color
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VibesScreen(
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val palette = LocalMiraPalette.current
    val userPrefs = remember { UserPreferences(context) }

    val musicApp by userPrefs.musicAppFlow.collectAsState(initial = "Spotify")
    val favoriteSong by userPrefs.favoriteSongFlow.collectAsState(initial = "Starboy")

    // Ambient mixer tracks
    val ambientTracks = remember {
        listOf(
            AmbientTrack("rain", "Gentle Rain", "Soothing patter for calming thoughts", R.drawable.ic_fluent_weather_moon_24_regular, Color(0xFF22D3EE)),
            AmbientTrack("forest", "Deep Forest", "Rustling leaves and morning birds", R.drawable.ic_fluent_drink_coffee_24_regular, Color(0xFF10B981)),
            AmbientTrack("waves", "Ocean Waves", "Rhythmic rolling surf and coastal tide", R.drawable.ic_fluent_sound_wave_circle_24_regular, Color(0xFF38BDF8)),
            AmbientTrack("whitenoise", "White Noise", "Steady broadband sound for deep focus", R.drawable.ic_fluent_headphones_24_regular, Color(0xFF8B5CF6)),
            AmbientTrack("fireplace", "Cozy Fireplace", "Warm crackling embers and flame", R.drawable.ic_fluent_weather_sunny_24_regular, Color(0xFFF59E0B))
        )
    }

    var activeTrackIds by remember { mutableStateOf(setOf<String>()) }
    val trackVolumes = remember {
        mutableStateMapOf(
            "rain" to 0.7f,
            "forest" to 0.6f,
            "waves" to 0.65f,
            "whitenoise" to 0.8f,
            "fireplace" to 0.5f
        )
    }

    var activePreset by remember { mutableStateOf<String?>(null) }

    fun applyPreset(presetName: String) {
        activePreset = presetName
        when (presetName) {
            "Focus" -> {
                activeTrackIds = setOf("whitenoise", "forest")
                trackVolumes["whitenoise"] = 0.75f
                trackVolumes["forest"] = 0.45f
            }
            "Relax" -> {
                activeTrackIds = setOf("rain", "waves")
                trackVolumes["rain"] = 0.7f
                trackVolumes["waves"] = 0.55f
            }
            "Sleep" -> {
                activeTrackIds = setOf("rain", "fireplace")
                trackVolumes["rain"] = 0.6f
                trackVolumes["fireplace"] = 0.4f
            }
            "Mute All" -> {
                activeTrackIds = emptySet()
                activePreset = null
            }
        }
    }

    val vibes = listOf(
        VibePreset("Chill Lo-Fi", "Mellow beats to unwind and relax", R.drawable.ic_fluent_headphones_24_regular, "lofi hip hop radio beats to relax chill to", Color(0xFF22D3EE)),
        VibePreset("Focus & Study", "Deep binaural and ambient flow state", R.drawable.ic_fluent_target_arrow_24_regular, "deep focus ambient study music", Color(0xFF8B5CF6)),
        VibePreset("Gym & Energy", "High BPM workout drive and pump", R.drawable.ic_fluent_dumbbell_24_regular, "workout motivation high energy electronic gym music", Color(0xFFEF4444)),
        VibePreset("Late Night Soul", "Moody RnB, jazz and atmospheric vibes", R.drawable.ic_fluent_weather_moon_24_regular, "late night r&b and moody soul playlist", Color(0xFFA855F7)),
        VibePreset("Acoustic Warmth", "Unplugged guitar, coffeehouse tunes", R.drawable.ic_fluent_drink_coffee_24_regular, "acoustic guitar warm coffeehouse playlist", Color(0xFFF59E0B)),
        VibePreset("Party & Dance", "Chart toppers, remixes and dancefloor hits", R.drawable.ic_fluent_sparkle_24_regular, "party dance club hits playlist", Color(0xFF10B981))
    )

    fun launchMusic(query: String) {
        val encoded = Uri.encode(query)
        try {
            when (musicApp) {
                "Spotify" -> {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse("spotify:search:$encoded"))
                    intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    context.startActivity(intent)
                }
                "YouTube Music" -> {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://music.youtube.com/search?q=$encoded"))
                    intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    context.startActivity(intent)
                }
                else -> { // YouTube / Web
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=$encoded"))
                    intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    context.startActivity(intent)
                }
            }
        } catch (e: Exception) {
            // Fallback to web search
            val fallback = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=$encoded"))
            fallback.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            context.startActivity(fallback)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Vibes & Audio",
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
            // One-Tap Presets Header & Chips
            item {
                Text(
                    text = "Ambient Sound Mixer",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = palette.primary,
                    modifier = Modifier.padding(top = 4.dp)
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val presets = listOf("Focus", "Relax", "Sleep", "Mute All")
                    for (preset in presets) {
                        val isSelected = activePreset == preset
                        FilterChip(
                            selected = isSelected,
                            onClick = { applyPreset(preset) },
                            label = { Text(preset, fontSize = 12.sp, fontWeight = FontWeight.Medium) },
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
            }

            // Ambient Mixer Cards
            items(ambientTracks) { track ->
                val isPlaying = activeTrackIds.contains(track.id)
                val volume = trackVolumes[track.id] ?: 0.5f

                MiraCard(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .size(42.dp)
                                    .clip(CircleShape)
                                    .background(track.accentColor.copy(alpha = if (isPlaying) 0.22f else 0.12f))
                                    .border(
                                        width = if (isPlaying) 1.5.dp else 1.dp,
                                        color = if (isPlaying) track.accentColor else palette.borderSubtle,
                                        shape = CircleShape
                                    )
                            ) {
                                Icon(
                                    painter = painterResource(id = track.iconRes),
                                    contentDescription = track.name,
                                    tint = if (isPlaying) track.accentColor else palette.textSecondary,
                                    modifier = Modifier.size(22.dp)
                                )
                            }

                            Spacer(modifier = Modifier.width(12.dp))

                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = track.name,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = if (isPlaying) palette.textPrimary else palette.textPrimary.copy(alpha = 0.85f)
                                )
                                Text(
                                    text = track.subtitle,
                                    fontSize = 12.sp,
                                    color = palette.textSecondary
                                )
                            }

                            // Play/Stop Toggle Button
                            FilledIconButton(
                                onClick = {
                                    activeTrackIds = if (isPlaying) {
                                        activeTrackIds - track.id
                                    } else {
                                        activeTrackIds + track.id
                                    }
                                },
                                shape = CircleShape,
                                colors = IconButtonDefaults.filledIconButtonColors(
                                    containerColor = if (isPlaying) track.accentColor.copy(alpha = 0.2f) else palette.surfaceVariant.copy(alpha = 0.6f),
                                    contentColor = if (isPlaying) track.accentColor else palette.textSecondary
                                ),
                                modifier = Modifier.size(38.dp)
                            ) {
                                Icon(
                                    painter = painterResource(
                                        id = if (isPlaying) R.drawable.ic_fluent_stop_24_filled else R.drawable.ic_fluent_play_24_regular
                                    ),
                                    contentDescription = if (isPlaying) "Stop" else "Play",
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }

                        // Volume Slider (Active when playing)
                        if (isPlaying) {
                            Spacer(modifier = Modifier.height(10.dp))
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(
                                    painter = painterResource(id = R.drawable.ic_fluent_speaker_2_24_regular),
                                    contentDescription = null,
                                    tint = palette.textSecondary,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Slider(
                                    value = volume,
                                    onValueChange = { trackVolumes[track.id] = it },
                                    colors = SliderDefaults.colors(
                                        thumbColor = track.accentColor,
                                        activeTrackColor = track.accentColor,
                                        inactiveTrackColor = palette.surfaceVariant
                                    ),
                                    modifier = Modifier.weight(1f)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "${(volume * 100).toInt()}%",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = palette.textSecondary,
                                    modifier = Modifier.width(36.dp)
                                )
                            }
                        }
                    }
                }
            }

            // Favorite Song Card
            item {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Quick Launcher",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = palette.primary
                )
                Spacer(modifier = Modifier.height(6.dp))
                MiraCard(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { launchMusic(favoriteSong) }
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(44.dp)
                                .clip(CircleShape)
                                .background(palette.primary.copy(alpha = 0.2f))
                                .border(1.dp, palette.primary.copy(alpha = 0.35f), CircleShape)
                        ) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_fluent_play_24_regular),
                                contentDescription = null,
                                tint = palette.primary,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(14.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Play Favorite Song",
                                fontSize = 15.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = palette.textPrimary
                            )
                            Text(
                                text = "“$favoriteSong” on $musicApp",
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

            // Curated Moods Section
            item {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Curated Music Streams",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = palette.primary
                )
            }

            items(vibes) { vibe ->
                MiraCard(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { launchMusic(vibe.query) }
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(42.dp)
                                .clip(CircleShape)
                                .background(vibe.accentColor.copy(alpha = 0.15f))
                                .border(1.dp, vibe.accentColor.copy(alpha = 0.3f), CircleShape)
                        ) {
                            Icon(
                                painter = painterResource(id = vibe.iconRes),
                                contentDescription = null,
                                tint = vibe.accentColor,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(14.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = vibe.title,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = palette.textPrimary
                            )
                            Text(
                                text = vibe.subtitle,
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
                Spacer(modifier = Modifier.height(20.dp))
            }
        }
    }
}
