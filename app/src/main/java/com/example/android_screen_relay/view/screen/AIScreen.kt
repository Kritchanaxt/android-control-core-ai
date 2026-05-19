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
import androidx.compose.runtime.collectAsState
import com.example.android_screen_relay.presenter.AiStateManager
import com.example.android_screen_relay.presenter.WatchdogStatus
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

    // States delegated to AiStateManager
    
    
    
    
    
    
    
    
    
    
    
    
    
    
    
    
    
    
    
    
    
    
    
    



class AiScreenStateWrapper(private val composeState: androidx.compose.runtime.State<com.example.android_screen_relay.presenter.AiState>) {
    var currentAiMode: AiMode
        get() = composeState.value.currentAiMode
        set(value) { AiStateManager.updateState { it.copy(currentAiMode = value) } }
    var isProcessing: Boolean
        get() = composeState.value.isProcessing
        set(value) { AiStateManager.updateState { it.copy(isProcessing = value) } }
    var showAiModeSheet: Boolean
        get() = composeState.value.showAiModeSheet
        set(value) { AiStateManager.updateState { it.copy(showAiModeSheet = value) } }
    var currentImage: Bitmap?
        get() = composeState.value.currentImage
        set(value) { AiStateManager.updateState { it.copy(currentImage = value) } }
    var leftPalmImage: Bitmap?
        get() = composeState.value.leftPalmImage
        set(value) { AiStateManager.updateState { it.copy(leftPalmImage = value) } }
    var rightPalmImage: Bitmap?
        get() = composeState.value.rightPalmImage
        set(value) { AiStateManager.updateState { it.copy(rightPalmImage = value) } }
    var ocrResultJson: String
        get() = composeState.value.ocrResultJson
        set(value) { AiStateManager.updateState { it.copy(ocrResultJson = value) } }
    var ocrTimeMs: Long
        get() = composeState.value.ocrTimeMs
        set(value) { AiStateManager.updateState { it.copy(ocrTimeMs = value) } }
    var computeMode: ComputeMode
        get() = composeState.value.computeMode
        set(value) { AiStateManager.updateState { it.copy(computeMode = value) } }
    var targetHand: String
        get() = composeState.value.targetHand
        set(value) { AiStateManager.updateState { it.copy(targetHand = value) } }
    var targetFaceMode: String
        get() = composeState.value.targetFaceMode
        set(value) { AiStateManager.updateState { it.copy(targetFaceMode = value) } }
    var zoomScale: Float
        get() = composeState.value.zoomScale
        set(value) { AiStateManager.updateState { it.copy(zoomScale = value) } }
    var useCropMode: Boolean
        get() = composeState.value.useCropMode
        set(value) { AiStateManager.updateState { it.copy(useCropMode = value) } }
    var selectedResolution: android.util.Size?
        get() = composeState.value.selectedResolution
        set(value) { AiStateManager.updateState { it.copy(selectedResolution = value) } }
    var availableResolutions: List<android.util.Size>
        get() = composeState.value.availableResolutions
        set(value) { AiStateManager.updateState { it.copy(availableResolutions = value) } }
    var selectedCameraId: String
        get() = composeState.value.selectedCameraId
        set(value) { AiStateManager.updateState { it.copy(selectedCameraId = value) } }
    var selectedAspectRatio: UiAspectRatio
        get() = composeState.value.selectedAspectRatio
        set(value) { AiStateManager.updateState { it.copy(selectedAspectRatio = value) } }
    var cropImage: Bitmap?
        get() = composeState.value.cropImage
        set(value) { AiStateManager.updateState { it.copy(cropImage = value) } }
    var processingResultMsg: String?
        get() = composeState.value.processingResultMsg
        set(value) { AiStateManager.updateState { it.copy(processingResultMsg = value) } }
    var horizontalFlip: Boolean
        get() = composeState.value.horizontalFlip
        set(value) { AiStateManager.updateState { it.copy(horizontalFlip = value) } }
    var verticalFlip: Boolean
        get() = composeState.value.verticalFlip
        set(value) { AiStateManager.updateState { it.copy(verticalFlip = value) } }
    var selfieOutputType: String
        get() = composeState.value.selfieOutputType
        set(value) { AiStateManager.updateState { it.copy(selfieOutputType = value) } }
    var selfieSelectClass: String
        get() = composeState.value.selfieSelectClass
        set(value) { AiStateManager.updateState { it.copy(selfieSelectClass = value) } }
    var isIdCardMode: Boolean
        get() = composeState.value.isIdCardMode
        set(value) { AiStateManager.updateState { it.copy(isIdCardMode = value) } }
    var selectedOcrModel: String
        get() = composeState.value.selectedOcrModel
        set(value) { AiStateManager.updateState { it.copy(selectedOcrModel = value) } }
    var autoFramingEnabled: Boolean
        get() = composeState.value.autoFramingEnabled
        set(value) { AiStateManager.updateState { it.copy(autoFramingEnabled = value) } }
    val watchdogMessage: String?
        get() = composeState.value.watchdogMessage
    val watchdogStatus: WatchdogStatus
        get() = composeState.value.watchdogStatus
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AIScreenLayout() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val composeState = AiStateManager.state.collectAsState()
    val trigger = composeState.value
    val wrapper = remember { AiScreenStateWrapper(composeState) }
    with(wrapper) {


    LaunchedEffect(currentAiMode, selectedOcrModel) {
        if ((currentAiMode == AiMode.PADDLE_OCR || currentAiMode == AiMode.TESSERACT_FAST_OCR || currentAiMode == AiMode.IDENTITY_VERIFICATION) && selectedOcrModel.isEmpty()) {
            // Wait for user to select OCR model
        } else {
            isProcessing = true
            withContext(Dispatchers.Default) {
                val modeToSwitch = currentAiMode.name

                AIManager.switchProcessor(context, modeToSwitch, mapOf("ocr_engine" to selectedOcrModel))
            }
            isProcessing = false
        }
        showAiModeSheet = false
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

    Box(modifier = Modifier.fillMaxSize()) {
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
                    scanRes = "${selectedResolution?.width ?: 0}x${selectedResolution?.height ?: 0}",
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
            selectedOcrModel = selectedOcrModel,
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
                onAiModeChange = { 
                    if (it == AiMode.IDENTITY_VERIFICATION) {
                        selectedOcrModel = "" // Reset model selection for Identity Verification
                    }
                    currentAiMode = it 
                },
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
                selfieOutputType = selfieOutputType, selfieSelectClass = selfieSelectClass, onSelfieOutputTypeChange = { selfieOutputType = it }, onSelfieSelectClassChange = { selfieSelectClass = it }, selectedOcrModel = selectedOcrModel,
                onSelectedOcrModelChange = { selectedOcrModel = it },
                isIdCardMode = isIdCardMode,
                onIsIdCardModeChange = { isIdCardMode = it },
                autoFramingEnabled = autoFramingEnabled,
                onAutoFramingEnabledChange = { autoFramingEnabled = it },
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
                                        com.example.android_screen_relay.core.safeCreateScaledBitmap(bitmap, (bitmap.width * scale).toInt(), (bitmap.height * scale).toInt(), false)
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
                    else if (currentAiMode == AiMode.PADDLE_OCR || currentAiMode == AiMode.TESSERACT_FAST_OCR || currentAiMode == AiMode.IDENTITY_VERIFICATION) {
                        try {
                            val scale = 720f / maxOf(bitmap.width, bitmap.height)
                            val scaled = if (scale < 1f) {
                                com.example.android_screen_relay.core.safeCreateScaledBitmap(bitmap, (bitmap.width * scale).toInt(), (bitmap.height * scale).toInt(), false)
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

                                val foundId = if (currentAiMode == AiMode.TESSERACT_FAST_OCR || currentAiMode == AiMode.PADDLE_OCR || currentAiMode == AiMode.IDENTITY_VERIFICATION) {
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
                                com.example.android_screen_relay.core.safeCreateScaledBitmap(bitmap, (bitmap.width * aiScale).toInt(), (bitmap.height * aiScale).toInt(), false)
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
                    else if (currentAiMode == AiMode.IDENTITY_VERIFICATION) {
                        // Sequential Pipeline: Bypass realtime AI to save memory and avoid "flicker"
                        // Return true with a dummy item to trigger the 2s countdown
                        Pair(true, listOf(AIDetectedItem("STABILIZING", 1f, android.graphics.RectF(), emptyMap())))
                    }
                    // 4. Special Handling for Face Detection (Rules: Center, Size, Angle)
                    else if (currentAiMode == AiMode.FACE_DETECTION) {
                        try {
                            val aiScale = 720f / maxOf(bitmap.width, bitmap.height).coerceAtLeast(1)
                            val aiBitmap = if (aiScale < 1f) {
                                com.example.android_screen_relay.core.safeCreateScaledBitmap(bitmap, (bitmap.width * aiScale).toInt(), (bitmap.height * aiScale).toInt(), false)
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
                                com.example.android_screen_relay.core.safeCreateScaledBitmap(bitmap, (bitmap.width * aiScale).toInt(), (bitmap.height * aiScale).toInt(), false)
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
                    // 6. Verified Auto Capture -> specialized state-machine processors
                    else if (currentAiMode == AiMode.VERIFIED_AUTO_CAPTURE) {
                        try {
                                val aiScale = 720f / maxOf(bitmap.width, bitmap.height).coerceAtLeast(1)
                                val aiBitmap = if (aiScale < 1f) {
                                    com.example.android_screen_relay.core.safeCreateScaledBitmap(bitmap, (bitmap.width * aiScale).toInt(), (bitmap.height * aiScale).toInt(), false)
                                } else bitmap
    
                                val result = AIManager.process(aiBitmap, options)
                                if (aiBitmap !== bitmap) aiBitmap.recycle()

                            // 🌟 KEY FIX: If the processor returned success=false (e.g. HAND_DETECTED, 
                            // FACE_NOT_FOUND, or stability not yet met), we REJECT this frame.
                            // This ensures the Pose hand filter actually works.
                            if (result != null && result.success && result.items.isNotEmpty()) {
                                val face = result.items.first()
                                val box = face.boundingBox

                                // Map back to original resolution for UI overlay drawing
                                val originalBox = if (aiScale < 1f) {
                                    android.graphics.RectF(box.left / aiScale, box.top / aiScale, box.right / aiScale, box.bottom / aiScale)
                                } else box

                                Pair(true, listOf(face.copy(boundingBox = originalBox)))
                            } else {
                                // Processor rejected: hand detected, face not found, angles bad, etc.
                                Pair(false, emptyList())
                            }
                        } catch (e: Throwable) { Pair(false, emptyList()) }
                    }
                    // 7. Multi-Class Selfie Segmentation (MediaPipe ImageSegmenter)
                    else if (currentAiMode == AiMode.MULTI_CLASS_SELFIE_SEGMENTATION || currentAiMode == AiMode.VERIFICATION_SEGMENTATION) {
                        try {
                            val aiScale = 720f / maxOf(bitmap.width, bitmap.height).coerceAtLeast(1)
                            val aiBitmap = if (aiScale < 1f) {
                                com.example.android_screen_relay.core.safeCreateScaledBitmap(bitmap, (bitmap.width * aiScale).toInt(), (bitmap.height * aiScale).toInt(), false)
                            } else bitmap

                            // Pass output type and selected class to the processor
                            val multiClassOptions = options + mapOf(
                                "output_type" to selfieOutputType,
                                "select_class" to selfieSelectClass,
                                "is_id_card_mode" to isIdCardMode
                            )

                            val result = AIManager.process(aiBitmap, multiClassOptions)
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
                            } else {
                                Pair(false, emptyList())
                            }
                        } catch (e: Throwable) { Pair(false, emptyList()) }
                    }
                    // 8. General Handling for other modes (POSE, etc.)
                    else {
                        try {
                            // 🌟 Smart Scaling for AI: ML Kit works best with reasonable sizes (e.g. max 720px)
                            // This prevents ML Kit from failing or being too slow on high-res bitmaps
                            val aiScale = 720f / maxOf(bitmap.width, bitmap.height).coerceAtLeast(1)
                            val aiBitmap = if (aiScale < 1f) {
                                com.example.android_screen_relay.core.safeCreateScaledBitmap(bitmap, (bitmap.width * aiScale).toInt(), (bitmap.height * aiScale).toInt(), false)
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
                                            val scaled = com.example.android_screen_relay.core.safeCreateScaledBitmap(resultBmp, (resultBmp.width * zoomScale).toInt(), (resultBmp.height * zoomScale).toInt(), true)
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
                                                val scaled = com.example.android_screen_relay.core.safeCreateScaledBitmap(cropped, (width * zoomScale).toInt(), (height * zoomScale).toInt(), true)
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
                    } else if (currentAiMode == AiMode.FACE_DETECTION || currentAiMode == AiMode.VERIFIED_AUTO_CAPTURE) {
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
                                    
                                    processingBitmap = com.example.android_screen_relay.core.safeCreateScaledBitmap(faceCrop, (roiWidth * scaleLimit).toInt(), (roiHeight * scaleLimit).toInt(), true)
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
                                        processingBitmap = com.example.android_screen_relay.core.safeCreateScaledBitmap(bitmap, (bitmap.width * scaleFactor).toInt(), (bitmap.height * scaleFactor).toInt(), true)
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
                                            val scaled = com.example.android_screen_relay.core.safeCreateScaledBitmap(finalResult, (finalResult.width * zoomScale).toInt(), (finalResult.height * zoomScale).toInt(), true)
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
                    } else if (currentAiMode == AiMode.MULTI_CLASS_SELFIE_SEGMENTATION || currentAiMode == AiMode.VERIFICATION_SEGMENTATION) {
                        isProcessing = true
                        scope.launch(Dispatchers.Default) {
                            try {
                                val pbStartMs = System.currentTimeMillis()
                                val multiClassOptions = mapOf(
                                    "is_front" to isFront,
                                    "output_type" to selfieOutputType,
                                    "select_class" to selfieSelectClass,
                                    "is_snap" to true,
                                    "is_id_card_mode" to isIdCardMode
                                )
                                val result = AIManager.runWithProcessor { proc ->
                                    proc?.process(bitmap, multiClassOptions)
                                } ?: AIResult(false, emptyList(), 0, "Processor unavailable")
                                val pbElapsedMs = System.currentTimeMillis() - pbStartMs

                                val item = result.items.firstOrNull()
                                val maskBitmap = item?.extra?.get("mask_bitmap") as? Bitmap
                                val bbox = item?.boundingBox
                                val handsSubtracted = item?.extra?.get("hands_subtracted") as? Int ?: 0

                                Log.d("VerificationSeg", "Snap result: success=${result.success}, items=${result.items.size}, maskSize=${maskBitmap?.width}x${maskBitmap?.height}, hands_subtracted=$handsSubtracted, elapsed=${pbElapsedMs}ms")

                                val finalImage = if (useCropMode && maskBitmap != null && bbox != null) {
                                    // 1. Create a bitmap for the extracted foreground
                                    val foreground = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
                                    val canvas = android.graphics.Canvas(foreground)
                                    
                                    // 2. Scale mask to match original bitmap size
                                    val scaledMask = com.example.android_screen_relay.core.safeCreateScaledBitmap(maskBitmap, bitmap.width, bitmap.height, true)
                                    
                                    // 3. Draw original bitmap using mask as alpha
                                    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
                                    canvas.drawBitmap(bitmap, 0f, 0f, paint)
                                    
                                    // Use Xfermode to keep only pixels where mask is present
                                    paint.xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.DST_IN)
                                    canvas.drawBitmap(scaledMask, 0f, 0f, paint)
                                    
                                    scaledMask.recycle()

                                    // 4. Crop to Bounding Box
                                    val pad = 20
                                    val left = (bbox.left - pad).toInt().coerceAtLeast(0)
                                    val top = (bbox.top - pad).toInt().coerceAtLeast(0)
                                    val right = (bbox.right + pad).toInt().coerceAtMost(bitmap.width)
                                    val bottom = (bbox.bottom + pad).toInt().coerceAtMost(bitmap.height)
                                    val w = (right - left).coerceAtLeast(1)
                                    val h = (bottom - top).coerceAtLeast(1)

                                    val cropped = Bitmap.createBitmap(foreground, left, top, w, h)
                                    foreground.recycle()
                                    cropped
                                } else {
                                    bitmap.copy(Bitmap.Config.ARGB_8888, true)
                                }

                                withContext(Dispatchers.Main) {
                                    ocrTimeMs = pbElapsedMs
                                    ocrResultJson = "[\n  {\n    \"label\": \"Verification Segmentation\",\n    \"confidence\": 1.0,\n    \"hands_subtracted\": $handsSubtracted\n  }\n]"
                                    currentImage = finalImage
                                    if (!bitmap.isRecycled) bitmap.recycle()
                                }

                                val payload = generateOCRPayload(context, finalImage, ocrResultJson, ocrTimeMs, currentAiMode)
                                RelayService.getInstance()?.broadcastMessage(payload.toString())

                                // Free mask bitmap
                                maskBitmap?.recycle()

                                withContext(Dispatchers.Main) { isProcessing = false }
                            } catch (e: Exception) {
                                Log.e("MultiSelfie", "Extraction error", e)
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

                                    val scaledMask = com.example.android_screen_relay.core.safeCreateScaledBitmap(maskBitmap, bitmap.width, bitmap.height, true)
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

                                // 🌟 FIX: Free memory from intermediate ML Kit Subject bitmaps (all items)
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
                    } else if (currentAiMode == AiMode.VERIFIED_AUTO_CAPTURE) {
                        isProcessing = true
                        scope.launch(Dispatchers.Default) {
                            try {
                                val pbStartMs = System.currentTimeMillis()
                                val result = AIManager.runWithProcessor { proc ->
                                    proc?.process(bitmap, mapOf("is_front" to isFront))
                                } ?: com.example.android_screen_relay.core.AIResult(false, emptyList(), 0, "Processor unavailable")
                                val pbElapsedMs = System.currentTimeMillis() - pbStartMs

                                val item = result.items.firstOrNull()
                                val jsonStr = if (item != null) {
                                    val arr = org.json.JSONArray()
                                    val obj = org.json.JSONObject()
                                    obj.put("label", item.label)
                                    obj.put("confidence", item.confidence)
                                    obj.put("verification_metrics", org.json.JSONObject(item.extra["verification_metrics"] as? String ?: "{}"))
                                    obj.put("id_info", item.extra["id_info"])
                                    obj.put("name_info", item.extra["name_info"])
                                    obj.put("dob_info", item.extra["dob_info"])
                                    obj.put("text", item.extra["text"])
                                    arr.put(obj)
                                    arr.toString()
                                } else "[]"

                                val faceOnCard = item?.extra?.get("card_face_bitmap") as? Bitmap
                                val facePerson = item?.extra?.get("face_bitmap") as? Bitmap
                                
                                val finalDisplayImage = facePerson ?: bitmap.copy(Bitmap.Config.ARGB_8888, true)

                                withContext(Dispatchers.Main) {
                                    ocrTimeMs = pbElapsedMs
                                    ocrResultJson = jsonStr
                                    currentImage = finalDisplayImage
                                    if (!bitmap.isRecycled && currentImage !== bitmap) bitmap.recycle()
                                    isProcessing = false
                                }

                                val payload = generateOCRPayload(context, finalDisplayImage, jsonStr, pbElapsedMs, currentAiMode)
                                RelayService.getInstance()?.broadcastMessage(payload.toString())
                            } catch (e: Exception) {
                                Log.e("Verification", "Final capture error", e)
                                withContext(Dispatchers.Main) { isProcessing = false }
                            }
                        }
                    } else {
                        isProcessing = true
                        scope.launch(Dispatchers.Default) {
                            try {
                                if ((currentAiMode == AiMode.PADDLE_OCR || currentAiMode == AiMode.TESSERACT_FAST_OCR || currentAiMode == AiMode.IDENTITY_VERIFICATION) && selectedOcrModel.isEmpty()) {
                                    withContext(Dispatchers.Main) { isProcessing = false }
                                    return@launch
                                }
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

                                // บังคับตัดภาพคงที่ (Fixed Crop) 
                                // พิกัดสำหรับ Identity Verification (เน้นกว้างเพื่อให้เห็นทั้งข้อมูลและใบหน้า)
                                val calculatedRect = if (currentAiMode == AiMode.IDENTITY_VERIFICATION) {
                                    androidx.compose.ui.geometry.Rect(
                                        left = 0.28f,
                                        top = 0.10f,
                                        right = 0.99f,
                                        bottom = 0.90f
                                    )
                                } else {
                                    // พิกัดเดิมสำหรับโหมด OCR อื่นๆ (เน้นตัดเฉพาะส่วนตัวหนังสือ)
                                    androidx.compose.ui.geometry.Rect(
                                        left = 0.28f,
                                        top = 0.10f,
                                        right = 0.99f,
                                        bottom = 0.62f
                                    )
                                }

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
                                        val scaled = com.example.android_screen_relay.core.safeCreateScaledBitmap(initialCrop, (safeW * zoomScale).toInt(), (safeH * zoomScale).toInt(), true)
                                        if (initialCrop !== bitmap) initialCrop.recycle()
                                        scaled
                                    } else initialCrop
                                } else {
                                    // Full Image Mode: Use original bitmap but still respect zoom if applicable
                                    if (!useCropMode && zoomScale > 1.0f) {
                                        com.example.android_screen_relay.core.safeCreateScaledBitmap(bitmap, (bitmap.width * zoomScale).toInt(), (bitmap.height * zoomScale).toInt(), true)
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
                                    if (currentAiMode == AiMode.IDENTITY_VERIFICATION) {
                                        // Sequential Logic for Identity Verification: Snap -> OCR -> Face
                                        val options = mapOf("ocr_engine" to selectedOcrModel)
                                        val aiResult = AIManager.process(optimizedCrop, options)
                                        
                                        if (aiResult != null && aiResult.success) {
                                            // Convert AIResult items to JSON format expected by OCRResultScreen
                                            val jsonArray = JSONArray()
                                            aiResult.items.forEach { item ->
                                                val obj = org.json.JSONObject()
                                                obj.put("label", item.label)
                                                obj.put("prob", item.confidence.toDouble())
                                                
                                                if (item.label == "Face") {
                                                    val b = item.boundingBox
                                                    val bArr = JSONArray()
                                                    bArr.put(b.left.toDouble()); bArr.put(b.top.toDouble())
                                                    bArr.put(b.right.toDouble()); bArr.put(b.bottom.toDouble())
                                                    obj.put("bbox", bArr)
                                                    
                                                    // Pass contours if available
                                                    (item.extra["contours"] as? org.json.JSONObject)?.let {
                                                        obj.put("contours", it)
                                                    }
                                                } else {
                                                    // Standard OCR format
                                                    obj.put("x0", item.boundingBox.left.toDouble())
                                                    obj.put("y0", item.boundingBox.top.toDouble())
                                                    obj.put("x1", item.boundingBox.right.toDouble())
                                                    obj.put("y1", item.boundingBox.top.toDouble())
                                                    obj.put("x2", item.boundingBox.right.toDouble())
                                                    obj.put("y2", item.boundingBox.bottom.toDouble())
                                                    obj.put("x3", item.boundingBox.left.toDouble())
                                                    obj.put("y3", item.boundingBox.bottom.toDouble())
                                                }
                                                jsonArray.put(obj)
                                            }
                                            
                                            finalJsonStr = OCRFormatter.formatLabelsInJsonArray(jsonArray.toString())
                                            finalLatencyMs = aiResult.processTimeMs
                                            isSuccess = true
                                        }
                                    } else {
                                        // Existing separate processor logic
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
        
        watchdogMessage?.let { msg ->
            WatchdogBanner(msg, watchdogStatus)
        }
    }
}
}

@Composable
fun WatchdogBanner(message: String, status: WatchdogStatus) {
    val bgColor = when (status) {
        WatchdogStatus.NORMAL -> androidx.compose.ui.graphics.Color(0xFF4CAF50)
        WatchdogStatus.WARNING -> androidx.compose.ui.graphics.Color(0xFFFFA000)
        WatchdogStatus.CRITICAL -> androidx.compose.ui.graphics.Color(0xFFD32F2F)
    }
    
    Surface(
        modifier = androidx.compose.ui.Modifier
            .fillMaxWidth()
            .padding(16.dp)
            .statusBarsPadding(),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
        color = bgColor,
        shadowElevation = 4.dp
    ) {
        androidx.compose.foundation.layout.Row(
            modifier = androidx.compose.ui.Modifier.padding(12.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
        ) {
            Icon(
                imageVector = if (status == WatchdogStatus.NORMAL) Icons.Default.CheckCircle else Icons.Default.Warning,
                contentDescription = null,
                tint = androidx.compose.ui.graphics.Color.White
            )
            androidx.compose.foundation.layout.Spacer(modifier = androidx.compose.ui.Modifier.width(8.dp))
            Text(
                text = message,
                color = androidx.compose.ui.graphics.Color.White,
                style = androidx.compose.material3.MaterialTheme.typography.bodyMedium
            )
        }
    }
}
