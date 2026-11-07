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
    containerColor: Color? = null,
    borderColor: Color? = null,
    borderWidth: Dp = 1.dp,
    contentPadding: PaddingValues = PaddingValues(16.dp),
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val palette = LocalMiraPalette.current
    val shape = RoundedCornerShape(cornerRadius)
    val cardBackground = containerColor ?: palette.surface
    val cardBorder = borderColor ?: palette.borderSubtle

    val cardModifier = if (onClick != null) {
        modifier
            .clip(shape)
            .background(cardBackground)
            .border(borderWidth, cardBorder, shape)
            .clickable(onClick = onClick)
    } else {
        modifier
            .clip(shape)
            .background(cardBackground)
            .border(borderWidth, cardBorder, shape)
    }

    Column(
        modifier = cardModifier.padding(contentPadding),
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
    val isInteractive = enabled && !isLoading
    val shape = RoundedCornerShape(14.dp)

    val gradient = if (isInteractive) {
        Brush.horizontalGradient(
            colors = listOf(palette.primary, palette.secondary)
        )
    } else {
        Brush.horizontalGradient(
            colors = listOf(
                palette.surfaceVariant.copy(alpha = 0.7f),
                palette.surfaceVariant.copy(alpha = 0.5f)
            )
        )
    }

    val contentColor = when {
        !isInteractive -> palette.textSecondary.copy(alpha = 0.6f)
        palette.isDark -> palette.background
        else -> Color.White
    }

    Button(
        onClick = onClick,
        enabled = isInteractive,
        shape = shape,
        colors = ButtonDefaults.buttonColors(
            containerColor = Color.Transparent,
            disabledContainerColor = Color.Transparent,
            contentColor = contentColor,
            disabledContentColor = contentColor
        ),
        contentPadding = PaddingValues(),
        modifier = modifier
            .clip(shape)
            .background(gradient)
            .then(
                if (!isInteractive) {
                    Modifier.border(1.dp, palette.borderSubtle.copy(alpha = 0.4f), shape)
                } else {
                    Modifier.border(1.dp, palette.primary.copy(alpha = 0.25f), shape)
                }
            )
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .defaultMinSize(minHeight = 48.dp)
                .padding(vertical = 12.dp, horizontal = 24.dp)
        ) {
            if (isLoading) {
                CircularProgressIndicator(
                    color = contentColor,
                    strokeWidth = 2.5.dp,
                    modifier = Modifier.size(20.dp)
                )
            } else {
                Text(
                    text = text,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = contentColor
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
