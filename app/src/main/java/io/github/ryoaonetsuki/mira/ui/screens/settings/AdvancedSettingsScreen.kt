package io.github.ryoaonetsuki.mira.ui.screens.settings

import androidx.compose.ui.res.painterResource
import io.github.ryoaonetsuki.mira.R
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import io.github.ryoaonetsuki.mira.ui.components.MiraSwitch
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.FragmentActivity
import io.github.ryoaonetsuki.mira.data.local.MiraDatabase
import io.github.ryoaonetsuki.mira.data.local.entities.SosContactEntity
import io.github.ryoaonetsuki.mira.data.preferences.SecurePreferences
import io.github.ryoaonetsuki.mira.data.preferences.UserPreferences
import io.github.ryoaonetsuki.mira.service.MiraAccessibilityService
import io.github.ryoaonetsuki.mira.ui.components.BiometricAuthHelper
import io.github.ryoaonetsuki.mira.ui.components.MiraCard
import io.github.ryoaonetsuki.mira.ui.theme.LocalMiraPalette
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdvancedSettingsScreen(
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val palette = LocalMiraPalette.current
    val scope = rememberCoroutineScope()
    val userPrefs = remember { UserPreferences(context) }
    val securePrefs = remember { SecurePreferences(context) }
    val db = remember { MiraDatabase.getInstance(context) }

    val themePalette by userPrefs.themePaletteFlow.collectAsState(initial = "Midnight")
    val orbStyle by userPrefs.orbStyleFlow.collectAsState(initial = "Mira Neon")

    val sosContacts by db.sosContactDao().getAllContacts().collectAsState(initial = emptyList())

    var isFaceLockActive by remember { mutableStateOf(securePrefs.isFaceLockEnabled()) }

    var isAccessibilityEnabled by remember {
        mutableStateOf(MiraAccessibilityService.isEnabled(context))
    }

    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                isAccessibilityEnabled = MiraAccessibilityService.isEnabled(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    var showSosDialog by remember { mutableStateOf(false) }
    var sosNameInput by remember { mutableStateOf("") }
    var sosPhoneInput by remember { mutableStateOf("") }

    val themesList = listOf("Midnight", "Obsidian", "Nocturne", "Ember", "Abyss", "Rosewood", "Daylight")
    val orbStylesList = listOf("Mira Neon", "Cyan Core", "Pulse Wave", "Deep Violet")

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Advanced & Security", fontWeight = FontWeight.Bold, color = palette.textPrimary) },
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
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Theme Palettes
            item {
                Text("Color Palette (7 Themes)", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = palette.primary)
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    for (t in themesList.take(4)) {
                        FilterChip(
                            selected = themePalette == t,
                            onClick = { scope.launch { userPrefs.setThemePalette(t) } },
                            label = { Text(t, fontSize = 11.sp) },
                            colors = FilterChipDefaults.filterChipColors(selectedContainerColor = palette.primary, selectedLabelColor = Color.Black)
                        )
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    for (t in themesList.drop(4)) {
                        FilterChip(
                            selected = themePalette == t,
                            onClick = { scope.launch { userPrefs.setThemePalette(t) } },
                            label = { Text(t, fontSize = 11.sp) },
                            colors = FilterChipDefaults.filterChipColors(selectedContainerColor = palette.primary, selectedLabelColor = Color.Black)
                        )
                    }
                }
            }

            // Orb Style
            item {
                Text("Orb Style Visuals", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = palette.primary)
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    for (style in orbStylesList) {
                        FilterChip(
                            selected = orbStyle == style,
                            onClick = { scope.launch { userPrefs.setOrbStyle(style) } },
                            label = { Text(style, fontSize = 11.sp) },
                            colors = FilterChipDefaults.filterChipColors(selectedContainerColor = palette.primary, selectedLabelColor = Color.Black)
                        )
                    }
                }
            }

            // Behaviour Toggles
            item {
                Text("Device Behaviour", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = palette.primary)
            }



            // Automation & Accessibility
            item {
                Text("Automation & Accessibility", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = palette.primary)
            }

            item {
                MiraCard(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Accessibility Service (Macros)", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = palette.textPrimary)
                            Text(
                                if (isAccessibilityEnabled) "Active • Screen automation enabled"
                                else "Disabled • Required for WhatsApp & YouTube screen automation",
                                fontSize = 12.sp,
                                color = if (isAccessibilityEnabled) Color(0xFF10B981) else palette.textSecondary
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(
                            onClick = {
                                val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                                context.startActivity(intent)
                            },
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isAccessibilityEnabled) palette.surfaceVariant else palette.primary
                            ),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Text(
                                if (isAccessibilityEnabled) "Configure" else "Enable",
                                fontSize = 12.sp,
                                color = if (isAccessibilityEnabled) palette.primary else Color.Black
                            )
                        }
                    }
                }
            }

            // Security: Face Lock & Biometrics
            item {
                Text("Security & Face Lock", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = palette.primary)
            }

            item {
                MiraCard(modifier = Modifier.fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Face Lock / Biometrics", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = palette.textPrimary)
                            Text("Gate Memories & Chat behind BiometricPrompt / Device PIN", fontSize = 12.sp, color = palette.textSecondary)
                        }
                        MiraSwitch(
                            checked = isFaceLockActive,
                            onCheckedChange = { active ->
                                if (active && context is FragmentActivity) {
                                    BiometricAuthHelper.promptBiometric(
                                        activity = context,
                                        title = "Enable Face / Biometric Lock",
                                        onSuccess = {
                                            isFaceLockActive = true
                                            securePrefs.setFaceLockEnabled(true)
                                        },
                                        onError = { Toast.makeText(context, "Auth cancelled", Toast.LENGTH_SHORT).show() }
                                    )
                                } else {
                                    isFaceLockActive = false
                                    securePrefs.setFaceLockEnabled(false)
                                }
                            }
                        )
                    }
                }
            }

            // Touch Guard

            // Emergency SOS
            item {
                Text("Emergency SOS", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = palette.primary)
            }

            item {
                MiraCard(modifier = Modifier.fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("SOS Contacts (${sosContacts.size})", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = palette.textPrimary)
                            Text("Saved emergency contacts (stored on this device)", fontSize = 12.sp, color = palette.textSecondary)
                        }
                        Surface(
                            color = palette.primary.copy(alpha = 0.15f),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .clickable { showSosDialog = true }
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                            ) {
                                Icon(
                                    painter = painterResource(id = R.drawable.ic_fluent_person_add_24_regular),
                                    contentDescription = null,
                                    tint = palette.primary,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = "Add",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = palette.primary
                                )
                            }
                        }
                    }

                    for (c in sosContacts) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                        ) {
                            Text("${c.name} (${c.phone})", fontSize = 13.sp, color = palette.textPrimary, modifier = Modifier.weight(1f))
                            IconButton(onClick = { scope.launch { db.sosContactDao().deleteContact(c) } }) {
                                Icon(
                                    painter = painterResource(id = R.drawable.ic_fluent_dismiss_24_regular),
                                    contentDescription = "Delete",
                                    tint = palette.textSecondary,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }
                }
            }
        }

        // Add SOS Dialog
        if (showSosDialog) {
            AlertDialog(
                onDismissRequest = { showSosDialog = false },
                title = { Text("Add SOS Contact") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = sosNameInput,
                            onValueChange = { sosNameInput = it },
                            label = { Text("Contact Name") },
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = sosPhoneInput,
                            onValueChange = { sosPhoneInput = it },
                            label = { Text("Phone Number") },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        if (sosNameInput.isNotBlank() && sosPhoneInput.isNotBlank()) {
                            scope.launch {
                                db.sosContactDao().insertContact(
                                    SosContactEntity(name = sosNameInput.trim(), phone = sosPhoneInput.trim())
                                )
                                sosNameInput = ""
                                sosPhoneInput = ""
                                showSosDialog = false
                            }
                        }
                    }) {
                        Text("Save")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showSosDialog = false }) { Text("Cancel") }
                },
                containerColor = palette.surface,
                titleContentColor = palette.textPrimary,
                textContentColor = palette.textSecondary
            )
        }
    }
}
