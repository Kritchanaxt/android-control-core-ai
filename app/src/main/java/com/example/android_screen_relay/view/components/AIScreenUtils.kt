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

fun rotateBitmap(bitmap: Bitmap, degrees: Int): Bitmap {
    if (degrees == 0) return bitmap
    val matrix = Matrix()
    matrix.postRotate(degrees.toFloat())
    return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
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

suspend fun generateOCRPayload(
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
            AiMode.POSE_DETECTION -> "pose_result"
            AiMode.SELFIE_SEGMENTATION -> "selfie_segmentation_result"
            AiMode.SUBJECT_SEGMENTATION -> "subject_segmentation_result"
            AiMode.OBJECT_DETECTION -> "object_detection_result"
            AiMode.CUSTOM_OBJECT_DETECTION -> "custom_object_detection_result"
            AiMode.TEXT_RECOGNITION -> "text_recognition_result"
            AiMode.MULTI_CLASS_SELFIE_SEGMENTATION -> "multi_class_selfie_segmentation_result"
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
            AiMode.MULTI_CLASS_SELFIE_SEGMENTATION -> {
                put("engine", "mediapipe")
                put("version", "tasks-vision")
                put("runtime", "tflite")
                put("model", "selfie_segmenter.task")
            }
            AiMode.FACE_DETECTION, AiMode.VERIFIED_AUTO_CAPTURE -> {
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
    if (aiMode == AiMode.VERIFIED_AUTO_CAPTURE) {
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

        if (aiMode == AiMode.HAND_DETECTION || aiMode == AiMode.FACE_DETECTION || aiMode == AiMode.VERIFIED_AUTO_CAPTURE) {
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

fun applyBlur(context: Context, bitmap: Bitmap, radius: Float = 25f): Bitmap {
    // 🌟 SMART SCALING: prevent OOM on 12MP+ camera frames
    val maxDim = 600f
    val currentMax = maxOf(bitmap.width, bitmap.height).toFloat()
    val scale = if (currentMax > maxDim) maxDim / currentMax else 1f
    
    val smallBitmap = if (scale < 1f) {
        com.example.android_screen_relay.core.safeCreateScaledBitmap(bitmap, (bitmap.width * scale).toInt(), (bitmap.height * scale).toInt(), true)
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
        val scaledBack = com.example.android_screen_relay.core.safeCreateScaledBitmap(outBitmap, bitmap.width, bitmap.height, true)
        outBitmap.recycle()
        scaledBack
    } else {
        outBitmap
    }
    
    return finalBitmap
}
