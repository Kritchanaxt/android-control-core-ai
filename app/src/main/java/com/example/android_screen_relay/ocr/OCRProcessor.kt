package com.example.android_screen_relay.ocr

import android.graphics.Bitmap
import android.util.Log

class OCRProcessor : AIProcessor {
    private var paddleOCR: PaddleOCR = PaddleOCR()
    private var isInit = false
    override val name: String = "PaddleOCR"

    override fun init(context: android.content.Context, config: AIConfig): Boolean {
        isInit = paddleOCR.initModel(context, config.threads, config.useGpu)
        return isInit
    }

    override fun process(bitmap: Bitmap): AIResult {
        if (!isInit) return AIResult(false, emptyList(), 0, "OCR Not initialized")
        
        val start = System.currentTimeMillis()
        val results = emptyList<AIDetectedItem>() /* TODO paddleocr adapter */
        val end = System.currentTimeMillis()
        
        // Convert PaddleOCR results to Generic AIResult
        // Assuming PaddleOCR returns results in its native format, we convert it here
        // If results is already a List<AIDetectedItem> type or similar logic should be added
        
        return AIResult(
            success = true,
            items = emptyList(), // Placeholder for mapping PaddleOCR result to common format
            processTimeMs = end - start
        )
    }

    override fun release() {
        paddleOCR.release()
        isInit = false
    }
}
