package io.github.ryoaonetsuki.mira.ui.icons

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * Lucide icon set for Mira (Round 4, Phase 5).
 * Converted from official Lucide SVGs (ISC Licence).
 * Viewport: 24x24, StrokeWidth: 2, Caps/Joins: Round, Stroke: Color.Black.
 */
object Lucide {
    val accessibility: ImageVector by lazy {
        ImageVector.Builder(
            name = "accessibility",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).apply {
            path(
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 2f,
                strokeCap = StrokeCap.Round,
                strokeJoin = StrokeJoin.Round
            ) {
                moveTo(15.0f, 4.0f)
                arcTo(1.0f, 1.0f, 0.0f, isMoreThanHalf = false, isPositiveArc = true, 17.0f, 4.0f)
                arcTo(1.0f, 1.0f, 0.0f, isMoreThanHalf = false, isPositiveArc = true, 15.0f, 4.0f)
                close()
                moveTo(18.0f, 19.0f)
                lineToRelative(1.0f, -7.0f)
                lineToRelative(-6.0f, 1.0f)
                moveTo(5.0f, 8.0f)
                lineToRelative(3.0f, -3.0f)
                lineToRelative(5.5f, 3.0f)
                lineToRelative(-2.36f, 3.5f)
                moveTo(4.24f, 14.5f)
                arcToRelative(5.0f, 5.0f, 0.0f, isMoreThanHalf = false, isPositiveArc = false, 6.88f, 6.0f)
                moveTo(13.76f, 17.5f)
                arcToRelative(5.0f, 5.0f, 0.0f, isMoreThanHalf = false, isPositiveArc = false, -6.88f, -6.0f)
            }
        }.build()
    }

    val alarmClock: ImageVector by lazy {
        ImageVector.Builder(
            name = "alarm-clock",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).apply {
            path(
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 2f,
                strokeCap = StrokeCap.Round,
                strokeJoin = StrokeJoin.Round
            ) {
                moveTo(4.0f, 13.0f)
                arcTo(8.0f, 8.0f, 0.0f, isMoreThanHalf = false, isPositiveArc = true, 20.0f, 13.0f)
                arcTo(8.0f, 8.0f, 0.0f, isMoreThanHalf = false, isPositiveArc = true, 4.0f, 13.0f)
                close()
                moveTo(12.0f, 9.0f)
                verticalLineToRelative(4.0f)
                lineToRelative(2.0f, 2.0f)
                moveTo(5.0f, 3.0f)
                lineTo(2.0f, 6.0f)
                moveTo(22.0f, 6.0f)
                lineToRelative(-3.0f, -3.0f)
                moveTo(6.38f, 18.7f)
                lineTo(4.0f, 21.0f)
                moveTo(17.64f, 18.67f)
                lineTo(20.0f, 21.0f)
            }
        }.build()
    }

    val arrowDownToLine: ImageVector by lazy {
        ImageVector.Builder(
            name = "arrow-down-to-line",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).apply {
            path(
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 2f,
                strokeCap = StrokeCap.Round,
                strokeJoin = StrokeJoin.Round
            ) {
                moveTo(12.0f, 17.0f)
                verticalLineTo(3.0f)
                moveTo(6.0f, 11.0f)
                lineToRelative(6.0f, 6.0f)
                lineToRelative(6.0f, -6.0f)
                moveTo(19.0f, 21.0f)
                horizontalLineTo(5.0f)
            }
        }.build()
    }

    val arrowLeft: ImageVector by lazy {
        ImageVector.Builder(
            name = "arrow-left",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).apply {
            path(
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 2f,
                strokeCap = StrokeCap.Round,
                strokeJoin = StrokeJoin.Round
            ) {
                moveTo(12.0f, 19.0f)
                lineToRelative(-7.0f, -7.0f)
                lineToRelative(7.0f, -7.0f)
                moveTo(19.0f, 12.0f)
                horizontalLineTo(5.0f)
            }
        }.build()
    }

    val arrowRight: ImageVector by lazy {
        ImageVector.Builder(
            name = "arrow-right",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).apply {
            path(
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 2f,
                strokeCap = StrokeCap.Round,
                strokeJoin = StrokeJoin.Round
            ) {
                moveTo(5.0f, 12.0f)
                horizontalLineToRelative(14.0f)
                moveTo(12.0f, 5.0f)
                lineToRelative(7.0f, 7.0f)
                lineToRelative(-7.0f, 7.0f)
            }
        }.build()
    }

    val arrowUpRight: ImageVector by lazy {
        ImageVector.Builder(
            name = "arrow-up-right",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).apply {
            path(
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 2f,
                strokeCap = StrokeCap.Round,
                strokeJoin = StrokeJoin.Round
            ) {
                moveTo(7.0f, 7.0f)
                horizontalLineToRelative(10.0f)
                verticalLineToRelative(10.0f)
                moveTo(7.0f, 17.0f)
                lineTo(17.0f, 7.0f)
            }
        }.build()
    }

    val audioLines: ImageVector by lazy {
        ImageVector.Builder(
            name = "audio-lines",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).apply {
            path(
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 2f,
                strokeCap = StrokeCap.Round,
                strokeJoin = StrokeJoin.Round
            ) {
                moveTo(2.0f, 10.0f)
                verticalLineToRelative(3.0f)
                moveTo(6.0f, 6.0f)
                verticalLineToRelative(11.0f)
                moveTo(10.0f, 3.0f)
                verticalLineToRelative(18.0f)
                moveTo(14.0f, 8.0f)
                verticalLineToRelative(7.0f)
                moveTo(18.0f, 5.0f)
                verticalLineToRelative(13.0f)
                moveTo(22.0f, 10.0f)
                verticalLineToRelative(3.0f)
            }
        }.build()
    }

    val batteryCharging: ImageVector by lazy {
        ImageVector.Builder(
            name = "battery-charging",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).apply {
            path(
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 2f,
                strokeCap = StrokeCap.Round,
                strokeJoin = StrokeJoin.Round
            ) {
                moveTo(11.0f, 7.0f)
                lineToRelative(-3.0f, 5.0f)
                horizontalLineToRelative(4.0f)
                lineToRelative(-3.0f, 5.0f)
                moveTo(14.856f, 6.0f)
                horizontalLineTo(16.0f)
                arcToRelative(2.0f, 2.0f, 0.0f, isMoreThanHalf = false, isPositiveArc = true, 2.0f, 2.0f)
                verticalLineToRelative(8.0f)
                arcToRelative(2.0f, 2.0f, 0.0f, isMoreThanHalf = false, isPositiveArc = true, -2.0f, 2.0f)
                horizontalLineToRelative(-2.935f)
                moveTo(22.0f, 14.0f)
                verticalLineToRelative(-4.0f)
                moveTo(5.14f, 18.0f)
                horizontalLineTo(4.0f)
                arcToRelative(2.0f, 2.0f, 0.0f, isMoreThanHalf = false, isPositiveArc = true, -2.0f, -2.0f)
                verticalLineTo(8.0f)
                arcToRelative(2.0f, 2.0f, 0.0f, isMoreThanHalf = false, isPositiveArc = true, 2.0f, -2.0f)
                horizontalLineToRelative(2.936f)
            }
        }.build()
    }

    val bell: ImageVector by lazy {
        ImageVector.Builder(
            name = "bell",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).apply {
            path(
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 2f,
                strokeCap = StrokeCap.Round,
                strokeJoin = StrokeJoin.Round
            ) {
                moveTo(10.268f, 21.0f)
                arcToRelative(2.0f, 2.0f, 0.0f, isMoreThanHalf = false, isPositiveArc = false, 3.464f, 0.0f)
                moveTo(3.262f, 15.326f)
                arcTo(1.0f, 1.0f, 0.0f, isMoreThanHalf = false, isPositiveArc = false, 4.0f, 17.0f)
                horizontalLineToRelative(16.0f)
                arcToRelative(1.0f, 1.0f, 0.0f, isMoreThanHalf = false, isPositiveArc = false, 0.74f, -1.673f)
                curveTo(19.41f, 13.956f, 18.0f, 12.499f, 18.0f, 8.0f)
                arcTo(6.0f, 6.0f, 0.0f, isMoreThanHalf = false, isPositiveArc = false, 6.0f, 8.0f)
                curveToRelative(0.0f, 4.499f, -1.411f, 5.956f, -2.738f, 7.326f)
            }
        }.build()
    }

    val book: ImageVector by lazy {
        ImageVector.Builder(
            name = "book",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).apply {
            path(
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 2f,
                strokeCap = StrokeCap.Round,
                strokeJoin = StrokeJoin.Round
            ) {
                moveTo(4.0f, 19.5f)
                verticalLineToRelative(-15.0f)
                arcTo(2.5f, 2.5f, 0.0f, isMoreThanHalf = false, isPositiveArc = true, 6.5f, 2.0f)
                horizontalLineTo(19.0f)
                arcToRelative(1.0f, 1.0f, 0.0f, isMoreThanHalf = false, isPositiveArc = true, 1.0f, 1.0f)
                verticalLineToRelative(18.0f)
                arcToRelative(1.0f, 1.0f, 0.0f, isMoreThanHalf = false, isPositiveArc = true, -1.0f, 1.0f)
                horizontalLineTo(6.5f)
                arcToRelative(1.0f, 1.0f, 0.0f, isMoreThanHalf = false, isPositiveArc = true, 0.0f, -5.0f)
                horizontalLineTo(20.0f)
            }
        }.build()
    }

    val broom: ImageVector by lazy {
        ImageVector.Builder(
            name = "broom",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).apply {
            path(
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 2f,
                strokeCap = StrokeCap.Round,
                strokeJoin = StrokeJoin.Round
            ) {
                moveTo(13.5f, 10.5f)
                lineTo(22.0f, 2.0f)
                moveTo(14.734f, 13.841f)
                arcToRelative(2.0f, 2.0f, 0.0f, isMoreThanHalf = false, isPositiveArc = false, -0.314f, -2.42f)
                lineTo(12.58f, 9.58f)
                arcToRelative(2.0f, 2.0f, 0.0f, isMoreThanHalf = false, isPositiveArc = false, -2.421f, -0.314f)
                lineToRelative(-7.657f, 4.461f)
                arcTo(1.0f, 1.0f, 0.0f, isMoreThanHalf = false, isPositiveArc = false, 2.3f, 15.3f)
                lineToRelative(6.403f, 6.403f)
                arcToRelative(1.0f, 1.0f, 0.0f, isMoreThanHalf = false, isPositiveArc = false, 1.571f, -0.204f)
                close()
                moveTo(5.0f, 18.0f)
                lineToRelative(2.0f, -2.0f)
                moveTo(7.699f, 10.7f)
                lineToRelative(5.602f, 5.601f)
            }
        }.build()
    }

    val brush: ImageVector by lazy {
        ImageVector.Builder(
            name = "brush",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).apply {
            path(
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 2f,
                strokeCap = StrokeCap.Round,
                strokeJoin = StrokeJoin.Round
            ) {
                moveTo(11.0f, 10.0f)
                lineToRelative(3.0f, 3.0f)
                moveTo(6.5f, 21.0f)
                arcTo(3.5f, 3.5f, 0.0f, isMoreThanHalf = true, isPositiveArc = false, 3.0f, 17.5f)
                arcToRelative(2.62f, 2.62f, 0.0f, isMoreThanHalf = false, isPositiveArc = true, -0.708f, 1.792f)
                arcTo(1.0f, 1.0f, 0.0f, isMoreThanHalf = false, isPositiveArc = false, 3.0f, 21.0f)
                close()
                moveTo(9.969f, 17.031f)
                lineTo(21.378f, 5.624f)
                arcToRelative(1.0f, 1.0f, 0.0f, isMoreThanHalf = false, isPositiveArc = false, -3.002f, -3.002f)
                lineTo(6.967f, 14.031f)
            }
        }.build()
    }

    val calendar: ImageVector by lazy {
        ImageVector.Builder(
            name = "calendar",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).apply {
            path(
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 2f,
                strokeCap = StrokeCap.Round,
                strokeJoin = StrokeJoin.Round
            ) {
                moveTo(8.0f, 2.0f)
                verticalLineToRelative(3.0f)
                moveTo(16.0f, 2.0f)
                verticalLineToRelative(3.0f)
                moveTo(5.0f, 3.0f)
                lineTo(19.0f, 3.0f)
                arcTo(2.0f, 2.0f, 0.0f, isMoreThanHalf = false, isPositiveArc = true, 21.0f, 5.0f)
                lineTo(21.0f, 19.0f)
                arcTo(2.0f, 2.0f, 0.0f, isMoreThanHalf = false, isPositiveArc = true, 19.0f, 21.0f)
                lineTo(5.0f, 21.0f)
                arcTo(2.0f, 2.0f, 0.0f, isMoreThanHalf = false, isPositiveArc = true, 3.0f, 19.0f)
                lineTo(3.0f, 5.0f)
                arcTo(2.0f, 2.0f, 0.0f, isMoreThanHalf = false, isPositiveArc = true, 5.0f, 3.0f)
                close()
                moveTo(3.0f, 9.0f)
                horizontalLineToRelative(18.0f)
            }
        }.build()
    }

    val camera: ImageVector by lazy {
        ImageVector.Builder(
            name = "camera",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).apply {
            path(
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 2f,
                strokeCap = StrokeCap.Round,
                strokeJoin = StrokeJoin.Round
            ) {
                moveTo(13.997f, 4.0f)
                arcToRelative(2.0f, 2.0f, 0.0f, isMoreThanHalf = false, isPositiveArc = true, 1.76f, 1.05f)
                lineToRelative(0.486f, 0.9f)
                arcTo(2.0f, 2.0f, 0.0f, isMoreThanHalf = false, isPositiveArc = false, 18.003f, 7.0f)
                horizontalLineTo(20.0f)
                arcToRelative(2.0f, 2.0f, 0.0f, isMoreThanHalf = false, isPositiveArc = true, 2.0f, 2.0f)
                verticalLineToRelative(9.0f)
                arcToRelative(2.0f, 2.0f, 0.0f, isMoreThanHalf = false, isPositiveArc = true, -2.0f, 2.0f)
                horizontalLineTo(4.0f)
                arcToRelative(2.0f, 2.0f, 0.0f, isMoreThanHalf = false, isPositiveArc = true, -2.0f, -2.0f)
                verticalLineTo(9.0f)
                arcToRelative(2.0f, 2.0f, 0.0f, isMoreThanHalf = false, isPositiveArc = true, 2.0f, -2.0f)
                horizontalLineToRelative(1.997f)
                arcToRelative(2.0f, 2.0f, 0.0f, isMoreThanHalf = false, isPositiveArc = false, 1.759f, -1.048f)
                lineToRelative(0.489f, -0.904f)
                arcTo(2.0f, 2.0f, 0.0f, isMoreThanHalf = false, isPositiveArc = true, 10.004f, 4.0f)
                close()
                moveTo(9.0f, 13.0f)
                arcTo(3.0f, 3.0f, 0.0f, isMoreThanHalf = false, isPositiveArc = true, 15.0f, 13.0f)
                arcTo(3.0f, 3.0f, 0.0f, isMoreThanHalf = false, isPositiveArc = true, 9.0f, 13.0f)
                close()
            }
        }.build()
    }

