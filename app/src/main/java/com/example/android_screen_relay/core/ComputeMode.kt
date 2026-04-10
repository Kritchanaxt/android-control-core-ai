package com.example.android_screen_relay.core

import android.content.Context

enum class ComputeMode(val coreCount: Int, val useGpu: Boolean, val displayName: String, val maxResolution: Int) {
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
        
        // All devices default to CPU_4_CORE initially based on new spec requirements
        currentMode = ComputeMode.CPU_4_CORE
        
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
