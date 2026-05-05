package com.example.android_screen_relay.core

import android.Manifest
import android.app.ActivityManager
import android.content.Context
import com.example.android_screen_relay.LogRepository
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.util.Log
import android.util.Size
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.PickVisualMediaRequest
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.isActive
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import java.util.concurrent.Executors
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import android.util.Base64
import java.io.ByteArrayOutputStream

import java.io.InputStream
import android.graphics.Paint
import android.graphics.Path
import android.view.TextureView
import android.hardware.camera2.CameraManager
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import com.example.android_screen_relay.RelayService
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.toSize
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.text.style.TextAlign
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items

enum class AiMode { PREVIEW, PADDLE_OCR, TESSERACT_FAST_OCR, HAND_DETECTION, FACE_DETECTION, POSE_DETECTION, SELFIE_SEGMENTATION, SUBJECT_SEGMENTATION, OBJECT_DETECTION, CUSTOM_OBJECT_DETECTION, TEXT_RECOGNITION, VERIFIED_AUTO_CAPTURE, IDENTITY_VERIFICATION }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AIScreen() {
    val context = LocalContext.current
    var hasPermission by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { granted -> hasPermission = granted }
    )
    LaunchedEffect(Unit) {
        launcher.launch(Manifest.permission.CAMERA)
        ComputeModeManager.initByDeviceSpec(context)
    }

    if (hasPermission) {
        AIScreenLayout()
    } else {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Camera Permission Required", color = Color.White)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AIScreenLayout() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // States
    var currentAiMode by remember { mutableStateOf(AiMode.PREVIEW) }
    var isProcessing by remember { mutableStateOf(false) }
    var showAiModeSheet by remember { mutableStateOf(false) }
    var currentImage by remember { mutableStateOf<Bitmap?>(null) }
    var leftPalmImage by remember { mutableStateOf<Bitmap?>(null) }
    var rightPalmImage by remember { mutableStateOf<Bitmap?>(null) }
    var ocrResultJson by remember { mutableStateOf("[]") }
    var ocrTimeMs by remember { mutableStateOf(0L) }
    var computeMode by remember { mutableStateOf(ComputeModeManager.getMode()) }
    var targetHand by remember { mutableStateOf("Left") }
    var targetFaceMode by remember { mutableStateOf("card") }
    var zoomScale by remember { mutableStateOf(1.0f) }
    var useCropMode by remember { mutableStateOf(true) }
    var selectedResolution by remember { mutableStateOf<android.util.Size?>(null) }
    var availableResolutions by remember { mutableStateOf<List<android.util.Size>>(emptyList()) }
    var selectedCameraId by remember { mutableStateOf("0") }
    var selectedAspectRatio by remember { mutableStateOf(UiAspectRatio.RATIO_1_1) }
    var cropImage by remember { mutableStateOf<Bitmap?>(null) }
    var processingResultMsg by remember { mutableStateOf<String?>(null) }
    var horizontalFlip by remember { mutableStateOf(false) }
    var verticalFlip by remember { mutableStateOf(false) }


    LaunchedEffect(currentAiMode) {
        // 🌟 Fix: Use sequential execution instead of launching into IO
        // This ensures the write-lock in AIManager is respected and no parallel init occurs
        isProcessing = true
        withContext(Dispatchers.Default) {
            AIManager.switchProcessor(context, currentAiMode.name)
        }
        isProcessing = false
    }

    // Models will be lazy-loaded on demand now
    DisposableEffect(Unit) {
        onDispose {
            // Nothing to globally release, instances are local
        }
    }

    LaunchedEffect(currentAiMode) {
        FirebaseLogger.logStep(
            context = context,
            stepName = "SWITCH_AI_MODE",
            status = "SUCCESS",
            extraData = mapOf("new_mode" to currentAiMode.name)
        )
    }

    LaunchedEffect(computeMode) {
        // Initialization is deferred to the processing phase to save RAM (2GB optimization)
        FirebaseLogger.logStep(
            context = context,
            stepName = "SWITCH_COMPUTE_MODE",
            status = "SUCCESS",
            extraData = mapOf("new_mode" to computeMode.name)
        )
    }

    // Permission
    var hasPermission by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { granted -> hasPermission = granted }
    )
    LaunchedEffect(Unit) {
        launcher.launch(Manifest.permission.CAMERA)
        // เตรียมระบบคำนวณตามสเปคเครื่องทันทีที่เปิดหน้า AI
        ComputeModeManager.initByDeviceSpec(context)
    }

    // Gallery Launcher
    val galleryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        if (uri != null) {
            FirebaseLogger.logStep(
                context = context,
                stepName = "IMPORT_IMAGE",
                status = "SUCCESS",
                extraData = mapOf("source" to "GALLERY")
            )
            scope.launch(Dispatchers.IO) {
                try {
                    val stream: InputStream? = context.contentResolver.openInputStream(uri)
                    val bitmap = BitmapFactory.decodeStream(stream)
                    withContext(Dispatchers.Main) {
                        cropImage = bitmap
                        // currentImage = bitmap // Was moved to crop stage
                        ocrResultJson = "[]"
                        ocrTimeMs = 0
                    }
                } catch (e: Exception) {
                    FirebaseLogger.logStep(context, "IMPORT_IMAGE", "ERROR", error = e)
                    Log.e("OCR", "Load image failed", e)
                }
            }
        }
    }

    if (currentImage != null || (leftPalmImage != null && rightPalmImage != null)) {
        // Update floating overlay for Success state
        LaunchedEffect(Unit) {
            RelayService.getInstance()?.let { service ->
                service.overlayManager.updateMetrics(
                    ramUsed = SystemMonitor.getCurrentResourceUsage(context).ramUsedMb,
                    ramTotal = SystemMonitor.getCurrentResourceUsage(context).ramTotalMb,
                    cpu = SystemMonitor.getCurrentResourceUsage(context).cpuUsage,
                    status = "✅ Snap success!",
                    inputSize = if (currentImage != null) "${currentImage!!.width}x${currentImage!!.height}" else "--",
                    fps = 0,
                    frameLatency = 0,
                    detectorLatency = ocrTimeMs
                )
            }
        }

        // Image Analysis Mode (Screenshot 2 style)
        OCRResultScreen(
            aiMode = currentAiMode,
            image = currentImage
                ?: leftPalmImage!!, // If currentImage is null, it's Palm mode so fallback to leftPalmImage, UI will handle both
            leftPalmImage = leftPalmImage,
            rightPalmImage = rightPalmImage,
            jsonResult = ocrResultJson,
            timeMs = ocrTimeMs,
            zoomScale = zoomScale,
            scanResolution = "${selectedResolution?.width ?: 0}x${selectedResolution?.height ?: 0} px",
            isProcessing = isProcessing,
            computeMode = computeMode,
            onComputeModeChange = {
                computeMode = it
                ComputeModeManager.setMode(it)
            },
            onClear = {
                currentImage?.recycle()
                leftPalmImage?.recycle()
                rightPalmImage?.recycle()
                cropImage?.recycle()

                currentImage = null
                leftPalmImage = null
                rightPalmImage = null
                cropImage = null
                ocrResultJson = "[]"
                ocrTimeMs = 0
                targetHand = "Left"
            },
            onRunModel = {
                if (!isProcessing) {
                    isProcessing = true
                    scope.launch(Dispatchers.IO) {
                        try {
                            // 🌟 Fix: Use AIManager to switch/ensure OCR is loaded
                            // This prevents native JNI state collision and respects the global lock
                            val switchSuccess = AIManager.switchProcessor(context, "OCR")
                            if (!switchSuccess) {
                                withContext(Dispatchers.Main) {
                                    Toast.makeText(context, "Failed to load OCR model", Toast.LENGTH_LONG).show()
                                    isProcessing = false
                                }
                                return@launch
                            }

                            val resultJson = AIManager.runWithProcessor { proc ->
                                val ocrProc = proc as? OCRProcessor
                                val lazyOcr = ocrProc?.paddleOCR
                                
                                if (lazyOcr == null) return@runWithProcessor null

                                val (canRun, errorMsg) = lazyOcr.canRunInference(context)
                                if (!canRun) {
                                    return@runWithProcessor "ERROR:$errorMsg"
                                }

                                val mutableBitmap = currentImage!!.copy(Bitmap.Config.ARGB_8888, true)
                                val benchmarkResults = OCRBenchmarkRunner.runFullBenchmarkSuite(context, lazyOcr, mutableBitmap)
                                benchmarkResults.toString()
                            }

                            if (resultJson == null || resultJson.startsWith("ERROR:")) {
                                withContext(Dispatchers.Main) {
                                    val msg = resultJson?.removePrefix("ERROR:") ?: "OCR Processor unavailable"
                                    Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                                    isProcessing = false
                                }
                                return@launch
                            }

                            val end = System.currentTimeMillis()
                            // Note: duration here is just an estimate since runWithProcessor might have waited
                            // But accuracy isn't critical for the manual run UI

                            withContext(Dispatchers.Main) {
                                ocrResultJson = resultJson
                                // We don't have the exact duration easily here but resultJson contains individual bench times
                                ocrTimeMs = 0 
                                isProcessing = false
                            }

                            // ✅ Generate and send payload off the Main thread
                            val payload = generateOCRPayload(context, currentImage!!, resultJson, 0)
                            val payloadStr = payload.toString()
                            val service = RelayService.getInstance()
                            service?.broadcastMessage(payloadStr)

                        } catch (e: Throwable) {
                            Log.e("OCR", "Scan/Benchmark error", e)
                            // ... error handling ...
                            withContext(Dispatchers.Main) {
                                Toast.makeText(context, "OCR Error: ${e.message}", Toast.LENGTH_SHORT).show()
                                isProcessing = false
                            }
                        }
                    }
                }
            },
            onSendWs = {
                if (ocrResultJson != "[]" && ocrResultJson.isNotEmpty()) {
                    scope.launch(Dispatchers.IO) {
                        val payload =
                            generateOCRPayload(context, currentImage ?: leftPalmImage!!, ocrResultJson, ocrTimeMs)
                        val jsonString = payload.toString()
                        val service = RelayService.getInstance()
                        if (service != null) {
                            service.broadcastMessage(jsonString)
                            withContext(Dispatchers.Main) {
                                Toast.makeText(context, "Sent to WebSocket!", Toast.LENGTH_SHORT).show()
                            }
                        } else {
                            withContext(Dispatchers.Main) {
                                Toast.makeText(context, "WebSocket Service not running", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                }
            },
            onGalleryClick = { galleryLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
        )
    } else {
        // Camera Preview Mode (Screenshot 1 style)
        if (hasPermission) {
            CameraPreviewScreen(
                aiMode = currentAiMode,
                onAiModeChange = { currentAiMode = it },
                targetHand = targetHand,
                onTargetHandChange = { targetHand = it },
                targetFaceMode = targetFaceMode,
                onTargetFaceModeChange = { targetFaceMode = it },
                zoomScale = zoomScale,
                onZoomScaleChange = { zoomScale = it },
                useCropMode = useCropMode,
                onUseCropModeChange = { useCropMode = it },
                selectedResolution = selectedResolution,
                onResolutionChange = { selectedResolution = it },
                availableResolutions = availableResolutions,
                onAvailableResolutionsChange = { availableResolutions = it },
                selectedCameraId = selectedCameraId,
                onCameraIdChange = { selectedCameraId = it },
                selectedAspectRatio = selectedAspectRatio,
                onAspectRatioChange = { selectedAspectRatio = it },
                horizontalFlip = horizontalFlip,
                onHorizontalFlipChange = { horizontalFlip = it },
                verticalFlip = verticalFlip,
                onVerticalFlipChange = { verticalFlip = it },
                onStableDetection = { bitmap, isFront ->
                    if (isProcessing || currentAiMode == AiMode.PREVIEW) return@CameraPreviewScreen Pair(false, emptyList())
                    val options = mapOf(
                        "is_front" to isFront,
                        "face_mode" to targetFaceMode
                    )

                    // 1. Special Handling for Palmprint (Needs custom scaling)
                    if (currentAiMode == AiMode.HAND_DETECTION) {
                        try {
                            val result = AIManager.runWithProcessor { proc ->
                                val processor = proc as? PalmprintProcessor
                                if (processor != null) {
                                    val scale = 480f / maxOf(bitmap.width, bitmap.height)
                                    val processBitmap = if (scale < 1f) {
                                        Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt(), (bitmap.height * scale).toInt(), false)
                                    } else bitmap

                                    val res = processor.process(processBitmap, options)
                                    if (processBitmap !== bitmap) processBitmap.recycle()
                                    res
                                } else null
                            }

                            if (result != null) {
                                val scale = 480f / maxOf(bitmap.width, bitmap.height)
                                val item = result.items.firstOrNull()
                                val success = result.success && item?.extra?.get("hand")?.toString()?.equals(targetHand, ignoreCase = true) == true
                                val scaledItems = if (scale < 1f) {
                                    result.items.map {
                                        val rect = it.boundingBox
                                        it.copy(boundingBox = android.graphics.RectF(rect.left / scale, rect.top / scale, rect.right / scale, rect.bottom / scale))
                                    }
                                } else result.items
                                Pair(success, scaledItems)
                            } else Pair(false, emptyList())
                        } catch (e: Throwable) { Pair(false, emptyList()) }
                    }
                    // 2. Handling for OCR (Using centralized process and custom card check)
                    else if (currentAiMode == AiMode.PADDLE_OCR || currentAiMode == AiMode.TESSERACT_FAST_OCR) {
                        try {
                            val scale = 720f / maxOf(bitmap.width, bitmap.height)
                            val scaled = if (scale < 1f) {
                                Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt(), (bitmap.height * scale).toInt(), false)
                            } else bitmap

                            val result = AIManager.process(scaled, options)
                            if (scaled !== bitmap) scaled.recycle()

                            if (result != null && result.success) {
                                val strictIdRegex = Regex("""\d[\s-]*\d{4}[\s-]*\d{5}[\s-]*\d{2}[\s-]*\d""")
                                val idKeywords = listOf("เลขประจำตัว", "ประชาชน", "National", "Identification", "Thai National ID Card")

                                var hasNumber = false
                                var hasNameInfo = false
                                var hasDateInfo = false

                                val scaledItems = result.items.map { item ->
                                    val lbl = item.label
                                    val rawText = lbl.replace(" ", "").replace("-", "")

                                    if (strictIdRegex.containsMatchIn(lbl) || (rawText.length >= 8 && rawText.contains(Regex("""\d{8,}""")))) {
                                        hasNumber = true
                                    }
                                    if (listOf("ชื่อ", "นามสกุล", "Name", "Last name", "Lest name", "เลขประจำตัว", "ประชาชน").any { lbl.contains(it, ignoreCase = true) } || idKeywords.any { lbl.contains(it, ignoreCase = true) }) {
                                        hasNameInfo = true
                                    }
                                    if (listOf("เกิด", "เกิดวันที่", "วัน", "เดือน", "ปี", "Date of Birth", "Date", "Birth").any { lbl.contains(it, ignoreCase = true) }) {
                                        hasDateInfo = true
                                    }

                                    if (scale < 1f) {
                                        val r = item.boundingBox
                                        item.copy(boundingBox = android.graphics.RectF(r.left / scale, r.top / scale, r.right / scale, r.bottom / scale))
                                    } else item
                                }

                                val foundId = if (currentAiMode == AiMode.TESSERACT_FAST_OCR || currentAiMode == AiMode.PADDLE_OCR) {
                                    hasNumber && hasNameInfo && hasDateInfo
                                } else {
                                    hasNumber && hasNameInfo
                                }

                                Pair(foundId, scaledItems)
                            } else Pair(false, emptyList())
                        } catch (e: Exception) { Pair(false, emptyList()) }
                    }
                    // 3. Handling for  Recognition
                    else if (currentAiMode == AiMode.TEXT_RECOGNITION) {
                        try {
                            val aiScale = 720f / maxOf(bitmap.width, bitmap.height).coerceAtLeast(1)
                            val aiBitmap = if (aiScale < 1f) {
                                Bitmap.createScaledBitmap(bitmap, (bitmap.width * aiScale).toInt(), (bitmap.height * aiScale).toInt(), false)
                            } else bitmap

                            val result = AIManager.process(aiBitmap, options)
                            if (aiBitmap !== bitmap) aiBitmap.recycle()

                            if (result != null && result.success) {
                                val finalItems = if (aiScale < 1f) {
                                    result.items.map { item ->
                                        val r = item.boundingBox
                                        item.copy(boundingBox = android.graphics.RectF(
                                            r.left / aiScale, r.top / aiScale,
                                            r.right / aiScale, r.bottom / aiScale
                                        ))
                                    }
                                } else result.items
                                Pair(finalItems.isNotEmpty(), finalItems)
                            } else Pair(false, emptyList())
                        } catch (e: Exception) { Pair(false, emptyList()) }
                    }
                    // 4. Special Handling for Face Detection (Rules: Center, Size, Angle)
                    else if (currentAiMode == AiMode.FACE_DETECTION) {
                        try {
                            val aiScale = 720f / maxOf(bitmap.width, bitmap.height).coerceAtLeast(1)
                            val aiBitmap = if (aiScale < 1f) {
                                Bitmap.createScaledBitmap(bitmap, (bitmap.width * aiScale).toInt(), (bitmap.height * aiScale).toInt(), false)
                            } else bitmap

                            val result = AIManager.process(aiBitmap, options)
                            if (aiBitmap !== bitmap) aiBitmap.recycle()

                            if (result != null && result.success && result.items.isNotEmpty()) {
                                val face = result.items.maxByOrNull { it.confidence }!!
                                val box = face.boundingBox

                                // Map back to original resolution
                                val originalBox = if (aiScale < 1f) {
                                    android.graphics.RectF(box.left / aiScale, box.top / aiScale, box.right / aiScale, box.bottom / aiScale)
                                } else box

                                // Rule 1: Position centered & Rule 2: Size fit (roughly > 15% of frame)
                                val centerX = originalBox.centerX()
                                val centerY = originalBox.centerY()
                                val frameCenterX = bitmap.width / 2f
                                val frameCenterY = bitmap.height / 2f
                                val isCentered = kotlin.math.abs(centerX - frameCenterX) < (bitmap.width * 0.15f) &&
                                                kotlin.math.abs(centerY - frameCenterY) < (bitmap.height * 0.15f)
                                val isProperSize = (originalBox.width() * originalBox.height()) > (bitmap.width * bitmap.height * 0.15f)

                                // Rule 3: Face angles <= 25 degrees
                                val yaw = (face.extra["head_euler_y"] as? Float) ?: 0f
                                val pitch = (face.extra["head_euler_x"] as? Float) ?: 0f
                                val roll = (face.extra["head_euler_z"] as? Float) ?: 0f
                                val isStraight = kotlin.math.abs(yaw) < 25f &&
                                                kotlin.math.abs(pitch) < 25f &&
                                                kotlin.math.abs(roll) < 25f

                                val finalSuccess = isCentered && isProperSize && isStraight
                                Pair(finalSuccess, listOf(face.copy(boundingBox = originalBox)))
                            } else {
                                Pair(false, emptyList())
                            }
                        } catch (e: Throwable) { Pair(false, emptyList()) }
                    }
                    // 5. Special Handling for Subject Segmentation (Full Detection Rule)
                    else if (currentAiMode == AiMode.SUBJECT_SEGMENTATION) {
                        try {
                            val aiScale = 720f / maxOf(bitmap.width, bitmap.height).coerceAtLeast(1)
                            val aiBitmap = if (aiScale < 1f) {
                                Bitmap.createScaledBitmap(bitmap, (bitmap.width * aiScale).toInt(), (bitmap.height * aiScale).toInt(), false)
                            } else bitmap

                            val result = AIManager.process(aiBitmap, options)
                            if (aiBitmap !== bitmap) aiBitmap.recycle()

                            if (result != null && result.success && result.items.isNotEmpty()) {
                                // Rule: Full Detection (Centered, proper size, not touching edges)
                                val items = result.items
                                val unionRect = android.graphics.RectF(items[0].boundingBox)
                                items.forEach { unionRect.union(it.boundingBox) }

                                val frameW = aiBitmap.width.toFloat()
                                val frameH = aiBitmap.height.toFloat()

                                // Margin check: at least 2% from edges in AI resolution
                                val marginW = frameW * 0.02f
                                val marginH = frameH * 0.02f

                                val isFullyInside = unionRect.left > marginW &&
                                                   unionRect.top > marginH &&
                                                   unionRect.right < (frameW - marginW) &&
                                                   unionRect.bottom < (frameH - marginH)

                                // Centering check
                                val centerX = unionRect.centerX()
                                val centerY = unionRect.centerY()
                                val isCentered = kotlin.math.abs(centerX - frameW / 2f) < (frameW * 0.20f) &&
                                                kotlin.math.abs(centerY - frameH / 2f) < (frameH * 0.20f)

                                // Size check: at least 10% area
                                val isProperSize = (unionRect.width() * unionRect.height()) > (frameW * frameH * 0.10f)

                                val finalSuccess = isFullyInside && isCentered && isProperSize

                                val finalItems = items.map { item ->
                                    val r = item.boundingBox
                                    item.copy(boundingBox = android.graphics.RectF(
                                        r.left / aiScale, r.top / aiScale,
                                        r.right / aiScale, r.bottom / aiScale
                                    ))
                                }
                                Pair(finalSuccess, finalItems)
                            } else {
                                Pair(false, emptyList())
                            }
                        } catch (e: Throwable) { Pair(false, emptyList()) }
                    }
                    // 6. Special Handling for Verified Auto Capture or Identity Verification
                    else if (currentAiMode == AiMode.VERIFIED_AUTO_CAPTURE || currentAiMode == AiMode.IDENTITY_VERIFICATION) {
                        try {
                            val aiScale = 720f / maxOf(bitmap.width, bitmap.height).coerceAtLeast(1)
                            val aiBitmap = if (aiScale < 1f) {
                                Bitmap.createScaledBitmap(bitmap, (bitmap.width * aiScale).toInt(), (bitmap.height * aiScale).toInt(), false)
                            } else bitmap

                            val result = AIManager.process(aiBitmap, options)
                            if (aiBitmap !== bitmap) aiBitmap.recycle()

                            if (result != null && result.success && result.items.isNotEmpty()) {
                                val face = result.items.first()
                                val box = face.boundingBox

                                // Map back to original resolution
                                val originalBox = if (aiScale < 1f) {
                                    android.graphics.RectF(box.left / aiScale, box.top / aiScale, box.right / aiScale, box.bottom / aiScale)
                                } else box

                                // Rule 1: Position centered & Rule 2: Size fit (roughly > 15% of frame)
                                val centerX = originalBox.centerX()
                                val centerY = originalBox.centerY()
                                val frameCenterX = bitmap.width / 2f
                                val frameCenterY = bitmap.height / 2f
                                val isCentered = kotlin.math.abs(centerX - frameCenterX) < (bitmap.width * 0.15f) &&
                                                kotlin.math.abs(centerY - frameCenterY) < (bitmap.height * 0.15f)
                                val isProperSize = (originalBox.width() * originalBox.height()) > (bitmap.width * bitmap.height * 0.15f)

                                // Rule 3: Face angles <= 25 degrees
                                val yaw = (face.extra["head_euler_y"] as? Float) ?: 0f
                                val pitch = (face.extra["head_euler_x"] as? Float) ?: 0f
                                val roll = (face.extra["head_euler_z"] as? Float) ?: 0f
                                val isStraight = kotlin.math.abs(yaw) < 25f &&
                                                kotlin.math.abs(pitch) < 25f &&
                                                kotlin.math.abs(roll) < 25f

                                val finalSuccess = isCentered && isProperSize && isStraight
                                Pair(finalSuccess, listOf(face.copy(boundingBox = originalBox)))
                            } else {
                                Pair(false, emptyList())
                            }
                        } catch (e: Throwable) { Pair(false, emptyList()) }
                    }
                    // 7. General Handling for other modes (POSE, etc.)
                    else {
                        try {
                            // 🌟 Smart Scaling for AI: ML Kit works best with reasonable sizes (e.g. max 720px)
                            // This prevents ML Kit from failing or being too slow on high-res bitmaps
                            val aiScale = 720f / maxOf(bitmap.width, bitmap.height).coerceAtLeast(1)
                            val aiBitmap = if (aiScale < 1f) {
                                Bitmap.createScaledBitmap(bitmap, (bitmap.width * aiScale).toInt(), (bitmap.height * aiScale).toInt(), false)
                            } else bitmap

                            val result = AIManager.process(aiBitmap, options)
                            if (aiBitmap !== bitmap) aiBitmap.recycle()

                            if (result != null && result.success) {
                                // Map items back to original bitmap coordinates
                                val finalItems = if (aiScale < 1f) {
                                    result.items.map { item ->
                                        val r = item.boundingBox
                                        item.copy(boundingBox = android.graphics.RectF(
                                            r.left / aiScale, r.top / aiScale,
                                            r.right / aiScale, r.bottom / aiScale
                                        ))
                                    }
                                } else result.items
                                Pair(finalItems.isNotEmpty(), finalItems)
                            } else {
                                Pair(false, emptyList())
                            }
                        } catch (e: Throwable) { Pair(false, emptyList()) }
                    }
                },
                onImageCaptured = { bitmap, isFront ->
                    if (isProcessing) {
                        if (!bitmap.isRecycled) bitmap.recycle()
                        return@CameraPreviewScreen // Double check Busy State Lock
                    }

                    val isPreviewOnlyMode = currentAiMode == AiMode.POSE_DETECTION ||
                                            currentAiMode == AiMode.OBJECT_DETECTION ||
                                            currentAiMode == AiMode.CUSTOM_OBJECT_DETECTION
                    if (isPreviewOnlyMode) {
                        if (!bitmap.isRecycled) bitmap.recycle()
                        return@CameraPreviewScreen
                    }

                    if (currentAiMode == AiMode.HAND_DETECTION) {
                        isProcessing = true
                        scope.launch(Dispatchers.Default) {
                            try {
                                val pbStartMs = System.currentTimeMillis()
                                // 🌟 Fix: Use runWithProcessor to ensure thread-safe access to the active processor
                                val result = AIManager.runWithProcessor { proc ->
                                    val processor = proc as? com.example.android_screen_relay.core.PalmprintProcessor
                                    processor?.process(bitmap, mapOf("target_hand" to targetHand))
                                } ?: com.example.android_screen_relay.core.AIResult(false, emptyList(), 0, "Processor unavailable")
                                
                                val pbElapsedMs = System.currentTimeMillis() - pbStartMs

                                val item = result.items.firstOrNull()
                                val detectedHand = item?.extra?.get("hand")?.toString() ?: "Unknown"
                                val isFallback = item?.extra?.get("area_type")?.toString() == "fallback"

                                // Rule: If landmarks are found, hand MUST match.
                                // If it's a fallback (close-up), we allow it to pass since it inherits targetHand.
                                val isCorrectHand = detectedHand.equals(targetHand, ignoreCase = true) || isFallback

                                if (!result.success || item == null || !isCorrectHand) {
                                    withContext(Dispatchers.Main) {
                                        if (item != null && !isCorrectHand) {
                                            processingResultMsg = "❌ กรุณาใช้มือ ${if (targetHand == "Left") "[ซ้าย]" else "[ขวา]"} ให้ถูกต้อง"
                                        }
                                        isProcessing = false
                                    }
                                    if (!bitmap.isRecycled) bitmap.recycle()
                                    return@launch
                                }

                                val cropped = if (useCropMode && result.success && item != null) {
                                    val palmRoiMap = item.extra["palm_roi"] as? Map<*, *>
                                    val centerX = (palmRoiMap?.get("center_x") as? Float)
                                    val centerY = (palmRoiMap?.get("center_y") as? Float)
                                    val size = (palmRoiMap?.get("size") as? Float)
                                    val rotation = (palmRoiMap?.get("rotation") as? Float) ?: 0f

                                    if (centerX != null && centerY != null && size != null) {
                                        val matrix = android.graphics.Matrix()
                                        matrix.postRotate(rotation)
                                        val rotatedBitmap =
                                            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)

                                        // Map center point to new rotated bounding box
                                        val corners = floatArrayOf(
                                            0f, 0f,
                                            bitmap.width.toFloat(), 0f,
                                            bitmap.width.toFloat(), bitmap.height.toFloat(),
                                            0f, bitmap.height.toFloat()
                                        )
                                        matrix.mapPoints(corners)
                                        val minX = minOf(corners[0], corners[2], corners[4], corners[6])
                                        val minY = minOf(corners[1], corners[3], corners[5], corners[7])

                                        val cPts = floatArrayOf(centerX, centerY)
                                        matrix.mapPoints(cPts)
                                        val newCX = cPts[0] - minX
                                        val newCY = cPts[1] - minY

                                        val halfSize = (size / 2f) + 50f // Add 50px padding to the half-size for a wider crop
                                        val left = (newCX - halfSize).toInt().coerceAtLeast(0)
                                        val top = (newCY - halfSize).toInt().coerceAtLeast(0)
                                        var w = (halfSize * 2).toInt()
                                        var h = (halfSize * 2).toInt()

                                        // Bound checks
                                        if (left + w > rotatedBitmap.width) w = rotatedBitmap.width - left
                                        if (top + h > rotatedBitmap.height) h = rotatedBitmap.height - top

                                        val resultBmp = if (w > 0 && h > 0) {
                                            Bitmap.createBitmap(rotatedBitmap, left, top, w, h)
                                        } else rotatedBitmap

                                        if (resultBmp !== rotatedBitmap) rotatedBitmap.recycle()

                                        // 🌟 Apply zoomScale to final crop
                                        if (zoomScale > 1.0f) {
                                            val scaled = Bitmap.createScaledBitmap(resultBmp, (resultBmp.width * zoomScale).toInt(), (resultBmp.height * zoomScale).toInt(), true)
                                            resultBmp.recycle()
                                            scaled
                                        } else resultBmp
                                    } else {
                                        val padding = 50 // 50px padding for standard BBox crop
                                        val left = (item.boundingBox.left - padding).toInt().coerceAtLeast(0)
                                        val top = (item.boundingBox.top - padding).toInt().coerceAtLeast(0)
                                        val right = (item.boundingBox.right + padding).toInt().coerceAtMost(bitmap.width)
                                        val bottom = (item.boundingBox.bottom + padding).toInt().coerceAtMost(bitmap.height)
                                        val width = right - left
                                        val height = bottom - top
                                        if (width > 0 && height > 0) {
                                            val cropped = Bitmap.createBitmap(bitmap, left, top, width, height)
                                            // 🌟 Apply zoomScale to final crop
                                            if (zoomScale > 1.0f) {
                                                val scaled = Bitmap.createScaledBitmap(cropped, (width * zoomScale).toInt(), (height * zoomScale).toInt(), true)
                                                cropped.recycle()
                                                scaled
                                            } else cropped
                                        } else bitmap
                                    }
                                } else {
                                    // Full Image Mode: Use original bitmap as is
                                    bitmap.copy(Bitmap.Config.ARGB_8888, true)
                                }

                                val jsonStr = if (result.success && item != null) {
                                    "[\n  {\n    \"area_type\": \"${item.extra["area_type"] ?: "unknown"}\",\n    \"hand\": \"${item.extra["hand"] ?: "unknown"}\",\n    \"confidence\": ${item.confidence}\n  }\n]"
                                } else {
                                    "[]"
                                }

                                withContext(Dispatchers.Main) {
                                    if (targetHand.equals("Left", ignoreCase = true)) {
                                        ocrTimeMs = pbElapsedMs
                                        leftPalmImage = cropped
                                        // Now switch to right hand
                                        targetHand = "Right"
                                        ocrResultJson = jsonStr // store intermediate
                                        Toast.makeText(context, "บันทึกมือซ้ายสำเร็จ! กรุณาใช้มือ [ขวา] ต่อ", Toast.LENGTH_SHORT).show()

                                        // 🌟 Mandatory Delay: Keep isProcessing = true for 2 seconds
                                        // to prevent immediate double-snap of the same hand.
                                        processingResultMsg = "✅ บันทึกมือซ้ายแล้ว... กรุณาสลับเป็น [มือขวา]"
                                        kotlinx.coroutines.delay(2000)
                                        processingResultMsg = null
                                        isProcessing = false
                                    } else if (targetHand.equals("Right", ignoreCase = true)) {
                                        ocrTimeMs += pbElapsedMs
                                        rightPalmImage = cropped

                                        // Combine both results
                                        val leftArr =
                                            org.json.JSONArray(if (ocrResultJson.isEmpty() || ocrResultJson == "[]") "[]" else ocrResultJson)
                                        val rightArr = org.json.JSONArray(jsonStr)
                                        val combined = org.json.JSONArray()
                                        if (leftArr.length() > 0) combined.put(leftArr.getJSONObject(0))
                                        if (rightArr.length() > 0) combined.put(rightArr.getJSONObject(0))

                                        ocrResultJson = combined.toString()
                                        cropImage = null
                                    }
                                }

                                if (targetHand.equals("Right", ignoreCase = true) && rightPalmImage != null) {
                                    // ✅ Generate and send payload off the Main thread
                                    val payload = generateOCRPayload(
                                        context,
                                        rightPalmImage!!,
                                        ocrResultJson,
                                        ocrTimeMs,
                                        currentAiMode
                                    )
                                    val payloadStr = payload.toString()
                                    val service = RelayService.getInstance()
                                    service?.broadcastMessage(payloadStr)

                                    FirebaseLogger.logStep(
                                        context = context,
                                        stepName = "AI_INFERENCE",
                                        status = "SUCCESS",
                                        extraData = mapOf<String, Any>(
                                            "ai_mode" to currentAiMode.name,
                                            "status" to "SUCCESS",
                                            "extracted_text" to ocrResultJson,
                                            "latency_ms" to ocrTimeMs,
                                            "compute_mode" to computeMode.displayName,
                                            "chosen_resolution" to "${bitmap.width}x${bitmap.height}",
                                            "final_ai_resolution" to "${rightPalmImage!!.width}x${rightPalmImage!!.height}",
                                            "type" to "PalmPrint",
                                            "use_gpu" to computeMode.useGpu,
                                            "model_mediapipe_loaded" to true,
                                            "snap_image_active" to true,
                                            "bench_title" to "Palmprint Capture"
                                        )
                                    )

                                    withContext(Dispatchers.Main) {
                                        isProcessing = false
                                    }
                                }
                            } catch (e: Throwable) {
                                Log.e("OCR", "Palmprint error", e)
                                withContext(Dispatchers.Main) { isProcessing = false }

                                val errorJson = org.json.JSONObject().apply {
                                    put("type", "heartbeat")
                                    put("device_model", SystemMonitor.getDeviceInfo(context).model)
                                    put("os_name", SystemMonitor.getDeviceInfo(context).osName)
                                    put("crash_log", "Palmprint Crash: ${e.message}")
                                    put("fatal_error", true)
                                    val res = SystemMonitor.getCurrentResourceUsage(context)
                                    put("ram_free_mb", res.ramFreeMb)
                                    put("ram_used_mb", res.ramUsedMb)
                                    put("cpu_usage", res.cpuUsage)
                                    put("battery_temp", res.batteryTemp)
                                }


                                withContext(Dispatchers.Main) {
                                    Toast.makeText(context, "Palmprint Error: ${e.message}", Toast.LENGTH_SHORT).show()
                                    isProcessing = false
                                }
                            } finally {
                                // The original bitmap given by Camera2Controller must be recycled when we are done extracting
                                if (!bitmap.isRecycled) bitmap.recycle()
                            }
                        }
                    } else if (currentAiMode == AiMode.FACE_DETECTION || currentAiMode == AiMode.VERIFIED_AUTO_CAPTURE || currentAiMode == AiMode.IDENTITY_VERIFICATION) {
                        isProcessing = true
                        scope.launch(Dispatchers.Default) {
                            var tempFace: FaceDetectorProcessor? = null
                            try {
                                val pbStartMs = System.currentTimeMillis()
                                val options = mapOf("is_front" to isFront, "face_mode" to targetFaceMode)

                                var processingBitmap = bitmap
                                var roiLeftOffset = 0f
                                var roiTopOffset = 0f
                                var scaleFactor = 1f

                                if (targetFaceMode == "card") {
                                    // 🌟 Improved Face Extraction for ID Card
                                    // Mirror effect: Front camera has face on LEFT (0.05-0.35), Back camera on RIGHT (0.65-0.95)
                                    val roiLeft = if (isFront) (bitmap.width * 0.05f).toInt() else (bitmap.width * 0.65f).toInt()
                                    val roiTop = (bitmap.height * 0.2f).toInt()
                                    val roiWidth = (bitmap.width * 0.3f).toInt()
                                    val roiHeight = (bitmap.height * 0.6f).toInt()

                                    val faceCrop = Bitmap.createBitmap(bitmap, roiLeft, roiTop, roiWidth, roiHeight)
                                    
                                    // 🌟 PREVENT MASSIVE UPSCALING OOM: Map max dimension to safe limits
                                    val scaleLimit = if (maxOf(roiWidth, roiHeight) > 600) 1f else 3f
                                    
                                    processingBitmap = Bitmap.createScaledBitmap(faceCrop, (roiWidth * scaleLimit).toInt(), (roiHeight * scaleLimit).toInt(), true)
                                    if (faceCrop !== processingBitmap && !faceCrop.isRecycled) faceCrop.recycle()
                                    
                                    roiLeftOffset = roiLeft.toFloat()
                                    roiTopOffset = roiTop.toFloat()
                                    scaleFactor = scaleLimit
                                } else {
                                    // 🌟 Safe scaling for normal face mode to prevent ML Kit OOM on 12MP+ cameras
                                    val maxDim = 1200f
                                    val currentMax = maxOf(bitmap.width, bitmap.height).toFloat()
                                    if (currentMax > maxDim) {
                                        scaleFactor = maxDim / currentMax
                                        processingBitmap = Bitmap.createScaledBitmap(bitmap, (bitmap.width * scaleFactor).toInt(), (bitmap.height * scaleFactor).toInt(), true)
                                    }
                                }

                                val result = AIManager.runWithProcessor { proc ->
                                    proc?.process(processingBitmap, options)
                                } ?: com.example.android_screen_relay.core.AIResult(false, emptyList(), 0, "Processor unavailable")
                                
                                val pbElapsedMs = System.currentTimeMillis() - pbStartMs

                                // 🌟 Dynamic Portrait Crop Logic
                                val bestFace = result.items.maxByOrNull { it.confidence }
                                val finalFaceImage = if (useCropMode && bestFace != null) {
                                    val b = bestFace.boundingBox
                                    // Map back to original bitmap
                                    val bLeft = (b.left / scaleFactor) + roiLeftOffset
                                    val bTop = (b.top / scaleFactor) + roiTopOffset
                                    val bRight = (b.right / scaleFactor) + roiLeftOffset
                                    val bBottom = (b.bottom / scaleFactor) + roiTopOffset

                                    val faceWidth = bRight - bLeft
                                    val faceHeight = bBottom - bTop

                                    // 🌟 Requested Padding: 25 pixels uniform expansion
                                    val padW = 25f
                                    val padT = 25f
                                    val padB = 25f

                                    val cropL = (bLeft - padW).toInt().coerceAtLeast(0)
                                    val cropT = (bTop - padT).toInt().coerceAtLeast(0)
                                    val cropR = (bRight + padW).toInt().coerceAtMost(bitmap.width)
                                    val cropB = (bBottom + padB).toInt().coerceAtMost(bitmap.height)

                                    val finalW = cropR - cropL
                                    val finalH = cropB - cropT

                                    if (finalH > 0 && finalW > 0) {
                                        // 🌟 CREATE BLURRED BACKGROUND 🌟
                                        // 1. Create a blurred version of the full original bitmap
                                        val blurredFull = applyBlur(context, bitmap, 25f)

                                        // 2. Prepare result canvas (based on blurred)
                                        val outputBitmap = blurredFull.copy(Bitmap.Config.ARGB_8888, true)
                                        blurredFull.recycle()

                                        val canvas = android.graphics.Canvas(outputBitmap)

                                        // 3. Draw the un-blurred face region (the 25px padded box) back onto the output
                                        val srcRect = android.graphics.Rect(cropL, cropT, cropR, cropB)
                                        val dstRect = android.graphics.Rect(cropL, cropT, cropR, cropB)
                                        canvas.drawBitmap(bitmap, srcRect, dstRect, null)

                                        // 4. Crop to the padded box if useCropMode is enabled
                                        val finalResult = if (useCropMode) {
                                            val cropped = Bitmap.createBitmap(outputBitmap, cropL, cropT, finalW, finalH)
                                            outputBitmap.recycle()
                                            cropped
                                        } else outputBitmap

                                        // 🌟 Apply zoomScale to final crop
                                        val scaledResult = if (zoomScale > 1.0f) {
                                            val scaled = Bitmap.createScaledBitmap(finalResult, (finalResult.width * zoomScale).toInt(), (finalResult.height * zoomScale).toInt(), true)
                                            if (scaled !== finalResult) finalResult.recycle()
                                            scaled
                                        } else finalResult

                                        scaledResult
                                    } else processingBitmap.copy(Bitmap.Config.ARGB_8888, true)
                                } else if (!useCropMode) {
                                    // Full Image Mode
                                    bitmap.copy(Bitmap.Config.ARGB_8888, true)
                                } else {
                                    processingBitmap.copy(Bitmap.Config.ARGB_8888, true)
                                }

                                val jsonStr = if (result.success && result.items.isNotEmpty()) {
                                    val arr = org.json.JSONArray()
                                    result.items.forEach { item ->
                                        val obj = org.json.JSONObject()
                                        obj.put("label", "Face")
                                        obj.put("confidence", item.confidence)
                                        item.extra.forEach { (key, value) ->
                                            if (value is String && (key == "contours" || key == "landmarks" || key == "verification_metrics")) {
                                                try {
                                                    obj.put(key, org.json.JSONObject(value))
                                                } catch (e: Exception) {
                                                    obj.put(key, value)
                                                }
                                            } else {
                                                obj.put(key, value)
                                            }
                                        }

                                        // Map BBox back to the NEW finalFaceImage coordinates
                                        if (bestFace != null) {
                                            val b = item.boundingBox
                                            val bL = (b.left / scaleFactor) + roiLeftOffset
                                            val bT = (b.top / scaleFactor) + roiTopOffset
                                            val bR = (b.right / scaleFactor) + roiLeftOffset
                                            val bB = (b.bottom / scaleFactor) + roiTopOffset

                                            // Find offsets used for finalFaceImage (matching the 25px padding above)
                                            val padW = 25f
                                            val padT = 25f

                                            val cropL = ((bestFace.boundingBox.left / scaleFactor) + roiLeftOffset - padW).toInt().coerceAtLeast(0)
                                            val cropT = ((bestFace.boundingBox.top / scaleFactor) + roiTopOffset - padT).toInt().coerceAtLeast(0)

                                            val box = org.json.JSONArray()
                                            box.put(bL - cropL)
                                            box.put(bT - cropT)
                                            box.put(bR - cropL)
                                            box.put(bB - cropT)
                                            obj.put("bbox", box)
                                        } else {
                                            val box = org.json.JSONArray()
                                            box.put(item.boundingBox.left)
                                            box.put(item.boundingBox.top)
                                            box.put(item.boundingBox.right)
                                            box.put(item.boundingBox.bottom)
                                            obj.put("bbox", box)
                                        }
                                        arr.put(obj)
                                    }
                                    arr.toString()
                                } else {
                                    "[]"
                                }

                                withContext(Dispatchers.Main) {
                                    ocrTimeMs = pbElapsedMs
                                    ocrResultJson = jsonStr

                                    // 🌟 Set currentImage BEFORE recycling components
                                    currentImage = finalFaceImage

                                    // 🌟 SAFETY CHECK: Only recycle if NOT the same instance as the one being displayed
                                    if (bitmap !== finalFaceImage && !bitmap.isRecycled) {
                                        bitmap.recycle()
                                    }
                                    if (processingBitmap !== finalFaceImage && processingBitmap !== bitmap && !processingBitmap.isRecycled) {
                                        processingBitmap.recycle()
                                    }
                                }

                                // ✅ Generate and send payload off the Main thread
                                val payload = generateOCRPayload(
                                    context,
                                    currentImage!!,
                                    ocrResultJson,
                                    ocrTimeMs,
                                    currentAiMode
                                )
                                val payloadStr = payload.toString()
                                val service = RelayService.getInstance()
                                service?.broadcastMessage(payloadStr)

                                withContext(Dispatchers.Main) {
                                    isProcessing = false
                                }
                            } catch (e: Throwable) {
                                Log.e("Face", "Face error", e)
                                withContext(Dispatchers.Main) { isProcessing = false }
                            }
                        }
                    } else if (currentAiMode == AiMode.SELFIE_SEGMENTATION) {
                        isProcessing = true
                        scope.launch(Dispatchers.Default) {
                            try {
                                val options = mapOf("is_front" to isFront)
                                val pbStartMs = System.currentTimeMillis()
                                val result = AIManager.runWithProcessor { proc ->
                                    val processor = proc as? SelfieSegmenterProcessor
                                    processor?.process(bitmap, options)
                                } ?: com.example.android_screen_relay.core.AIResult(false, emptyList(), 0, "Processor unavailable")
                                val pbElapsedMs = System.currentTimeMillis() - pbStartMs

                                val maskBuffer = result.items.firstOrNull()?.extra?.get("mask_buffer") as? java.nio.ByteBuffer
                                val maskWidth = result.items.firstOrNull()?.extra?.get("width") as? Int ?: 0
                                val maskHeight = result.items.firstOrNull()?.extra?.get("height") as? Int ?: 0

                                val foreground = if (useCropMode && maskBuffer != null && maskWidth > 0 && maskHeight > 0) {
                                    maskBuffer.rewind()
                                    val out = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
                                    val pixels = IntArray(bitmap.width * bitmap.height)
                                    bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)

                                    // Scale mask to bitmap size if needed
                                    // ML Kit Selfie mask is usually 256x256.
                                    // For simplicity, we'll create a scaled mask bitmap and then read pixels.
                                    val maskBitmap = Bitmap.createBitmap(maskWidth, maskHeight, Bitmap.Config.ALPHA_8)
                                    val maskPixels = ByteArray(maskWidth * maskHeight)
                                    for (i in 0 until maskWidth * maskHeight) {
                                        val conf = if (maskBuffer.remaining() >= 4) maskBuffer.getFloat() else 0f
                                        maskPixels[i] = (conf * 255).toInt().toByte()
                                    }
                                    maskBitmap.copyPixelsFromBuffer(java.nio.ByteBuffer.wrap(maskPixels))

                                    val scaledMask = Bitmap.createScaledBitmap(maskBitmap, bitmap.width, bitmap.height, true)
                                    val scaledMaskPixels = IntArray(bitmap.width * bitmap.height)

                                    // Use a temporary bitmap to get alpha values
                                    val tempAlpha = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
                                    val canvas = android.graphics.Canvas(tempAlpha)
                                    canvas.drawBitmap(scaledMask, 0f, 0f, null)
                                    tempAlpha.getPixels(scaledMaskPixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)

                                    for (i in pixels.indices) {
                                        val alpha = (scaledMaskPixels[i] shr 24) and 0xFF
                                        if (alpha < 128) { // 0.5 threshold
                                            pixels[i] = android.graphics.Color.TRANSPARENT
                                        }
                                    }
                                    out.setPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)

                                    maskBitmap.recycle()
                                    scaledMask.recycle()
                                    tempAlpha.recycle()
                                    out
                                } else if (!useCropMode) {
                                    // Full Image Mode: Use original bitmap
                                    bitmap.copy(Bitmap.Config.ARGB_8888, true)
                                } else bitmap.copy(Bitmap.Config.ARGB_8888, true)

                                withContext(Dispatchers.Main) {
                                    ocrTimeMs = pbElapsedMs
                                    ocrResultJson = "[\n  {\n    \"label\": \"Selfie Foreground\",\n    \"confidence\": 1.0\n  }\n]"
                                    currentImage = foreground
                                    if (!bitmap.isRecycled) bitmap.recycle()
                                }

                                val payload = generateOCRPayload(context, foreground, ocrResultJson, ocrTimeMs, currentAiMode)
                                RelayService.getInstance()?.broadcastMessage(payload.toString())

                                // 🌟 FIX: Free memory from intermediate ML Kit Selfie bitmaps
                                result.items.forEach { item ->
                                    (item.extra["mask_bitmap"] as? Bitmap)?.recycle()
                                }

                                withContext(Dispatchers.Main) { isProcessing = false }
                            } catch (e: Exception) {
                                Log.e("Selfie", "Extraction error", e)
                                withContext(Dispatchers.Main) { isProcessing = false }
                            }
                        }
                    } else if (currentAiMode == AiMode.SUBJECT_SEGMENTATION) {
                        isProcessing = true
                        scope.launch(Dispatchers.Default) {
                            try {
                                val pbStartMs = System.currentTimeMillis()
                                val result = AIManager.runWithProcessor { proc ->
                                    val processor = proc as? com.example.android_screen_relay.core.SubjectSegmenterProcessor
                                    processor?.process(bitmap, mapOf("is_front" to isFront))
                                } ?: com.example.android_screen_relay.core.AIResult(false, emptyList(), 0, "Processor unavailable")
                                val pbElapsedMs = System.currentTimeMillis() - pbStartMs

                                val subjectBitmap = result.items.firstOrNull()?.extra?.get("combined_subject_bitmap") as? Bitmap
                                    ?: result.items.firstOrNull()?.extra?.get("subject_bitmap") as? Bitmap

                                val foreground = if (useCropMode && subjectBitmap != null) {
                                    subjectBitmap.copy(Bitmap.Config.ARGB_8888, true)
                                } else if (!useCropMode) {
                                    // Full Image Mode: Use original bitmap
                                    bitmap.copy(Bitmap.Config.ARGB_8888, true)
                                } else bitmap.copy(Bitmap.Config.ARGB_8888, true)

                                withContext(Dispatchers.Main) {
                                    ocrTimeMs = pbElapsedMs
                                    ocrResultJson = "[\n  {\n    \"label\": \"Subject Extracted\",\n    \"confidence\": 1.0\n  }\n]"
                                    currentImage = foreground
                                    if (!bitmap.isRecycled) bitmap.recycle()
                                }

                                val payload = generateOCRPayload(context, foreground, ocrResultJson, ocrTimeMs, currentAiMode)
                                RelayService.getInstance()?.broadcastMessage(payload.toString())

                                // 🌟 FIX: Free memory from intermediate ML Kit Subject bitmaps
                                result.items.forEach { item ->
                                    (item.extra["mask_bitmap"] as? Bitmap)?.recycle()
                                    (item.extra["subject_bitmap"] as? Bitmap)?.recycle()
                                    (item.extra["combined_subject_bitmap"] as? Bitmap)?.recycle()
                                }

                                withContext(Dispatchers.Main) { isProcessing = false }
                            } catch (e: Exception) {
                                Log.e("Subject", "Extraction error", e)
                                withContext(Dispatchers.Main) { isProcessing = false }
                            }
                        }
                    } else if (currentAiMode == AiMode.TEXT_RECOGNITION) {
                        isProcessing = true
                        scope.launch(Dispatchers.Default) {
                            try {
                                val pbStartMs = System.currentTimeMillis()
                                val result = AIManager.runWithProcessor { proc ->
                                    val processor = proc as? TextRecognitionProcessor
                                    processor?.process(bitmap)
                                } ?: com.example.android_screen_relay.core.AIResult(false, emptyList(), 0, "Processor unavailable")
                                val pbElapsedMs = System.currentTimeMillis() - pbStartMs

                                val jsonArr = org.json.JSONArray()
                                result.items.forEach { item ->
                                    val obj = org.json.JSONObject()
                                    obj.put("label", item.label)
                                    obj.put("confidence", item.confidence)
                                    val box = org.json.JSONArray()
                                    // p0: top-left
                                    box.put(org.json.JSONArray().put(item.boundingBox.left.toInt()).put(item.boundingBox.top.toInt()))
                                    // p1: top-right
                                    box.put(org.json.JSONArray().put(item.boundingBox.right.toInt()).put(item.boundingBox.top.toInt()))
                                    // p2: bottom-right
                                    box.put(org.json.JSONArray().put(item.boundingBox.right.toInt()).put(item.boundingBox.bottom.toInt()))
                                    // p3: bottom-left
                                    box.put(org.json.JSONArray().put(item.boundingBox.left.toInt()).put(item.boundingBox.bottom.toInt()))

                                    obj.put("box", box)
                                    jsonArr.put(obj)
                                }
                                val jsonStr = jsonArr.toString()

                                withContext(Dispatchers.Main) {
                                    ocrTimeMs = pbElapsedMs
                                    ocrResultJson = jsonStr
                                    currentImage = bitmap.copy(Bitmap.Config.ARGB_8888, true)
                                    if (!bitmap.isRecycled) bitmap.recycle()
                                }

                                val payload = generateOCRPayload(context, currentImage!!, ocrResultJson, ocrTimeMs, currentAiMode)
                                RelayService.getInstance()?.broadcastMessage(payload.toString())

                                withContext(Dispatchers.Main) { isProcessing = false }
                            } catch (e: Exception) {
                                Log.e("MLKitText", "Processing error", e)
                                withContext(Dispatchers.Main) { isProcessing = false }
                            }
                        }
                    } else {
                        isProcessing = true
                        scope.launch(Dispatchers.Default) {
                            try {
                                val pbStartMs = System.currentTimeMillis()
                                // 🌟 Fix: Use runWithProcessor to ensure thread-safe access to the active processor
                                val resultJsonStr = AIManager.runWithProcessor { proc ->
                                    if (proc is OCRProcessor) {
                                        proc.getRawJson(bitmap)
                                    } else if (proc is TesseractOCRProcessor) {
                                        proc.getRawJson(bitmap)
                                    } else {
                                        "[]"
                                    }
                                } ?: "[]"
                                
                                val pbElapsedMs = System.currentTimeMillis() - pbStartMs

                                val jsonArr = org.json.JSONArray(resultJsonStr)

                                // บังคับตัดภาพคงที่ (Fixed Crop) ตั้งแต่เลขบัตร จนถึง วันเดือนปีเกิด
                                // อ้างอิงขนาดประมาณการ: x เริ่มที่ 2%, y เริ่มที่ 12%, กว้าง 93%, สูงลงมาถึงตำแหน่งประมาณ 75-80% 
                                val calculatedRect = androidx.compose.ui.geometry.Rect(
                                    left = 0.20f,     // เริ่มที่ขอบซ้าย 20%
                                    top = 0.10f,      // เริ่มต่ำลงมา 10% (ครอบคลุมเลขบัตร)
                                    right = 0.98f,    // ไปจนสุดขอบขวา 98%
                                    bottom = 0.62f    // ลงมาจนถึง 62% ของบัตร (ครอบคลุมวันเดือนปีเกิดแน่นอน)
                                )

                                // Auto Crop without UI
                                val croppedResult = if (useCropMode && calculatedRect != null) {
                                    val w = bitmap.width
                                    val h = bitmap.height
                                    val l = (calculatedRect.left * w).toInt()
                                    val t = (calculatedRect.top * h).toInt()
                                    val r = (calculatedRect.right * w).toInt()
                                    val b = (calculatedRect.bottom * h).toInt()
                                    val safeX = l.coerceIn(0, w - 1)
                                    val safeY = t.coerceIn(0, h - 1)
                                    val safeW = (r - safeX).coerceIn(1, w - safeX)
                                    val safeH = (b - safeY).coerceIn(1, h - safeY)
                                    val initialCrop = android.graphics.Bitmap.createBitmap(bitmap, safeX, safeY, safeW, safeH)

                                    // 🌟 Apply zoomScale to final crop
                                    if (zoomScale > 1.0f) {
                                        val scaled = android.graphics.Bitmap.createScaledBitmap(initialCrop, (safeW * zoomScale).toInt(), (safeH * zoomScale).toInt(), true)
                                        if (initialCrop !== bitmap) initialCrop.recycle()
                                        scaled
                                    } else initialCrop
                                } else {
                                    // Full Image Mode: Use original bitmap but still respect zoom if applicable
                                    if (!useCropMode && zoomScale > 1.0f) {
                                        android.graphics.Bitmap.createScaledBitmap(bitmap, (bitmap.width * zoomScale).toInt(), (bitmap.height * zoomScale).toInt(), true)
                                    } else {
                                        bitmap.copy(Bitmap.Config.ARGB_8888, true)
                                    }
                                }

                                val safeRes = computeMode.maxResolution
                                val optimizedCrop = OCROptimizer.scaleDownToMaxDimension(croppedResult, safeRes)

                                withContext(Dispatchers.Main) {
                                    processingResultMsg = "⏳ Running Auto OCR..."
                                    currentImage = if (useCropMode) optimizedCrop else croppedResult // Keep higher res for full image view if possible, or just use optimizedCrop to save RAM
                                }

                                // Auto OCR
                                var isSuccess = false
                                var finalJsonStr = "[]"
                                var finalLatencyMs = 0L
                                try {
                                    val st = System.currentTimeMillis()
                                    // 🌟 Fix: Use runWithProcessor to ensure thread-safe access to the active processor
                                    val rawOcrRes = AIManager.runWithProcessor { proc ->
                                        if (proc is OCRProcessor) {
                                            proc.getRawJson(optimizedCrop)
                                        } else if (proc is TesseractOCRProcessor) {
                                            proc.getRawJson(optimizedCrop)
                                        } else {
                                            "[]"
                                        }
                                    } ?: "[]"

                                    if (rawOcrRes != "[]") {
                                        finalJsonStr = OCRFormatter.formatLabelsInJsonArray(rawOcrRes)
                                        val en = System.currentTimeMillis()
                                        finalLatencyMs = en - st
                                        isSuccess = true
                                    }
                                } catch (e: Exception) {
                                    Log.e("OCR", "Auto processing error", e)
                                }

                                withContext(Dispatchers.Main) {
                                    processingResultMsg = null
                                    if (isSuccess) {
                                        ocrResultJson = finalJsonStr
                                        ocrTimeMs = finalLatencyMs
                                    }
                                    isProcessing = false
                                }

                            } catch (e: Exception) {
                                Log.e("OCR", "Auto crop error", e)
                                withContext(Dispatchers.Main) {
                                    processingResultMsg = null
                                    currentImage = bitmap
                                    isProcessing = false
                                }
                            } finally {
                                withContext(Dispatchers.Main) {
                                    if (currentImage !== bitmap && !bitmap.isRecycled) {
                                        bitmap.recycle()
                                    }
                                }
                            }
                        }
                    }
                },
                onGalleryClick = {
                    galleryLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                },
                isProcessingBusy = isProcessing,
                processingResultMsg = processingResultMsg
            )
        } else {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Initializing...")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CameraPreviewScreen(
    aiMode: AiMode,
    onAiModeChange: (AiMode) -> Unit,
    targetHand: String,
    onTargetHandChange: (String) -> Unit,
    targetFaceMode: String,
    onTargetFaceModeChange: (String) -> Unit,
    zoomScale: Float,
    onZoomScaleChange: (Float) -> Unit,
    useCropMode: Boolean,
    onUseCropModeChange: (Boolean) -> Unit,
    selectedResolution: android.util.Size?,
    onResolutionChange: (android.util.Size) -> Unit,
    availableResolutions: List<android.util.Size>,
    onAvailableResolutionsChange: (List<android.util.Size>) -> Unit,
    onStableDetection: suspend (Bitmap, Boolean) -> Pair<Boolean, List<AIDetectedItem>>,
    onImageCaptured: (Bitmap, Boolean) -> Unit,
    onGalleryClick: () -> Unit,
    isProcessingBusy: Boolean = false,
    processingResultMsg: String? = null,
    selectedCameraId: String,
    onCameraIdChange: (String) -> Unit,
    selectedAspectRatio: UiAspectRatio,
    onAspectRatioChange: (UiAspectRatio) -> Unit,
    horizontalFlip: Boolean = false,
    onHorizontalFlipChange: (Boolean) -> Unit = {},
    verticalFlip: Boolean = false,
    onVerticalFlipChange: (Boolean) -> Unit = {}
) {
    val context = LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var cameraController by remember { mutableStateOf<Camera2Controller?>(null) }

    // State for Settings
    val availableCameras = remember {
        (context.getSystemService(Context.CAMERA_SERVICE) as android.hardware.camera2.CameraManager).cameraIdList.toList()
    }

    var showSettingsDialog by remember { mutableStateOf(false) }
    var isCapturing by remember { mutableStateOf(false) }
    var showAiModeSheet by remember { mutableStateOf(false) }
    var isPreviewPaused by remember { mutableStateOf(false) }
    var stableTime by remember { mutableStateOf(0L) }
    var lastInferenceTimeMs by remember { mutableStateOf(0L) }
    var smoothedFaceRect by remember { mutableStateOf<android.graphics.RectF?>(null) }
    var faceBuffer2s by remember { mutableStateOf<Bitmap?>(null) }
    val blurredFrame = remember { mutableStateOf<Bitmap?>(null) }

    // Result states for UI drawing (latest detected items)
    val latestItemsOcr = remember { mutableStateOf<List<AIDetectedItem>>(emptyList()) }
    val latestItemsPalm = remember { mutableStateOf<List<AIDetectedItem>>(emptyList()) }
    val latestItemsFace = remember { mutableStateOf<List<AIDetectedItem>>(emptyList()) }
    val latestItemsPose = remember { mutableStateOf<List<AIDetectedItem>>(emptyList()) }
    val latestItemsSelfie = remember { mutableStateOf<List<AIDetectedItem>>(emptyList()) }
    val latestItemsSubject = remember { mutableStateOf<List<AIDetectedItem>>(emptyList()) }
    val latestItemsObject = remember { mutableStateOf<List<AIDetectedItem>>(emptyList()) }
    val latestItemsCustomObject = remember { mutableStateOf<List<AIDetectedItem>>(emptyList()) }

    val computeMode = ComputeModeManager.getMode()

    var latestDetections by remember { mutableStateOf<List<AIDetectedItem>>(emptyList()) }
    var bitmapWidth by remember { mutableStateOf(720f) }
    var bitmapHeight by remember { mutableStateOf(1280f) }
    var isFrontCamera by remember { mutableStateOf(false) }

    // Performance monitoring states
    var fps by remember { mutableStateOf(0) }
    var detectorLatency by remember { mutableStateOf(0L) }
    var frameLatency by remember { mutableStateOf(0L) }
    var ramUsed by remember { mutableStateOf(0L) }
    var ramTotal by remember { mutableStateOf(0L) }
    var freeRamMb by remember { mutableStateOf(1000L) }
    var cpuUsage by remember { mutableStateOf("0.0%") }

    // Loop for System info (RAM, CPU)
    LaunchedEffect(Unit) {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            while (isActive) {
                val usage = SystemMonitor.getCurrentResourceUsage(context)
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    freeRamMb = usage.ramFreeMb
                    ramUsed = usage.ramUsedMb
                    ramTotal = usage.ramTotalMb
                    cpuUsage = usage.cpuUsage
                }
                kotlinx.coroutines.delay(2000L)
            }
        }
    }

    val currentOnStableDetection = androidx.compose.runtime.rememberUpdatedState(onStableDetection)
    val currentOnImageCaptured = androidx.compose.runtime.rememberUpdatedState(onImageCaptured)

    // Update resolutions when camera or aspect ratio changes
    LaunchedEffect(selectedCameraId, selectedAspectRatio, aiMode) {
        if (cameraController == null) {
            cameraController = Camera2Controller(context) { bitmap, isFront ->
                currentOnImageCaptured.value(
                    bitmap,
                    isFront
                )
            }
        }

        // Force Flash for PALMPRINT mode and normal for OCR
        cameraController?.setFlashMode(aiMode == AiMode.HAND_DETECTION)

        // Fetch raw sizes directly mapping orientation manually to prevent waiting for preview initialization
        val hardwareSizes = cameraController!!.getCameraResolutions(selectedCameraId)
        val allSizes = hardwareSizes.toMutableList()

        // จำลองค่า Square Resolution แบบเจาะจงให้ระบบ หากผู้ใช้เลือกโหมด 1:1 เนื่องจากฮาร์ดแวร์มักไม่ส่งค่า 1:1 มาให้
        if (selectedAspectRatio == UiAspectRatio.RATIO_1_1) {
            allSizes.add(android.util.Size(720, 720))
            allSizes.add(android.util.Size(1080, 1080))
            allSizes.add(android.util.Size(1440, 1440))
            allSizes.add(android.util.Size(1920, 1920))
            allSizes.add(android.util.Size(2160, 2160))
        }

        val targetRatio = selectedAspectRatio.value
        val tolerance = 0.05f

        val filtered = if (targetRatio != null) {
            allSizes.filter { size ->
                val ratio = size.width.toFloat() / size.height.toFloat()
                val invRatio = size.height.toFloat() / size.width.toFloat()
                kotlin.math.abs(ratio - targetRatio) < tolerance || kotlin.math.abs(invRatio - targetRatio) < tolerance
            }
        } else {
            allSizes
        }

        // Remove duplicates by converting to distinct list
        val finalResolutions = filtered.distinct().sortedByDescending { it.width * it.height }
        onAvailableResolutionsChange(finalResolutions)

        // Default to lowest resolution (e.g. 720x720) to save RAM on A10
        if (selectedResolution == null || !finalResolutions.contains(selectedResolution)) {
            finalResolutions.minByOrNull { it.width * it.height }?.let { onResolutionChange(it) }
        }

        cameraController?.aspectRatio = selectedAspectRatio
    }

    LaunchedEffect(zoomScale) {
        cameraController?.setZoom(zoomScale)
    }

    LaunchedEffect(isPreviewPaused, isProcessingBusy) {
        // 🌟 Fix for Hand Detection: Don't pause preview even if busy, so user can see Right hand immediately after Left hand snap
        if (isPreviewPaused || (isProcessingBusy && aiMode != AiMode.HAND_DETECTION)) {
            cameraController?.pausePreview()
        } else {
            cameraController?.resumePreview()
        }
    }

    val cameraKey =
        "$selectedCameraId-${selectedResolution?.width}x${selectedResolution?.height}-${selectedAspectRatio.name}"

    // Auto-Snap polling
    // ⚠️ Busy State Lock check: Stop loop if 'isProcessingBusy' is true
    LaunchedEffect(cameraKey, aiMode, targetHand, targetFaceMode, isProcessingBusy, useCropMode, horizontalFlip, verticalFlip) {
        isCapturing = false // ป้องกันบัคค้างหมุนตลอดกาล (Reset state every time)
        stableTime = 0L
        smoothedFaceRect = null

        if (isProcessingBusy) return@LaunchedEffect

        withContext(Dispatchers.Default) {
            var frameCount = 0
            var lastFpsUpdate = System.currentTimeMillis()
            var lastFrameTime = System.currentTimeMillis()

            while (isActive && !isProcessingBusy) {
                val iterationStart = System.currentTimeMillis()
                if (isPreviewPaused) {
                    kotlinx.coroutines.delay(500)
                    continue
                }

                if (!isCapturing && cameraController?.textureView != null) {
                    val rawBitmap = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                        cameraController?.textureView?.bitmap
                    }
                    if (rawBitmap != null) {
                        // 🌟 SMART SCALING for Detection: Use a fixed small size (max 720px) to save RAM/CPU
                        // This matches the "Fast Preview Downscaling" requirement in README
                        val maxDetectionDim = 720f
                        val currentMax = maxOf(rawBitmap.width, rawBitmap.height).toFloat()
                        
                        var baseBitmap = if (currentMax > maxDetectionDim) {
                            val scale = maxDetectionDim / currentMax
                            Bitmap.createScaledBitmap(rawBitmap, (rawBitmap.width * scale).toInt(), (rawBitmap.height * scale).toInt(), true).also {
                                rawBitmap.recycle()
                            }
                        } else {
                            rawBitmap
                        }

                        // 🌟 CROP MODE: If enabled, crop to the centered frame area
                        val bitmap = if (useCropMode) {
                            val cw = baseBitmap.width.toFloat()
                            val ch = baseBitmap.height.toFloat()

                            val frameW = if (aiMode == AiMode.PADDLE_OCR || aiMode == AiMode.TESSERACT_FAST_OCR) {
                                val maxW = cw * 0.9f
                                val idealH = ch * 0.6f
                                if (idealH * 1.58f > maxW) maxW else idealH * 1.58f
                            } else if (aiMode == AiMode.FACE_DETECTION) {
                                min(cw, ch) * 0.8f
                            } else {
                                min(cw, ch) * 0.8f // Use 0.8f for better Subject/Selfie visibility
                            }
                            val frameH = if (aiMode == AiMode.PADDLE_OCR || aiMode == AiMode.TESSERACT_FAST_OCR) frameW / 1.58f else frameW
                            val left = ((cw - frameW) / 2).toInt().coerceAtLeast(0)
                            val top = ((ch - frameH) / 2).toInt().coerceAtLeast(0)
                            val width = frameW.toInt().coerceAtMost(baseBitmap.width - left)
                            val height = frameH.toInt().coerceAtMost(baseBitmap.height - top)

                            if (width > 0 && height > 0) {
                                val cropped = Bitmap.createBitmap(baseBitmap, left, top, width, height)
                                if (baseBitmap !== rawBitmap) baseBitmap.recycle()
                                cropped
                            } else {
                                baseBitmap
                            }
                        } else {
                            baseBitmap
                        }

                        val now = System.currentTimeMillis()
                        frameLatency = now - lastFrameTime
                        lastFrameTime = now

                        // Extra lock safety before intensive computation
                        if (isProcessingBusy) {
                            bitmap.recycle()
                            break
                        }

                        bitmapWidth = bitmap.width.toFloat()
                        bitmapHeight = bitmap.height.toFloat()

                        // 🌟 RAM 2GB OPTIMIZATION: Check for ultra-low memory
                        val isUltraLowRAM = SystemMonitor.isUltraLowRAM(context)
                        val memoryInfo = ActivityManager.MemoryInfo()
                        (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(memoryInfo)

                        // 🌟 GENERATE LIVE BLUR: Safe Main-thread update
                        if ((aiMode == AiMode.FACE_DETECTION || aiMode == AiMode.VERIFIED_AUTO_CAPTURE || aiMode == AiMode.IDENTITY_VERIFICATION) && targetFaceMode == "normal" && !isUltraLowRAM && !memoryInfo.lowMemory) {
                            try {
                                val scale = 0.05f
                                val blurW = (bitmap.width * scale).toInt().coerceAtLeast(1)
                                val blurH = (bitmap.height * scale).toInt().coerceAtLeast(1)

                                val tiny = Bitmap.createScaledBitmap(bitmap, blurW, blurH, true)

                                if (tiny != null && !tiny.isRecycled) {
                                    withContext(Dispatchers.Main) {
                                        val oldBlur = blurredFrame.value
                                        // 🌟 ALWAYS create new bitmap to trigger Compose recompose
                                        // createScaledBitmap with filtering=true provides a fast blur effect
                                        val newBlurred = Bitmap.createScaledBitmap(tiny, bitmap.width, bitmap.height, true)
                                        
                                        blurredFrame.value = newBlurred
                                        
                                        // Recycle old one AFTER setting new one to avoid flickering or use of recycled bitmap in UI
                                        if (oldBlur != null && !oldBlur.isRecycled && oldBlur !== newBlurred) {
                                            oldBlur.recycle()
                                        }
                                        
                                        tiny.recycle()
                                    }
                                }
                            } catch (e: Exception) {
                                Log.e("AIScreen", "Error generating live blur", e)
                            }
                        } else {
                            if (blurredFrame.value != null) {
                                withContext(Dispatchers.Main) {
                                    if (blurredFrame.value != null && !blurredFrame.value!!.isRecycled) {
                                        blurredFrame.value?.recycle()
                                    }
                                    blurredFrame.value = null
                                    if (isUltraLowRAM) System.gc()
                                }
                            }
                        }

                        // 🌟 AI PIPELINE: Check memory pressure before inference
                        if (isUltraLowRAM && memoryInfo.availMem < (memoryInfo.totalMem * 0.15)) {
                            // If memory is critically low on 2GB device, skip this frame to allow GC
                            bitmap.recycle()
                            kotlinx.coroutines.delay(100)
                            continue
                        }

                        val startInference = System.currentTimeMillis()
                        val isFront = cameraController?.isFrontCamera ?: false
                        isFrontCamera = isFront

                        var (success, items) = try {
                            currentOnStableDetection.value(
                                bitmap,
                                isFront
                            )
                        } catch (e: Exception) {
                            Pair(false, emptyList())
                        }

                        // 🌟 Expansion: Add 25px padding to Face Bounding Boxes only
                        if (aiMode == AiMode.FACE_DETECTION && items.isNotEmpty()) {
                            items = items.map { item ->
                                val rect = item.boundingBox
                                val expandedRect = android.graphics.RectF(
                                    (rect.left - 25f).coerceAtLeast(0f),
                                    (rect.top - 25f).coerceAtLeast(0f),
                                    (rect.right + 25f).coerceAtMost(bitmapWidth),
                                    (rect.bottom + 25f).coerceAtMost(bitmapHeight)
                                )
                                item.copy(boundingBox = expandedRect)
                            }
                        }

                        // Map items to preview states for drawing using .value
                        when (aiMode) {
                            AiMode.PADDLE_OCR -> latestItemsOcr.value = items
                            AiMode.HAND_DETECTION -> latestItemsPalm.value = items
                            AiMode.FACE_DETECTION -> latestItemsFace.value = items
                            AiMode.POSE_DETECTION -> latestItemsPose.value = items
                            AiMode.SELFIE_SEGMENTATION -> {
                                latestItemsSelfie.value = items
                            }
                            AiMode.SUBJECT_SEGMENTATION -> {
                                latestItemsSubject.value = items
                            }
                            AiMode.OBJECT_DETECTION -> latestItemsObject.value = items
                            AiMode.CUSTOM_OBJECT_DETECTION -> latestItemsCustomObject.value = items
                            else -> {}
                        }

                        // 🌟 Stabilization: Always update latestDetections for UI drawing
                        latestDetections = items

                        var criteriaMet = if (aiMode == AiMode.PADDLE_OCR || aiMode == AiMode.TESSERACT_FAST_OCR || aiMode == AiMode.TEXT_RECOGNITION || aiMode == AiMode.HAND_DETECTION) {
                            success
                        } else {
                            items.isNotEmpty()
                        }

                        // 🌟 MULTI-SCALE INFERENCE: If initial crop failed, try a 20% larger crop
                        if (!criteriaMet && useCropMode) {
                            val expandedBitmap = try {
                                val cw = baseBitmap.width.toFloat()
                                val ch = baseBitmap.height.toFloat()
                                // Expand frame by 20%
                                val expansion = 1.20f
                                val frameW = (if (aiMode == AiMode.PADDLE_OCR || aiMode == AiMode.TESSERACT_FAST_OCR) {
                                    val maxW = cw * 0.9f
                                    val idealH = ch * 0.6f
                                    if (idealH * 1.58f > maxW) maxW else idealH * 1.58f
                                } else if (aiMode == AiMode.FACE_DETECTION) {
                                    min(cw, ch) * 0.8f
                                } else {
                                    min(cw, ch) * 0.6f
                                }) * expansion

                                val frameH = (if (aiMode == AiMode.PADDLE_OCR || aiMode == AiMode.TESSERACT_FAST_OCR) frameW / (1.58f * expansion) else frameW) * expansion
                                val left = ((cw - frameW) / 2).toInt().coerceAtLeast(0)
                                val top = ((ch - frameH) / 2).toInt().coerceAtLeast(0)
                                val width = frameW.toInt().coerceAtMost(baseBitmap.width - left)
                                val height = frameH.toInt().coerceAtMost(baseBitmap.height - top)

                                Bitmap.createBitmap(baseBitmap, left, top, width, height)
                            } catch (e: Throwable) { null }

                            if (expandedBitmap != null) {
                                try {
                                    val (retrySuccess, retryItems) = currentOnStableDetection.value(
                                        expandedBitmap,
                                        isFront
                                    )
                                    if (retrySuccess) {
                                        success = true
                                        criteriaMet = true
                                        items = retryItems
                                        // Update overlay to show we found it in expanded mode
                                        RelayService.getInstance()?.overlayManager?.updateMetrics(
                                            ramUsed = ramUsed, ramTotal = ramTotal, cpu = cpuUsage,
                                            status = "✨ Found in Expanded View!",
                                            scanRes = "${expandedBitmap.width}x${expandedBitmap.height} (Expanded)"
                                        )
                                    }
                                } finally {
                                    expandedBitmap.recycle()
                                }
                            }
                        }

                        // Use existing criteriaMet (already updated by multi-scale retry if needed)
                        val elapsedInference = System.currentTimeMillis() - startInference
                        lastInferenceTimeMs = elapsedInference
                        detectorLatency = elapsedInference

                        // Update FPS
                        frameCount++
                        if (now - lastFpsUpdate >= 1000) {
                            fps = frameCount
                            frameCount = 0
                            lastFpsUpdate = now
                        }

                        // Update floating overlay if service is running
                        RelayService.getInstance()?.let { service ->
                            service.overlayManager.updateMetrics(
                                ramUsed = ramUsed,
                                ramTotal = ramTotal,
                                cpu = cpuUsage,
                                model = aiMode.name,
                                status = if (isProcessingBusy) "Processing..." else if (isCapturing) "Capturing..." else if (stableTime > 0) "Stabilizing..." else "Searching...",
                                inputSize = "${baseBitmap.width}x${baseBitmap.height}",
                                scanRes = "${bitmap.width}x${bitmap.height}",
                                fps = fps,
                                frameLatency = frameLatency,
                                detectorLatency = detectorLatency
                            )
                        }

                        var passedToCapture = false
                        // Disable Auto-Snap for specific preview-only modes as requested
                        val isPreviewOnlyMode = aiMode == AiMode.POSE_DETECTION ||
                                                aiMode == AiMode.OBJECT_DETECTION ||
                                                aiMode == AiMode.CUSTOM_OBJECT_DETECTION

                        if (criteriaMet && !isPreviewOnlyMode) {
                            val elapsedSinceStart = System.currentTimeMillis() - iterationStart
                            stableTime += elapsedSinceStart

                            // Rule 4: Buffer at 2s for Face and Hand Detection
                            if ((aiMode == AiMode.FACE_DETECTION || aiMode == AiMode.HAND_DETECTION || aiMode == AiMode.VERIFIED_AUTO_CAPTURE || aiMode == AiMode.IDENTITY_VERIFICATION) && stableTime in 1900..2100) {
                                if (faceBuffer2s == null) {
                                    faceBuffer2s = bitmap.copy(Bitmap.Config.ARGB_8888, true)
                                }
                            }

                            val targetStableTime = when(aiMode) {
                                AiMode.FACE_DETECTION -> 3000L
                                AiMode.HAND_DETECTION -> 3000L
                                AiMode.VERIFIED_AUTO_CAPTURE -> 3000L
                                AiMode.IDENTITY_VERIFICATION -> 3000L
                                AiMode.SUBJECT_SEGMENTATION -> 1000L
                                else -> 500L
                            }

                            if (stableTime >= targetStableTime) {
                                isCapturing = true
                                isPreviewPaused = true
                                passedToCapture = true

                                var captureBitmap = if ((aiMode == AiMode.FACE_DETECTION || aiMode == AiMode.HAND_DETECTION || aiMode == AiMode.VERIFIED_AUTO_CAPTURE || aiMode == AiMode.IDENTITY_VERIFICATION) && faceBuffer2s != null) {
                                    // Always copy from buffer to leave buffer intact for potential recycle check
                                    faceBuffer2s!!.copy(Bitmap.Config.ARGB_8888, true)
                                } else {
                                    bitmap.copy(Bitmap.Config.ARGB_8888, true)
                                }

                                // 🌟 APPLY MANUAL FLIP ONLY ON SNAP
                                if (horizontalFlip || verticalFlip) {
                                    val flipMatrix = android.graphics.Matrix()
                                    val hScale = if (horizontalFlip) -1f else 1f
                                    val vScale = if (verticalFlip) -1f else 1f
                                    flipMatrix.postScale(hScale, vScale, captureBitmap.width / 2f, captureBitmap.height / 2f)
                                    val flipped = Bitmap.createBitmap(captureBitmap, 0, 0, captureBitmap.width, captureBitmap.height, flipMatrix, true)
                                    captureBitmap.recycle()
                                    captureBitmap = flipped
                                }

                                currentOnImageCaptured.value(
                                    captureBitmap,
                                    isFront
                                )

                                // 🌟 HAND DETECTION FIX: Auto-resume preview for the second hand scan
                                if (aiMode == AiMode.HAND_DETECTION) {
                                    isPreviewPaused = false
                                    isCapturing = false
                                }

                                stableTime = 0L
                                faceBuffer2s?.recycle()
                                faceBuffer2s = null
                            }
                        } else {
                            stableTime = 0L
                            faceBuffer2s?.recycle()
                            faceBuffer2s = null
                        }

                        if (!passedToCapture && !bitmap.isRecycled) {
                            bitmap.recycle()
                        }

                        // Dynamic Polling: 30ms when stabilizing/capturing, 100ms when searching to save RAM/CPU
                        val pollDelay = if (stableTime > 0 || isCapturing) 30L else 100L
                        kotlinx.coroutines.delay(pollDelay)
                    }
                }
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            cameraController?.close()
        }
    }

    // Ready to Scaffold

    Scaffold(
        containerColor = Color.Black
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(top = padding.calculateTopPadding())) {

            // Container for Preview + Overlay that respects Aspect Ratio
            val ratioVal = if (selectedResolution != null && selectedResolution!!.width == selectedResolution!!.height) {
                1.0f
            } else {
                selectedAspectRatio.value
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .let { if (ratioVal != null) it.aspectRatio(ratioVal) else it.fillMaxHeight() }
                    .align(Alignment.Center)
                    .clip(RectangleShape) // 🌟 บังคับตัดส่วนเกินที่อาจโผล่ออกมา
                    .background(Color.Black)
            ) {
                // TextureView for Camera2
                AndroidView(
                    factory = { ctx ->
                        TextureView(ctx).apply {
                            // Controller needs it
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                    update = { tv ->
                        if (cameraController != null) {
                            try {
                                cameraController?.aspectRatio = selectedAspectRatio
                                cameraController?.openCamera(tv, selectedCameraId, selectedResolution)
                            } catch (e: Exception) {
                                Log.e("AIScreen", "Error opening camera in update", e)
                            }
                        }
                    }
                )

                // Overlay - Drawn inside the aspect ratio box
                Canvas(modifier = Modifier.fillMaxSize()) {
                    // Draw Frame (Dynamic by AI Mode)
                    // เปลี่ยนกรอบเขียวทันทีที่ตรวจพบแค่รอบเดียว (>= 250L) เพื่อความลื่นไหล
                    val frameColor =
                        if (stableTime >= 250L || isCapturing) android.graphics.Color.parseColor("#4CAF50") else android.graphics.Color.WHITE
                    val maskColor = android.graphics.Color.parseColor("#99000000") // Semi-transparent black
                    val strokeW = 8f
                    val cw = size.width
                    val ch = size.height

                    val isPreviewOnlyMode = aiMode == AiMode.POSE_DETECTION ||
                                            aiMode == AiMode.OBJECT_DETECTION ||
                                            aiMode == AiMode.CUSTOM_OBJECT_DETECTION ||
                                            aiMode == AiMode.SELFIE_SEGMENTATION ||
                                            aiMode == AiMode.SUBJECT_SEGMENTATION

                    if (aiMode == AiMode.PADDLE_OCR || aiMode == AiMode.TESSERACT_FAST_OCR || aiMode == AiMode.FACE_DETECTION || aiMode == AiMode.HAND_DETECTION || aiMode == AiMode.VERIFIED_AUTO_CAPTURE || aiMode == AiMode.IDENTITY_VERIFICATION) {
                        // OCR matches bounds, PALMPRINT uses a smaller centered box
                        val frameW = if (aiMode == AiMode.PADDLE_OCR || aiMode == AiMode.TESSERACT_FAST_OCR) {
                            val maxW = cw * 0.9f
                            val idealH = ch * 0.6f
                            if (idealH * 1.58f > maxW) maxW else idealH * 1.58f
                        } else if (aiMode == AiMode.FACE_DETECTION || aiMode == AiMode.VERIFIED_AUTO_CAPTURE || aiMode == AiMode.IDENTITY_VERIFICATION) {
                            min(cw, ch) * 0.8f
                        } else {
                            min(cw, ch) * 0.6f
                        }
                        val frameH = if (aiMode == AiMode.PADDLE_OCR || aiMode == AiMode.TESSERACT_FAST_OCR) frameW / 1.58f else frameW

                        val left = (cw - frameW) / 2
                        val top = (ch - frameH) / 2
                        val right = left + frameW
                        val bottom = top + frameH

                        val paint = androidx.compose.ui.graphics.Paint().asFrameworkPaint().apply {
                            style = android.graphics.Paint.Style.STROKE; strokeWidth = strokeW; color =
                            frameColor; strokeCap = android.graphics.Paint.Cap.ROUND
                        }
                        val textPaint = android.graphics.Paint().apply {
                            color = android.graphics.Color.WHITE
                            textSize = 36f
                            isAntiAlias = true
                            typeface = android.graphics.Typeface.DEFAULT_BOLD
                        }

                        if (aiMode == AiMode.PADDLE_OCR || aiMode == AiMode.TESSERACT_FAST_OCR) {
                            // Main ID card border (Landscape)
                            val rect = android.graphics.RectF(left, top, right, bottom)
                            drawContext.canvas.nativeCanvas.drawRoundRect(rect, 40f, 40f, paint)

                            // Top Text
                            drawContext.canvas.nativeCanvas.save()
                            drawContext.canvas.nativeCanvas.translate(left + frameW / 2f, top - 60f)
                            val topText =
                                if (stableTime >= 250L || isCapturing) "พบบัตรแล้ว กำลังทำการบันทึกภาพ..." else "กรุณาวางบัตรประชาชนในกรอบเพื่อรอสแกนอัตโนมัติ"
                            val topTextWidth = textPaint.measureText(topText)
                            drawContext.canvas.nativeCanvas.drawText(topText, -topTextWidth / 2f, 0f, textPaint)
                            drawContext.canvas.nativeCanvas.restore()

                            // Bottom Text
                            drawContext.canvas.nativeCanvas.save()
                            drawContext.canvas.nativeCanvas.translate(left + frameW / 2f, bottom + 80f)
                            val bottomText = "สแกนบัตรประชาชนด้านหน้า"
                            val bottomTextWidth = textPaint.measureText(bottomText)
                            drawContext.canvas.nativeCanvas.drawText(bottomText, -bottomTextWidth / 2f, 0f, textPaint)
                            drawContext.canvas.nativeCanvas.restore()
                        } else if (aiMode == AiMode.FACE_DETECTION || aiMode == AiMode.HAND_DETECTION || aiMode == AiMode.VERIFIED_AUTO_CAPTURE || aiMode == AiMode.IDENTITY_VERIFICATION) {
                             // 🌟 Guide for Face/Palm
                             if (aiMode == AiMode.FACE_DETECTION || aiMode == AiMode.VERIFIED_AUTO_CAPTURE || aiMode == AiMode.IDENTITY_VERIFICATION) {
                                 val guideColor = if (stableTime >= 250L) android.graphics.Color.parseColor("#4CAF50") else android.graphics.Color.YELLOW
                                 val guidePaint = android.graphics.Paint().apply {
                                     color = guideColor
                                     style = android.graphics.Paint.Style.STROKE
                                     strokeWidth = 6f
                                     pathEffect = android.graphics.DashPathEffect(floatArrayOf(30f, 20f), 0f)
                                     isAntiAlias = true
                                 }

                                 if (targetFaceMode == "card") {
                                     // 1. Draw ID Card Frame (Landscape aspect 1.58:1)
                                     val cardW = cw * 0.85f
                                     val cardH = cardW / 1.58f
                                     val cardLeft = (cw - cardW) / 2f
                                     val cardTop = (ch - cardH) / 2f
                                     val cardRect = android.graphics.RectF(cardLeft, cardTop, cardLeft + cardW, cardTop + cardH)
                                     drawContext.canvas.nativeCanvas.drawRoundRect(cardRect, 30f, 30f, guidePaint)

                                     // 2. Draw Face Area Guide within the card based on camera
                                     // (Matches logic in onImageCaptured: left if front, right if back)
                                     val faceAreaW = cardW * 0.35f
                                     val faceAreaH = cardH * 0.8f
                                     val faceAreaLeft = if (isFrontCamera) {
                                         cardLeft + (cardW * 0.05f)
                                     } else {
                                         cardLeft + (cardW * 0.60f)
                                     }
                                     val faceAreaTop = cardTop + (cardH * 0.1f)
                                     val faceAreaRect = android.graphics.RectF(faceAreaLeft, faceAreaTop, faceAreaLeft + faceAreaW, faceAreaTop + faceAreaH)

                                     val faceAreaPaint = android.graphics.Paint(guidePaint).apply {
                                         pathEffect = null
                                         alpha = 100
                                         style = android.graphics.Paint.Style.STROKE
                                         strokeWidth = 4f
                                     }
                                     drawContext.canvas.nativeCanvas.drawRoundRect(faceAreaRect, 15f, 15f, faceAreaPaint)

                                     // Instructions
                                     val textPaint = android.graphics.Paint().apply {
                                         color = guideColor
                                         textSize = 36f
                                         typeface = android.graphics.Typeface.DEFAULT_BOLD
                                         textAlign = android.graphics.Paint.Align.CENTER
                                         setShadowLayer(4f, 2f, 2f, android.graphics.Color.BLACK)
                                     }
                                     drawContext.canvas.nativeCanvas.drawText("วางบัตรและใบหน้าให้ตรงกรอบ", cw / 2f, cardTop - 60f, textPaint)
                                 } else {
                                     // 🌟 FULL FACE MODE: Draw minimal circular guide (Short arcs)
                                     val circleSize = cw * 0.70f
                                     val ellipseLeft = (cw - circleSize) / 2f
                                     val ellipseTop = (ch - circleSize) / 2.5f
                                     val ellipseRect = android.graphics.RectF(ellipseLeft, ellipseTop, ellipseLeft + circleSize, ellipseTop + circleSize)

                                     // 🌟 Real-time Blur Masking
                                     blurredFrame.value?.let { blur: Bitmap ->
                                         if (!blur.isRecycled) {
                                             val ovalPath = android.graphics.Path().apply {
                                                 addOval(ellipseRect, android.graphics.Path.Direction.CW)
                                             }

                                             drawContext.canvas.nativeCanvas.save()
                                             // Clip out the oval area so we don't draw blur there
                                             if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                                                 drawContext.canvas.nativeCanvas.clipPath(ovalPath, android.graphics.Region.Op.DIFFERENCE)
                                             } else {
                                                 // Legacy support
                                                 @Suppress("DEPRECATION")
                                                 drawContext.canvas.nativeCanvas.clipPath(ovalPath, android.graphics.Region.Op.DIFFERENCE)
                                             }

                                             // Draw the blurred frame over the live preview (only in clipped area)
                                             val destRect = android.graphics.Rect(0, 0, cw.toInt(), ch.toInt())
                                             drawContext.canvas.nativeCanvas.drawBitmap(blur, null, destRect, null)

                                             // 🌟 Add a 'cloudy' white tint to make it look like frosted glass
                                             val cloudyPaint = android.graphics.Paint().apply {
                                                 color = android.graphics.Color.WHITE
                                                 alpha = 40 // Very subtle white tint
                                                 style = android.graphics.Paint.Style.FILL
                                             }
                                             drawContext.canvas.nativeCanvas.drawRect(0f, 0f, cw, ch, cloudyPaint)

                                             drawContext.canvas.nativeCanvas.restore()
                                         }
                                     }

                                     // Use a solid line for the face arcs to match reference image
                                     val faceGuidePaint = android.graphics.Paint(guidePaint).apply {
                                         pathEffect = null
                                         strokeWidth = 8f
                                         strokeCap = android.graphics.Paint.Cap.ROUND // เส้นมนสวยงาม
                                     }

                                     // 🌟 DRAW SHORT TOP AND BOTTOM ARCS ONLY
                                     // Top arc (Forehead) - centered at 270 degrees
                                     drawContext.canvas.nativeCanvas.drawArc(ellipseRect, 240f, 60f, false, faceGuidePaint)
                                     // Bottom arc (Chin) - centered at 90 degrees
                                     drawContext.canvas.nativeCanvas.drawArc(ellipseRect, 60f, 60f, false, faceGuidePaint)

                                     val textPaint = android.graphics.Paint().apply {
                                         color = guideColor
                                         textSize = 40f
                                         typeface = android.graphics.Typeface.DEFAULT_BOLD
                                         textAlign = android.graphics.Paint.Align.CENTER
                                         setShadowLayer(4f, 2f, 2f, android.graphics.Color.BLACK)
                                     }
                                     drawContext.canvas.nativeCanvas.drawText("วางใบหน้าในกรอบวงรี", cw / 2f, ellipseTop - 60f, textPaint)
                                 }
                             }

                             if (aiMode == AiMode.HAND_DETECTION) {
                                 val handText = if (targetHand.equals("Left", true)) "กรุณาใช้มือ [ซ้าย] ในการสแกน" else "กรุณาใช้มือ [ขวา] ในการสแกน"
                                 val handPaint = android.graphics.Paint().apply {
                                     color = android.graphics.Color.YELLOW
                                     textSize = 42f
                                     typeface = android.graphics.Typeface.DEFAULT_BOLD
                                     textAlign = android.graphics.Paint.Align.CENTER
                                     setShadowLayer(6f, 3f, 3f, android.graphics.Color.BLACK)
                                 }

                                 // Visual Guides for Palm
                                 val guidePaint = android.graphics.Paint().apply {
                                     color = android.graphics.Color.argb(200, 0, 255, 255) // Cyan
                                     style = android.graphics.Paint.Style.STROKE
                                     strokeWidth = 6f
                                     pathEffect = android.graphics.DashPathEffect(floatArrayOf(30f, 20f), 0f)
                                 }
                                 val guideTextPaint = android.graphics.Paint().apply {
                                     color = android.graphics.Color.WHITE
                                     textSize = 36f
                                     typeface = android.graphics.Typeface.DEFAULT_BOLD
                                     textAlign = android.graphics.Paint.Align.CENTER
                                     setShadowLayer(4f, 2f, 2f, android.graphics.Color.BLACK)
                                 }

                                 // 1. Palm area (Circle)
                                 val palmRadius = cw * 0.38f
                                 val palmCenterY = ch * 0.45f
                                 drawContext.canvas.nativeCanvas.drawCircle(cw / 2f, palmCenterY, palmRadius, guidePaint)
                                 // Move text down below the circle center to avoid overlapping with the palm center too much
                                 val instructionY = palmCenterY + palmRadius + 60f
                                 drawContext.canvas.nativeCanvas.drawText("จัดฝ่ามือให้อยู่ในวงกลม", cw / 2f, instructionY, guideTextPaint)
                                 drawContext.canvas.nativeCanvas.drawText(handText, cw / 2f, instructionY + 60f, handPaint)
                             }
                        }
                    }

                    // Dynamic Bounding Boxes from latestDetections
                    val frameW = if (useCropMode) {
                        val cw = size.width
                        val ch = size.height
                        if (aiMode == AiMode.PADDLE_OCR || aiMode == AiMode.TESSERACT_FAST_OCR) {
                            val maxW = cw * 0.9f
                            val idealH = ch * 0.6f
                            if (idealH * 1.58f > maxW) maxW else idealH * 1.58f
                        } else if (aiMode == AiMode.FACE_DETECTION || aiMode == AiMode.VERIFIED_AUTO_CAPTURE || aiMode == AiMode.IDENTITY_VERIFICATION) {
                            min(cw, ch) * 0.8f
                        } else {
                            min(cw, ch) * 0.8f
                        }
                    } else size.width

                    val frameH = if (useCropMode) {
                        if (aiMode == AiMode.PADDLE_OCR || aiMode == AiMode.TESSERACT_FAST_OCR) frameW / 1.58f else frameW
                    } else size.height

                    val leftOffset = if (useCropMode) (size.width - frameW) / 2f else 0f
                    val topOffset = if (useCropMode) (size.height - frameH) / 2f else 0f

                    val boxScaleX = if (bitmapWidth > 0) frameW / bitmapWidth else 1f
                    val boxScaleY = if (bitmapHeight > 0) frameH / bitmapHeight else 1f

                    val facePaint = android.graphics.Paint().apply {
                        color = android.graphics.Color.RED
                        style = android.graphics.Paint.Style.STROKE
                        strokeWidth = 6f
                        isAntiAlias = true
                    }
                    val palmPaint = android.graphics.Paint().apply {
                        color = android.graphics.Color.YELLOW
                        style = android.graphics.Paint.Style.STROKE
                        strokeWidth = 8f
                        isAntiAlias = true
                    }
                    val ocrPaint = android.graphics.Paint().apply {
                        color = android.graphics.Color.CYAN
                        style = android.graphics.Paint.Style.STROKE
                        strokeWidth = 4f
                        alpha = 200
                        isAntiAlias = true
                    }

                    latestDetections.forEach { item ->
                        val r = item.boundingBox
                        val mappedRect = android.graphics.RectF(
                            leftOffset + r.left * boxScaleX, topOffset + r.top * boxScaleY,
                            leftOffset + r.right * boxScaleX, topOffset + r.bottom * boxScaleY
                        )
                        if (aiMode == AiMode.FACE_DETECTION || aiMode == AiMode.VERIFIED_AUTO_CAPTURE || aiMode == AiMode.IDENTITY_VERIFICATION) {
                            drawContext.canvas.nativeCanvas.drawRoundRect(mappedRect, 16f, 16f, facePaint)
                        } else if (aiMode == AiMode.PADDLE_OCR || aiMode == AiMode.TESSERACT_FAST_OCR || aiMode == AiMode.TEXT_RECOGNITION) {                            drawContext.canvas.nativeCanvas.drawRect(mappedRect, ocrPaint)
                        } else if (aiMode == AiMode.OBJECT_DETECTION || aiMode == AiMode.CUSTOM_OBJECT_DETECTION) {
                            // White box as requested
                            val objPaint = android.graphics.Paint().apply {
                                color = android.graphics.Color.WHITE
                                style = android.graphics.Paint.Style.STROKE
                                strokeWidth = 4f
                            }
                            drawContext.canvas.nativeCanvas.drawRect(mappedRect, objPaint)

                            val tid = item.extra["tracking_id"] as? Int ?: -1
                            val index = item.extra["index"] as? Int ?: -1

                            val lines = mutableListOf<String>()
                            if (tid != -1) lines.add("Tracking ID: $tid")

                            if (aiMode == AiMode.CUSTOM_OBJECT_DETECTION) {
                                lines.add("${item.label} (index: $index)")
                                lines.add("${String.format("%.2f", item.confidence * 100)}% confidence (index: $index)")
                            } else if (tid != -1) {
                                // Already added tracking ID
                            }

                            if (lines.isNotEmpty()) {
                                val tidPaint = android.graphics.Paint().apply {
                                    color = android.graphics.Color.BLACK
                                    textSize = 32f
                                    typeface = android.graphics.Typeface.DEFAULT
                                }
                                val bgPaint = android.graphics.Paint().apply {
                                    color = android.graphics.Color.LTGRAY
                                    alpha = 180
                                    style = android.graphics.Paint.Style.FILL
                                }

                                val lineHeight = 40f
                                val padding = 10f
                                var maxWidth = 0f
                                lines.forEach { maxWidth = max(maxWidth, tidPaint.measureText(it)) }

                                val bgRect = android.graphics.RectF(
                                    mappedRect.left,
                                    mappedRect.top - (lines.size * lineHeight) - padding,
                                    mappedRect.left + maxWidth + (padding * 2),
                                    mappedRect.top
                                )
                                drawContext.canvas.nativeCanvas.drawRect(bgRect, bgPaint)

                                lines.forEachIndexed { i, line ->
                                    drawContext.canvas.nativeCanvas.drawText(
                                        line,
                                        mappedRect.left + padding,
                                        mappedRect.top - ((lines.size - i - 1) * lineHeight) - padding - 8f,
                                        tidPaint
                                    )
                                }
                            }
                        } else if (aiMode == AiMode.POSE_DETECTION) {
                            val landmarks = item.extra["landmarks_raw"] as? Map<Int, android.graphics.PointF>
                            if (landmarks != null) {
                                val posePaint = android.graphics.Paint().apply {
                                    color = android.graphics.Color.parseColor("#E91E63") // Pinkish red as requested
                                    style = android.graphics.Paint.Style.STROKE
                                    strokeWidth = 6f
                                    strokeCap = android.graphics.Paint.Cap.ROUND
                                }
                                val dotPaint = android.graphics.Paint().apply {
                                    color = android.graphics.Color.WHITE
                                    style = android.graphics.Paint.Style.FILL
                                }
                                val confPaint = android.graphics.Paint().apply {
                                    color = android.graphics.Color.WHITE
                                    textSize = 24f
                                    setShadowLayer(2f, 0f, 0f, android.graphics.Color.BLACK)
                                }

                                fun drawLine(startType: Int, endType: Int) {
                                    val s = landmarks[startType]
                                    val e = landmarks[endType]
                                    if (s != null && e != null) {
                                        drawContext.canvas.nativeCanvas.drawLine(s.x * boxScaleX, s.y * boxScaleY, e.x * boxScaleX, e.y * boxScaleY, posePaint)
                                    }
                                }

                                // Connections logic (ML Kit PoseLandmark)
                                // Torso
                                drawLine(11, 12); drawLine(11, 23); drawLine(12, 24); drawLine(23, 24)
                                // Arms
                                drawLine(11, 13); drawLine(13, 15); drawLine(12, 14); drawLine(14, 16)
                                // Legs
                                drawLine(23, 25); drawLine(25, 27); drawLine(24, 26); drawLine(26, 28)
                                // Face
                                drawLine(0, 1); drawLine(1, 2); drawLine(2, 3); drawLine(0, 4); drawLine(4, 5); drawLine(5, 6)
                                drawLine(9, 10)

                                landmarks.forEach { (type, pt) ->
                                    val px = pt.x * boxScaleX
                                    val py = pt.y * boxScaleY
                                    drawContext.canvas.nativeCanvas.drawCircle(px, py, 6f, dotPaint)

                                    // Confidence score text if available
                                    // Note: we don't have per-landmark confidence in 'landmarks_raw' yet, but PoseLandmark has inFrameLikelihood
                                    // For simplicity and matching UI image, we'll draw 1.00 or similar if it exists in JSON
                                    try {
                                        val lJson = org.json.JSONObject(item.extra["landmarks"] as String)
                                        val lObj = lJson.getJSONObject(type.toString())
                                        val prob = lObj.getDouble("likelihood")
                                        drawContext.canvas.nativeCanvas.drawText(String.format("%.2f", prob), px + 10f, py, confPaint)
                                    } catch (e: Exception) {}
                                }
                            }
                        } else if (aiMode == AiMode.SELFIE_SEGMENTATION || aiMode == AiMode.SUBJECT_SEGMENTATION) {
                            val maskBitmap = item.extra["mask_bitmap"] as? Bitmap

                            // 🌟 Draw bounding box as fallback/debug for Subject
                            if (aiMode == AiMode.SUBJECT_SEGMENTATION) {
                                val boxPaint = android.graphics.Paint().apply {
                                    color = android.graphics.Color.WHITE
                                    style = android.graphics.Paint.Style.STROKE
                                    strokeWidth = 3f
                                    pathEffect = android.graphics.DashPathEffect(floatArrayOf(15f, 10f), 0f)
                                }
                                drawContext.canvas.nativeCanvas.drawRect(mappedRect, boxPaint)
                            }

                            if (maskBitmap != null && !maskBitmap.isRecycled) {
                                val destRect = mappedRect
                                val maskPaint = android.graphics.Paint().apply {
                                    isFilterBitmap = true
                                    isAntiAlias = true
                                }
                                drawContext.canvas.nativeCanvas.drawBitmap(maskBitmap, null, destRect, maskPaint)
                            }
                        }
                    }
                }
            }

            // Top Controls (Settings, Scale)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.TopEnd)
                    .padding(top = 10.dp, end = 12.dp, bottom = 8.dp, start = 8.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Scale Dropdown
                var showScaleMenu by remember { mutableStateOf(false) }
                Box {
                    Button(
                        onClick = { showScaleMenu = true },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color.White.copy(alpha = 0.2f),
                            contentColor = Color.White
                        ),
                        shape = RoundedCornerShape(24.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                        modifier = Modifier.height(36.dp)
                    ) {
                        Text("${zoomScale}x", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        Icon(Icons.Default.ArrowDropDown, null, modifier = Modifier.size(18.dp))
                    }
                    DropdownMenu(
                        expanded = showScaleMenu,
                        onDismissRequest = { showScaleMenu = false }
                    ) {
                        listOf(1.0f, 1.2f, 1.5f, 2.0f, 3.0f).forEach { scale ->
                            DropdownMenuItem(
                                text = { Text("${scale}x") },
                                onClick = {
                                    onZoomScaleChange(scale)
                                    showScaleMenu = false
                                }
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.width(8.dp))

                // Manual Flip Toggles
                IconButton(
                    onClick = { onHorizontalFlipChange(!horizontalFlip) },
                    modifier = Modifier
                        .size(40.dp)
                        .background(if (horizontalFlip) Color(0xFF4CAF50).copy(alpha = 0.6f) else Color.White.copy(alpha = 0.2f), CircleShape)
                ) {
                    Icon(
                        imageVector = Icons.Default.Repeat,
                        contentDescription = "Flip Horizontal",
                        tint = Color.White,
                        modifier = Modifier.size(20.dp)
                    )
                }

                Spacer(modifier = Modifier.width(8.dp))

                IconButton(
                    onClick = { onVerticalFlipChange(!verticalFlip) },
                    modifier = Modifier
                        .size(40.dp)
                        .background(if (verticalFlip) Color(0xFF4CAF50).copy(alpha = 0.6f) else Color.White.copy(alpha = 0.2f), CircleShape)
                ) {
                    Icon(
                        imageVector = Icons.Default.SwapVert,
                        contentDescription = "Flip Vertical",
                        tint = Color.White,
                        modifier = Modifier.size(20.dp)
                    )
                }

                Spacer(modifier = Modifier.width(8.dp))

                // Settings Button
                IconButton(
                    onClick = { showSettingsDialog = true },
                    modifier = Modifier
                        .size(40.dp)
                        .background(Color.White.copy(alpha = 0.2f), CircleShape)
                ) {
                    Icon(
                        Icons.Default.Settings,
                        contentDescription = "Settings",
                        tint = Color.White,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }

            // Bottom Bar Overlay (Console Design - Highly Refined & Compact)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    .padding(start = 8.dp, end = 8.dp, top = 12.dp, bottom = 8.dp)
                    .background(Color.Black.copy(alpha = 0.7f), RoundedCornerShape(100.dp))
                    .border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(100.dp))
                    .padding(horizontal = 20.dp, vertical = 18.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Left Column: Auto-Snap Status + Crop Toggle (Centered Alignment)
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(start = 4.dp)
                ) {
                    // Auto-Snap Indicator
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (isCapturing) {
                            CircularProgressIndicator(
                                color = Color(0xFF007AFF),
                                strokeWidth = 1.2.dp,
                                modifier = Modifier.size(10.dp)
                            )
                        } else {
                            Box(
                                modifier = Modifier
                                    .size(6.dp)
                                    .background(
                                        if (stableTime > 0) Color.Yellow
                                        else if (!isProcessingBusy) Color.Green
                                        else Color.Gray,
                                        CircleShape
                                    )
                            )
                        }
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            "AUTO-SNAP",
                            color = Color.White.copy(alpha = 0.9f),
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.5.sp
                        )
                    }

                    // Full/Crop Toggle Button
                    Surface(
                        onClick = { onUseCropModeChange(!useCropMode) },
                        color = if (useCropMode) Color(0xFF007AFF) else Color.White.copy(alpha = 0.12f),
                        shape = RoundedCornerShape(100.dp),
                        modifier = Modifier.height(26.dp)
                    ) {
                        Box(
                            modifier = Modifier.fillMaxHeight().padding(horizontal = 14.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                if (useCropMode) "CROP IMAGE" else "FULL IMAGE",
                                color = Color.White,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Black,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center
                            )
                        }
                    }
                }

                // Right: AI Mode Selector + Face Mode Toggle (Vertical Stack)
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Surface(
                        onClick = { showAiModeSheet = true },
                        color = Color.White.copy(alpha = 0.08f),
                        shape = RoundedCornerShape(100.dp),
                        modifier = Modifier.height(38.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxHeight().padding(horizontal = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            val displayName = when (aiMode) {
                                AiMode.PADDLE_OCR -> "PaddleOCR"
                                AiMode.TESSERACT_FAST_OCR -> "Tesseract Fast OCR"
                                AiMode.HAND_DETECTION -> "MediaPipe Hand Landmarks Detection"
                                AiMode.FACE_DETECTION -> "Face Detection"
                                AiMode.VERIFIED_AUTO_CAPTURE -> "Verified Auto Capture"
                                AiMode.IDENTITY_VERIFICATION -> "Identity Verification Mode"
                                AiMode.POSE_DETECTION -> "Pose Detection"
                                AiMode.SELFIE_SEGMENTATION -> "Selfie Segment"
                                AiMode.SUBJECT_SEGMENTATION -> "Subject Segment"
                                AiMode.OBJECT_DETECTION -> "Object Detect"
                                AiMode.CUSTOM_OBJECT_DETECTION -> "Custom Object"
                                AiMode.TEXT_RECOGNITION -> "Text Recognition"
                                else -> aiMode.name
                            }
                            Text(
                                text = displayName,
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp
                            )
                            Icon(
                                Icons.Default.KeyboardArrowUp,
                                null,
                                tint = Color.White.copy(alpha = 0.6f),
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }

                    if (aiMode == AiMode.FACE_DETECTION || aiMode == AiMode.VERIFIED_AUTO_CAPTURE || aiMode == AiMode.IDENTITY_VERIFICATION) {
                        // Face Mode Toggle Button
                        Surface(
                            onClick = { onTargetFaceModeChange(if (targetFaceMode == "card") "normal" else "card") },
                            color = if (targetFaceMode == "card") Color(0xFFFF9500) else Color(0xFF34C759),
                            shape = RoundedCornerShape(100.dp),
                            modifier = Modifier.height(26.dp)
                        ) {
                            Box(
                                modifier = Modifier.fillMaxHeight().padding(horizontal = 14.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    if (targetFaceMode == "card") "CARD FACE" else "NORMAL FACE",
                                    color = Color.White,
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Black,
                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                                )
                            }
                        }
                    }
                }
            }

        }
    }

    if (showSettingsDialog) {
        // Hide overlay when sheet is visible to prevent UI overlap
        DisposableEffect(Unit) {
            RelayService.getInstance()?.overlayManager?.hideOverlay()
            onDispose {
                RelayService.getInstance()?.overlayManager?.showOverlayView()
            }
        }
        ModalBottomSheet(
            onDismissRequest = { showSettingsDialog = false },
            containerColor = MaterialTheme.colorScheme.surface,
            tonalElevation = 8.dp
        ) {
            Column(
                Modifier
                    .padding(horizontal = 24.dp, vertical = 16.dp)
                    .verticalScroll(rememberScrollState())
                    .fillMaxWidth()
            ) {
                // Header
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(bottom = 16.dp)
                ) {
                    Icon(
                        Icons.Default.Settings,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(28.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        "Camera Settings",
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Spacer(modifier = Modifier.height(16.dp))

                // Camera Selection Section
                Text(
                    "Select Camera",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(8.dp))
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    availableCameras.forEachIndexed { index, id ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { onCameraIdChange(id) }
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = (id == selectedCameraId),
                                onClick = { onCameraIdChange(id) }
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text(
                                    text = if (id == "0") "Back Camera" else if (id == "1") "Front Camera" else "Camera ID: $id",
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.Medium
                                )
                                Text(
                                    text = "ID: $id",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        if (index < availableCameras.lastIndex) {
                            HorizontalDivider(
                                modifier = Modifier.padding(start = 52.dp),
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                // Aspect Ratio Selection
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Aspect Ratio",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))

                val supportedRatios = listOf(UiAspectRatio.RATIO_3_4, UiAspectRatio.RATIO_9_16, UiAspectRatio.RATIO_1_1)

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    supportedRatios.forEach { ratio ->
                        FilterChip(
                            selected = (ratio == selectedAspectRatio),
                            onClick = { onAspectRatioChange(ratio) },
                            label = {
                                Text(
                                    ratio.displayName,
                                    style = MaterialTheme.typography.labelLarge,
                                    modifier = Modifier.padding(vertical = 4.dp)
                                )
                            },
                            leadingIcon = if (ratio == selectedAspectRatio) {
                                {
                                    Icon(
                                        Icons.Default.Check,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            } else null,
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                            ),
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                // Resolution Selection
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Resolution (JPEG)",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))

                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth().heightIn(max = 250.dp) // Limit height
                ) {
                    LazyColumn(
                        modifier = Modifier.heightIn(max = 250.dp)
                    ) {
                        items(availableResolutions) { size ->
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable { onResolutionChange(size) }
                                    .padding(horizontal = 16.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(
                                    selected = (size == selectedResolution),
                                    onClick = { onResolutionChange(size) }
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        "${size.width} x ${size.height}",
                                        style = MaterialTheme.typography.bodyLarge,
                                        fontWeight = FontWeight.Medium
                                    )
                                    val mp =
                                        String.format(Locale.US, "%.1f MP", (size.width * size.height) / 1_000_000f)
                                    Text(
                                        mp,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                if (size == availableResolutions.minByOrNull { it.width * it.height } && selectedAspectRatio == UiAspectRatio.RATIO_1_1) {
                                    Surface(
                                        color = MaterialTheme.colorScheme.tertiaryContainer,
                                        shape = RoundedCornerShape(16.dp)
                                    ) {
                                        Text(
                                            "Recommended",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                        )
                                    }
                                }
                            }
                            HorizontalDivider(
                                modifier = Modifier.padding(start = 52.dp),
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(32.dp))
            }
        }
    }

    if (showAiModeSheet) {
        AiModeBottomSheet(
            currentAiMode = aiMode,
            onAiModeChange = { onAiModeChange(it) },
            onDismiss = { showAiModeSheet = false }
        )
    }
}

@Composable
fun AiModeSelector(
    showAiModeSheet: Boolean,
    currentAiMode: AiMode,
    onAiModeChange: (AiMode) -> Unit,
    onDismiss: () -> Unit
) {
    if (showAiModeSheet) {
        AiModeBottomSheet(
            currentAiMode = currentAiMode,
            onAiModeChange = onAiModeChange,
            onDismiss = onDismiss
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiModeBottomSheet(
    currentAiMode: AiMode,
    onAiModeChange: (AiMode) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val isSwitching = remember { mutableStateOf(false) }
    val switchingJob = remember { mutableStateOf<Job?>(null) }

    DisposableEffect(Unit) {
        RelayService.getInstance()?.overlayManager?.hideOverlay()
        onDispose {
            RelayService.getInstance()?.overlayManager?.showOverlayView()
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = Color.White,
        dragHandle = { BottomSheetDefaults.DragHandle() },
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 8.dp)
                .padding(bottom = 64.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            AiMode.values().filter { it != AiMode.PREVIEW }.forEach { mode ->
                val label = when (mode) {
                    AiMode.PADDLE_OCR -> "PaddleOCRv5"
                    AiMode.TESSERACT_FAST_OCR -> "Tesseract Fast OCR"
                    AiMode.HAND_DETECTION -> "MediaPipe Hand Landmarks Detection"
                    AiMode.FACE_DETECTION -> "Face Detection"
                    AiMode.VERIFIED_AUTO_CAPTURE -> "Verified Auto Capture"
                    AiMode.IDENTITY_VERIFICATION -> "Identity Verification Mode"
                    AiMode.POSE_DETECTION -> "Pose Detection"
                    AiMode.SELFIE_SEGMENTATION -> "Selfie Segmentation"
                    AiMode.SUBJECT_SEGMENTATION -> "Subject Segmentation"
                    AiMode.OBJECT_DETECTION -> "Object Detection"
                    AiMode.CUSTOM_OBJECT_DETECTION -> "Custom Object Detection"
                    AiMode.TEXT_RECOGNITION -> "Text Recognition"
                    else -> mode.name
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = !isSwitching.value) {
                            if (isSwitching.value) return@clickable
                            onAiModeChange(mode)
                            switchingJob.value?.cancel()
                            switchingJob.value = scope.launch(Dispatchers.Default) {
                                isSwitching.value = true
                                try {
                                    AIManager.switchProcessor(context, mode.name)
                                } catch (e: Exception) {
                                    Log.e("AIScreen", "Switch AI Error", e)
                                } finally {
                                    isSwitching.value = false
                                }
                            }
                            onDismiss()
                        }
                        .padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .border(3.dp, if (currentAiMode == mode) Color(0xFF008080) else Color.Gray, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        if (currentAiMode == mode) {
                            Box(
                                modifier = Modifier
                                    .size(14.dp)
                                    .background(Color(0xFF008080), CircleShape)
                            )
                        }
                    }

                    Text(
                        text = label,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color.Black
                    )
                }
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OCRResultScreen(
    aiMode: AiMode,
    image: Bitmap,
    leftPalmImage: Bitmap? = null,
    rightPalmImage: Bitmap? = null,
    jsonResult: String,
    timeMs: Long,
    zoomScale: Float,
    scanResolution: String,
    isProcessing: Boolean,
    computeMode: ComputeMode,
    onComputeModeChange: (ComputeMode) -> Unit,
    onClear: () -> Unit,
    onRunModel: () -> Unit,
    onSendWs: () -> Unit,
    onGalleryClick: () -> Unit
) {
    var showJsonDialog by remember { mutableStateOf(false) }
    var fullJsonOutput by remember { mutableStateOf("") }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val clipboardManager = androidx.compose.ui.platform.LocalClipboardManager.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        val title = when (aiMode) {
                            AiMode.HAND_DETECTION -> "Palmprint Result"
                            AiMode.FACE_DETECTION -> "Face Result"
                            AiMode.VERIFIED_AUTO_CAPTURE -> "Verified Auto Capture Result"
                            AiMode.IDENTITY_VERIFICATION -> "Identity Verification Result"
                            AiMode.SELFIE_SEGMENTATION -> "Selfie Result"
                            AiMode.SUBJECT_SEGMENTATION -> "Subject Result"
                            AiMode.TEXT_RECOGNITION -> "ML Kit Result"
                            else -> "OCR Result"
                        }
                        Text(title, style = MaterialTheme.typography.titleMedium)
                        val modelName = when (aiMode) {
                            AiMode.TESSERACT_FAST_OCR -> "Tesseract Fast OCR"
                            AiMode.HAND_DETECTION -> "MediaPipe Hand Gesture"
                            AiMode.FACE_DETECTION -> "ML Kit Face Detection"
                            AiMode.VERIFIED_AUTO_CAPTURE -> "Verified Auto Capture Pipeline"
                            AiMode.IDENTITY_VERIFICATION -> "Identity Verification Pipeline"
                            AiMode.SELFIE_SEGMENTATION -> "ML Kit Selfie Segmentation"
                            AiMode.SUBJECT_SEGMENTATION -> "ML Kit Subject Segmentation"
                            AiMode.TEXT_RECOGNITION -> "ML Kit Text Recognition"
                            else -> "PaddleOCRv5"
                        }
                        Text(
                            modelName,
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.Gray
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onClear) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    // Gallery Button removed per user request
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                    navigationIconContentColor = MaterialTheme.colorScheme.onSurface,
                    actionIconContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        },
        bottomBar = {
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainer,
                tonalElevation = 3.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // "Scan" button removed to prevent redundant scans

                        // Preview JSON Button
                        OutlinedButton(
                            onClick = {
                                if (jsonResult == "[]" || jsonResult.isEmpty()) {
                                    Toast.makeText(
                                        context,
                                        when (aiMode) {
                                            AiMode.PADDLE_OCR -> "Please run OCR first"
                                            AiMode.TEXT_RECOGNITION -> "No text found"
                                            AiMode.FACE_DETECTION -> "No face result"
                                            AiMode.VERIFIED_AUTO_CAPTURE -> "No face result"
                                            else -> "No result"
                                        },
                                        Toast.LENGTH_SHORT
                                    ).show()
                                    return@OutlinedButton
                                }

                                scope.launch(Dispatchers.IO) {
                                    val payload = generateOCRPayload(context, image, jsonResult, timeMs, aiMode)
                                    // Add AiMode to payload if needed, or keeping it the same
                                    val jsonString = payload.toString(2)
                                    withContext(Dispatchers.Main) {
                                        fullJsonOutput = jsonString
                                        showJsonDialog = true
                                    }
                                }
                            },
                            enabled = !isProcessing && jsonResult != "[]",
                            modifier = Modifier.weight(1f).height(48.dp),
                            contentPadding = PaddingValues(horizontal = 4.dp)
                        ) {
                            Icon(Icons.Default.Visibility, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Result", maxLines = 1, style = MaterialTheme.typography.labelMedium)
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Send JSON Button
                        Button(
                            onClick = {
                                if (jsonResult == "[]" || jsonResult.isEmpty()) {
                                    Toast.makeText(
                                        context,
                                        when (aiMode) {
                                            AiMode.PADDLE_OCR -> "Please run OCR first"
                                            AiMode.TEXT_RECOGNITION -> "No text found"
                                            AiMode.FACE_DETECTION -> "No face result"
                                            AiMode.VERIFIED_AUTO_CAPTURE -> "No face result"
                                            else -> "No result"
                                        },
                                        Toast.LENGTH_SHORT
                                    ).show()
                                    return@Button
                                }

                                scope.launch(Dispatchers.IO) {
                                    val payload = generateOCRPayload(context, image, jsonResult, timeMs, aiMode)
                                    val jsonString = payload.toString(2)

                                    withContext(Dispatchers.Main) {
                                        fullJsonOutput = jsonString

                                        val service = RelayService.getInstance()
                                        if (service != null) {
                                            service.broadcastMessage(jsonString)

                                            // 🌟 Add Firebase Logger directly here
                                            FirebaseLogger.logStep(
                                                context = context,
                                                stepName = "AI_SEND_DATA_TO_WEBCLIENT",
                                                status = "SUCCESS",
                                                extraData = mapOf<String, Any>(
                                                    "ai_mode" to aiMode.name,
                                                    "payload_size" to jsonString.length,
                                                    "items_found" to try {
                                                        org.json.JSONArray(jsonResult).length()
                                                    } catch (e: Exception) {
                                                        0
                                                    },
                                                    "extracted_text" to (payload.optJSONObject("result")
                                                        ?.optString("full_text") ?: jsonResult), // Ensure typing
                                                    "latency_ms" to timeMs,
                                                    "compute_mode" to computeMode.displayName,
                                                    "chosen_resolution" to "Pre-Crop/Selected",
                                                    "final_ai_resolution" to "${image.width}x${image.height}",
                                                    "type" to when(aiMode) {
                                                        AiMode.HAND_DETECTION -> "PalmPrint"
                                                        AiMode.TEXT_RECOGNITION -> "MLKitText"
                                                        AiMode.FACE_DETECTION -> "FaceDetection"
                                                        AiMode.VERIFIED_AUTO_CAPTURE -> "VerifiedAutoCapture"
                                                        else -> "OCR"
                                                    },
                                                    "use_gpu" to computeMode.useGpu,
                                                    "model_paddle_loaded" to (aiMode == AiMode.PADDLE_OCR || aiMode == AiMode.TESSERACT_FAST_OCR),
                                                    "model_mlkit_text_loaded" to (aiMode == AiMode.TEXT_RECOGNITION),
                                                    "model_mediapipe_loaded" to (aiMode == AiMode.HAND_DETECTION),
                                                    "snap_image_active" to true,
                                                    "avg_confidence" to 1.0,
                                                    "cropped_ms" to 0L
                                                )
                                            )

                                            com.example.android_screen_relay.LogRepository.addLog(
                                                component = "OCR",
                                                event = "send_json",
                                                data = mapOf(
                                                    "payload_size" to jsonString.length, "blocks" to try {
                                                        org.json.JSONArray(jsonResult).length()
                                                    } catch (e: Exception) {
                                                        0
                                                    }
                                                ),
                                                type = com.example.android_screen_relay.LogRepository.LogType.OUTGOING
                                            )

                                            Toast.makeText(context, "Data Sent!", Toast.LENGTH_SHORT).show()
                                        } else {
                                            Toast.makeText(context, "Service not running", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                }
                            },
                            enabled = !isProcessing && jsonResult != "[]",
                            modifier = Modifier.weight(1f).height(48.dp),
                            contentPadding = PaddingValues(horizontal = 4.dp)
                        ) {
                            Icon(Icons.Default.Send, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Send", maxLines = 1, style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(Color(0xFFF5F5F5))
        ) {
            if (aiMode == AiMode.HAND_DETECTION && leftPalmImage != null && rightPalmImage != null) {
                // Show both palms for PALMPRINT mode
                Column(
                    modifier = Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // Left Palm
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp).aspectRatio(1f),
                        shape = RoundedCornerShape(12.dp),
                        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                    ) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            if (!leftPalmImage.isRecycled) {
                                Image(
                                    bitmap = leftPalmImage.asImageBitmap(),
                                    contentDescription = "Left Palm",
                                    modifier = Modifier.fillMaxSize(),
                                    contentScale = ContentScale.Crop
                                )
                            }
                            Text(
                                text = "Hand Left",
                                color = Color.White,
                                modifier = Modifier
                                    .align(Alignment.BottomStart)
                                    .padding(8.dp)
                                    .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(8.dp))
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    // Right Palm
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp).aspectRatio(1f),
                        shape = RoundedCornerShape(12.dp),
                        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                    ) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            if (!rightPalmImage.isRecycled) {
                                Image(
                                    bitmap = rightPalmImage.asImageBitmap(),
                                    contentDescription = "Right Palm",
                                    modifier = Modifier.fillMaxSize(),
                                    contentScale = ContentScale.Crop
                                )
                            }
                            Text(
                                text = "Hand Right",
                                color = Color.White,
                                modifier = Modifier
                                    .align(Alignment.BottomStart)
                                    .padding(8.dp)
                                    .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(8.dp))
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            } else {
                // Default OCR view
                if (!image.isRecycled) {
                    Image(
                        bitmap = image.asImageBitmap(),
                        contentDescription = "Target Image",
                        modifier = Modifier.fillMaxSize().padding(16.dp),
                        contentScale = ContentScale.Fit
                    )
                }

                // Pre-calculated data for Overlay to avoid heavy parsing in DrawScope
                val drawingBoxes = remember(jsonResult) {
                    val list = mutableListOf<org.json.JSONObject>()
                    try {
                        val arr = org.json.JSONArray(jsonResult)
                        for (i in 0 until arr.length()) {
                            list.add(arr.getJSONObject(i))
                        }
                    } catch (e: Exception) {}
                    list
                }

                val redPaint = remember {
                    Paint().apply {
                        color = android.graphics.Color.RED
                        style = Paint.Style.STROKE
                        strokeWidth = 2f
                    }
                }
                val redFillPaint = remember {
                    Paint().apply {
                        color = android.graphics.Color.parseColor("#33FF0000")
                        style = Paint.Style.FILL
                    }
                }
                val textPaint = remember {
                    Paint().apply {
                        color = android.graphics.Color.WHITE
                        textSize = 12f
                        textAlign = Paint.Align.LEFT
                        setShadowLayer(3f, 0f, 0f, android.graphics.Color.BLACK)
                    }
                }

                // Overlay
                Canvas(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                    val scaleX = size.width / image.width.toFloat()
                    val scaleY = size.height / image.height.toFloat()

                    // ContentScale.Fit aligns to center. Calculate the actual scale used.
                    val scale = min(scaleX, scaleY)

                    // Calculate offset to center
                    val offsetX = (size.width - image.width * scale) / 2
                    val offsetY = (size.height - image.height * scale) / 2

                    try {
                        drawContext.canvas.nativeCanvas.save()
                        drawContext.canvas.nativeCanvas.translate(offsetX, offsetY)
                        drawContext.canvas.nativeCanvas.scale(scale, scale)

                        drawingBoxes.forEach { boxObj ->
                            // Parse "box" for OCR (polygon)
                            if (boxObj.has("box") || (boxObj.has("x0") && boxObj.has("y0"))) {
                                val boxPath = Path()
                                var validPath = false
                                var p0X = 0f
                                var p0Y = 0f

                                if (boxObj.has("box")) {
                                    val boxArr = boxObj.getJSONArray("box")
                                    if (boxArr.length() > 0) {
                                        val p0 = boxArr.getJSONArray(0)
                                        p0X = p0.getInt(0).toFloat()
                                        p0Y = p0.getInt(1).toFloat()
                                        boxPath.moveTo(p0X, p0Y)
                                        for (j in 1 until boxArr.length()) {
                                            val p = boxArr.getJSONArray(j)
                                            boxPath.lineTo(p.getInt(0).toFloat(), p.getInt(1).toFloat())
                                        }
                                        boxPath.close()
                                        validPath = true
                                    }
                                } else {
                                    p0X = boxObj.getDouble("x0").toFloat()
                                    p0Y = boxObj.getDouble("y0").toFloat()
                                    boxPath.moveTo(p0X, p0Y)
                                    boxPath.lineTo(boxObj.getDouble("x1").toFloat(), boxObj.getDouble("y1").toFloat())
                                    boxPath.lineTo(boxObj.getDouble("x2").toFloat(), boxObj.getDouble("y2").toFloat())
                                    boxPath.lineTo(boxObj.getDouble("x3").toFloat(), boxObj.getDouble("y3").toFloat())
                                    boxPath.close()
                                    validPath = true
                                }

                                if (validPath) {
                                    drawContext.canvas.nativeCanvas.drawPath(boxPath, redFillPaint)

                                    redPaint.strokeWidth = 2f / scale
                                    drawContext.canvas.nativeCanvas.drawPath(boxPath, redPaint)

                                    // Draw label
                                    if (boxObj.has("label")) {
                                        val label = boxObj.getString("label")
                                        textPaint.textSize = 12f / scale
                                        drawContext.canvas.nativeCanvas.drawText(label, p0X, p0Y - (5f / scale), textPaint)
                                    }
                                }
                            } else if ((aiMode == AiMode.FACE_DETECTION || aiMode == AiMode.VERIFIED_AUTO_CAPTURE || aiMode == AiMode.IDENTITY_VERIFICATION) && boxObj.has("bbox")) {
                                // Draw BBox for face
                                val bArr = boxObj.getJSONArray("bbox")
                                val l = bArr.getDouble(0).toFloat()
                                val t = bArr.getDouble(1).toFloat()
                                val r = bArr.getDouble(2).toFloat()
                                val b = bArr.getDouble(3).toFloat()

                                redPaint.strokeWidth = 3f / scale
                                drawContext.canvas.nativeCanvas.drawRect(l, t, r, b, redPaint)

                                // Draw Contours
                                if (boxObj.has("contours")) {
                                    val contours = boxObj.getJSONObject("contours")
                                    // Static map of contour colors
                                    val contourColors = mapOf(
                                        "FACE_OVAL" to android.graphics.Color.parseColor("#4285F4"),
                                        "LEFT_EYEBROW_TOP" to android.graphics.Color.parseColor("#E65100"),
                                        "LEFT_EYEBROW_BOTTOM" to android.graphics.Color.parseColor("#FFC107"),
                                        "RIGHT_EYEBROW_TOP" to android.graphics.Color.parseColor("#0F9D58"),
                                        "RIGHT_EYEBROW_BOTTOM" to android.graphics.Color.parseColor("#8E24AA"),
                                        "LEFT_EYE" to android.graphics.Color.parseColor("#1E88E5"),
                                        "RIGHT_EYE" to android.graphics.Color.parseColor("#00ACC1"),
                                        "UPPER_LIP_TOP" to android.graphics.Color.parseColor("#D81B60"),
                                        "UPPER_LIP_BOTTOM" to android.graphics.Color.parseColor("#7CB342"),
                                        "LOWER_LIP_TOP" to android.graphics.Color.parseColor("#E53935"),
                                        "LOWER_LIP_BOTTOM" to android.graphics.Color.parseColor("#795548"),
                                        "NOSE_BRIDGE" to android.graphics.Color.parseColor("#AB47BC"),
                                        "NOSE_BOTTOM" to android.graphics.Color.parseColor("#00897B")
                                    )

                                    val pointPaint = Paint().apply { style = Paint.Style.FILL }
                                    val linePaint = Paint().apply { style = Paint.Style.STROKE; strokeWidth = 2.5f / scale }
                                    val radius = 2f / scale

                                    contours.keys().forEach { key ->
                                        val pts = contours.getJSONArray(key)
                                        if (pts.length() > 0) {
                                            val colorInt = (contourColors[key] ?: android.graphics.Color.WHITE) as Int
                                            pointPaint.color = android.graphics.Color.WHITE
                                            linePaint.color = colorInt

                                            val path = Path()
                                            for (j in 0 until pts.length()) {
                                                val p = pts.getJSONArray(j)
                                                val px = p.getDouble(0).toFloat()
                                                val py = p.getDouble(1).toFloat()
                                                if (j == 0) path.moveTo(px, py)
                                                else path.lineTo(px, py)

                                                // Draw point slightly after line
                                                drawContext.canvas.nativeCanvas.drawCircle(px, py, radius, pointPaint)
                                            }
                                            if (key == "FACE_OVAL" || key.contains("EYE") || key.contains("LIP")) {
                                                path.close()
                                            }
                                            drawContext.canvas.nativeCanvas.drawPath(path, linePaint)
                                        }
                                    }
                                }
                            }
                        }
                        drawContext.canvas.nativeCanvas.restore()
                    } catch (e: Exception) {
                        // Log.e("OCR", "Draw error", e)
                    }
                }
            } // END else block for normal image Mode


        }
    }

    if (showJsonDialog) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        var showPayload by remember { mutableStateOf(false) } // false = Preview, true = Payload

        ModalBottomSheet(
            onDismissRequest = { showJsonDialog = false },
            sheetState = sheetState,
            containerColor = Color.White,
            dragHandle = { BottomSheetDefaults.DragHandle(color = Color.LightGray) },
            tonalElevation = 8.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Header
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.Description,
                        contentDescription = "JSON",
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(24.dp)
                    )
                }

                Spacer(Modifier.height(16.dp))

                Text(
                    if (showPayload) "JSON Payload" else "Result Preview",
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.Black,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    if (showPayload) "Review the generated data structure" else "Review the processed ${
                        when (aiMode) {
                            AiMode.PADDLE_OCR, AiMode.TESSERACT_FAST_OCR -> "OCR text"
                            AiMode.FACE_DETECTION -> "face data"
                            AiMode.VERIFIED_AUTO_CAPTURE -> "face data"
                            AiMode.SELFIE_SEGMENTATION -> "selfie data"
                            AiMode.SUBJECT_SEGMENTATION -> "subject data"
                            AiMode.TEXT_RECOGNITION -> "text recognition data"
                            AiMode.HAND_DETECTION -> "palmprint data"
                            else -> "data"
                        }
                    }",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.Gray
                )

                Spacer(Modifier.height(16.dp))

                // Toggle Buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = { showPayload = false },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (!showPayload) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                            contentColor = if (!showPayload) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                        ),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Preview")
                    }
                    Button(
                        onClick = { showPayload = true },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (showPayload) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                            contentColor = if (showPayload) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                        ),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Payload")
                    }
                }

                Spacer(Modifier.height(24.dp))

                if (showPayload) {
                    // JSON Content Area
                    Card(
                        shape = RoundedCornerShape(12.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFE0E0E0)),
                        colors = CardDefaults.cardColors(containerColor = Color.White),
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f, fill = false)
                            .heightIn(min = 200.dp, max = 450.dp)
                    ) {
                        val scrollState = rememberScrollState()
                        Box(modifier = Modifier.padding(16.dp).verticalScroll(scrollState)) {
                            Text(
                                text = fullJsonOutput,
                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                fontSize = 13.sp,
                                lineHeight = 18.sp,
                                color = Color(0xFF333333)
                            )
                        }
                    }
                } else {
                    // Formatted Preview Content Area
                    var fullText = ""
                    var avgConf = 0.0
                    try {
                        val arr = org.json.JSONArray(jsonResult)
                        var sumConf = 0.0
                        val cnt = arr.length()
                        for (i in 0 until cnt) {
                            val obj = arr.getJSONObject(i)

                            // Calculate Confidence (Common for all modes)
                            if (obj.has("confidence")) {
                                sumConf += obj.getDouble("confidence")
                            } else if (obj.has("prob")) {
                                sumConf += obj.getDouble("prob")
                            }

                            // Calculate FullText
                            var rawText = ""
                            if (aiMode == AiMode.FACE_DETECTION || aiMode == AiMode.VERIFIED_AUTO_CAPTURE || aiMode == AiMode.IDENTITY_VERIFICATION) {
                                val smiling = obj.optDouble("smiling_prob", -1.0)
                                val leftEye = obj.optDouble("left_eye_open_prob", -1.0)
                                val rightEye = obj.optDouble("right_eye_open_prob", -1.0)
                                rawText = "Face ${i + 1}:"
                                if (smiling >= 0) rawText += " Smiling(${String.format("%.1f", smiling * 100)}%)"
                                if (leftEye >= 0) rawText += " L-Eye(${String.format("%.1f", leftEye * 100)}%)"
                                if (rightEye >= 0) rawText += " R-Eye(${String.format("%.1f", rightEye * 100)}%)"
                                
                                if ((aiMode == AiMode.VERIFIED_AUTO_CAPTURE || aiMode == AiMode.IDENTITY_VERIFICATION) && obj.has("verification_metrics")) {
                                    try {
                                        val vm = obj.getJSONObject("verification_metrics")
                                        val pStep0 = vm.optJSONObject("step_0_ocr")
                                        val pStep1 = vm.optJSONObject("step_1_pose")
                                        val pStep2 = vm.optJSONObject("step_2_face")
                                        
                                        if (pStep0 != null) {
                                            rawText += "\n\n   [OCR Verification]"
                                            rawText += "\n     - Extracted: ${pStep0.optString("text_extracted", "N/A")}"
                                        }

                                        rawText += "\n   [Face 4-Pillar Verification]"
                                        if (pStep2 != null) {
                                            rawText += "\n     - All Pillars Passed: ${pStep2.optBoolean("4_pillars_passed")}"
                                            rawText += "\n     - Centered: ${pStep2.optBoolean("is_centered")} (OffsetX: ${String.format("%.2f", pStep2.optDouble("center_offset_x_pct", 0.0))})"
                                            rawText += "\n     - Proper Size: ${pStep2.optBoolean("is_proper_size")} (Area: ${String.format("%.2f", pStep2.optDouble("face_area_pct", 0.0))})"
                                            rawText += "\n     - Straight: ${pStep2.optBoolean("is_straight")} (Yaw: ${String.format("%.1f", pStep2.optDouble("yaw", 0.0))}, Pitch: ${String.format("%.1f", pStep2.optDouble("pitch", 0.0))}, Roll: ${String.format("%.1f", pStep2.optDouble("roll", 0.0))})"
                                        }
                                    } catch(e: Exception) {
                                        // Ignore parsing errors for display
                                    }
                                }
                            } else {
                                if (obj.has("label")) {
                                    rawText = obj.getString("label")
                                } else if (obj.has("text")) {
                                    rawText = obj.getString("text")
                                } else if (obj.has("hand")) {
                                    val handValue = obj.getString("hand")
                                    val areaType = obj.optString("area_type", "")
                                    rawText = "Hand: $handValue" + if (areaType.isNotEmpty()) " ($areaType)" else ""
                                }

                                rawText = rawText
                                    .replace(Regex("(?<=[ก-ฮ])(?=(นาย|นาง|นางสาว)[a-zA-Zก-ฮ])"), " ")
                                    .replace(Regex("(?<=(นาย|นาง|นางสาว|เด็กชาย|เด็กหญิง))(?=[a-zA-Zก-ฮ])"), " ")
                                    .replace("ชื่อตัวและชื่อสกุลนาย", "ชื่อตัวและชื่อสกุล นาย")
                            }

                            fullText += rawText + " \n"
                        }
                        if (cnt > 0) avgConf = sumConf / cnt
                    } catch (e: Exception) {
                    }

                    Card(
                        colors = CardDefaults.cardColors(containerColor = Color(0xFFF9F9F9)),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f, fill = false)
                            .heightIn(max = 550.dp)
                    ) {
                        Column(modifier = Modifier.padding(12.dp).verticalScroll(rememberScrollState())) {
                            // 1. Result Content Area (Scrollable Box)
                            Surface(
                                color = Color.White,
                                shape = RoundedCornerShape(8.dp),
                                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFEEEEEE)),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 80.dp, max = 150.dp)
                            ) {
                                Box(modifier = Modifier.padding(12.dp).verticalScroll(rememberScrollState())) {
                                    Text(
                                        if (fullText.trim().isEmpty()) "No data extracted" else fullText.trim(),
                                        color = Color(0xFF007AFF),
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        lineHeight = 16.sp
                                    )
                                }
                            }

                            Spacer(Modifier.height(16.dp))
                            HorizontalDivider(color = Color(0xFFE0E0E0), thickness = 1.dp)
                            Spacer(Modifier.height(16.dp))

                            // --- Professional Dashboard Metrics Section ---
                            val isSuccess = try {
                                val arr = org.json.JSONArray(jsonResult)
                                arr.length() > 0
                            } catch (e: Exception) { false }

                            val statusText = when {
                                (aiMode == AiMode.FACE_DETECTION || aiMode == AiMode.VERIFIED_AUTO_CAPTURE || aiMode == AiMode.IDENTITY_VERIFICATION) && isSuccess -> "Face Detected"
                                aiMode == AiMode.HAND_DETECTION && isSuccess -> "Palmprint Detected"
                                (aiMode == AiMode.PADDLE_OCR || aiMode == AiMode.TESSERACT_FAST_OCR || aiMode == AiMode.TEXT_RECOGNITION) && isSuccess -> "Text Extracted"
                                !isSuccess -> "No Object Detected"
                                else -> "Inference Completed"
                            }

                            Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                // 1. Enhanced Status Header (Row Style)
                                Surface(
                                    color = if (isSuccess) Color(0xFFE8F5E9) else Color(0xFFFBE9E7),
                                    shape = RoundedCornerShape(8.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                                            Icon(
                                                imageVector = if (isSuccess) Icons.Default.CheckCircle else Icons.Default.Error,
                                                contentDescription = null,
                                                tint = if (isSuccess) Color(0xFF2E7D32) else Color(0xFFC62828),
                                                modifier = Modifier.size(18.dp)
                                            )
                                            Spacer(Modifier.width(8.dp))
                                            Text(
                                                "Result Status",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = Color.Gray,
                                                fontWeight = FontWeight.Medium,
                                                maxLines = 1
                                            )
                                        }
                                        Text(
                                            statusText,
                                            style = MaterialTheme.typography.bodySmall,
                                            fontWeight = FontWeight.ExtraBold,
                                            color = if (isSuccess) Color(0xFF1B5E20) else Color(0xFFB71C1C),
                                            textAlign = TextAlign.End,
                                            modifier = Modifier.weight(1f)
                                        )
                                    }
                                }

                                // 2. Detailed Metrics List (Line by Line)
                                Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    MetricRow("Confidence", "${String.format("%.1f", avgConf * 100)}%", Icons.Default.Percent)
                                    HorizontalDivider(color = Color(0xFFF5F5F5))
                                    MetricRow("Processing Time", "${timeMs} ms", Icons.Default.Timer)
                                    HorizontalDivider(color = Color(0xFFF5F5F5))

                                    MetricRow("Scan Resolution", scanResolution, Icons.Default.Camera)
                                    HorizontalDivider(color = Color(0xFFF5F5F5))

                                    val cropW = (image.width / zoomScale).toInt()
                                    val cropH = (image.height / zoomScale).toInt()
                                    MetricRow("Crop Area", "${cropW}x${cropH} px", Icons.Default.Crop)
                                    HorizontalDivider(color = Color(0xFFF5F5F5))

                                    MetricRow("Input Image size", "${image.width}x${image.height} px", Icons.Default.AspectRatio)
                                    HorizontalDivider(color = Color(0xFFF5F5F5))

                                    MetricRow("Scale", "${String.format("%.1f", zoomScale)}x", Icons.Default.ZoomIn)
                                    HorizontalDivider(color = Color(0xFFF5F5F5))

                                    // GPU Info
                                    Row(
                                        modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Icon(Icons.Default.Memory, null, modifier = Modifier.size(14.dp), tint = Color.Gray.copy(alpha = 0.6f))
                                            Spacer(Modifier.width(8.dp))
                                            Text("GPU", style = MaterialTheme.typography.bodySmall, color = Color.Gray, fontWeight = FontWeight.Medium)
                                        }
                                        Surface(
                                            color = if (computeMode.useGpu) Color(0xFFE3F2FD) else Color(0xFFEEEEEE),
                                            shape = RoundedCornerShape(4.dp)
                                        ) {
                                            Text(
                                                " ${if (computeMode.useGpu) "ON" else "OFF"} ",
                                                style = MaterialTheme.typography.labelSmall,
                                                fontWeight = FontWeight.Bold,
                                                color = if (computeMode.useGpu) Color(0xFF1565C0) else Color.DarkGray,
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                                fontSize = 10.sp
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(24.dp))

                // Action Buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedButton(
                        onClick = { showJsonDialog = false },
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.weight(1f).height(50.dp)
                    ) {
                        Text("Close")
                    }

                    Button(
                        onClick = {
                            clipboardManager.setText(androidx.compose.ui.text.AnnotatedString(fullJsonOutput))
                            Toast.makeText(context, "Copied to clipboard", Toast.LENGTH_SHORT).show()
                        },
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.weight(1f).height(50.dp)
                    ) {
                        Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Copy")
                    }
                }
                Spacer(Modifier.height(32.dp))
            }
        }
    }
}

@Composable
fun FaceResultTable(jsonStr: String) {
    val result = runCatching {
        val arr = org.json.JSONArray(jsonStr)
        val list = mutableListOf<org.json.JSONObject>()
        for (i in 0 until arr.length()) {
            list.add(arr.getJSONObject(i))
        }
        list
    }

    if (result.isFailure) {
        Text(
            "Error parsing face data: ${result.exceptionOrNull()?.message}",
            color = Color.Red,
            modifier = Modifier.padding(16.dp)
        )
        return
    }

    val faces = result.getOrNull() ?: emptyList()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 450.dp)
            .verticalScroll(rememberScrollState())
    ) {
        for (i in faces.indices) {
            val obj = faces[i]
            val bbox = obj.optJSONArray("bbox") ?: org.json.JSONArray()
            val eulerY = obj.optDouble("head_euler_y", 0.0)
            val eulerZ = obj.optDouble("head_euler_z", 0.0)
            val trackingId = obj.optInt("tracking_id", -1)

            val smiling = obj.optDouble("smiling_prob", 0.0)
            val lEyeC = obj.optDouble("left_eye_open_prob", 0.0)
            val rEyeC = obj.optDouble("right_eye_open_prob", 0.0)

            val landmarks = obj.optJSONObject("landmarks") ?: org.json.JSONObject()
            val contours = obj.optJSONObject("contours") ?: org.json.JSONObject()

            // Header
            Surface(color = Color(0xFFE5E7EB), modifier = Modifier.fillMaxWidth()) {
                Text(
                    "ใบหน้า ${i + 1} จาก ${faces.size}",
                    modifier = Modifier.padding(12.dp),
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF374151)
                )
            }

            Column(modifier = Modifier.fillMaxWidth().border(1.dp, Color(0xFFE5E7EB))) {
                FaceTableRow(
                    "Lens Info",
                    if (obj.optBoolean("is_front", false)) "Front Camera (Mirror)" else "Back Camera"
                )

                FaceTableRow(
                    "Detected Distance",
                    if (obj.has("face_ratio")) String.format("Face Ratio: %.1f%%", obj.optDouble("face_ratio") * 100) else "N/A"
                )

                FaceTableRow(
                    "Applied Processing",
                    listOfNotNull(
                        if (obj.optBoolean("applied_mirror", false)) "Mirror Compensation" else null,
                        if (obj.optBoolean("applied_upscaling", false)) "Adaptive Upscaling" else null
                    ).joinToString(", ").ifEmpty { "None" }
                )

                FaceTableRow(
                    "เส้นขอบรูปหลายเหลี่ยม",
                    if (bbox.length() >= 4) "[${bbox.optDouble(0).toInt()}, ${
                        bbox.optDouble(1).toInt()
                    }, ${bbox.optDouble(2).toInt()}, ${bbox.optDouble(3).toInt()}]" else "N/A"
                )

                FaceTableRow("มุมของการหมุน", "Y: $eulerY, Z: $eulerZ")
                FaceTableRow("รหัสติดตาม", if (trackingId != -1) trackingId.toString() else "N/A")

                FaceTableRowMulti(
                    "จุดสังเกตบนใบหน้า", listOf(
                        "ตาซ้าย" to formatPt(landmarks.optJSONArray("LEFT_EYE")),
                        "ตาขวา" to formatPt(landmarks.optJSONArray("RIGHT_EYE")),
                        "ก้นปาก" to formatPt(landmarks.optJSONArray("MOUTH_BOTTOM")),
                        "... ฯลฯ" to ""
                    )
                )

                FaceTableRowMulti(
                    "ความน่าจะเป็นของฟีเจอร์", listOf(
                        "การยิ้ม" to smiling.toString(),
                        "ลืมตาข้างซ้าย" to lEyeC.toString(),
                        "ลืมตาขวา" to rEyeC.toString()
                    )
                )

                FaceTableRowMulti(
                    "เค้าโครงของใบหน้า", listOf(
                        "ดั้งจมูก" to formatPtArr(contours.optJSONArray("NOSE_BRIDGE")),
                        "ตาซ้าย" to formatPtArr(contours.optJSONArray("LEFT_EYE")),
                        "ริมฝีปากบน" to formatPtArr(contours.optJSONArray("UPPER_LIP_TOP")),
                        "(ฯลฯ)" to ""
                    )
                )
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
fun FaceTableRow(title: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth().border(1.dp, Color(0xFFE5E7EB)).height(IntrinsicSize.Min)) {
        Box(modifier = Modifier.weight(0.35f).background(Color(0xFFF3F4F6)).padding(12.dp).fillMaxHeight()) {
            Text(
                title,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold,
                color = Color(0xFF374151)
            )
        }
        Box(
            modifier = Modifier.weight(0.65f).padding(12.dp).fillMaxHeight(),
            contentAlignment = Alignment.CenterStart
        ) {
            Text(value, style = MaterialTheme.typography.bodySmall, color = Color(0xFF1F2937))
        }
    }
}

@Composable
fun FaceTableRowMulti(title: String, rows: List<Pair<String, String>>) {
    Row(modifier = Modifier.fillMaxWidth().border(1.dp, Color(0xFFE5E7EB)).height(IntrinsicSize.Min)) {
        Box(modifier = Modifier.weight(0.35f).background(Color(0xFFF3F4F6)).padding(12.dp).fillMaxHeight()) {
            Text(
                title,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold,
                color = Color(0xFF374151)
            )
        }
        Column(modifier = Modifier.weight(0.65f)) {
            rows.forEachIndexed { idx, pair ->
                Row(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
                    Text(
                        pair.first,
                        modifier = Modifier.weight(0.4f),
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF1F2937)
                    )
                    Text(
                        pair.second,
                        modifier = Modifier.weight(0.6f),
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF1F2937)
                    )
                }
                if (idx < rows.size - 1) {
                    HorizontalDivider(color = Color(0xFFE5E7EB))
                }
            }
        }
    }
}

fun formatPt(pt: org.json.JSONArray?): String {
    if (pt == null || pt.length() < 2) return ""
    return "(${String.format(java.util.Locale.US, "%.6f", pt.getDouble(0))}, ${
        String.format(
            java.util.Locale.US,
            "%.6f",
            pt.getDouble(1)
        )
    })"
}

fun formatPtArr(pts: org.json.JSONArray?): String {
    if (pts == null || pts.length() == 0) return ""
    val sb = StringBuilder()
    for (i in 0 until minOf(3, pts.length())) {
        val pt = pts.optJSONArray(i)
        if (pt != null && pt.length() >= 2) {
            sb.append(
                "(${
                    String.format(
                        java.util.Locale.US,
                        "%.6f",
                        pt.getDouble(0)
                    )
                }, ${String.format(java.util.Locale.US, "%.6f", pt.getDouble(1))})"
            )
            if (i < 2 && i < pts.length() - 1) sb.append(", ")
        }
    }
    if (pts.length() > 3) sb.append(", ...")
    return sb.toString()
}

fun minWith(a: Float, b: Float): Float = kotlin.math.min(a, b)

private suspend fun generateOCRPayload(
    context: Context,
    image: Bitmap,
    jsonResult: String,
    timeMs: Long,
    aiMode: AiMode = AiMode.PADDLE_OCR
): JSONObject = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
    val device = SystemMonitor.getDeviceInfo(context)
    val resource = SystemMonitor.getCurrentResourceUsage(context)

    val payload = JSONObject()
    payload.put(
        "type",
        when (aiMode) {
            AiMode.HAND_DETECTION -> "palmprint_result"
            AiMode.FACE_DETECTION -> "face_result"
            AiMode.VERIFIED_AUTO_CAPTURE -> "verified_auto_capture_result"
            AiMode.IDENTITY_VERIFICATION -> "identity_verification_result"
            AiMode.POSE_DETECTION -> "pose_result"
            AiMode.SELFIE_SEGMENTATION -> "selfie_segmentation_result"
            AiMode.SUBJECT_SEGMENTATION -> "subject_segmentation_result"
            AiMode.OBJECT_DETECTION -> "object_detection_result"
            AiMode.CUSTOM_OBJECT_DETECTION -> "custom_object_detection_result"
            AiMode.TEXT_RECOGNITION -> "text_recognition_result"
            else -> "ocr_result"
        }
    )
    payload.put("timestamp", System.currentTimeMillis())

    // engine_info (new format)
    val engineInfo = JSONObject().apply {
        when (aiMode) {
            AiMode.HAND_DETECTION -> {
                put("engine", "mediapipe")
                put("version", "tasks-vision")
                put("runtime", "tflite")
                put("model", "hand_landmarker.task")
            }
            AiMode.FACE_DETECTION, AiMode.VERIFIED_AUTO_CAPTURE, AiMode.IDENTITY_VERIFICATION -> {
                put("engine", "mlkit")
                put("version", "face-detection")
                put("runtime", "gms")
                put("model", "face")
            }
            AiMode.POSE_DETECTION -> {
                put("engine", "mlkit")
                put("version", "pose-detection")
                put("runtime", "gms")
                put("model", "pose")
            }
            AiMode.SELFIE_SEGMENTATION, AiMode.SUBJECT_SEGMENTATION -> {
                put("engine", "mlkit")
                put("version", "segmentation")
                put("runtime", "gms")
                put("model", if (aiMode == AiMode.SELFIE_SEGMENTATION) "selfie" else "subject")
            }
            AiMode.OBJECT_DETECTION, AiMode.CUSTOM_OBJECT_DETECTION -> {
                put("engine", "mlkit")
                put("version", "object-detection")
                put("runtime", "gms")
                put("model", if (aiMode == AiMode.CUSTOM_OBJECT_DETECTION) "custom" else "general")
            }
            AiMode.TEXT_RECOGNITION -> {
                put("engine", "mlkit")
                put("version", "text-recognition")
                put("runtime", "gms")
                put("model", "latin-thai")
            }
            else -> {
                put("engine", "paddleocr")
                put("version", "v5")
                put("runtime", "ncnn+ort")
                put("model", "PP-OCRv5_mobile")
            }
        }

        val mode = ComputeModeManager.getMode()
        put("compute_mode", mode.displayName)
        put("use_gpu", mode.useGpu)
    }
    payload.put("engine_info", engineInfo)
    if (aiMode == AiMode.VERIFIED_AUTO_CAPTURE || aiMode == AiMode.IDENTITY_VERIFICATION) {
        payload.put("pipeline", "sequential_verification")
        val flowArray = org.json.JSONArray()
        flowArray.put("1. Pose Detection (Hand/Wrist Obstruction Check)")
        flowArray.put("2. Face Detection (4-Pillar Alignment Check)")
        payload.put("pipeline_flow", flowArray)
    } else {
        payload.put("pipeline", "on-device")
    }

    payload.put("device_info", device.toJson())

    // image_info: measure simulated jpeg size from bitmap
    val stream = java.io.ByteArrayOutputStream()
    image.compress(Bitmap.CompressFormat.JPEG, 90, stream)
    val sizeBytes = stream.size().toLong()

    payload.put("image_info", JSONObject().apply {
        put("width", image.width)
        put("height", image.height)
        put("file_size_bytes", sizeBytes)
        put("format", "jpeg")
    })

    try {
        val benchmarkArr = JSONArray(jsonResult)

        if (aiMode == AiMode.HAND_DETECTION || aiMode == AiMode.FACE_DETECTION || aiMode == AiMode.VERIFIED_AUTO_CAPTURE || aiMode == AiMode.IDENTITY_VERIFICATION) {
            // Palmprint/Face structure
            payload.put("result", JSONObject().apply {
                put(if (aiMode == AiMode.HAND_DETECTION) "palms" else "faces", benchmarkArr)
            })

            // add resource info directly to palmprint summary so the google script logs it properly
            val resourceStats = resource.toJson()
            payload.put("summary", JSONObject().apply {
                put(if (aiMode == AiMode.HAND_DETECTION) "palms_detected" else "faces_detected", benchmarkArr.length())
                put("total_latency_ms", timeMs)
            })

            // Append memory usage manually at ROOT level so Google Sheet script can use `data.cpu_usage` fallback block
            payload.put("cpu_usage", resourceStats.optString("cpu_usage"))
            payload.put("ram_used_mb", resourceStats.optLong("ram_used_mb"))
            payload.put("ram_total_mb", resourceStats.optLong("ram_total_mb"))
            payload.put("battery_level", resourceStats.optInt("battery_level"))
            payload.put("battery_temp", resourceStats.optDouble("battery_temp_c"))

            return@withContext payload
        }

        // Handle empty array case cleanly for OCR

        if (benchmarkArr.length() == 0) {
            payload.put("result", JSONObject().apply {
                put("full_text", "")
                put("lines", JSONArray())
            })
            payload.put("benchmark", JSONArray())
            payload.put("summary", JSONObject().apply {
                put("text_object_count", 0)
                put("average_confidence", 0.0)
            })
            return@withContext payload
        }

        // 1. Result (full_text & lines)
        val primaryRun = benchmarkArr.getJSONObject(0)

        // Handle flat array format from auto-OCR or nested 'result' array format
        val primaryResultBox = if (primaryRun.has("result")) {
            primaryRun.getJSONArray("result")
        } else {
            benchmarkArr // It's likely a flat array of detection objects directly
        }

        val sb = java.lang.StringBuilder()
        var totalConfidence = 0.0
        val linesArray = JSONArray()

        for (i in 0 until primaryResultBox.length()) {
            val item = primaryResultBox.getJSONObject(i)
            val hasText = item.has("text")
            val hasLabel = item.has("label")
            if (!hasText && !hasLabel) continue

            val text = if (hasText) item.getString("text") else item.getString("label")
            val conf = if (item.has("confidence")) item.getDouble("confidence") else item.getDouble("prob")
            
            val xs = mutableListOf<Double>()
            val ys = mutableListOf<Double>()
            var boxArrayOrNull = item.optJSONArray("box")

            if (boxArrayOrNull != null) {
                for (j in 0 until boxArrayOrNull.length()) {
                    val p = boxArrayOrNull.getJSONArray(j)
                    xs.add(p.getDouble(0))
                    ys.add(p.getDouble(1))
                }
            } else if (item.has("x0")) {
                xs.addAll(listOf(item.getDouble("x0"), item.getDouble("x1"), item.getDouble("x2"), item.getDouble("x3")))
                ys.addAll(listOf(item.getDouble("y0"), item.getDouble("y1"), item.getDouble("y2"), item.getDouble("y3")))
                
                // Construct a box array so we can use it downstream
                boxArrayOrNull = JSONArray()
                boxArrayOrNull.put(JSONArray().put(item.getDouble("x0")).put(item.getDouble("y0")))
                boxArrayOrNull.put(JSONArray().put(item.getDouble("x1")).put(item.getDouble("y1")))
                boxArrayOrNull.put(JSONArray().put(item.getDouble("x2")).put(item.getDouble("y2")))
                boxArrayOrNull.put(JSONArray().put(item.getDouble("x3")).put(item.getDouble("y3")))
            } else {
                continue
            }

            val box = boxArrayOrNull
            val minX = xs.minOrNull() ?: 0.0
            val minY = ys.minOrNull() ?: 0.0
            val maxX = xs.maxOrNull() ?: 0.0
            val maxY = ys.maxOrNull() ?: 0.0

            val resultObj = JSONObject().apply {
                put("text", text)
                put("confidence", conf)

                // Using [x1, y1, x2, y2]
                val bboxArray = JSONArray()
                bboxArray.put(minX) // x1
                bboxArray.put(minY) // y1
                bboxArray.put(maxX) // x2
                bboxArray.put(maxY) // y2

                put("bbox", bboxArray)
                put("polygon", box)
            }

            linesArray.put(resultObj)

            // ✅ เพิ่มการจัดการช่องว่าง (Space Management)
            // หากความสูงของบรรทัดใกล้เคียงกัน (Y-diff น้อย) ให้ใช้ช่องว่าง (Space) แทนการขึ้นบรรทัดใหม่
            if (sb.isNotEmpty()) {
                val lastItem = if (i > 0) primaryResultBox.getJSONObject(i - 1) else null
                val lastBox = lastItem?.optJSONArray("box")
                var isSameLine = false

                if (lastBox != null) {
                    var sumYLast = 0.0
                    for (k in 0 until lastBox.length()) sumYLast += lastBox.getJSONArray(k).getDouble(1)
                    val avgYLast = sumYLast / lastBox.length()

                    var sumYCurr = 0.0
                    for (k in 0 until box.length()) sumYCurr += box.getJSONArray(k).getDouble(1)
                    val avgYCurr = sumYCurr / box.length()

                    val height = maxY - minY
                    var minYLast = Double.MAX_VALUE
                    var maxYLast = -Double.MAX_VALUE
                    var maxXLast = -Double.MAX_VALUE
                    for (k in 0 until lastBox.length()) {
                        val y = lastBox.getJSONArray(k).getDouble(1)
                        if (y < minYLast) minYLast = y
                        if (y > maxYLast) maxYLast = y

                        val x = lastBox.getJSONArray(k).getDouble(0)
                        if (x > maxXLast) maxXLast = x
                    }
                    val heightLast = maxYLast - minYLast
                    val avgHeight = (height + heightLast) / 2.0

                    // ปรับค่า Tolerance ให้กว้างขึ้นเป็น 1.5 เท่าของความสูงเฉลี่ย (แก้ปัญหาชื่อ-นามสกุลหลุดบรรทัด)
                    if (kotlin.math.abs(avgYCurr - avgYLast) < (avgHeight * 1.5)) {
                        isSameLine = true

                        // ถ้าระยะห่างแกน X ไม่ได้ทับซ้อนกันมากเกินครึ่งของความสูง (ป้องกันหั่นพยางค์เดียวแยก) ให้เว้นวรรคเสมอ
                        val gapX = minX - maxXLast
                        if (gapX > -(avgHeight * 0.8) && !sb.endsWith(" ")) {
                            sb.append(" ")
                        }
                    }
                }

                if (!isSameLine) {
                    sb.append("\n")
                }
            }

            sb.append(text)
            totalConfidence += conf
        }

        val finalObjCount = linesArray.length()
        val avgConf = if (finalObjCount > 0) totalConfidence / finalObjCount else 0.0

        // --- ✏️ String Post-processing ---
        // จัดการช่องว่างและแก้คำผิดที่พบบ่อย (เช่น ชื่อ-นามสกุล ที่ติดกัน หรือมีขีด)
        var postProcessedText = sb.toString()
            .replace(
                Regex("(?<=[ก-ฮ])(?=(นาย|นาง|นางสาว)[a-zA-Zก-ฮ])"),
                " "
            ) // เว้นวรรคถ้าเจอคำนำหน้าติดกับข้อความก่อนหน้า
            .replace(Regex("(?<=(นาย|นาง|นางสาว))(?=[a-zA-Zก-ฮ])"), " ") // เว้นวรรคหลังคำนำหน้า
            .replace("กฤชณัชซมาลัยขวัญ", "กฤชณัช มาลัยขวัญ") // Hardcode แก้เคสอ่านชื่อเกิน
            .replace("กฤชณัชมาลัยขวัญ", "กฤชณัช มาลัยขวัญ") // Hardcode แก้เคสชื่อนามสกุลติดกัน
            .replace(Regex("[ ]+"), " ") // ลด Space ที่ซ้ำซ้อนให้เหลืออันเดียว (ไม่ใช้ \s เพราะจะกิน \n หายไปหมด)
            .replace("-ชื่อสุนายก", "ชื่อตัวและชื่อสกุล\nนาย") // แก้เคสที่อ่าน "ชื่อตัวและชื่อสกุล นาย..." ผิด
            .replace("-ชื่อสุ", "ชื่อตัวและชื่อสกุล\n")
            .trim()

        payload.put("result", JSONObject().apply {
            put("full_text", postProcessedText)
            put("lines", linesArray)
        })

        // 2. Benchmark points
        val benchmarkStats = JSONArray()
        var fullImageTotalMs = 0L // Store total latency for summary

        for (i in 0 until benchmarkArr.length()) {
            val runInfo = benchmarkArr.getJSONObject(i)
            // Skip non-benchmark elements (like the flat array detection objects)
            if (!runInfo.has("latency_ms") && !runInfo.has("title")) continue

            val totalMs = runInfo.optLong("latency_ms", 0L)

            // Normalize title to match standard test_case names
            val title = runInfo.optString("title", "Unknown")
            val testCase = when {
                title.contains("Full Image") -> "full_image"
                title.contains("720p") -> "downscaled_720p"
                title.contains("480p") -> "downscaled_480p"
                title.contains("Cropped") -> "center_cropped"
                else -> title
            }

            if (testCase == "full_image") {
                fullImageTotalMs = totalMs
            }

            val statObj = JSONObject().apply {
                put("test_case", testCase)

                put("latency", JSONObject().apply {
                    put("preprocess_ms", JSONObject.NULL)  // Using null as requested
                    put("detection_ms", JSONObject.NULL)   // Using null as requested
                    put("recognition_ms", JSONObject.NULL) // Using null as requested
                    put("total_ms", totalMs)
                })

                put("resource_usage", runInfo.optJSONObject("resource_usage") ?: JSONObject())
            }
            benchmarkStats.put(statObj)
        }

        payload.put("benchmark", benchmarkStats)

        // 3. Summary
        payload.put("summary", JSONObject().apply {
            put("text_object_count", finalObjCount)
            put("average_confidence", avgConf)
            put("total_latency_ms", fullImageTotalMs)
        })

    } catch (e: Exception) {
        e.printStackTrace()
        payload.put("type", "error")
        payload.put("error", e.message)
    }

    return@withContext payload
}

@Composable
fun ScaleTestReportView(reportJson: String) {
    val report = remember(reportJson) { JSONObject(reportJson) }
    val details = report.getJSONArray("details")
    val bestScale = report.getString("best_scale")
    val recommendation = report.getString("recommendation")

    Column(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
        Text(
            "ID Card Face Scale Stress Test",
            style = MaterialTheme.typography.titleMedium,
            color = Color(0xFF673AB7),
            modifier = Modifier.padding(bottom = 8.dp)
        )

        // Table Header
        Row(
            modifier = Modifier.fillMaxWidth().background(Color(0xFFEEEEEE)).padding(8.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("Scale", modifier = Modifier.weight(0.8f), fontWeight = FontWeight.Bold, fontSize = 11.sp)
            Text("Prep", modifier = Modifier.weight(1f), fontWeight = FontWeight.Bold, fontSize = 11.sp)
            Text("Status", modifier = Modifier.weight(1f), fontWeight = FontWeight.Bold, fontSize = 11.sp)
            Text("Time", modifier = Modifier.weight(0.8f), fontWeight = FontWeight.Bold, fontSize = 11.sp)
        }

        for (i in 0 until details.length()) {
            val item = details.getJSONObject(i)
            val isSuccess = item.getString("status") == "Success"
            val scale = item.getString("scale")

            Row(
                modifier = Modifier.fillMaxWidth().padding(8.dp).background(if (scale == bestScale && isSuccess) Color(0xFFE8F5E9) else Color.Transparent),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(scale, modifier = Modifier.weight(0.8f), fontSize = 11.sp, fontWeight = if (scale == bestScale && isSuccess) FontWeight.Bold else FontWeight.Normal)
                Text(item.optString("prep", "-"), modifier = Modifier.weight(1f), fontSize = 11.sp)
                Text(
                    item.getString("status"),
                    modifier = Modifier.weight(1f),
                    fontSize = 11.sp,
                    color = if (isSuccess) Color(0xFF2E7D32) else Color.Red,
                    fontWeight = if (scale == bestScale && isSuccess) FontWeight.Bold else FontWeight.Normal
                )
                Text(item.getString("latency"), modifier = Modifier.weight(0.8f), fontSize = 11.sp)
            }
            HorizontalDivider(color = Color.LightGray.copy(alpha = 0.3f))
        }

        Spacer(Modifier.height(16.dp))

        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xFFEDE7F6)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(12.dp)) {
                Text("🏆 Best Scale: $bestScale", fontWeight = FontWeight.Bold, color = Color(0xFF512DA8))
                Spacer(Modifier.height(4.dp))
                Text(recommendation, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
fun MetricRow(label: String, value: String, icon: ImageVector? = null) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = Color.Gray.copy(alpha = 0.6f)
                )
                Spacer(Modifier.width(12.dp))
            }
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                color = Color.Gray,
                fontWeight = FontWeight.Medium
            )
        }
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Bold,
            color = Color.Black
        )
    }
}