    val check: ImageVector by lazy {
        ImageVector.Builder(
            name = "check",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).apply {
            path(
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 2f,
                strokeCap = StrokeCap.Round,
                strokeJoin = StrokeJoin.Round
            ) {
                moveTo(20.0f, 6.0f)
                lineTo(9.0f, 17.0f)
                lineToRelative(-5.0f, -5.0f)
            }
        }.build()
    }

    val chevronLeft: ImageVector by lazy {
        ImageVector.Builder(
            name = "chevron-left",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).apply {
            path(
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 2f,
                strokeCap = StrokeCap.Round,
                strokeJoin = StrokeJoin.Round
            ) {
                moveTo(15.0f, 18.0f)
                lineToRelative(-6.0f, -6.0f)
                lineToRelative(6.0f, -6.0f)
            }
        }.build()
    }

    val chevronRight: ImageVector by lazy {
        ImageVector.Builder(
            name = "chevron-right",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).apply {
            path(
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 2f,
                strokeCap = StrokeCap.Round,
                strokeJoin = StrokeJoin.Round
            ) {
                moveTo(9.0f, 18.0f)
                lineToRelative(6.0f, -6.0f)
                lineToRelative(-6.0f, -6.0f)
            }
        }.build()
    }

    val circleCheck: ImageVector by lazy {
        ImageVector.Builder(
            name = "circle-check",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).apply {
            path(
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 2f,
                strokeCap = StrokeCap.Round,
                strokeJoin = StrokeJoin.Round
            ) {
                moveTo(2.0f, 12.0f)
                arcTo(10.0f, 10.0f, 0.0f, isMoreThanHalf = false, isPositiveArc = true, 22.0f, 12.0f)
                arcTo(10.0f, 10.0f, 0.0f, isMoreThanHalf = false, isPositiveArc = true, 2.0f, 12.0f)
                close()
                moveTo(16.0f, 9.0f)
                lineToRelative(-5.5f, 5.5f)
                lineTo(8.0f, 12.0f)
            }
        }.build()
    }

    val circlePlay: ImageVector by lazy {
        ImageVector.Builder(
            name = "circle-play",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).apply {
            path(
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 2f,
                strokeCap = StrokeCap.Round,
                strokeJoin = StrokeJoin.Round
            ) {
                moveTo(9.0f, 9.003f)
                arcToRelative(1.0f, 1.0f, 0.0f, isMoreThanHalf = false, isPositiveArc = true, 1.517f, -0.859f)
                lineToRelative(4.997f, 2.997f)
                arcToRelative(1.0f, 1.0f, 0.0f, isMoreThanHalf = false, isPositiveArc = true, 0.0f, 1.718f)
                lineToRelative(-4.997f, 2.997f)
                arcTo(1.0f, 1.0f, 0.0f, isMoreThanHalf = false, isPositiveArc = true, 9.0f, 14.996f)
                close()
                moveTo(2.0f, 12.0f)
                arcTo(10.0f, 10.0f, 0.0f, isMoreThanHalf = false, isPositiveArc = true, 22.0f, 12.0f)
                arcTo(10.0f, 10.0f, 0.0f, isMoreThanHalf = false, isPositiveArc = true, 2.0f, 12.0f)
                close()
            }
        }.build()
    }

    val cloud: ImageVector by lazy {
        ImageVector.Builder(
            name = "cloud",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).apply {
            path(
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 2f,
                strokeCap = StrokeCap.Round,
                strokeJoin = StrokeJoin.Round
            ) {
                moveTo(17.5f, 19.0f)
                horizontalLineTo(9.0f)
                arcToRelative(7.0f, 7.0f, 0.0f, isMoreThanHalf = true, isPositiveArc = true, 6.71f, -9.0f)
                horizontalLineToRelative(1.79f)
                arcToRelative(4.5f, 4.5f, 0.0f, isMoreThanHalf = true, isPositiveArc = true, 0.0f, 9.0f)
                close()
            }
        }.build()
    }

    val cloudUpload: ImageVector by lazy {
        ImageVector.Builder(
            name = "cloud-upload",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).apply {
            path(
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 2f,
                strokeCap = StrokeCap.Round,
                strokeJoin = StrokeJoin.Round
            ) {
                moveTo(12.0f, 13.0f)
                verticalLineToRelative(8.0f)
                moveTo(4.0f, 14.899f)
                arcTo(7.0f, 7.0f, 0.0f, isMoreThanHalf = true, isPositiveArc = true, 15.71f, 8.0f)
                horizontalLineToRelative(1.79f)
                arcToRelative(4.5f, 4.5f, 0.0f, isMoreThanHalf = false, isPositiveArc = true, 2.5f, 8.242f)
                moveTo(8.0f, 17.0f)
                lineToRelative(4.0f, -4.0f)
                lineToRelative(4.0f, 4.0f)
            }
        }.build()
    }

    val code: ImageVector by lazy {
        ImageVector.Builder(
            name = "code",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).apply {
            path(
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 2f,
                strokeCap = StrokeCap.Round,
                strokeJoin = StrokeJoin.Round
            ) {
                moveTo(16.0f, 18.0f)
                lineToRelative(6.0f, -6.0f)
                lineToRelative(-6.0f, -6.0f)
                moveTo(8.0f, 6.0f)
                lineToRelative(-6.0f, 6.0f)
                lineToRelative(6.0f, 6.0f)
            }
        }.build()
    }

    val coffee: ImageVector by lazy {
        ImageVector.Builder(
            name = "coffee",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).apply {
            path(
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 2f,
                strokeCap = StrokeCap.Round,
                strokeJoin = StrokeJoin.Round
            ) {
                moveTo(10.0f, 2.0f)
                verticalLineToRelative(2.0f)
                moveTo(14.0f, 2.0f)
                verticalLineToRelative(2.0f)
                moveTo(16.0f, 8.0f)
                arcToRelative(1.0f, 1.0f, 0.0f, isMoreThanHalf = false, isPositiveArc = true, 1.0f, 1.0f)
                verticalLineToRelative(8.0f)
                arcToRelative(4.0f, 4.0f, 0.0f, isMoreThanHalf = false, isPositiveArc = true, -4.0f, 4.0f)
                horizontalLineTo(7.0f)
                arcToRelative(4.0f, 4.0f, 0.0f, isMoreThanHalf = false, isPositiveArc = true, -4.0f, -4.0f)
                verticalLineTo(9.0f)
                arcToRelative(1.0f, 1.0f, 0.0f, isMoreThanHalf = false, isPositiveArc = true, 1.0f, -1.0f)
                horizontalLineToRelative(14.0f)
                arcToRelative(4.0f, 4.0f, 0.0f, isMoreThanHalf = true, isPositiveArc = true, 0.0f, 8.0f)
                horizontalLineToRelative(-1.0f)
                moveTo(6.0f, 2.0f)
                verticalLineToRelative(2.0f)
            }
        }.build()
    }

    val copy: ImageVector by lazy {
        ImageVector.Builder(
            name = "copy",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).apply {
            path(
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 2f,
                strokeCap = StrokeCap.Round,
                strokeJoin = StrokeJoin.Round
            ) {
                moveTo(10.0f, 8.0f)
                lineTo(20.0f, 8.0f)
                arcTo(2.0f, 2.0f, 0.0f, isMoreThanHalf = false, isPositiveArc = true, 22.0f, 10.0f)
                lineTo(22.0f, 20.0f)
                arcTo(2.0f, 2.0f, 0.0f, isMoreThanHalf = false, isPositiveArc = true, 20.0f, 22.0f)
                lineTo(10.0f, 22.0f)
                arcTo(2.0f, 2.0f, 0.0f, isMoreThanHalf = false, isPositiveArc = true, 8.0f, 20.0f)
                lineTo(8.0f, 10.0f)
                arcTo(2.0f, 2.0f, 0.0f, isMoreThanHalf = false, isPositiveArc = true, 10.0f, 8.0f)
                close()
                moveTo(4.0f, 16.0f)
                curveToRelative(-1.1f, 0.0f, -2.0f, -0.9f, -2.0f, -2.0f)
                verticalLineTo(4.0f)
                curveToRelative(0.0f, -1.1f, 0.9f, -2.0f, 2.0f, -2.0f)
                horizontalLineToRelative(10.0f)
                curveToRelative(1.1f, 0.0f, 2.0f, 0.9f, 2.0f, 2.0f)
            }
        }.build()
    }

    val download: ImageVector by lazy {
        ImageVector.Builder(
            name = "download",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).apply {
            path(
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 2f,
                strokeCap = StrokeCap.Round,
                strokeJoin = StrokeJoin.Round
            ) {
                moveTo(12.0f, 15.0f)
                verticalLineTo(3.0f)
                moveTo(21.0f, 15.0f)
                verticalLineToRelative(4.0f)
                arcToRelative(2.0f, 2.0f, 0.0f, isMoreThanHalf = false, isPositiveArc = true, -2.0f, 2.0f)
                horizontalLineTo(5.0f)
                arcToRelative(2.0f, 2.0f, 0.0f, isMoreThanHalf = false, isPositiveArc = true, -2.0f, -2.0f)
                verticalLineToRelative(-4.0f)
                moveTo(7.0f, 10.0f)
                lineToRelative(5.0f, 5.0f)
                lineToRelative(5.0f, -5.0f)
            }
        }.build()
    }

    val dumbbell: ImageVector by lazy {
        ImageVector.Builder(
            name = "dumbbell",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).apply {
            path(
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 2f,
                strokeCap = StrokeCap.Round,
                strokeJoin = StrokeJoin.Round
            ) {
                moveTo(17.596f, 12.768f)
                arcToRelative(2.0f, 2.0f, 0.0f, isMoreThanHalf = true, isPositiveArc = false, 2.829f, -2.829f)
                lineToRelative(-1.768f, -1.767f)
                arcToRelative(2.0f, 2.0f, 0.0f, isMoreThanHalf = false, isPositiveArc = false, 2.828f, -2.829f)
                lineToRelative(-2.828f, -2.828f)
                arcToRelative(2.0f, 2.0f, 0.0f, isMoreThanHalf = false, isPositiveArc = false, -2.829f, 2.828f)
                lineToRelative(-1.767f, -1.768f)
                arcToRelative(2.0f, 2.0f, 0.0f, isMoreThanHalf = true, isPositiveArc = false, -2.829f, 2.829f)
                close()
                moveTo(2.5f, 21.5f)
                lineToRelative(1.4f, -1.4f)
                moveTo(20.1f, 3.9f)
                lineToRelative(1.4f, -1.4f)
                moveTo(5.343f, 21.485f)
                arcToRelative(2.0f, 2.0f, 0.0f, isMoreThanHalf = true, isPositiveArc = false, 2.829f, -2.828f)
                lineToRelative(1.767f, 1.768f)
                arcToRelative(2.0f, 2.0f, 0.0f, isMoreThanHalf = true, isPositiveArc = false, 2.829f, -2.829f)
                lineToRelative(-6.364f, -6.364f)
                arcToRelative(2.0f, 2.0f, 0.0f, isMoreThanHalf = true, isPositiveArc = false, -2.829f, 2.829f)
                lineToRelative(1.768f, 1.767f)
                arcToRelative(2.0f, 2.0f, 0.0f, isMoreThanHalf = false, isPositiveArc = false, -2.828f, 2.829f)
                close()
                moveTo(9.6f, 14.4f)
                lineToRelative(4.8f, -4.8f)
            }
        }.build()
    }

    val eighthNote: ImageVector by lazy {
        ImageVector.Builder(
            name = "eighth-note",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).apply {
            path(
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 2f,
                strokeCap = StrokeCap.Round,
                strokeJoin = StrokeJoin.Round
            ) {
                moveTo(19.0f, 6.0f)
                lineToRelative(-5.2f, -3.9f)
                arcToRelative(0.5f, 0.5f, 0.0f, isMoreThanHalf = false, isPositiveArc = false, -0.8f, 0.4f)
                verticalLineTo(18.0f)
                moveTo(5.0f, 18.0f)
                arcTo(4.0f, 4.0f, 0.0f, isMoreThanHalf = false, isPositiveArc = true, 13.0f, 18.0f)
                arcTo(4.0f, 4.0f, 0.0f, isMoreThanHalf = false, isPositiveArc = true, 5.0f, 18.0f)
                close()
            }
        }.build()
    }

    val externalLink: ImageVector by lazy {
        ImageVector.Builder(
            name = "external-link",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).apply {
            path(
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 2f,
                strokeCap = StrokeCap.Round,
                strokeJoin = StrokeJoin.Round
            ) {
                moveTo(15.0f, 3.0f)
                horizontalLineToRelative(6.0f)
                verticalLineToRelative(6.0f)
                moveTo(10.0f, 14.0f)
                lineTo(21.0f, 3.0f)
                moveTo(18.0f, 13.0f)
                verticalLineToRelative(6.0f)
                arcToRelative(2.0f, 2.0f, 0.0f, isMoreThanHalf = false, isPositiveArc = true, -2.0f, 2.0f)
                horizontalLineTo(5.0f)
                arcToRelative(2.0f, 2.0f, 0.0f, isMoreThanHalf = false, isPositiveArc = true, -2.0f, -2.0f)
                verticalLineTo(8.0f)
                arcToRelative(2.0f, 2.0f, 0.0f, isMoreThanHalf = false, isPositiveArc = true, 2.0f, -2.0f)
                horizontalLineToRelative(6.0f)
            }
        }.build()
    }

    val eye: ImageVector by lazy {
        ImageVector.Builder(
            name = "eye",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).apply {
            path(
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 2f,
                strokeCap = StrokeCap.Round,
                strokeJoin = StrokeJoin.Round
            ) {
                moveTo(2.062f, 12.348f)
                arcToRelative(1.0f, 1.0f, 0.0f, isMoreThanHalf = false, isPositiveArc = true, 0.0f, -0.696f)
                arcToRelative(10.75f, 10.75f, 0.0f, isMoreThanHalf = false, isPositiveArc = true, 19.876f, 0.0f)
                arcToRelative(1.0f, 1.0f, 0.0f, isMoreThanHalf = false, isPositiveArc = true, 0.0f, 0.696f)
                arcToRelative(10.75f, 10.75f, 0.0f, isMoreThanHalf = false, isPositiveArc = true, -19.876f, 0.0f)
                moveTo(9.0f, 12.0f)
                arcTo(3.0f, 3.0f, 0.0f, isMoreThanHalf = false, isPositiveArc = true, 15.0f, 12.0f)
                arcTo(3.0f, 3.0f, 0.0f, isMoreThanHalf = false, isPositiveArc = true, 9.0f, 12.0f)
                close()
            }
        }.build()
    }

