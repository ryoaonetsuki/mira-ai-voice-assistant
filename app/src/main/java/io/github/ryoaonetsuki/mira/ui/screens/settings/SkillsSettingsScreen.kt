package io.github.ryoaonetsuki.mira.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.ryoaonetsuki.mira.ui.components.MiraCard
import io.github.ryoaonetsuki.mira.ui.components.MiraSwitch
import io.github.ryoaonetsuki.mira.ui.theme.LocalMiraPalette

data class MiraSkill(
    val id: String,
    val name: String,
    val description: String,
    val icon: ImageVector,
    var isEnabled: Boolean = true
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SkillsSettingsScreen(
    onBack: () -> Unit
) {
    val palette = LocalMiraPalette.current

    val skills = remember {
        mutableStateListOf(
            MiraSkill("web_search", "Web Search Playbook", "Gathers fresh info and web citations from the live internet", Icons.Rounded.Search, true),
            MiraSkill("coding", "Full-Stack Code Agent", "Builds interactive web apps and scripts in app storage", Icons.Rounded.Code, true),
            MiraSkill("memory", "Long-Term Memory Keeper", "Extracts durable personal facts and routines from voice dialogues", Icons.Rounded.AutoAwesome, true),
            MiraSkill("marketing", "Business Post Creator", "Generates high-converting marketing copy and captions", Icons.Rounded.Campaign, true),
            MiraSkill("finance", "Market Watcher", "Fetches live ticker quotes and crypto data via Yahoo Finance", Icons.Rounded.TrendingUp, true),
            MiraSkill("macros", "Android Device Macros", "Executes phone calls, WhatsApp messages, and maps navigation", Icons.Rounded.PhoneAndroid, true)
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Installed Skills", fontWeight = FontWeight.Bold, color = palette.textPrimary) },
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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
        ) {
            Text(
                text = "Skills extend Mira's reasoning and tool-calling capabilities. Enabled skills are active in conversation context.",
                fontSize = 13.sp,
                color = palette.textSecondary
            )

            Spacer(modifier = Modifier.height(14.dp))

            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(skills) { skill ->
                    MiraCard(modifier = Modifier.fillMaxWidth()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = skill.icon,
                                contentDescription = null,
                                tint = palette.primary,
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(modifier = Modifier.width(14.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = skill.name,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = palette.textPrimary
                                )
                                Text(
                                    text = skill.description,
                                    fontSize = 12.sp,
                                    color = palette.textSecondary
                                )
                            }
                            MiraSwitch(
                                checked = skill.isEnabled,
                                onCheckedChange = { checked ->
                                    val idx = skills.indexOfFirst { it.id == skill.id }
                                    if (idx != -1) {
                                        skills[idx] = skills[idx].copy(isEnabled = checked)
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}
