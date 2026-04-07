package com.example.android_screen_relay.ocr

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build

import android.system.Os
import android.system.OsConstants
import android.os.SystemClock
import android.os.Environment
import android.os.StatFs
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraCharacteristics
import kotlin.math.roundToInt
import android.util.Log

object SystemMonitor {
    private var lastAppCpuTime: Long = 0
    private var lastAppUptime: Long = 0

    private fun getCpuUsage(): String {
        return getAppCpuUsage()
    }

    private fun getAppCpuUsage(): String {
        try {
            val reader = java.io.BufferedReader(java.io.FileReader("/proc/self/stat"))
            val line = reader.readLine()
            reader.close()

            val stats = line.split("\\s+".toRegex())
            // Field 13 (utime) and 14 (stime) are usually user and system time for the process
            val utime = stats[13].toLong()
            val stime = stats[14].toLong()
            val cpuTime = utime + stime
            
            val uptime = SystemClock.uptimeMillis()

            if (lastAppUptime == 0L) {
                lastAppCpuTime = cpuTime
                lastAppUptime = uptime
                return "0.0%"
            }

            val timeDelta = uptime - lastAppUptime
            val cpuDelta = cpuTime - lastAppCpuTime

            lastAppCpuTime = cpuTime
            lastAppUptime = uptime

            if (timeDelta == 0L) return "0.0%"

            // Get Clock Ticks per Second (usually 100 on Android)
            val clkIk = Os.sysconf(OsConstants._SC_CLK_TCK)
            
            // Calculate usage: (cpu_ticks / clock_ticks_per_sec) / (time_ms / 1000)
            val cpuSeconds = cpuDelta.toDouble() / clkIk.toDouble()
            val timeSeconds = timeDelta.toDouble() / 1000.0
            
            val usage = (cpuSeconds / timeSeconds) * 100.0
            val cores = Runtime.getRuntime().availableProcessors()
            
            // Usage relative to one core, divide by cores for total system impact
            val totalUsage = usage / cores 
            
            return String.format("%.1f%%", totalUsage)
        } catch (e: Exception) {
            return "N/A"
        }
    }

    fun getDeviceInfo(context: Context): DeviceInfo {
        val statFs = StatFs(Environment.getDataDirectory().path)
        val totalRomBytes = statFs.blockCountLong * statFs.blockSizeLong
        val totalRomGb = totalRomBytes.toDouble() / (1024.0 * 1024.0 * 1024.0)

        // Battery Capacity (Approximate via Power Profile mostly, but battery manager capacity sometimes works)
        val mBatteryManager = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        var cap = 0
        try {
            val mPowerProfileClass = Class.forName("com.android.internal.os.PowerProfile")
            val mPowerProfile = mPowerProfileClass.getConstructor(Context::class.java).newInstance(context)
            val batteryCapacity = mPowerProfileClass.getMethod("getBatteryCapacity").invoke(mPowerProfile) as Double
            cap = batteryCapacity.toInt()
        } catch (e: Exception) {
             // Fallback
            cap = 3400 // Placeholder if reflection fails
        }

        // Cameras
        var backLabel = ""
        var frontLabel = ""
        var hasFront = false
        var hasBack = false
        var totalCams = 0
        val resolutionsSet = mutableSetOf<String>()
        var camPermission = false
        try {
            camPermission = androidx.core.content.ContextCompat.checkSelfPermission(
                context, 
                android.Manifest.permission.CAMERA
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            
            val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as android.hardware.camera2.CameraManager
            val camIds = cameraManager.cameraIdList
            totalCams = camIds.size
            for (cameraId in camIds) {
                val chars = cameraManager.getCameraCharacteristics(cameraId)
                val facing = chars.get(android.hardware.camera2.CameraCharacteristics.LENS_FACING)
                val map = chars.get(android.hardware.camera2.CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                val sizes = map?.getOutputSizes(android.graphics.ImageFormat.JPEG)
                if (sizes != null && sizes.isNotEmpty()) {
                    // Add resolutions
                    sizes.forEach { size ->
                        resolutionsSet.add("${size.width}x${size.height}")
                    }
                    val maxSize = sizes.maxByOrNull { it.width * it.height }
                    if (maxSize != null) {
                        val mpValue = (maxSize.width * maxSize.height) / 100000f
                        val mpFormatted = (mpValue.roundToInt() / 10.0f).toString()
                        val labelText = "Cam $cameraId (${mpFormatted}MP)"
                        
                        if (facing == android.hardware.camera2.CameraCharacteristics.LENS_FACING_BACK) {
                            backLabel = if (backLabel.isEmpty()) labelText else "$backLabel, $labelText"
                            hasBack = true
                        } else if (facing == android.hardware.camera2.CameraCharacteristics.LENS_FACING_FRONT) {
                            frontLabel = if (frontLabel.isEmpty()) labelText else "$frontLabel, $labelText"
                            hasFront = true
                        }
                    }
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("SystemMonitor", "Error getting camera specs", e)
        }

        return DeviceInfo(
            model = Build.MODEL,
            manufacturer = Build.MANUFACTURER,
            androidVersion = Build.VERSION.RELEASE,
            apiLevel = Build.VERSION.SDK_INT,
            totalRomGb = ((totalRomGb * 10.0).roundToInt() / 10.0),
            batteryCapacityMAh = cap,
            backCameraLabel = backLabel.ifEmpty { "N/A" },
            frontCameraLabel = frontLabel.ifEmpty { "N/A" },
            hasFrontCamera = hasFront,
            hasBackCamera = hasBack,
            totalCameras = totalCams,
            supportedResolutions = resolutionsSet.take(5).joinToString(", "), // take first 5 to not bloat json
            cameraPermissionGranted = camPermission,
            osName = "Android" // OS Name requested
        )
    }

    fun getCurrentResourceUsage(context: Context): ResourceUsage {
        val actManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        actManager.getMemoryInfo(memInfo)
        
        val ramUsed = (memInfo.totalMem - memInfo.availMem) / (1024 * 1024)
        val ramTotal = memInfo.totalMem / (1024 * 1024)
        
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val batteryPct = if (level != -1 && scale != -1) (level * 100 / scale) else 0
        val temp = intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0
        
        return ResourceUsage(
            cpuUsage = getCpuUsage(), 
            ramUsedMb = ramUsed,
            ramTotalMb = ramTotal,
            ramFreeMb = ramTotal - ramUsed,
            batteryLevel = batteryPct,
            batteryTemp = temp / 10.0f
        )
    }
}