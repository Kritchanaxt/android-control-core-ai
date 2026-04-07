package com.example.android_screen_relay.ocr

import android.graphics.Bitmap
import android.graphics.RectF

/**
 * Interface for AI Processors to allow switching and lifecycle management
 */
interface AIProcessor {
    val name: String
    fun init(context: android.content.Context, config: AIConfig): Boolean
    fun process(bitmap: Bitmap): AIResult
    fun release()
}

data class AIConfig(
    val useGpu: Boolean = false,
    val threads: Int = 4,
    val options: Map<String, Any> = emptyMap()
)

data class AIResult(
    val success: Boolean,
    val items: List<AIDetectedItem>,
    val processTimeMs: Long,
    val errorMessage: String? = null
)

data class AIDetectedItem(
    val label: String,
    val confidence: Float,
    val boundingBox: RectF,
    val extra: Map<String, Any> = emptyMap()
)

/**
 * Manager to handle multiple AI models and ensure only one is active or managed properly
 */
object AIManager {
    private var activeProcessor: AIProcessor? = null
    
    fun switchProcessor(processor: AIProcessor, context: android.content.Context, config: AIConfig): Boolean {
        activeProcessor?.release()
        activeProcessor = if (processor.init(context, config)) {
            processor
        } else {
            null
        }
        return activeProcessor != null
    }
    
    fun getActiveProcessor(): AIProcessor? = activeProcessor
    
    fun paddleOCRLoaded(): Boolean {
        return activeProcessor?.name?.contains("PaddleOCR", ignoreCase = true) == true
    }
    
    fun process(bitmap: Bitmap): AIResult? {
        return activeProcessor?.process(bitmap)
    }
    
    fun release() {
        activeProcessor?.release()
        activeProcessor = null
    }
}
