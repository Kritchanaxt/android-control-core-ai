package com.example.android_screen_relay.core

import android.content.Context

enum class ComputeMode(val coreCount: Int, val useGpu: Boolean, val displayName: String, val maxResolution: Int) {
    CPU_4_CORE(4, false, "CPU 4 Core", 720),
    CPU_6_CORE(6, false, "CPU 6 Core", 1080),
    GPU(4, true, "GPU", 1080)
}

object ComputeModeManager {
    private var currentMode = ComputeMode.GPU

    fun initByDeviceSpec(context: Context) {
        // Force GPU mode globally as requested
        currentMode = ComputeMode.GPU
        
        FirebaseLogger.logStep(
            context,
            "AUTO_CONFIG_RESOURCES",
            "SUCCESS",
            extraData = mapOf(
                "selected_mode" to currentMode.name,
                "forced_gpu" to true
            )
        )
    }

    fun setMode(mode: ComputeMode) {
        currentMode = mode
    }

    fun getMode(): ComputeMode = currentMode
}
