package io.github.ryoaonetsuki.mira.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.VpnKey
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.ryoaonetsuki.mira.backend.SupabaseClient
import io.github.ryoaonetsuki.mira.data.preferences.SecurePreferences
import io.github.ryoaonetsuki.mira.ui.components.MiraButton
import io.github.ryoaonetsuki.mira.ui.components.MiraCard
import io.github.ryoaonetsuki.mira.ui.theme.LocalMiraPalette
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LicenceScreen(
    onLicenceSuccess: () -> Unit,
    onBack: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val palette = LocalMiraPalette.current
    val scope = rememberCoroutineScope()
    val securePrefs = remember { SecurePreferences(context) }
    val supabaseClient = remember { SupabaseClient() }

    var licenceKeyInput by remember { mutableStateOf(securePrefs.getLicenceKey() ?: "") }
    var isValidating by remember { mutableStateOf(false) }
    var validationError by remember { mutableStateOf<String?>(null) }
    var validationSuccess by remember { mutableStateOf(false) }

    fun submitLicence() {
        val trimmed = licenceKeyInput.trim()
        if (trimmed.isBlank()) {
            validationError = "Please enter your licence key"
            return
        }

        isValidating = true
        validationError = null
        scope.launch {
            val result = supabaseClient.validateLicence(trimmed)
            isValidating = false
            if (result.isSuccess) {
                val isValid = result.getOrThrow()
                if (isValid) {
                    securePrefs.setLicenceKey(trimmed)
                    validationSuccess = true
                    onLicenceSuccess()
                } else {
                    validationError = "Invalid or revoked licence key. Please verify your key."
                }
            } else {
                validationError = "Cannot verify licence — check connection: ${result.exceptionOrNull()?.message ?: "Network error"}"
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("App Licence", fontWeight = FontWeight.Bold, color = palette.textPrimary) },
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(onClick = onBack) {
                            Icon(Icons.Rounded.ArrowBack, contentDescription = "Back", tint = palette.textPrimary)
                        }
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
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(64.dp)
                    .background(palette.surface, shape = RoundedCornerShape(16.dp))
            ) {
                Icon(
                    imageVector = Icons.Rounded.Lock,
                    contentDescription = "Licence Required",
                    tint = palette.primary,
                    modifier = Modifier.size(32.dp)
                )
            }

            Spacer(modifier = Modifier.height(18.dp))

            Text(
                text = "Licence Key Required",
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                color = palette.textPrimary
            )

            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = "Enter your personal licence key to activate Mira AI on this device.",
                fontSize = 13.sp,
                color = palette.textSecondary,
                lineHeight = 18.sp
            )

            Spacer(modifier = Modifier.height(24.dp))

            MiraCard(modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = licenceKeyInput,
                    onValueChange = {
                        licenceKeyInput = it
                        validationError = null
                    },
                    label = { Text("Licence Key") },
                    placeholder = { Text("XXXX-XXXX-XXXX-XXXX") },
                    leadingIcon = {
                        Icon(Icons.Rounded.VpnKey, contentDescription = null, tint = palette.primary)
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                if (validationError != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = validationError!!,
                        fontSize = 12.sp,
                        color = Color(0xFFEF4444)
                    )
                }

                if (validationSuccess) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.CheckCircle, contentDescription = null, tint = Color(0xFF10B981), modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Licence verified successfully!",
                            fontSize = 12.sp,
                            color = Color(0xFF10B981)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                MiraButton(
                    text = "Activate Licence",
                    isLoading = isValidating,
                    onClick = { submitLicence() },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}