    val eyeOff: ImageVector by lazy {
        ImageVector.Builder(
            name = "eye-off",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).apply {
            path(
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 2f,
                strokeCap = StrokeCap.Round,
                strokeJoin = StrokeJoin.Round
            ) {
                moveTo(10.733f, 5.076f)
                arcToRelative(10.744f, 10.744f, 0.0f, isMoreThanHalf = false, isPositiveArc = true, 11.205f, 6.575f)
                arcToRelative(1.0f, 1.0f, 0.0f, isMoreThanHalf = false, isPositiveArc = true, 0.0f, 0.696f)
                arcToRelative(10.747f, 10.747f, 0.0f, isMoreThanHalf = false, isPositiveArc = true, -1.444f, 2.49f)
                moveTo(14.084f, 14.158f)
                arcToRelative(3.0f, 3.0f, 0.0f, isMoreThanHalf = false, isPositiveArc = true, -4.242f, -4.242f)
                moveTo(17.479f, 17.499f)
                arcToRelative(10.75f, 10.75f, 0.0f, isMoreThanHalf = false, isPositiveArc = true, -15.417f, -5.151f)
                arcToRelative(1.0f, 1.0f, 0.0f, isMoreThanHalf = false, isPositiveArc = true, 0.0f, -0.696f)
                arcToRelative(10.75f, 10.75f, 0.0f, isMoreThanHalf = false, isPositiveArc = true, 4.446f, -5.143f)
                moveTo(2.0f, 2.0f)
                lineToRelative(20.0f, 20.0f)
            }
        }.build()
    }

    val faceSlightlySmiling: ImageVector by lazy {
        ImageVector.Builder(
            name = "face-slightly-smiling",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).apply {
            path(
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 2f,
                strokeCap = StrokeCap.Round,
                strokeJoin = StrokeJoin.Round
            ) {
                moveTo(15.0f, 10.0f)
                verticalLineTo(9.0f)
                moveTo(16.472f, 15.0f)
                arcToRelative(6.0f, 6.0f, 0.0f, isMoreThanHalf = false, isPositiveArc = true, -8.943f, 0.0f)
                moveTo(9.0f, 10.0f)
                verticalLineTo(9.0f)
                moveTo(2.0f, 12.0f)
                arcTo(10.0f, 10.0f, 0.0f, isMoreThanHalf = false, isPositiveArc = true, 22.0f, 12.0f)
                arcTo(10.0f, 10.0f, 0.0f, isMoreThanHalf = false, isPositiveArc = true, 2.0f, 12.0f)
                close()
            }
        }.build()
    }

    val fileCode: ImageVector by lazy {
        ImageVector.Builder(
            name = "file-code",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).apply {
            path(
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 2f,
                strokeCap = StrokeCap.Round,
                strokeJoin = StrokeJoin.Round
            ) {
                moveTo(6.0f, 22.0f)
                arcToRelative(2.0f, 2.0f, 0.0f, isMoreThanHalf = false, isPositiveArc = true, -2.0f, -2.0f)
                verticalLineTo(4.0f)
                arcToRelative(2.0f, 2.0f, 0.0f, isMoreThanHalf = false, isPositiveArc = true, 2.0f, -2.0f)
                horizontalLineToRelative(8.0f)
                arcToRelative(2.4f, 2.4f, 0.0f, isMoreThanHalf = false, isPositiveArc = true, 1.704f, 0.706f)
                lineToRelative(3.588f, 3.588f)
                arcTo(2.4f, 2.4f, 0.0f, isMoreThanHalf = false, isPositiveArc = true, 20.0f, 8.0f)
                verticalLineToRelative(12.0f)
                arcToRelative(2.0f, 2.0f, 0.0f, isMoreThanHalf = false, isPositiveArc = true, -2.0f, 2.0f)
                close()
                moveTo(14.0f, 2.0f)
                verticalLineToRelative(5.0f)
                arcToRelative(1.0f, 1.0f, 0.0f, isMoreThanHalf = false, isPositiveArc = false, 1.0f, 1.0f)
                horizontalLineToRelative(5.0f)
                moveTo(10.0f, 12.5f)
                lineTo(8.0f, 15.0f)
                lineToRelative(2.0f, 2.5f)
                moveTo(14.0f, 12.5f)
                lineToRelative(2.0f, 2.5f)
                lineToRelative(-2.0f, 2.5f)
            }
        }.build()
    }

    val fileText: ImageVector by lazy {
        ImageVector.Builder(
            name = "file-text",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).apply {
            path(
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 2f,
                strokeCap = StrokeCap.Round,
                strokeJoin = StrokeJoin.Round
            ) {
                moveTo(6.0f, 22.0f)
                arcToRelative(2.0f, 2.0f, 0.0f, isMoreThanHalf = false, isPositiveArc = true, -2.0f, -2.0f)
                verticalLineTo(4.0f)
                arcToRelative(2.0f, 2.0f, 0.0f, isMoreThanHalf = false, isPositiveArc = true, 2.0f, -2.0f)
                horizontalLineToRelative(8.0f)
                arcToRelative(2.4f, 2.4f, 0.0f, isMoreThanHalf = false, isPositiveArc = true, 1.704f, 0.706f)
                lineToRelative(3.588f, 3.588f)
                arcTo(2.4f, 2.4f, 0.0f, isMoreThanHalf = false, isPositiveArc = true, 20.0f, 8.0f)
                verticalLineToRelative(12.0f)
                arcToRelative(2.0f, 2.0f, 0.0f, isMoreThanHalf = false, isPositiveArc = true, -2.0f, 2.0f)
                close()
                moveTo(14.0f, 2.0f)
                verticalLineToRelative(5.0f)
                arcToRelative(1.0f, 1.0f, 0.0f, isMoreThanHalf = false, isPositiveArc = false, 1.0f, 1.0f)
                horizontalLineToRelative(5.0f)
                moveTo(10.0f, 9.0f)
                horizontalLineTo(8.0f)
                moveTo(16.0f, 13.0f)
                horizontalLineTo(8.0f)
                moveTo(16.0f, 17.0f)
                horizontalLineTo(8.0f)
            }
        }.build()
    }

    val focus: ImageVector by lazy {
        ImageVector.Builder(
            name = "focus",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).apply {
            path(
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 2f,
                strokeCap = StrokeCap.Round,
                strokeJoin = StrokeJoin.Round
            ) {
                moveTo(9.0f, 12.0f)
                arcTo(3.0f, 3.0f, 0.0f, isMoreThanHalf = false, isPositiveArc = true, 15.0f, 12.0f)
                arcTo(3.0f, 3.0f, 0.0f, isMoreThanHalf = false, isPositiveArc = true, 9.0f, 12.0f)
                close()
                moveTo(3.0f, 7.0f)
                verticalLineTo(5.0f)
                arcToRelative(2.0f, 2.0f, 0.0f, isMoreThanHalf = false, isPositiveArc = true, 2.0f, -2.0f)
                horizontalLineToRelative(2.0f)
                moveTo(17.0f, 3.0f)
                horizontalLineToRelative(2.0f)
                arcToRelative(2.0f, 2.0f, 0.0f, isMoreThanHalf = false, isPositiveArc = true, 2.0f, 2.0f)
                verticalLineToRelative(2.0f)
                moveTo(21.0f, 17.0f)
                verticalLineToRelative(2.0f)
                arcToRelative(2.0f, 2.0f, 0.0f, isMoreThanHalf = false, isPositiveArc = true, -2.0f, 2.0f)
                horizontalLineToRelative(-2.0f)
                moveTo(7.0f, 21.0f)
                horizontalLineTo(5.0f)
                arcToRelative(2.0f, 2.0f, 0.0f, isMoreThanHalf = false, isPositiveArc = true, -2.0f, -2.0f)
                verticalLineToRelative(-2.0f)
            }
        }.build()
    }

    val folder: ImageVector by lazy {
        ImageVector.Builder(
            name = "folder",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).apply {
            path(
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 2f,
                strokeCap = StrokeCap.Round,
                strokeJoin = StrokeJoin.Round
            ) {
                moveTo(20.0f, 20.0f)
                arcToRelative(2.0f, 2.0f, 0.0f, isMoreThanHalf = false, isPositiveArc = false, 2.0f, -2.0f)
                verticalLineTo(8.0f)
                arcToRelative(2.0f, 2.0f, 0.0f, isMoreThanHalf = false, isPositiveArc = false, -2.0f, -2.0f)
                horizontalLineToRelative(-7.9f)
                arcToRelative(2.0f, 2.0f, 0.0f, isMoreThanHalf = false, isPositiveArc = true, -1.69f, -0.9f)
                lineTo(9.6f, 3.9f)
                arcTo(2.0f, 2.0f, 0.0f, isMoreThanHalf = false, isPositiveArc = false, 7.93f, 3.0f)
                horizontalLineTo(4.0f)
                arcToRelative(2.0f, 2.0f, 0.0f, isMoreThanHalf = false, isPositiveArc = false, -2.0f, 2.0f)
                verticalLineToRelative(13.0f)
                arcToRelative(2.0f, 2.0f, 0.0f, isMoreThanHalf = false, isPositiveArc = false, 2.0f, 2.0f)
                close()
            }
        }.build()
    }

    val folderOpen: ImageVector by lazy {
        ImageVector.Builder(
            name = "folder-open",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).apply {
            path(
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 2f,
                strokeCap = StrokeCap.Round,
                strokeJoin = StrokeJoin.Round
            ) {
                moveTo(6.0f, 14.0f)
                lineToRelative(1.5f, -2.9f)
                arcTo(2.0f, 2.0f, 0.0f, isMoreThanHalf = false, isPositiveArc = true, 9.24f, 10.0f)
                horizontalLineTo(20.0f)
                arcToRelative(2.0f, 2.0f, 0.0f, isMoreThanHalf = false, isPositiveArc = true, 1.94f, 2.5f)
                lineToRelative(-1.54f, 6.0f)
                arcToRelative(2.0f, 2.0f, 0.0f, isMoreThanHalf = false, isPositiveArc = true, -1.95f, 1.5f)
                horizontalLineTo(4.0f)
                arcToRelative(2.0f, 2.0f, 0.0f, isMoreThanHalf = false, isPositiveArc = true, -2.0f, -2.0f)
                verticalLineTo(5.0f)
                arcToRelative(2.0f, 2.0f, 0.0f, isMoreThanHalf = false, isPositiveArc = true, 2.0f, -2.0f)
                horizontalLineToRelative(3.9f)
                arcToRelative(2.0f, 2.0f, 0.0f, isMoreThanHalf = false, isPositiveArc = true, 1.69f, 0.9f)
                lineToRelative(0.81f, 1.2f)
                arcToRelative(2.0f, 2.0f, 0.0f, isMoreThanHalf = false, isPositiveArc = false, 1.67f, 0.9f)
                horizontalLineTo(18.0f)
                arcToRelative(2.0f, 2.0f, 0.0f, isMoreThanHalf = false, isPositiveArc = true, 2.0f, 2.0f)
                verticalLineToRelative(2.0f)
            }
        }.build()
    }

    val globe: ImageVector by lazy {
        ImageVector.Builder(
            name = "globe",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).apply {
            path(
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 2f,
                strokeCap = StrokeCap.Round,
                strokeJoin = StrokeJoin.Round
            ) {
                moveTo(2.0f, 12.0f)
                arcTo(10.0f, 10.0f, 0.0f, isMoreThanHalf = false, isPositiveArc = true, 22.0f, 12.0f)
                arcTo(10.0f, 10.0f, 0.0f, isMoreThanHalf = false, isPositiveArc = true, 2.0f, 12.0f)
                close()
                moveTo(12.0f, 2.0f)
                arcToRelative(14.5f, 14.5f, 0.0f, isMoreThanHalf = false, isPositiveArc = false, 0.0f, 20.0f)
                arcToRelative(14.5f, 14.5f, 0.0f, isMoreThanHalf = false, isPositiveArc = false, 0.0f, -20.0f)
                moveTo(2.0f, 12.0f)
                horizontalLineToRelative(20.0f)
            }
        }.build()
    }

    val graduationCap: ImageVector by lazy {
        ImageVector.Builder(
            name = "graduation-cap",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).apply {
            path(
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 2f,
                strokeCap = StrokeCap.Round,
                strokeJoin = StrokeJoin.Round
            ) {
                moveTo(21.42f, 10.922f)
                arcToRelative(1.0f, 1.0f, 0.0f, isMoreThanHalf = false, isPositiveArc = false, -0.019f, -1.838f)
                lineTo(12.83f, 5.18f)
                arcToRelative(2.0f, 2.0f, 0.0f, isMoreThanHalf = false, isPositiveArc = false, -1.66f, 0.0f)
                lineTo(2.6f, 9.08f)
                arcToRelative(1.0f, 1.0f, 0.0f, isMoreThanHalf = false, isPositiveArc = false, 0.0f, 1.832f)
                lineToRelative(8.57f, 3.908f)
                arcToRelative(2.0f, 2.0f, 0.0f, isMoreThanHalf = false, isPositiveArc = false, 1.66f, 0.0f)
                close()
                moveTo(22.0f, 10.0f)
                verticalLineToRelative(6.0f)
                moveTo(6.0f, 12.5f)
                verticalLineTo(16.0f)
                arcToRelative(6.0f, 3.0f, 0.0f, isMoreThanHalf = false, isPositiveArc = false, 12.0f, 0.0f)
                verticalLineToRelative(-3.5f)
            }
        }.build()
    }

    val headphones: ImageVector by lazy {
        ImageVector.Builder(
            name = "headphones",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).apply {
            path(
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 2f,
                strokeCap = StrokeCap.Round,
                strokeJoin = StrokeJoin.Round
            ) {
                moveTo(3.0f, 14.0f)
                horizontalLineToRelative(3.0f)
                arcToRelative(2.0f, 2.0f, 0.0f, isMoreThanHalf = false, isPositiveArc = true, 2.0f, 2.0f)
                verticalLineToRelative(3.0f)
                arcToRelative(2.0f, 2.0f, 0.0f, isMoreThanHalf = false, isPositiveArc = true, -2.0f, 2.0f)
                horizontalLineTo(5.0f)
                arcToRelative(2.0f, 2.0f, 0.0f, isMoreThanHalf = false, isPositiveArc = true, -2.0f, -2.0f)
                verticalLineToRelative(-7.0f)
                arcToRelative(9.0f, 9.0f, 0.0f, isMoreThanHalf = false, isPositiveArc = true, 18.0f, 0.0f)
                verticalLineToRelative(7.0f)
                arcToRelative(2.0f, 2.0f, 0.0f, isMoreThanHalf = false, isPositiveArc = true, -2.0f, 2.0f)
                horizontalLineToRelative(-1.0f)
                arcToRelative(2.0f, 2.0f, 0.0f, isMoreThanHalf = false, isPositiveArc = true, -2.0f, -2.0f)
                verticalLineToRelative(-3.0f)
                arcToRelative(2.0f, 2.0f, 0.0f, isMoreThanHalf = false, isPositiveArc = true, 2.0f, -2.0f)
                horizontalLineToRelative(3.0f)
            }
        }.build()
    }

