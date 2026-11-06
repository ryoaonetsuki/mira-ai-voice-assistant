package io.github.ryoaonetsuki.mira.ui.screens.settings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.ryoaonetsuki.mira.R
import io.github.ryoaonetsuki.mira.ui.components.MiraCard
import io.github.ryoaonetsuki.mira.ui.theme.LocalMiraPalette

data class OsLicense(val name: String, val author: String, val license: String)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(
    onBack: () -> Unit
) {
    val palette = LocalMiraPalette.current

    val licenses = listOf(
        OsLicense("Android Jetpack Compose & Material 3", "The Android Open Source Project", "Apache 2.0"),
        OsLicense("Room Database & Navigation Compose", "The Android Open Source Project", "Apache 2.0"),
        OsLicense("OkHttp 4.12.0", "Square, Inc.", "Apache 2.0"),
        OsLicense("Coil Image Loading 2.7.0", "Coil Contributors", "Apache 2.0"),
        OsLicense("Kotlinx Coroutines Android 1.8.1", "JetBrains s.r.o.", "Apache 2.0"),
        OsLicense("AndroidX Security Crypto 1.1.0", "The Android Open Source Project", "Apache 2.0"),
        OsLicense("AndroidX Biometric 1.1.0", "The Android Open Source Project", "Apache 2.0")
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("About Mira AI", fontWeight = FontWeight.Bold, color = palette.textPrimary) },
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
            verticalArrangement = Arrangement.spacedBy(14.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            item {
                Spacer(modifier = Modifier.height(8.dp))
                Image(
                    painter = painterResource(id = R.drawable.logo_chosen),
                    contentDescription = "Mira Logo",
                    modifier = Modifier
                        .size(86.dp)
                        .clip(CircleShape)
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "Mira AI",
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    color = palette.textPrimary
                )
                Text(
                    text = "Version 1.0.0 (Build 1)",
                    fontSize = 13.sp,
                    color = palette.textSecondary
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Developed by Salman Biswas",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = palette.primary
                )
            }

            // Privacy Card
            item {
                MiraCard(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = "Device-First Privacy",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = palette.textPrimary
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Mira is built with user privacy as a foundation. All API keys and connector credentials are encrypted using Android Keystore AES256-GCM hardware security. Voice sessions connect directly via secure low-latency WebSocket without intermediary proxy logging.",
                        fontSize = 12.sp,
                        color = palette.textSecondary,
                        lineHeight = 18.sp
                    )
                }
            }

            // Open-Source Licenses
            item {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = "Open-Source Libraries",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = palette.primary,
                        modifier = Modifier.padding(bottom = 6.dp)
                    )
                }
            }

            items(licenses) { item ->
                MiraCard(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = item.name,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = palette.textPrimary
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "${item.author} • Licensed under ${item.license}",
                        fontSize = 11.sp,
                        color = palette.textSecondary
                    )
                }
            }
        }
    }
}
