package com.example.android_screen_relay.ocr

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
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.math.min
import android.graphics.Paint
import android.graphics.Path
import androidx.compose.ui.window.Dialog
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll

import com.example.android_screen_relay.RelayService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OCRScreen() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val ocr = remember { PaddleOCR() }
    
    // States
    var isInitialized by remember { mutableStateOf(false) }
    var currentImage by remember { mutableStateOf<Bitmap?>(null) }
    var ocrResultJson by remember { mutableStateOf("[]") }
    var ocrTimeMs by remember { mutableStateOf(0L) }
    var isProcessing by remember { mutableStateOf(false) }

    // Init OCR
    LaunchedEffect(Unit) {
        val success = ocr.initModel(context)
        isInitialized = success
        if (!success) {
            Toast.makeText(context, "OCR Init Failed.", Toast.LENGTH_LONG).show()
        }
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
                        currentImage = bitmap
                        ocrResultJson = "[]"
                        ocrTimeMs = 0
                    }
                } catch (e: Exception) {
                    Log.e("OCR", "Load image failed", e)
                }
            }
        }
    }

    if (currentImage != null) {
        // Image Analysis Mode (Screenshot 2 style)
        OCRResultScreen(
            image = currentImage!!,
            jsonResult = ocrResultJson,
            timeMs = ocrTimeMs,
            isProcessing = isProcessing,
            onClear = { 
                currentImage = null
                ocrResultJson = "[]"
                ocrTimeMs = 0
            },
            onRunModel = {
                if (!isProcessing && isInitialized) {
                    isProcessing = true
                    scope.launch(Dispatchers.IO) {
                        try {
                            val start = System.currentTimeMillis()
                            // Ensure mutable ARGB_8888 bitmap for NCNN
                            val mutableBitmap = currentImage!!.copy(Bitmap.Config.ARGB_8888, true)
                            val result = ocr.detect(mutableBitmap)
                            val end = System.currentTimeMillis()
                            withContext(Dispatchers.Main) {
                                ocrResultJson = result
                                ocrTimeMs = end - start
                                isProcessing = false
                            }
                        } catch (e: Exception) {
                            Log.e("OCR", "Detect error", e)
                            withContext(Dispatchers.Main) {
                                isProcessing = false
                                Toast.makeText(context, "OCR Error: ${e.message}", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                }
            },
            onGalleryClick = { galleryLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
        )
    } else {
        // Camera Preview Mode (Screenshot 1 style)
        if (hasPermission && isInitialized) {
            CameraPreviewScreen(
                onImageCaptured = { bitmap ->
                    currentImage = bitmap
                    // Auto run? Or let user press run? The screenshot has "Run Model" button.
                    // Let's create the image view first.
                },
                onGalleryClick = {
                    galleryLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                }
            )
        } else {
             Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Initializing...")
            }
        }
    }
}

@Composable
fun CameraPreviewScreen(
    onImageCaptured: (Bitmap) -> Unit,
    onGalleryClick: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var imageCapture: ImageCapture? by remember { mutableStateOf(null) }

    Box(modifier = Modifier.fillMaxSize()) {
        // Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFFE3F2FD))
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "OCR Dev",
                style = MaterialTheme.typography.titleLarge,
                color = Color.Black
            )
            Icon(Icons.Default.Settings, contentDescription = "Settings", tint = Color.Black)
        }
        
        // Language Badge
        Text(
            text = "Thai",
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 70.dp, end = 16.dp),
            style = MaterialTheme.typography.bodyLarge,
            color = Color.Black
        )

        // Camera Preview
        AndroidView(
            factory = { ctx ->
                val pv = PreviewView(ctx)
                pv.scaleType = PreviewView.ScaleType.FILL_CENTER
                pv
            },
            modifier = Modifier.fillMaxSize().padding(top = 90.dp), // Push down to avoid overlap if needed, or overlay
            update = { pv ->
                val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
                cameraProviderFuture.addListener({
                    val cameraProvider = cameraProviderFuture.get()
                    val preview = Preview.Builder().build()
                    val capture = ImageCapture.Builder()
                        .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                        .build()
                    imageCapture = capture
                    
                    try {
                        cameraProvider.unbindAll()
                        cameraProvider.bindToLifecycle(
                            lifecycleOwner, 
                            CameraSelector.DEFAULT_BACK_CAMERA, 
                            preview, 
                            capture
                        )
                        preview.setSurfaceProvider(pv.surfaceProvider)
                    } catch (e: Exception) {
                        Log.e("Camera", "Bind failed", e)
                    }
                }, ContextCompat.getMainExecutor(context))
            }
        )

        // FABs
        Column(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            FloatingActionButton(
                onClick = onGalleryClick,
                containerColor = Color(0xFF80DEEA)
            ) {
                Icon(Icons.Default.PhotoLibrary, contentDescription = "Gallery", tint = Color.Black)
            }
            
            FloatingActionButton(
                onClick = {
                    val capture = imageCapture ?: return@FloatingActionButton
                    capture.takePicture(
                        ContextCompat.getMainExecutor(context),
                        object : ImageCapture.OnImageCapturedCallback() {
                            override fun onCaptureSuccess(image: ImageProxy) {
                                val buffer = image.planes[0].buffer
                                val bytes = ByteArray(buffer.remaining())
                                buffer.get(bytes)
                                val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                                
                                val rotatedBitmap = rotateBitmap(bitmap, image.imageInfo.rotationDegrees)
                                onImageCaptured(rotatedBitmap)
                                image.close()
                            }
                            override fun onError(exception: ImageCaptureException) {
                                Log.e("Camera", "Capture failed", exception)
                            }
                        }
                    )
                },
                containerColor = Color(0xFF80DEEA)
            ) {
                Icon(Icons.Default.CameraAlt, contentDescription = "Camera", tint = Color.Black)
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
    image: Bitmap,
    jsonResult: String,
    timeMs: Long,
    isProcessing: Boolean,
    onClear: () -> Unit,
    onRunModel: () -> Unit,
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
                        Text("OCR Result", style = MaterialTheme.typography.titleMedium)
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
            BottomAppBar(
                containerColor = MaterialTheme.colorScheme.surfaceContainer,
                contentPadding = PaddingValues(16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Start OCR Button
                    Button(
                        onClick = onRunModel,
                        enabled = !isProcessing,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary
                        )
                    ) {
                        if (isProcessing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                color = MaterialTheme.colorScheme.onPrimary,
                                strokeWidth = 2.dp
                            )
                            Spacer(Modifier.width(8.dp))
                            Text("Processing...")
                        } else {
                            Icon(Icons.Default.Scanner, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Scan Text")
                        }
                    }

                    // Send JSON Button
                    Button(
                        onClick = {
                            if (jsonResult == "[]" || jsonResult.isEmpty()) {
                                Toast.makeText(context, "Please run OCR first", Toast.LENGTH_SHORT).show()
                                return@Button
                            }
                            
                            // Generate Full JSON
                             scope.launch(Dispatchers.IO) {
                                val device = SystemMonitor.getDeviceInfo(context)
                                val resource = SystemMonitor.getCurrentResourceUsage(context)
                                
                                val payload = JSONObject().apply {
                                    put("type", "ocr_result")
                                    put("device", device.toJson())
                                    put("resource", resource.toJson())
                                    
                                    val ocrData = JSONObject()
                                    try {
                                        val jsonArr = JSONArray(jsonResult)
                                        val textLines = JSONObject()
                                        var recognizedText = ""
                                        var totalConfidence = 0.0

                                        if (jsonArr.length() > 0) {
                                            val sb = StringBuilder()
                                            for (i in 0 until jsonArr.length()) {
                                                val item = jsonArr.getJSONObject(i)
                                                val text = item.getString("label")
                                                val conf = item.getDouble("prob")
                                                // "box": [[x0,y0],[x1,y1],[x2,y2],[x3,y3]]
                                                val box = item.getJSONArray("box")

                                                val lineObj = JSONObject()
                                                lineObj.put("id", "line_${i + 1}")
                                                lineObj.put("text", text)
                                                lineObj.put("confidence", conf)

                                                // Calculate bounding box from 4 points
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
                                                
                                                val posObj = JSONObject()
                                                posObj.put("x", minX)
                                                posObj.put("y", minY)
                                                posObj.put("width", maxX - minX)
                                                posObj.put("height", maxY - minY)
                                                lineObj.put("position", posObj)
                                                
                                                textLines.put("line_${i + 1}", lineObj)
                                                
                                                if (sb.isNotEmpty()) sb.append(" ")
                                                sb.append(text)
                                                totalConfidence += conf
                                            }
                                            recognizedText = sb.toString()
                                            totalConfidence /= jsonArr.length()
                                        }

                                        ocrData.put("document_type", "image")
                                        ocrData.put("recognized_text", recognizedText)
                                        ocrData.put("confidence", totalConfidence)
                                        ocrData.put("text_lines", textLines)

                                        val dimensions = JSONObject()
                                        dimensions.put("width", image.width)
                                        dimensions.put("height", image.height)
                                        dimensions.put("unit", "pixel")
                                        ocrData.put("dimensions", dimensions)

                                        // Calculate simulated file size for JPG
                                        val stream = java.io.ByteArrayOutputStream()
                                        image.compress(Bitmap.CompressFormat.JPEG, 80, stream)
                                        val sizeBytes = stream.size()

                                        ocrData.put("fast_rate", 0)
                                        ocrData.put("rack_cooling_rate", 0)
                                        ocrData.put("processing_time", timeMs) // Ensure timeMs is Long/Double
                                        ocrData.put("text_object_count", jsonArr.length())
                                        ocrData.put("output_path", "")
                                        ocrData.put("file_extension", "jpg")
                                        ocrData.put("file_size", sizeBytes)

                                    } catch (e: Exception) {
                                        e.printStackTrace()
                                        ocrData.put("error", e.message)
                                        try { ocrData.put("raw", JSONArray(jsonResult)) } catch(ex: Exception) {}
                                    }
                                    put("ocr_data", ocrData)
                                    put("timestamp", System.currentTimeMillis())
                                }
                                
                                val jsonString = payload.toString(2)
                                
                                withContext(Dispatchers.Main) {
                                    fullJsonOutput = jsonString
                                    showJsonDialog = true
                                    
                                    // Send to WebSocket
                                    val service = RelayService.getInstance()
                                    if (service != null) {
                                        service.broadcastMessage(jsonString)
                                        
                                        // Requirement 7: Add Log to System View
                                        com.example.android_screen_relay.LogRepository.addLog(
                                            component = "OCR",
                                            event = "send_json",
                                            data = mapOf("payload_size" to jsonString.length, "blocks" to try { JSONArray(jsonResult).length() } catch(e:Exception){0}),
                                            type = com.example.android_screen_relay.LogRepository.LogType.OUTGOING
                                        )
                                        
                                        // Toast.makeText(context, "Sent & Logged", Toast.LENGTH_SHORT).show()
                                    } else {
                                        Toast.makeText(context, "Service not running", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            }
                        },
                        enabled = !isProcessing && jsonResult != "[]",  
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer,
                            contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    ) {
                        Icon(Icons.Default.Send, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Send Data")
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
                            }
                        }
                    }
                    drawContext.canvas.nativeCanvas.restore()
                } catch (e: Exception) {
                    // Log.e("OCR", "Draw error", e)
                }
            }

            // Model Info Overlay (Subtle)
            if (isProcessing || jsonResult != "[]") {
                Surface(
                    modifier = Modifier.align(Alignment.TopEnd).padding(16.dp),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.8f),
                    shape = RoundedCornerShape(8.dp),
                    tonalElevation = 2.dp
                ) {
                    Column(Modifier.padding(8.dp)) {
                         Text(
                             text = "Model: PP-OCRv4",
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
                        .padding(horizontal = 24.dp, vertical = 8.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(bottom = 16.dp)
                    ) {
                        Icon(
                            Icons.Default.CheckCircle, 
                            contentDescription = "Success", 
                            tint = Color(0xFF4CAF50),
                            modifier = Modifier.size(32.dp)
                        )
                        Spacer(Modifier.width(16.dp))
                        Column {
                            Text("Data Sent Successfully", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            Text("Payload transmitted via WebSocket", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                        }
                    }

                    HorizontalDivider()
                    Spacer(Modifier.height(16.dp))
                    
                    Text(
                        "JSON Payload", 
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )

                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                        modifier = Modifier.fillMaxWidth().heightIn(max = 350.dp)
                    ) {
                        val scrollState = rememberScrollState()
                        Box(modifier = Modifier.padding(12.dp).verticalScroll(scrollState)) {
                             Text(
                                 text = fullJsonOutput,
                                 fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                 fontSize = 12.sp,
                                 lineHeight = 16.sp,
                                 color = MaterialTheme.colorScheme.onSurfaceVariant
                             )
                        }
                    }

                    Spacer(Modifier.height(24.dp))
                    
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        OutlinedButton(
                            onClick = {
                                clipboardManager.setText(androidx.compose.ui.text.AnnotatedString(fullJsonOutput))
                                Toast.makeText(context, "Copied to clipboard", Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Copy")
                        }
                        
                        Button(
                            onClick = { showJsonDialog = false },
                             modifier = Modifier.weight(1f)
                        ) {
                            Text("Close")
                        }
                    }
                    Spacer(Modifier.height(32.dp))
                }
            }
        }
}

fun minWith(a: Float, b: Float): Float = min(a, b)
