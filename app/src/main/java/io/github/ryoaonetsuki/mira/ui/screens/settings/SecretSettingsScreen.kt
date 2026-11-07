package io.github.ryoaonetsuki.mira.ui.screens.settings

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
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
                title = {
                    Text(
                        text = "Secret Settings",
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
                    text = "Companion Closeness & Tone",
                    fontSize = 13.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = palette.primary
                )

                Spacer(modifier = Modifier.height(6.dp))

                // Friendly Mode Card
                val isFriendly = secretMode == "Friendly"
                MiraCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(
                            if (isFriendly) Modifier.border(1.5.dp, palette.primary, RoundedCornerShape(16.dp))
                            else Modifier
                        ),
                    onClick = {
                        scope.launch { userPrefs.setSecretMode("Friendly") }
                    }
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(if (isFriendly) palette.primary.copy(alpha = 0.2f) else palette.surfaceVariant)
                        ) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_fluent_emoji_24_regular),
                                contentDescription = null,
                                tint = if (isFriendly) palette.primary else palette.textSecondary,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(14.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Friendly Mode (Default)",
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (isFriendly) palette.primary else palette.textPrimary
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "Warm, human-like companion, thoughtful friend & everyday confidante. Speaks with genuine emotion and warmth.",
                                fontSize = 12.sp,
                                color = palette.textSecondary,
                                lineHeight = 16.sp
                            )
                        }
                        RadioButton(
                            selected = isFriendly,
                            onClick = { scope.launch { userPrefs.setSecretMode("Friendly") } },
                            colors = RadioButtonDefaults.colors(selectedColor = palette.primary)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Romantic Mode Card
                val isRomantic = secretMode == "Romantic"
                MiraCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(
                            if (isRomantic) Modifier.border(1.5.dp, Color(0xFFF43F5E), RoundedCornerShape(16.dp))
                            else Modifier
                        ),
                    onClick = {
                        if (!isAdultConfirmed) {
                            showAgeGateDialog = true
                        } else {
                            scope.launch { userPrefs.setSecretMode("Romantic") }
                        }
                    }
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(if (isRomantic) Color(0xFFF43F5E).copy(alpha = 0.2f) else palette.surfaceVariant)
                        ) {
                            Icon(
                                painter = painterResource(
                                    id = if (isRomantic) R.drawable.ic_fluent_heart_24_filled else R.drawable.ic_fluent_heart_24_regular
                                ),
                                contentDescription = null,
                                tint = if (isRomantic) Color(0xFFF43F5E) else palette.textSecondary,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(14.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Romantic Companion Mode",
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (isRomantic) Color(0xFFF43F5E) else palette.textPrimary
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "Affectionate, deeply attentive and caring romantic companion. Speaks like a loving partner with heartfelt warmth. (Age-gated 18+; strictly PG companion tone).",
                                fontSize = 12.sp,
                                color = palette.textSecondary,
                                lineHeight = 16.sp
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

            // Custom Persona Instructions Card
            item {
                Text(
                    text = "Custom Persona Instructions",
                    fontSize = 13.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = palette.primary
                )

                Spacer(modifier = Modifier.height(6.dp))

                MiraCard(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = "System Prompt Customization",
                        fontSize = 14.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = palette.textPrimary
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "These instructions are injected into Mira's internal personality prompt on every turn.",
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
                        placeholder = { Text("e.g. Always greet me warmly and keep answers thoughtful and concise.") },
                        minLines = 4,
                        shape = RoundedCornerShape(12.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = palette.primary,
                            unfocusedBorderColor = palette.borderSubtle
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            item {
                Spacer(modifier = Modifier.height(20.dp))
            }
        }

        // Age Gate Dialog
        if (showAgeGateDialog) {
            AlertDialog(
                onDismissRequest = { showAgeGateDialog = false },
                title = { Text("Age Confirmation Required", fontWeight = FontWeight.Bold) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            text = "Romantic companion mode requires confirming you are at least 18 years of age. What year were you born?",
                            fontSize = 13.sp,
                            color = palette.textSecondary,
                            lineHeight = 17.sp
                        )
                        OutlinedTextField(
                            value = birthYearInput,
                            onValueChange = {
                                birthYearInput = it
                                ageGateError = null
                            },
                            label = { Text("Birth Year (e.g. 2002)") },
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp),
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
                        Text("Confirm", fontWeight = FontWeight.Bold, color = Color(0xFFF43F5E))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showAgeGateDialog = false }) {
                        Text("Cancel", color = palette.textSecondary)
                    }
                },
                containerColor = palette.surface,
                titleContentColor = palette.textPrimary,
                textContentColor = palette.textSecondary
            )
        }
    }
}
