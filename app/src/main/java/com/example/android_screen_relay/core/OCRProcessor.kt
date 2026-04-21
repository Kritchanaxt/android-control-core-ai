package com.example.android_screen_relay.core

import android.graphics.Bitmap
import android.util.Log

class OCRProcessor : AIProcessor {
    private var appContext: android.content.Context? = null
    private var appThreads: Int = 4
    private var appGpu: Boolean = false
    private var paddleOCR: PaddleOCR? = null
    override val name: String = "PaddleOCRv5"

    override fun init(context: android.content.Context, config: AIConfig): Boolean {
        appContext = context.applicationContext
        appThreads = config.threads
        appGpu = true // Force GPU as requested by P'Bear
        
        try {
            if (paddleOCR == null) {
                paddleOCR = PaddleOCR()
            }
            val isInit = paddleOCR?.initModel(appContext!!, appThreads, appGpu) ?: false
            if (!isInit) {
                paddleOCR?.release()
                paddleOCR = null
                return false
            }
            return true
        } catch (e: Exception) {
            Log.e("OCR", "Init error", e)
            paddleOCR?.release()
            paddleOCR = null
            return false
        }
    }

    override fun process(bitmap: Bitmap): AIResult {
        if (appContext == null || paddleOCR == null) return AIResult(false, emptyList(), 0, "OCR Not initialized")
        
        val start = System.currentTimeMillis()
        val results = mutableListOf<AIDetectedItem>()
        
        try {
            val jsonResult = paddleOCR?.detect(bitmap)
            // Assuming json mapping if we needed to populate AIDetectedItem
            // Convert PaddleOCR results to Generic AIResult
        } catch (e: Exception) {
            Log.e("OCR", "Process error", e)
        }
        
        val end = System.currentTimeMillis()
        
        return AIResult(
            success = true,
            items = results, 
            processTimeMs = end - start
        )
    }

    override fun release() {
        paddleOCR?.release()
        paddleOCR = null
        appContext = null
    }
}