    val heart: ImageVector by lazy {
        ImageVector.Builder(
            name = "heart",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).apply {
            path(
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 2f,
                strokeCap = StrokeCap.Round,
                strokeJoin = StrokeJoin.Round
            ) {
                moveTo(2.0f, 9.5f)
                arcToRelative(5.5f, 5.5f, 0.0f, isMoreThanHalf = false, isPositiveArc = true, 9.591f, -3.676f)
                arcToRelative(0.56f, 0.56f, 0.0f, isMoreThanHalf = false, isPositiveArc = false, 0.818f, 0.0f)
                arcTo(5.49f, 5.49f, 0.0f, isMoreThanHalf = false, isPositiveArc = true, 22.0f, 9.5f)
                curveToRelative(0.0f, 2.29f, -1.5f, 4.0f, -3.0f, 5.5f)
                lineToRelative(-5.492f, 5.313f)
                arcToRelative(2.0f, 2.0f, 0.0f, isMoreThanHalf = false, isPositiveArc = true, -3.0f, 0.019f)
                lineTo(5.0f, 15.0f)
                curveToRelative(-1.5f, -1.5f, -3.0f, -3.2f, -3.0f, -5.5f)
            }
        }.build()
    }

    val house: ImageVector by lazy {
        ImageVector.Builder(
            name = "house",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).apply {
            path(
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 2f,
                strokeCap = StrokeCap.Round,
                strokeJoin = StrokeJoin.Round
            ) {
                moveTo(15.0f, 21.0f)
                verticalLineToRelative(-8.0f)
                arcToRelative(1.0f, 1.0f, 0.0f, isMoreThanHalf = false, isPositiveArc = false, -1.0f, -1.0f)
                horizontalLineToRelative(-4.0f)
                arcToRelative(1.0f, 1.0f, 0.0f, isMoreThanHalf = false, isPositiveArc = false, -1.0f, 1.0f)
                verticalLineToRelative(8.0f)
                moveTo(3.0f, 10.0f)
                arcToRelative(2.0f, 2.0f, 0.0f, isMoreThanHalf = false, isPositiveArc = true, 0.709f, -1.528f)
                lineToRelative(7.0f, -6.0f)
                arcToRelative(2.0f, 2.0f, 0.0f, isMoreThanHalf = false, isPositiveArc = true, 2.582f, 0.0f)
                lineToRelative(7.0f, 6.0f)
                arcTo(2.0f, 2.0f, 0.0f, isMoreThanHalf = false, isPositiveArc = true, 21.0f, 10.0f)
                verticalLineToRelative(9.0f)
                arcToRelative(2.0f, 2.0f, 0.0f, isMoreThanHalf = false, isPositiveArc = true, -2.0f, 2.0f)
                horizontalLineTo(5.0f)
                arcToRelative(2.0f, 2.0f, 0.0f, isMoreThanHalf = false, isPositiveArc = true, -2.0f, -2.0f)
                close()
            }
        }.build()
    }

    val image: ImageVector by lazy {
        ImageVector.Builder(
            name = "image",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).apply {
            path(
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 2f,
                strokeCap = StrokeCap.Round,
                strokeJoin = StrokeJoin.Round
            ) {
                moveTo(5.0f, 3.0f)
                lineTo(19.0f, 3.0f)
                arcTo(2.0f, 2.0f, 0.0f, isMoreThanHalf = false, isPositiveArc = true, 21.0f, 5.0f)
                lineTo(21.0f, 19.0f)
                arcTo(2.0f, 2.0f, 0.0f, isMoreThanHalf = false, isPositiveArc = true, 19.0f, 21.0f)
                lineTo(5.0f, 21.0f)
                arcTo(2.0f, 2.0f, 0.0f, isMoreThanHalf = false, isPositiveArc = true, 3.0f, 19.0f)
                lineTo(3.0f, 5.0f)
                arcTo(2.0f, 2.0f, 0.0f, isMoreThanHalf = false, isPositiveArc = true, 5.0f, 3.0f)
                close()
                moveTo(7.0f, 9.0f)
                arcTo(2.0f, 2.0f, 0.0f, isMoreThanHalf = false, isPositiveArc = true, 11.0f, 9.0f)
                arcTo(2.0f, 2.0f, 0.0f, isMoreThanHalf = false, isPositiveArc = true, 7.0f, 9.0f)
                close()
                moveTo(21.0f, 15.0f)
                lineToRelative(-3.086f, -3.086f)
                arcToRelative(2.0f, 2.0f, 0.0f, isMoreThanHalf = false, isPositiveArc = false, -2.828f, 0.0f)
                lineTo(6.0f, 21.0f)
            }
        }.build()
    }

    val images: ImageVector by lazy {
        ImageVector.Builder(
            name = "images",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).apply {
            path(
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 2f,
                strokeCap = StrokeCap.Round,
                strokeJoin = StrokeJoin.Round
            ) {
                moveTo(22.0f, 11.0f)
                lineToRelative(-1.296f, -1.296f)
                arcToRelative(2.4f, 2.4f, 0.0f, isMoreThanHalf = false, isPositiveArc = false, -3.408f, 0.0f)
                lineTo(11.0f, 16.0f)
                moveTo(4.0f, 8.0f)
                arcToRelative(2.0f, 2.0f, 0.0f, isMoreThanHalf = false, isPositiveArc = false, -2.0f, 2.0f)
                verticalLineToRelative(10.0f)
                arcToRelative(2.0f, 2.0f, 0.0f, isMoreThanHalf = false, isPositiveArc = false, 2.0f, 2.0f)
                horizontalLineToRelative(10.0f)
                arcToRelative(2.0f, 2.0f, 0.0f, isMoreThanHalf = false, isPositiveArc = false, 2.0f, -2.0f)
                moveTo(12.0f, 7.0f)
                arcTo(1.0f, 1.0f, 0.0f, isMoreThanHalf = false, isPositiveArc = true, 14.0f, 7.0f)
                arcTo(1.0f, 1.0f, 0.0f, isMoreThanHalf = false, isPositiveArc = true, 12.0f, 7.0f)
                close()
                moveTo(10.0f, 2.0f)
                lineTo(20.0f, 2.0f)
                arcTo(2.0f, 2.0f, 0.0f, isMoreThanHalf = false, isPositiveArc = true, 22.0f, 4.0f)
                lineTo(22.0f, 14.0f)
                arcTo(2.0f, 2.0f, 0.0f, isMoreThanHalf = false, isPositiveArc = true, 20.0f, 16.0f)
                lineTo(10.0f, 16.0f)
                arcTo(2.0f, 2.0f, 0.0f, isMoreThanHalf = false, isPositiveArc = true, 8.0f, 14.0f)
                lineTo(8.0f, 4.0f)
                arcTo(2.0f, 2.0f, 0.0f, isMoreThanHalf = false, isPositiveArc = true, 10.0f, 2.0f)
                close()
            }
        }.build()
    }

    val info: ImageVector by lazy {
        ImageVector.Builder(
            name = "info",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).apply {
            path(
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 2f,
                strokeCap = StrokeCap.Round,
                strokeJoin = StrokeJoin.Round
            ) {
                moveTo(2.0f, 12.0f)
                arcTo(10.0f, 10.0f, 0.0f, isMoreThanHalf = false, isPositiveArc = true, 22.0f, 12.0f)
                arcTo(10.0f, 10.0f, 0.0f, isMoreThanHalf = false, isPositiveArc = true, 2.0f, 12.0f)
                close()
                moveTo(12.0f, 16.0f)
                verticalLineToRelative(-4.0f)
                moveTo(12.0f, 8.0f)
                horizontalLineToRelative(0.01f)
            }
        }.build()
    }

    val key: ImageVector by lazy {
        ImageVector.Builder(
            name = "key",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).apply {
            path(
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 2f,
                strokeCap = StrokeCap.Round,
                strokeJoin = StrokeJoin.Round
            ) {
                moveTo(2.0f, 21.0f)
                lineToRelative(9.6f, -9.6f)
                moveTo(7.5f, 15.5f)
                lineToRelative(2.3f, 2.3f)
                arcToRelative(1.0f, 1.0f, 0.0f, isMoreThanHalf = false, isPositiveArc = true, 0.0f, 1.4f)
                lineToRelative(-2.1f, 2.1f)
                arcToRelative(1.0f, 1.0f, 0.0f, isMoreThanHalf = false, isPositiveArc = true, -1.4f, 0.0f)
                lineTo(4.0f, 19.0f)
                moveTo(10.0f, 7.5f)
                arcTo(5.5f, 5.5f, 0.0f, isMoreThanHalf = false, isPositiveArc = true, 21.0f, 7.5f)
                arcTo(5.5f, 5.5f, 0.0f, isMoreThanHalf = false, isPositiveArc = true, 10.0f, 7.5f)
                close()
            }
        }.build()
    }

    val laptop: ImageVector by lazy {
        ImageVector.Builder(
            name = "laptop",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).apply {
            path(
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 2f,
                strokeCap = StrokeCap.Round,
                strokeJoin = StrokeJoin.Round
            ) {
                moveTo(18.0f, 5.0f)
                arcToRelative(2.0f, 2.0f, 0.0f, isMoreThanHalf = false, isPositiveArc = true, 2.0f, 2.0f)
                verticalLineToRelative(8.526f)
                arcToRelative(2.0f, 2.0f, 0.0f, isMoreThanHalf = false, isPositiveArc = false, 0.212f, 0.897f)
                lineToRelative(1.068f, 2.127f)
                arcToRelative(1.0f, 1.0f, 0.0f, isMoreThanHalf = false, isPositiveArc = true, -0.9f, 1.45f)
                horizontalLineTo(3.62f)
                arcToRelative(1.0f, 1.0f, 0.0f, isMoreThanHalf = false, isPositiveArc = true, -0.9f, -1.45f)
                lineToRelative(1.068f, -2.127f)
                arcTo(2.0f, 2.0f, 0.0f, isMoreThanHalf = false, isPositiveArc = false, 4.0f, 15.526f)
                verticalLineTo(7.0f)
                arcToRelative(2.0f, 2.0f, 0.0f, isMoreThanHalf = false, isPositiveArc = true, 2.0f, -2.0f)
                close()
                moveTo(20.054f, 15.987f)
                horizontalLineTo(3.946f)
            }
        }.build()
    }

    val layers: ImageVector by lazy {
        ImageVector.Builder(
            name = "layers",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).apply {
            path(
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 2f,
                strokeCap = StrokeCap.Round,
                strokeJoin = StrokeJoin.Round
            ) {
                moveTo(12.83f, 2.18f)
                arcToRelative(2.0f, 2.0f, 0.0f, isMoreThanHalf = false, isPositiveArc = false, -1.66f, 0.0f)
                lineTo(2.6f, 6.08f)
                arcToRelative(1.0f, 1.0f, 0.0f, isMoreThanHalf = false, isPositiveArc = false, 0.0f, 1.83f)
                lineToRelative(8.58f, 3.91f)
                arcToRelative(2.0f, 2.0f, 0.0f, isMoreThanHalf = false, isPositiveArc = false, 1.66f, 0.0f)
                lineToRelative(8.58f, -3.9f)
                arcToRelative(1.0f, 1.0f, 0.0f, isMoreThanHalf = false, isPositiveArc = false, 0.0f, -1.83f)
                close()
                moveTo(2.0f, 12.0f)
                arcToRelative(1.0f, 1.0f, 0.0f, isMoreThanHalf = false, isPositiveArc = false, 0.58f, 0.91f)
                lineToRelative(8.6f, 3.91f)
                arcToRelative(2.0f, 2.0f, 0.0f, isMoreThanHalf = false, isPositiveArc = false, 1.65f, 0.0f)
                lineToRelative(8.58f, -3.9f)
                arcTo(1.0f, 1.0f, 0.0f, isMoreThanHalf = false, isPositiveArc = false, 22.0f, 12.0f)
                moveTo(2.0f, 17.0f)
                arcToRelative(1.0f, 1.0f, 0.0f, isMoreThanHalf = false, isPositiveArc = false, 0.58f, 0.91f)
                lineToRelative(8.6f, 3.91f)
                arcToRelative(2.0f, 2.0f, 0.0f, isMoreThanHalf = false, isPositiveArc = false, 1.65f, 0.0f)
                lineToRelative(8.58f, -3.9f)
                arcTo(1.0f, 1.0f, 0.0f, isMoreThanHalf = false, isPositiveArc = false, 22.0f, 17.0f)
            }
        }.build()
    }

    val layoutGrid: ImageVector by lazy {
        ImageVector.Builder(
            name = "layout-grid",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).apply {
            path(
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 2f,
                strokeCap = StrokeCap.Round,
                strokeJoin = StrokeJoin.Round
            ) {
                moveTo(4.0f, 3.0f)
                lineTo(9.0f, 3.0f)
                arcTo(1.0f, 1.0f, 0.0f, isMoreThanHalf = false, isPositiveArc = true, 10.0f, 4.0f)
                lineTo(10.0f, 9.0f)
                arcTo(1.0f, 1.0f, 0.0f, isMoreThanHalf = false, isPositiveArc = true, 9.0f, 10.0f)
                lineTo(4.0f, 10.0f)
                arcTo(1.0f, 1.0f, 0.0f, isMoreThanHalf = false, isPositiveArc = true, 3.0f, 9.0f)
                lineTo(3.0f, 4.0f)
                arcTo(1.0f, 1.0f, 0.0f, isMoreThanHalf = false, isPositiveArc = true, 4.0f, 3.0f)
                close()
                moveTo(15.0f, 3.0f)
                lineTo(20.0f, 3.0f)
                arcTo(1.0f, 1.0f, 0.0f, isMoreThanHalf = false, isPositiveArc = true, 21.0f, 4.0f)
                lineTo(21.0f, 9.0f)
                arcTo(1.0f, 1.0f, 0.0f, isMoreThanHalf = false, isPositiveArc = true, 20.0f, 10.0f)
                lineTo(15.0f, 10.0f)
                arcTo(1.0f, 1.0f, 0.0f, isMoreThanHalf = false, isPositiveArc = true, 14.0f, 9.0f)
                lineTo(14.0f, 4.0f)
                arcTo(1.0f, 1.0f, 0.0f, isMoreThanHalf = false, isPositiveArc = true, 15.0f, 3.0f)
                close()
                moveTo(15.0f, 14.0f)
                lineTo(20.0f, 14.0f)
                arcTo(1.0f, 1.0f, 0.0f, isMoreThanHalf = false, isPositiveArc = true, 21.0f, 15.0f)
                lineTo(21.0f, 20.0f)
                arcTo(1.0f, 1.0f, 0.0f, isMoreThanHalf = false, isPositiveArc = true, 20.0f, 21.0f)
                lineTo(15.0f, 21.0f)
                arcTo(1.0f, 1.0f, 0.0f, isMoreThanHalf = false, isPositiveArc = true, 14.0f, 20.0f)
                lineTo(14.0f, 15.0f)
                arcTo(1.0f, 1.0f, 0.0f, isMoreThanHalf = false, isPositiveArc = true, 15.0f, 14.0f)
                close()
                moveTo(4.0f, 14.0f)
                lineTo(9.0f, 14.0f)
                arcTo(1.0f, 1.0f, 0.0f, isMoreThanHalf = false, isPositiveArc = true, 10.0f, 15.0f)
                lineTo(10.0f, 20.0f)
                arcTo(1.0f, 1.0f, 0.0f, isMoreThanHalf = false, isPositiveArc = true, 9.0f, 21.0f)
                lineTo(4.0f, 21.0f)
                arcTo(1.0f, 1.0f, 0.0f, isMoreThanHalf = false, isPositiveArc = true, 3.0f, 20.0f)
                lineTo(3.0f, 15.0f)
                arcTo(1.0f, 1.0f, 0.0f, isMoreThanHalf = false, isPositiveArc = true, 4.0f, 14.0f)
                close()
            }
        }.build()
    }

