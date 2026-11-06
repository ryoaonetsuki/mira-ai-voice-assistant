package io.github.ryoaonetsuki.mira.ui.screens.settings

import io.github.ryoaonetsuki.mira.ui.icons.Lucide
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.ryoaonetsuki.mira.ui.navigation.Screen
import io.github.ryoaonetsuki.mira.ui.theme.LocalMiraPalette

data class SettingsHubRow(
    val title: String,
    val subtitle: String,
    val icon: ImageVector,
    val route: String,
    val group: String
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsHubScreen(
    onNavigate: (String) -> Unit,
    onBack: () -> Unit
) {
    val palette = LocalMiraPalette.current

    val rows = listOf(
        SettingsHubRow("Mira & Persona", "Persona, assistant name, language, driving mode", Lucide.faceSlightlySmiling, Screen.SettingsMaya.route, "Assistant"),
        SettingsHubRow("Personal Profile", "Your name, API key, music app & song", Lucide.user, Screen.SettingsPersonal.route, "Assistant"),
        SettingsHubRow("Secret Settings", "Friendly vs Romantic tone with age confirmation", Lucide.heart, Screen.SettingsSecret.route, "Assistant"),
        SettingsHubRow("Voice Engine", "Mind/Soul/Swift models, 30 voices, Deepgram key", Lucide.audioLines, Screen.SettingsVoice.route, "Voice"),
        SettingsHubRow("Advanced & Security", "Themes, orb styles, Face Lock, SOS, Touch Guard", Lucide.shieldCheck, Screen.SettingsAdvanced.route, "System"),
        SettingsHubRow("Connectors", "GitHub, Telegram, Notion, Spotify, Linear, Todoist", Lucide.link, Screen.SettingsConnectors.route, "Integrations"),
        SettingsHubRow("Email", "Send-as address, signature, AI email composer", Lucide.mail, Screen.SettingsEmail.route, "Integrations"),
        SettingsHubRow("Messages & WhatsApp", "Auto-reply template, Notification listener", Lucide.messageCircle, Screen.SettingsMessages.route, "Integrations"),
        SettingsHubRow("Social Media", "Handle, caption voice, story ideas", Lucide.share2, Screen.SettingsSocial.route, "Integrations"),
        SettingsHubRow("Optional Integrations", "Image provider, Maps key, Web search engines", Lucide.slidersHorizontal, Screen.SettingsOptional.route, "Integrations"),
        SettingsHubRow("Skills", "Installed playbooks & tool capabilities", Lucide.sparkles, Screen.SettingsSkills.route, "System"),
        SettingsHubRow("Backup & Restore", "Export & restore memories and chats via SAF", Lucide.cloudUpload, Screen.SettingsBackup.route, "System"),
        SettingsHubRow("About Mira AI", "Version, developer info, open-source licenses", Lucide.info, Screen.About.route, "System")
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings", fontWeight = FontWeight.Bold, color = palette.textPrimary) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Lucide.arrowLeft, contentDescription = "Back", tint = palette.textPrimary)
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
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item {
                Text(
                    text = "Preferences & Configurations",
                    fontSize = 13.sp,
                    color = palette.textSecondary,
                    modifier = Modifier.padding(bottom = 6.dp)
                )
            }

            items(rows) { row ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(palette.surface)
                        .clickable { onNavigate(row.route) }
                        .padding(14.dp)
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(38.dp)
                            .clip(CircleShape)
                            .background(palette.primary.copy(alpha = 0.15f))
                    ) {
                        Icon(
                            imageVector = row.icon,
                            contentDescription = row.title,
                            tint = palette.primary,
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(14.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = row.title,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = palette.textPrimary
                        )
                        Text(
                            text = row.subtitle,
                            fontSize = 12.sp,
                            color = palette.textSecondary
                        )
                    }

                    Icon(
                        imageVector = Lucide.chevronRight,
                        contentDescription = null,
                        tint = palette.textSecondary
                    )
                }
            }

            item {
                Spacer(modifier = Modifier.height(16.dp))
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 20.dp)
                ) {
                    Text("Mira AI • v1.0.0 (Build 1)", fontSize = 12.sp, color = palette.textSecondary)
                    Text("Developed by Salman Biswas", fontSize = 11.sp, color = palette.primary)
                }
            }
        }
    }
}
