package com.example.android_screen_relay.core

import com.example.android_screen_relay.presenter.AiStateManager
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

    // Performance monitoring states delegated to AiStateManager
    
    
    
    
    
    
    







class CameraStateWrapper(private val composeState: androidx.compose.runtime.State<com.example.android_screen_relay.presenter.AiState>) {
    var fps: Int
        get() = composeState.value.fps
        set(value) { AiStateManager.updateState { it.copy(fps = value) } }
    var detectorLatency: Long
        get() = composeState.value.detectorLatency
        set(value) { AiStateManager.updateState { it.copy(detectorLatency = value) } }
    var frameLatency: Long
        get() = composeState.value.frameLatency
        set(value) { AiStateManager.updateState { it.copy(frameLatency = value) } }
    var ramUsed: Long
        get() = composeState.value.ramUsed
        set(value) { AiStateManager.updateState { it.copy(ramUsed = value) } }
    var ramTotal: Long
        get() = composeState.value.ramTotal
        set(value) { AiStateManager.updateState { it.copy(ramTotal = value) } }
    var freeRamMb: Long
        get() = composeState.value.freeRamMb
        set(value) { AiStateManager.updateState { it.copy(freeRamMb = value) } }
    var cpuUsage: String
        get() = composeState.value.cpuUsage
        set(value) { AiStateManager.updateState { it.copy(cpuUsage = value) } }
    var isCapturing: Boolean
    get() = composeState.value.isCapturing
    set(value) { AiStateManager.updateState { it.copy(isCapturing = value) } }
    var isFrontCamera: Boolean
    get() = composeState.value.isFrontCamera
    set(value) { AiStateManager.updateState { it.copy(isFrontCamera = value) } }
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
    onVerticalFlipChange: (Boolean) -> Unit = {},
    selfieOutputType: String, selfieSelectClass: String, onSelfieOutputTypeChange: (String) -> Unit, onSelfieSelectClassChange: (String) -> Unit, selectedOcrModel: String,
    onSelectedOcrModelChange: (String) -> Unit,
    isIdCardMode: Boolean,
    onIsIdCardModeChange: (Boolean) -> Unit,
    autoFramingEnabled: Boolean = false,
    onAutoFramingEnabledChange: (Boolean) -> Unit = {}
) {
    val context = LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var cameraController by remember { mutableStateOf<Camera2Controller?>(null) }

    // State for Settings
    val availableCameras = remember {
        val tempController = Camera2Controller(context) { _, _ -> }
        val list = tempController.enumerateCameras()
        tempController.close()
        list
    }

    var showSettingsDialog by remember { mutableStateOf(false) }

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


    var showOutputTypeMenu by remember { mutableStateOf(false) }
    var showSelectClassMenu by remember { mutableStateOf(false) }


    val composeState = AiStateManager.state.collectAsState()
    val trigger = composeState.value
    val wrapper = remember(composeState) { CameraStateWrapper(composeState) }
    with(wrapper) {

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

    // 🌟 Auto-framing (Center Stage) sync
    LaunchedEffect(autoFramingEnabled) {
        cameraController?.setAutoFraming(autoFramingEnabled)
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

                // 🌟 ML KIT VISION QUICKSTART OPTIMIZATION: 
                // Skip frame if AI is still processing the previous one. 
                // This prevents 'Memory Bloat' (~200MB/s) from redundant Bitmap creations.
                if (AIManager.isBusy()) {
                    kotlinx.coroutines.delay(10)
                    continue
                }

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
                            com.example.android_screen_relay.core.safeCreateScaledBitmap(rawBitmap, (rawBitmap.width * scale).toInt(), (rawBitmap.height * scale).toInt(), true).also {
                                rawBitmap.recycle()
                            }
                        } else {
                            rawBitmap
                        }

                        // 🌟 CROP MODE: If enabled, crop to the centered frame area
                        // 🌟 FIX: Skip crop for VERIFIED_AUTO_CAPTURE — Pose Detection needs full body visible
                        val bitmap = if (useCropMode && aiMode != AiMode.VERIFIED_AUTO_CAPTURE && aiMode != AiMode.MULTI_CLASS_SELFIE_SEGMENTATION && aiMode != AiMode.VERIFICATION_SEGMENTATION) {
                            val cw = baseBitmap.width.toFloat()
                            val ch = baseBitmap.height.toFloat()

                            val frameW = if (aiMode == AiMode.PADDLE_OCR || aiMode == AiMode.TESSERACT_FAST_OCR || aiMode == AiMode.IDENTITY_VERIFICATION) {
                                val maxW = cw * 0.9f
                                val idealH = ch * 0.6f
                                if (idealH * 1.58f > maxW) maxW else idealH * 1.58f
                            } else if (aiMode == AiMode.FACE_DETECTION) {
                                min(cw, ch) * 0.8f
                            } else {
                                min(cw, ch) * 0.8f // Use 0.8f for better Subject/Selfie visibility
                            }
                            val frameH = if (aiMode == AiMode.PADDLE_OCR || aiMode == AiMode.TESSERACT_FAST_OCR || aiMode == AiMode.IDENTITY_VERIFICATION) frameW / 1.58f else frameW
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
                        // 🌟 FIX: Only for FACE_DETECTION normal mode. Removed VERIFIED_AUTO_CAPTURE cleanup.
                        // because blur prevents Pose Detection from seeing the body for hand detection.
                        if (aiMode == AiMode.FACE_DETECTION && targetFaceMode == "normal" && !isUltraLowRAM && !memoryInfo.lowMemory) {
                            try {
                                val scale = 0.05f
                                val blurW = (bitmap.width * scale).toInt().coerceAtLeast(1)
                                val blurH = (bitmap.height * scale).toInt().coerceAtLeast(1)

                                val tiny = com.example.android_screen_relay.core.safeCreateScaledBitmap(bitmap, blurW, blurH, true)

                                if (tiny != null && !tiny.isRecycled) {
                                    withContext(Dispatchers.Main) {
                                        val oldBlur = blurredFrame.value
                                        // 🌟 ALWAYS create new bitmap to trigger Compose recompose
                                        // createScaledBitmap with filtering=true provides a fast blur effect
                                        val newBlurred = com.example.android_screen_relay.core.safeCreateScaledBitmap(tiny, bitmap.width, bitmap.height, true)
                                        
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

                        // 🌟 FIX: Explicitly recycle old bitmaps before assigning new ones to prevent Native memory leak
                        val oldItems = when (aiMode) {
                            AiMode.SELFIE_SEGMENTATION, AiMode.MULTI_CLASS_SELFIE_SEGMENTATION, AiMode.VERIFICATION_SEGMENTATION -> latestItemsSelfie.value
                            AiMode.SUBJECT_SEGMENTATION -> latestItemsSubject.value
                            else -> null
                        }
                        val oldItemsToRecycle = oldItems
                        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default).launch {
                            kotlinx.coroutines.delay(150) // Wait for UI to finish rendering the old frame
                            oldItemsToRecycle?.forEach { oldItem ->
                                (oldItem.extra["mask_bitmap"] as? Bitmap)?.let { if (!it.isRecycled) it.recycle() }
                                (oldItem.extra["subject_bitmap"] as? Bitmap)?.let { if (!it.isRecycled) it.recycle() }
                                (oldItem.extra["combined_subject_bitmap"] as? Bitmap)?.let { if (!it.isRecycled) it.recycle() }
                            }
                        }

                        // Map items to preview states for drawing using .value
                        when (aiMode) {
                            AiMode.PADDLE_OCR, AiMode.IDENTITY_VERIFICATION -> latestItemsOcr.value = items
                            AiMode.HAND_DETECTION -> latestItemsPalm.value = items
                            AiMode.FACE_DETECTION -> latestItemsFace.value = items
                            AiMode.POSE_DETECTION -> latestItemsPose.value = items
                            AiMode.SELFIE_SEGMENTATION, AiMode.MULTI_CLASS_SELFIE_SEGMENTATION, AiMode.VERIFICATION_SEGMENTATION -> {
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

                        var criteriaMet = if (aiMode == AiMode.PADDLE_OCR || aiMode == AiMode.TESSERACT_FAST_OCR || aiMode == AiMode.IDENTITY_VERIFICATION || aiMode == AiMode.TEXT_RECOGNITION || aiMode == AiMode.HAND_DETECTION) {
                            success
                        } else if (aiMode == AiMode.MULTI_CLASS_SELFIE_SEGMENTATION || aiMode == AiMode.VERIFICATION_SEGMENTATION || aiMode == AiMode.SELFIE_SEGMENTATION || aiMode == AiMode.SUBJECT_SEGMENTATION) {
                            items.isNotEmpty() && items.any { item -> 
                                val w = item.boundingBox.width()
                                val h = item.boundingBox.height()
                                // Require bounding box to be at least 15% of the frame width and height to avoid false positives on noise/black screens
                                w > bitmapWidth * 0.15f && h > bitmapHeight * 0.15f
                            }
                        } else {
                            items.isNotEmpty()
                        }

                        // 🌟 MULTI-SCALE INFERENCE: If initial crop failed, try a 20% larger crop
                        if (!criteriaMet && useCropMode && aiMode != AiMode.MULTI_CLASS_SELFIE_SEGMENTATION && aiMode != AiMode.VERIFICATION_SEGMENTATION && aiMode != AiMode.SELFIE_SEGMENTATION && aiMode != AiMode.OBJECT_DETECTION && aiMode != AiMode.CUSTOM_OBJECT_DETECTION) {
                            val expandedBitmap = try {
                                val cw = baseBitmap.width.toFloat()
                                val ch = baseBitmap.height.toFloat()
                                // Expand frame by 20%
                                val expansion = 1.20f
                                val frameW = (if (aiMode == AiMode.PADDLE_OCR || aiMode == AiMode.TESSERACT_FAST_OCR || aiMode == AiMode.IDENTITY_VERIFICATION) {
                                    val maxW = cw * 0.9f
                                    val idealH = ch * 0.6f
                                    if (idealH * 1.58f > maxW) maxW else idealH * 1.58f
                                } else if (aiMode == AiMode.FACE_DETECTION) {
                                    min(cw, ch) * 0.8f
                                } else {
                                    min(cw, ch) * 0.6f
                                }) * expansion

                                val frameH = (if (aiMode == AiMode.PADDLE_OCR || aiMode == AiMode.TESSERACT_FAST_OCR || aiMode == AiMode.IDENTITY_VERIFICATION) frameW / (1.58f * expansion) else frameW) * expansion
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
                                            inputSize = "${expandedBitmap.width}x${expandedBitmap.height} (Expanded)",
                                            scanRes = "${selectedResolution?.width ?: 0}x${selectedResolution?.height ?: 0}"
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
                                inputSize = "${bitmap.width}x${bitmap.height}",
                                scanRes = "${selectedResolution?.width ?: 0}x${selectedResolution?.height ?: 0}",
                                fps = fps,
                                frameLatency = frameLatency,
                                detectorLatency = detectorLatency
                            )
                        }

                        var passedToCapture = false
                        // Disable Auto-Snap for specific preview-only modes as requested
                        // POSE_DETECTION removed to allow T+2 Capture
                        val isPreviewOnlyMode = aiMode == AiMode.OBJECT_DETECTION ||
                                                aiMode == AiMode.CUSTOM_OBJECT_DETECTION

                        if (criteriaMet && !isPreviewOnlyMode) {
                            val elapsedSinceStart = System.currentTimeMillis() - iterationStart
                            stableTime += elapsedSinceStart

                            // Rule 4: Buffer at 2s (T+2) for all modes except Object Detect
                            if (aiMode != AiMode.OBJECT_DETECTION && aiMode != AiMode.CUSTOM_OBJECT_DETECTION && stableTime >= 2000L) {
                                if (faceBuffer2s == null) {
                                    faceBuffer2s = bitmap.copy(Bitmap.Config.ARGB_8888, true)
                                }
                            }

                            val targetStableTime = when(aiMode) {
                                AiMode.OBJECT_DETECTION, AiMode.CUSTOM_OBJECT_DETECTION -> 500L // Excluded from T+2
                                AiMode.IDENTITY_VERIFICATION -> 2000L // User explicitly asked for 2nd second
                                else -> 3000L // Standard T+2 rule: 3s stability
                            }

                            if (stableTime >= targetStableTime) {
                                isCapturing = true
                                isPreviewPaused = true
                                passedToCapture = true

                                var captureBitmap = if (faceBuffer2s != null) {
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

                    if (aiMode == AiMode.PADDLE_OCR || aiMode == AiMode.TESSERACT_FAST_OCR || aiMode == AiMode.IDENTITY_VERIFICATION || aiMode == AiMode.FACE_DETECTION || aiMode == AiMode.HAND_DETECTION || aiMode == AiMode.VERIFIED_AUTO_CAPTURE) {
                        // OCR matches bounds, PALMPRINT uses a smaller centered box
                        val frameW = if (aiMode == AiMode.PADDLE_OCR || aiMode == AiMode.TESSERACT_FAST_OCR || aiMode == AiMode.IDENTITY_VERIFICATION) {
                            val maxW = cw * 0.9f
                            val idealH = ch * 0.6f
                            if (idealH * 1.58f > maxW) maxW else idealH * 1.58f
                        } else if (aiMode == AiMode.FACE_DETECTION || aiMode == AiMode.VERIFIED_AUTO_CAPTURE) {
                            min(cw, ch) * 0.8f
                        } else {
                            min(cw, ch) * 0.6f
                        }
                        val frameH = if (aiMode == AiMode.PADDLE_OCR || aiMode == AiMode.TESSERACT_FAST_OCR || aiMode == AiMode.IDENTITY_VERIFICATION) frameW / 1.58f else frameW

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

                        if (aiMode == AiMode.PADDLE_OCR || aiMode == AiMode.TESSERACT_FAST_OCR || aiMode == AiMode.IDENTITY_VERIFICATION) {
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
                        } else if (aiMode == AiMode.FACE_DETECTION || aiMode == AiMode.HAND_DETECTION || aiMode == AiMode.VERIFIED_AUTO_CAPTURE) {
                             // 🌟 Guide for Face/Palm
                             if (aiMode == AiMode.FACE_DETECTION || aiMode == AiMode.VERIFIED_AUTO_CAPTURE) {
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
                        if (aiMode == AiMode.PADDLE_OCR || aiMode == AiMode.TESSERACT_FAST_OCR || aiMode == AiMode.IDENTITY_VERIFICATION) {
                            val maxW = cw * 0.9f
                            val idealH = ch * 0.6f
                            if (idealH * 1.58f > maxW) maxW else idealH * 1.58f
                        } else if (aiMode == AiMode.FACE_DETECTION || aiMode == AiMode.VERIFIED_AUTO_CAPTURE) {
                            min(cw, ch) * 0.8f
                        } else {
                            min(cw, ch) * 0.8f
                        }
                    } else size.width

                    val frameH = if (useCropMode) {
                        if (aiMode == AiMode.PADDLE_OCR || aiMode == AiMode.TESSERACT_FAST_OCR || aiMode == AiMode.IDENTITY_VERIFICATION) frameW / 1.58f else frameW
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
                        if (aiMode == AiMode.FACE_DETECTION || aiMode == AiMode.VERIFIED_AUTO_CAPTURE) {
                            drawContext.canvas.nativeCanvas.drawRoundRect(mappedRect, 16f, 16f, facePaint)
                        } else if (aiMode == AiMode.PADDLE_OCR || aiMode == AiMode.TESSERACT_FAST_OCR || aiMode == AiMode.IDENTITY_VERIFICATION || aiMode == AiMode.TEXT_RECOGNITION) {                            drawContext.canvas.nativeCanvas.drawRect(mappedRect, ocrPaint)
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
                        } else if (aiMode == AiMode.SELFIE_SEGMENTATION || aiMode == AiMode.MULTI_CLASS_SELFIE_SEGMENTATION || aiMode == AiMode.VERIFICATION_SEGMENTATION || aiMode == AiMode.SUBJECT_SEGMENTATION) {
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
                                // 🌟 Multi-class selfie mask covers full preview (bitmap is always full-frame, not cropped)
                                val destRect = if (aiMode == AiMode.MULTI_CLASS_SELFIE_SEGMENTATION || aiMode == AiMode.VERIFICATION_SEGMENTATION) {
                                    android.graphics.RectF(0f, 0f, size.width, size.height)
                                } else {
                                    mappedRect
                                }
                                val maskPaint = android.graphics.Paint().apply {
                                    isFilterBitmap = true
                                    isAntiAlias = true
                                }
                                try {
                                    drawContext.canvas.nativeCanvas.drawBitmap(maskBitmap, null, destRect, maskPaint)
                                } catch (e: Exception) {
                                    // Ignore: Bitmap was recycled concurrently by the background loop
                                }
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

                // 🌟 Auto-framing (Center Stage) Toggle Button
                IconButton(
                    onClick = {
                        val newState = !autoFramingEnabled
                        onAutoFramingEnabledChange(newState)
                        // Show user feedback via Toast
                        val msg = if (newState) "Auto-framing ON" else "Auto-framing OFF"
                        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier
                        .size(40.dp)
                        .background(
                            if (autoFramingEnabled) Color(0xFF7C4DFF).copy(alpha = 0.7f) else Color.White.copy(alpha = 0.2f),
                            CircleShape
                        )
                ) {
                    Icon(
                        imageVector = Icons.Default.CenterFocusStrong,
                        contentDescription = "Auto-framing (Center Stage)",
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
                                AiMode.POSE_DETECTION -> "Pose Detection"
                                AiMode.SELFIE_SEGMENTATION -> "Selfie Segment"
                                AiMode.SUBJECT_SEGMENTATION -> "Subject Segment"
                                AiMode.OBJECT_DETECTION -> "Object Detect"
                                AiMode.CUSTOM_OBJECT_DETECTION -> "Custom Object"
                                AiMode.TEXT_RECOGNITION -> "Text Recognition"
                                AiMode.IDENTITY_VERIFICATION -> "Identity Verification"
                                AiMode.MULTI_CLASS_SELFIE_SEGMENTATION -> "Multi-Class Selfie"
                                AiMode.VERIFICATION_SEGMENTATION -> "Verification Segmetation"
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

                    if (aiMode == AiMode.MULTI_CLASS_SELFIE_SEGMENTATION || aiMode == AiMode.VERIFICATION_SEGMENTATION) {
                        Box {
                            Surface(
                                onClick = { showOutputTypeMenu = true },
                                color = Color.White.copy(alpha = 0.12f),
                                shape = RoundedCornerShape(100.dp),
                                modifier = Modifier.height(26.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxHeight().padding(horizontal = 14.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Text(
                                        selfieOutputType,
                                        color = Color.White,
                                        fontSize = 9.sp,
                                        fontWeight = FontWeight.Black
                                    )
                                    Icon(Icons.Default.ArrowDropDown, null, tint = Color.White, modifier = Modifier.size(14.dp))
                                }
                            }
                            DropdownMenu(
                                expanded = showOutputTypeMenu,
                                onDismissRequest = { showOutputTypeMenu = false }
                            ) {
                                listOf("Category Mask", "Confidence Mask").forEach { model ->
                                    DropdownMenuItem(
                                        text = { Text(model) },
                                        onClick = {
                                            onSelfieOutputTypeChange(model)
                                            showOutputTypeMenu = false
                                        }
                                    )
                                }
                            }
                        }
                    }

                    if (aiMode == AiMode.VERIFICATION_SEGMENTATION) {
                        Surface(
                            onClick = { onIsIdCardModeChange(!isIdCardMode) },
                            color = if (isIdCardMode) Color(0xFFFF9500) else Color(0xFF34C759),
                            shape = RoundedCornerShape(100.dp),
                            modifier = Modifier.height(26.dp)
                        ) {
                            Box(
                                modifier = Modifier.fillMaxHeight().padding(horizontal = 14.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    if (isIdCardMode) "ID CARD MODE" else "LIVE FACE MODE",
                                    color = Color.White,
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Black,
                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                                )
                            }
                        }
                    }

                    if ((aiMode == AiMode.MULTI_CLASS_SELFIE_SEGMENTATION || aiMode == AiMode.VERIFICATION_SEGMENTATION) && selfieOutputType == "Confidence Mask") {
                        Box {
                            Surface(
                                onClick = { showSelectClassMenu = true },
                                color = Color.White.copy(alpha = 0.12f),
                                shape = RoundedCornerShape(100.dp),
                                modifier = Modifier.height(26.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxHeight().padding(horizontal = 14.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Text(
                                        selfieSelectClass,
                                        color = Color.White,
                                        fontSize = 9.sp,
                                        fontWeight = FontWeight.Black
                                    )
                                    Icon(Icons.Default.ArrowDropDown, null, tint = Color.White, modifier = Modifier.size(14.dp))
                                }
                            }
                            DropdownMenu(
                                expanded = showSelectClassMenu,
                                onDismissRequest = { showSelectClassMenu = false }
                            ) {
                                listOf("0 - background", "1 - hair", "2 - body-skin", "3 - face-skin", "4 - clothes", "5 - others").forEach { model ->
                                    DropdownMenuItem(
                                        text = { Text(model) },
                                        onClick = {
                                            onSelfieSelectClassChange(model)
                                            showSelectClassMenu = false
                                        }
                                    )
                                }
                            }
                        }
                    }


                    if (aiMode == AiMode.IDENTITY_VERIFICATION) {
                        // OCR Model Selection Dropdown (For Identity Verification)
                        var showOcrModelMenu by remember { mutableStateOf(false) }
                        Box {
                            Surface(
                                onClick = { showOcrModelMenu = true },
                                color = if (selectedOcrModel.isEmpty()) Color(0xFFFF9500).copy(alpha = 0.8f) else Color.White.copy(alpha = 0.12f),
                                shape = RoundedCornerShape(100.dp),
                                modifier = Modifier.height(26.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxHeight().padding(horizontal = 14.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Text(
                                        if (selectedOcrModel.isEmpty()) "SELECT OCR MODEL" else selectedOcrModel.uppercase(),
                                        color = Color.White,
                                        fontSize = 9.sp,
                                        fontWeight = FontWeight.Black
                                    )
                                    Icon(Icons.Default.ArrowDropDown, null, tint = Color.White, modifier = Modifier.size(14.dp))
                                }
                            }
                            DropdownMenu(
                                expanded = showOcrModelMenu,
                                onDismissRequest = { showOcrModelMenu = false }
                            ) {
                                listOf("PaddleOCR", "Tesseract fast").forEach { model ->
                                    DropdownMenuItem(
                                        text = { Text(model) },
                                        onClick = {
                                            onSelectedOcrModelChange(model)
                                            showOcrModelMenu = false
                                        }
                                    )
                                }
                            }
                        }
                    }

                    if (aiMode == AiMode.FACE_DETECTION || aiMode == AiMode.VERIFIED_AUTO_CAPTURE) {
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
                    availableCameras.forEachIndexed { index, cameraInfo ->
                        val id = cameraInfo.cameraId
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
                                    text = cameraInfo.cameraType,
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


}
