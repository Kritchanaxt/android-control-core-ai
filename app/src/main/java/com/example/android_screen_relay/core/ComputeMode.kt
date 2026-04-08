package com.example.android_screen_relay.core

import android.content.Context

enum class ComputeMode(val coreCount: Int, val useGpu: Boolean, val displayName: String, val maxResolution: Int) {
    // โหมดตั้งต้นสำหรับสเปคต่ำมาก (RAM 2GB) จะลด Resolution อัตโนมัติเพื่อไม่ให้ OOM
    LOW_END(4, false, "Low-End (4 Cores, Safe RAM)", 480),
    CPU_4_CORE(4, false, "CPU 4 Core", 720),
    CPU_6_CORE(6, false, "CPU 6 Core", 1080),
    GPU(4, true, "GPU", 1080)
}

object ComputeModeManager {
    private var currentMode = ComputeMode.CPU_4_CORE

    fun initByDeviceSpec(context: Context) {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        val memInfo = android.app.ActivityManager.MemoryInfo()
        am.getMemoryInfo(memInfo)
        
        val ramTotalGb = memInfo.totalMem / (1024.0 * 1024.0 * 1024.0)
        
        currentMode = if (ramTotalGb <= 3.0) {
            // ถ้าน้อยกว่า 3GB เราถือเป็นสเปคต่ำ (เป้าหมาย 2GB)
            ComputeMode.LOW_END
        } else {
            ComputeMode.CPU_4_CORE
        }
        
        FirebaseLogger.logStep(
            context,
            "AUTO_CONFIG_RESOURCES",
            "SUCCESS",
            extraData = mapOf("selected_mode" to currentMode.name, "device_ram_gb" to ramTotalGb)
        )
    }

    fun setMode(mode: ComputeMode) {
        currentMode = mode
    }

    fun getMode(): ComputeMode = currentMode
}
