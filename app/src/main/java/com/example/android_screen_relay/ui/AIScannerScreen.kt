package com.example.android_screen_relay.ui

import android.Manifest
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.Image
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import java.util.concurrent.Executors
import com.example.android_screen_relay.core.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.json.JSONArray
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

enum class ScanMode {
    OCR, PALM
}

enum class HandSide {
    ANY, LEFT, RIGHT
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AIScannerScreen() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    
    // States
    var hasCameraPermission by remember { mutableStateOf(false) }
    var currentMode by remember { mutableStateOf(ScanMode.PALM) }
    var targetHand by remember { mutableStateOf(HandSide.ANY) }
    var isDetected by remember { mutableStateOf(false) }
    var snappedImage by remember { mutableStateOf<Bitmap?>(null) }
    
    var useGpu by remember { mutableStateOf(false) }
    
    // AI Loading
    var isAILoading by remember { mutableStateOf(false) }
    
    // Post-Snap States
    var showReview by remember { mutableStateOf(false) }
    var resultJson by remember { mutableStateOf("") }
    
    // Launcher
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasCameraPermission = granted
    }

    LaunchedEffect(Unit) {
        permissionLauncher.launch(Manifest.permission.CAMERA)
    }
    
    // Initialize AI when mode changes
    LaunchedEffect(currentMode, useGpu) {
        isAILoading = true
        withContext(Dispatchers.IO) {
            when (currentMode) {
                ScanMode.PALM -> AIManager.switchProcessor(PalmprintProcessor(), context, AIConfig(useGpu = useGpu))
                ScanMode.OCR -> AIManager.switchProcessor(OCRProcessor(), context, AIConfig(useGpu = useGpu))
            }
        }
        isAILoading = false
    }

    if (showReview && snappedImage != null) {
        // Review Screen (Crop / Send JSON)
        ReviewScreen(
            image = snappedImage!!,
            mode = currentMode,
            resultJson = resultJson,
            onRetake = {
                snappedImage = null
                showReview = false
                isDetected = false
            },
            onSend = {
                // Implement Web Socket send
                Toast.makeText(context, "Sending via RelayService...", Toast.LENGTH_SHORT).show()
                val service = com.example.android_screen_relay.RelayService.getInstance()
                service?.broadcastMessage(resultJson)
            }
        )
    } else if (hasCameraPermission) {
        Box(modifier = Modifier.fillMaxSize()) {
            RealtimeCameraPreview(
                mode = currentMode,
                targetHand = targetHand,
                onDetected = { detected, meta ->
                    isDetected = detected
                    if (isDetected && snappedImage == null) {
                        resultJson = meta
                    }
                },
                onSnap = { bitmap ->
                    if (snappedImage == null) {
                        snappedImage = bitmap
                        showReview = true
                    }
                }
            )

            // Top Controls (Mode Select)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 48.dp, start = 16.dp, end = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Mode Toggle
                SegmentedButtonUI(
                    options = listOf("Palm", "OCR"),
                    selectedIndex = if (currentMode == ScanMode.PALM) 0 else 1,
                    onSelect = { index ->
                        currentMode = if (index == 0) ScanMode.PALM else ScanMode.OCR
                        isDetected = false
                    }
                )
                
                // Hardware Toggle
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("GPU", color = Color.White, fontWeight = FontWeight.Bold, modifier = Modifier.padding(end=4.dp))
                    Switch(checked = useGpu, onCheckedChange = { useGpu = it })
                }
            }
            
            // Hand Targeting Toggle
            if (currentMode == ScanMode.PALM) {
                Row(
                    modifier = Modifier.align(Alignment.TopCenter).padding(top = 100.dp),
                    horizontalArrangement = Arrangement.Center
                ) {
                    SegmentedButtonUI(
                        options = listOf("Any", "Left", "Right"),
                        selectedIndex = targetHand.ordinal,
                        onSelect = { index ->
                            targetHand = HandSide.values()[index]
                        },
                        selectedColor = Color(0xFF6200EA) // Purple for target selection
                    )
                }
            }

            // Overlay Drawing
            val boxColor by animateColorAsState(if (isDetected) Color.Green else Color.White)
            Canvas(modifier = Modifier.fillMaxSize()) {
                val boxWidth = if (currentMode == ScanMode.OCR) size.width * 0.85f else size.width * 0.7f
                val boxHeight = if (currentMode == ScanMode.OCR) boxWidth * (5.4f / 8.5f) else boxWidth * 1.2f
                val left = (size.width - boxWidth) / 2
                val top = (size.height - boxHeight) / 2
                
                drawRoundRect(
                    color = boxColor,
                    topLeft = Offset(left, top),
                    size = Size(boxWidth, boxHeight),
                    cornerRadius = CornerRadius(16f, 16f),
                    style = Stroke(width = 8f)
                )
            }
            
            // Loading text
            if (isAILoading) {
                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha=0.5f)), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Color.White)
                }
            }
        }
    } else {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("No Camera Permission.")
        }
    }
}

