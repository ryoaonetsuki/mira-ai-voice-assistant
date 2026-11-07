package io.github.ryoaonetsuki.mira.ui.theme

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.InfiniteRepeatableSpec
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.TweenSpec
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize

/**
 * Mira AI Motion System tokens.
 * Built on Material 3 Motion specifications and Expressive motion curves.
 */
object MiraMotion {
    // Easing curves
    val Emphasized = CubicBezierEasing(0.2f, 0.0f, 0.0f, 1.0f)
    val EmphasizedDecelerate = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1.0f) // Enter: fast start, gentle settle
    val EmphasizedAccelerate = CubicBezierEasing(0.3f, 0.0f, 0.8f, 0.15f) // Exit: slow start, fast exit
    val Standard = CubicBezierEasing(0.2f, 0.0f, 0.0f, 1.0f)
    val StandardDecelerate = CubicBezierEasing(0.0f, 0.0f, 0.0f, 1.0f)
    val StandardAccelerate = CubicBezierEasing(0.3f, 0.0f, 1.0f, 1.0f)
    val Linear = LinearEasing

    // Durations (in milliseconds)
    const val DurationShort1 = 50
    const val DurationShort2 = 100
    const val DurationShort3 = 150
    const val DurationShort4 = 200
    const val DurationMedium1 = 250
    const val DurationMedium2 = 300
    const val DurationMedium3 = 350
    const val DurationMedium4 = 400
    const val DurationLong1 = 450
    const val DurationLong2 = 500
    const val DurationLong3 = 550
    const val DurationLong4 = 600
    const val DurationExtraLong1 = 700
    const val DurationExtraLong2 = 800
    const val DurationExtraLong3 = 900
    const val DurationExtraLong4 = 1000

    // Spatial spring specs (layout, position, size transitions) - snappier default with zero overshoot
    fun <T> spatialSpring(
        dampingRatio: Float = Spring.DampingRatioNoBouncy,
        stiffness: Float = Spring.StiffnessMedium
    ): SpringSpec<T> = spring(
        dampingRatio = dampingRatio,
        stiffness = stiffness
    )

    // Spatial tween spec for faster, deterministic layout transitions
    fun <T> spatialTween(
        durationMillis: Int = DurationShort4,
        easing: Easing = EmphasizedDecelerate
    ): TweenSpec<T> = tween(
        durationMillis = durationMillis,
        easing = easing
    )

    val SpatialSpringFloat: SpringSpec<Float> = spatialSpring()
    val SpatialSpringDp: SpringSpec<Dp> = spatialSpring()
    val SpatialSpringIntOffset: SpringSpec<IntOffset> = spatialSpring()
    val SpatialSpringIntSize: SpringSpec<IntSize> = spatialSpring()

    // Effects specs (color, opacity, icon state transitions)
    fun <T> effectsSpec(
        durationMillis: Int = DurationShort4,
        easing: Easing = FastOutSlowInEasing
    ): TweenSpec<T> = tween(
        durationMillis = durationMillis,
        easing = easing
    )

    val EffectsFloat: TweenSpec<Float> = effectsSpec()

    // Shimmer effect (thinking pulse)
    val ThinkingShimmer: InfiniteRepeatableSpec<Float> = infiniteRepeatable(
        animation = tween(durationMillis = 1200, easing = LinearEasing),
        repeatMode = RepeatMode.Restart
    )

    // Transitions - fast, fluid tweens without heavy physics oscillations
    val AccordionExpand: EnterTransition = expandVertically(
        animationSpec = tween(durationMillis = DurationShort4, easing = EmphasizedDecelerate)
    ) + fadeIn(
        animationSpec = tween(durationMillis = DurationShort4, easing = EmphasizedDecelerate)
    )

    val AccordionCollapse: ExitTransition = shrinkVertically(
        animationSpec = tween(durationMillis = DurationShort3, easing = EmphasizedAccelerate)
    ) + fadeOut(
        animationSpec = tween(durationMillis = DurationShort3, easing = EmphasizedAccelerate)
    )
}
