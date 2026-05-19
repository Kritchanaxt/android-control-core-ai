package com.example.android_screen_relay.core

import android.graphics.Bitmap
import android.graphics.RectF
import android.util.Log
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write
import com.example.android_screen_relay.presenter.system.ResourceWatchdog

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
     * 🌟 Type-safe processor switch using AiMode enum.
     * This is the preferred method — eliminates fragile string matching.
     */
    fun switchProcessor(context: android.content.Context, mode: AiMode, extraOptions: Map<String, Any> = emptyMap()): Boolean {
        // Avoid redundant switching if the current processor already matches
        val current = getActiveProcessor()
        val alreadyActive = when (mode) {
            AiMode.TESSERACT_FAST_OCR -> current is TesseractOCRProcessor
            AiMode.PADDLE_OCR -> current is OCRProcessor
            AiMode.HAND_DETECTION -> current is PalmprintProcessor
            AiMode.FACE_DETECTION -> current is FaceDetectorProcessor
            AiMode.POSE_DETECTION -> current is PoseDetectorProcessor
            AiMode.MULTI_CLASS_SELFIE_SEGMENTATION -> current is MultiClassSelfieSegmenterProcessor
            AiMode.VERIFICATION_SEGMENTATION -> current is VerificationSegmentationProcessor
            AiMode.SELFIE_SEGMENTATION -> current is SelfieSegmenterProcessor
            AiMode.SUBJECT_SEGMENTATION -> current is SubjectSegmenterProcessor
            AiMode.CUSTOM_OBJECT_DETECTION -> current is CustomObjectDetectorProcessor
            AiMode.OBJECT_DETECTION -> current is ObjectDetectorProcessor
            AiMode.TEXT_RECOGNITION -> current is TextRecognitionProcessor
            AiMode.IDENTITY_VERIFICATION -> current is IdentityVerificationProcessor
            AiMode.VERIFIED_AUTO_CAPTURE -> current is VerifiedAutoCaptureProcessor
            AiMode.PREVIEW -> return true // No processor needed for preview
        }

        if (alreadyActive && extraOptions.isEmpty()) {
            Log.d("AIManager", "Processor for ${mode.name} is already active. Skipping switch.")
            return true
        }

        val isLowSpec = SystemMonitor.isLowSpecDevice(context)
        val config = AIConfig(
            useGpu = true,
            threads = if (isLowSpec) 4 else 6,
            options = mapOf("low_spec_mode" to isLowSpec, "force_gpu" to true) + extraOptions
        )

        val processor: AIProcessor = when (mode) {
            AiMode.TESSERACT_FAST_OCR -> TesseractOCRProcessor()
            AiMode.PADDLE_OCR -> OCRProcessor()
            AiMode.HAND_DETECTION -> PalmprintProcessor()
            AiMode.FACE_DETECTION -> FaceDetectorProcessor()
            AiMode.POSE_DETECTION -> PoseDetectorProcessor()
            AiMode.MULTI_CLASS_SELFIE_SEGMENTATION -> MultiClassSelfieSegmenterProcessor()
            AiMode.VERIFICATION_SEGMENTATION -> VerificationSegmentationProcessor()
            AiMode.SELFIE_SEGMENTATION -> SelfieSegmenterProcessor()
            AiMode.SUBJECT_SEGMENTATION -> SubjectSegmenterProcessor()
            AiMode.CUSTOM_OBJECT_DETECTION -> CustomObjectDetectorProcessor()
            AiMode.OBJECT_DETECTION -> ObjectDetectorProcessor()
            AiMode.TEXT_RECOGNITION -> TextRecognitionProcessor()
            AiMode.IDENTITY_VERIFICATION -> IdentityVerificationProcessor()
            AiMode.VERIFIED_AUTO_CAPTURE -> VerifiedAutoCaptureProcessor()
            AiMode.PREVIEW -> return true
        }
        return switchProcessor(processor, context, config)
    }

    /**
     * Legacy bridge: switch processor by name string.
     * Delegates to the type-safe AiMode version.
     * @deprecated Use switchProcessor(context, AiMode, extraOptions) instead.
     */
    fun switchProcessor(context: android.content.Context, modeName: String, extraOptions: Map<String, Any> = emptyMap()): Boolean {
        val mode = try {
            AiMode.valueOf(modeName)
        } catch (e: IllegalArgumentException) {
            // Fallback: try matching by substring for backward compatibility
            AiMode.entries.firstOrNull { modeName.contains(it.name, ignoreCase = true) }
                ?: run {
                    Log.e("AIManager", "Unknown mode name: $modeName")
                    return false
                }
        }
        return switchProcessor(context, mode, extraOptions)
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

        // Notify watchdog that AI is still alive
        ResourceWatchdog.notifyHeartbeat()

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