    val link: ImageVector by lazy {
        ImageVector.Builder(
            name = "link",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).apply {
            path(
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 2f,
                strokeCap = StrokeCap.Round,
                strokeJoin = StrokeJoin.Round
            ) {
                moveTo(10.0f, 13.0f)
                arcToRelative(5.0f, 5.0f, 0.0f, isMoreThanHalf = false, isPositiveArc = false, 7.54f, 0.54f)
                lineToRelative(3.0f, -3.0f)
                arcToRelative(5.0f, 5.0f, 0.0f, isMoreThanHalf = false, isPositiveArc = false, -7.07f, -7.07f)
                lineToRelative(-1.72f, 1.71f)
                moveTo(14.0f, 11.0f)
                arcToRelative(5.0f, 5.0f, 0.0f, isMoreThanHalf = false, isPositiveArc = false, -7.54f, -0.54f)
                lineToRelative(-3.0f, 3.0f)
                arcToRelative(5.0f, 5.0f, 0.0f, isMoreThanHalf = false, isPositiveArc = false, 7.07f, 7.07f)
                lineToRelative(1.71f, -1.71f)
            }
        }.build()
    }

    val lock: ImageVector by lazy {
        ImageVector.Builder(
            name = "lock",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).apply {
            path(
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 2f,
                strokeCap = StrokeCap.Round,
                strokeJoin = StrokeJoin.Round
            ) {
                moveTo(5.0f, 11.0f)
                lineTo(19.0f, 11.0f)
                arcTo(2.0f, 2.0f, 0.0f, isMoreThanHalf = false, isPositiveArc = true, 21.0f, 13.0f)
                lineTo(21.0f, 20.0f)
                arcTo(2.0f, 2.0f, 0.0f, isMoreThanHalf = false, isPositiveArc = true, 19.0f, 22.0f)
                lineTo(5.0f, 22.0f)
                arcTo(2.0f, 2.0f, 0.0f, isMoreThanHalf = false, isPositiveArc = true, 3.0f, 20.0f)
                lineTo(3.0f, 13.0f)
                arcTo(2.0f, 2.0f, 0.0f, isMoreThanHalf = false, isPositiveArc = true, 5.0f, 11.0f)
                close()
                moveTo(7.0f, 11.0f)
                verticalLineTo(7.0f)
                arcToRelative(5.0f, 5.0f, 0.0f, isMoreThanHalf = false, isPositiveArc = true, 10.0f, 0.0f)
                verticalLineToRelative(4.0f)
            }
        }.build()
    }

    val mail: ImageVector by lazy {
        ImageVector.Builder(
            name = "mail",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).apply {
            path(
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 2f,
                strokeCap = StrokeCap.Round,
                strokeJoin = StrokeJoin.Round
            ) {
                moveTo(22.0f, 7.0f)
                lineToRelative(-8.991f, 5.727f)
                arcToRelative(2.0f, 2.0f, 0.0f, isMoreThanHalf = false, isPositiveArc = true, -2.009f, 0.0f)
                lineTo(2.0f, 7.0f)
                moveTo(4.0f, 4.0f)
                lineTo(20.0f, 4.0f)
                arcTo(2.0f, 2.0f, 0.0f, isMoreThanHalf = false, isPositiveArc = true, 22.0f, 6.0f)
                lineTo(22.0f, 18.0f)
                arcTo(2.0f, 2.0f, 0.0f, isMoreThanHalf = false, isPositiveArc = true, 20.0f, 20.0f)
                lineTo(4.0f, 20.0f)
                arcTo(2.0f, 2.0f, 0.0f, isMoreThanHalf = false, isPositiveArc = true, 2.0f, 18.0f)
                lineTo(2.0f, 6.0f)
                arcTo(2.0f, 2.0f, 0.0f, isMoreThanHalf = false, isPositiveArc = true, 4.0f, 4.0f)
                close()
            }
        }.build()
    }

    val map: ImageVector by lazy {
        ImageVector.Builder(
            name = "map",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).apply {
            path(
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 2f,
                strokeCap = StrokeCap.Round,
                strokeJoin = StrokeJoin.Round
            ) {
                moveTo(14.106f, 5.553f)
                arcToRelative(2.0f, 2.0f, 0.0f, isMoreThanHalf = false, isPositiveArc = false, 1.788f, 0.0f)
                lineToRelative(3.659f, -1.83f)
                arcTo(1.0f, 1.0f, 0.0f, isMoreThanHalf = false, isPositiveArc = true, 21.0f, 4.619f)
                verticalLineToRelative(12.764f)
                arcToRelative(1.0f, 1.0f, 0.0f, isMoreThanHalf = false, isPositiveArc = true, -0.553f, 0.894f)
                lineToRelative(-4.553f, 2.277f)
                arcToRelative(2.0f, 2.0f, 0.0f, isMoreThanHalf = false, isPositiveArc = true, -1.788f, 0.0f)
                lineToRelative(-4.212f, -2.106f)
                arcToRelative(2.0f, 2.0f, 0.0f, isMoreThanHalf = false, isPositiveArc = false, -1.788f, 0.0f)
                lineToRelative(-3.659f, 1.83f)
                arcTo(1.0f, 1.0f, 0.0f, isMoreThanHalf = false, isPositiveArc = true, 3.0f, 19.381f)
                verticalLineTo(6.618f)
                arcToRelative(1.0f, 1.0f, 0.0f, isMoreThanHalf = false, isPositiveArc = true, 0.553f, -0.894f)
                lineToRelative(4.553f, -2.277f)
                arcToRelative(2.0f, 2.0f, 0.0f, isMoreThanHalf = false, isPositiveArc = true, 1.788f, 0.0f)
                close()
                moveTo(15.0f, 5.764f)
                verticalLineToRelative(15.0f)
                moveTo(9.0f, 3.236f)
                verticalLineToRelative(15.0f)
            }
        }.build()
    }

    val megaphone: ImageVector by lazy {
        ImageVector.Builder(
            name = "megaphone",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).apply {
            path(
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 2f,
                strokeCap = StrokeCap.Round,
                strokeJoin = StrokeJoin.Round
            ) {
                moveTo(11.0f, 6.0f)
                arcToRelative(13.0f, 13.0f, 0.0f, isMoreThanHalf = false, isPositiveArc = false, 8.4f, -2.8f)
                arcTo(1.0f, 1.0f, 0.0f, isMoreThanHalf = false, isPositiveArc = true, 21.0f, 4.0f)
                verticalLineToRelative(12.0f)
                arcToRelative(1.0f, 1.0f, 0.0f, isMoreThanHalf = false, isPositiveArc = true, -1.6f, 0.8f)
                arcTo(13.0f, 13.0f, 0.0f, isMoreThanHalf = false, isPositiveArc = false, 11.0f, 14.0f)
                horizontalLineTo(5.0f)
                arcToRelative(2.0f, 2.0f, 0.0f, isMoreThanHalf = false, isPositiveArc = true, -2.0f, -2.0f)
                verticalLineTo(8.0f)
                arcToRelative(2.0f, 2.0f, 0.0f, isMoreThanHalf = false, isPositiveArc = true, 2.0f, -2.0f)
                close()
                moveTo(6.0f, 14.0f)
                arcToRelative(12.0f, 12.0f, 0.0f, isMoreThanHalf = false, isPositiveArc = false, 2.4f, 7.2f)
                arcToRelative(2.0f, 2.0f, 0.0f, isMoreThanHalf = false, isPositiveArc = false, 3.2f, -2.4f)
                arcTo(8.0f, 8.0f, 0.0f, isMoreThanHalf = false, isPositiveArc = true, 10.0f, 14.0f)
                moveTo(8.0f, 6.0f)
                verticalLineToRelative(8.0f)
            }
        }.build()
    }

    val menu: ImageVector by lazy {
        ImageVector.Builder(
            name = "menu",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).apply {
            path(
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 2f,
                strokeCap = StrokeCap.Round,
                strokeJoin = StrokeJoin.Round
            ) {
                moveTo(4.0f, 5.0f)
                horizontalLineToRelative(16.0f)
                moveTo(4.0f, 12.0f)
                horizontalLineToRelative(16.0f)
                moveTo(4.0f, 19.0f)
                horizontalLineToRelative(16.0f)
            }
        }.build()
    }

    val messageCircle: ImageVector by lazy {
        ImageVector.Builder(
            name = "message-circle",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).apply {
            path(
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 2f,
                strokeCap = StrokeCap.Round,
                strokeJoin = StrokeJoin.Round
            ) {
                moveTo(2.992f, 16.342f)
                arcToRelative(2.0f, 2.0f, 0.0f, isMoreThanHalf = false, isPositiveArc = true, 0.094f, 1.167f)
                lineToRelative(-1.065f, 3.29f)
                arcToRelative(1.0f, 1.0f, 0.0f, isMoreThanHalf = false, isPositiveArc = false, 1.236f, 1.168f)
                lineToRelative(3.413f, -0.998f)
                arcToRelative(2.0f, 2.0f, 0.0f, isMoreThanHalf = false, isPositiveArc = true, 1.099f, 0.092f)
                arcToRelative(10.0f, 10.0f, 0.0f, isMoreThanHalf = true, isPositiveArc = false, -4.777f, -4.719f)
            }
        }.build()
    }

    val messageSquare: ImageVector by lazy {
        ImageVector.Builder(
            name = "message-square",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).apply {
            path(
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 2f,
                strokeCap = StrokeCap.Round,
                strokeJoin = StrokeJoin.Round
            ) {
                moveTo(22.0f, 17.0f)
                arcToRelative(2.0f, 2.0f, 0.0f, isMoreThanHalf = false, isPositiveArc = true, -2.0f, 2.0f)
                horizontalLineTo(6.828f)
                arcToRelative(2.0f, 2.0f, 0.0f, isMoreThanHalf = false, isPositiveArc = false, -1.414f, 0.586f)
                lineToRelative(-2.202f, 2.202f)
                arcTo(0.71f, 0.71f, 0.0f, isMoreThanHalf = false, isPositiveArc = true, 2.0f, 21.286f)
                verticalLineTo(5.0f)
                arcToRelative(2.0f, 2.0f, 0.0f, isMoreThanHalf = false, isPositiveArc = true, 2.0f, -2.0f)
                horizontalLineToRelative(16.0f)
                arcToRelative(2.0f, 2.0f, 0.0f, isMoreThanHalf = false, isPositiveArc = true, 2.0f, 2.0f)
                close()
            }
        }.build()
    }

    val messageSquareDot: ImageVector by lazy {
        ImageVector.Builder(
            name = "message-square-dot",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).apply {
            path(
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 2f,
                strokeCap = StrokeCap.Round,
                strokeJoin = StrokeJoin.Round
            ) {
                moveTo(12.7f, 3.0f)
                horizontalLineTo(4.0f)
                arcToRelative(2.0f, 2.0f, 0.0f, isMoreThanHalf = false, isPositiveArc = false, -2.0f, 2.0f)
                verticalLineToRelative(16.286f)
                arcToRelative(0.71f, 0.71f, 0.0f, isMoreThanHalf = false, isPositiveArc = false, 1.212f, 0.502f)
                lineToRelative(2.202f, -2.202f)
                arcTo(2.0f, 2.0f, 0.0f, isMoreThanHalf = false, isPositiveArc = true, 6.828f, 19.0f)
                horizontalLineTo(20.0f)
                arcToRelative(2.0f, 2.0f, 0.0f, isMoreThanHalf = false, isPositiveArc = false, 2.0f, -2.0f)
                verticalLineToRelative(-4.7f)
                moveTo(16.0f, 6.0f)
                arcTo(3.0f, 3.0f, 0.0f, isMoreThanHalf = false, isPositiveArc = true, 22.0f, 6.0f)
                arcTo(3.0f, 3.0f, 0.0f, isMoreThanHalf = false, isPositiveArc = true, 16.0f, 6.0f)
                close()
            }
        }.build()
    }

    val messageSquarePlus: ImageVector by lazy {
        ImageVector.Builder(
            name = "message-square-plus",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).apply {
            path(
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 2f,
                strokeCap = StrokeCap.Round,
                strokeJoin = StrokeJoin.Round
            ) {
                moveTo(22.0f, 17.0f)
                arcToRelative(2.0f, 2.0f, 0.0f, isMoreThanHalf = false, isPositiveArc = true, -2.0f, 2.0f)
                horizontalLineTo(6.828f)
                arcToRelative(2.0f, 2.0f, 0.0f, isMoreThanHalf = false, isPositiveArc = false, -1.414f, 0.586f)
                lineToRelative(-2.202f, 2.202f)
                arcTo(0.71f, 0.71f, 0.0f, isMoreThanHalf = false, isPositiveArc = true, 2.0f, 21.286f)
                verticalLineTo(5.0f)
                arcToRelative(2.0f, 2.0f, 0.0f, isMoreThanHalf = false, isPositiveArc = true, 2.0f, -2.0f)
                horizontalLineToRelative(16.0f)
                arcToRelative(2.0f, 2.0f, 0.0f, isMoreThanHalf = false, isPositiveArc = true, 2.0f, 2.0f)
                close()
                moveTo(12.0f, 8.0f)
                verticalLineToRelative(6.0f)
                moveTo(9.0f, 11.0f)
                horizontalLineToRelative(6.0f)
            }
        }.build()
    }

    val messageSquareText: ImageVector by lazy {
        ImageVector.Builder(
            name = "message-square-text",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).apply {
            path(
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 2f,
                strokeCap = StrokeCap.Round,
                strokeJoin = StrokeJoin.Round
            ) {
                moveTo(22.0f, 17.0f)
                arcToRelative(2.0f, 2.0f, 0.0f, isMoreThanHalf = false, isPositiveArc = true, -2.0f, 2.0f)
                horizontalLineTo(6.828f)
                arcToRelative(2.0f, 2.0f, 0.0f, isMoreThanHalf = false, isPositiveArc = false, -1.414f, 0.586f)
                lineToRelative(-2.202f, 2.202f)
                arcTo(0.71f, 0.71f, 0.0f, isMoreThanHalf = false, isPositiveArc = true, 2.0f, 21.286f)
                verticalLineTo(5.0f)
                arcToRelative(2.0f, 2.0f, 0.0f, isMoreThanHalf = false, isPositiveArc = true, 2.0f, -2.0f)
                horizontalLineToRelative(16.0f)
                arcToRelative(2.0f, 2.0f, 0.0f, isMoreThanHalf = false, isPositiveArc = true, 2.0f, 2.0f)
                close()
                moveTo(7.0f, 11.0f)
                horizontalLineToRelative(10.0f)
                moveTo(7.0f, 15.0f)
                horizontalLineToRelative(6.0f)
                moveTo(7.0f, 7.0f)
                horizontalLineToRelative(8.0f)
            }
        }.build()
    }

