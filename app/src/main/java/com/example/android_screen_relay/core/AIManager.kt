package com.example.android_screen_relay.core

import android.graphics.Bitmap
import android.graphics.RectF
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * Interface for AI Processors to allow switching and lifecycle management
 */
interface AIProcessor {
    val name: String
    fun init(context: android.content.Context, config: AIConfig): Boolean
    fun process(bitmap: Bitmap, options: Map<String, Any> = emptyMap()): AIResult
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
 * Manager to handle multiple AI models and ensure only one is active or managed properly.
 * Fixed to be thread-safe to prevent crashes when switching models while frames are processing.
 */
object AIManager {
    private var activeProcessor: AIProcessor? = null
    private val lock = ReentrantReadWriteLock()
    
    // A flag to quickly skip processing during transitions
    @Volatile
    private var isSwitching = false
    
    fun switchProcessor(processor: AIProcessor, context: android.content.Context, config: AIConfig): Boolean {
        // Use write lock to ensure no one is processing or reading while we switch
        lock.write {
            isSwitching = true
            try {
                // 1. Release old with tracking
                activeProcessor?.let { old ->
                    SystemMonitor.trackMemoryAction(context, "Release ${old.name}") {
                        old.release()
                    }
                    sendPerformanceNotification(context, "Released ${old.name}")
                }
                
                // 2. Clear first to ensure no one uses the stale processor
                activeProcessor = null
                
                // 3. Init new with tracking
                val newProcessor = SystemMonitor.trackMemoryAction(context, "Init ${processor.name}") {
                    if (processor.init(context, config)) {
                        processor
                    } else {
                        null
                    }
                }
                
                activeProcessor = newProcessor
                
                activeProcessor?.let {
                    sendPerformanceNotification(context, "Loaded ${it.name}")
                }
                
                return activeProcessor != null
            } finally {
                isSwitching = false
            }
        }
    }

    /**
     * Helper to switch processor by name (string)
     */
    fun switchProcessor(context: android.content.Context, modeName: String): Boolean {
        val isLowSpec = SystemMonitor.isLowSpecDevice(context)
        val config = AIConfig(
            useGpu = true, // Force GPU as requested
            threads = if (isLowSpec) 4 else 6, // Increase threads even on low spec if GPU fails
            options = mapOf("low_spec_mode" to isLowSpec, "force_gpu" to true)
        )
        val processor: AIProcessor = when {
            modeName.contains("OCR", ignoreCase = true) -> OCRProcessor()
            modeName.contains("PALM", ignoreCase = true) -> PalmprintProcessor()
            modeName.contains("FACE", ignoreCase = true) -> FaceDetectorProcessor()
            modeName.contains("POSE", ignoreCase = true) -> PoseDetectorProcessor()
            modeName.contains("SELFIE", ignoreCase = true) -> SelfieSegmenterProcessor()
            modeName.contains("SUBJECT", ignoreCase = true) -> SubjectSegmenterProcessor()
            modeName.contains("CUSTOM_OBJECT", ignoreCase = true) -> CustomObjectDetectorProcessor()
            modeName.contains("OBJECT", ignoreCase = true) -> ObjectDetectorProcessor()
            else -> return false
        }
        return switchProcessor(processor, context, config)
    }
    
    fun getActiveProcessor(): AIProcessor? = lock.read { activeProcessor }
    
    fun paddleOCRLoaded(): Boolean = lock.read {
        activeProcessor?.name?.contains("PaddleOCR", ignoreCase = true) == true
    }
    
    // Performance metrics
    private var frameCount = 0
    private var lastFpsTimestamp = 0L
    private var currentFps = 0
    private var lastDetectorTimeMs = 0L

    fun getFPS(): Int = currentFps
    fun getLastLatency(): Long = lastDetectorTimeMs

    /**
     * Thread-safe process call
     */
    fun process(bitmap: Bitmap, options: Map<String, Any> = emptyMap()): AIResult? {
        if (isSwitching) return null
        
        val start = System.currentTimeMillis()
        
        // Use read lock to allow multiple detections in parallel but block switching
        val result = lock.read {
            activeProcessor?.process(bitmap, options)
        }
        
        val end = System.currentTimeMillis()
        lastDetectorTimeMs = end - start
        
        // Update FPS
        frameCount++
        if (end - lastFpsTimestamp >= 1000) {
            currentFps = frameCount
            frameCount = 0
            lastFpsTimestamp = end
        }
        
        return result
    }
    
    fun release() {
        lock.write {
            isSwitching = true
            try {
                activeProcessor?.release()
                activeProcessor = null
            } finally {
                isSwitching = false
            }
        }
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
