package com.example.android_screen_relay.core

import android.graphics.Bitmap
import android.graphics.RectF
import android.util.Log
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

    @Volatile
    private var isBusy = false

    // 🌟 Standardized 720p scaling toggle
    private var use720pScaling = true

    fun setScalingEnabled(enabled: Boolean) {
        use720pScaling = enabled
    }

    fun isBusy(): Boolean = isBusy || isSwitching
    
    fun switchProcessor(processor: AIProcessor, context: android.content.Context, config: AIConfig): Boolean {
        // Use write lock to ensure no one is processing or reading while we switch
        // Use tryLock to prevent hanging if something goes wrong
        val writeLock = lock.writeLock()
        if (!writeLock.tryLock(5, java.util.concurrent.TimeUnit.SECONDS)) {
            Log.e("AIManager", "Failed to acquire write lock for ${processor.name}")
            return false
        }
        
        try {
            isSwitching = true
            
            // 1. Release old with tracking
            activeProcessor?.let { old ->
                try {
                    SystemMonitor.trackMemoryAction(context, "Release ${old.name}") {
                        old.release()
                    }
                    sendPerformanceNotification(context, "Released ${old.name}")
                } catch (e: Exception) {
                    Log.e("AIManager", "Error releasing ${old.name}", e)
                }
            }
            
            // 2. Clear first to ensure no one uses the stale processor
            activeProcessor = null
            
            // 3. Force GC to help memory on low-end devices during switch
            System.gc()
            System.runFinalization()
            
            // 4. Init new with tracking
            val newProcessor = try {
                SystemMonitor.trackMemoryAction(context, "Init ${processor.name}") {
                    if (processor.init(context, config)) {
                        processor
                    } else {
                        Log.e("AIManager", "Init failed for ${processor.name}")
                        null
                    }
                }
            } catch (e: Exception) {
                Log.e("AIManager", "Exception during init for ${processor.name}", e)
                null
            }
            
            activeProcessor = newProcessor
            
            activeProcessor?.let {
                sendPerformanceNotification(context, "Loaded ${it.name}")
            }
            
            return activeProcessor != null
        } finally {
            isSwitching = false
            writeLock.unlock()
        }
    }

    /**
     * Helper to switch processor by name (string)
     */
    fun switchProcessor(context: android.content.Context, modeName: String, extraOptions: Map<String, Any> = emptyMap()): Boolean {
        // 🌟 Fix: Avoid redundant switching if the current processor matches the requested mode
        val current = getActiveProcessor()
        val alreadyActive = when {
            modeName.contains("TESSERACT_FAST", ignoreCase = true) && current is TesseractOCRProcessor -> true
            modeName.contains("OCR", ignoreCase = true) && current is OCRProcessor -> true
            modeName.contains("HAND", ignoreCase = true) && current is PalmprintProcessor -> true
            modeName.contains("FACE", ignoreCase = true) && current is FaceDetectorProcessor -> true
            modeName.contains("POSE", ignoreCase = true) && current is PoseDetectorProcessor -> true
            modeName.contains("MULTI_CLASS_SELFIE", ignoreCase = true) && current is MultiClassSelfieSegmenterProcessor -> true
            modeName.contains("SELFIE", ignoreCase = true) && current is SelfieSegmenterProcessor -> true
            modeName.contains("SUBJECT", ignoreCase = true) && current is SubjectSegmenterProcessor -> true
            modeName.contains("CUSTOM_OBJECT", ignoreCase = true) && current is CustomObjectDetectorProcessor -> true
            modeName.contains("OBJECT", ignoreCase = true) && current is ObjectDetectorProcessor -> true
            modeName.contains("TEXT", ignoreCase = true) && current is TextRecognitionProcessor -> true
            modeName.contains("IDENTITY_VERIFICATION", ignoreCase = true) && current is IdentityVerificationProcessor -> true
            modeName.contains("VERIFIED_AUTO_CAPTURE", ignoreCase = true) && current is VerifiedAutoCaptureProcessor -> true
            else -> false
        }
        
        // 🌟 KEY: For some modes, we might need to RE-INIT if options changed.
        var forceReinit = false

        if (alreadyActive && !forceReinit && extraOptions.isEmpty()) {
            Log.d("AIManager", "Processor for $modeName is already active. Skipping switch.")
            return true
        }

        val isLowSpec = SystemMonitor.isLowSpecDevice(context)
        val config = AIConfig(
            useGpu = true, // Force GPU as requested
            threads = if (isLowSpec) 4 else 6, // Increase threads even on low spec if GPU fails
            options = mapOf("low_spec_mode" to isLowSpec, "force_gpu" to true) + extraOptions
        )
        val processor: AIProcessor = when {
            modeName.contains("TESSERACT_FAST", ignoreCase = true) -> TesseractOCRProcessor()
            modeName.contains("OCR", ignoreCase = true) -> OCRProcessor()
            modeName.contains("HAND", ignoreCase = true) -> PalmprintProcessor()
            modeName.contains("FACE", ignoreCase = true) -> FaceDetectorProcessor()
            modeName.contains("POSE", ignoreCase = true) -> PoseDetectorProcessor()
            modeName.contains("MULTI_CLASS_SELFIE", ignoreCase = true) -> MultiClassSelfieSegmenterProcessor()
            modeName.contains("SELFIE", ignoreCase = true) -> SelfieSegmenterProcessor()
            modeName.contains("SUBJECT", ignoreCase = true) -> SubjectSegmenterProcessor()
            modeName.contains("CUSTOM_OBJECT", ignoreCase = true) -> CustomObjectDetectorProcessor()
            modeName.contains("OBJECT", ignoreCase = true) -> ObjectDetectorProcessor()
            modeName.contains("TEXT", ignoreCase = true) -> TextRecognitionProcessor()
            modeName.contains("IDENTITY_VERIFICATION", ignoreCase = true) -> IdentityVerificationProcessor()
            modeName.contains("VERIFIED_AUTO_CAPTURE", ignoreCase = true) -> VerifiedAutoCaptureProcessor()
            else -> return false
        }
        return switchProcessor(processor, context, config)
    }
    
    fun getActiveProcessor(): AIProcessor? = lock.read { activeProcessor }

    /**
     * Executes a block of code with the active processor safely under a read lock.
     * Use this when you need to cast the processor or perform extra logic alongside processing.
     */
    fun <T> runWithProcessor(block: (AIProcessor?) -> T): T {
        val readLock = lock.readLock()
        // Wait up to 2 seconds for a switch to complete
        if (!readLock.tryLock(2, java.util.concurrent.TimeUnit.SECONDS)) {
            Log.e("AIManager", "Timeout waiting for read lock")
            // Return result of block with null if we can't get the lock
            // This is safer than crashing or blocking indefinitely
            return block(null)
        }
        
        isBusy = true
        return try {
            if (isSwitching) {
                block(null)
            } else {
                block(activeProcessor)
            }
        } finally {
            isBusy = false
            readLock.unlock()
        }
    }
    
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
     * Thread-safe process call with automatic 720p scaling for performance and memory stability.
     */
    fun process(bitmap: Bitmap, options: Map<String, Any> = emptyMap()): AIResult? {
        if (isSwitching) return null
        
        val start = System.currentTimeMillis()
        
        // 1. Resize if needed (Target 720p)
        var processingBitmap = bitmap
        var scaleX = 1.0f
        var scaleY = 1.0f
        var wasResized = false

        if (use720pScaling) {
            val minDim = minOf(bitmap.width, bitmap.height)
            if (minDim > 720) {
                val ratio = 720f / minDim
                val targetW = (bitmap.width * ratio).toInt()
                val targetH = (bitmap.height * ratio).toInt()
                
                try {
                    processingBitmap = Bitmap.createScaledBitmap(bitmap, targetW, targetH, true)
                    scaleX = bitmap.width.toFloat() / targetW
                    scaleY = bitmap.height.toFloat() / targetH
                    wasResized = true
                } catch (e: Exception) {
                    Log.e("AIManager", "Resize failed, using original", e)
                    processingBitmap = bitmap
                }
            }
        }

        // Use read lock to allow multiple detections in parallel but block switching
        val readLock = lock.readLock()
        if (!readLock.tryLock(2, java.util.concurrent.TimeUnit.SECONDS)) {
            if (wasResized) processingBitmap.recycle()
            return null
        }
        
        isBusy = true
        var result = try {
            activeProcessor?.process(processingBitmap, options)
        } finally {
            isBusy = false
            readLock.unlock()
        }

        // 2. Scale back coordinates if was resized
        if (result != null && wasResized) {
            val scaledItems = result.items.map { item ->
                val originalBox = CoordinateMapper.scaleRect(item.boundingBox, scaleX, scaleY)
                
                // Also handle extra bitmaps if any (e.g. masks in SubjectSegmenter)
                val newExtra = item.extra.toMutableMap()
                if (newExtra.containsKey("mask_bitmap")) {
                    val mask = newExtra["mask_bitmap"] as? Bitmap
                    if (mask != null) {
                        val targetW = (mask.width * scaleX).toInt()
                        val targetH = (mask.height * scaleY).toInt()
                        newExtra["mask_bitmap"] = Bitmap.createScaledBitmap(mask, targetW, targetH, true)
                        mask.recycle()
                    }
                }
                if (newExtra.containsKey("combined_subject_bitmap")) {
                    val combined = newExtra["combined_subject_bitmap"] as? Bitmap
                    if (combined != null) {
                        val targetW = (combined.width * scaleX).toInt()
                        val targetH = (combined.height * scaleY).toInt()
                        newExtra["combined_subject_bitmap"] = Bitmap.createScaledBitmap(combined, targetW, targetH, true)
                        combined.recycle()
                    }
                }

                item.copy(boundingBox = originalBox, extra = newExtra)
            }
            result = result.copy(items = scaledItems)
        }

        if (wasResized) {
            processingBitmap.recycle()
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
