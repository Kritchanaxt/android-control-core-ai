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
import androidx.compose.ui.graphics.nativeCanvas
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
    PADDLE_OCR, TESSERACT_FAST_OCR, HAND_DETECTION, FACE_DETECTION, POSE_DETECTION, SELFIE, SUBJECT, IDENTITY_VERIFICATION
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
    var currentMode by remember { mutableStateOf(ScanMode.HAND_DETECTION) }
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
            System.gc()
            System.runFinalization()
            Thread.sleep(100)

            // Use helper with low-spec detection
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
                status = resultJson,
                onDetected = { detected, meta ->
                    isDetected = detected
                    if (isDetected && snappedImage == null) {
                        resultJson = meta
                    }
                },
                onStatusUpdate = { msg ->
                    resultJson = msg // Overuse resultJson for status display if it's not detected yet
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
                    .padding(top = 48.dp, start = 8.dp, end = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Mode Toggle
                SegmentedButtonUI(
                    options = listOf("OCR", "Hand", "Face", "Pose", "Selfie", "Subj", "Verify"),
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
                        modifier = Modifier.padding(end = 4.dp),
                        fontSize = 12.sp
                    )
                    Switch(checked = useGpu, onCheckedChange = { useGpu = it }, scale = 0.7f)
                }
            }

            // Hand Targeting Toggle
            if (currentMode == ScanMode.HAND_DETECTION) {
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
                        selectedColor = Color(0xFF6200EA)
                    )
                }
            }

            // Loading indicator
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
private fun Switch(checked: Boolean, onCheckedChange: (Boolean) -> Unit, scale: Float) {
    androidx.compose.material3.Switch(
        checked = checked, 
        onCheckedChange = onCheckedChange,
        modifier = Modifier.height(24.dp)
    )
}