    val mic: ImageVector by lazy {
        ImageVector.Builder(
            name = "mic",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).apply {
            path(
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 2f,
                strokeCap = StrokeCap.Round,
                strokeJoin = StrokeJoin.Round
            ) {
                moveTo(12.0f, 19.0f)
                verticalLineToRelative(3.0f)
                moveTo(19.0f, 10.0f)
                verticalLineToRelative(2.0f)
                arcToRelative(7.0f, 7.0f, 0.0f, isMoreThanHalf = false, isPositiveArc = true, -14.0f, 0.0f)
                verticalLineToRelative(-2.0f)
                moveTo(12.0f, 2.0f)
                lineTo(12.0f, 2.0f)
                arcTo(3.0f, 3.0f, 0.0f, isMoreThanHalf = false, isPositiveArc = true, 15.0f, 5.0f)
                lineTo(15.0f, 12.0f)
                arcTo(3.0f, 3.0f, 0.0f, isMoreThanHalf = false, isPositiveArc = true, 12.0f, 15.0f)
                lineTo(12.0f, 15.0f)
                arcTo(3.0f, 3.0f, 0.0f, isMoreThanHalf = false, isPositiveArc = true, 9.0f, 12.0f)
                lineTo(9.0f, 5.0f)
                arcTo(3.0f, 3.0f, 0.0f, isMoreThanHalf = false, isPositiveArc = true, 12.0f, 2.0f)
                close()
            }
        }.build()
    }

    val moonStar: ImageVector by lazy {
        ImageVector.Builder(
            name = "moon-star",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).apply {
            path(
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 2f,
                strokeCap = StrokeCap.Round,
                strokeJoin = StrokeJoin.Round
            ) {
                moveTo(18.0f, 5.0f)
                horizontalLineToRelative(4.0f)
                moveTo(20.0f, 3.0f)
                verticalLineToRelative(4.0f)
                moveTo(20.985f, 12.486f)
                arcToRelative(9.0f, 9.0f, 0.0f, isMoreThanHalf = true, isPositiveArc = true, -9.473f, -9.472f)
                curveToRelative(0.405f, -0.022f, 0.617f, 0.46f, 0.402f, 0.803f)
                arcToRelative(6.0f, 6.0f, 0.0f, isMoreThanHalf = false, isPositiveArc = false, 8.268f, 8.268f)
                curveToRelative(0.344f, -0.215f, 0.825f, -0.004f, 0.803f, 0.401f)
            }
        }.build()
    }

    val newspaper: ImageVector by lazy {
        ImageVector.Builder(
            name = "newspaper",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).apply {
            path(
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 2f,
                strokeCap = StrokeCap.Round,
                strokeJoin = StrokeJoin.Round
            ) {
                moveTo(15.0f, 18.0f)
                horizontalLineToRelative(-5.0f)
                moveTo(18.0f, 14.0f)
                horizontalLineToRelative(-8.0f)
                moveTo(4.0f, 22.0f)
                horizontalLineToRelative(16.0f)
                arcToRelative(2.0f, 2.0f, 0.0f, isMoreThanHalf = false, isPositiveArc = false, 2.0f, -2.0f)
                verticalLineTo(4.0f)
                arcToRelative(2.0f, 2.0f, 0.0f, isMoreThanHalf = false, isPositiveArc = false, -2.0f, -2.0f)
                horizontalLineTo(8.0f)
                arcToRelative(2.0f, 2.0f, 0.0f, isMoreThanHalf = false, isPositiveArc = false, -2.0f, 2.0f)
                verticalLineToRelative(16.0f)
                arcToRelative(2.0f, 2.0f, 0.0f, isMoreThanHalf = false, isPositiveArc = true, -4.0f, 0.0f)
                verticalLineToRelative(-9.0f)
                arcToRelative(2.0f, 2.0f, 0.0f, isMoreThanHalf = false, isPositiveArc = true, 2.0f, -2.0f)
                horizontalLineToRelative(2.0f)
                moveTo(11.0f, 6.0f)
                lineTo(17.0f, 6.0f)
                arcTo(1.0f, 1.0f, 0.0f, isMoreThanHalf = false, isPositiveArc = true, 18.0f, 7.0f)
                lineTo(18.0f, 9.0f)
                arcTo(1.0f, 1.0f, 0.0f, isMoreThanHalf = false, isPositiveArc = true, 17.0f, 10.0f)
                lineTo(11.0f, 10.0f)
                arcTo(1.0f, 1.0f, 0.0f, isMoreThanHalf = false, isPositiveArc = true, 10.0f, 9.0f)
                lineTo(10.0f, 7.0f)
                arcTo(1.0f, 1.0f, 0.0f, isMoreThanHalf = false, isPositiveArc = true, 11.0f, 6.0f)
                close()
            }
        }.build()
    }

    val partyPopper: ImageVector by lazy {
        ImageVector.Builder(
            name = "party-popper",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).apply {
            path(
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 2f,
                strokeCap = StrokeCap.Round,
                strokeJoin = StrokeJoin.Round
            ) {
                moveTo(5.8f, 11.3f)
                lineTo(2.0f, 22.0f)
                lineToRelative(10.7f, -3.79f)
                moveTo(4.0f, 3.0f)
                horizontalLineToRelative(0.01f)
                moveTo(22.0f, 8.0f)
                horizontalLineToRelative(0.01f)
                moveTo(15.0f, 2.0f)
                horizontalLineToRelative(0.01f)
                moveTo(22.0f, 20.0f)
                horizontalLineToRelative(0.01f)
                moveTo(22.0f, 2.0f)
                lineToRelative(-2.24f, 0.75f)
                arcToRelative(2.9f, 2.9f, 0.0f, isMoreThanHalf = false, isPositiveArc = false, -1.96f, 3.12f)
                curveToRelative(0.1f, 0.86f, -0.57f, 1.63f, -1.45f, 1.63f)
                horizontalLineToRelative(-0.38f)
                curveToRelative(-0.86f, 0.0f, -1.6f, 0.6f, -1.76f, 1.44f)
                lineTo(14.0f, 10.0f)
                moveTo(22.0f, 13.0f)
                lineToRelative(-0.82f, -0.33f)
                curveToRelative(-0.86f, -0.34f, -1.82f, 0.2f, -1.98f, 1.11f)
                curveToRelative(-0.11f, 0.7f, -0.72f, 1.22f, -1.43f, 1.22f)
                horizontalLineTo(17.0f)
                moveTo(11.0f, 2.0f)
                lineToRelative(0.33f, 0.82f)
                curveToRelative(0.34f, 0.86f, -0.2f, 1.82f, -1.11f, 1.98f)
                curveTo(9.52f, 4.9f, 9.0f, 5.52f, 9.0f, 6.23f)
                verticalLineTo(7.0f)
                moveTo(11.0f, 13.0f)
                curveToRelative(1.93f, 1.93f, 2.83f, 4.17f, 2.0f, 5.0f)
                curveToRelative(-0.83f, 0.83f, -3.07f, -0.07f, -5.0f, -2.0f)
                curveToRelative(-1.93f, -1.93f, -2.83f, -4.17f, -2.0f, -5.0f)
                curveToRelative(0.83f, -0.83f, 3.07f, 0.07f, 5.0f, 2.0f)
                close()
            }
        }.build()
    }

    val penLine: ImageVector by lazy {
        ImageVector.Builder(
            name = "pen-line",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).apply {
            path(
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 2f,
                strokeCap = StrokeCap.Round,
                strokeJoin = StrokeJoin.Round
            ) {
                moveTo(13.0f, 21.0f)
                horizontalLineToRelative(8.0f)
                moveTo(21.174f, 6.812f)
                arcToRelative(1.0f, 1.0f, 0.0f, isMoreThanHalf = false, isPositiveArc = false, -3.986f, -3.987f)
                lineTo(3.842f, 16.174f)
                arcToRelative(2.0f, 2.0f, 0.0f, isMoreThanHalf = false, isPositiveArc = false, -0.5f, 0.83f)
                lineToRelative(-1.321f, 4.352f)
                arcToRelative(0.5f, 0.5f, 0.0f, isMoreThanHalf = false, isPositiveArc = false, 0.623f, 0.622f)
                lineToRelative(4.353f, -1.32f)
                arcToRelative(2.0f, 2.0f, 0.0f, isMoreThanHalf = false, isPositiveArc = false, 0.83f, -0.497f)
                close()
            }
        }.build()
    }

    val phoneCall: ImageVector by lazy {
        ImageVector.Builder(
            name = "phone-call",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).apply {
            path(
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 2f,
                strokeCap = StrokeCap.Round,
                strokeJoin = StrokeJoin.Round
            ) {
                moveTo(13.0f, 2.0f)
                arcToRelative(9.0f, 9.0f, 0.0f, isMoreThanHalf = false, isPositiveArc = true, 9.0f, 9.0f)
                moveTo(13.0f, 6.0f)
                arcToRelative(5.0f, 5.0f, 0.0f, isMoreThanHalf = false, isPositiveArc = true, 5.0f, 5.0f)
                moveTo(13.832f, 16.568f)
                arcToRelative(1.0f, 1.0f, 0.0f, isMoreThanHalf = false, isPositiveArc = false, 1.213f, -0.303f)
                lineToRelative(0.355f, -0.465f)
                arcTo(2.0f, 2.0f, 0.0f, isMoreThanHalf = false, isPositiveArc = true, 17.0f, 15.0f)
                horizontalLineToRelative(3.0f)
                arcToRelative(2.0f, 2.0f, 0.0f, isMoreThanHalf = false, isPositiveArc = true, 2.0f, 2.0f)
                verticalLineToRelative(3.0f)
                arcToRelative(2.0f, 2.0f, 0.0f, isMoreThanHalf = false, isPositiveArc = true, -2.0f, 2.0f)
                arcTo(18.0f, 18.0f, 0.0f, isMoreThanHalf = false, isPositiveArc = true, 2.0f, 4.0f)
                arcToRelative(2.0f, 2.0f, 0.0f, isMoreThanHalf = false, isPositiveArc = true, 2.0f, -2.0f)
                horizontalLineToRelative(3.0f)
                arcToRelative(2.0f, 2.0f, 0.0f, isMoreThanHalf = false, isPositiveArc = true, 2.0f, 2.0f)
                verticalLineToRelative(3.0f)
                arcToRelative(2.0f, 2.0f, 0.0f, isMoreThanHalf = false, isPositiveArc = true, -0.8f, 1.6f)
                lineToRelative(-0.468f, 0.351f)
                arcToRelative(1.0f, 1.0f, 0.0f, isMoreThanHalf = false, isPositiveArc = false, -0.292f, 1.233f)
                arcToRelative(14.0f, 14.0f, 0.0f, isMoreThanHalf = false, isPositiveArc = false, 6.392f, 6.384f)
            }
        }.build()
    }

    val play: ImageVector by lazy {
        ImageVector.Builder(
            name = "play",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).apply {
            path(
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 2f,
                strokeCap = StrokeCap.Round,
                strokeJoin = StrokeJoin.Round
            ) {
                moveTo(5.0f, 5.0f)
                arcToRelative(2.0f, 2.0f, 0.0f, isMoreThanHalf = false, isPositiveArc = true, 3.008f, -1.728f)
                lineToRelative(11.997f, 6.998f)
                arcToRelative(2.0f, 2.0f, 0.0f, isMoreThanHalf = false, isPositiveArc = true, 0.003f, 3.458f)
                lineToRelative(-12.0f, 7.0f)
                arcTo(2.0f, 2.0f, 0.0f, isMoreThanHalf = false, isPositiveArc = true, 5.0f, 19.0f)
                close()
            }
        }.build()
    }

    val plus: ImageVector by lazy {
        ImageVector.Builder(
            name = "plus",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).apply {
            path(
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 2f,
                strokeCap = StrokeCap.Round,
                strokeJoin = StrokeJoin.Round
            ) {
                moveTo(5.0f, 12.0f)
                horizontalLineToRelative(14.0f)
                moveTo(12.0f, 5.0f)
                verticalLineToRelative(14.0f)
            }
        }.build()
    }

    val puzzle: ImageVector by lazy {
        ImageVector.Builder(
            name = "puzzle",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).apply {
            path(
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 2f,
                strokeCap = StrokeCap.Round,
                strokeJoin = StrokeJoin.Round
            ) {
                moveTo(15.39f, 4.39f)
                arcToRelative(1.0f, 1.0f, 0.0f, isMoreThanHalf = false, isPositiveArc = false, 1.68f, -0.474f)
                arcToRelative(2.5f, 2.5f, 0.0f, isMoreThanHalf = true, isPositiveArc = true, 3.014f, 3.015f)
                arcToRelative(1.0f, 1.0f, 0.0f, isMoreThanHalf = false, isPositiveArc = false, -0.474f, 1.68f)
                lineToRelative(1.683f, 1.682f)
                arcToRelative(2.414f, 2.414f, 0.0f, isMoreThanHalf = false, isPositiveArc = true, 0.0f, 3.414f)
                lineTo(19.61f, 15.39f)
                arcToRelative(1.0f, 1.0f, 0.0f, isMoreThanHalf = false, isPositiveArc = true, -1.68f, -0.474f)
                arcToRelative(2.5f, 2.5f, 0.0f, isMoreThanHalf = true, isPositiveArc = false, -3.014f, 3.015f)
                arcToRelative(1.0f, 1.0f, 0.0f, isMoreThanHalf = false, isPositiveArc = true, 0.474f, 1.68f)
                lineToRelative(-1.683f, 1.682f)
                arcToRelative(2.414f, 2.414f, 0.0f, isMoreThanHalf = false, isPositiveArc = true, -3.414f, 0.0f)
                lineTo(8.61f, 19.61f)
                arcToRelative(1.0f, 1.0f, 0.0f, isMoreThanHalf = false, isPositiveArc = false, -1.68f, 0.474f)
                arcToRelative(2.5f, 2.5f, 0.0f, isMoreThanHalf = true, isPositiveArc = true, -3.014f, -3.015f)
                arcToRelative(1.0f, 1.0f, 0.0f, isMoreThanHalf = false, isPositiveArc = false, 0.474f, -1.68f)
                lineToRelative(-1.683f, -1.682f)
                arcToRelative(2.414f, 2.414f, 0.0f, isMoreThanHalf = false, isPositiveArc = true, 0.0f, -3.414f)
                lineTo(4.39f, 8.61f)
                arcToRelative(1.0f, 1.0f, 0.0f, isMoreThanHalf = false, isPositiveArc = true, 1.68f, 0.474f)
                arcToRelative(2.5f, 2.5f, 0.0f, isMoreThanHalf = true, isPositiveArc = false, 3.014f, -3.015f)
                arcToRelative(1.0f, 1.0f, 0.0f, isMoreThanHalf = false, isPositiveArc = true, -0.474f, -1.68f)
                lineToRelative(1.683f, -1.682f)
                arcToRelative(2.414f, 2.414f, 0.0f, isMoreThanHalf = false, isPositiveArc = true, 3.414f, 0.0f)
                close()
            }
        }.build()
    }

