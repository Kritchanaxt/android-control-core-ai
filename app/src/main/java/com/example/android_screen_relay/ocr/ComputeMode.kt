package com.example.android_screen_relay.ocr

enum class ComputeMode(val coreCount: Int, val useGpu: Boolean, val displayName: String) {
    CPU_4_CORE(4, false, "CPU 4 Core"),
    CPU_6_CORE(6, false, "CPU 6 Core"),
    GPU(4, true, "GPU")
}
