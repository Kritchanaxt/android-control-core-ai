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