    val refreshCw: ImageVector by lazy {
        ImageVector.Builder(
            name = "refresh-cw",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).apply {
            path(
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 2f,
                strokeCap = StrokeCap.Round,
                strokeJoin = StrokeJoin.Round
            ) {
                moveTo(3.0f, 12.0f)
                arcToRelative(9.0f, 9.0f, 0.0f, isMoreThanHalf = false, isPositiveArc = true, 9.0f, -9.0f)
                arcToRelative(9.75f, 9.75f, 0.0f, isMoreThanHalf = false, isPositiveArc = true, 6.74f, 2.74f)
                lineTo(21.0f, 8.0f)
                moveTo(21.0f, 3.0f)
                verticalLineToRelative(5.0f)
                horizontalLineToRelative(-5.0f)
                moveTo(21.0f, 12.0f)
                arcToRelative(9.0f, 9.0f, 0.0f, isMoreThanHalf = false, isPositiveArc = true, -9.0f, 9.0f)
                arcToRelative(9.75f, 9.75f, 0.0f, isMoreThanHalf = false, isPositiveArc = true, -6.74f, -2.74f)
                lineTo(3.0f, 16.0f)
                moveTo(8.0f, 16.0f)
                horizontalLineTo(3.0f)
                verticalLineToRelative(5.0f)
            }
        }.build()
    }

    val save: ImageVector by lazy {
        ImageVector.Builder(
            name = "save",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).apply {
            path(
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 2f,
                strokeCap = StrokeCap.Round,
                strokeJoin = StrokeJoin.Round
            ) {
                moveTo(15.2f, 3.0f)
                arcToRelative(2.0f, 2.0f, 0.0f, isMoreThanHalf = false, isPositiveArc = true, 1.4f, 0.6f)
                lineToRelative(3.8f, 3.8f)
                arcToRelative(2.0f, 2.0f, 0.0f, isMoreThanHalf = false, isPositiveArc = true, 0.6f, 1.4f)
                verticalLineTo(19.0f)
                arcToRelative(2.0f, 2.0f, 0.0f, isMoreThanHalf = false, isPositiveArc = true, -2.0f, 2.0f)
                horizontalLineTo(5.0f)
                arcToRelative(2.0f, 2.0f, 0.0f, isMoreThanHalf = false, isPositiveArc = true, -2.0f, -2.0f)
                verticalLineTo(5.0f)
                arcToRelative(2.0f, 2.0f, 0.0f, isMoreThanHalf = false, isPositiveArc = true, 2.0f, -2.0f)
                close()
                moveTo(17.0f, 21.0f)
                verticalLineToRelative(-7.0f)
                arcToRelative(1.0f, 1.0f, 0.0f, isMoreThanHalf = false, isPositiveArc = false, -1.0f, -1.0f)
                horizontalLineTo(8.0f)
                arcToRelative(1.0f, 1.0f, 0.0f, isMoreThanHalf = false, isPositiveArc = false, -1.0f, 1.0f)
                verticalLineToRelative(7.0f)
                moveTo(7.0f, 3.0f)
                verticalLineToRelative(4.0f)
                arcToRelative(1.0f, 1.0f, 0.0f, isMoreThanHalf = false, isPositiveArc = false, 1.0f, 1.0f)
                horizontalLineToRelative(7.0f)
            }
        }.build()
    }

    val scale: ImageVector by lazy {
        ImageVector.Builder(
            name = "scale",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).apply {
            path(
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 2f,
                strokeCap = StrokeCap.Round,
                strokeJoin = StrokeJoin.Round
            ) {
                moveTo(12.0f, 3.0f)
                verticalLineToRelative(18.0f)
                moveTo(19.0f, 8.0f)
                lineToRelative(3.0f, 8.0f)
                arcToRelative(5.0f, 5.0f, 0.0f, isMoreThanHalf = false, isPositiveArc = true, -6.0f, 0.0f)
                close()
                verticalLineTo(7.0f)
                moveTo(3.0f, 7.0f)
                horizontalLineToRelative(1.0f)
                arcToRelative(17.0f, 17.0f, 0.0f, isMoreThanHalf = false, isPositiveArc = false, 8.0f, -2.0f)
                arcToRelative(17.0f, 17.0f, 0.0f, isMoreThanHalf = false, isPositiveArc = false, 8.0f, 2.0f)
                horizontalLineToRelative(1.0f)
                moveTo(5.0f, 8.0f)
                lineToRelative(3.0f, 8.0f)
                arcToRelative(5.0f, 5.0f, 0.0f, isMoreThanHalf = false, isPositiveArc = true, -6.0f, 0.0f)
                close()
                verticalLineTo(7.0f)
                moveTo(7.0f, 21.0f)
                horizontalLineToRelative(10.0f)
            }
        }.build()
    }

    val scanQrCode: ImageVector by lazy {
        ImageVector.Builder(
            name = "scan-qr-code",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).apply {
            path(
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 2f,
                strokeCap = StrokeCap.Round,
                strokeJoin = StrokeJoin.Round
            ) {
                moveTo(17.0f, 12.0f)
                verticalLineToRelative(4.0f)
                arcToRelative(1.0f, 1.0f, 0.0f, isMoreThanHalf = false, isPositiveArc = true, -1.0f, 1.0f)
                horizontalLineToRelative(-4.0f)
                moveTo(17.0f, 3.0f)
                horizontalLineToRelative(2.0f)
                arcToRelative(2.0f, 2.0f, 0.0f, isMoreThanHalf = false, isPositiveArc = true, 2.0f, 2.0f)
                verticalLineToRelative(2.0f)
                moveTo(17.0f, 8.0f)
                verticalLineTo(7.0f)
                moveTo(21.0f, 17.0f)
                verticalLineToRelative(2.0f)
                arcToRelative(2.0f, 2.0f, 0.0f, isMoreThanHalf = false, isPositiveArc = true, -2.0f, 2.0f)
                horizontalLineToRelative(-2.0f)
                moveTo(3.0f, 7.0f)
                verticalLineTo(5.0f)
                arcToRelative(2.0f, 2.0f, 0.0f, isMoreThanHalf = false, isPositiveArc = true, 2.0f, -2.0f)
                horizontalLineToRelative(2.0f)
                moveTo(7.0f, 17.0f)
                horizontalLineToRelative(0.01f)
                moveTo(7.0f, 21.0f)
                horizontalLineTo(5.0f)
                arcToRelative(2.0f, 2.0f, 0.0f, isMoreThanHalf = false, isPositiveArc = true, -2.0f, -2.0f)
                verticalLineToRelative(-2.0f)
                moveTo(8.0f, 7.0f)
                lineTo(11.0f, 7.0f)
                arcTo(1.0f, 1.0f, 0.0f, isMoreThanHalf = false, isPositiveArc = true, 12.0f, 8.0f)
                lineTo(12.0f, 11.0f)
                arcTo(1.0f, 1.0f, 0.0f, isMoreThanHalf = false, isPositiveArc = true, 11.0f, 12.0f)
                lineTo(8.0f, 12.0f)
                arcTo(1.0f, 1.0f, 0.0f, isMoreThanHalf = false, isPositiveArc = true, 7.0f, 11.0f)
                lineTo(7.0f, 8.0f)
                arcTo(1.0f, 1.0f, 0.0f, isMoreThanHalf = false, isPositiveArc = true, 8.0f, 7.0f)
                close()
            }
        }.build()
    }

    val search: ImageVector by lazy {
        ImageVector.Builder(
            name = "search",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).apply {
            path(
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 2f,
                strokeCap = StrokeCap.Round,
                strokeJoin = StrokeJoin.Round
            ) {
                moveTo(21.0f, 21.0f)
                lineToRelative(-4.34f, -4.34f)
                moveTo(3.0f, 11.0f)
                arcTo(8.0f, 8.0f, 0.0f, isMoreThanHalf = false, isPositiveArc = true, 19.0f, 11.0f)
                arcTo(8.0f, 8.0f, 0.0f, isMoreThanHalf = false, isPositiveArc = true, 3.0f, 11.0f)
                close()
            }
        }.build()
    }

    val send: ImageVector by lazy {
        ImageVector.Builder(
            name = "send",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).apply {
            path(
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 2f,
                strokeCap = StrokeCap.Round,
                strokeJoin = StrokeJoin.Round
            ) {
                moveTo(14.536f, 21.686f)
                arcToRelative(0.5f, 0.5f, 0.0f, isMoreThanHalf = false, isPositiveArc = false, 0.937f, -0.024f)
                lineToRelative(6.5f, -19.0f)
                arcToRelative(0.496f, 0.496f, 0.0f, isMoreThanHalf = false, isPositiveArc = false, -0.635f, -0.635f)
                lineToRelative(-19.0f, 6.5f)
                arcToRelative(0.5f, 0.5f, 0.0f, isMoreThanHalf = false, isPositiveArc = false, -0.024f, 0.937f)
                lineToRelative(7.93f, 3.18f)
                arcToRelative(2.0f, 2.0f, 0.0f, isMoreThanHalf = false, isPositiveArc = true, 1.112f, 1.11f)
                close()
                moveTo(21.854f, 2.147f)
                lineToRelative(-10.94f, 10.939f)
            }
        }.build()
    }

    val settings: ImageVector by lazy {
        ImageVector.Builder(
            name = "settings",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).apply {
            path(
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 2f,
                strokeCap = StrokeCap.Round,
                strokeJoin = StrokeJoin.Round
            ) {
                moveTo(9.671f, 4.136f)
                arcToRelative(2.34f, 2.34f, 0.0f, isMoreThanHalf = false, isPositiveArc = true, 4.659f, 0.0f)
                arcToRelative(2.34f, 2.34f, 0.0f, isMoreThanHalf = false, isPositiveArc = false, 3.319f, 1.915f)
                arcToRelative(2.34f, 2.34f, 0.0f, isMoreThanHalf = false, isPositiveArc = true, 2.33f, 4.033f)
                arcToRelative(2.34f, 2.34f, 0.0f, isMoreThanHalf = false, isPositiveArc = false, 0.0f, 3.831f)
                arcToRelative(2.34f, 2.34f, 0.0f, isMoreThanHalf = false, isPositiveArc = true, -2.33f, 4.033f)
                arcToRelative(2.34f, 2.34f, 0.0f, isMoreThanHalf = false, isPositiveArc = false, -3.319f, 1.915f)
                arcToRelative(2.34f, 2.34f, 0.0f, isMoreThanHalf = false, isPositiveArc = true, -4.659f, 0.0f)
                arcToRelative(2.34f, 2.34f, 0.0f, isMoreThanHalf = false, isPositiveArc = false, -3.32f, -1.915f)
                arcToRelative(2.34f, 2.34f, 0.0f, isMoreThanHalf = false, isPositiveArc = true, -2.33f, -4.033f)
                arcToRelative(2.34f, 2.34f, 0.0f, isMoreThanHalf = false, isPositiveArc = false, 0.0f, -3.831f)
                arcTo(2.34f, 2.34f, 0.0f, isMoreThanHalf = false, isPositiveArc = true, 6.35f, 6.051f)
                arcToRelative(2.34f, 2.34f, 0.0f, isMoreThanHalf = false, isPositiveArc = false, 3.319f, -1.915f)
                moveTo(9.0f, 12.0f)
                arcTo(3.0f, 3.0f, 0.0f, isMoreThanHalf = false, isPositiveArc = true, 15.0f, 12.0f)
                arcTo(3.0f, 3.0f, 0.0f, isMoreThanHalf = false, isPositiveArc = true, 9.0f, 12.0f)
                close()
            }
        }.build()
    }

    val share2: ImageVector by lazy {
        ImageVector.Builder(
            name = "share-2",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).apply {
            path(
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 2f,
                strokeCap = StrokeCap.Round,
                strokeJoin = StrokeJoin.Round
            ) {
                moveTo(15.0f, 5.0f)
                arcTo(3.0f, 3.0f, 0.0f, isMoreThanHalf = false, isPositiveArc = true, 21.0f, 5.0f)
                arcTo(3.0f, 3.0f, 0.0f, isMoreThanHalf = false, isPositiveArc = true, 15.0f, 5.0f)
                close()
                moveTo(3.0f, 12.0f)
                arcTo(3.0f, 3.0f, 0.0f, isMoreThanHalf = false, isPositiveArc = true, 9.0f, 12.0f)
                arcTo(3.0f, 3.0f, 0.0f, isMoreThanHalf = false, isPositiveArc = true, 3.0f, 12.0f)
                close()
                moveTo(15.0f, 19.0f)
                arcTo(3.0f, 3.0f, 0.0f, isMoreThanHalf = false, isPositiveArc = true, 21.0f, 19.0f)
                arcTo(3.0f, 3.0f, 0.0f, isMoreThanHalf = false, isPositiveArc = true, 15.0f, 19.0f)
                close()
                moveTo(8.59f, 13.51f)
                lineTo(15.42f, 17.49f)
                moveTo(15.41f, 6.51f)
                lineTo(8.59f, 10.49f)
            }
        }.build()
    }

    val shieldCheck: ImageVector by lazy {
        ImageVector.Builder(
            name = "shield-check",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).apply {
            path(
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 2f,
                strokeCap = StrokeCap.Round,
                strokeJoin = StrokeJoin.Round
            ) {
                moveTo(20.0f, 13.0f)
                curveToRelative(0.0f, 5.0f, -3.5f, 7.5f, -7.66f, 8.95f)
                arcToRelative(1.0f, 1.0f, 0.0f, isMoreThanHalf = false, isPositiveArc = true, -0.67f, -0.01f)
                curveTo(7.5f, 20.5f, 4.0f, 18.0f, 4.0f, 13.0f)
                verticalLineTo(6.0f)
                arcToRelative(1.0f, 1.0f, 0.0f, isMoreThanHalf = false, isPositiveArc = true, 1.0f, -1.0f)
                curveToRelative(2.0f, 0.0f, 4.5f, -1.2f, 6.24f, -2.72f)
                arcToRelative(1.17f, 1.17f, 0.0f, isMoreThanHalf = false, isPositiveArc = true, 1.52f, 0.0f)
                curveTo(14.51f, 3.81f, 17.0f, 5.0f, 19.0f, 5.0f)
                arcToRelative(1.0f, 1.0f, 0.0f, isMoreThanHalf = false, isPositiveArc = true, 1.0f, 1.0f)
                close()
                moveTo(9.0f, 12.0f)
                lineToRelative(2.0f, 2.0f)
                lineToRelative(4.0f, -4.0f)
            }
        }.build()
    }

