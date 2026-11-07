package io.github.ryoaonetsuki.mira.ui.screens

import android.os.Build
import android.os.Environment
import androidx.compose.animation.core.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.ryoaonetsuki.mira.R
import io.github.ryoaonetsuki.mira.backend.RemoteAppConfig
import io.github.ryoaonetsuki.mira.backend.SupabaseClient
import io.github.ryoaonetsuki.mira.data.preferences.SecurePreferences
import io.github.ryoaonetsuki.mira.ui.components.MiraButton
import io.github.ryoaonetsuki.mira.ui.components.MiraCard
import io.github.ryoaonetsuki.mira.ui.navigation.Screen
import io.github.ryoaonetsuki.mira.ui.theme.LocalMiraPalette
import kotlinx.coroutines.delay

private fun isVersionOlder(current: String, target: String): Boolean {
    if (target.isBlank()) return false
    val currParts = current.split(".").mapNotNull { it.toIntOrNull() }
    val targetParts = target.split(".").mapNotNull { it.toIntOrNull() }
    val maxLen = maxOf(currParts.size, targetParts.size)
    for (i in 0 until maxLen) {
        val c = currParts.getOrElse(i) { 0 }
        val t = targetParts.getOrElse(i) { 0 }
        if (t > c) return true
        if (c > t) return false
    }
    return false
}

@Composable
fun SplashScreen(
    onNavigate: (String) -> Unit
) {
    val context = LocalContext.current
    val palette = LocalMiraPalette.current

    val infiniteTransition = rememberInfiniteTransition(label = "SplashScale")
    val scale by infiniteTransition.animateFloat(
        initialValue = 0.95f,
        targetValue = 1.05f,
        animationSpec = infiniteRepeatable(
            animation = tween(1400, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "LogoPulse"
    )

    var blockingTitle by remember { mutableStateOf<String?>(null) }
    var blockingMessage by remember { mutableStateOf<String?>(null) }
    var retryTrigger by remember { mutableIntStateOf(0) }

    LaunchedEffect(retryTrigger) {
        blockingTitle = null
        blockingMessage = null

        val startTime = System.currentTimeMillis()
        val securePrefs = SecurePreferences(context)
        val supabaseClient = SupabaseClient()

        // Fetch remote config with cached fallback
        val configRes = supabaseClient.fetchRemoteConfig()
        val config: RemoteAppConfig = if (configRes.isSuccess) {
            val live = configRes.getOrThrow()
            securePrefs.setCachedRemoteConfig(live)
            live
        } else {
            securePrefs.getCachedRemoteConfig() ?: RemoteAppConfig()
        }

        // Fast startup: minimum visual animation duration 500ms
        val elapsed = System.currentTimeMillis() - startTime
        if (elapsed < 500) {
            delay(500 - elapsed)
        }

        // 1. Kill Switch Check
        if (config.killSwitch) {
            blockingTitle = "Application Deactivated"
            blockingMessage = "Mira AI has been remotely deactivated by administration. Please check back later."
            return@LaunchedEffect
        }

        // 2. Maintenance Mode Check
        if (config.maintenanceMode) {
            blockingTitle = "Under Maintenance"
            blockingMessage = config.maintenanceMessage.ifBlank {
                "Mira is currently undergoing scheduled maintenance. Please try again shortly."
            }
            return@LaunchedEffect
        }

        // 3. Forced Update Check
        val currentAppVersion = "1.0.0"
        if (isVersionOlder(currentAppVersion, config.forcedUpdateVersion)) {
            blockingTitle = "Update Required"
            blockingMessage = config.forcedUpdateMessage.ifBlank {
                "A required update (${config.forcedUpdateVersion}) is available. Please update the app to continue."
            }
            return@LaunchedEffect
        }

        // 4. Normal setup check: proceed to Home if user has a key OR server key is configured
        val key = securePrefs.getGeminiApiKey()
        val hasKey = if (key.isNullOrBlank()) {
            val resolver = io.github.ryoaonetsuki.mira.backend.ApiKeyResolver(context)
            resolver.isServerKeyConfigured()
        } else {
            true
        }

        if (!hasKey) {
            onNavigate("setup?startStep=1")
            return@LaunchedEffect
        }

        // Before navigating to Home, verify File Management permission (required setup step)
        val hasFileAccess = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            true
        }

        if (!hasFileAccess) {
            onNavigate("setup?startStep=2")
        } else {
            onNavigate(Screen.Home.route)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(palette.background)
            .statusBarsPadding()
            .navigationBarsPadding(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(24.dp)
        ) {
            Image(
                painter = painterResource(id = R.drawable.logo_chosen),
                contentDescription = "Mira Logo",
                modifier = Modifier
                    .size(140.dp)
                    .scale(scale)
                    .clip(CircleShape)
            )

            Spacer(modifier = Modifier.height(28.dp))

            Text(
                text = "Mira AI",
                fontSize = 32.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 2.sp,
                color = palette.textPrimary
            )

            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = "Voice • Brain • Companion",
                fontSize = 14.sp,
                color = palette.primary,
                letterSpacing = 1.sp
            )

            if (blockingTitle != null) {
                Spacer(modifier = Modifier.height(30.dp))
                MiraCard(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = blockingTitle!!,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFFEF4444)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = blockingMessage ?: "",
                        fontSize = 13.sp,
                        color = palette.textSecondary,
                        lineHeight = 18.sp
                    )
                    Spacer(modifier = Modifier.height(14.dp))
                    MiraButton(
                        text = "Retry Connection",
                        onClick = { retryTrigger++ },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    }
}
