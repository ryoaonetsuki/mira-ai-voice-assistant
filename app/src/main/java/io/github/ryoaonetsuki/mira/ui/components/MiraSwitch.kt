package io.github.ryoaonetsuki.mira.ui.components

import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import io.github.ryoaonetsuki.mira.ui.theme.LocalMiraPalette

/**
 * App-wide branded Switch with explicit, high-contrast colours in both states.
 * - ON:  white thumb on cyan/blue track (clearly active)
 * - OFF: white thumb on grey track (clearly inactive)
 *
 * Use this instead of the raw Material Switch everywhere in Mira.
 */
@Composable
fun MiraSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    val palette = LocalMiraPalette.current

    Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        modifier = modifier,
        enabled = enabled,
        colors = SwitchDefaults.colors(
            // ON state: white thumb, cyan track, transparent border
            checkedThumbColor = Color.White,
            checkedTrackColor = palette.primary,
            checkedBorderColor = Color.Transparent,
            checkedIconColor = Color.White,
            // OFF state: white thumb, mid-grey track
            uncheckedThumbColor = Color.White,
            uncheckedTrackColor = Color(0xFF6B7280),
            uncheckedBorderColor = Color(0xFF6B7280),
            uncheckedIconColor = Color.White,
            // Disabled states keep shape clear
            disabledCheckedThumbColor = Color.White.copy(alpha = 0.5f),
            disabledCheckedTrackColor = palette.primary.copy(alpha = 0.4f),
            disabledUncheckedThumbColor = Color.White.copy(alpha = 0.4f),
            disabledUncheckedTrackColor = Color(0xFF6B7280).copy(alpha = 0.3f)
        )
    )
}
