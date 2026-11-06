package io.github.ryoaonetsuki.mira.ui.screens.settings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import io.github.ryoaonetsuki.mira.ui.components.MiraSwitch
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.ryoaonetsuki.mira.data.model.PersonaType
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
    val drivingMode by userPrefs.drivingModeFlow.collectAsState(initial = false)
    val sleepTimeout by userPrefs.sleepTimeoutFlow.collectAsState(initial = 5)

    var showNameDialog by remember { mutableStateOf(false) }
    var nameInput by remember { mutableStateOf(assistantName) }

    var showLanguageDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Mira & Persona", fontWeight = FontWeight.Bold, color = palette.textPrimary) },
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
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Text(
                    text = "Persona Archetype",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = palette.primary
                )
            }

            // Persona Cards (Only 2: Mira and Orion)
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    val isMira = persona == "Mira"
                    MiraCard(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(14.dp)),
                        onClick = {
                            scope.launch {
                                userPrefs.setPersona("Mira")
                                userPrefs.setVoiceName("Aoede")
                            }
                        }
                    ) {
                        Text(
                            text = "Mira (Default)",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (isMira) palette.primary else palette.textPrimary
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Female, warm companion. Default voice: Aoede.",
                            fontSize = 11.sp,
                            color = palette.textSecondary
                        )
                    }

                    val isOrion = persona == "Orion"
                    MiraCard(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(14.dp)),
                        onClick = {
                            scope.launch {
                                userPrefs.setPersona("Orion")
                                userPrefs.setVoiceName("Charon")
                            }
                        }
                    ) {
                        Text(
                            text = "Orion",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (isOrion) palette.primary else palette.textPrimary
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Male, sharp & concise. Default voice: Charon.",
                            fontSize = 11.sp,
                            color = palette.textSecondary
                        )
                    }
                }
            }

            item {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "Identity & Communication",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = palette.primary
                )
            }

            // Assistant Name
            item {
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
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Assistant Name", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = palette.textPrimary)
                            Text("What you call her: \"$assistantName\"", fontSize = 12.sp, color = palette.textSecondary)
                        }
                        Icon(Icons.Rounded.ChevronRight, contentDescription = null, tint = palette.textSecondary)
                    }
                }
            }

            // Spoken Language
            item {
                MiraCard(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { showLanguageDialog = true }
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Spoken Language", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = palette.textPrimary)
                            Text(language, fontSize = 12.sp, color = palette.textSecondary)
                        }
                        Icon(Icons.Rounded.ChevronRight, contentDescription = null, tint = palette.textSecondary)
                    }
                }
            }

            // Driving Mode
            item {
                MiraCard(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Driving Mode", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = palette.textPrimary)
                            Text("Auto-reject calls and announce who is calling", fontSize = 12.sp, color = palette.textSecondary)
                        }
                        MiraSwitch(
                            checked = drivingMode,
                            onCheckedChange = { scope.launch { userPrefs.setDrivingMode(it) } }
                        )
                    }
                }
            }

            // Secret Settings Shortcut
            item {
                MiraCard(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { onNavigate(Screen.SettingsSecret.route) }
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Secret Settings", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = palette.primary)
                            Text("Friendly vs Romantic companion mode with age gate", fontSize = 12.sp, color = palette.textSecondary)
                        }
                        Icon(Icons.Rounded.ChevronRight, contentDescription = null, tint = palette.primary)
                    }
                }
            }
        }

        // Rename Dialog
        if (showNameDialog) {
            AlertDialog(
                onDismissRequest = { showNameDialog = false },
                title = { Text("Rename Assistant") },
                text = {
                    OutlinedTextField(
                        value = nameInput,
                        onValueChange = { nameInput = it },
                        singleLine = true,
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
                        Text("Save")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showNameDialog = false }) { Text("Cancel") }
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
                title = { Text("Select Spoken Language") },
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
                                    color = if (language == lang) palette.primary else palette.textPrimary
                                )
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showLanguageDialog = false }) { Text("Close") }
                },
                containerColor = palette.surface,
                titleContentColor = palette.textPrimary,
                textContentColor = palette.textSecondary
            )
        }
    }
}
