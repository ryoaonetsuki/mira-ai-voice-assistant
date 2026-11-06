package io.github.ryoaonetsuki.mira.ui.screens.settings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.SentimentSatisfied
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.ryoaonetsuki.mira.data.preferences.UserPreferences
import io.github.ryoaonetsuki.mira.ui.components.MiraCard
import io.github.ryoaonetsuki.mira.ui.theme.LocalMiraPalette
import kotlinx.coroutines.launch
import java.util.Calendar

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SecretSettingsScreen(
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val palette = LocalMiraPalette.current
    val scope = rememberCoroutineScope()
    val userPrefs = remember { UserPreferences(context) }

    val secretMode by userPrefs.secretModeFlow.collectAsState(initial = "Friendly")
    val isAdultConfirmed by userPrefs.isAdultConfirmedFlow.collectAsState(initial = false)
    val customInstructions by userPrefs.customInstructionsFlow.collectAsState(initial = "")

    var instructionsInput by remember { mutableStateOf(customInstructions) }
    var showAgeGateDialog by remember { mutableStateOf(false) }
    var birthYearInput by remember { mutableStateOf("") }
    var ageGateError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(customInstructions) {
        instructionsInput = customInstructions
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Secret Settings", fontWeight = FontWeight.Bold, color = palette.textPrimary) },
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
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                Text("Companion Closeness & Tone", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = palette.primary)
            }

            // Friendly Mode Card
            item {
                val isFriendly = secretMode == "Friendly"
                MiraCard(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        scope.launch { userPrefs.setSecretMode("Friendly") }
                    }
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Rounded.SentimentSatisfied,
                            contentDescription = null,
                            tint = if (isFriendly) palette.primary else palette.textSecondary,
                            modifier = Modifier.size(28.dp)
                        )
                        Spacer(modifier = Modifier.width(14.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Friendly Mode (Default)",
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (isFriendly) palette.primary else palette.textPrimary
                            )
                            Text(
                                text = "Warm, human-like companion, thoughtful friend & everyday confidante. Speaks with genuine emotion and warmth.",
                                fontSize = 12.sp,
                                color = palette.textSecondary
                            )
                        }
                        RadioButton(
                            selected = isFriendly,
                            onClick = { scope.launch { userPrefs.setSecretMode("Friendly") } },
                            colors = RadioButtonDefaults.colors(selectedColor = palette.primary)
                        )
                    }
                }
            }

            // Romantic Mode Card
            item {
                val isRomantic = secretMode == "Romantic"
                MiraCard(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        if (!isAdultConfirmed) {
                            showAgeGateDialog = true
                        } else {
                            scope.launch { userPrefs.setSecretMode("Romantic") }
                        }
                    }
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Rounded.Favorite,
                            contentDescription = null,
                            tint = if (isRomantic) Color(0xFFF43F5E) else palette.textSecondary,
                            modifier = Modifier.size(28.dp)
                        )
                        Spacer(modifier = Modifier.width(14.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Romantic Companion Mode",
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (isRomantic) Color(0xFFF43F5E) else palette.textPrimary
                            )
                            Text(
                                text = "Affectionate, deeply attentive and caring romantic companion. Speaks like a loving partner with heartfelt warmth. (Age-gated 18+; strictly PG companion tone).",
                                fontSize = 12.sp,
                                color = palette.textSecondary
                            )
                        }
                        RadioButton(
                            selected = isRomantic,
                            onClick = {
                                if (!isAdultConfirmed) {
                                    showAgeGateDialog = true
                                } else {
                                    scope.launch { userPrefs.setSecretMode("Romantic") }
                                }
                            },
                            colors = RadioButtonDefaults.colors(selectedColor = Color(0xFFF43F5E))
                        )
                    }
                }
            }

            item {
                Spacer(modifier = Modifier.height(6.dp))
                Text("Custom Persona Instructions", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = palette.primary)
            }

            item {
                MiraCard(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = "Custom Prompt Injection",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = palette.textPrimary
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "These instructions are injected into Mira's instructions.",
                        fontSize = 12.sp,
                        color = palette.textSecondary
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    OutlinedTextField(
                        value = instructionsInput,
                        onValueChange = {
                            instructionsInput = it
                            scope.launch { userPrefs.setCustomInstructions(it) }
                        },
                        placeholder = { Text("e.g. Always greet me warmly and be playful.") },
                        minLines = 4,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }

        // Age Gate Dialog
        if (showAgeGateDialog) {
            AlertDialog(
                onDismissRequest = { showAgeGateDialog = false },
                title = { Text("Age Confirmation Required") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = "Romantic companion mode requires confirming you are at least 18 years of age. What year were you born?",
                            fontSize = 13.sp,
                            color = palette.textSecondary
                        )
                        OutlinedTextField(
                            value = birthYearInput,
                            onValueChange = {
                                birthYearInput = it
                                ageGateError = null
                            },
                            label = { Text("Birth Year (e.g. 2002)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        if (ageGateError != null) {
                            Text(ageGateError!!, color = Color(0xFFEF4444), fontSize = 12.sp)
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        val year = birthYearInput.toIntOrNull()
                        val currentYear = Calendar.getInstance().get(Calendar.YEAR)
                        if (year != null && (currentYear - year) >= 18 && year in 1900..currentYear) {
                            scope.launch {
                                userPrefs.setAdultConfirmed(true)
                                userPrefs.setSecretMode("Romantic")
                                showAgeGateDialog = false
                                Toast.makeText(context, "Romantic mode enabled", Toast.LENGTH_SHORT).show()
                            }
                        } else {
                            ageGateError = "Must be 18 years or older."
                        }
                    }) {
                        Text("Confirm")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showAgeGateDialog = false }) { Text("Cancel") }
                },
                containerColor = palette.surface,
                titleContentColor = palette.textPrimary,
                textContentColor = palette.textSecondary
            )
        }
    }
}
