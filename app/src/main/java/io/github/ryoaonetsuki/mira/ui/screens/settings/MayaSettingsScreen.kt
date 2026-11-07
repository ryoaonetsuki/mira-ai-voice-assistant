package io.github.ryoaonetsuki.mira.ui.screens.settings

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
import io.github.ryoaonetsuki.mira.data.model.VoiceCatalog
import io.github.ryoaonetsuki.mira.data.preferences.UserPreferences
import io.github.ryoaonetsuki.mira.ui.components.MiraCard
import io.github.ryoaonetsuki.mira.ui.navigation.Screen
import io.github.ryoaonetsuki.mira.ui.theme.LocalMiraPalette
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MayaSettingsScreen(
    onNavigate: (String) -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val palette = LocalMiraPalette.current
    val scope = rememberCoroutineScope()
    val userPrefs = remember { UserPreferences(context) }

    val persona by userPrefs.personaFlow.collectAsState(initial = "Mira")
    val assistantName by userPrefs.assistantNameFlow.collectAsState(initial = "Mira")
    val language by userPrefs.languageFlow.collectAsState(initial = "Bangla (Bengali)")

    var showNameDialog by remember { mutableStateOf(false) }
    var nameInput by remember { mutableStateOf(assistantName) }

    var showLanguageDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Mira & Persona",
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
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "Persona Archetype",
                    fontSize = 13.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = palette.primary
                )

                Spacer(modifier = Modifier.height(6.dp))

                // Persona Cards (Mira and Orion)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    val isMira = persona == "Mira"
                    MiraCard(
                        modifier = Modifier
                            .weight(1f)
                            .then(
                                if (isMira) Modifier.border(1.5.dp, palette.primary, RoundedCornerShape(16.dp))
                                else Modifier
                            ),
                        onClick = {
                            scope.launch {
                                userPrefs.setPersona("Mira")
                                userPrefs.setVoiceName("Aoede")
                            }
                        }
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = "Mira (Default)",
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (isMira) palette.primary else palette.textPrimary,
                                modifier = Modifier.weight(1f)
                            )
                            if (isMira) {
                                Icon(
                                    painter = painterResource(id = R.drawable.ic_fluent_checkmark_circle_24_filled),
                                    contentDescription = "Selected",
                                    tint = palette.primary,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "Warm, empathetic companion. Default voice: Aoede.",
                            fontSize = 12.sp,
                            color = palette.textSecondary,
                            lineHeight = 16.sp
                        )
                    }

                    val isOrion = persona == "Orion"
                    MiraCard(
                        modifier = Modifier
                            .weight(1f)
                            .then(
                                if (isOrion) Modifier.border(1.5.dp, palette.secondary, RoundedCornerShape(16.dp))
                                else Modifier
                            ),
                        onClick = {
                            scope.launch {
                                userPrefs.setPersona("Orion")
                                userPrefs.setVoiceName("Charon")
                            }
                        }
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = "Orion",
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (isOrion) palette.secondary else palette.textPrimary,
                                modifier = Modifier.weight(1f)
                            )
                            if (isOrion) {
                                Icon(
                                    painter = painterResource(id = R.drawable.ic_fluent_checkmark_circle_24_filled),
                                    contentDescription = "Selected",
                                    tint = palette.secondary,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "Sharp, focused & concise. Default voice: Charon.",
                            fontSize = 12.sp,
                            color = palette.textSecondary,
                            lineHeight = 16.sp
                        )
                    }
                }
            }

            item {
                Text(
                    text = "Identity & Communication",
                    fontSize = 13.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = palette.primary
                )

                Spacer(modifier = Modifier.height(6.dp))

                // Assistant Name Card
                MiraCard(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        nameInput = assistantName
                        showNameDialog = true
                    }
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(38.dp)
                                .clip(CircleShape)
                                .background(palette.primary.copy(alpha = 0.15f))
                        ) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_fluent_bot_sparkle_24_regular),
                                contentDescription = null,
                                tint = palette.primary,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Assistant Name",
                                fontSize = 14.5.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = palette.textPrimary
                            )
                            Text(
                                text = "What you call her: “$assistantName”",
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

                Spacer(modifier = Modifier.height(10.dp))

                // Spoken Language Card
                MiraCard(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { showLanguageDialog = true }
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(38.dp)
                                .clip(CircleShape)
                                .background(palette.secondary.copy(alpha = 0.15f))
                        ) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_fluent_globe_24_regular),
                                contentDescription = null,
                                tint = palette.secondary,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Spoken Language",
                                fontSize = 14.5.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = palette.textPrimary
                            )
                            Text(
                                text = language,
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

            // Secret Settings Shortcut Card
            item {
                MiraCard(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { onNavigate(Screen.SettingsSecret.route) }
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(38.dp)
                                .clip(CircleShape)
                                .background(Color(0xFFF43F5E).copy(alpha = 0.15f))
                        ) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_fluent_heart_24_regular),
                                contentDescription = null,
                                tint = Color(0xFFF43F5E),
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Secret Settings",
                                fontSize = 14.5.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = palette.textPrimary
                            )
                            Text(
                                text = "Friendly vs Romantic companion mode with age gate",
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

        // Rename Dialog
        if (showNameDialog) {
            AlertDialog(
                onDismissRequest = { showNameDialog = false },
                title = { Text("Rename Assistant", fontWeight = FontWeight.Bold) },
                text = {
                    OutlinedTextField(
                        value = nameInput,
                        onValueChange = { nameInput = it },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        if (nameInput.isNotBlank()) {
                            scope.launch { userPrefs.setAssistantName(nameInput.trim()) }
                            showNameDialog = false
                        }
                    }) {
                        Text("Save", fontWeight = FontWeight.Bold, color = palette.primary)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showNameDialog = false }) {
                        Text("Cancel", color = palette.textSecondary)
                    }
                },
                containerColor = palette.surface,
                titleContentColor = palette.textPrimary,
                textContentColor = palette.textSecondary
            )
        }

        // Language Picker Dialog
        if (showLanguageDialog) {
            AlertDialog(
                onDismissRequest = { showLanguageDialog = false },
                title = { Text("Select Spoken Language", fontWeight = FontWeight.Bold) },
                text = {
                    LazyColumn(modifier = Modifier.height(300.dp)) {
                        items(VoiceCatalog.supportedLanguages) { lang ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        scope.launch { userPrefs.setLanguage(lang) }
                                        showLanguageDialog = false
                                    }
                                    .padding(vertical = 10.dp)
                            ) {
                                Text(
                                    text = lang,
                                    fontSize = 14.sp,
                                    fontWeight = if (language == lang) FontWeight.Bold else FontWeight.Normal,
                                    color = if (language == lang) palette.primary else palette.textPrimary
                                )
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showLanguageDialog = false }) {
                        Text("Close", color = palette.textSecondary)
                    }
                },
                containerColor = palette.surface,
                titleContentColor = palette.textPrimary,
                textContentColor = palette.textSecondary
            )
        }
    }
}
