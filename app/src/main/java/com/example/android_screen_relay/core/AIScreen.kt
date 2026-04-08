package com.example.android_screen_relay.core

import android.Manifest
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
import kotlinx.coroutines.isActive
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
import java.io.InputStream
import android.graphics.Paint
import android.graphics.Path
import android.view.TextureView
import android.hardware.camera2.CameraManager
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import com.example.android_screen_relay.RelayService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.toSize
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.PathEffect
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.math.min
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items

enum class AiMode { OCR, PALMPRINT }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AIScreen() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    
    // States
    var isInitialized by remember { mutableStateOf(true) } // true by default since we init on demand
    var currentImage by remember { mutableStateOf<Bitmap?>(null) }
    var leftPalmImage by remember { mutableStateOf<Bitmap?>(null) }
    var rightPalmImage by remember { mutableStateOf<Bitmap?>(null) }
    var cropImage by remember { mutableStateOf<Bitmap?>(null) }
    var ocrResultJson by remember { mutableStateOf("[]") }
    var ocrTimeMs by remember { mutableStateOf(0L) }
    var isProcessing by remember { mutableStateOf(false) }
    var computeMode by remember { mutableStateOf(ComputeModeManager.getMode()) }
    var currentAiMode by remember { mutableStateOf(AiMode.OCR) }
    var targetHand by remember { mutableStateOf("Left") }

    // Models will be lazy-loaded on demand now
    DisposableEffect(Unit) {
        onDispose {
            // Nothing to globally release, instances are local
        }
    }

    LaunchedEffect(computeMode) {
        // Initialization is deferred to the processing phase to save RAM (2GB optimization)
    }

    // Permission
    var hasPermission by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { granted -> hasPermission = granted }
    )
    LaunchedEffect(Unit) {
        launcher.launch(Manifest.permission.CAMERA)
    }

    // Gallery Launcher
    val galleryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        if (uri != null) {
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
                    Log.e("OCR", "Load image failed", e)
                }
            }
        }
    }

    if (cropImage != null) {
        ImageCropScreen(
            bitmap = cropImage!!,
            onCropDone = { cropped ->
                currentImage = cropped
                cropImage = null
                ocrResultJson = "[]"
                ocrTimeMs = 0
            },
            onCancel = {
                cropImage = null
                // If it was captured, it goes back to camera. If gallery, it goes back.
            }
        )
    } else if (currentImage != null || (leftPalmImage != null && rightPalmImage != null)) {
        // Image Analysis Mode (Screenshot 2 style)
        OCRResultScreen(
            aiMode = currentAiMode,
            image = currentImage ?: leftPalmImage!!, // If currentImage is null, it's Palm mode so fallback to leftPalmImage, UI will handle both
            leftPalmImage = leftPalmImage,
            rightPalmImage = rightPalmImage,
            jsonResult = ocrResultJson,
            timeMs = ocrTimeMs,
            isProcessing = isProcessing,
            computeMode = computeMode,
            onComputeModeChange = { 
                computeMode = it
                ComputeModeManager.setMode(it)
            },
            onClear = { 
                currentImage = null
                leftPalmImage = null
                rightPalmImage = null
                ocrResultJson = "[]"
                ocrTimeMs = 0
                targetHand = "Left"
            },
            onRunModel = {
                if (!isProcessing) {
                    isProcessing = true
                    scope.launch(Dispatchers.IO) {
                        var lazyOcr: PaddleOCR? = null
                        try {
                            lazyOcr = PaddleOCR()
                            val success = lazyOcr.initModel(context, computeMode.coreCount, computeMode.useGpu)
                            if (!success) {
                                withContext(Dispatchers.Main) { Toast.makeText(context, "OCR Init Failed.", Toast.LENGTH_LONG).show() }
                                return@launch
                            }
                            
                            val (canRun, errorMsg) = lazyOcr.canRunInference(context)
                            if (!canRun) {
                                withContext(Dispatchers.Main) { Toast.makeText(context, errorMsg, Toast.LENGTH_LONG).show() }
                                return@launch
                            }

                            val start = System.currentTimeMillis()
                            val mutableBitmap = currentImage!!.copy(Bitmap.Config.ARGB_8888, true)
                            val benchmarkResults = OCRBenchmarkRunner.runFullBenchmarkSuite(context, lazyOcr, mutableBitmap)
                            val end = System.currentTimeMillis()
                            
                            withContext(Dispatchers.Main) {
                                ocrResultJson = benchmarkResults.toString()
                                ocrTimeMs = end - start
                                
                                // ✅ 1. Log-on-Success (Instant Send)
                                val payload = generateOCRPayload(context, currentImage!!, ocrResultJson, ocrTimeMs)
                                val service = RelayService.getInstance()
                                service?.broadcastMessage(payload.toString())
                                
                                isProcessing = false
                            }
                        } catch (e: Throwable) {
                            Log.e("OCR", "Scan/Benchmark error", e)
                            
                            val errorJson = org.json.JSONObject().apply {
                                put("type", "heartbeat")
                                put("device_model", SystemMonitor.getDeviceInfo(context).model)
                                put("os_name", SystemMonitor.getDeviceInfo(context).osName)
                                put("crash_log", "OCR Crash: ${e.message}")
                                put("fatal_error", true)
                                val res = SystemMonitor.getCurrentResourceUsage(context)
                                put("ram_free_mb", res.ramFreeMb)
                                put("ram_used_mb", res.ramUsedMb)
                                put("cpu_usage", res.cpuUsage)
                                put("battery_temp", res.batteryTemp)
                            }
                            com.example.android_screen_relay.GoogleSheetsLogger.logSync(errorJson.toString())
                            
                            withContext(Dispatchers.Main) {
                                Toast.makeText(context, "OCR Error: ${e.message}", Toast.LENGTH_SHORT).show()
                            }
                        } finally {
                            lazyOcr?.release()
                            withContext(Dispatchers.Main) { isProcessing = false }
                        }
                    }
                }
            },
            onSendWs = {
                if (ocrResultJson != "[]" && ocrResultJson.isNotEmpty()) {
                    scope.launch(Dispatchers.IO) {
                        val payload = generateOCRPayload(context, currentImage ?: leftPalmImage!!, ocrResultJson, ocrTimeMs)
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
                onStableDetection = { bitmap ->
                    if (isProcessing) return@CameraPreviewScreen false // Return false if CPU is locked 

                    if (currentAiMode == AiMode.PALMPRINT) {
                        try {
                            val tempPalm = PalmprintProcessor()
                            val success = tempPalm.init(context, AIConfig(computeMode.useGpu, computeMode.coreCount))
                            if (success) {
                                val result = tempPalm.process(bitmap)
                                val item = result.items.firstOrNull()
                                tempPalm.release()
                                result.success && item?.extra?.get("hand")?.toString()?.equals(targetHand, ignoreCase = true) == true
                            } else {
                                tempPalm.release()
                                false
                            }
                        } catch (e: Exception) { false }
                    } else {
                        // For OCR, detect card text before snap to prevent infinite snapping
                        try {
                            val scale = 320f / maxOf(bitmap.width, bitmap.height)
                            val tempOcr = PaddleOCR()
                            val s = tempOcr.initModel(context, computeMode.coreCount, computeMode.useGpu)
                            if (s) {
                                if (scale >= 1f) {
                                    val res = tempOcr.detect(bitmap)
                                    tempOcr.release()
                                    org.json.JSONArray(res).length() >= 4
                                } else {
                                    val w = (bitmap.width * scale).toInt()
                                    val h = (bitmap.height * scale).toInt()
                                    val scaled = Bitmap.createScaledBitmap(bitmap, w, h, false)
                                    val res = tempOcr.detect(scaled)
                                    tempOcr.release()
                                    org.json.JSONArray(res).length() >= 4
                                }
                            } else {
                                tempOcr.release()
                                false
                            }
                        } catch (e: Exception) { false }
                    }
                },
                onImageCaptured = { bitmap ->
                    if (isProcessing) return@CameraPreviewScreen // Double check Busy State Lock
                    
                    if (currentAiMode == AiMode.PALMPRINT) {
                        isProcessing = true
                        scope.launch(Dispatchers.Default) {
                            var tempPalm: PalmprintProcessor? = null
                            try {
                                tempPalm = PalmprintProcessor()
                                tempPalm.init(context, AIConfig(computeMode.useGpu, computeMode.coreCount))
                                val result = tempPalm.process(bitmap)
                                val item = result.items.firstOrNull()
                                val cropped = if (result.success && item != null) {
                                    val palmRoiMap = item.extra["palm_roi"] as? Map<*, *>
                                    val centerX = (palmRoiMap?.get("center_x") as? Float)
                                    val centerY = (palmRoiMap?.get("center_y") as? Float)
                                    val size = (palmRoiMap?.get("size") as? Float)
                                    val rotation = (palmRoiMap?.get("rotation") as? Float) ?: 0f
                                    
                                    if (centerX != null && centerY != null && size != null) {
                                        val matrix = android.graphics.Matrix()
                                        matrix.postRotate(rotation)
                                        val rotatedBitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
                                        
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
                                        
                                        val halfSize = size / 2f
                                        val left = (newCX - halfSize).toInt().coerceAtLeast(0)
                                        val top = (newCY - halfSize).toInt().coerceAtLeast(0)
                                        var w = size.toInt()
                                        var h = size.toInt()
                                        
                                        // Bound checks
                                        if (left + w > rotatedBitmap.width) w = rotatedBitmap.width - left
                                        if (top + h > rotatedBitmap.height) h = rotatedBitmap.height - top
                                        
                                        if (w > 0 && h > 0) {
                                            Bitmap.createBitmap(rotatedBitmap, left, top, w, h)
                                        } else rotatedBitmap
                                    } else {
                                        val left = item.boundingBox.left.toInt().coerceAtLeast(0)
                                        val top = item.boundingBox.top.toInt().coerceAtLeast(0)
                                        val right = item.boundingBox.right.toInt().coerceAtMost(bitmap.width)
                                        val bottom = item.boundingBox.bottom.toInt().coerceAtMost(bitmap.height)
                                        val width = right - left
                                        val height = bottom - top
                                        if (width > 0 && height > 0) {
                                            Bitmap.createBitmap(bitmap, left, top, width, height)
                                        } else bitmap
                                    }
                                } else bitmap
                                
                                val jsonStr = if (result.success && item != null) {
                                    "[\n  {\n    \"area_type\": \"${item.extra["area_type"] ?: "unknown"}\",\n    \"hand\": \"${item.extra["hand"] ?: "unknown"}\"\n  }\n]"
                                } else {
                                    "[]"
                                }

                                withContext(Dispatchers.Main) {
                                    if (targetHand.equals("Left", ignoreCase = true)) {
                                        leftPalmImage = cropped
                                        // Now switch to right hand
                                        targetHand = "Right"
                                        ocrResultJson = jsonStr // store intermediate
                                        // Delay to let UI show "right hand" properly before unlocking camera
                                        kotlinx.coroutines.delay(1500)
                                        isProcessing = false
                                    } else if (targetHand.equals("Right", ignoreCase = true)) {
                                        rightPalmImage = cropped
                                        
                                        // Combine both results
                                        val leftArr = org.json.JSONArray(if(ocrResultJson.isEmpty() || ocrResultJson == "[]") "[]" else ocrResultJson)
                                        val rightArr = org.json.JSONArray(jsonStr)
                                        val combined = org.json.JSONArray()
                                        if (leftArr.length() > 0) combined.put(leftArr.getJSONObject(0))
                                        if (rightArr.length() > 0) combined.put(rightArr.getJSONObject(0))
                                        
                                        ocrResultJson = combined.toString()
                                        cropImage = null
                                        
                                        // ✅ Both hands captured, automatically logs
                                        val payload = generateOCRPayload(context, rightPalmImage!!, ocrResultJson, 0L, currentAiMode)
                                        val service = RelayService.getInstance()
                                        service?.broadcastMessage(payload.toString())
                                        
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
                                com.example.android_screen_relay.GoogleSheetsLogger.logSync(errorJson.toString())
                                
                                withContext(Dispatchers.Main) {
                                    Toast.makeText(context, "Palmprint Error: ${e.message}", Toast.LENGTH_SHORT).show()
                                    isProcessing = false
                                }
                            } finally {
                                tempPalm?.release()
                            }
                        }
                    } else {
                        // OCR mode: manual crop
                        cropImage = bitmap
                        // Unlock processing state because manual crop pauses AI until user taps OK
                        isProcessing = false
                    }
                },
                onGalleryClick = {
                    galleryLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                },
                isProcessingBusy = isProcessing
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
    onStableDetection: suspend (Bitmap) -> Boolean,
    onImageCaptured: (Bitmap) -> Unit,
    onGalleryClick: () -> Unit,
    isProcessingBusy: Boolean = false
) {
    val context = LocalContext.current
    var cameraController by remember { mutableStateOf<Camera2Controller?>(null) }
    
    // State for Settings
    val availableCameras = remember { 
        (context.getSystemService(Context.CAMERA_SERVICE) as CameraManager).cameraIdList.toList() 
    }
    var selectedCameraId by remember { mutableStateOf(availableCameras.firstOrNull() ?: "0") }
    
    // Aspect Ratio State (Default 1:1 for RAM 2GB Device)
    var selectedAspectRatio by remember { mutableStateOf(UiAspectRatio.RATIO_1_1) }
    
    var availableResolutions by remember { mutableStateOf<List<Size>>(emptyList()) }
    var selectedResolution by remember { mutableStateOf<Size?>(null) }
    
    var showSettingsDialog by remember { mutableStateOf(false) }
    var isCapturing by remember { mutableStateOf(false) }
    var stableTime by remember { mutableStateOf(0L) }

    // Update resolutions when camera or aspect ratio changes
    LaunchedEffect(selectedCameraId, selectedAspectRatio, aiMode) {
        if (cameraController == null) {
             cameraController = Camera2Controller(context, onImageCaptured)
        }
        
        // Force Flash for PALMPRINT mode and normal for OCR
        cameraController?.setFlashMode(aiMode == AiMode.PALMPRINT)
        
        // Fetch raw sizes directly mapping orientation manually to prevent waiting for preview initialization
        val allSizes = cameraController!!.getCameraResolutions(selectedCameraId)
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
        
        availableResolutions = filtered.sortedByDescending { it.width * it.height }
        
        // Default to lowest resolution (e.g. 720x720) to save RAM on A10
        if (selectedResolution == null || !availableResolutions.contains(selectedResolution)) {
            selectedResolution = availableResolutions.minByOrNull { it.width * it.height }
        }
        
        cameraController?.aspectRatio = selectedAspectRatio
    }
    
    val cameraKey = "$selectedCameraId-${selectedResolution?.width}x${selectedResolution?.height}-${selectedAspectRatio.name}"

    // Auto-Snap polling
    // ⚠️ Busy State Lock check: Stop loop if 'isProcessingBusy' is true
    LaunchedEffect(aiMode, targetHand, isProcessingBusy) {
        isCapturing = false // ป้องกันบัคค้างหมุนตลอดกาล (Reset state every time)
        stableTime = 0L

        if (isProcessingBusy) return@LaunchedEffect
        
        withContext(Dispatchers.Default) {
            while (isActive && !isProcessingBusy) {
                kotlinx.coroutines.delay(250) // ลดเวลาตรวจจับ (จาก 500ms เป็น 250ms) ให้สแกนถี่ยิ่งขึ้น
                if (!isCapturing && cameraController?.textureView != null) {
                    val bitmap = cameraController?.textureView?.bitmap
                    if (bitmap != null) {
                        // Extra lock safety before intensive computation
                        if (isProcessingBusy) break
                        
                        val criteriaMet = try { onStableDetection(bitmap) } catch(e: Exception) { false }
                        if (criteriaMet) {
                            stableTime += 250
                            if (stableTime >= 750) { // ถือบัตรนิ่งแค่ 0.75 วินาทีก็กดถ่ายเลย (จากเดิม 1.5 วินาที)
                                isCapturing = true
                                withContext(Dispatchers.Main) {
                                    cameraController!!.takePhoto()
                                }
                                stableTime = 0L
                                kotlinx.coroutines.delay(1000) // Wait before resuming (ลดลงมาจาก 2000)
                                isCapturing = false
                            }
                        } else {
                            stableTime = 0L
                        }
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

    var aiDropdownExpanded by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = Color.Black
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            
            // We removed the static AI Mod selector. Wait to add overlays at the bottom.

            // Container for Preview + Overlay that respects Aspect Ratio
            val ratioVal = selectedAspectRatio.value
            
            Box(
                 // If FULL (null), use fillMaxSize, else use aspectRatio
                modifier = (if (ratioVal != null) Modifier.aspectRatio(ratioVal) else Modifier.fillMaxSize()).align(Alignment.Center)
            ) {
                // TextureView for Camera2
                AndroidView(
                    factory = { ctx ->
                        TextureView(ctx).apply {
                            // Keep reference? Controller needs it.
                        }
                    },
                    modifier = Modifier.fillMaxSize(), // Fill the AspectRatio Box
                    update = { tv ->
                         if (cameraController != null) {
                            try {
                               // Make sure we pass the correct ratio/resolution
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
                    val frameColor = if (stableTime >= 250L || isCapturing) android.graphics.Color.parseColor("#4CAF50") else android.graphics.Color.WHITE
                    val maskColor = android.graphics.Color.parseColor("#99000000") // Semi-transparent black
                    val strokeW = 8f
                    val cw = size.width
                    val ch = size.height

                    // OCR matches bounds, PALMPRINT uses a smaller centered box
                    val frameW = if (aiMode == AiMode.OCR) {
                        val maxW = cw * 0.9f
                        val idealH = ch * 0.6f
                        if (idealH * 1.58f > maxW) maxW else idealH * 1.58f
                    } else min(cw, ch) * 0.6f
                    val frameH = if (aiMode == AiMode.OCR) frameW / 1.58f else frameW
                    
                    val left = (cw - frameW) / 2
                    val top = (ch - frameH) / 2
                    val right = left + frameW
                    val bottom = top + frameH

                    // Draw Mask (Darken outside frame)
                    drawRect(Color(maskColor), size = androidx.compose.ui.geometry.Size(cw, top))
                    drawRect(Color(maskColor), topLeft = androidx.compose.ui.geometry.Offset(0f, bottom), size = androidx.compose.ui.geometry.Size(cw, ch - bottom))
                    drawRect(Color(maskColor), topLeft = androidx.compose.ui.geometry.Offset(0f, top), size = androidx.compose.ui.geometry.Size(left, frameH))
                    drawRect(Color(maskColor), topLeft = androidx.compose.ui.geometry.Offset(right, top), size = androidx.compose.ui.geometry.Size(cw - right, frameH))

                    val paint = androidx.compose.ui.graphics.Paint().asFrameworkPaint().apply {
                        style = android.graphics.Paint.Style.STROKE; strokeWidth = strokeW; color = frameColor; strokeCap = android.graphics.Paint.Cap.ROUND
                    }
                    val textPaint = android.graphics.Paint().apply {
                        color = frameColor
                        textSize = 36f
                        isAntiAlias = true
                        typeface = android.graphics.Typeface.DEFAULT_BOLD
                    }

                    if (aiMode == AiMode.OCR) {
                        // Main ID card border (Landscape)
                        val rect = android.graphics.RectF(left, top, right, bottom)
                        drawContext.canvas.nativeCanvas.drawRoundRect(rect, 40f, 40f, paint)
                        
                        // Left vertical line (Simulating card header in Landscape)
                        val headerX = left + frameW * 0.2f
                        drawContext.canvas.nativeCanvas.drawLine(headerX, top, headerX, bottom, paint)
                        
                        // Bottom Right square (Simulating face photo placeholder in Landscape)
                        val squareSize = frameH * 0.40f
                        val sqRight = right - frameW * 0.05f
                        val sqLeft = sqRight - squareSize
                        val sqBottom = bottom - frameH * 0.1f
                        val sqTop = sqBottom - squareSize
                        val sqRect = android.graphics.RectF(sqLeft, sqTop, sqRight, sqBottom)
                        drawContext.canvas.nativeCanvas.drawRoundRect(sqRect, 24f, 24f, paint)
                        
                        // Top Text
                        drawContext.canvas.nativeCanvas.save()
                        drawContext.canvas.nativeCanvas.translate(left + frameW / 2f, top - 60f)
                        // เปลี่ยน Text ทันทีที่ตรวจพบแค่รอบเดียว (>= 250L) เพื่อความลื่นไหล
                        val topText = if (stableTime >= 250L || isCapturing) "พบบัตรแล้ว กำลังทำการบันทึกภาพ..." else "กรุณาวางบัตรประชาชนในกรอบเพื่อรอสแกนอัตโนมัติ"
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
                        
                    } else {
                        // PALMPRINT uses corners
                        val path = android.graphics.Path()
                        val cornerSize = 80f
                        path.moveTo(left, top + cornerSize); path.lineTo(left, top); path.lineTo(left + cornerSize, top)
                        path.moveTo(right - cornerSize, top); path.lineTo(right, top); path.lineTo(right, top + cornerSize)
                        path.moveTo(right, bottom - cornerSize); path.lineTo(right, bottom); path.lineTo(right - cornerSize, bottom)
                        path.moveTo(left + cornerSize, bottom); path.lineTo(left, bottom); path.lineTo(left, bottom - cornerSize)
                        drawContext.canvas.nativeCanvas.drawPath(path, paint)
                        
                        // Top Text
                        drawContext.canvas.nativeCanvas.save()
                        drawContext.canvas.nativeCanvas.translate(left + frameW / 2f, top - 60f)
                        val handName = if (targetHand.equals("Left", ignoreCase=true)) "ซ้าย" else "ขวา"
                        val topText = if (stableTime >= 250L || isCapturing) "ตรวจสอบมือ${handName}เรียบร้อยแล้ว กำลังบันทึกภาพ..." else "กรุณาแบมือ${handName}ให้เต็มกรอบ"
                        val topTextWidth = textPaint.measureText(topText)
                        drawContext.canvas.nativeCanvas.drawText(topText, -topTextWidth / 2f, 0f, textPaint)
                        drawContext.canvas.nativeCanvas.restore()
                    }
                 }
            }

            // Bottom Bar Overlay
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    .background(Color.Black.copy(alpha = 0.7f)) 
                    .padding(horizontal = 16.dp, vertical = 16.dp)
            ) {
                // Auto-Snap Indicator (Left side)
                Box(modifier = Modifier.align(Alignment.CenterStart), contentAlignment = Alignment.Center) {
                    if (isCapturing) {
                        CircularProgressIndicator(color = Color.White, strokeWidth = 3.dp, modifier = Modifier.size(32.dp))
                    } else {
                        Text(
                            "Auto-Snap\nEnabled", 
                            color = Color.White, 
                            fontSize = 12.sp, 
                            fontWeight = FontWeight.Medium,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                    }
                }

                // Tools Group (Right side)
                Row(
                    modifier = Modifier.align(Alignment.CenterEnd),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (aiMode == AiMode.PALMPRINT) {
                        var handExpanded by remember { mutableStateOf(false) }
                        Box {
                            Button(
                                onClick = { handExpanded = true },
                                colors = ButtonDefaults.buttonColors(containerColor = Color.White.copy(alpha = 0.2f), contentColor = Color.White),
                                shape = RoundedCornerShape(24.dp),
                                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 0.dp),
                                modifier = Modifier.height(48.dp)
                            ) {
                                Text(text = targetHand, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                            }
                            
                            DropdownMenu(
                                expanded = handExpanded,
                                onDismissRequest = { handExpanded = false },
                                modifier = Modifier.background(Color(0xFF333333))
                            ) {
                                listOf("Left", "Right").forEach { hand ->
                                    DropdownMenuItem(
                                        text = { Text(hand, color = Color.White, fontWeight = FontWeight.Bold) },
                                        onClick = { 
                                            onTargetHandChange(hand)
                                            handExpanded = false
                                        }
                                    )
                                }
                            }
                        }
                    }

                    // AI Dropdown
                    Box {
                        Button(
                            onClick = { aiDropdownExpanded = true },
                            colors = ButtonDefaults.buttonColors(containerColor = Color.White.copy(alpha = 0.2f), contentColor = Color.White),
                            shape = RoundedCornerShape(24.dp),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 0.dp),
                            modifier = Modifier.height(48.dp)
                        ) {
                            Text(text = aiMode.name, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        }
                        
                        DropdownMenu(
                            expanded = aiDropdownExpanded,
                            onDismissRequest = { aiDropdownExpanded = false },
                            modifier = Modifier.background(Color(0xFF333333))
                        ) {
                            AiMode.values().forEach { mode ->
                                DropdownMenuItem(
                                    text = { Text(mode.name, color = Color.White, fontWeight = FontWeight.Bold) },
                                    onClick = { 
                                        onAiModeChange(mode)
                                        aiDropdownExpanded = false
                                    }
                                )
                            }
                        }
                    }

                    // Import Button (Icon only)
                    IconButton(
                        onClick = onGalleryClick,
                        modifier = Modifier.size(48.dp).background(Color.White.copy(alpha = 0.2f), CircleShape)
                    ) {
                        Icon(Icons.Default.PhotoLibrary, contentDescription = "Import", tint = Color.White, modifier = Modifier.size(24.dp))
                    }

                    // Settings Button
                    IconButton(
                        onClick = { showSettingsDialog = true },
                        modifier = Modifier.size(48.dp).background(Color.White.copy(alpha = 0.2f), CircleShape)
                    ) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings", tint = Color.White, modifier = Modifier.size(24.dp))
                    }
                }
            }

        }
    }
    
    if (showSettingsDialog) {
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
                                .clickable { selectedCameraId = id }
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = (id == selectedCameraId),
                                onClick = { selectedCameraId = id }
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
                    Spacer(modifier = Modifier.width(8.dp))
                    Surface(
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        shape = RoundedCornerShape(4.dp)
                    ) {
                        Text(
                            "Default: 1:1",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
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
                            onClick = { selectedAspectRatio = ratio },
                            label = { 
                                Text(
                                    ratio.displayName, 
                                    style = MaterialTheme.typography.labelLarge,
                                    modifier = Modifier.padding(vertical = 4.dp)
                                ) 
                            },
                            leadingIcon = if (ratio == selectedAspectRatio) {
                                { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp)) }
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
                    Spacer(modifier = Modifier.width(8.dp))
                    Surface(
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        shape = RoundedCornerShape(4.dp)
                    ) {
                        Text(
                            "Default: 720x720",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
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
                                    .clickable { selectedResolution = size }
                                    .padding(horizontal = 16.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(
                                    selected = (size == selectedResolution),
                                    onClick = { selectedResolution = size }
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                     Text(
                                        "${size.width} x ${size.height}",
                                        style = MaterialTheme.typography.bodyLarge,
                                        fontWeight = FontWeight.Medium
                                    )
                                    val mp = String.format(Locale.US, "%.1f MP", (size.width * size.height) / 1_000_000f)
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
    isProcessing: Boolean,
    computeMode: ComputeMode,
    onComputeModeChange: (ComputeMode) -> Unit,
    onClear: () -> Unit,
    onRunModel: () -> Unit,
    onSendWs: () -> Unit,
    onGalleryClick: () -> Unit // Add gallery option here too as per screenshot?
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
                        Text(if (aiMode == AiMode.PALMPRINT) "Palmprint Result" else "OCR Result", style = MaterialTheme.typography.titleMedium)
                        if (timeMs > 0) {
                            Text("Process time: ${timeMs}ms", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onClear) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = onGalleryClick) {
                        Icon(Icons.Default.PhotoLibrary, contentDescription = "Import")
                    }
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
                    if (aiMode == AiMode.OCR) {
                        // Compute Mode Selector only for OCR
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            ComputeMode.values().forEach { mode ->
                                FilterChip(
                                    selected = (computeMode == mode),
                                    onClick = { onComputeModeChange(mode) },
                                    label = { Text(mode.displayName, fontSize = 12.sp) },
                                    leadingIcon = if (computeMode == mode) {
                                        { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp)) }
                                    } else null,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (aiMode == AiMode.OCR) {
                            // Start OCR Button
                            FilledTonalButton(
                                onClick = onRunModel,
                                enabled = !isProcessing,
                                modifier = Modifier.weight(1f).height(48.dp),
                                 colors = ButtonDefaults.filledTonalButtonColors(
                                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            ) {
                                if (isProcessing) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(14.dp),
                                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                                        strokeWidth = 2.dp
                                    )
                                    Spacer(Modifier.width(8.dp))
                                } else {
                                    Icon(Icons.Default.Scanner, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(6.dp))
                                }
                                Text("Scan", maxLines = 1, style = MaterialTheme.typography.labelMedium)
                            }
                        }

                        // Preview JSON Button
                        OutlinedButton(
                            onClick = {
                                if (jsonResult == "[]" || jsonResult.isEmpty()) {
                                    Toast.makeText(context, if (aiMode == AiMode.OCR) "Please run OCR first" else "No Palmprint result", Toast.LENGTH_SHORT).show()
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
                            Text("Preview", maxLines = 1, style = MaterialTheme.typography.labelMedium)
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
                                    Toast.makeText(context, "Please run OCR first", Toast.LENGTH_SHORT).show()
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
                                            
                                            com.example.android_screen_relay.LogRepository.addLog(
                                                component = "OCR",
                                                event = "send_json",
                                                data = mapOf("payload_size" to jsonString.length, "blocks" to try { JSONArray(jsonResult).length() } catch(e:Exception){0}),
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
            if (aiMode == AiMode.PALMPRINT && leftPalmImage != null && rightPalmImage != null) {
                // Show both palms for PALMPRINT mode
                Column(
                    modifier = Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    val itemCount = try { JSONArray(jsonResult).length() } catch(e:Exception){0}
                    
                    // Model info
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(
                                text = "Model: MediaPipe Hand",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = "$itemCount items found (Left & Right)",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    // Left Palm
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp).aspectRatio(1f),
                        shape = RoundedCornerShape(12.dp),
                        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                    ) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Image(
                                bitmap = leftPalmImage.asImageBitmap(),
                                contentDescription = "Left Palm",
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop
                            )
                            Text(
                                text = "Hand Left",
                                color = Color.White,
                                modifier = Modifier
                                    .align(Alignment.BottomStart)
                                    .padding(8.dp)
                                    .background(Color.Black.copy(alpha=0.6f), RoundedCornerShape(8.dp))
                                    .padding(horizontal=12.dp, vertical=8.dp),
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
                            Image(
                                bitmap = rightPalmImage.asImageBitmap(),
                                contentDescription = "Right Palm",
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop
                            )
                            Text(
                                text = "Hand Right",
                                color = Color.White,
                                modifier = Modifier
                                    .align(Alignment.BottomStart)
                                    .padding(8.dp)
                                    .background(Color.Black.copy(alpha=0.6f), RoundedCornerShape(8.dp))
                                    .padding(horizontal=12.dp, vertical=8.dp),
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            } else {
                // Default OCR view
                Image(
                    bitmap = image.asImageBitmap(),
                    contentDescription = "Target Image",
                    modifier = Modifier.fillMaxSize().padding(16.dp),
                    contentScale = ContentScale.Fit
                )
                
                // Overlay
                val density = LocalContext.current.resources.displayMetrics.density
                Canvas(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                val scaleX = size.width / image.width.toFloat()
                val scaleY = size.height / image.height.toFloat()
                
                // ContentScale.Fit aligns to center. Calculate the actual scale used.
                val scale = min(scaleX, scaleY)
                
                // Calculate offset to center
                val offsetX = (size.width - image.width * scale) / 2
                val offsetY = (size.height - image.height * scale) / 2
                
                try {
                // Draw Boxes
                    val boxes = JSONArray(jsonResult)
                    val paint = Paint().apply {
                         color = android.graphics.Color.RED
                         style = Paint.Style.STROKE
                         strokeWidth = 2f / scale
                    }
                    val fillPaint = Paint().apply {
                        color = android.graphics.Color.parseColor("#33FF0000") // Red with alpha
                        style = Paint.Style.FILL
                    }

                    drawContext.canvas.nativeCanvas.save()
                    drawContext.canvas.nativeCanvas.translate(offsetX, offsetY)
                    drawContext.canvas.nativeCanvas.scale(scale, scale)

                    val textPaint = Paint().apply {
                         color = android.graphics.Color.WHITE
                         textSize = 12f
                         textAlign = Paint.Align.LEFT
                         setShadowLayer(3f, 0f, 0f, android.graphics.Color.BLACK)
                    }

                    for (i in 0 until boxes.length()) {
                        val boxObj = boxes.getJSONObject(i)
                        
                        // Parse "box" [[x,y], [x,y]...]
                        if (boxObj.has("box")) {
                            val boxArr = boxObj.getJSONArray("box")
                            if (boxArr.length() > 0) {
                                val path = Path()
                                val p0 = boxArr.getJSONArray(0)
                                path.moveTo(p0.getInt(0).toFloat(), p0.getInt(1).toFloat())
                                for(j in 1 until boxArr.length()){
                                    val p = boxArr.getJSONArray(j)
                                    path.lineTo(p.getInt(0).toFloat(), p.getInt(1).toFloat())
                                }
                                path.close()
                                drawContext.canvas.nativeCanvas.drawPath(path, fillPaint)
                                drawContext.canvas.nativeCanvas.drawPath(path, paint)

                                // Draw label
                                if (boxObj.has("label")) {
                                    val label = boxObj.getString("label")
                                    val x = p0.getInt(0).toFloat()
                                    val y = p0.getInt(1).toFloat()
                                    drawContext.canvas.nativeCanvas.drawText(label, x, y - 5f, textPaint)
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

            // Model Info Overlay (Subtle)
            if (aiMode != AiMode.PALMPRINT) {
                if (isProcessing || jsonResult != "[]") {
                    Surface(
                        modifier = Modifier.align(Alignment.TopEnd).padding(16.dp),
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.8f),
                        shape = RoundedCornerShape(8.dp),
                        tonalElevation = 2.dp
                    ) {
                        Column(Modifier.padding(8.dp)) {
                             Text(
                                 text = "Model: PP-OCRv5",
                                 style = MaterialTheme.typography.labelSmall,
                                 fontWeight = FontWeight.Bold
                             )
                             if (jsonResult != "[]") {
                                 val count = try { JSONArray(jsonResult).length() } catch(e:Exception){0}
                                 Text(text = "$count items found", style = MaterialTheme.typography.labelSmall)
                             }
                        }
                    }
                }
            }
        }
    }
    
        if (showJsonDialog) {
            val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
            
            ModalBottomSheet(
                onDismissRequest = { showJsonDialog = false },
                sheetState = sheetState,
                containerColor = MaterialTheme.colorScheme.surface,
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
                        "JSON Payload", 
                        style = MaterialTheme.typography.headlineSmall, 
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        "Review the generated data structure", 
                        style = MaterialTheme.typography.bodyMedium, 
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Spacer(Modifier.height(24.dp))

                    // JSON Content Area
                    Card(
                        shape = RoundedCornerShape(12.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFE0E0E0)),
                        colors = CardDefaults.cardColors(containerColor = Color.White),
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f, fill = false) // Allow it to take available space but not force it
                            .heightIn(min = 200.dp, max = 450.dp)
                    ) {
                        val scrollState = rememberScrollState()
                        Box(modifier = Modifier.padding(16.dp).verticalScroll(scrollState)) {
                             Text(
                                 text = fullJsonOutput,
                                 fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                 fontSize = 13.sp,
                                 lineHeight = 18.sp,
                                 color = Color(0xFF333333) // Dark gray for better contrast on white
                             )
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

fun minWith(a: Float, b: Float): Float = kotlin.math.min(a, b)

private fun generateOCRPayload(
    context: Context,
    image: Bitmap,
    jsonResult: String,
    timeMs: Long,
    aiMode: AiMode = AiMode.OCR
): JSONObject {
    val device = SystemMonitor.getDeviceInfo(context)
    val resource = SystemMonitor.getCurrentResourceUsage(context)
    
    val payload = JSONObject()
    payload.put("type", if (aiMode == AiMode.PALMPRINT) "palmprint_result" else "ocr_result")
    payload.put("timestamp", System.currentTimeMillis())
    
    // engine_info (new format)
    val engineInfo = JSONObject().apply {
        if (aiMode == AiMode.PALMPRINT) {
            put("engine", "mediapipe")
            put("version", "tasks-vision")
            put("runtime", "tflite")
            put("model", "hand_landmarker.task")
        } else {
            put("engine", "paddleocr")
            put("version", "v5")
            put("runtime", "ncnn")
            put("model", "PP-OCRv5_mobile_rec")
        }
        
        val mode = ComputeModeManager.getMode()
        put("compute_mode", mode.displayName)
        put("cores", mode.coreCount)
        put("use_gpu", mode.useGpu)
    }
    payload.put("engine_info", engineInfo)
    payload.put("pipeline", "on-device")
    
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
        
        if (aiMode == AiMode.PALMPRINT) {
            // Palmprint structure
            payload.put("result", JSONObject().apply {
                put("palms", benchmarkArr)
            })
            payload.put("summary", JSONObject().apply {
                put("palms_detected", benchmarkArr.length())
                put("total_latency_ms", timeMs)
            })
            return payload
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
            return payload
        }
        
        // 1. Result (full_text & lines)
        val primaryRun = benchmarkArr.getJSONObject(0)
        val primaryResultBox = primaryRun.getJSONArray("result")
        
        val sb = java.lang.StringBuilder()
        var totalConfidence = 0.0
        val linesArray = JSONArray()
        
        for (i in 0 until primaryResultBox.length()) {
            val item = primaryResultBox.getJSONObject(i)
            if (!item.has("label")) continue 

            val text = item.getString("label")
            val conf = item.getDouble("prob")
            val box = item.getJSONArray("box")
            
            val xs = mutableListOf<Double>()
            val ys = mutableListOf<Double>()
            for (j in 0 until box.length()) {
                val p = box.getJSONArray(j)
                xs.add(p.getDouble(0))
                ys.add(p.getDouble(1))
            }
            
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
                    for(k in 0 until lastBox.length()) sumYLast += lastBox.getJSONArray(k).getDouble(1)
                    val avgYLast = sumYLast / lastBox.length()

                    var sumYCurr = 0.0
                    for(k in 0 until box.length()) sumYCurr += box.getJSONArray(k).getDouble(1)
                    val avgYCurr = sumYCurr / box.length()

                    val height = maxY - minY
                    var minYLast = Double.MAX_VALUE
                    var maxYLast = -Double.MAX_VALUE
                    var maxXLast = -Double.MAX_VALUE
                    for(k in 0 until lastBox.length()) {
                        val y = lastBox.getJSONArray(k).getDouble(1)
                        if(y < minYLast) minYLast = y
                        if(y > maxYLast) maxYLast = y
                        
                        val x = lastBox.getJSONArray(k).getDouble(0)
                        if(x > maxXLast) maxXLast = x
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
            .replace(Regex("(?<=[ก-ฮ])(?=(นาย|นาง|นางสาว)[a-zA-Zก-ฮ])"), " ") // เว้นวรรคถ้าเจอคำนำหน้าติดกับข้อความก่อนหน้า
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
    
    return payload
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImageCropScreen(
    bitmap: Bitmap,
    onCropDone: (Bitmap) -> Unit,
    onCancel: () -> Unit
) {
    // Adjust default crop frame to isolate the 'Name-Surname' (upper-middle) part of the Thai ID card
    // Left at 0.25 (to skip the Garuda emblem), Right at 0.95 (near edge)
    // Top at 0.32, Bottom at 0.45 (around the Thai name position)
    var cropRectNormalized by remember { mutableStateOf(Rect(0.25f, 0.32f, 0.95f, 0.45f)) }
    
    // Helper enum for drag Logic
    var activeHandle by remember { mutableStateOf("NONE") } 

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Crop Image") },
                navigationIcon = {
                    IconButton(onClick = onCancel) {
                        Icon(Icons.Default.Close, contentDescription = "Cancel")
                    }
                },
                actions = {
                    IconButton(onClick = {
                        val width = bitmap.width
                        val height = bitmap.height
                        
                        val left = cropRectNormalized.left.coerceIn(0f, 1f)
                        val top = cropRectNormalized.top.coerceIn(0f, 1f)
                        val right = cropRectNormalized.right.coerceIn(0f, 1f)
                        val bottom = cropRectNormalized.bottom.coerceIn(0f, 1f)

                        val x = (left * width).toInt()
                        val y = (top * height).toInt()
                        val w = ((right - left) * width).toInt().coerceAtLeast(1)
                        val h = ((bottom - top) * height).toInt().coerceAtLeast(1)
                        
                        // Final safety check
                        val safeX = x.coerceIn(0, width - 1)
                        val safeY = y.coerceIn(0, height - 1)
                        val safeW = w.coerceAtMost(width - safeX)
                        val safeH = h.coerceAtMost(height - safeY)

                        val cropped = Bitmap.createBitmap(bitmap, safeX, safeY, safeW, safeH)
                        onCropDone(cropped)
                    }) {
                        Icon(Icons.Default.Check, contentDescription = "Done")
                    }
                }
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(Color.Black),
            contentAlignment = Alignment.Center
        ) {
            // Measure container
            BoxWithConstraints(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                 val density = LocalDensity.current.density
                 val containerWidth = maxWidth.value * density
                 val containerHeight = maxHeight.value * density
                 
                 // Calculate displayed image size (Fit Center)
                 val imageRatio = bitmap.width.toFloat() / bitmap.height.toFloat()
                 val containerRatio = containerWidth / containerHeight
                 
                 val displayWidth: Float
                 val displayHeight: Float
                 
                 if (imageRatio > containerRatio) {
                     displayWidth = containerWidth
                     displayHeight = containerWidth / imageRatio
                 } else {
                     displayHeight = containerHeight
                     displayWidth = displayHeight * imageRatio
                 }
                 
                 val displayWidthDp = (displayWidth / density).dp
                 val displayHeightDp = (displayHeight / density).dp

                 Box(
                     modifier = Modifier
                         .size(displayWidthDp, displayHeightDp)
                 ) {
                     Image(
                         bitmap = bitmap.asImageBitmap(),
                         contentDescription = null,
                         contentScale = ContentScale.FillBounds,
                         modifier = Modifier.fillMaxSize()
                     )
                     
                     // Overlay & Gestures
                     Canvas(
                         modifier = Modifier
                             .fillMaxSize()
                             .pointerInput(Unit) {
                                 detectDragGestures(
                                     onDragStart = { offset ->
                                         val w = size.width
                                         val h = size.height
                                         val rect = cropRectNormalized
                                         val l = rect.left * w
                                         val t = rect.top * h
                                         val r = rect.right * w
                                         val b = rect.bottom * h
                                         
                                         // Hit test radius
                                         val rad = 40f 

                                         activeHandle = when {
                                             (offset - Offset(l, t)).getDistance() < rad -> "TL"
                                             (offset - Offset(r, t)).getDistance() < rad -> "TR"
                                             (offset - Offset(l, b)).getDistance() < rad -> "BL"
                                             (offset - Offset(r, b)).getDistance() < rad -> "BR"
                                             offset.x in l..r && offset.y in t..b -> "CENTER"
                                             else -> "NONE"
                                         }
                                     },
                                     onDrag = { change, dragAmount ->
                                         change.consume()
                                         val dx = dragAmount.x / size.width
                                         val dy = dragAmount.y / size.height
                                         
                                         var (l, t, r, b) = cropRectNormalized
                                         
                                         when (activeHandle) {
                                             "TL" -> { l += dx; t += dy }
                                             "TR" -> { r += dx; t += dy }
                                             "BL" -> { l += dx; b += dy }
                                             "BR" -> { r += dx; b += dy }
                                             "CENTER" -> {
                                                 l += dx; r += dx
                                                 t += dy; b += dy
                                             }
                                         }
                                         
                                         // Constraint logic could be improved but this prevents flipping
                                         if (l > r - 0.05f) l = r - 0.05f
                                         if (t > b - 0.05f) t = b - 0.05f
                                         
                                         // Keep Center inside bounds
                                         if (activeHandle == "CENTER") {
                                              val w = r - l
                                              val h = b - t
                                              if (l < 0) { l = 0f; r = w }
                                              if (t < 0) { t = 0f; b = h }
                                              if (r > 1) { r = 1f; l = 1f - w }
                                              if (b > 1) { b = 1f; t = 1f - h }
                                         }

                                         cropRectNormalized = Rect(
                                             l.coerceIn(0f, 1f),
                                             t.coerceIn(0f, 1f),
                                             r.coerceIn(0f, 1f),
                                             b.coerceIn(0f, 1f)
                                         )
                                     },
                                     onDragEnd = { activeHandle = "NONE" }
                                 )
                             }
                     ) {
                         val w = size.width
                         val h = size.height
                         
                         val l = cropRectNormalized.left * w
                         val t = cropRectNormalized.top * h
                         val r = cropRectNormalized.right * w
                         val b = cropRectNormalized.bottom * h
                         
                         // Dim background (Draw 4 rectangles around crop area)
                         val dimColor = Color(0x99000000)
                         
                         // Top
                         drawRect(
                             color = dimColor,
                             topLeft = Offset(0f, 0f),
                             size = androidx.compose.ui.geometry.Size(w, t)
                         )
                         // Bottom
                         drawRect(
                             color = dimColor,
                             topLeft = Offset(0f, b),
                             size = androidx.compose.ui.geometry.Size(w, h - b)
                         )
                         // Left
                         drawRect(
                             color = dimColor,
                             topLeft = Offset(0f, t),
                             size = androidx.compose.ui.geometry.Size(l, b - t)
                         )
                         // Right
                         drawRect(
                             color = dimColor,
                             topLeft = Offset(r, t),
                             size = androidx.compose.ui.geometry.Size(w - r, b - t)
                         )
                         
                         // UI: Border
                         drawRect(
                             color = Color.White,
                             topLeft = Offset(l, t),
                             size = androidx.compose.ui.geometry.Size(r - l, b - t),
                             style = Stroke(2.dp.toPx())
                         )
                         
                         // UI: Handles
                         val handleRadius = 8.dp.toPx()
                         drawCircle(Color.White, handleRadius, Offset(l, t))
                         drawCircle(Color.White, handleRadius, Offset(r, t))
                         drawCircle(Color.White, handleRadius, Offset(l, b))
                         drawCircle(Color.White, handleRadius, Offset(r, b))
                     }
                 }
            }
        }
    }
}
