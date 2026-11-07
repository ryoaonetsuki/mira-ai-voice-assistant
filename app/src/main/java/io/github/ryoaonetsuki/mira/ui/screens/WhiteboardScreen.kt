package io.github.ryoaonetsuki.mira.ui.screens

import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.Paint
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.widget.Toast
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.ryoaonetsuki.mira.R
import io.github.ryoaonetsuki.mira.ui.theme.LocalMiraPalette
import io.github.ryoaonetsuki.mira.ui.theme.MiraMotion

data class DrawPath(val path: androidx.compose.ui.graphics.Path, val color: Color, val strokeWidth: Float)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WhiteboardScreen(
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val palette = LocalMiraPalette.current

    val paths = remember { mutableStateListOf<DrawPath>() }
    val undonePaths = remember { mutableStateListOf<DrawPath>() }
    var currentPath by remember { mutableStateOf<androidx.compose.ui.graphics.Path?>(null) }
    var selectedColor by remember { mutableStateOf(Color(0xFF22D3EE)) }
    var strokeWidth by remember { mutableFloatStateOf(8f) }
    var isEraser by remember { mutableStateOf(false) }

    val canvasBackgroundColor = palette.surfaceSubtle

    val primarySwatches = listOf(
        Color(0xFF22D3EE), // Cyan Accent
        Color(0xFF8B5CF6), // Violet Accent
        Color(0xFFFFFFFF), // Crisp White
        Color(0xFFFBBF24), // Warm Amber
        Color(0xFF10B981), // Emerald Green
        Color(0xFFF43F5E), // Rose Pink
        Color(0xFF38BDF8)  // Sky Blue
    )

    fun saveWhiteboardToGallery() {
        try {
            val width = 1080
            val height = 1920
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(bitmap)
            canvas.drawColor(android.graphics.Color.rgb(13, 21, 34)) // Dark canvas surface

            val paint = Paint().apply {
                isAntiAlias = true
                style = Paint.Style.STROKE
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
            }

            for (p in paths) {
                paint.color = p.color.toArgb()
                paint.strokeWidth = p.strokeWidth * 2.5f
                canvas.drawPath(p.path.asAndroidPath(), paint)
            }

            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "mira_whiteboard_${System.currentTimeMillis()}.png")
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
            }

            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            if (uri != null) {
                resolver.openOutputStream(uri)?.use { out ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    values.clear()
                    values.put(MediaStore.Images.Media.IS_PENDING, 0)
                    resolver.update(uri, values, null, null)
                }
                Toast.makeText(context, "Whiteboard saved to Gallery!", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Toast.makeText(context, "Failed to save: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Whiteboard",
                        fontSize = 19.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = palette.textPrimary
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_fluent_arrow_left_24_regular),
                            contentDescription = "Back",
                            tint = palette.textPrimary
                        )
                    }
                },
                actions = {
                    // Undo
                    IconButton(
                        onClick = {
                            if (paths.isNotEmpty()) {
                                val last = paths.removeAt(paths.size - 1)
                                undonePaths.add(last)
                            }
                        },
                        enabled = paths.isNotEmpty()
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_fluent_arrow_undo_24_regular),
                            contentDescription = "Undo",
                            tint = if (paths.isNotEmpty()) palette.textPrimary else palette.textSecondary.copy(alpha = 0.35f)
                        )
                    }
                    // Redo (Mirrored Undo icon)
                    IconButton(
                        onClick = {
                            if (undonePaths.isNotEmpty()) {
                                val restored = undonePaths.removeAt(undonePaths.size - 1)
                                paths.add(restored)
                            }
                        },
                        enabled = undonePaths.isNotEmpty()
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_fluent_arrow_undo_24_regular),
                            contentDescription = "Redo",
                            tint = if (undonePaths.isNotEmpty()) palette.textPrimary else palette.textSecondary.copy(alpha = 0.35f),
                            modifier = Modifier.graphicsLayer(scaleX = -1f)
                        )
                    }
                    // Clear
                    IconButton(
                        onClick = {
                            if (paths.isNotEmpty()) {
                                undonePaths.clear()
                                undonePaths.addAll(paths)
                                paths.clear()
                            }
                        },
                        enabled = paths.isNotEmpty()
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_fluent_delete_24_regular),
                            contentDescription = "Clear Canvas",
                            tint = if (paths.isNotEmpty()) palette.textSecondary else palette.textSecondary.copy(alpha = 0.35f)
                        )
                    }
                    // Save to Gallery
                    IconButton(onClick = { saveWhiteboardToGallery() }) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_fluent_save_24_regular),
                            contentDescription = "Save to Gallery",
                            tint = palette.primary
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = palette.background)
            )
        },
        containerColor = palette.background
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Drawing Canvas Surface
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 120.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(canvasBackgroundColor)
                    .border(1.dp, palette.borderSubtle, RoundedCornerShape(16.dp))
            ) {
                Canvas(
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(isEraser, selectedColor, strokeWidth) {
                            detectDragGestures(
                                onDragStart = { offset ->
                                    val newP = androidx.compose.ui.graphics.Path()
                                    newP.moveTo(offset.x, offset.y)
                                    currentPath = newP
                                },
                                onDrag = { change, _ ->
                                    currentPath?.lineTo(change.position.x, change.position.y)
                                },
                                onDragEnd = {
                                    currentPath?.let {
                                        val strokeColor = if (isEraser) canvasBackgroundColor else selectedColor
                                        val actualWidth = if (isEraser) strokeWidth * 2.5f else strokeWidth
                                        paths.add(DrawPath(it, strokeColor, actualWidth))
                                        undonePaths.clear()
                                    }
                                    currentPath = null
                                },
                                onDragCancel = {
                                    currentPath = null
                                }
                            )
                        }
                ) {
                    for (p in paths) {
                        drawPath(
                            path = p.path,
                            color = p.color,
                            style = Stroke(
                                width = p.strokeWidth,
                                cap = StrokeCap.Round,
                                join = StrokeJoin.Round
                            )
                        )
                    }
                    currentPath?.let {
                        val activeColor = if (isEraser) canvasBackgroundColor else selectedColor
                        val activeWidth = if (isEraser) strokeWidth * 2.5f else strokeWidth
                        drawPath(
                            path = it,
                            color = activeColor,
                            style = Stroke(
                                width = activeWidth,
                                cap = StrokeCap.Round,
                                join = StrokeJoin.Round
                            )
                        )
                    }
                }
            }

            // Floating Tool Dock (Material 3 Expressive floating toolbar)
            Surface(
                shape = RoundedCornerShape(22.dp),
                color = palette.surface,
                tonalElevation = 6.dp,
                shadowElevation = 8.dp,
                border = androidx.compose.foundation.BorderStroke(1.dp, palette.borderSubtle),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 12.dp)
                ) {
                    // Tool Selection & Swatch Picker Row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        // Pen Mode Button
                        Surface(
                            shape = CircleShape,
                            color = if (!isEraser) palette.primary.copy(alpha = 0.2f) else Color.Transparent,
                            border = if (!isEraser) androidx.compose.foundation.BorderStroke(1.dp, palette.primary) else null,
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .clickable { isEraser = false }
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    painter = painterResource(id = R.drawable.ic_fluent_paint_brush_24_regular),
                                    contentDescription = "Pen Tool",
                                    tint = if (!isEraser) palette.primary else palette.textSecondary,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }

                        // Eraser Mode Button
                        Surface(
                            shape = CircleShape,
                            color = if (isEraser) palette.primary.copy(alpha = 0.2f) else Color.Transparent,
                            border = if (isEraser) androidx.compose.foundation.BorderStroke(1.dp, palette.primary) else null,
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .clickable { isEraser = true }
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    painter = painterResource(id = R.drawable.ic_fluent_broom_24_regular),
                                    contentDescription = "Eraser Tool",
                                    tint = if (isEraser) palette.primary else palette.textSecondary,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }

                        Box(
                            modifier = Modifier
                                .height(24.dp)
                                .width(1.dp)
                                .background(palette.borderSubtle)
                        )

                        // 6 Primary Swatches
                        for (col in primarySwatches) {
                            val isSelected = !isEraser && selectedColor == col
                            val animatedBorderColor by animateColorAsState(
                                targetValue = if (isSelected) palette.primary else palette.borderSubtle.copy(alpha = 0.6f),
                                animationSpec = MiraMotion.EffectsFloat,
                                label = "SwatchBorder"
                            )

                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .size(32.dp)
                                    .clip(CircleShape)
                                    .clickable {
                                        selectedColor = col
                                        isEraser = false
                                    }
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(if (isSelected) 30.dp else 26.dp)
                                        .clip(CircleShape)
                                        .background(col)
                                        .border(
                                            width = if (isSelected) 2.5.dp else 1.dp,
                                            color = animatedBorderColor,
                                            shape = CircleShape
                                        )
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // Stroke Width Slider Row
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_fluent_line_thickness_24_regular),
                            contentDescription = "Stroke Width",
                            tint = palette.textSecondary,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Slider(
                            value = strokeWidth,
                            onValueChange = { strokeWidth = it },
                            valueRange = 3f..36f,
                            colors = SliderDefaults.colors(
                                thumbColor = palette.primary,
                                activeTrackColor = palette.primary,
                                inactiveTrackColor = palette.surfaceVariant
                            ),
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = "${strokeWidth.toInt()}px",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            color = palette.textSecondary,
                            modifier = Modifier.width(36.dp)
                        )
                    }
                }
            }
        }
    }
}
