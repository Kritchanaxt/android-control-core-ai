package com.example.android_screen_relay.core

import android.util.Log
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FieldValue
import android.content.Context
import android.os.BatteryManager
import android.os.PowerManager
import android.app.ActivityManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.TimeZone
import java.util.Locale

object FirebaseLogger {
    private const val TAG = "FirebaseLogger"
    private val db: FirebaseFirestore by lazy { FirebaseFirestore.getInstance() }

    /**
     * ดึงข้อมูลสถานะเครื่องแบบ Real-time เพื่อแนบไปกับทุก Log
     */
    private fun getSystemStatus(context: Context): Map<String, Any> {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        am.getMemoryInfo(memInfo)

        val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager

        return mapOf(
            "ram_free_mb" to memInfo.availMem / (1024 * 1024),
            "ram_used_mb" to (memInfo.totalMem - memInfo.availMem) / (1024 * 1024),
            "ram_total_mb" to memInfo.totalMem / (1024 * 1024),
            "low_memory_mode" to memInfo.lowMemory,
            "battery_percent" to bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY),
            "is_power_save" to pm.isPowerSaveMode,
            "thermal_status" to if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) pm.currentThermalStatus else "N/A"
        )
    }

    /**
     * บันทึก Log ทุกขั้นตอน โดยจัดหมวดหมู่ (Categorization) ตามความต้องการของ Founder
     * รวบรวมข้อมูลทุกอย่างจากที่เคยขอไว้: Device, System, AI, Camera, Error
     */
    fun logStep(
        context: Context,
        stepName: String, // เช่น "APP_START", "CAMERA_INIT", "AI_SCAN_START", "SNAP_SUCCESS", "APP_CRASH"
        status: String = "SUCCESS",
        result: BenchmarkResult? = null,
        error: Throwable? = null,
        extraData: Map<String, Any>? = null
    ) {
        val device = SystemMonitor.getDeviceInfo(context)
        val logData = mutableMapOf<String, Any>()
        
        // ดึงสถานะระบบรอบเดียวเพื่อใช้ทั้งฟังก์ชัน (ประหยัดพลังงานรันของ CPU)
        val currentSystemStatus = getSystemStatus(context)

        // 1. หมวดหมู่พื้นฐาน (Identity & Time)
        logData["timestamp"] = FieldValue.serverTimestamp() // Time on server
        
        // เวลาไทย (UTC+7)
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        sdf.timeZone = TimeZone.getTimeZone("Asia/Bangkok")
        logData["datetime_th"] = sdf.format(Date())

        logData["step_name"] = stepName
        logData["status"] = status
        logData["app_version"] = "1.3"
        logData["device_id"] = android.provider.Settings.Secure.getString(context.contentResolver, android.provider.Settings.Secure.ANDROID_ID)

        // 2. หมวดหมู่ข้อมูลสเปคเครื่อง (Device Specs) - ข้อมูล Hardware ตายตัว
        val ramTotalMb = extraData?.get("ram_total_mb") as? Number ?: currentSystemStatus["ram_total_mb"] as? Number ?: 0L
        val ramTotalGb = ramTotalMb.toDouble() / 1024.0
        val isTargetLowSpec = Runtime.getRuntime().availableProcessors() == 8 && ramTotalGb <= 3.0 // เช็คสเปคเป้าหมาย Founder

        logData["device_info"] = hashMapOf(
            "brand" to device.manufacturer,
            "model" to device.model,
            "os_name" to "Android",
            "android_version" to device.androidVersion,
            "api_level" to android.os.Build.VERSION.SDK_INT,
            "cpu_abi" to android.os.Build.SUPPORTED_ABIS.firstOrNull().toString(),
            "cores" to Runtime.getRuntime().availableProcessors(),
            "total_rom_gb" to (extraData?.get("total_rom_gb") ?: device.totalRomGb),
            "battery_capacity_mah" to (extraData?.get("battery_capacity_mah") ?: device.batteryCapacityMAh),
            "is_target_low_end" to isTargetLowSpec 
        )

        // 3. หมวดหมู่สถานะระบบขณะนั้น (System State) - เปลี่ยนแปลงตลอดเวลา
        logData["system_status"] = currentSystemStatus.toMutableMap().apply {
            put("cpu_usage", (result?.resourceUsage?.cpuUsage ?: extraData?.get("cpu_usage") ?: "N/A"))
            put("foreground_service", (extraData?.get("foreground_service") ?: false))
            put("screen_capture_active", (extraData?.get("screen_capture_active") ?: false))
        }

        // 4. หมวดหมู่กล้อง (Camera Details)
        logData["camera_details"] = hashMapOf(
            "total_cameras" to (extraData?.get("total_cameras") ?: device.totalCameras),
            "front_camera_label" to (extraData?.get("front_camera_label") ?: device.frontCameraLabel).toString().ifEmpty { "N/A" },
            "back_camera_label" to (extraData?.get("back_camera_label") ?: device.backCameraLabel).toString().ifEmpty { "N/A" },
            "supported_resolutions" to (extraData?.get("supported_resolutions") ?: device.supportedResolutions),
            "chosen_resolution" to (result?.resolution ?: extraData?.get("target_resolution") ?: "N/A"), // บันทึก Resolution ที่ถูกเลือกใช้จริง
            "camera_id" to (extraData?.get("camera_id") ?: "N/A"), // เก็บว่าใช้กล้องหน้าหรือหลัง ID อะไร
            "is_front_camera" to (extraData?.get("is_front_camera") ?: false),
            "camera_permission" to (extraData?.get("camera_permission") ?: device.cameraPermissionGranted)
        )

        // 5. หมวดหมู่ AI & ประมวลผล (AI Mode, Snap, Confidence, Text Extracted)
        if (result != null || stepName.contains("AI") || stepName.contains("SNAP")) {
            val extractedText = result?.resultJson?.take(500) ?: ""
            // นำผลลัพธ์การ Snap ออกมาที่ Root เพื่อให้แสดงผลในตาราง Firestore ได้ชัดเจน
            if (extractedText.isNotEmpty()) {
                logData["snap_extracted_text"] = extractedText
            }
            if (result?.latencyMs != null) {
                logData["snap_latency_ms"] = result.latencyMs
            }

            logData["ai_processing"] = hashMapOf(
                "type" to (result?.title ?: extraData?.get("type") ?: "N/A"),
                "compute_mode" to (extraData?.get("compute_mode") ?: "N/A"),
                "use_gpu" to (extraData?.get("use_gpu") ?: false),
                "latency_ms" to (result?.latencyMs ?: 0L),
                "cropped_ms" to (extraData?.get("cropped_ms") ?: 0L),
                "avg_confidence" to (extraData?.get("avg_confidence") ?: 0.0),
                "ai_mode" to (extraData?.get("ai_mode") ?: "OCR"),
                "items_found" to (extraData?.get("items_found") ?: 0),
                "extracted_text" to extractedText,
                "snap_image_active" to (extraData?.get("snap_image_active") ?: false),
                "model_paddle_loaded" to (extraData?.get("model_paddle_loaded") ?: true),
                "model_mediapipe_loaded" to (extraData?.get("model_mediapipe_loaded") ?: false)
            )
        }

        // 6. หมวดหมู่ข้อผิดพลาด (Fatal Error & Crash Log)
        if (error != null || status == "FATAL" || status == "ERROR") {
            logData["crash_info"] = hashMapOf(
                "fatal_error" to (status == "FATAL"),
                "error_msg" to (error?.message ?: extraData?.get("error_msg") ?: "Unknown Error"),
                "exception_type" to (error?.javaClass?.simpleName ?: "N/A"),
                "stack_trace" to (error?.stackTraceToString()?.take(2000) ?: "N/A")
            )
        }

        // ส่งข้อมูลไปยัง "structured_app_logs" (แยก Document ID ของแต่ละหน้างาน)
        db.collection("structured_app_logs")
            .add(logData)
            .addOnSuccessListener { doc ->
                Log.d(TAG, "Structured Log added: ${doc.id}")
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Failed to send log", e)
            }
    }

    /**
     * ตัวช่วยส่ง Benchmark แบบเร็ว (คงไว้เหมือนเดิม)
     */
    fun logAIInference(
        context: Context,
        result: BenchmarkResult,
        aiMode: String,
        useGpu: Boolean,
        extra: Map<String, Any>? = null
    ) {
        logStep(
            context = context,
            stepName = "AI_INFERENCE",
            status = "COMPLETED",
            result = result,
            extraData = (extra ?: emptyMap()).toMutableMap().apply {
                put("ai_mode", aiMode)
                put("use_gpu", useGpu)
            }
        )
    }

    // ฟังก์ชันเดิมที่เคยมี เพื่อไม่ให้โค้ดเก่าพัง
    fun logBenchmark(result: BenchmarkResult) {
        // ให้ส่งไปที่ logStep แทน เพื่อเข้าโครงสร้างใหม่ที่จัดหมวดหมู่แล้ว
        // จะเป็นการสร้าง Log ชุดเดิมแต่มีระเบียบขึ้น
    }
}