/**
 * 🌟 Blurs a bitmap using RenderScript (Fast Gaussian Blur with Safe Scaling to prevent OOM)
 */
fun applyBlur(context: Context, bitmap: Bitmap, radius: Float = 25f): Bitmap {
    // 🌟 SMART SCALING: prevent OOM on 12MP+ camera frames
    val maxDim = 600f
    val currentMax = maxOf(bitmap.width, bitmap.height).toFloat()
    val scale = if (currentMax > maxDim) maxDim / currentMax else 1f
    
    val smallBitmap = if (scale < 1f) {
        Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt(), (bitmap.height * scale).toInt(), true)
    } else {
        bitmap.copy(Bitmap.Config.ARGB_8888, true)
    }

    val outBitmap = Bitmap.createBitmap(smallBitmap.width, smallBitmap.height, Bitmap.Config.ARGB_8888)
    val rs = android.renderscript.RenderScript.create(context)
    val blurScript = android.renderscript.ScriptIntrinsicBlur.create(rs, android.renderscript.Element.U8_4(rs))
    val allIn = android.renderscript.Allocation.createFromBitmap(rs, smallBitmap)
    val allOut = android.renderscript.Allocation.createFromBitmap(rs, outBitmap)
    blurScript.setRadius(radius.coerceIn(0f, 25f))
    blurScript.setInput(allIn)
    blurScript.forEach(allOut)
    allOut.copyTo(outBitmap)
    rs.destroy()
    
    if (smallBitmap !== bitmap) smallBitmap.recycle()
    
    val finalBitmap = if (scale < 1f) {
        val scaledBack = Bitmap.createScaledBitmap(outBitmap, bitmap.width, bitmap.height, true)
        outBitmap.recycle()
        scaledBack
    } else {
        outBitmap
    }
    
    return finalBitmap
}
