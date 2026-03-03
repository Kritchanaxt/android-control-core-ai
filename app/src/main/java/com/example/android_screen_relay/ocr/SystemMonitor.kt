package com.example.android_screen_relay.ocr

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build

object SystemMonitor {
    fun getDeviceInfo(context: Context): DeviceInfo {
        return DeviceInfo(
            model = Build.MODEL,
            manufacturer = Build.MANUFACTURER,
            androidVersion = Build.VERSION.RELEASE,
            apiLevel = Build.VERSION.SDK_INT
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
            cpuUsage = "N/A", 
            ramUsedMb = ramUsed,
            ramTotalMb = ramTotal,
            batteryLevel = batteryPct,
            batteryTemp = temp / 10.0f
        )
    }
}