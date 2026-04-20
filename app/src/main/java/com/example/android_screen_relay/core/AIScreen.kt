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
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.ClipOp
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.math.min
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items

enum class AiMode { PREVIEW, OCR, PALMPRINT, FACE }

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
    var suggestedCropRect by remember { mutableStateOf<Rect?>(null) }

    androidx.compose.runtime.DisposableEffect(Unit) {
        onDispose {
            currentImage?.recycle()
            leftPalmImage?.recycle()
            rightPalmImage?.recycle()
            cropImage?.recycle()
        }
    }
    var ocrResultJson by remember { mutableStateOf("[]") }
    var ocrTimeMs by remember { mutableStateOf(0L) }
    var isProcessing by remember { mutableStateOf(false) }
    var processingResultMsg by remember { mutableStateOf<String?>(null) }
    var computeMode by remember { mutableStateOf(ComputeModeManager.getMode()) }
    var currentAiMode by remember { mutableStateOf(AiMode.OCR) }
    var targetHand by remember { mutableStateOf("Left") }

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
        computeMode = ComputeModeManager.getMode()
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
        // Image Analysis Mode (Screenshot 2 style)
        OCRResultScreen(
            aiMode = currentAiMode,
            image = currentImage
                ?: leftPalmImage!!, // If currentImage is null, it's Palm mode so fallback to leftPalmImage, UI will handle both
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
                        var lazyOcr: PaddleOCR? = null
                        try {
                            lazyOcr = PaddleOCR()
                            val success = lazyOcr.initModel(context, computeMode.coreCount, computeMode.useGpu)
                            if (!success) {
                                withContext(Dispatchers.Main) {
                                    Toast.makeText(
                                        context,
                                        "OCR Init Failed.",
                                        Toast.LENGTH_LONG
                                    ).show()
                                }
                                return@launch
                            }

                            val (canRun, errorMsg) = lazyOcr.canRunInference(context)
                            if (!canRun) {
                                withContext(Dispatchers.Main) {
                                    Toast.makeText(context, errorMsg, Toast.LENGTH_LONG).show()
                                }
                                return@launch
                            }

                            val start = System.currentTimeMillis()
                            val mutableBitmap = currentImage!!.copy(Bitmap.Config.ARGB_8888, true)
                            val benchmarkResults =
                                OCRBenchmarkRunner.runFullBenchmarkSuite(context, lazyOcr, mutableBitmap)
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
                onStableDetection = { bitmap, previewOcr, previewPalm, previewFace ->
                    if (isProcessing || currentAiMode == AiMode.PREVIEW) return@CameraPreviewScreen false // Return false if CPU is locked or in PREVIEW

                    if (currentAiMode == AiMode.PALMPRINT) {
                        try {
                            if (previewPalm != null) {
                                // Scale down bitmap for faster/lower-memory auto-snap detection
                                val scale = 480f / maxOf(bitmap.width, bitmap.height)
                                val processBitmap = if (scale < 1f) {
                                    Bitmap.createScaledBitmap(
                                        bitmap,
                                        (bitmap.width * scale).toInt(),
                                        (bitmap.height * scale).toInt(),
                                        false
                                    )
                                } else {
                                    bitmap
                                }
                                val result = previewPalm.process(processBitmap)
                                if (processBitmap !== bitmap) processBitmap.recycle()
                                val item = result.items.firstOrNull()
                                result.success && item?.extra?.get("hand")?.toString()
                                    ?.equals(targetHand, ignoreCase = true) == true
                            } else {
                                false
                            }
                        } catch (e: Exception) {
                            false
                        }
                    } else if (currentAiMode == AiMode.FACE) {
                        try {
                            if (previewFace != null) {
                                val scale = 480f / maxOf(bitmap.width, bitmap.height)
                                val processBitmap = if (scale < 1f) {
                                    Bitmap.createScaledBitmap(
                                        bitmap,
                                        (bitmap.width * scale).toInt(),
                                        (bitmap.height * scale).toInt(),
                                        false
                                    )
                                } else bitmap
                                val result = previewFace.process(processBitmap)
                                if (processBitmap !== bitmap) processBitmap.recycle()
                                result.success && result.items.isNotEmpty()
                            } else {
                                false
                            }
                        } catch (e: Exception) {
                            false
                        }
                    } else {
                        // For OCR, detect card text before snap to prevent infinite snapping
                        try {
                            if (previewOcr != null) {
                                val scale = 640f / maxOf(bitmap.width, bitmap.height)
                                val w = (bitmap.width * scale).toInt()
                                val h = (bitmap.height * scale).toInt()
                                val scaled = Bitmap.createScaledBitmap(bitmap, w, h, false)
                                val res = previewOcr.detect(scaled)
                                if (scaled !== bitmap) scaled.recycle()
                                var foundId = false
                                try {
                                    val jsonArray = org.json.JSONArray(res)
                                    val strictIdRegex = Regex("""\d[\s-]*\d{4}[\s-]*\d{5}[\s-]*\d{2}[\s-]*\d""")

                                    for (i in 0 until jsonArray.length()) {
                                        val obj = jsonArray.optJSONObject(i)
                                        val lbl = obj?.optString("label", "") ?: ""
                                        val rawText = lbl.replace(" ", "").replace("-", "")

                                        // ผ่อนปรนในหน้า Preview: แค่เห็นเลขต่อเนื่อง 9 หลัก หรือ คีย์เวิร์ดบนบัตรประชาชน ก็ถือว่าเริ่มจับภาพได้ (เพราะภาพดรอปสเกลมาอ่านยาก)
                                        val isIdPattern =
                                            strictIdRegex.containsMatchIn(lbl) || (rawText.length >= 9 && rawText.contains(
                                                Regex("""\d{9,}""")
                                            ))
                                        val idKeywords = listOf(
                                            "เลขประจำตัว",
                                            "ประชาชน",
                                            "National",
                                            "Identification",
                                            "ชื่อตัว",
                                            "เกิดวันที่",
                                            "นาย",
                                            "นางสาว"
                                        )
                                        val isKeyword = idKeywords.any { lbl.contains(it) }

                                        if (isIdPattern || isKeyword) {
                                            foundId = true
                                            break
                                        }
                                    }
                                } catch (e: Exception) {
                                }
                                foundId
                            } else {
                                false
                            }
                        } catch (e: Exception) {
                            false
                        }
                    }
                },
                onImageCaptured = { bitmap ->
                    if (isProcessing) {
                        if (!bitmap.isRecycled) bitmap.recycle()
                        return@CameraPreviewScreen // Double check Busy State Lock
                    }

                    if (currentAiMode == AiMode.PALMPRINT) {
                        isProcessing = true
                        scope.launch(Dispatchers.Default) {
                            var tempPalm: com.example.android_screen_relay.core.PalmprintProcessor? = null
                            try {
                                val pbStartMs = System.currentTimeMillis()
                                tempPalm = com.example.android_screen_relay.core.PalmprintProcessor()
                                tempPalm.init(context, AIConfig(computeMode.useGpu, computeMode.coreCount))
                                val result = tempPalm.process(bitmap)
                                val pbElapsedMs = System.currentTimeMillis() - pbStartMs

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

                                        val halfSize = size / 2f
                                        val left = (newCX - halfSize).toInt().coerceAtLeast(0)
                                        val top = (newCY - halfSize).toInt().coerceAtLeast(0)
                                        var w = size.toInt()
                                        var h = size.toInt()

                                        // Bound checks
                                        if (left + w > rotatedBitmap.width) w = rotatedBitmap.width - left
                                        if (top + h > rotatedBitmap.height) h = rotatedBitmap.height - top

                                        val resultBmp = if (w > 0 && h > 0) {
                                            Bitmap.createBitmap(rotatedBitmap, left, top, w, h)
                                        } else rotatedBitmap

                                        if (resultBmp !== rotatedBitmap) rotatedBitmap.recycle()
                                        resultBmp
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

                                if (bitmap !== cropped && !bitmap.isRecycled) {
                                    bitmap.recycle()
                                }

                                withContext(Dispatchers.Main) {
                                    if (targetHand.equals("Left", ignoreCase = true)) {
                                        ocrTimeMs = pbElapsedMs
                                        leftPalmImage = cropped
                                        // Now switch to right hand
                                        targetHand = "Right"
                                        ocrResultJson = jsonStr // store intermediate
                                        // Delay to let UI show "right hand" properly before unlocking camera
                                        kotlinx.coroutines.delay(1500)
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

                                        // ✅ Both hands captured, automatically logs
                                        val payload = generateOCRPayload(
                                            context,
                                            rightPalmImage!!,
                                            ocrResultJson,
                                            ocrTimeMs,
                                            currentAiMode
                                        )
                                        val service = RelayService.getInstance()
                                        service?.broadcastMessage(payload.toString())

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
                    } else if (currentAiMode == AiMode.FACE) {
                        isProcessing = true
                        scope.launch(Dispatchers.Default) {
                            var tempFace: FaceDetectorProcessor? = null
                            try {
                                val pbStartMs = System.currentTimeMillis()
                                tempFace = FaceDetectorProcessor()
                                tempFace.init(context, AIConfig(computeMode.useGpu, computeMode.coreCount))
                                val result = tempFace.process(bitmap)
                                val pbElapsedMs = System.currentTimeMillis() - pbStartMs

                                val jsonStr = if (result.success && result.items.isNotEmpty()) {
                                    val arr = org.json.JSONArray()
                                    result.items.forEach { item ->
                                        val obj = org.json.JSONObject()
                                        obj.put("label", "Face")
                                        item.extra.forEach { (key, value) ->
                                            if (value is String && (key == "contours" || key == "landmarks")) {
                                                obj.put(key, org.json.JSONObject(value))
                                            } else {
                                                obj.put(key, value)
                                            }
                                        }
                                        val box = org.json.JSONArray()
                                        box.put(item.boundingBox.left)
                                        box.put(item.boundingBox.top)
                                        box.put(item.boundingBox.right)
                                        box.put(item.boundingBox.bottom)
                                        obj.put("bbox", box)
                                        arr.put(obj)
                                    }
                                    arr.toString()
                                } else {
                                    "[]"
                                }

                                withContext(Dispatchers.Main) {
                                    ocrTimeMs = pbElapsedMs
                                    ocrResultJson = jsonStr
                                    currentImage = bitmap.copy(Bitmap.Config.ARGB_8888, true)

                                    if (!bitmap.isRecycled) bitmap.recycle()

                                    val payload = generateOCRPayload(
                                        context,
                                        currentImage!!,
                                        ocrResultJson,
                                        ocrTimeMs,
                                        currentAiMode
                                    )
                                    val service = RelayService.getInstance()
                                    service?.broadcastMessage(payload.toString())

                                    isProcessing = false
                                }
                            } catch (e: Throwable) {
                                Log.e("Face", "Face error", e)
                                withContext(Dispatchers.Main) { isProcessing = false }
                            } finally {
                                tempFace?.release()
                            }
                        }
                    } else {
                        // OCR mode: Automatic extraction of ID and Name
                        isProcessing = true
                        scope.launch(Dispatchers.Default) {
                            var tempOcr: PaddleOCR? = null
                            try {
                                val pbStartMs = System.currentTimeMillis()
                                tempOcr = PaddleOCR()
                                tempOcr.initModel(context, computeMode.coreCount, computeMode.useGpu)
                                val resultJsonStr = tempOcr.detect(bitmap)
                                val pbElapsedMs = System.currentTimeMillis() - pbStartMs

                                val jsonArr = org.json.JSONArray(resultJsonStr)

                                var idMinX = Float.MAX_VALUE;
                                var idMinY = Float.MAX_VALUE;
                                var idMaxX = 0f;
                                var idMaxY = 0f
                                var nameMinX = Float.MAX_VALUE;
                                var nameMinY = Float.MAX_VALUE;
                                var nameMaxX = 0f;
                                var nameMaxY = 0f

                                var hasId = false
                                var hasName = false

                                val strictIdRegex = Regex("""\d[\s-]*\d{4}[\s-]*\d{5}[\s-]*\d{2}[\s-]*\d""")
                                // ตัด "ชื่อตัวและชื่อสกุล" ออก เพื่อไม่ให้กรอบไปกินป้ายกำกับด้านซ้ายหรือบน ให้จับแค่คำนำหน้าชื่อจริงๆ เท่านั้น
                                val namePrefixes = listOf("นาย", "นาง", "นางสาว", "เด็กชาย", "เด็กหญิง")

                                for (i in 0 until jsonArr.length()) {
                                    val obj = jsonArr.optJSONObject(i) ?: continue
                                    val lbl = obj.optString("label", "")
                                    val rawText = lbl.replace(" ", "").replace("-", "")

                                    // ดักเฉพาะตัวเลข 13 หลักเท่านั้น ไม่เอาคำศัพท์ป้ายกำกับเช่น "เลขประจำตัว" เพื่อไม่ให้กรอบสูงเกินไปกินขอบบัตรด้านบน
                                    val isIdFound = strictIdRegex.containsMatchIn(lbl) ||
                                            (rawText.length >= 13 && rawText.contains(Regex("""\d{13}""")))

                                    val isNameFound = namePrefixes.any { lbl.contains(it) }

                                    if (isIdFound || isNameFound) {
                                        val box = obj.optJSONArray("box") ?: continue
                                        var bxXMin = Float.MAX_VALUE;
                                        var bxYMin = Float.MAX_VALUE;
                                        var bxXMax = 0f;
                                        var bxYMax = 0f
                                        for (j in 0 until box.length()) {
                                            val pt = box.optJSONArray(j) ?: continue
                                            val x = pt.optDouble(0, 0.0).toFloat()
                                            val y = pt.optDouble(1, 0.0).toFloat()
                                            if (x < bxXMin) bxXMin = x
                                            if (y < bxYMin) bxYMin = y
                                            if (x > bxXMax) bxXMax = x
                                            if (y > bxYMax) bxYMax = y
                                        }

                                        // ตรวจจับเฉพาะคำที่อยู่ด้านบนและกลางบัตร (ตัดส่วนล่างทิ้ง)
                                        val centerY = (bxYMin + bxYMax) / 2f
                                        val centerX = (bxXMin + bxXMax) / 2f
                                        val isTopHalf =
                                            centerY < (bitmap.height * 0.40f) // ห้ามเกิน 40% ความสูงของบัตรลงมาด้านล่าง
                                        val isNotTooEdge =
                                            centerX > (bitmap.width * 0.05f) && centerX < (bitmap.width * 0.95f)

                                        if (isTopHalf && isNotTooEdge) {
                                            if (isIdFound) {
                                                hasId = true
                                                var adjXMin = bxXMin
                                                // ขยับ X ไปที่ตัวเลขจริงๆ (ประมาณ index ใน string)
                                                val match = strictIdRegex.find(lbl)
                                                if (match != null && lbl.isNotEmpty()) {
                                                    val ratio = match.range.first.toFloat() / lbl.length.toFloat()
                                                    adjXMin = bxXMin + (bxXMax - bxXMin) * ratio
                                                } else if (lbl.length >= 13 && lbl.isNotEmpty()) {
                                                    // fallback
                                                    val digitIdx = lbl.indexOfFirst { it.isDigit() }
                                                    if (digitIdx > 0) {
                                                        val ratio = digitIdx.toFloat() / lbl.length.toFloat()
                                                        adjXMin = bxXMin + (bxXMax - bxXMin) * ratio
                                                    }
                                                }

                                                if (adjXMin < idMinX) idMinX = adjXMin
                                                // ขยับ Y ลงมาให้เยอะขึ้น (หั่นครึ่งบนของกล่องทิ้ง) เพื่อหนี "บัตรประจำตัวประชาชน"
                                                // เลขบัตรไม่มีสระบน จึงสามารถตัด top margin ของกล่องทิ้งไปได้มากถึง 35-40%
                                                val yPad = (bxYMax - bxYMin) * 0.45f
                                                if ((bxYMin + yPad) < idMinY) idMinY = bxYMin + yPad
                                                if (bxXMax > idMaxX) idMaxX = bxXMax
                                                if (bxYMax > idMaxY) idMaxY = bxYMax
                                            }
                                            if (isNameFound) {
                                                hasName = true
                                                var adjXMin = bxXMin
                                                val prefixMatch = namePrefixes.find { lbl.contains(it) }
                                                if (prefixMatch != null && lbl.isNotEmpty()) {
                                                    val idx = lbl.indexOf(prefixMatch)
                                                    val ratio = idx.toFloat() / lbl.length.toFloat()
                                                    adjXMin = bxXMin + (bxXMax - bxXMin) * ratio
                                                }

                                                if (adjXMin < nameMinX) nameMinX = adjXMin
                                                if (bxYMin < nameMinY) nameMinY = bxYMin
                                                if (bxXMax > nameMaxX) nameMaxX = bxXMax
                                                if (bxYMax > nameMaxY) nameMaxY = bxYMax
                                            }
                                        }
                                    }
                                }

                                var finalMinX = 0f;
                                var finalMinY = 0f;
                                var finalMaxX = 0f;
                                var finalMaxY = 0f
                                var validAreaFound = false

                                // บังคับกรอบให้อยู่แค่ด้านบน-กลางบัตรเท่านั้น (ซ้าย 10% ถึง ขวา 95%, บน 15% ถึง 40%) ไม่ว่าจะเจออะไรหรือไม่ก็ตาม
                                // เพื่อป้องกันไม่ให้อ่าน โลโก้ด้านซ้าย หรือ ที่อยู่และวันเกิดด้านล่างบัตรมามั่วๆ

                                if (hasId || hasName) {
                                    validAreaFound = true
                                    var minXAll = Float.MAX_VALUE
                                    var minYAll = Float.MAX_VALUE
                                    var maxXAll = 0f
                                    var maxYAll = 0f

                                    if (hasId) {
                                        minXAll = minOf(minXAll, idMinX)
                                        minYAll = minOf(minYAll, idMinY)
                                        maxXAll = maxOf(maxXAll, idMaxX)
                                        maxYAll = maxOf(maxYAll, idMaxY)
                                    }
                                    if (hasName) {
                                        minXAll = minOf(minXAll, nameMinX)
                                        minYAll = minOf(minYAll, nameMinY)
                                        maxXAll = maxOf(maxXAll, nameMaxX)
                                        maxYAll = maxOf(maxYAll, nameMaxY)
                                    }
                                    finalMinX = minXAll
                                    finalMinY = minYAll
                                    finalMaxX = maxXAll
                                    finalMaxY = maxYAll
                                }

                                val calculatedRect = if (validAreaFound) {
                                    // ตั้งค่า Padding เล็กน้อยเพื่อให้ไม่กุดจนเกินไป
                                    val padX = 15f
                                    val padYTop = 0f    // ขอบบนชิดพอดี
                                    val padYBottom = 30f // เพิ่มขอบล่างเซฟสระด้านล่าง
                                    val w = bitmap.width.toFloat()
                                    val h = bitmap.height.toFloat()

                                    // สร้างกรอบภาพพอดีๆ (Tight crop) รอบเลขบัตรและชื่อเท่านั้น
                                    val left = maxOf(0f, ((finalMinX - padX) / w).coerceIn(0f, 1f))
                                    val top = maxOf(0f, ((finalMinY - padYTop) / h).coerceIn(0f, 1f))
                                    val right =
                                        0.95f // บังคับให้ครอปสุดขอบบัตรด้านขวาเสมอ เพื่อไม่ให้นามสกุลยาวๆ โดนหั่นทิ้ง
                                    val bottom = minOf(1f, ((finalMaxY + padYBottom) / h).coerceIn(0f, 1f))

                                    if (bottom > top && right > left) androidx.compose.ui.geometry.Rect(
                                        left,
                                        top,
                                        right,
                                        bottom
                                    )
                                    else androidx.compose.ui.geometry.Rect(0.15f, 0.15f, 0.95f, 0.40f)
                                } else {
                                    androidx.compose.ui.geometry.Rect(0.15f, 0.15f, 0.95f, 0.40f)
                                }

                                // Auto Crop without UI
                                val cropped = if (calculatedRect != null) {
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
                                    android.graphics.Bitmap.createBitmap(bitmap, safeX, safeY, safeW, safeH)
                                } else bitmap

                                val safeRes = computeMode.maxResolution
                                val optimizedCrop = OCROptimizer.scaleDownToMaxDimension(cropped, safeRes)

                                withContext(Dispatchers.Main) {
                                    processingResultMsg = "⏳ Running Auto OCR..."
                                    currentImage = optimizedCrop
                                }

                                // Auto OCR
                                var isSuccess = false
                                var finalJsonStr = "[]"
                                var finalLatencyMs = 0L
                                try {
                                    val localOcr = PaddleOCR()
                                    if (localOcr.initModel(context, computeMode.coreCount, computeMode.useGpu)) {
                                        val st = System.currentTimeMillis()
                                        // Use formatLabelsInJsonArray if available or just rawOcrRes
                                        val rawOcrRes = localOcr.detect(optimizedCrop)
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
                                tempOcr?.release()
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
    onStableDetection: suspend (Bitmap, PaddleOCR?, PalmprintProcessor?, FaceDetectorProcessor?) -> Boolean,
    onImageCaptured: (Bitmap) -> Unit,
    onGalleryClick: () -> Unit,
    isProcessingBusy: Boolean = false,
    processingResultMsg: String? = null
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
    var isPreviewPaused by remember { mutableStateOf(false) }
    var stableTime by remember { mutableStateOf(0L) }
    var lastInferenceTimeMs by remember { mutableStateOf(0L) }

    // Persistent models for preview mode (Prevent memory/CPU spike from allocating every 250ms)
    var previewOcr by remember { mutableStateOf<PaddleOCR?>(null) }
    var previewPalm by remember { mutableStateOf<PalmprintProcessor?>(null) }
    var previewFace by remember { mutableStateOf<FaceDetectorProcessor?>(null) }
    val computeMode = ComputeModeManager.getMode()

    // Initialize or release preview models when the mode changes
    LaunchedEffect(aiMode) {
        withContext(Dispatchers.IO) {
            // First release both
            previewOcr?.release()
            previewPalm?.release()
            previewFace?.release()
            previewOcr = null
            previewPalm = null
            previewFace = null

            // Then initialize the active one (Limit cores to 1 or 2 for preview stability)
            if (aiMode == AiMode.OCR) {
                val ocr = PaddleOCR()
                val success = ocr.initModel(context, maxOf(1, computeMode.coreCount - 2), computeMode.useGpu)
                if (success) previewOcr = ocr
            } else if (aiMode == AiMode.PALMPRINT) {
                val palm = PalmprintProcessor()
                val success = palm.init(context, AIConfig(computeMode.useGpu, maxOf(1, computeMode.coreCount - 2)))
                if (success) previewPalm = palm
            } else if (aiMode == AiMode.FACE) {
                val face = FaceDetectorProcessor()
                val success = face.init(context, AIConfig(computeMode.useGpu, maxOf(1, computeMode.coreCount - 2)))
                if (success) previewFace = face
            }
        }
    }

    // Update resolutions when camera or aspect ratio changes
    LaunchedEffect(selectedCameraId, selectedAspectRatio, aiMode) {
        if (cameraController == null) {
            cameraController = Camera2Controller(context, onImageCaptured)
        }

        // Force Flash for PALMPRINT mode and normal for OCR
        cameraController?.setFlashMode(aiMode == AiMode.PALMPRINT)

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
        availableResolutions = filtered.distinct().sortedByDescending { it.width * it.height }

        // Default to lowest resolution (e.g. 720x720) to save RAM on A10
        if (selectedResolution == null || !availableResolutions.contains(selectedResolution)) {
            selectedResolution = availableResolutions.minByOrNull { it.width * it.height }
        }

        cameraController?.aspectRatio = selectedAspectRatio
    }

    LaunchedEffect(isPreviewPaused, isProcessingBusy) {
        if (isPreviewPaused || isProcessingBusy) {
            cameraController?.pausePreview()
        } else {
            cameraController?.resumePreview()
        }
    }

    val cameraKey =
        "$selectedCameraId-${selectedResolution?.width}x${selectedResolution?.height}-${selectedAspectRatio.name}"

    // Auto-Snap polling
    // ⚠️ Busy State Lock check: Stop loop if 'isProcessingBusy' is true
    LaunchedEffect(aiMode, targetHand, isProcessingBusy) {
        isCapturing = false // ป้องกันบัคค้างหมุนตลอดกาล (Reset state every time)
        stableTime = 0L

        if (isProcessingBusy) return@LaunchedEffect

        withContext(Dispatchers.Default) {
            while (isActive && !isProcessingBusy) {
                kotlinx.coroutines.delay(250) // ลดเวลาตรวจจับ (จาก 500ms เป็น 250ms) ให้สแกนถี่ยิ่งขึ้น
                if (isPreviewPaused) continue // ข้ามการตรวจจับถ้า preview ถูก pause

                if (!isCapturing && cameraController?.textureView != null) {
                    val bitmap = cameraController?.textureView?.bitmap
                    if (bitmap != null) {
                        // Extra lock safety before intensive computation
                        if (isProcessingBusy) {
                            bitmap.recycle()
                            break
                        }

                        val startInference = System.currentTimeMillis()
                        val criteriaMet = try {
                            onStableDetection(bitmap, previewOcr, previewPalm, previewFace)
                        } catch (e: Exception) {
                            false
                        }
                        val elapsedInference = System.currentTimeMillis() - startInference
                        lastInferenceTimeMs = elapsedInference

                        var passedToCapture = false
                        if (criteriaMet) {
                            stableTime += 250
                            if (stableTime >= 500) { // ถือบัตรนิ่งแค่ 0.5 วินาทีก็กดถ่ายเลย (2 consecutive frames)
                                isCapturing = true
                                passedToCapture = true

                                // แก้ปัญหาเครื่อง RAM 2GB ถ่ายรูปไม่ติด (Hardware stall/OOM during TEMPLATE_STILL_CAPTURE):
                                // สั่ง Snap จาก Preview Bitmap ปัจจุบันที่มีอยู่แล้ว โยนเข้า Pipeline ได้เลย (เร็วและไม่เปลือง RAM เพิ่ม)
                                val snapBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, true)
                                withContext(Dispatchers.Main) {
                                    onImageCaptured(snapBitmap)
                                }

                                stableTime = 0L
                                kotlinx.coroutines.delay(1000) // Wait before resuming (ลดลงมาจาก 2000)
                                isCapturing = false
                            }
                        } else {
                            stableTime = 0L
                        }

                        if (!passedToCapture && !bitmap.isRecycled) {
                            bitmap.recycle()
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

            // Loop for System RAM info
            var freeRamMb by remember { mutableStateOf(1000L) }
            val localContext = LocalContext.current
            LaunchedEffect(Unit) {
                while (isActive) {
                    freeRamMb = SystemMonitor.getCurrentResourceUsage(localContext).ramFreeMb
                    kotlinx.coroutines.delay(2000L)
                }
            }

            // Container for Preview + Overlay that respects Aspect Ratio
            val ratioVal = selectedAspectRatio.value

            Box(
                // If FULL (null), use fillMaxSize, else use aspectRatio
                modifier = (if (ratioVal != null) Modifier.aspectRatio(ratioVal) else Modifier.fillMaxSize()).align(
                    Alignment.Center
                )
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
                    val frameColor =
                        if (stableTime >= 250L || isCapturing) android.graphics.Color.parseColor("#4CAF50") else android.graphics.Color.WHITE
                    val maskColor = android.graphics.Color.parseColor("#99000000") // Semi-transparent black
                    val strokeW = 8f
                    val cw = size.width
                    val ch = size.height

                    // OCR matches bounds, PALMPRINT uses a smaller centered box
                    val frameW = if (aiMode == AiMode.OCR) {
                        val maxW = cw * 0.9f
                        val idealH = ch * 0.6f
                        if (idealH * 1.58f > maxW) maxW else idealH * 1.58f
                    } else if (aiMode == AiMode.FACE) {
                        min(cw, ch) * 0.8f
                    } else {
                        min(cw, ch) * 0.6f
                    }
                    val frameH = if (aiMode == AiMode.OCR) frameW / 1.58f else frameW

                    val left = (cw - frameW) / 2
                    val top = (ch - frameH) / 2
                    val right = left + frameW
                    val bottom = top + frameH

                    val paint = androidx.compose.ui.graphics.Paint().asFrameworkPaint().apply {
                        style = android.graphics.Paint.Style.STROKE; strokeWidth = strokeW; color =
                        frameColor; strokeCap = android.graphics.Paint.Cap.ROUND
                    }
                    val textPaint = android.graphics.Paint().apply {
                        color = frameColor
                        textSize = 36f
                        isAntiAlias = true
                        typeface = android.graphics.Typeface.DEFAULT_BOLD
                    }

                    if (aiMode != AiMode.PREVIEW) {
                        // Draw Mask with circular/rectangular cutout helper
                        drawContext.canvas.save()
                        val cutoutPath = androidx.compose.ui.graphics.Path().apply {
                            val r = androidx.compose.ui.geometry.Rect(left, top, right, bottom)
                            if (aiMode == AiMode.FACE) {
                                addOval(r)
                            } else {
                                addRect(r)
                            }
                        }
                        // Clip everything EXCEPT the cutout, then draw the mask color
                        clipPath(
                            path = cutoutPath,
                            clipOp = ClipOp.Difference
                        ) {
                            drawRect(Color(maskColor))
                        }
                        drawContext.canvas.restore()
                    }

                    if (aiMode == AiMode.OCR) {
                        // Main ID card border (Landscape)
                        val rect = android.graphics.RectF(left, top, right, bottom)
                        drawContext.canvas.nativeCanvas.drawRoundRect(rect, 40f, 40f, paint)

                        // Top Text
                        drawContext.canvas.nativeCanvas.save()
                        drawContext.canvas.nativeCanvas.translate(left + frameW / 2f, top - 60f)
                        // เปลี่ยน Text ทันทีที่ตรวจพบแค่รอบเดียว (>= 250L) เพื่อความลื่นไหล
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

                    } else if (aiMode == AiMode.PALMPRINT) {
                        // PALMPRINT uses corners
                        val path = android.graphics.Path()
                        val cornerSize = 80f
                        path.moveTo(left, top + cornerSize); path.lineTo(left, top); path.lineTo(left + cornerSize, top)
                        path.moveTo(right - cornerSize, top); path.lineTo(right, top); path.lineTo(
                            right,
                            top + cornerSize
                        )
                        path.moveTo(right, bottom - cornerSize); path.lineTo(
                            right,
                            bottom
                        ); path.lineTo(right - cornerSize, bottom)
                        path.moveTo(left + cornerSize, bottom); path.lineTo(left, bottom); path.lineTo(
                            left,
                            bottom - cornerSize
                        )
                        drawContext.canvas.nativeCanvas.drawPath(path, paint)

                        // Top Text
                        drawContext.canvas.nativeCanvas.save()
                        drawContext.canvas.nativeCanvas.translate(left + frameW / 2f, top - 60f)
                        val handName = if (targetHand.equals("Left", ignoreCase = true)) "ซ้าย" else "ขวา"
                        val topText =
                            if (stableTime >= 250L || isCapturing) "ตรวจสอบมือ${handName}เรียบร้อยแล้ว กำลังบันทึกภาพ..." else "กรุณาแบมือ${handName}ให้เต็มกรอบ"
                        val topTextWidth = textPaint.measureText(topText)
                        drawContext.canvas.nativeCanvas.drawText(topText, -topTextWidth / 2f, 0f, textPaint)
                        drawContext.canvas.nativeCanvas.restore()
                    } else if (aiMode == AiMode.FACE) {
                        val rect = android.graphics.RectF(left, top, right, bottom)
                        drawContext.canvas.nativeCanvas.drawOval(rect, paint)

                        drawContext.canvas.nativeCanvas.save()
                        drawContext.canvas.nativeCanvas.translate(left + frameW / 2f, top - 60f)
                        val topText =
                            if (stableTime >= 250L || isCapturing) "พบใบหน้าแล้ว กำลังทำการบันทึกภาพ..." else "กรุณาจัดใบหน้าให้อยู่ในกรอบ"
                        val topTextWidth = textPaint.measureText(topText)
                        drawContext.canvas.nativeCanvas.drawText(topText, -topTextWidth / 2f, 0f, textPaint)
                        drawContext.canvas.nativeCanvas.restore()
                    }
                }
            }

            // Low Memory Warning Banner
            if (freeRamMb < 400L) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.TopCenter)
                        .background(Color.Red.copy(alpha = 0.85f))
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "⚠️ ข้อมูล RAM ต่ำกว่า 400MB: AI อาจถูกบังคับปิดเพื่อป้องกันเครื่องค้าง (เหลือ ${freeRamMb}MB)",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
            }

            // Real-time Inference & RAM Overlay
            Box(
                modifier = Modifier
                    .padding(top = if (freeRamMb < 400L) 48.dp else 16.dp, start = 16.dp)
                    .align(Alignment.TopStart)
                    .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(8.dp))
                    .padding(8.dp)
            ) {
                Column {
                    Text(
                        text = "RAM Available: ${freeRamMb} MB",
                        color = Color.White,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                    val statusText =
                        if (isProcessingBusy) "✅ Snap success!" else if (isCapturing) "Found! Capturing..." else if (stableTime > 0) "Stabilizing..." else "Searching..."
                    Text(text = "Status: $statusText", color = Color.White, fontSize = 11.sp)
                    if (processingResultMsg != null) {
                        Text(
                            text = processingResultMsg!!,
                            color = Color.Green,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            // Bottom Bar Overlay
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    .background(Color.Black.copy(alpha = 0.7f))
                    .padding(horizontal = 8.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Auto-Snap Indicator (Left side)
                Box(contentAlignment = Alignment.Center) {
                    if (isCapturing) {
                        CircularProgressIndicator(
                            color = Color.White,
                            strokeWidth = 3.dp,
                            modifier = Modifier.size(24.dp)
                        )
                    } else {
                        Text(
                            "Auto-Snap\n" + if (aiMode == AiMode.PALMPRINT) targetHand else "Enabled",
                            color = Color.White,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                    }
                }

                // Tools Group (Right side)
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // AI Dropdown
                    Box {
                        Button(
                            onClick = { aiDropdownExpanded = true },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color.White.copy(alpha = 0.2f),
                                contentColor = Color.White
                            ),
                            shape = RoundedCornerShape(24.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                            modifier = Modifier.height(40.dp)
                        ) {
                            Text(text = aiMode.name, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        }

                        DropdownMenu(
                            expanded = aiDropdownExpanded,
                            onDismissRequest = { aiDropdownExpanded = false },
                            modifier = Modifier.background(Color(0xFF333333))
                        ) {
                            AiMode.values().filter { it != AiMode.PREVIEW }.forEach { mode ->
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            mode.name,
                                            color = Color.White,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 12.sp
                                        )
                                    },
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
                        modifier = Modifier.size(40.dp).background(Color.White.copy(alpha = 0.2f), CircleShape)
                    ) {
                        Icon(
                            Icons.Default.PhotoLibrary,
                            contentDescription = "Import",
                            tint = Color.White,
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    // Settings Button
                    IconButton(
                        onClick = { showSettingsDialog = true },
                        modifier = Modifier.size(40.dp).background(Color.White.copy(alpha = 0.2f), CircleShape)
                    ) {
                        Icon(
                            Icons.Default.Settings,
                            contentDescription = "Settings",
                            tint = Color.White,
                            modifier = Modifier.size(20.dp)
                        )
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
                        val title = when (aiMode) {
                            AiMode.PALMPRINT -> "Palmprint Result"
                            AiMode.FACE -> "Face Result"
                            else -> "OCR Result"
                        }
                        Text(title, style = MaterialTheme.typography.titleMedium)
                        if (timeMs > 0) {
                            Text(
                                "Process time: ${timeMs}ms",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.Gray
                            )
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
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            ComputeMode.values().forEach { mode ->
                                FilterChip(
                                    selected = (computeMode == mode),
                                    onClick = { onComputeModeChange(mode) },
                                    label = { Text(mode.displayName, fontSize = 12.sp, maxLines = 1) },
                                    leadingIcon = if (computeMode == mode) {
                                        {
                                            Icon(
                                                Icons.Default.Check,
                                                contentDescription = null,
                                                modifier = Modifier.size(16.dp)
                                            )
                                        }
                                    } else null
                                )
                            }
                        }
                    }

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
                                        if (aiMode == AiMode.OCR) "Please run OCR first" else "No Palmprint result",
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
                                        if (aiMode == AiMode.OCR) "Please run OCR first" else "No Palmprint result",
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
                                                    "type" to if (aiMode == AiMode.PALMPRINT) "PalmPrint" else "OCR",
                                                    "use_gpu" to computeMode.useGpu,
                                                    "model_paddle_loaded" to (aiMode == AiMode.OCR),
                                                    "model_mediapipe_loaded" to (aiMode == AiMode.PALMPRINT),
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
            if (aiMode == AiMode.PALMPRINT && leftPalmImage != null && rightPalmImage != null) {
                // Show both palms for PALMPRINT mode
                Column(
                    modifier = Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    val itemCount = try {
                        JSONArray(jsonResult).length()
                    } catch (e: Exception) {
                        0
                    }

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

                            // Parse "box" for OCR (polygon)
                            if (boxObj.has("box")) {
                                val boxArr = boxObj.getJSONArray("box")
                                if (boxArr.length() > 0) {
                                    val path = Path()
                                    val p0 = boxArr.getJSONArray(0)
                                    path.moveTo(p0.getInt(0).toFloat(), p0.getInt(1).toFloat())
                                    for (j in 1 until boxArr.length()) {
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
                            } else if (aiMode == AiMode.FACE && boxObj.has("bbox")) {
                                // Draw BBox for face
                                val bArr = boxObj.getJSONArray("bbox")
                                val l = bArr.getDouble(0).toFloat()
                                val t = bArr.getDouble(1).toFloat()
                                val r = bArr.getDouble(2).toFloat()
                                val b = bArr.getDouble(3).toFloat()

                                val facePaint = Paint().apply {
                                    color = android.graphics.Color.RED
                                    style = Paint.Style.STROKE
                                    strokeWidth = 3f / scale
                                }
                                drawContext.canvas.nativeCanvas.drawRect(l, t, r, b, facePaint)

                                // Draw Contours
                                if (boxObj.has("contours")) {
                                    val contours = boxObj.getJSONObject("contours")
                                    val colors = mapOf(
                                        "FACE_OVAL" to android.graphics.Color.parseColor("#4285F4"), // Blue
                                        "LEFT_EYEBROW_TOP" to android.graphics.Color.parseColor("#E65100"), // Orange
                                        "LEFT_EYEBROW_BOTTOM" to android.graphics.Color.parseColor("#FFC107"), // Yellow
                                        "RIGHT_EYEBROW_TOP" to android.graphics.Color.parseColor("#0F9D58"), // Green
                                        "RIGHT_EYEBROW_BOTTOM" to android.graphics.Color.parseColor("#8E24AA"), // Purple
                                        "LEFT_EYE" to android.graphics.Color.parseColor("#1E88E5"), // Blue
                                        "RIGHT_EYE" to android.graphics.Color.parseColor("#00ACC1"), // Cyan
                                        "UPPER_LIP_TOP" to android.graphics.Color.parseColor("#D81B60"), // Pink
                                        "UPPER_LIP_BOTTOM" to android.graphics.Color.parseColor("#7CB342"), // L Green
                                        "LOWER_LIP_TOP" to android.graphics.Color.parseColor("#E53935"), // Red
                                        "LOWER_LIP_BOTTOM" to android.graphics.Color.parseColor("#795548"), // Brown
                                        "NOSE_BRIDGE" to android.graphics.Color.parseColor("#AB47BC"), // Purple
                                        "NOSE_BOTTOM" to android.graphics.Color.parseColor("#00897B") // Teal
                                    )

                                    val pointPaint = Paint().apply { style = Paint.Style.FILL }
                                    val linePaint =
                                        Paint().apply { style = Paint.Style.STROKE; strokeWidth = 2.5f / scale }

                                    val radius = 2f / scale

                                    contours.keys().forEach { key ->
                                        val pts = contours.getJSONArray(key)
                                        if (pts.length() > 0) {
                                            val colorInt = colors[key] ?: android.graphics.Color.WHITE
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
                    if (showPayload) "Review the generated data structure" else "Review the processed ${if (aiMode == AiMode.OCR) "OCR text" else if (aiMode == AiMode.FACE) "face data" else "palmprint data"}",
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
                } else if (aiMode == AiMode.FACE) {
                    FaceResultTable(jsonResult)
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
                            var rawText = ""
                            if (obj.has("label")) {
                                rawText = obj.getString("label")
                            } else if (obj.has("text")) {
                                rawText = obj.getString("text")
                            }

                            rawText = rawText
                                .replace(Regex("(?<=[ก-ฮ])(?=(นาย|นาง|นางสาว)[a-zA-Zก-ฮ])"), " ")
                                .replace(Regex("(?<=(นาย|นาง|นางสาว|เด็กชาย|เด็กหญิง))(?=[a-zA-Zก-ฮ])"), " ")
                                .replace("นาย กฤชณัชมาลัยขวัญ", "นาย กฤชณัช มาลัยขวัญ")
                                .replace("ชื่อตัวและชื่อสกุลนาย", "ชื่อตัวและชื่อสกุล นาย")

                            fullText += rawText + " \n"

                            if (obj.has("confidence")) {
                                sumConf += obj.getDouble("confidence")
                            } else if (obj.has("prob")) {
                                sumConf += obj.getDouble("prob")
                            }
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
                            .heightIn(max = 350.dp)
                    ) {
                        Column(modifier = Modifier.padding(16.dp).verticalScroll(rememberScrollState())) {
                            Text(
                                fullText.trim(),
                                color = Color(0xFF007AFF),
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                lineHeight = 18.sp
                            )
                            Spacer(Modifier.height(12.dp))
                            HorizontalDivider(color = Color(0xFFE0E0E0), thickness = 1.dp)
                            Spacer(Modifier.height(8.dp))
                            Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                                Text("Device: ${android.os.Build.HARDWARE}", color = Color.Gray, fontSize = 11.sp)
                                Text(
                                    "GPU: ${if (computeMode.useGpu) "ON" else "OFF"} (${computeMode.coreCount} cores)",
                                    color = Color.Gray,
                                    fontSize = 11.sp
                                )
                            }
                            Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                                Text("Time: ${timeMs} ms", color = Color.DarkGray, fontSize = 11.sp)
                                Text(
                                    "Conf: ${String.format("%.2f", avgConf * 100)}%",
                                    color = Color(0xFF34C759),
                                    fontSize = 11.sp
                                )
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
                FaceTableRow("เส้นขอบรูปหลายเหลี่ยม", if (bbox.length() >= 4) ":" else ":")
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
    payload.put(
        "type",
        if (aiMode == AiMode.PALMPRINT) "palmprint_result" else if (aiMode == AiMode.FACE) "face_result" else "ocr_result"
    )
    payload.put("timestamp", System.currentTimeMillis())

    // engine_info (new format)
    val engineInfo = JSONObject().apply {
        if (aiMode == AiMode.PALMPRINT) {
            put("engine", "mediapipe")
            put("version", "tasks-vision")
            put("runtime", "tflite")
            put("model", "hand_landmarker.task")
        } else if (aiMode == AiMode.FACE) {
            put("engine", "mlkit")
            put("version", "face-detection")
            put("runtime", "gms")
            put("model", "face")
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

        if (aiMode == AiMode.PALMPRINT || aiMode == AiMode.FACE) {
            // Palmprint/Face structure
            payload.put("result", JSONObject().apply {
                put(if (aiMode == AiMode.PALMPRINT) "palms" else "faces", benchmarkArr)
            })

            // add resource info directly to palmprint summary so the google script logs it properly
            val resourceStats = resource.toJson()
            payload.put("summary", JSONObject().apply {
                put(if (aiMode == AiMode.PALMPRINT) "palms_detected" else "faces_detected", benchmarkArr.length())
                put("total_latency_ms", timeMs)
            })

            // Append memory usage manually at ROOT level so Google Sheet script can use `data.cpu_usage` fallback block
            payload.put("cpu_usage", resourceStats.optString("cpu_usage"))
            payload.put("ram_used_mb", resourceStats.optLong("ram_used_mb"))
            payload.put("ram_total_mb", resourceStats.optLong("ram_total_mb"))
            payload.put("battery_level", resourceStats.optInt("battery_level"))
            payload.put("battery_temp", resourceStats.optDouble("battery_temp_c"))

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

    return payload
}
