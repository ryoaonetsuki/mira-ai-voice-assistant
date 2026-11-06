package io.github.ryoaonetsuki.mira.ui.screens.settings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Backup
import androidx.compose.material.icons.rounded.Chat
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Email
import androidx.compose.material.icons.rounded.Face
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Tune
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
        SettingsHubRow("Mira & Persona", "Persona, assistant name, language, driving mode", Icons.Rounded.Face, Screen.SettingsMaya.route, "Assistant"),
        SettingsHubRow("Personal Profile", "Your name, API key, music app & song", Icons.Rounded.Person, Screen.SettingsPersonal.route, "Assistant"),
        SettingsHubRow("Secret Settings", "Friendly vs Romantic tone with age confirmation", Icons.Rounded.Favorite, Screen.SettingsSecret.route, "Assistant"),
        SettingsHubRow("Voice Engine", "Live capability, diagnostics, 30 voices", Icons.Rounded.GraphicEq, Screen.SettingsVoice.route, "Voice"),
        SettingsHubRow("Advanced & Security", "Themes, orb styles, Face Lock, SOS, Touch Guard", Icons.Rounded.Security, Screen.SettingsAdvanced.route, "System"),
        SettingsHubRow("Connectors", "GitHub, Telegram, Notion, Spotify, Linear, Todoist", Icons.Rounded.Link, Screen.SettingsConnectors.route, "Integrations"),
        SettingsHubRow("Email", "Send-as address, signature, AI email composer", Icons.Rounded.Email, Screen.SettingsEmail.route, "Integrations"),
        SettingsHubRow("Messages & WhatsApp", "Auto-reply template, Notification listener", Icons.Rounded.Chat, Screen.SettingsMessages.route, "Integrations"),
        SettingsHubRow("Social Media", "Handle, caption voice, story ideas", Icons.Rounded.Share, Screen.SettingsSocial.route, "Integrations"),
        SettingsHubRow("Optional Integrations", "Image provider, Maps key, Web search engines", Icons.Rounded.Tune, Screen.SettingsOptional.route, "Integrations"),
        SettingsHubRow("Skills", "Installed playbooks & tool capabilities", Icons.Rounded.AutoAwesome, Screen.SettingsSkills.route, "System"),
        SettingsHubRow("Backup & Restore", "Export & restore memories and chats via SAF", Icons.Rounded.Backup, Screen.SettingsBackup.route, "System"),
        SettingsHubRow("About Mira AI", "Version, developer info, open-source licenses", Icons.Rounded.Info, Screen.About.route, "System")
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings", fontWeight = FontWeight.Bold, color = palette.textPrimary) },
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
                        imageVector = Icons.Rounded.ChevronRight,
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
