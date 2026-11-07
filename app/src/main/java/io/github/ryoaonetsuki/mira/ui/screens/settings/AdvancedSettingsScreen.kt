package io.github.ryoaonetsuki.mira.ui.screens.settings

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.widget.Toast
import androidx.annotation.DrawableRes
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
import androidx.fragment.app.FragmentActivity
import io.github.ryoaonetsuki.mira.R
import io.github.ryoaonetsuki.mira.data.local.MiraDatabase
import io.github.ryoaonetsuki.mira.data.local.entities.SosContactEntity
import io.github.ryoaonetsuki.mira.data.preferences.SecurePreferences
import io.github.ryoaonetsuki.mira.data.preferences.UserPreferences
import io.github.ryoaonetsuki.mira.service.MiraAccessibilityService
import io.github.ryoaonetsuki.mira.ui.components.BiometricAuthHelper
import io.github.ryoaonetsuki.mira.ui.components.MiraCard
import io.github.ryoaonetsuki.mira.ui.components.MiraSwitch
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

    val orbStylesList = listOf("Mira Neon", "Cyan Core", "Pulse Wave", "Deep Violet")

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Advanced & Security",
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
            // Orb Style Card
            item {
                MiraCard(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = "Orb Visual Style",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = palette.textPrimary
                    )
                    Text(
                        text = "Shader and glowing waveform aesthetic on the Home Screen",
                        fontSize = 12.sp,
                        color = palette.textSecondary
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        for (style in orbStylesList) {
                            val isSelected = orbStyle == style
                            FilterChip(
                                selected = isSelected,
                                onClick = { scope.launch { userPrefs.setOrbStyle(style) } },
                                label = { Text(style, fontSize = 11.5.sp) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = palette.primary,
                                    selectedLabelColor = if (palette.isDark) palette.background else Color.White
                                ),
                                border = FilterChipDefaults.filterChipBorder(
                                    enabled = true,
                                    selected = isSelected,
                                    borderColor = palette.borderSubtle,
                                    selectedBorderColor = palette.primary
                                )
                            )
                        }
                    }
                }
            }

            // Automation & Accessibility
            item {
                Text(
                    text = "Automation & Accessibility",
                    fontSize = 13.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = palette.primary,
                    modifier = Modifier.padding(start = 2.dp)
                )

                Spacer(modifier = Modifier.height(6.dp))

                MiraCard(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Accessibility Service (Macros)",
                                fontSize = 14.5.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = palette.textPrimary
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = if (isAccessibilityEnabled) "Active • Screen automation enabled"
                                else "Disabled • Required for WhatsApp & YouTube screen automation",
                                fontSize = 12.sp,
                                color = if (isAccessibilityEnabled) Color(0xFF10B981) else palette.textSecondary
                            )
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Button(
                            onClick = {
                                val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                                context.startActivity(intent)
                            },
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isAccessibilityEnabled) palette.surfaceVariant else palette.primary,
                                contentColor = if (isAccessibilityEnabled) palette.primary else (if (palette.isDark) palette.background else Color.White)
                            ),
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
                        ) {
                            Text(
                                text = if (isAccessibilityEnabled) "Configure" else "Enable",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
            }

            // Security: Face Lock & Biometrics
            item {
                Text(
                    text = "Security & Biometrics",
                    fontSize = 13.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = palette.primary,
                    modifier = Modifier.padding(start = 2.dp)
                )

                Spacer(modifier = Modifier.height(6.dp))

                MiraCard(modifier = Modifier.fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(38.dp)
                                .clip(CircleShape)
                                .background(palette.primary.copy(alpha = 0.15f))
                        ) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_fluent_shield_keyhole_24_regular),
                                contentDescription = null,
                                tint = palette.primary,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Face Lock / Biometrics",
                                fontSize = 14.5.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = palette.textPrimary
                            )
                            Text(
                                text = "Gate Memories & Secret Settings behind BiometricPrompt / PIN",
                                fontSize = 12.sp,
                                color = palette.textSecondary
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
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

            // Emergency SOS
            item {
                Text(
                    text = "Emergency SOS",
                    fontSize = 13.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = palette.primary,
                    modifier = Modifier.padding(start = 2.dp)
                )

                Spacer(modifier = Modifier.height(6.dp))

                MiraCard(modifier = Modifier.fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "SOS Contacts (${sosContacts.size})",
                                fontSize = 14.5.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = palette.textPrimary
                            )
                            Text(
                                text = "Emergency contacts saved on-device for SOS alerts",
                                fontSize = 12.sp,
                                color = palette.textSecondary
                            )
                        }
                        Surface(
                            color = palette.primary.copy(alpha = 0.15f),
                            shape = RoundedCornerShape(10.dp),
                            border = BorderStroke(1.dp, palette.primary.copy(alpha = 0.35f)),
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .clickable { showSosDialog = true }
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
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

                    if (sosContacts.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(10.dp))
                        HorizontalDivider(color = palette.borderSubtle.copy(alpha = 0.5f))
                        Spacer(modifier = Modifier.height(6.dp))

                        for (c in sosContacts) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp)
                            ) {
                                Icon(
                                    painter = painterResource(id = R.drawable.ic_fluent_phone_24_regular),
                                    contentDescription = null,
                                    tint = palette.textSecondary,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = c.name,
                                        fontSize = 13.5.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = palette.textPrimary
                                    )
                                    Text(
                                        text = c.phone,
                                        fontSize = 11.5.sp,
                                        color = palette.textSecondary
                                    )
                                }
                                IconButton(
                                    onClick = { scope.launch { db.sosContactDao().deleteContact(c) } },
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Icon(
                                        painter = painterResource(id = R.drawable.ic_fluent_delete_24_regular),
                                        contentDescription = "Delete Contact",
                                        tint = palette.textSecondary,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            item {
                Spacer(modifier = Modifier.height(20.dp))
            }
        }

        // Add SOS Dialog
        if (showSosDialog) {
            AlertDialog(
                onDismissRequest = { showSosDialog = false },
                title = {
                    Text(
                        text = "Add SOS Contact",
                        fontWeight = FontWeight.Bold,
                        color = palette.textPrimary
                    )
                },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedTextField(
                            value = sosNameInput,
                            onValueChange = { sosNameInput = it },
                            label = { Text("Contact Name") },
                            placeholder = { Text("e.g. Mom, Partner") },
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = sosPhoneInput,
                            onValueChange = { sosPhoneInput = it },
                            label = { Text("Phone Number") },
                            placeholder = { Text("+1 (555) 000-0000") },
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp),
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
                        Text("Save", fontWeight = FontWeight.Bold, color = palette.primary)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showSosDialog = false }) {
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
