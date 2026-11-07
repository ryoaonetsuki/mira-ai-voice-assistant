package io.github.ryoaonetsuki.mira.ui.screens.settings

import androidx.annotation.DrawableRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.ryoaonetsuki.mira.R
import io.github.ryoaonetsuki.mira.data.preferences.UserPreferences
import io.github.ryoaonetsuki.mira.ui.components.MiraCard
import io.github.ryoaonetsuki.mira.ui.navigation.Screen
import io.github.ryoaonetsuki.mira.ui.theme.LocalMiraPalette
import kotlinx.coroutines.launch

data class SettingsHubRow(
    val title: String,
    val subtitle: String,
    @DrawableRes val iconRes: Int,
    val route: String
)

data class SettingsHubGroup(
    val categoryName: String,
    val items: List<SettingsHubRow>
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsHubScreen(
    onNavigate: (String) -> Unit,
    onBack: () -> Unit
) {
    val palette = LocalMiraPalette.current

    val groups = listOf(
        SettingsHubGroup(
            categoryName = "Profile & Persona",
            items = listOf(
                SettingsHubRow("Mira & Persona", "Persona, assistant name, primary language", R.drawable.ic_fluent_bot_sparkle_24_regular, Screen.SettingsMaya.route),
                SettingsHubRow("Personal Profile", "Your name, API key, music app & favorite song", R.drawable.ic_fluent_person_24_regular, Screen.SettingsPersonal.route),
                SettingsHubRow("Secret Settings", "Friendly vs Romantic tone with age confirmation", R.drawable.ic_fluent_heart_24_regular, Screen.SettingsSecret.route)
            )
        ),
        SettingsHubGroup(
            categoryName = "Intelligence & Voice",
            items = listOf(
                SettingsHubRow("Voice Engine", "Live capability, diagnostics, 30 voices", R.drawable.ic_fluent_sound_wave_circle_24_regular, Screen.SettingsVoice.route)
            )
        ),
        SettingsHubGroup(
            categoryName = "Integrations & Tools",
            items = listOf(
                SettingsHubRow("Connectors", "GitHub, Telegram, Notion, Spotify, Linear, Todoist", R.drawable.ic_fluent_link_24_regular, Screen.SettingsConnectors.route),
                SettingsHubRow("Email", "Send-as address, signature, AI email composer", R.drawable.ic_fluent_mail_24_regular, Screen.SettingsEmail.route),
                SettingsHubRow("Messages & WhatsApp", "Auto-reply template, Notification listener", R.drawable.ic_fluent_chat_24_regular, Screen.SettingsMessages.route),
                SettingsHubRow("Social Media", "Social handle, caption voice", R.drawable.ic_fluent_share_24_regular, Screen.SettingsSocial.route),
                SettingsHubRow("Optional Integrations", "Google Maps API key", R.drawable.ic_fluent_options_24_regular, Screen.SettingsOptional.route)
            )
        ),
        SettingsHubGroup(
            categoryName = "System & Privacy",
            items = listOf(
                SettingsHubRow("Advanced & Security", "Orb styles, Face Lock, SOS contacts", R.drawable.ic_fluent_shield_keyhole_24_regular, Screen.SettingsAdvanced.route),
                SettingsHubRow("Backup & Restore", "Export & restore memories and rules via SAF JSON", R.drawable.ic_fluent_cloud_sync_24_regular, Screen.SettingsBackup.route)
            )
        )
    )

    val context = LocalContext.current
    val userPrefs = remember { UserPreferences(context) }
    val scope = rememberCoroutineScope()
    val themeMode by userPrefs.themeModeFlow.collectAsState(initial = "System")

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Settings",
                        fontSize = 19.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = palette.textPrimary
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            painter = painterResource(R.drawable.ic_fluent_arrow_left_24_regular),
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
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "Preferences & Configurations",
                    fontSize = 13.sp,
                    color = palette.textSecondary
                )
            }

            // Appearance & Theme Mode Selector
            item {
                Text(
                    text = "Appearance",
                    fontSize = 13.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = palette.primary,
                    modifier = Modifier.padding(start = 4.dp, bottom = 6.dp)
                )

                MiraCard(
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(16.dp)
                ) {
                    Column {
                        Text(
                            text = "App Theme",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = palette.textPrimary
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = when (themeMode.lowercase()) {
                                "dark" -> "Always Dark"
                                "light" -> "Always Light"
                                else -> "Follow System (${if (palette.isDark) "Dark" else "Light"})"
                            },
                            fontSize = 12.sp,
                            color = palette.textSecondary
                        )

                        Spacer(modifier = Modifier.height(14.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            listOf(
                                Triple("System", "System", R.drawable.ic_fluent_phone_24_regular),
                                Triple("Light", "Light", R.drawable.ic_fluent_weather_sunny_24_regular),
                                Triple("Dark", "Dark", R.drawable.ic_fluent_weather_moon_24_regular)
                            ).forEach { (mode, label, iconRes) ->
                                val isSelected = themeMode.equals(mode, ignoreCase = true)
                                Surface(
                                    onClick = { scope.launch { userPrefs.setThemeMode(mode) } },
                                    modifier = Modifier.weight(1f),
                                    shape = RoundedCornerShape(12.dp),
                                    color = if (isSelected) palette.primary.copy(alpha = 0.15f) else palette.surfaceVariant.copy(alpha = 0.4f),
                                    border = BorderStroke(
                                        width = if (isSelected) 1.5.dp else 1.dp,
                                        color = if (isSelected) palette.primary else palette.borderSubtle
                                    )
                                ) {
                                    Row(
                                        modifier = Modifier.padding(vertical = 10.dp, horizontal = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.Center
                                    ) {
                                        Icon(
                                            painter = painterResource(id = iconRes),
                                            contentDescription = label,
                                            modifier = Modifier.size(16.dp),
                                            tint = if (isSelected) palette.primary else palette.textSecondary
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = label,
                                            fontSize = 12.5.sp,
                                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                                            color = if (isSelected) palette.primary else palette.textPrimary
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            for (group in groups) {
                item {
                    Text(
                        text = group.categoryName,
                        fontSize = 13.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = palette.primary,
                        modifier = Modifier.padding(start = 4.dp, bottom = 6.dp)
                    )

                    MiraCard(
                        modifier = Modifier.fillMaxWidth(),
                        contentPadding = PaddingValues(0.dp)
                    ) {
                        group.items.forEachIndexed { index, row ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onNavigate(row.route) }
                                    .padding(horizontal = 14.dp, vertical = 13.dp)
                            ) {
                                Box(
                                    contentAlignment = Alignment.Center,
                                    modifier = Modifier
                                        .size(38.dp)
                                        .clip(CircleShape)
                                        .background(palette.primary.copy(alpha = 0.15f))
                                ) {
                                    Icon(
                                        painter = painterResource(row.iconRes),
                                        contentDescription = row.title,
                                        tint = palette.primary,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }

                                Spacer(modifier = Modifier.width(14.dp))

                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = row.title,
                                        fontSize = 14.5.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = palette.textPrimary
                                    )
                                    Text(
                                        text = row.subtitle,
                                        fontSize = 12.sp,
                                        color = palette.textSecondary,
                                        lineHeight = 16.sp
                                    )
                                }

                                Icon(
                                    painter = painterResource(R.drawable.ic_fluent_chevron_right_24_regular),
                                    contentDescription = null,
                                    tint = palette.textSecondary.copy(alpha = 0.7f),
                                    modifier = Modifier.size(18.dp)
                                )
                            }

                            if (index < group.items.size - 1) {
                                HorizontalDivider(
                                    color = palette.borderSubtle.copy(alpha = 0.6f),
                                    modifier = Modifier.padding(start = 66.dp, end = 14.dp)
                                )
                            }
                        }
                    }
                }
            }

            item {
                Spacer(modifier = Modifier.height(8.dp))
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 24.dp)
                ) {
                    Text(
                        text = "Mira AI • v1.0.0 (Build 1)",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = palette.textSecondary
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "Developed by Salman Biswas",
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = palette.primary
                    )
                }
            }
        }
    }
}
