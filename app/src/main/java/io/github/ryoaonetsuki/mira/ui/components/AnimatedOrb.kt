package io.github.ryoaonetsuki.mira.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.ryoaonetsuki.mira.service.LiveState
import io.github.ryoaonetsuki.mira.ui.theme.LocalMiraPalette
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * ChatGPT-style Voice Orb: A glowing 3D glass sphere with optical depth,
 * luminous rim Fresnel lighting, glassy specular reflections, and fluid
 * harmonic surface deformation reactive to mic audio amplitude.
 */
@Composable
fun AnimatedOrb(
    state: LiveState,
    amplitude: Float,
    orbStyle: String = "Mira Neon",
    modifier: Modifier = Modifier
) {
    val palette = LocalMiraPalette.current

    // Single unified infiniteTransition driving all temporal animations with zero extra allocations
    val infiniteTransition = rememberInfiniteTransition(label = "GlassOrbDynamics")

    // Continuous time phase for continuous rotation and fluid waves (0 .. 2π)
    val timePhase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = (2 * PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(4000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "TimePhase"
    )

    // Calm breathing pulse for idle state (3.2s cycle)
    val idlePulse by infiniteTransition.animateFloat(
        initialValue = 0.98f,
        targetValue = 1.04f,
        animationSpec = infiniteRepeatable(
            animation = tween(3200, easing = EaseInOutSine),
            repeatMode = RepeatMode.Reverse
        ),
        label = "IdlePulse"
    )

    // Speaking rhythm frequency
    val speakingPhase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = (2 * PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(1600, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "SpeakingPhase"
    )

    // Smooth reactive microphone amplitude (dampens jitter while remaining instantly responsive)
    val smoothedAmplitude by animateFloatAsState(
        targetValue = amplitude.coerceIn(0f, 1f),
        animationSpec = spring(dampingRatio = 0.72f, stiffness = Spring.StiffnessMediumLow),
        label = "SmoothedAmplitude"
    )

    // Dynamic scale driven by state & amplitude
    val currentScale = when (state) {
        LiveState.IDLE -> idlePulse
        LiveState.CONNECTING -> 1.0f + (sin(timePhase.toDouble()).toFloat() * 0.03f)
        LiveState.LISTENING -> 1.0f + (smoothedAmplitude * 0.35f)
        LiveState.SPEAKING -> 1.06f + (sin(speakingPhase.toDouble()).toFloat() * 0.05f)
        LiveState.ERROR -> 0.96f
    }

    // Orb theme palette
    val (primaryGlow, secondaryGlow, deepAccent) = when (orbStyle) {
        "Cyan Core" -> Triple(Color(0xFF38BDF8), Color(0xFF0284C7), Color(0xFF0369A1))
        "Pulse Wave" -> Triple(Color(0xFF818CF8), Color(0xFF4F46E5), Color(0xFF312E81))
        "Deep Violet" -> Triple(Color(0xFFC084FC), Color(0xFF9333EA), Color(0xFF581C87))
        else -> Triple(Color(0xFF38BDF8), Color(0xFF818CF8), Color(0xFF4F46E5)) // Default Mira Neon
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(250.dp)
                .padding(8.dp)
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val center = Offset(size.width / 2f, size.height / 2f)
                val baseRadius = (size.minDimension / 2f) * 0.62f * currentScale

                // 1. Soft atmospheric outer halo (volumetric ambient glow)
                val haloAlpha = when (state) {
                    LiveState.SPEAKING -> 0.38f
                    LiveState.LISTENING -> 0.22f + (smoothedAmplitude * 0.30f)
                    LiveState.ERROR -> 0.15f
                    else -> 0.18f
                }
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            primaryGlow.copy(alpha = haloAlpha),
                            secondaryGlow.copy(alpha = haloAlpha * 0.4f),
                            Color.Transparent
                        ),
                        center = center,
                        radius = baseRadius * 1.65f
                    ),
                    center = center,
                    radius = baseRadius * 1.65f
                )

                // 2. Fluid surface deformation Path (sine harmonic rim)
                val fluidPath = Path()
                val steps = 72
                val distortionAmp = when (state) {
                    LiveState.LISTENING -> (baseRadius * 0.09f * smoothedAmplitude)
                    LiveState.SPEAKING -> (baseRadius * 0.04f)
                    else -> (baseRadius * 0.015f)
                }
                val wavePhase = if (state == LiveState.SPEAKING) speakingPhase else timePhase

                for (step in 0..steps) {
                    val angle = (step.toFloat() / steps) * (2 * PI).toFloat()
                    val harmonic = sin(angle * 4f + wavePhase) * cos(angle * 2f - wavePhase)
                    val r = baseRadius + (harmonic.toFloat() * distortionAmp)
                    val x = center.x + cos(angle) * r
                    val y = center.y + sin(angle) * r
                    if (step == 0) fluidPath.moveTo(x, y) else fluidPath.lineTo(x, y)
                }
                fluidPath.close()

                // 3. Deep 3D glass body (offset center creates true spherical volume)
                val lightOffset = Offset(center.x - baseRadius * 0.28f, center.y - baseRadius * 0.28f)
                drawPath(
                    path = fluidPath,
                    brush = Brush.radialGradient(
                        colors = if (state == LiveState.ERROR) {
                            listOf(
                                Color(0xFFFCA5A5),
                                Color(0xFFEF4444),
                                Color(0xFF7F1D1D),
                                Color(0xFF180A0A)
                            )
                        } else {
                            listOf(
                                Color(0xFFFFFFFF).copy(alpha = 0.90f),
                                primaryGlow,
                                secondaryGlow,
                                deepAccent,
                                Color(0xFF0F172A),
                                Color(0xFF020617)
                            )
                        },
                        center = lightOffset,
                        radius = baseRadius * 1.15f
                    )
                )

                // 4. Inner luminous plasma / core activity
                if (state == LiveState.SPEAKING || state == LiveState.LISTENING || state == LiveState.CONNECTING) {
                    val corePulse = if (state == LiveState.SPEAKING) {
                        (sin(speakingPhase.toDouble() * 2).toFloat() * 0.5f + 0.5f)
                    } else {
                        smoothedAmplitude
                    }
                    val innerRadius = baseRadius * (0.35f + corePulse * 0.20f)
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                Color.White.copy(alpha = 0.85f),
                                primaryGlow.copy(alpha = 0.70f),
                                secondaryGlow.copy(alpha = 0.35f),
                                Color.Transparent
                            ),
                            center = center,
                            radius = innerRadius
                        ),
                        center = center,
                        radius = innerRadius
                    )
                }

                // 5. Luminous rim Fresnel lighting (glass perimeter edge highlight)
                val rimDegrees = Math.toDegrees(timePhase.toDouble()).toFloat()
                rotate(degrees = rimDegrees, pivot = center) {
                    drawPath(
                        path = fluidPath,
                        brush = Brush.sweepGradient(
                            colors = listOf(
                                primaryGlow.copy(alpha = 0.95f),
                                Color.White.copy(alpha = 0.90f),
                                secondaryGlow.copy(alpha = 0.40f),
                                primaryGlow.copy(alpha = 0.15f),
                                primaryGlow.copy(alpha = 0.95f)
                            ),
                            center = center
                        ),
                        style = Stroke(width = 2.4.dp.toPx())
                    )
                }

                // 6. Connecting celestial orbit rings
                if (state == LiveState.CONNECTING) {
                    val orbitAngle = timePhase
                    val orbitX = center.x + cos(orbitAngle) * baseRadius * 0.45f
                    val orbitY = center.y + sin(orbitAngle) * baseRadius * 0.45f
                    drawCircle(
                        color = Color.White.copy(alpha = 0.85f),
                        radius = 4.dp.toPx(),
                        center = Offset(orbitX, orbitY)
                    )
                }

                // 7. Glass specular reflection highlight (upper-left lens shine)
                rotate(degrees = -30f, pivot = lightOffset) {
                    drawOval(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                Color.White.copy(alpha = 0.80f),
                                Color.White.copy(alpha = 0.25f),
                                Color.Transparent
                            ),
                            center = lightOffset,
                            radius = baseRadius * 0.45f
                        ),
                        topLeft = Offset(lightOffset.x - baseRadius * 0.32f, lightOffset.y - baseRadius * 0.18f),
                        size = Size(baseRadius * 0.64f, baseRadius * 0.36f)
                    )
                }

                // 8. Ground bounce counter-reflection (subtle bottom-right inner bounce)
                val bounceOffset = Offset(center.x + baseRadius * 0.35f, center.y + baseRadius * 0.35f)
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            primaryGlow.copy(alpha = 0.35f),
                            Color.Transparent
                        ),
                        center = bounceOffset,
                        radius = baseRadius * 0.35f
                    ),
                    center = bounceOffset,
                    radius = baseRadius * 0.35f
                )
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // State Text Indicator
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
