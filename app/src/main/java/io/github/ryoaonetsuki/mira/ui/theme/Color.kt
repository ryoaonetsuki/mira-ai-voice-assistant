package io.github.ryoaonetsuki.mira.ui.theme

import androidx.compose.ui.graphics.Color

// Default Brand Gradients & Accents
val CyanAccent = Color(0xFF22D3EE)
val VioletAccent = Color(0xFF8B5CF6)
val PurpleAccent = Color(0xFFA855F7)

// 7 Complete Palettes
data class MiraPalette(
    val background: Color,
    val surface: Color,
    val surfaceVariant: Color,
    val primary: Color,
    val secondary: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val isDark: Boolean = true,
    val surfaceSubtle: Color = Color(0xFF0D1522),
    val borderSubtle: Color = Color(0xFF1E2F45),
    val codeBackground: Color = Color(0xFF121824),
    val codeHeader: Color = Color(0xFF0A0F17),
    val codeBorder: Color = Color(0xFF1F2C3F),
    val thinkingBorder: Color = primary.copy(alpha = 0.4f),
    val thinkingSurface: Color = surface.copy(alpha = 0.5f)
)

object Palettes {
    val Midnight = MiraPalette(
        background = Color(0xFF080D16),
        surface = Color(0xFF101A29),
        surfaceVariant = Color(0xFF19283B),
        primary = Color(0xFF22D3EE),
        secondary = Color(0xFF8B5CF6),
        textPrimary = Color(0xFFF2F7FC),
        textSecondary = Color(0xFFA9B9CC),
        isDark = true,
        surfaceSubtle = Color(0xFF0D1522),
        borderSubtle = Color(0xFF1E2F45),
        codeBackground = Color(0xFF121824),
        codeHeader = Color(0xFF0A0F17),
        codeBorder = Color(0xFF1F2C3F),
        thinkingBorder = Color(0xFF22D3EE).copy(alpha = 0.4f),
        thinkingSurface = Color(0xFF101A29).copy(alpha = 0.5f)
    )

    val Obsidian = MiraPalette(
        background = Color(0xFF0B0D10),
        surface = Color(0xFF14191F),
        surfaceVariant = Color(0xFF202A34),
        primary = Color(0xFF22D3EE),
        secondary = Color(0xFF8B5CF6),
        textPrimary = Color(0xFFF3F6F8),
        textSecondary = Color(0xFFB0BEC9),
        isDark = true,
        surfaceSubtle = Color(0xFF0E1216),
        borderSubtle = Color(0xFF232D37),
        codeBackground = Color(0xFF101419),
        codeHeader = Color(0xFF0B0E12),
        codeBorder = Color(0xFF1F2630),
        thinkingBorder = Color(0xFF22D3EE).copy(alpha = 0.4f),
        thinkingSurface = Color(0xFF14191F).copy(alpha = 0.5f)
    )

    val Nocturne = MiraPalette(
        background = Color(0xFF0D0B18),
        surface = Color(0xFF171329),
        surfaceVariant = Color(0xFF241D3B),
        primary = Color(0xFF22D3EE),
        secondary = Color(0xFF8B5CF6),
        textPrimary = Color(0xFFF7F3FF),
        textSecondary = Color(0xFFC0B5D4),
        isDark = true,
        surfaceSubtle = Color(0xFF110E20),
        borderSubtle = Color(0xFF2B2346),
        codeBackground = Color(0xFF151026),
        codeHeader = Color(0xFF0E0A1B),
        codeBorder = Color(0xFF282040),
        thinkingBorder = Color(0xFF8B5CF6).copy(alpha = 0.45f),
        thinkingSurface = Color(0xFF171329).copy(alpha = 0.5f)
    )

    val Ember = MiraPalette(
        background = Color(0xFF120C08),
        surface = Color(0xFF201610),
        surfaceVariant = Color(0xFF32231A),
        primary = Color(0xFFFB923C),
        secondary = Color(0xFFF59E0B),
        textPrimary = Color(0xFFFFF7ED),
        textSecondary = Color(0xFFA8A29E),
        isDark = true,
        surfaceSubtle = Color(0xFF180F0A),
        borderSubtle = Color(0xFF3D2B20),
        codeBackground = Color(0xFF1B110B),
        codeHeader = Color(0xFF120C08),
        codeBorder = Color(0xFF38261B),
        thinkingBorder = Color(0xFFFB923C).copy(alpha = 0.4f),
        thinkingSurface = Color(0xFF201610).copy(alpha = 0.5f)
    )

    val Abyss = MiraPalette(
        background = Color(0xFF051219),
        surface = Color(0xFF0B212D),
        surfaceVariant = Color(0xFF133649),
        primary = Color(0xFF14B8A6),
        secondary = Color(0xFF06B6D4),
        textPrimary = Color(0xFFF0FDFA),
        textSecondary = Color(0xFF64748B),
        isDark = true,
        surfaceSubtle = Color(0xFF071822),
        borderSubtle = Color(0xFF184157),
        codeBackground = Color(0xFF091E29),
        codeHeader = Color(0xFF051219),
        codeBorder = Color(0xFF15384C),
        thinkingBorder = Color(0xFF14B8A6).copy(alpha = 0.4f),
        thinkingSurface = Color(0xFF0B212D).copy(alpha = 0.5f)
    )

    val Rosewood = MiraPalette(
        background = Color(0xFF14080E),
        surface = Color(0xFF24101B),
        surfaceVariant = Color(0xFF38192A),
        primary = Color(0xFFF43F5E),
        secondary = Color(0xFFFB7185),
        textPrimary = Color(0xFFFFF1F2),
        textSecondary = Color(0xFF9CA3AF),
        isDark = true,
        surfaceSubtle = Color(0xFF1A0A13),
        borderSubtle = Color(0xFF451F34),
        codeBackground = Color(0xFF1F0D17),
        codeHeader = Color(0xFF14080E),
        codeBorder = Color(0xFF3D1B2E),
        thinkingBorder = Color(0xFFF43F5E).copy(alpha = 0.4f),
        thinkingSurface = Color(0xFF24101B).copy(alpha = 0.5f)
    )

    val Daylight = MiraPalette(
        background = Color(0xFFF8FAFC),
        surface = Color(0xFFFFFFFF),
        surfaceVariant = Color(0xFFF1F5F9),
        primary = Color(0xFF0284C7),
        secondary = Color(0xFF7C3AED),
        textPrimary = Color(0xFF0F172A),
        textSecondary = Color(0xFF64748B),
        isDark = false,
        surfaceSubtle = Color(0xFFF1F5F9),
        borderSubtle = Color(0xFFCBD5E1),
        codeBackground = Color(0xFFF8FAFC),
        codeHeader = Color(0xFFE2E8F0),
        codeBorder = Color(0xFFCBD5E1),
        thinkingBorder = Color(0xFF0284C7).copy(alpha = 0.4f),
        thinkingSurface = Color(0xFFF1F5F9).copy(alpha = 0.7f)
    )

    fun getPalette(name: String): MiraPalette {
        return when (name) {
            "Obsidian" -> Obsidian
            "Nocturne" -> Nocturne
            "Ember" -> Ember
            "Abyss" -> Abyss
            "Rosewood" -> Rosewood
            "Daylight" -> Daylight
            else -> Midnight
        }
    }
}
