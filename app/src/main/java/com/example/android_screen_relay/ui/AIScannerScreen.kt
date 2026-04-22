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
    OCR, PALM, FACE, POSE, SELFIE, SUBJECT
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

    var useGpu by remember { mutableStateOf(true) }

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
            // Memory Optimization for 2GB RAM Devices
            // 1. Suggest Garbage Collection to free up memory from the previous model
            System.gc()
            System.runFinalization()
            Thread.sleep(100) // Brief pause to allow OS to reclaim memory

            // 2. Use the helper that already handles low-spec config and releases old model safely
            AIManager.switchProcessor(context, currentMode.name)
        }
        isAILoading = false
    }

    androidx.compose.runtime.DisposableEffect(Unit) {
        onDispose {
            snappedImage?.recycle()
            AIManager.release()
        }
    }

    if (showReview && snappedImage != null) {
        // Review Screen (Crop / Send JSON)
        ReviewScreen(
            image = snappedImage!!,
            mode = currentMode,
            resultJson = resultJson,
            onRetake = {
                snappedImage?.recycle()
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
                    } else {
                        bitmap.recycle()
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
                    options = listOf("OCR", "Palm", "Face", "Pose", "Selfie", "Subj"),
                    selectedIndex = currentMode.ordinal,
                    onSelect = { index ->
                        currentMode = ScanMode.values()[index]
                        isDetected = false
                    }
                )

                // Hardware Toggle
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "GPU",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(end = 4.dp)
                    )
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
                Box(
                    Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.5f)),
                    contentAlignment = Alignment.Center
                ) {
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
    val isProcessingFrame = remember { java.util.concurrent.atomic.AtomicBoolean(false) }
    val consecutiveDetections = remember { java.util.concurrent.atomic.AtomicInteger(0) }
    var lastProcessTime = 0L

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
                    // Set Target Resolution to fulfill the minimum 720x720 requirement
                    .setTargetResolution(android.util.Size(720, 1280))
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                    .also {
                        it.setAnalyzer(executor) { image ->
                            // Free up processor by skipping frames if they come too fast (throttle to ~10 FPS max)
                            val currentTime = System.currentTimeMillis()
                            if (currentTime - lastProcessTime < 100) {
                                image.close()
                                return@setAnalyzer
                            }

                            if (!isProcessingFrame.compareAndSet(false, true)) {
                                image.close()
                                return@setAnalyzer
                            }

                            lastProcessTime = currentTime
                            var bitmap: Bitmap? = null
                            var rotatedBitmap: Bitmap? = null
                            try {
                                bitmap = image.toBitmap()
                                rotatedBitmap = rotateBitmap(bitmap, image.imageInfo.rotationDegrees)
                                
                                // Thread-safe processing via AIManager
                                val result = AIManager.process(rotatedBitmap)

                                if (result != null) {
                                    val detectedItems = result.items

                                    var criteriaMet = false
                                    var metaStr = "{}"

                                    if (mode == ScanMode.PALM) {
                                        val handMatch = detectedItems.find { item ->
                                            val sideStr = item.extra["side"] as? String ?: ""
                                            targetHand == HandSide.ANY || sideStr.equals(
                                                targetHand.name,
                                                ignoreCase = true
                                            )
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
                                    } else if (mode == ScanMode.FACE) {
                                        if (result.success && detectedItems.isNotEmpty()) {
                                            criteriaMet = true
                                            val face = detectedItems[0]
                                            metaStr = JSONObject().apply {
                                                put("detected", true)
                                                put("smiling_prob", face.extra["smiling_prob"])
                                                put("right_eye_open_prob", face.extra["right_eye_open_prob"])
                                                put("left_eye_open_prob", face.extra["left_eye_open_prob"])
                                            }.toString()
                                        }
                                    } else if (mode == ScanMode.POSE || mode == ScanMode.SELFIE || mode == ScanMode.SUBJECT) {
                                        if (result.success && detectedItems.isNotEmpty()) {
                                            criteriaMet = true
                                            metaStr = JSONObject().apply {
                                                put("type", mode.name)
                                                put("detected", true)
                                                put("item_count", detectedItems.size)
                                            }.toString()
                                        }
                                    } else {
                                        // OCR logic checking
                                        if (result.success && detectedItems.isNotEmpty()) {
                                            criteriaMet = true
                                            metaStr = JSONObject().apply {
                                                put("type", "OCR")
                                                put("detected", true)
                                                val texts = JSONArray()
                                                detectedItems.forEach { texts.put(it.label) }
                                                put("text_blocks", texts)
                                            }.toString()
                                        }
                                    }

                                    if (criteriaMet) {
                                        val count = consecutiveDetections.incrementAndGet()
                                        onDetected(true, metaStr)
                                        if (count > 5) { // Needs 5 consecutive stable frames
                                            // Trigger Auto Snap!
                                            // To avoid double snaps, set consecutive really high or break
                                            consecutiveDetections.set(-9999)
                                            val snappedCopy = rotatedBitmap.copy(
                                                rotatedBitmap.config ?: Bitmap.Config.ARGB_8888,
                                                true
                                            )
                                            onSnap(snappedCopy)
                                        }
                                    } else {
                                        consecutiveDetections.set(0)
                                        onDetected(false, "")
                                    }
                                }
                            } catch (e: Exception) {
                                Log.e("CameraX", "Analyze error", e)
                            } finally {
                                if (bitmap != null && rotatedBitmap != null && bitmap !== rotatedBitmap) {
                                    rotatedBitmap.recycle()
                                }
                                bitmap?.recycle()
                                image.close()
                                isProcessingFrame.set(false)
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

    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        onDispose {
            try {
                cameraProviderFuture.get().unbindAll()
            } catch (e: Exception) {
                android.util.Log.e("CameraX", "Unbind failed", e)
            }
            executor.shutdown()
        }
    }
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
            .background(Color.DarkGray.copy(alpha = 0.7f), RoundedCornerShape(50))
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

// Utils (Removed custom ImageProxy.toBitmap() to use CameraX's built-in optimized version)
fun rotateBitmap(bitmap: Bitmap, degrees: Int): Bitmap {
    if (degrees == 0) return bitmap
    val matrix = Matrix()
    matrix.postRotate(degrees.toFloat())
    return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
}
