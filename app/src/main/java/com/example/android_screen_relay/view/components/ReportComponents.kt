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