@Composable
fun RealtimeCameraPreview(
    mode: ScanMode,
    targetHand: HandSide,
    onDetected: (Boolean, String) -> Unit,
    onStatusUpdate: (String) -> Unit = {},
    status: String = "",
    onSnap: (Bitmap) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val cameraProviderFuture = remember { ProcessCameraProvider.getInstance(context) }
    var imageCapture: ImageCapture? by remember { mutableStateOf(null) }
    val isProcessingFrame = remember { java.util.concurrent.atomic.AtomicBoolean(false) }
    val consecutiveDetections = remember { java.util.concurrent.atomic.AtomicInteger(0) }
    val isAppInForeground = remember { java.util.concurrent.atomic.AtomicBoolean(true) }
    var lastProcessTime = 0L

    // Results for drawing
    var latestDetections by remember { mutableStateOf<List<AIDetectedItem>>(emptyList()) }
    var previewWidth by remember { mutableStateOf(720) }
    var previewHeight by remember { mutableStateOf(1280) }

    val executor = remember { Executors.newSingleThreadExecutor() }
    val isLowSpecDevice = remember { SystemMonitor.isLowSpecDevice(context) }

    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_PAUSE || event == androidx.lifecycle.Lifecycle.Event.ON_STOP) {
                isAppInForeground.set(false)
            } else if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                isAppInForeground.set(true)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    // FrameProcessingRunnable สำหรับเครื่องทั่วไป (จำลองตาม mlkit/vision-quickstart)
    val frameProcessingRunnable = remember(mode, targetHand) {
        FrameProcessingRunnable { rotatedBitmap ->
            try {
                val result = AIManager.process(rotatedBitmap)
                if (result != null) {
                    onStatusUpdate(result.errorMessage ?: "")
                    latestDetections = result.items
                    var criteriaMet = false
                    var metaStr = "{}"

                    if (mode == ScanMode.HAND_DETECTION) {
                        val handMatch = result.items.find { item ->
                            val sideStr = item.extra["side"] as? String ?: ""
                            targetHand == HandSide.ANY || sideStr.equals(targetHand.name, ignoreCase = true)
                        }
                        if (handMatch != null && result.success) {
                            criteriaMet = true
                            metaStr = JSONObject().apply {
                                put("detected", true)
                                put("side", handMatch.extra["side"])
                            }.toString()
                        }
                    } else if (mode == ScanMode.IDENTITY_VERIFICATION) {
                        val identityItem = result.items.find { it.label == "IDENTITY_VERIFICATION" }
                        if (identityItem != null) {
                            criteriaMet = true
                            metaStr = identityItem.extra["verification_metrics"] as? String ?: "{}"
                            
                            // Prioritize Card Face, then Card, then full frame
                            val cardFaceBmp = identityItem.extra["card_face_bitmap"] as? Bitmap
                            val cardBmp = identityItem.extra["card_bitmap"] as? Bitmap
                            
                            val finalBmp = cardFaceBmp ?: cardBmp
                            
                            if (finalBmp != null) {
                                onSnap(finalBmp.copy(finalBmp.config ?: Bitmap.Config.ARGB_8888, true))
                                consecutiveDetections.set(-9999)
                                onDetected(true, metaStr)
                                return@FrameProcessingRunnable
                            }
                        }
                    } else if (mode != ScanMode.PADDLE_OCR && mode != ScanMode.TESSERACT_FAST_OCR && mode != ScanMode.IDENTITY_VERIFICATION) {
                        if (result.success && result.items.isNotEmpty()) {
                            criteriaMet = true
                            metaStr = JSONObject().apply {
                                put("type", mode.name)
                                put("count", result.items.size)
                            }.toString()
                        }
                    }

                    if (criteriaMet) {
                        val count = consecutiveDetections.incrementAndGet()
                        onDetected(true, metaStr)
                        if (count > 8) {
                            consecutiveDetections.set(-9999)
                            // Copy before the runnable recycles it
                            val snap = rotatedBitmap.copy(rotatedBitmap.config ?: Bitmap.Config.ARGB_8888, true)
                            onSnap(snap)
                        }
                    } else {
                        consecutiveDetections.set(0)
                        onDetected(false, "")
                    }
                }
            } catch (e: Exception) {
                Log.e("CameraX", "Analyze error in Runnable", e)
            }
        }
    }

    androidx.compose.runtime.DisposableEffect(frameProcessingRunnable) {
        val thread = Thread(frameProcessingRunnable, "FrameProcessingThread")
        if (!isLowSpecDevice) {
            thread.start()
        }
        onDispose {
            frameProcessingRunnable.release()
            thread.interrupt()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
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
                        .setTargetResolution(android.util.Size(720, 1280))
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()
                        .also {
                            it.setAnalyzer(executor) { image ->
                                if (!isAppInForeground.get()) {
                                    image.close()
                                    return@setAnalyzer
                                }

                                previewWidth = image.width
                                previewHeight = image.height
                                
                                val currentTime = System.currentTimeMillis()

                                if (isLowSpecDevice) {
                                    // โหมด Low-Spec: บังคับจำกัด FPS และรอให้ประมวลผลเสร็จทีละเฟรม
                                    if (currentTime - lastProcessTime < 80) { // Max ~12 FPS
                                        image.close()
                                        return@setAnalyzer
                                    }

                                    if (!isProcessingFrame.compareAndSet(false, true)) {
                                        image.close()
                                        return@setAnalyzer
                                    }
                                } else {
                                    // โหมดเครื่องทั่วไป: โยนเข้า FrameProcessingRunnable ให้ทำงานอิสระตามรอบกล้อง
                                    if (currentTime - lastProcessTime < 30) { // Max ~30 FPS limit
                                        image.close()
                                        return@setAnalyzer
                                    }
                                }

                                lastProcessTime = currentTime
                                var bitmap: Bitmap? = null
                                var rotatedBitmap: Bitmap? = null
                                try {
                                    bitmap = image.toBitmap()
                                    rotatedBitmap = rotateBitmap(bitmap, image.imageInfo.rotationDegrees)
                                    
                                    // ป้องกัน Memory Leak กรณีที่ rotateBitmap คืนค่าคนละ Instance
                                    if (bitmap !== rotatedBitmap) {
                                        bitmap.recycle()
                                    }

                                    if (!isLowSpecDevice) {
                                        // ให้ Runnable จัดการประมวลผลและ Recycle ต่อไป
                                        frameProcessingRunnable.setNextFrame(rotatedBitmap)
                                        rotatedBitmap = null // ล้าง ref ป้องกันโดน recycle ใน finally
                                    } else {
                                        // ประมวลผลแบบ Block สำหรับสเปคต่ำ
                                        val result = AIManager.process(rotatedBitmap)
                                        if (result != null) {
                                            latestDetections = result.items
                                            var criteriaMet = false
                                            var metaStr = "{}"

                                            if (mode == ScanMode.HAND_DETECTION) {
                                                val handMatch = result.items.find { item ->
                                                    val sideStr = item.extra["side"] as? String ?: ""
                                                    targetHand == HandSide.ANY || sideStr.equals(targetHand.name, ignoreCase = true)
                                                }
                                                if (handMatch != null && result.success) {
                                                    criteriaMet = true
                                                    metaStr = JSONObject().apply {
                                                        put("detected", true)
                                                        put("side", handMatch.extra["side"])
                                                    }.toString()
                                                }
                                            } else if (mode == ScanMode.IDENTITY_VERIFICATION) {
                                                val identityItem = result.items.find { it.label == "IDENTITY_VERIFICATION" }
                                                if (identityItem != null) {
                                                    criteriaMet = true
                                                    metaStr = identityItem.extra["verification_metrics"] as? String ?: "{}"
                                                    
                                                    // Prioritize Card Face, then Card, then full frame
                                                    val cardFaceBmp = identityItem.extra["card_face_bitmap"] as? Bitmap
                                                    val cardBmp = identityItem.extra["card_bitmap"] as? Bitmap
                                                    
                                                    val finalBmp = cardFaceBmp ?: cardBmp
                                                    
                                                    if (finalBmp != null) {
                                                        onSnap(finalBmp.copy(finalBmp.config ?: Bitmap.Config.ARGB_8888, true))
                                                        consecutiveDetections.set(-9999)
                                                        onDetected(true, metaStr)
                                                        return@setAnalyzer
                                                    }
                                                }
                                            } else if (mode != ScanMode.PADDLE_OCR && mode != ScanMode.TESSERACT_FAST_OCR && mode != ScanMode.IDENTITY_VERIFICATION) {
                                                if (result.success && result.items.isNotEmpty()) {
                                                    criteriaMet = true
                                                    metaStr = JSONObject().apply {
                                                        put("type", mode.name)
                                                        put("count", result.items.size)
                                                    }.toString()
                                                }
                                            }

                                            if (criteriaMet) {
                                                val count = consecutiveDetections.incrementAndGet()
                                                onDetected(true, metaStr)
                                                if (count > 8) {
                                                    consecutiveDetections.set(-9999)
                                                    onSnap(rotatedBitmap.copy(rotatedBitmap.config ?: Bitmap.Config.ARGB_8888, true))
                                                }
                                            } else {
                                                consecutiveDetections.set(0)
                                                onDetected(false, "")
                                            }
                                        }
                                    }
                                } catch (e: Exception) {
                                    Log.e("CameraX", "Analyze error", e)
                                } finally {
                                    // เฉพาะโหมด Low Spec หรือ Error ที่ rotatedBitmap ยังไม่ถูกเซ็ตเป็น null
                                    if (rotatedBitmap != null) {
                                        rotatedBitmap.recycle()
                                    }
                                    image.close()
                                    if (isLowSpecDevice) {
                                        isProcessingFrame.set(false)
                                    }
                                }
                            }
                        }

                    try {
                        cameraProvider.unbindAll()
                        cameraProvider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageCapture, imageAnalyzer)
                    } catch (e: Exception) {
                        Log.e("CameraX", "Binding failed", e)
                    }
                }, ContextCompat.getMainExecutor(context))
                previewView
            }
        )

        // Overlay Graphics (Skeleton / Bounding Boxes)
        Canvas(modifier = Modifier.fillMaxSize()) {
            // Coordinate mapping (Image coordinates -> View coordinates)
            // CameraX 720x1280 is usually landscape raw, rotated. 
            // We use previewHeight/Width because image is rotated
            val scaleX = size.width / previewHeight.toFloat()
            val scaleY = size.height / previewWidth.toFloat()

            latestDetections.forEach { item ->
                when (mode) {
                    ScanMode.POSE_DETECTION -> {
                        val rawLandmarks = item.extra["landmarks_raw"] as? Map<Int, android.graphics.PointF>
                        if (rawLandmarks != null) {
                            // 1. Draw Points
                            rawLandmarks.values.forEach { pt ->
                                drawCircle(
                                    color = Color.Cyan,
                                    radius = 6.dp.toPx(),
                                    center = Offset(pt.x * scaleX, pt.y * scaleY)
                                )
                            }
                            // 2. Draw Skeleton
                            val connections = listOf(
                                11 to 12, 11 to 13, 13 to 15, 12 to 14, 14 to 16, // Arms
                                11 to 23, 12 to 24, 23 to 24, // Torso
                                23 to 25, 25 to 27, 24 to 26, 26 to 28 // Legs
                            )
                            connections.forEach { (a, b) ->
                                val p1 = rawLandmarks[a]
                                val p2 = rawLandmarks[b]
                                if (p1 != null && p2 != null) {
                                    drawLine(
                                        color = Color.White,
                                        start = Offset(p1.x * scaleX, p1.y * scaleY),
                                        end = Offset(p2.x * scaleX, p2.y * scaleY),
                                        strokeWidth = 3.dp.toPx()
                                    )
                                }
                            }
                        }
                    }
                    ScanMode.FACE_DETECTION -> {
                        val rect = item.boundingBox
                        drawRoundRect(
                            color = Color.Red,
                            topLeft = Offset(rect.left * scaleX, rect.top * scaleY),
                            size = Size((rect.right - rect.left) * scaleX, (rect.bottom - rect.top) * scaleY),
                            cornerRadius = CornerRadius(10f, 10f),
                            style = Stroke(width = 3.dp.toPx())
                        )
                    }
                    ScanMode.HAND_DETECTION -> {
                        val rect = item.boundingBox
                        drawRoundRect(
                            color = Color.Yellow,
                            topLeft = Offset(rect.left * scaleX, rect.top * scaleY),
                            size = Size((rect.right - rect.left) * scaleX, (rect.bottom - rect.top) * scaleY),
                            cornerRadius = CornerRadius(16f, 16f),
                            style = Stroke(width = 4.dp.toPx())
                        )
                    }
                    ScanMode.PADDLE_OCR, ScanMode.TESSERACT_FAST_OCR, ScanMode.IDENTITY_VERIFICATION -> {
                        val rect = item.boundingBox
                        drawRect(
                            color = Color.Cyan.copy(alpha = 0.8f),
                            topLeft = Offset(rect.left * scaleX, rect.top * scaleY),
                            size = Size((rect.right - rect.left) * scaleX, (rect.bottom - rect.top) * scaleY),
                            style = Stroke(width = 2.dp.toPx())
                        )
                    }
                    ScanMode.SELFIE, ScanMode.SUBJECT -> {
                        val rect = item.boundingBox
                        drawRect(
                            color = Color.Green.copy(alpha = 0.5f),
                            topLeft = Offset(rect.left * scaleX, rect.top * scaleY),
                            size = Size((rect.right - rect.left) * scaleX, (rect.bottom - rect.top) * scaleY),
                            style = Stroke(width = 2.dp.toPx())
                        )
                    }
                }
            }

            // Performance Stats Overlay
            drawContext.canvas.nativeCanvas.apply {
                val paint = android.graphics.Paint().apply {
                    color = android.graphics.Color.GREEN
                    textSize = 36f
                    typeface = android.graphics.Typeface.MONOSPACE
                    setShadowLayer(4f, 0f, 0f, android.graphics.Color.BLACK)
                }
                val yStart = 300f
                drawText("FPS: ${AIManager.getFPS()}", 40f, yStart, paint)
                drawText("Latency: ${AIManager.getLastLatency()}ms", 40f, yStart + 50f, paint)
                drawText("Mode: ${mode.name}", 40f, yStart + 100f, paint)
                
                // Draw Status Message
                paint.color = android.graphics.Color.YELLOW
                drawText("Status: $status", 40f, yStart + 150f, paint)
                
                if (latestDetections.isEmpty() && mode != ScanMode.PADDLE_OCR && mode != ScanMode.TESSERACT_FAST_OCR) {
                    paint.color = android.graphics.Color.RED
                    drawText("No Object Detected", 40f, yStart + 150f, paint)
                }
            }

            if (mode == ScanMode.PADDLE_OCR || mode == ScanMode.TESSERACT_FAST_OCR || mode == ScanMode.HAND_DETECTION || mode == ScanMode.IDENTITY_VERIFICATION) {
                val isCardMode = mode == ScanMode.PADDLE_OCR || mode == ScanMode.TESSERACT_FAST_OCR || mode == ScanMode.IDENTITY_VERIFICATION
                val boxWidth = if (isCardMode) size.width * 0.85f else size.width * 0.7f
                val boxHeight = if (isCardMode) boxWidth * (5.4f / 8.5f) else boxWidth * 1.2f
                val left = (size.width - boxWidth) / 2
                val top = (size.height - boxHeight) / 2
                drawRoundRect(
                    color = if (latestDetections.isNotEmpty()) Color.Green else Color.White,
                    topLeft = Offset(left, top),
                    size = Size(boxWidth, boxHeight),
                    cornerRadius = CornerRadius(16f, 16f),
                    style = Stroke(width = 6.dp.toPx())
                )
            }
        }
    }

    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        onDispose {
            try {
                cameraProviderFuture.get().unbindAll()
            } catch (e: Exception) {}
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
            .padding(2.dp)
    ) {
        options.forEachIndexed { index, option ->
            val isSelected = index == selectedIndex
            val bgColor = animateColorAsState(if (isSelected) selectedColor else Color.Transparent)

            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(bgColor.value)
                    .clickable { onSelect(index) }
                    .padding(horizontal = 8.dp, vertical = 6.dp)
            ) {
                Text(
                    option, 
                    color = Color.White, 
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                    fontSize = 10.sp
                )
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
        Text("Review Result (${mode.name})", color = Color.White, fontSize = 18.sp, modifier = Modifier.padding(16.dp))
        Image(
            bitmap = image.asImageBitmap(),
            contentDescription = "Captured",
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentScale = ContentScale.Fit
        )
        Box(modifier = Modifier.fillMaxWidth().heightIn(max = 150.dp).background(Color.DarkGray).padding(12.dp)) {
            Text("Data: $resultJson", color = Color.White, fontSize = 11.sp)
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

fun rotateBitmap(bitmap: Bitmap, degrees: Int): Bitmap {
    if (degrees == 0) return bitmap
    val matrix = Matrix()
    matrix.postRotate(degrees.toFloat())
    return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
}
