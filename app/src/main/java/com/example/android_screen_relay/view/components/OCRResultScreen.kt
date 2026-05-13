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
    onGalleryClick: () -> Unit,
    selectedOcrModel: String
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
                            AiMode.SELFIE_SEGMENTATION -> "Selfie Result"
                            AiMode.SUBJECT_SEGMENTATION -> "Subject Result"
                            AiMode.TEXT_RECOGNITION -> "ML Kit Result"
                            AiMode.IDENTITY_VERIFICATION -> "Identity Verification Result"
                            AiMode.MULTI_CLASS_SELFIE_SEGMENTATION -> "Selfie Segmentation Result"
                            else -> "OCR Result"
                        }
                        Text(title, style = MaterialTheme.typography.titleMedium)
                        val modelName = when (aiMode) {
                            AiMode.TESSERACT_FAST_OCR -> "Tesseract Fast OCR"
                            AiMode.HAND_DETECTION -> "MediaPipe Hand Gesture"
                            AiMode.FACE_DETECTION -> "ML Kit Face Detection"
                            AiMode.VERIFIED_AUTO_CAPTURE -> "Verified Auto Capture Pipeline"
                            AiMode.SELFIE_SEGMENTATION -> "ML Kit Selfie Segmentation"
                            AiMode.SUBJECT_SEGMENTATION -> "ML Kit Subject Segmentation"
                            AiMode.TEXT_RECOGNITION -> "ML Kit Text Recognition"
                            AiMode.IDENTITY_VERIFICATION -> "Identity Verification ($selectedOcrModel)"
                            AiMode.MULTI_CLASS_SELFIE_SEGMENTATION -> "MediaPipe Multi-Class Selfie Segmentation"
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
                                                    "model_paddle_loaded" to (aiMode == AiMode.PADDLE_OCR || aiMode == AiMode.TESSERACT_FAST_OCR || aiMode == AiMode.IDENTITY_VERIFICATION),
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
                            AiMode.PADDLE_OCR, AiMode.TESSERACT_FAST_OCR, AiMode.IDENTITY_VERIFICATION -> "OCR text"
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
                            if (aiMode == AiMode.FACE_DETECTION || aiMode == AiMode.VERIFIED_AUTO_CAPTURE) {
                                val smiling = obj.optDouble("smiling_prob", -1.0)
                                val leftEye = obj.optDouble("left_eye_open_prob", -1.0)
                                val rightEye = obj.optDouble("right_eye_open_prob", -1.0)
                                rawText = "Face ${i + 1}:"
                                if (smiling >= 0) rawText += " Smiling(${String.format("%.1f", smiling * 100)}%)"
                                if (leftEye >= 0) rawText += " L-Eye(${String.format("%.1f", leftEye * 100)}%)"
                                if (rightEye >= 0) rawText += " R-Eye(${String.format("%.1f", rightEye * 100)}%)"
                                
                                if (aiMode == AiMode.VERIFIED_AUTO_CAPTURE && obj.has("verification_metrics")) {
                                    try {
                                        val vm = obj.getJSONObject("verification_metrics")
                                        val pStep0 = vm.optJSONObject("step_0_ocr")
                                        val pStep1 = vm.optJSONObject("step_1_pose")
                                        val pStep2 = vm.optJSONObject("step_2_face")
                                        
                                        if (pStep0 != null) {
                                            rawText += "\n\n   [OCR Verification]"
                                            rawText += "\n     - ID: ${pStep0.optString("id_found", "N/A")}"
                                            rawText += "\n     - Name: ${pStep0.optString("name_found", "N/A")}"
                                            rawText += "\n     - DOB: ${pStep0.optString("dob_found", "N/A")}"
                                            rawText += "\n     - Extracted: ${pStep0.optString("text_extracted", "N/A")}"
                                        }

                                        if (pStep1 != null) {
                                            rawText += "\n\n   [Pose Verification]"
                                            rawText += "\n     - Hand Detected: ${pStep1.optBoolean("hand_detected")}"
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
                                (aiMode == AiMode.FACE_DETECTION || aiMode == AiMode.VERIFIED_AUTO_CAPTURE) && isSuccess -> "Face Detected"
                                aiMode == AiMode.HAND_DETECTION && isSuccess -> "Palmprint Detected"
                                (aiMode == AiMode.PADDLE_OCR || aiMode == AiMode.TESSERACT_FAST_OCR || aiMode == AiMode.IDENTITY_VERIFICATION || aiMode == AiMode.TEXT_RECOGNITION) && isSuccess -> "Text Extracted"
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

