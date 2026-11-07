package io.github.ryoaonetsuki.mira.ui.screens

import androidx.annotation.DrawableRes
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
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
                title = { Text("Vibes & Audio", fontWeight = FontWeight.Bold, color = palette.textPrimary) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(painter = painterResource(id = R.drawable.ic_fluent_arrow_left_24_regular), contentDescription = "Back", tint = palette.textPrimary)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = palette.background)
            )
        },
        containerColor = palette.background
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
        ) {
            // Favorite Song Card
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
                            fontWeight = FontWeight.Bold,
                            color = palette.textPrimary
                        )
                        Text(
                            text = "\"$favoriteSong\" on $musicApp",
                            fontSize = 12.sp,
                            color = palette.textSecondary
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "Curated Moods",
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                color = palette.textPrimary
            )

            Spacer(modifier = Modifier.height(10.dp))

            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
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
            }
        }
    }
}
