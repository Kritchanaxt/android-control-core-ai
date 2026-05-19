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
                    AiMode.VERIFIED_AUTO_CAPTURE -> "Verified Auto Capture (Pose + Face)"
                    AiMode.POSE_DETECTION -> "Pose Detection"
                    AiMode.SELFIE_SEGMENTATION -> "Selfie Segmentation"
                    AiMode.MULTI_CLASS_SELFIE_SEGMENTATION -> "Multi-class Selfie Segmentation"
                    AiMode.VERIFICATION_SEGMENTATION -> "Verification Segmetation (Multi-class Selfie + Hand detection)"
                    AiMode.SUBJECT_SEGMENTATION -> "Subject Segmentation"
                    AiMode.OBJECT_DETECTION -> "Object Detection"
                    AiMode.CUSTOM_OBJECT_DETECTION -> "Custom Object Detection"
                    AiMode.TEXT_RECOGNITION -> "Text Recognition"
                    AiMode.IDENTITY_VERIFICATION -> "Identity Verification (OCR + Face)"
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
                                    AIManager.switchProcessor(context, mode)
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


