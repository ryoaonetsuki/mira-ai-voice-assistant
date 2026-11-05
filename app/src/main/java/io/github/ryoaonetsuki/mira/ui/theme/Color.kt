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
    val isDark: Boolean = true
)

object Palettes {
    val Midnight = MiraPalette(
        background = Color(0xFF0A0A12),
        surface = Color(0xFF131322),
        surfaceVariant = Color(0xFF1D1D34),
        primary = Color(0xFF22D3EE),
        secondary = Color(0xFF8B5CF6),
        textPrimary = Color(0xFFF8FAFC),
        textSecondary = Color(0xFF94A3B8),
        isDark = true
    )

    val Obsidian = MiraPalette(
        background = Color(0xFF0D0E11),
        surface = Color(0xFF16181D),
        surfaceVariant = Color(0xFF23272F),
        primary = Color(0xFF38BDF8),
        secondary = Color(0xFF94A3B8),
        textPrimary = Color(0xFFF1F5F9),
        textSecondary = Color(0xFF64748B),
        isDark = true
    )

    val Nocturne = MiraPalette(
        background = Color(0xFF0A091A),
        surface = Color(0xFF141233),
        surfaceVariant = Color(0xFF1E1C4A),
        primary = Color(0xFFA78BFA),
        secondary = Color(0xFFC084FC),
        textPrimary = Color(0xFFFAF5FF),
        textSecondary = Color(0xFFA8A29E),
        isDark = true
    )

    val Ember = MiraPalette(
        background = Color(0xFF120C08),
        surface = Color(0xFF201610),
        surfaceVariant = Color(0xFF32231A),
        primary = Color(0xFFFB923C),
        secondary = Color(0xFFF59E0B),
        textPrimary = Color(0xFFFFF7ED),
        textSecondary = Color(0xFFA8A29E),
        isDark = true
    )

    val Abyss = MiraPalette(
        background = Color(0xFF051219),
        surface = Color(0xFF0B212D),
        surfaceVariant = Color(0xFF133649),
        primary = Color(0xFF14B8A6),
        secondary = Color(0xFF06B6D4),
        textPrimary = Color(0xFFF0FDFA),
        textSecondary = Color(0xFF64748B),
        isDark = true
    )

    val Rosewood = MiraPalette(
        background = Color(0xFF14080E),
        surface = Color(0xFF24101B),
        surfaceVariant = Color(0xFF38192A),
        primary = Color(0xFFF43F5E),
        secondary = Color(0xFFFB7185),
        textPrimary = Color(0xFFFFF1F2),
        textSecondary = Color(0xFF9CA3AF),
        isDark = true
    )

    val Daylight = MiraPalette(
        background = Color(0xFFF8FAFC),
        surface = Color(0xFFFFFFFF),
        surfaceVariant = Color(0xFFF1F5F9),
        primary = Color(0xFF0284C7),
        secondary = Color(0xFF7C3AED),
        textPrimary = Color(0xFF0F172A),
        textSecondary = Color(0xFF64748B),
        isDark = false
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
