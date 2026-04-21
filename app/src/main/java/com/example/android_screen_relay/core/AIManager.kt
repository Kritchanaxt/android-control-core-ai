package com.example.android_screen_relay.core

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
    val useGpu: Boolean = true,
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
        // 1. Release old with tracking
        activeProcessor?.let { old ->
            SystemMonitor.trackMemoryAction(context, "Release ${old.name}") {
                old.release()
            }
            sendPerformanceNotification(context, "Released ${old.name}")
        }
        
        // 2. Init new with tracking
        activeProcessor = SystemMonitor.trackMemoryAction(context, "Init ${processor.name}") {
            if (processor.init(context, config)) {
                processor
            } else {
                null
            }
        }
        
        activeProcessor?.let {
            sendPerformanceNotification(context, "Loaded ${it.name}")
        }
        
        return activeProcessor != null
    }

    /**
     * Helper to switch processor by name (string)
     */
    fun switchProcessor(context: android.content.Context, modeName: String): Boolean {
        val config = AIConfig(useGpu = true, threads = 4)
        val processor: AIProcessor = when {
            modeName.contains("OCR", ignoreCase = true) -> OCRProcessor()
            modeName.contains("PALM", ignoreCase = true) -> PalmprintProcessor()
            modeName.contains("FACE", ignoreCase = true) -> FaceDetectorProcessor()
            else -> return false
        }
        return switchProcessor(processor, context, config)
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

    private fun sendPerformanceNotification(context: android.content.Context, message: String) {
        try {
            val service = com.example.android_screen_relay.RelayService.getInstance()
            if (service != null) {
                // Determine RAM state for the message
                val res = SystemMonitor.getCurrentResourceUsage(context)
                val fullMsg = "$message | RAM: ${res.ramUsedMb}MB"
                
                // Show as Toast/Notification
                service.showPerformanceEvent(fullMsg)
                
                // Broadcast to WS
                val json = org.json.JSONObject().apply {
                    put("type", "performance_alert")
                    put("message", fullMsg)
                    put("ram_used_mb", res.ramUsedMb)
                    put("timestamp", System.currentTimeMillis())
                }
                service.broadcastMessage(json.toString())
            }
        } catch (e: Exception) {}
    }
}
