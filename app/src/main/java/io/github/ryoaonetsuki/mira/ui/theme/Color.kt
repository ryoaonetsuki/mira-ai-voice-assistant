package io.github.ryoaonetsuki.mira.ui.theme

import androidx.compose.ui.graphics.Color

// DeepSeek Brand Accents (Original Muted Royal Blue)
val DeepSeekBlue = Color(0xFF4D6BFE)
val DeepSeekBlueSecondary = Color(0xFF3B57DB)

// Legacy accent tokens mapped to DeepSeek Blue to prevent neon colors anywhere
val CyanAccent = DeepSeekBlue
val VioletAccent = DeepSeekBlueSecondary
val PurpleAccent = DeepSeekBlue

// Mira Theme Palette: Exactly 2 Palettes (Dark & Light)
data class MiraPalette(
    val background: Color,
    val surface: Color,
    val surfaceVariant: Color,
    val primary: Color,
    val secondary: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val isDark: Boolean = true,
    val surfaceSubtle: Color = Color(0xFF13161F),
    val borderSubtle: Color = Color(0xFF272C3D),
    val codeBackground: Color = Color(0xFF161B26),
    val codeHeader: Color = Color(0xFF0F1219),
    val codeBorder: Color = Color(0xFF282F3E),
    val thinkingBorder: Color = primary.copy(alpha = 0.4f),
    val thinkingSurface: Color = surface.copy(alpha = 0.5f)
)

object Palettes {
    // DeepSeek Original Dark Palette
    val Dark = MiraPalette(
        background = Color(0xFF0F1117),
        surface = Color(0xFF181B24),
        surfaceVariant = Color(0xFF212530),
        primary = DeepSeekBlue,
        secondary = DeepSeekBlueSecondary,
        textPrimary = Color(0xFFF3F4F6),
        textSecondary = Color(0xFF9CA3AF),
        isDark = true,
        surfaceSubtle = Color(0xFF13161F),
        borderSubtle = Color(0xFF272C3D),
        codeBackground = Color(0xFF161B26),
        codeHeader = Color(0xFF0F1219),
        codeBorder = Color(0xFF282F3E),
        thinkingBorder = DeepSeekBlue.copy(alpha = 0.4f),
        thinkingSurface = Color(0xFF181B24).copy(alpha = 0.5f)
    )

    // DeepSeek Original Light Palette
    val Light = MiraPalette(
        background = Color(0xFFF8FAFC),
        surface = Color(0xFFFFFFFF),
        surfaceVariant = Color(0xFFF1F5F9),
        primary = DeepSeekBlue,
        secondary = DeepSeekBlueSecondary,
        textPrimary = Color(0xFF1E293B),
        textSecondary = Color(0xFF64748B),
        isDark = false,
        surfaceSubtle = Color(0xFFF8FAFC),
        borderSubtle = Color(0xFFE2E8F0),
        codeBackground = Color(0xFFF1F5F9),
        codeHeader = Color(0xFFE2E8F0),
        codeBorder = Color(0xFFCBD5E1),
        thinkingBorder = DeepSeekBlue.copy(alpha = 0.4f),
        thinkingSurface = Color(0xFFF1F5F9).copy(alpha = 0.7f)
    )

    fun getPalette(name: String, isSystemDark: Boolean = true): MiraPalette {
        return when (name.trim().lowercase()) {
            "light" -> Light
            "dark" -> Dark
            else -> if (isSystemDark) Dark else Light
        }
    }
}
