package io.github.ryoaonetsuki.mira.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.ryoaonetsuki.mira.service.LiveState
import io.github.ryoaonetsuki.mira.ui.theme.LocalMiraPalette
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun AnimatedOrb(
    state: LiveState,
    amplitude: Float,
    orbStyle: String = "Mira Neon",
    modifier: Modifier = Modifier
) {
    val palette = LocalMiraPalette.current

    // Idle breathing pulse animation
    val infiniteTransition = rememberInfiniteTransition(label = "OrbPulse")
    val idlePulse by infiniteTransition.animateFloat(
        initialValue = 0.92f,
        targetValue = 1.05f,
        animationSpec = infiniteRepeatable(
            animation = tween(2400, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "IdleScale"
    )

    // Speaking shimmer rotation
    val speakingPhase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "SpeakingPhase"
    )

    // Connecting rotation
    val connectingRotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(3000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "ConnectingRotation"
    )

    // Smooth amplitude scaling when listening
    val smoothedAmp by animateFloatAsState(
        targetValue = amplitude.coerceIn(0f, 1f),
        animationSpec = spring(stiffness = Spring.StiffnessLow),
        label = "AmplitudeScale"
    )

    val currentScale = when (state) {
        LiveState.LISTENING -> 1.0f + (smoothedAmp * 0.45f)
        LiveState.SPEAKING -> 1.08f + (sin(Math.toRadians(speakingPhase.toDouble())).toFloat() * 0.08f)
        LiveState.CONNECTING -> idlePulse
        LiveState.IDLE -> idlePulse
        LiveState.ERROR -> 0.95f
    }

    // Orb Colors depending on style and palette
    val coreColor = when (orbStyle) {
        "Cyan Core" -> Color(0xFF00E5FF)
        "Pulse Wave" -> Color(0xFF38BDF8)
        "Deep Violet" -> Color(0xFFA855F7)
        else -> palette.primary // Mira Neon default
    }

    val glowColor = when (orbStyle) {
        "Cyan Core" -> Color(0xFF0284C7)
        "Pulse Wave" -> Color(0xFF818CF8)
        "Deep Violet" -> Color(0xFFEC4899)
        else -> palette.secondary
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(240.dp)
                .padding(16.dp)
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val center = Offset(size.width / 2f, size.height / 2f)
                val baseRadius = (size.minDimension / 2f) * 0.65f * currentScale

                // 1. Outer ambient glow
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            glowColor.copy(alpha = if (state == LiveState.SPEAKING) 0.35f else 0.22f),
                            glowColor.copy(alpha = 0.08f),
                            Color.Transparent
                        ),
                        center = center,
                        radius = baseRadius * 1.55f
                    ),
                    center = center,
                    radius = baseRadius * 1.55f
                )

                // 2. Middle energy halo
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            glowColor.copy(alpha = 0.55f),
                            coreColor.copy(alpha = 0.25f),
                            Color.Transparent
                        ),
                        center = center,
                        radius = baseRadius * 1.2f
                    ),
                    center = center,
                    radius = baseRadius * 1.2f
                )

                // 3. Dense core sphere
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            Color.White.copy(alpha = 0.9f),
                            coreColor,
                            glowColor,
                            palette.background
                        ),
                        center = center,
                        radius = baseRadius
                    ),
                    center = center,
                    radius = baseRadius
                )

                // 4. State specific overlay rings
                if (state == LiveState.CONNECTING) {
                    val angleRad = Math.toRadians(connectingRotation.toDouble())
                    val ringOffset = Offset(
                        center.x + (cos(angleRad) * baseRadius * 0.25f).toFloat(),
                        center.y + (sin(angleRad) * baseRadius * 0.25f).toFloat()
                    )
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(Color.White.copy(alpha = 0.7f), Color.Transparent),
                            center = ringOffset,
                            radius = baseRadius * 0.5f
                        ),
                        center = ringOffset,
                        radius = baseRadius * 0.5f
                    )
                }

                if (state == LiveState.SPEAKING) {
                    val angleRad = Math.toRadians(speakingPhase.toDouble())
                    val shimmerOffset = Offset(
                        center.x + (cos(angleRad) * baseRadius * 0.35f).toFloat(),
                        center.y + (sin(angleRad) * baseRadius * 0.35f).toFloat()
                    )
                    drawCircle(
                        color = Color.White.copy(alpha = 0.6f),
                        radius = baseRadius * 0.3f,
                        center = shimmerOffset
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // State Text
        val stateLabel = when (state) {
            LiveState.IDLE -> "HOW CAN I HELP YOU?"
            LiveState.CONNECTING -> "CONNECTING…"
            LiveState.LISTENING -> "LISTENING…"
            LiveState.SPEAKING -> "SPEAKING…"
            LiveState.ERROR -> "CONNECTION FAILED"
        }

        Text(
            text = "AI ASSISTANT",
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 2.sp,
            color = palette.textSecondary
        )

        Spacer(modifier = Modifier.height(4.dp))

        Text(
            text = stateLabel,
            fontSize = 17.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp,
            color = if (state == LiveState.ERROR) Color(0xFFEF4444) else palette.primary
        )
    }
}
