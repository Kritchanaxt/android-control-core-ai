package com.example.android_screen_relay.core

import android.graphics.Bitmap
import android.util.Log

class OCRProcessor : AIProcessor {
    private var appContext: android.content.Context? = null
    private var appThreads: Int = 4
    private var appGpu: Boolean = false
    override val name: String = "PaddleOCR"

    override fun init(context: android.content.Context, config: AIConfig): Boolean {
        // Defer PaddleOCR instantiation until process() is called
        appContext = context.applicationContext
        appThreads = config.threads
        appGpu = config.useGpu
        return true
    }

    override fun process(bitmap: Bitmap): AIResult {
        if (appContext == null) return AIResult(false, emptyList(), 0, "OCR Not initialized")
        
        val start = System.currentTimeMillis()
        var paddleOCR: PaddleOCR? = null
        val results = mutableListOf<AIDetectedItem>()
        
        try {
            paddleOCR = PaddleOCR()
            val isInit = paddleOCR.initModel(appContext!!, appThreads, appGpu)
            if (isInit) {
                val jsonResult = paddleOCR.detect(bitmap)
                // Assuming json mapping if we needed to populate AIDetectedItem
                 // Convert PaddleOCR results to Generic AIResult
            }
        } catch (e: Exception) {
            Log.e("OCR", "Process error", e)
        } finally {
            // Nullify/Destroy immediately to return RAM 2GB to system
            paddleOCR?.release()
            paddleOCR = null
        }
        
        val end = System.currentTimeMillis()
        
        return AIResult(
            success = true,
            items = results, 
            processTimeMs = end - start
        )
    }

    override fun release() {
        appContext = null
    }
}
