package io.github.ryoaonetsuki.mira.ui.screens

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.util.Size
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import coil.compose.AsyncImage
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import io.github.ryoaonetsuki.mira.R
import io.github.ryoaonetsuki.mira.ui.components.MiraButton
import io.github.ryoaonetsuki.mira.ui.components.MiraCard
import io.github.ryoaonetsuki.mira.ui.theme.LocalMiraPalette
import io.github.ryoaonetsuki.mira.ui.theme.MiraMotion
import java.io.File
import java.util.concurrent.Executors

object VisionTransfer {
    var pendingImageFile: File? = null
    var pendingQuery: String? = null
}

enum class CameraMode {
    VISION,
    SCAN
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScanScreen(
    onBack: () -> Unit,
    onNavigateToChat: () -> Unit = {}
) {
    val context = LocalContext.current
    val palette = LocalMiraPalette.current
    val keyboardController = LocalSoftwareKeyboardController.current

    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        )
    }

    var cameraMode by remember { mutableStateOf(CameraMode.VISION) }
    var lensFacing by remember { mutableStateOf(CameraSelector.LENS_FACING_BACK) }
    var flashMode by remember { mutableStateOf(ImageCapture.FLASH_MODE_OFF) }

    var imageCapture: ImageCapture? by remember { mutableStateOf(null) }
    var capturedImageFile by remember { mutableStateOf<File?>(null) }
    var userPrompt by remember { mutableStateOf("") }
    var isCapturing by remember { mutableStateOf(false) }

    var scannedValue by remember { mutableStateOf<String?>(null) }
    var scannedFormat by remember { mutableStateOf<String?>(null) }
    var isScanning by remember { mutableStateOf(true) }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasCameraPermission = granted
    }

    LaunchedEffect(Unit) {
        if (!hasCameraPermission) {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    val shutterScale by animateFloatAsState(
        targetValue = if (isCapturing) 0.86f else 1.0f,
        animationSpec = tween(durationMillis = 150, easing = FastOutSlowInEasing),
        label = "shutter-scale"
    )

    Scaffold(
        containerColor = palette.background
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            if (!hasCameraPermission) {
                // Permission required view
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .statusBarsPadding()
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(80.dp)
                            .clip(CircleShape)
                            .background(palette.surfaceVariant)
                            .border(1.dp, palette.borderSubtle, CircleShape)
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_fluent_camera_24_regular),
                            contentDescription = null,
                            tint = palette.primary,
                            modifier = Modifier.size(36.dp)
                        )
                    }
                    Spacer(modifier = Modifier.height(20.dp))
                    Text(
                        text = "Camera Permission Required",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = palette.textPrimary
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Mira uses your camera for real-time visual intelligence and QR/barcode scanning.",
                        fontSize = 14.sp,
                        lineHeight = 21.sp,
                        color = palette.textSecondary,
                        modifier = Modifier.padding(horizontal = 16.dp)
                    )
                    Spacer(modifier = Modifier.height(24.dp))
                    MiraButton(
                        text = "Grant Camera Access",
                        onClick = { cameraPermissionLauncher.launch(Manifest.permission.CAMERA) },
                        modifier = Modifier.fillMaxWidth(0.7f)
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    TextButton(onClick = onBack) {
                        Text("Go Back", color = palette.textSecondary)
                    }
                }
            } else if (capturedImageFile != null) {
                // Photo review & Ask Mira view
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .statusBarsPadding()
                        .navigationBarsPadding()
                        .padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // Top header for captured review
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        IconButton(
                            onClick = {
                                capturedImageFile = null
                                userPrompt = ""
                            },
                            modifier = Modifier
                                .size(42.dp)
                                .clip(CircleShape)
                                .background(palette.surfaceVariant)
                                .border(1.dp, palette.borderSubtle, CircleShape)
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_fluent_arrow_left_24_regular),
                                contentDescription = "Back",
                                tint = palette.textPrimary,
                                modifier = Modifier.size(20.dp)
                            )
                        }

                        Text(
                            text = "Analyze with Mira",
                            fontSize = 17.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = palette.textPrimary
                        )

                        IconButton(
                            onClick = {
                                capturedImageFile = null
                                userPrompt = ""
                            },
                            modifier = Modifier
                                .size(42.dp)
                                .clip(CircleShape)
                                .background(palette.surfaceVariant)
                                .border(1.dp, palette.borderSubtle, CircleShape)
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_fluent_arrow_clockwise_24_regular),
                                contentDescription = "Retake",
                                tint = palette.textSecondary,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }

                    // Photo card
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(20.dp))
                            .background(Color.Black)
                            .border(1.dp, palette.borderSubtle, RoundedCornerShape(20.dp))
                    ) {
                        AsyncImage(
                            model = capturedImageFile,
                            contentDescription = "Captured Photo",
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize()
                        )
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // Bottom Floating Prompt Pill Container
                    Surface(
                        shape = RoundedCornerShape(22.dp),
                        color = palette.surface,
                        border = BorderStroke(1.dp, palette.borderSubtle),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 8.dp, vertical = 6.dp)
                        ) {
                            // Thumbnail Preview Chip
                            Box(
                                modifier = Modifier
                                    .size(42.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(Color.Black)
                                    .border(1.dp, palette.primary.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
                            ) {
                                AsyncImage(
                                    model = capturedImageFile,
                                    contentDescription = "Thumbnail",
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize()
                                )
                            }

                            Spacer(modifier = Modifier.width(10.dp))

                            OutlinedTextField(
                                value = userPrompt,
                                onValueChange = { userPrompt = it },
                                placeholder = {
                                    Text(
                                        "Ask Mira about this image...",
                                        fontSize = 14.sp,
                                        color = palette.textSecondary
                                    )
                                },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                                keyboardActions = KeyboardActions(onSend = {
                                    keyboardController?.hide()
                                    val file = capturedImageFile ?: return@KeyboardActions
                                    val query = userPrompt.ifBlank { "What's in this image?" }
                                    processImageForAiVision(context, file, query) {
                                        onNavigateToChat()
                                    }
                                }),
                                modifier = Modifier
                                    .weight(1f)
                                    .heightIn(min = 44.dp),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = Color.Transparent,
                                    unfocusedBorderColor = Color.Transparent,
                                    focusedContainerColor = Color.Transparent,
                                    unfocusedContainerColor = Color.Transparent,
                                    focusedTextColor = palette.textPrimary,
                                    unfocusedTextColor = palette.textPrimary
                                )
                            )

                            // Send Button
                            IconButton(
                                onClick = {
                                    keyboardController?.hide()
                                    val file = capturedImageFile ?: return@IconButton
                                    val query = userPrompt.ifBlank { "What's in this image?" }
                                    processImageForAiVision(context, file, query) {
                                        onNavigateToChat()
                                    }
                                },
                                modifier = Modifier
                                    .size(42.dp)
                                    .clip(CircleShape)
                                    .background(
                                        Brush.horizontalGradient(
                                            listOf(palette.primary, palette.secondary)
                                        )
                                    )
                            ) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_fluent_sparkle_24_filled),
                                    contentDescription = "Ask Mira",
                                    tint = if (palette.isDark) palette.background else Color.White,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }
                }
            } else if (cameraMode == CameraMode.SCAN && scannedValue != null && !isScanning) {
                // QR/Barcode Scan Result View
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .statusBarsPadding()
                        .navigationBarsPadding()
                        .padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(72.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF10B981).copy(alpha = 0.15f))
                            .border(1.dp, Color(0xFF10B981).copy(alpha = 0.4f), CircleShape)
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_fluent_checkmark_circle_24_filled),
                            contentDescription = null,
                            tint = Color(0xFF10B981),
                            modifier = Modifier.size(38.dp)
                        )
                    }

                    Spacer(modifier = Modifier.height(18.dp))

                    Text(
                        text = "Code Detected",
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        color = palette.textPrimary
                    )

                    Spacer(modifier = Modifier.height(6.dp))

                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = palette.surfaceVariant,
                        border = BorderStroke(1.dp, palette.borderSubtle)
                    ) {
                        Text(
                            text = scannedFormat ?: "Barcode",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            color = palette.primary,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                        )
                    }

                    Spacer(modifier = Modifier.height(20.dp))

                    MiraCard(
                        modifier = Modifier.fillMaxWidth(),
                        cornerRadius = 16.dp,
                        containerColor = palette.surface,
                        borderColor = palette.borderSubtle
                    ) {
                        Text(
                            text = scannedValue ?: "",
                            fontSize = 14.sp,
                            lineHeight = 22.sp,
                            color = palette.textPrimary
                        )
                    }

                    Spacer(modifier = Modifier.height(24.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        OutlinedButton(
                            onClick = {
                                scannedValue = null
                                scannedFormat = null
                                isScanning = true
                            },
                            shape = RoundedCornerShape(14.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = palette.textPrimary),
                            border = BorderStroke(1.dp, palette.borderSubtle),
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_fluent_arrow_clockwise_24_regular),
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Scan Again", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        }

                        Button(
                            onClick = {
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                val clip = ClipData.newPlainText("Scanned Value", scannedValue)
                                clipboard.setPrimaryClip(clip)
                                Toast.makeText(context, "Copied to clipboard", Toast.LENGTH_SHORT).show()
                            },
                            shape = RoundedCornerShape(14.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = palette.primary,
                                contentColor = if (palette.isDark) palette.background else Color.White
                            ),
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_fluent_copy_24_regular),
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Copy Value", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            } else {
                // Live Full Bleed Camera View
                Box(
                    modifier = Modifier.fillMaxSize()
                ) {
                    CameraXView(
                        modifier = Modifier.fillMaxSize(),
                        lensFacing = lensFacing,
                        flashMode = flashMode,
                        isScanMode = cameraMode == CameraMode.SCAN,
                        onImageCaptureReady = { capture -> imageCapture = capture },
                        onBarcodeDetected = { value, format ->
                            if (cameraMode == CameraMode.SCAN && isScanning) {
                                scannedValue = value
                                scannedFormat = format
                                isScanning = false
                            }
                        }
                    )

                    // Top Floating Control Bar (Status bar aware)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .statusBarsPadding()
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        // Back button pill
                        IconButton(
                            onClick = onBack,
                            modifier = Modifier
                                .size(44.dp)
                                .clip(CircleShape)
                                .background(Color.Black.copy(alpha = 0.55f))
                                .border(1.dp, Color.White.copy(alpha = 0.2f), CircleShape)
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_fluent_arrow_left_24_regular),
                                contentDescription = "Back",
                                tint = Color.White,
                                modifier = Modifier.size(20.dp)
                            )
                        }

                        // Mode chip label in center
                        Surface(
                            shape = RoundedCornerShape(20.dp),
                            color = Color.Black.copy(alpha = 0.55f),
                            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.2f))
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
                            ) {
                                Icon(
                                    painter = painterResource(
                                        if (cameraMode == CameraMode.VISION)
                                            R.drawable.ic_fluent_sparkle_24_regular
                                        else
                                            R.drawable.ic_fluent_camera_24_regular
                                    ),
                                    contentDescription = null,
                                    tint = palette.primary,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = if (cameraMode == CameraMode.VISION) "Mira Vision" else "QR Scanner",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = Color.White
                                )
                            }
                        }

                        // Top right floating controls container (Flash + Flip)
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            // Flash toggle
                            IconButton(
                                onClick = {
                                    flashMode = if (flashMode == ImageCapture.FLASH_MODE_OFF) {
                                        ImageCapture.FLASH_MODE_ON
                                    } else {
                                        ImageCapture.FLASH_MODE_OFF
                                    }
                                },
                                modifier = Modifier
                                    .size(44.dp)
                                    .clip(CircleShape)
                                    .background(Color.Black.copy(alpha = 0.55f))
                                    .border(
                                        1.dp,
                                        if (flashMode == ImageCapture.FLASH_MODE_ON) palette.primary else Color.White.copy(alpha = 0.2f),
                                        CircleShape
                                    )
                            ) {
                                Icon(
                                    painter = painterResource(
                                        if (flashMode == ImageCapture.FLASH_MODE_ON)
                                            R.drawable.ic_fluent_flash_24_regular
                                        else
                                            R.drawable.ic_fluent_flash_off_24_regular
                                    ),
                                    contentDescription = "Flash",
                                    tint = if (flashMode == ImageCapture.FLASH_MODE_ON) palette.primary else Color.White,
                                    modifier = Modifier.size(20.dp)
                                )
                            }

                            // Camera Switch
                            IconButton(
                                onClick = {
                                    lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK) {
                                        CameraSelector.LENS_FACING_FRONT
                                    } else {
                                        CameraSelector.LENS_FACING_BACK
                                    }
                                },
                                modifier = Modifier
                                    .size(44.dp)
                                    .clip(CircleShape)
                                    .background(Color.Black.copy(alpha = 0.55f))
                                    .border(1.dp, Color.White.copy(alpha = 0.2f), CircleShape)
                            ) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_fluent_camera_switch_24_regular),
                                    contentDescription = "Switch Camera",
                                    tint = Color.White,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }

                    // Bottom Floating Controls: Mode Switch Pill + Concentric Shutter Button
                    Column(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .navigationBarsPadding()
                            .padding(bottom = 24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        // Horizontal Mode Switch Pill Slider
                        Surface(
                            shape = RoundedCornerShape(24.dp),
                            color = Color.Black.copy(alpha = 0.65f),
                            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.18f))
                        ) {
                            Row(
                                modifier = Modifier.padding(4.dp),
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                val isVision = cameraMode == CameraMode.VISION
                                val visionBg by animateColorAsState(
                                    targetValue = if (isVision) palette.primary else Color.Transparent,
                                    animationSpec = MiraMotion.effectsSpec(),
                                    label = "vision-bg"
                                )
                                val scanBg by animateColorAsState(
                                    targetValue = if (!isVision) palette.primary else Color.Transparent,
                                    animationSpec = MiraMotion.effectsSpec(),
                                    label = "scan-bg"
                                )

                                Box(
                                    contentAlignment = Alignment.Center,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(20.dp))
                                        .background(visionBg)
                                        .clickable { cameraMode = CameraMode.VISION }
                                        .padding(horizontal = 20.dp, vertical = 7.dp)
                                ) {
                                    Text(
                                        text = "Vision",
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = if (isVision) Color.Black else Color.White
                                    )
                                }

                                Box(
                                    contentAlignment = Alignment.Center,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(20.dp))
                                        .background(scanBg)
                                        .clickable {
                                            cameraMode = CameraMode.SCAN
                                            isScanning = true
                                        }
                                        .padding(horizontal = 20.dp, vertical = 7.dp)
                                ) {
                                    Text(
                                        text = "QR Scan",
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = if (!isVision) Color.Black else Color.White
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(18.dp))

                        // Shutter Button Area (Vision Mode Only)
                        if (cameraMode == CameraMode.VISION) {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .size(76.dp)
                                    .graphicsLayer {
                                        scaleX = shutterScale
                                        scaleY = shutterScale
                                    }
                                    .border(3.5.dp, palette.primary, CircleShape)
                                    .padding(6.dp)
                                    .clip(CircleShape)
                                    .background(if (isCapturing) palette.primary.copy(alpha = 0.6f) else Color.White)
                                    .clickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = null,
                                        enabled = !isCapturing
                                    ) {
                                        val capture = imageCapture ?: return@clickable
                                        isCapturing = true
                                        val photoFile = File(
                                            context.cacheDir,
                                            "mira_vision_${System.currentTimeMillis()}.jpg"
                                        )
                                        val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile).build()

                                        capture.takePicture(
                                            outputOptions,
                                            ContextCompat.getMainExecutor(context),
                                            object : ImageCapture.OnImageSavedCallback {
                                                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                                                    isCapturing = false
                                                    capturedImageFile = photoFile
                                                }

                                                override fun onError(exception: ImageCaptureException) {
                                                    isCapturing = false
                                                    Toast.makeText(context, "Capture failed: ${exception.message}", Toast.LENGTH_SHORT).show()
                                                }
                                            }
                                        )
                                    }
                            ) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_fluent_camera_24_filled),
                                    contentDescription = "Capture",
                                    tint = Color.Black,
                                    modifier = Modifier.size(30.dp)
                                )
                            }
                        } else {
                            // Scanner helper guide text
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = Color.Black.copy(alpha = 0.55f),
                                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.15f))
                            ) {
                                Text(
                                    text = "Point camera at any QR or barcode",
                                    fontSize = 12.sp,
                                    color = Color.White.copy(alpha = 0.85f),
                                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Clean hook function that routes the captured photo to chat with user query.
 */
fun processImageForAiVision(
    context: Context,
    imageFile: File,
    userQuery: String,
    onNavigateToChat: () -> Unit
) {
    VisionTransfer.pendingImageFile = imageFile
    VisionTransfer.pendingQuery = userQuery
    onNavigateToChat()
}

@Composable
private fun CameraXView(
    modifier: Modifier = Modifier,
    lensFacing: Int,
    flashMode: Int,
    isScanMode: Boolean,
    onImageCaptureReady: (ImageCapture) -> Unit,
    onBarcodeDetected: (value: String, format: String) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val cameraExecutor = remember { Executors.newSingleThreadExecutor() }

    AndroidView(
        factory = { ctx ->
            val previewView = PreviewView(ctx).apply {
                implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                scaleType = PreviewView.ScaleType.FILL_CENTER
            }
            previewView
        },
        update = { previewView ->
            val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
            cameraProviderFuture.addListener({
                val cameraProvider = cameraProviderFuture.get()

                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }

                val imageCapture = ImageCapture.Builder()
                    .setFlashMode(flashMode)
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .build()
                onImageCaptureReady(imageCapture)

                val barcodeScanner = BarcodeScanning.getClient()
                val imageAnalysis = ImageAnalysis.Builder()
                    .setTargetResolution(Size(1280, 720))
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()

                imageAnalysis.setAnalyzer(cameraExecutor) { imageProxy ->
                    if (!isScanMode) {
                        imageProxy.close()
                        return@setAnalyzer
                    }
                    val mediaImage = imageProxy.image
                    if (mediaImage != null) {
                        val image = InputImage.fromMediaImage(
                            mediaImage,
                            imageProxy.imageInfo.rotationDegrees
                        )
                        barcodeScanner.process(image)
                            .addOnSuccessListener { barcodes ->
                                for (barcode in barcodes) {
                                    val rawValue = barcode.rawValue ?: continue
                                    val formatName = when (barcode.format) {
                                        Barcode.FORMAT_QR_CODE -> "QR Code"
                                        Barcode.FORMAT_EAN_13 -> "EAN-13"
                                        Barcode.FORMAT_EAN_8 -> "EAN-8"
                                        Barcode.FORMAT_CODE_128 -> "Code 128"
                                        Barcode.FORMAT_CODE_39 -> "Code 39"
                                        Barcode.FORMAT_CODE_93 -> "Code 93"
                                        Barcode.FORMAT_UPC_A -> "UPC-A"
                                        Barcode.FORMAT_UPC_E -> "UPC-E"
                                        Barcode.FORMAT_DATA_MATRIX -> "Data Matrix"
                                        Barcode.FORMAT_PDF417 -> "PDF417"
                                        Barcode.FORMAT_AZTEC -> "Aztec"
                                        Barcode.FORMAT_ITF -> "ITF"
                                        else -> "Barcode"
                                    }
                                    onBarcodeDetected(rawValue, formatName)
                                    break
                                }
                            }
                            .addOnCompleteListener { imageProxy.close() }
                    } else {
                        imageProxy.close()
                    }
                }

                val cameraSelector = CameraSelector.Builder()
                    .requireLensFacing(lensFacing)
                    .build()

                try {
                    cameraProvider.unbindAll()
                    cameraProvider.bindToLifecycle(
                        lifecycleOwner,
                        cameraSelector,
                        preview,
                        imageCapture,
                        imageAnalysis
                    )
                } catch (e: Exception) {
                    android.util.Log.e("ScanScreen", "Camera binding failed: ${e.message}", e)
                }
            }, ContextCompat.getMainExecutor(context))
        },
        modifier = modifier
    )

    DisposableEffect(Unit) {
        onDispose {
            cameraExecutor.shutdown()
        }
    }
}

