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
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import coil.compose.AsyncImage
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import io.github.ryoaonetsuki.mira.R
import io.github.ryoaonetsuki.mira.ui.theme.LocalMiraPalette
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

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = if (cameraMode == CameraMode.VISION) "Mira Vision" else "QR & Barcode Scan",
                        fontWeight = FontWeight.Bold,
                        color = palette.textPrimary
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            painter = painterResource(R.drawable.ic_fluent_arrow_left_24_regular),
                            contentDescription = "Back",
                            tint = palette.textPrimary
                        )
                    }
                },
                actions = {
                    if (hasCameraPermission && capturedImageFile == null) {
                        // Flash toggle
                        IconButton(onClick = {
                            flashMode = if (flashMode == ImageCapture.FLASH_MODE_OFF) {
                                ImageCapture.FLASH_MODE_ON
                            } else {
                                ImageCapture.FLASH_MODE_OFF
                            }
                        }) {
                            Icon(
                                painter = painterResource(
                                    if (flashMode == ImageCapture.FLASH_MODE_ON)
                                        R.drawable.ic_fluent_flash_24_regular
                                    else
                                        R.drawable.ic_fluent_flash_off_24_regular
                                ),
                                contentDescription = "Flash",
                                tint = if (flashMode == ImageCapture.FLASH_MODE_ON) palette.primary else palette.textSecondary
                            )
                        }

                        // Lens switch
                        IconButton(onClick = {
                            lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK) {
                                CameraSelector.LENS_FACING_FRONT
                            } else {
                                CameraSelector.LENS_FACING_BACK
                            }
                        }) {
                            Icon(
                                painter = painterResource(R.drawable.ic_fluent_camera_switch_24_regular),
                                contentDescription = "Switch Camera",
                                tint = palette.textPrimary
                            )
                        }
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
                .padding(padding),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (!hasCameraPermission) {
                // Permission required view
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(24.dp)
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            painter = painterResource(R.drawable.ic_fluent_camera_24_regular),
                            contentDescription = null,
                            tint = palette.textSecondary,
                            modifier = Modifier.size(56.dp)
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "Camera permission required",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = palette.textPrimary
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Mira needs camera access for visual intelligence and scanning.",
                            fontSize = 13.sp,
                            color = palette.textSecondary
                        )
                        Spacer(modifier = Modifier.height(20.dp))
                        Button(
                            onClick = { cameraPermissionLauncher.launch(Manifest.permission.CAMERA) },
                            colors = ButtonDefaults.buttonColors(containerColor = palette.primary)
                        ) {
                            Text("Grant Camera Access", color = Color.Black)
                        }
                    }
                }
            } else if (capturedImageFile != null) {
                // Photo review & Ask Mira view
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .background(Color.Black)
                    ) {
                        AsyncImage(
                            model = capturedImageFile,
                            contentDescription = "Captured Photo",
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize()
                        )
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    OutlinedTextField(
                        value = userPrompt,
                        onValueChange = { userPrompt = it },
                        placeholder = { Text("Ask Mira about this image...", color = palette.textSecondary) },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = palette.textPrimary,
                            unfocusedTextColor = palette.textPrimary,
                            focusedBorderColor = palette.primary,
                            unfocusedBorderColor = palette.surfaceVariant,
                            focusedContainerColor = palette.surface,
                            unfocusedContainerColor = palette.surface
                        )
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        OutlinedButton(
                            onClick = {
                                capturedImageFile = null
                                userPrompt = ""
                            },
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_fluent_arrow_clockwise_24_regular),
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Retake", color = palette.textPrimary)
                        }

                        Button(
                            onClick = {
                                val file = capturedImageFile ?: return@Button
                                val query = userPrompt.ifBlank { "What's in this image?" }
                                processImageForAiVision(context, file, query) {
                                    onNavigateToChat()
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = palette.primary),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_fluent_sparkle_24_filled),
                                contentDescription = null,
                                tint = Color.Black,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Ask Mira", color = Color.Black, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            } else if (cameraMode == CameraMode.SCAN && scannedValue != null && !isScanning) {
                // QR/Barcode scan result view
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_fluent_checkmark_circle_24_filled),
                        contentDescription = null,
                        tint = Color(0xFF10B981),
                        modifier = Modifier.size(56.dp)
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "Scan Result",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = palette.textPrimary
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Format: ${scannedFormat ?: "Unknown"}",
                        fontSize = 13.sp,
                        color = palette.textSecondary
                    )
                    Spacer(modifier = Modifier.height(20.dp))

                    Surface(
                        color = palette.surface,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                text = scannedValue ?: "",
                                fontSize = 15.sp,
                                color = palette.textPrimary,
                                lineHeight = 22.sp
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(20.dp))

                    Button(
                        onClick = {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            val clip = ClipData.newPlainText("Scanned Value", scannedValue)
                            clipboard.setPrimaryClip(clip)
                            Toast.makeText(context, "Copied to clipboard", Toast.LENGTH_SHORT).show()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = palette.primary),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_fluent_copy_24_regular),
                            contentDescription = null,
                            tint = Color.Black
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Copy", color = Color.Black, fontWeight = FontWeight.SemiBold)
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    OutlinedButton(
                        onClick = {
                            scannedValue = null
                            scannedFormat = null
                            isScanning = true
                        },
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = palette.primary),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_fluent_arrow_clockwise_24_regular),
                            contentDescription = null
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Scan Again")
                    }
                }
            } else {
                // Live Camera View
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .padding(horizontal = 16.dp, vertical = 4.dp)
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

                    // Mode switch pills at bottom of preview
                    Row(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 12.dp)
                            .clip(RoundedCornerShape(20.dp))
                            .background(Color.Black.copy(alpha = 0.6f))
                            .padding(4.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .clip(RoundedCornerShape(16.dp))
                                .background(if (cameraMode == CameraMode.VISION) palette.primary else Color.Transparent)
                                .clickable { cameraMode = CameraMode.VISION }
                                .padding(horizontal = 16.dp, vertical = 6.dp)
                        ) {
                            Text(
                                "Vision",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = if (cameraMode == CameraMode.VISION) Color.Black else Color.White
                            )
                        }

                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .clip(RoundedCornerShape(16.dp))
                                .background(if (cameraMode == CameraMode.SCAN) palette.primary else Color.Transparent)
                                .clickable {
                                    cameraMode = CameraMode.SCAN
                                    isScanning = true
                                }
                                .padding(horizontal = 16.dp, vertical = 6.dp)
                        ) {
                            Text(
                                "QR Scan",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = if (cameraMode == CameraMode.SCAN) Color.Black else Color.White
                            )
                        }
                    }
                }

                // Shutter / action area below preview
                if (cameraMode == CameraMode.VISION) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 16.dp)
                    ) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(72.dp)
                                .border(4.dp, palette.primary, CircleShape)
                                .padding(6.dp)
                                .clip(CircleShape)
                                .background(if (isCapturing) palette.primary.copy(alpha = 0.5f) else Color.White)
                                .clickable(enabled = !isCapturing) {
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
                                modifier = Modifier.size(28.dp)
                            )
                        }
                    }
                } else {
                    Spacer(modifier = Modifier.height(16.dp))
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