@Composable
fun RealtimeCameraPreview(
    mode: ScanMode,
    targetHand: HandSide,
    onDetected: (Boolean, String) -> Unit,
    onSnap: (Bitmap) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val cameraProviderFuture = remember { ProcessCameraProvider.getInstance(context) }
    var imageCapture: ImageCapture? by remember { mutableStateOf(null) }
    var isProcessingFrame by remember { mutableStateOf(false) }
    var consecutiveDetections by remember { mutableStateOf(0) }
    
    val executor = remember { Executors.newSingleThreadExecutor() }
    
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            val previewView = PreviewView(ctx)
            cameraProviderFuture.addListener({
                val cameraProvider = cameraProviderFuture.get()
                
                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }

                imageCapture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .build()

                val imageAnalyzer = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                    .also {
                        it.setAnalyzer(executor) { image ->
                            if (isProcessingFrame) {
                                image.close()
                                return@setAnalyzer
                            }
                            isProcessingFrame = true
                            
                            try {
                                val bitmap = image.toBitmap()
                                val rotatedBitmap = rotateBitmap(bitmap, image.imageInfo.rotationDegrees)
                                val processor = AIManager.getActiveProcessor()
                                
                                if (processor != null) {
                                    val result = processor.process(rotatedBitmap)
                                    val detectedItems = result.items
                                    
                                    var criteriaMet = false
                                    var metaStr = "{}"
                                    
                                    if (mode == ScanMode.PALM) {
                                        val handMatch = detectedItems.find { item ->
                                            val sideStr = item.extra["side"] as? String ?: ""
                                            targetHand == HandSide.ANY || sideStr.equals(targetHand.name, ignoreCase=true)
                                        }
                                        if (handMatch != null && result.success) {
                                            criteriaMet = true
                                            metaStr = JSONObject().apply {
                                                put("detected", true)
                                                put("side", handMatch.extra["side"])
                                                put("roi_dist", handMatch.extra["roi_dist_d"])
                                                put("area_type", handMatch.extra["area_type"])
                                            }.toString()
                                        }
                                    } else {
                                        // OCR logic checking
                                        if (result.success /* Add specific OCR logic if needed */) {
                                             criteriaMet = true
                                             metaStr = JSONObject().apply {
                                                 put("detected", true)
                                                 // Add OCR texts here
                                             }.toString()
                                        }
                                    }
                                    
                                    if (criteriaMet) {
                                        consecutiveDetections++
                                        onDetected(true, metaStr)
                                        if (consecutiveDetections > 5) { // Needs 5 consecutive stable frames
                                            // Trigger Auto Snap!
                                            // To avoid double snaps, set consecutive really high or break
                                            consecutiveDetections = -9999
                                            onSnap(rotatedBitmap)
                                        }
                                    } else {
                                        consecutiveDetections = 0
                                        onDetected(false, "")
                                    }
                                }
                            } catch (e: Exception) {
                                Log.e("CameraX", "Analyze error", e)
                            } finally {
                                image.close()
                                isProcessingFrame = false
                            }
                        }
                    }

                val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

                try {
                    cameraProvider.unbindAll()
                    cameraProvider.bindToLifecycle(
                        lifecycleOwner,
                        cameraSelector,
                        preview,
                        imageCapture,
                        imageAnalyzer
                    )
                } catch (e: Exception) {
                    Log.e("CameraX", "Use case binding failed", e)
                }

            }, ContextCompat.getMainExecutor(context))
            previewView
        }
    )
}

@Composable
fun SegmentedButtonUI(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    selectedColor: Color = Color(0xFF1976D2)
) {
    Row(
        modifier = Modifier
            .background(Color.DarkGray.copy(alpha=0.7f), RoundedCornerShape(50))
            .padding(4.dp)
    ) {
        options.forEachIndexed { index, option ->
            val isSelected = index == selectedIndex
            val bgColor = animateColorAsState(if (isSelected) selectedColor else Color.Transparent)
            
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(bgColor.value)
                    .clickable { onSelect(index) }
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Text(option, color = Color.White, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal)
            }
        }
    }
}

@Composable
fun ReviewScreen(
    image: Bitmap,
    mode: ScanMode,
    resultJson: String,
    onRetake: () -> Unit,
    onSend: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize().background(Color.Black),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Review Image", color = Color.White, fontSize = 20.sp, modifier = Modifier.padding(16.dp))
        
        Image(
            bitmap = image.asImageBitmap(),
            contentDescription = "Captured",
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentScale = ContentScale.Fit
        )
        
        Box(modifier = Modifier.fillMaxWidth().background(Color.DarkGray).padding(16.dp)) {
            Text("Data: $resultJson", color = Color.White, fontSize = 12.sp)
        }
        
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            Button(onClick = onRetake, colors = ButtonDefaults.buttonColors(containerColor = Color.Red)) {
                Text("Retake")
            }
            Button(onClick = onSend, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50))) {
                Text("Send JSON")
            }
        }
    }
}

// Utils
fun ImageProxy.toBitmap(): Bitmap {
    val yBuffer = planes[0].buffer 
    val uBuffer = planes[1].buffer
    val vBuffer = planes[2].buffer
    val ySize = yBuffer.remaining()
    val uSize = uBuffer.remaining()
    val vSize = vBuffer.remaining()
    val nv21 = ByteArray(ySize + uSize + vSize)
    
    yBuffer.get(nv21, 0, ySize)
    vBuffer.get(nv21, ySize, vSize)
    uBuffer.get(nv21, ySize + vSize, uSize)
    
    val yuvImage = android.graphics.YuvImage(nv21, android.graphics.ImageFormat.NV21, this.width, this.height, null)
    val out = ByteArrayOutputStream()
    yuvImage.compressToJpeg(android.graphics.Rect(0, 0, yuvImage.width, yuvImage.height), 100, out)
    val imageBytes = out.toByteArray()
    return BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
}

fun rotateBitmap(bitmap: Bitmap, degrees: Int): Bitmap {
    if (degrees == 0) return bitmap
    val matrix = Matrix()
    matrix.postRotate(degrees.toFloat())
    return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
}