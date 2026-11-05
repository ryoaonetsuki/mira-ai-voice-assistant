package io.github.ryoaonetsuki.mira.ui.components

import android.content.Context
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import io.github.ryoaonetsuki.mira.ui.theme.LocalMiraPalette

@Composable
fun MiraCard(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 16.dp,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val palette = LocalMiraPalette.current
    val clickableModifier = if (onClick != null) modifier.clickable { onClick() } else modifier

    Column(
        modifier = clickableModifier
            .clip(RoundedCornerShape(cornerRadius))
            .background(palette.surface)
            .border(1.dp, palette.surfaceVariant.copy(alpha = 0.5f), RoundedCornerShape(cornerRadius))
            .padding(16.dp),
        content = content
    )
}

@Composable
fun MiraButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    isLoading: Boolean = false
) {
    val palette = LocalMiraPalette.current
    val gradient = Brush.horizontalGradient(
        colors = if (enabled) listOf(palette.primary, palette.secondary) else listOf(Color.Gray, Color.DarkGray)
    )

    Button(
        onClick = onClick,
        enabled = enabled && !isLoading,
        shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent),
        contentPadding = PaddingValues(),
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(gradient)
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .padding(vertical = 12.dp, horizontal = 24.dp)
        ) {
            if (isLoading) {
                CircularProgressIndicator(
                    color = Color.Black,
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(20.dp)
                )
            } else {
                Text(
                    text = text,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (palette.isDark) Color.Black else Color.White
                )
            }
        }
    }
}

object BiometricAuthHelper {
    fun canAuthenticate(context: Context): Boolean {
        val biometricManager = BiometricManager.from(context)
        return biometricManager.canAuthenticate(
            BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL
        ) == BiometricManager.BIOMETRIC_SUCCESS
    }

    fun promptBiometric(
        activity: FragmentActivity,
        title: String = "Face / Biometric Verification",
        subtitle: String = "Authenticate to unlock protected area",
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) {
        val executor = ContextCompat.getMainExecutor(activity)
        val prompt = BiometricPrompt(activity, executor, object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                super.onAuthenticationSucceeded(result)
                onSuccess()
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                super.onAuthenticationError(errorCode, errString)
                onError(errString.toString())
            }

            override fun onAuthenticationFailed() {
                super.onAuthenticationFailed()
                onError("Authentication failed")
            }
        })

        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setSubtitle(subtitle)
            .setAllowedAuthenticators(
                BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL
            )
            .build()

        prompt.authenticate(promptInfo)
    }
}