    val slidersHorizontal: ImageVector by lazy {
        ImageVector.Builder(
            name = "sliders-horizontal",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).apply {
            path(
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 2f,
                strokeCap = StrokeCap.Round,
                strokeJoin = StrokeJoin.Round
            ) {
                moveTo(10.0f, 5.0f)
                horizontalLineTo(3.0f)
                moveTo(12.0f, 19.0f)
                horizontalLineTo(3.0f)
                moveTo(14.0f, 3.0f)
                verticalLineToRelative(4.0f)
                moveTo(16.0f, 17.0f)
                verticalLineToRelative(4.0f)
                moveTo(21.0f, 12.0f)
                horizontalLineToRelative(-9.0f)
                moveTo(21.0f, 19.0f)
                horizontalLineToRelative(-5.0f)
                moveTo(21.0f, 5.0f)
                horizontalLineToRelative(-7.0f)
                moveTo(8.0f, 10.0f)
                verticalLineToRelative(4.0f)
                moveTo(8.0f, 12.0f)
                horizontalLineTo(3.0f)
            }
        }.build()
    }

    val smartphone: ImageVector by lazy {
        ImageVector.Builder(
            name = "smartphone",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).apply {
            path(
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 2f,
                strokeCap = StrokeCap.Round,
                strokeJoin = StrokeJoin.Round
            ) {
                moveTo(7.0f, 2.0f)
                lineTo(17.0f, 2.0f)
                arcTo(2.0f, 2.0f, 0.0f, isMoreThanHalf = false, isPositiveArc = true, 19.0f, 4.0f)
                lineTo(19.0f, 20.0f)
                arcTo(2.0f, 2.0f, 0.0f, isMoreThanHalf = false, isPositiveArc = true, 17.0f, 22.0f)
                lineTo(7.0f, 22.0f)
                arcTo(2.0f, 2.0f, 0.0f, isMoreThanHalf = false, isPositiveArc = true, 5.0f, 20.0f)
                lineTo(5.0f, 4.0f)
                arcTo(2.0f, 2.0f, 0.0f, isMoreThanHalf = false, isPositiveArc = true, 7.0f, 2.0f)
                close()
                moveTo(12.0f, 18.0f)
                horizontalLineToRelative(0.01f)
            }
        }.build()
    }

    val sparkles: ImageVector by lazy {
        ImageVector.Builder(
            name = "sparkles",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).apply {
            path(
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 2f,
                strokeCap = StrokeCap.Round,
                strokeJoin = StrokeJoin.Round
            ) {
                moveTo(11.017f, 2.814f)
                arcToRelative(1.0f, 1.0f, 0.0f, isMoreThanHalf = false, isPositiveArc = true, 1.966f, 0.0f)
                lineToRelative(1.051f, 5.558f)
                arcToRelative(2.0f, 2.0f, 0.0f, isMoreThanHalf = false, isPositiveArc = false, 1.594f, 1.594f)
                lineToRelative(5.558f, 1.051f)
                arcToRelative(1.0f, 1.0f, 0.0f, isMoreThanHalf = false, isPositiveArc = true, 0.0f, 1.966f)
                lineToRelative(-5.558f, 1.051f)
                arcToRelative(2.0f, 2.0f, 0.0f, isMoreThanHalf = false, isPositiveArc = false, -1.594f, 1.594f)
                lineToRelative(-1.051f, 5.558f)
                arcToRelative(1.0f, 1.0f, 0.0f, isMoreThanHalf = false, isPositiveArc = true, -1.966f, 0.0f)
                lineToRelative(-1.051f, -5.558f)
                arcToRelative(2.0f, 2.0f, 0.0f, isMoreThanHalf = false, isPositiveArc = false, -1.594f, -1.594f)
                lineToRelative(-5.558f, -1.051f)
                arcToRelative(1.0f, 1.0f, 0.0f, isMoreThanHalf = false, isPositiveArc = true, 0.0f, -1.966f)
                lineToRelative(5.558f, -1.051f)
                arcToRelative(2.0f, 2.0f, 0.0f, isMoreThanHalf = false, isPositiveArc = false, 1.594f, -1.594f)
                close()
                moveTo(20.0f, 2.0f)
                verticalLineToRelative(4.0f)
                moveTo(22.0f, 4.0f)
                horizontalLineToRelative(-4.0f)
                moveTo(2.0f, 20.0f)
                arcTo(2.0f, 2.0f, 0.0f, isMoreThanHalf = false, isPositiveArc = true, 6.0f, 20.0f)
                arcTo(2.0f, 2.0f, 0.0f, isMoreThanHalf = false, isPositiveArc = true, 2.0f, 20.0f)
                close()
            }
        }.build()
    }

    val speech: ImageVector by lazy {
        ImageVector.Builder(
            name = "speech",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).apply {
            path(
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 2f,
                strokeCap = StrokeCap.Round,
                strokeJoin = StrokeJoin.Round
            ) {
                moveTo(8.8f, 20.0f)
                verticalLineToRelative(-4.1f)
                lineToRelative(1.9f, 0.2f)
                arcToRelative(2.3f, 2.3f, 0.0f, isMoreThanHalf = false, isPositiveArc = false, 2.164f, -2.1f)
                verticalLineTo(8.3f)
                arcTo(5.37f, 5.37f, 0.0f, isMoreThanHalf = false, isPositiveArc = false, 2.0f, 8.25f)
                curveToRelative(0.0f, 2.8f, 0.656f, 3.054f, 1.0f, 4.55f)
                arcToRelative(5.77f, 5.77f, 0.0f, isMoreThanHalf = false, isPositiveArc = true, 0.029f, 2.758f)
                lineTo(2.0f, 20.0f)
                moveTo(19.8f, 17.8f)
                arcToRelative(7.5f, 7.5f, 0.0f, isMoreThanHalf = false, isPositiveArc = false, 0.003f, -10.603f)
                moveTo(17.0f, 15.0f)
                arcToRelative(3.5f, 3.5f, 0.0f, isMoreThanHalf = false, isPositiveArc = false, -0.025f, -4.975f)
            }
        }.build()
    }

    val square: ImageVector by lazy {
        ImageVector.Builder(
            name = "square",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).apply {
            path(
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 2f,
                strokeCap = StrokeCap.Round,
                strokeJoin = StrokeJoin.Round
            ) {
                moveTo(5.0f, 3.0f)
                lineTo(19.0f, 3.0f)
                arcTo(2.0f, 2.0f, 0.0f, isMoreThanHalf = false, isPositiveArc = true, 21.0f, 5.0f)
                lineTo(21.0f, 19.0f)
                arcTo(2.0f, 2.0f, 0.0f, isMoreThanHalf = false, isPositiveArc = true, 19.0f, 21.0f)
                lineTo(5.0f, 21.0f)
                arcTo(2.0f, 2.0f, 0.0f, isMoreThanHalf = false, isPositiveArc = true, 3.0f, 19.0f)
                lineTo(3.0f, 5.0f)
                arcTo(2.0f, 2.0f, 0.0f, isMoreThanHalf = false, isPositiveArc = true, 5.0f, 3.0f)
                close()
            }
        }.build()
    }

    val sun: ImageVector by lazy {
        ImageVector.Builder(
            name = "sun",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).apply {
            path(
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 2f,
                strokeCap = StrokeCap.Round,
                strokeJoin = StrokeJoin.Round
            ) {
                moveTo(8.0f, 12.0f)
                arcTo(4.0f, 4.0f, 0.0f, isMoreThanHalf = false, isPositiveArc = true, 16.0f, 12.0f)
                arcTo(4.0f, 4.0f, 0.0f, isMoreThanHalf = false, isPositiveArc = true, 8.0f, 12.0f)
                close()
                moveTo(12.0f, 2.0f)
                verticalLineToRelative(2.0f)
                moveTo(12.0f, 20.0f)
                verticalLineToRelative(2.0f)
                moveTo(4.93f, 4.93f)
                lineToRelative(1.41f, 1.41f)
                moveTo(17.66f, 17.66f)
                lineToRelative(1.41f, 1.41f)
                moveTo(2.0f, 12.0f)
                horizontalLineToRelative(2.0f)
                moveTo(20.0f, 12.0f)
                horizontalLineToRelative(2.0f)
                moveTo(6.34f, 17.66f)
                lineToRelative(-1.41f, 1.41f)
                moveTo(19.07f, 4.93f)
                lineToRelative(-1.41f, 1.41f)
            }
        }.build()
    }

    val trash: ImageVector by lazy {
        ImageVector.Builder(
            name = "trash",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).apply {
            path(
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 2f,
                strokeCap = StrokeCap.Round,
                strokeJoin = StrokeJoin.Round
            ) {
                moveTo(10.0f, 11.0f)
                verticalLineToRelative(6.0f)
                moveTo(14.0f, 11.0f)
                verticalLineToRelative(6.0f)
                moveTo(19.0f, 6.0f)
                verticalLineToRelative(14.0f)
                arcToRelative(2.0f, 2.0f, 0.0f, isMoreThanHalf = false, isPositiveArc = true, -2.0f, 2.0f)
                horizontalLineTo(7.0f)
                arcToRelative(2.0f, 2.0f, 0.0f, isMoreThanHalf = false, isPositiveArc = true, -2.0f, -2.0f)
                verticalLineTo(6.0f)
                moveTo(3.0f, 6.0f)
                horizontalLineToRelative(18.0f)
                moveTo(8.0f, 6.0f)
                verticalLineTo(4.0f)
                arcToRelative(2.0f, 2.0f, 0.0f, isMoreThanHalf = false, isPositiveArc = true, 2.0f, -2.0f)
                horizontalLineToRelative(4.0f)
                arcToRelative(2.0f, 2.0f, 0.0f, isMoreThanHalf = false, isPositiveArc = true, 2.0f, 2.0f)
                verticalLineToRelative(2.0f)
            }
        }.build()
    }

    val trendingUp: ImageVector by lazy {
        ImageVector.Builder(
            name = "trending-up",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).apply {
            path(
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 2f,
                strokeCap = StrokeCap.Round,
                strokeJoin = StrokeJoin.Round
            ) {
                moveTo(16.0f, 7.0f)
                horizontalLineToRelative(6.0f)
                verticalLineToRelative(6.0f)
                moveTo(22.0f, 7.0f)
                lineToRelative(-8.5f, 8.5f)
                lineToRelative(-5.0f, -5.0f)
                lineTo(2.0f, 17.0f)
            }
        }.build()
    }

    val undo: ImageVector by lazy {
        ImageVector.Builder(
            name = "undo",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).apply {
            path(
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 2f,
                strokeCap = StrokeCap.Round,
                strokeJoin = StrokeJoin.Round
            ) {
                moveTo(3.0f, 7.0f)
                verticalLineToRelative(6.0f)
                horizontalLineToRelative(6.0f)
                moveTo(21.0f, 17.0f)
                arcToRelative(9.0f, 9.0f, 0.0f, isMoreThanHalf = false, isPositiveArc = false, -9.0f, -9.0f)
                arcToRelative(9.0f, 9.0f, 0.0f, isMoreThanHalf = false, isPositiveArc = false, -6.0f, 2.3f)
                lineTo(3.0f, 13.0f)
            }
        }.build()
    }

    val upload: ImageVector by lazy {
        ImageVector.Builder(
            name = "upload",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).apply {
            path(
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 2f,
                strokeCap = StrokeCap.Round,
                strokeJoin = StrokeJoin.Round
            ) {
                moveTo(12.0f, 3.0f)
                verticalLineToRelative(12.0f)
                moveTo(17.0f, 8.0f)
                lineToRelative(-5.0f, -5.0f)
                lineToRelative(-5.0f, 5.0f)
                moveTo(21.0f, 15.0f)
                verticalLineToRelative(4.0f)
                arcToRelative(2.0f, 2.0f, 0.0f, isMoreThanHalf = false, isPositiveArc = true, -2.0f, 2.0f)
                horizontalLineTo(5.0f)
                arcToRelative(2.0f, 2.0f, 0.0f, isMoreThanHalf = false, isPositiveArc = true, -2.0f, -2.0f)
                verticalLineToRelative(-4.0f)
            }
        }.build()
    }

    val user: ImageVector by lazy {
        ImageVector.Builder(
            name = "user",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).apply {
            path(
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 2f,
                strokeCap = StrokeCap.Round,
                strokeJoin = StrokeJoin.Round
            ) {
                moveTo(19.0f, 21.0f)
                verticalLineToRelative(-2.0f)
                arcToRelative(4.0f, 4.0f, 0.0f, isMoreThanHalf = false, isPositiveArc = false, -4.0f, -4.0f)
                horizontalLineTo(9.0f)
                arcToRelative(4.0f, 4.0f, 0.0f, isMoreThanHalf = false, isPositiveArc = false, -4.0f, 4.0f)
                verticalLineToRelative(2.0f)
                moveTo(8.0f, 7.0f)
                arcTo(4.0f, 4.0f, 0.0f, isMoreThanHalf = false, isPositiveArc = true, 16.0f, 7.0f)
                arcTo(4.0f, 4.0f, 0.0f, isMoreThanHalf = false, isPositiveArc = true, 8.0f, 7.0f)
                close()
            }
        }.build()
    }

    val userCheck: ImageVector by lazy {
        ImageVector.Builder(
            name = "user-check",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).apply {
            path(
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 2f,
                strokeCap = StrokeCap.Round,
                strokeJoin = StrokeJoin.Round
            ) {
                moveTo(16.0f, 11.0f)
                lineToRelative(2.0f, 2.0f)
                lineToRelative(4.0f, -4.0f)
                moveTo(16.0f, 21.0f)
                verticalLineToRelative(-2.0f)
                arcToRelative(4.0f, 4.0f, 0.0f, isMoreThanHalf = false, isPositiveArc = false, -4.0f, -4.0f)
                horizontalLineTo(6.0f)
                arcToRelative(4.0f, 4.0f, 0.0f, isMoreThanHalf = false, isPositiveArc = false, -4.0f, 4.0f)
                verticalLineToRelative(2.0f)
                moveTo(5.0f, 7.0f)
                arcTo(4.0f, 4.0f, 0.0f, isMoreThanHalf = false, isPositiveArc = true, 13.0f, 7.0f)
                arcTo(4.0f, 4.0f, 0.0f, isMoreThanHalf = false, isPositiveArc = true, 5.0f, 7.0f)
                close()
            }
        }.build()
    }

    val x: ImageVector by lazy {
        ImageVector.Builder(
            name = "x",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).apply {
            path(
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 2f,
                strokeCap = StrokeCap.Round,
                strokeJoin = StrokeJoin.Round
            ) {
                moveTo(18.0f, 6.0f)
                lineTo(6.0f, 18.0f)
                moveTo(6.0f, 6.0f)
                lineToRelative(12.0f, 12.0f)
            }
        }.build()
    }
}
