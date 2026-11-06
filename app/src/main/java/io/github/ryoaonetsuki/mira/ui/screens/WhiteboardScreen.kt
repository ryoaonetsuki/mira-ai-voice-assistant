package io.github.ryoaonetsuki.mira.ui.screens

import io.github.ryoaonetsuki.mira.ui.icons.Lucide
import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.Paint
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.widget.Toast
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.ryoaonetsuki.mira.ui.theme.LocalMiraPalette

data class DrawPath(val path: androidx.compose.ui.graphics.Path, val color: Color, val strokeWidth: Float)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WhiteboardScreen(
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val palette = LocalMiraPalette.current

    val paths = remember { mutableStateListOf<DrawPath>() }
    var currentPath by remember { mutableStateOf<androidx.compose.ui.graphics.Path?>(null) }
    var selectedColor by remember { mutableStateOf(Color(0xFF22D3EE)) }
    var strokeWidth by remember { mutableFloatStateOf(8f) }

    val colors = listOf(
        Color(0xFF22D3EE), // Cyan
        Color(0xFF8B5CF6), // Violet
        Color(0xFFFFFFFF), // White
        Color(0xFFFBBF24), // Yellow
        Color(0xFF10B981), // Green
        Color(0xFFF43F5E), // Pink
        Color(0xFF000000)  // Eraser/Dark
    )

    fun saveWhiteboardToGallery() {
        try {
            val width = 1080
            val height = 1920
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(bitmap)
            canvas.drawColor(android.graphics.Color.rgb(10, 10, 18)) // Mira dark background

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
                title = { Text("Whiteboard", fontWeight = FontWeight.Bold, color = palette.textPrimary) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Lucide.arrowLeft, contentDescription = "Back", tint = palette.textPrimary)
                    }
                },
                actions = {
                    // Undo
                    IconButton(onClick = { if (paths.isNotEmpty()) paths.removeAt(paths.size - 1) }) {
                        Icon(Lucide.undo, contentDescription = "Undo", tint = palette.textPrimary)
                    }
                    // Clear
                    IconButton(onClick = { paths.clear() }) {
                        Icon(Lucide.trash, contentDescription = "Clear", tint = palette.textSecondary)
                    }
                    // Save
                    IconButton(onClick = { saveWhiteboardToGallery() }) {
                        Icon(Lucide.save, contentDescription = "Save to Gallery", tint = palette.primary)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = palette.background)
            )
        },
        containerColor = palette.background
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Drawing Canvas
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(8.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color(0xFF0D0D18))
                    .border(1.dp, palette.surfaceVariant.copy(alpha = 0.5f), RoundedCornerShape(16.dp))
            ) {
                Canvas(
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(Unit) {
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
                                        paths.add(DrawPath(it, selectedColor, strokeWidth))
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
                        drawPath(
                            path = it,
                            color = selectedColor,
                            style = Stroke(
                                width = strokeWidth,
                                cap = StrokeCap.Round,
                                join = StrokeJoin.Round
                            )
                        )
                    }
                }
            }

            // Toolbar Controls
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(palette.surface)
                    .padding(horizontal = 16.dp, vertical = 10.dp)
            ) {
                // Color choices
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    for (col in colors) {
                        val isSelected = selectedColor == col
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .clip(CircleShape)
                                .background(col)
                                .border(
                                    width = if (isSelected) 3.dp else 1.dp,
                                    color = if (isSelected) palette.primary else Color.Gray.copy(alpha = 0.5f),
                                    shape = CircleShape
                                )
                                .clickable { selectedColor = col }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Stroke Width Slider
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        imageVector = Lucide.penLine,
                        contentDescription = null,
                        tint = palette.textSecondary,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Slider(
                        value = strokeWidth,
                        onValueChange = { strokeWidth = it },
                        valueRange = 3f..36f,
                        colors = SliderDefaults.colors(
                            thumbColor = palette.primary,
                            activeTrackColor = palette.primary
                        ),
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "${strokeWidth.toInt()}px",
                        fontSize = 12.sp,
                        color = palette.textSecondary
                    )
                }
            }
        }
    }
}
