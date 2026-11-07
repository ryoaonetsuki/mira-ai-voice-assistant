package io.github.ryoaonetsuki.mira.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

val LocalMiraPalette = staticCompositionLocalOf { Palettes.Dark }

object MiraTheme {
    val palette: MiraPalette
        @Composable
        get() = LocalMiraPalette.current

    val motion: MiraMotion
        get() = MiraMotion
}

@Composable
fun MiraTheme(
    themeMode: String = "System",
    paletteName: String = themeMode,
    darkTheme: Boolean = when (themeMode.trim().lowercase()) {
        "dark" -> true
        "light" -> false
        else -> when (paletteName.trim().lowercase()) {
            "dark" -> true
            "light" -> false
            else -> isSystemInDarkTheme()
        }
    },
    content: @Composable () -> Unit
) {
    val palette = if (darkTheme) Palettes.Dark else Palettes.Light

    val colorScheme = remember(palette) {
        if (palette.isDark) {
            darkColorScheme(
                primary = palette.primary,
                secondary = palette.secondary,
                background = palette.background,
                surface = palette.surface,
                surfaceVariant = palette.surfaceVariant,
                onPrimary = Color.White,
                onSecondary = Color.White,
                onBackground = palette.textPrimary,
                onSurface = palette.textPrimary,
                onSurfaceVariant = palette.textSecondary
            )
        } else {
            lightColorScheme(
                primary = palette.primary,
                secondary = palette.secondary,
                background = palette.background,
                surface = palette.surface,
                surfaceVariant = palette.surfaceVariant,
                onPrimary = Color.White,
                onSecondary = Color.White,
                onBackground = palette.textPrimary,
                onSurface = palette.textPrimary,
                onSurfaceVariant = palette.textSecondary
            )
        }
    }

    CompositionLocalProvider(LocalMiraPalette provides palette) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = Typography,
            content = content
        )
    }
}
