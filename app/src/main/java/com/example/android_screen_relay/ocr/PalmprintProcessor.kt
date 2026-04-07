package com.example.android_screen_relay.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult
import kotlin.math.pow
import kotlin.math.sqrt

class PalmprintProcessor : AIProcessor {
    override val name: String = "PalmprintDetection"
    private var appContext: Context? = null
    private var appConfig: AIConfig? = null

    override fun init(context: Context, config: AIConfig): Boolean {
        // Defer media_pipe task load until process() is called
        this.appContext = context.applicationContext
        this.appConfig = config
        return true
    }

    override fun process(bitmap: Bitmap): AIResult {
        if (appContext == null || appConfig == null) return AIResult(false, emptyList(), 0, "Not initialized")
        val startTime = System.currentTimeMillis()
        
        var handLandmarker: HandLandmarker? = null
        return try {
            val baseOptionsBuilder = BaseOptions.builder().setModelAssetPath("hand_landmarker.task")
            if (appConfig!!.useGpu) {
                baseOptionsBuilder.setDelegate(com.google.mediapipe.tasks.core.Delegate.GPU)
            }
            val optionsBuilder = HandLandmarker.HandLandmarkerOptions.builder()
                .setBaseOptions(baseOptionsBuilder.build())
                .setMinHandDetectionConfidence(0.5f)
                .setMinHandPresenceConfidence(0.5f)
                .setMinTrackingConfidence(0.5f)
                .setNumHands(1)
                .setRunningMode(RunningMode.IMAGE)
            handLandmarker = HandLandmarker.createFromOptions(appContext, optionsBuilder.build())
            
            val mpImage = BitmapImageBuilder(bitmap).build()
            val result = handLandmarker.detect(mpImage)
            val duration = System.currentTimeMillis() - startTime
            
            if (result.landmarks().isEmpty()) {
                return AIResult(true, emptyList(), duration, "No hand detected")
            }

            val items = mutableListOf<AIDetectedItem>()
            
            val landmarks = result.landmarks()[0]
            val handedness = result.handednesses().getOrNull(0)?.getOrNull(0)
            
            val label = handedness?.categoryName() ?: "Unknown"
            val score = handedness?.score() ?: 0f

            val palmLandmarks = listOf(0, 1, 2, 5, 9, 13, 17)
            var minX = 1f; var minY = 1f; var maxX = 0f; var maxY = 0f
            
            for (index in landmarks.indices) {
                val landmark = landmarks[index]
                if (index in palmLandmarks) {
                    minX = minX.coerceAtMost(landmark.x())
                    minY = minY.coerceAtMost(landmark.y())
                    maxX = maxX.coerceAtLeast(landmark.x())
                    maxY = maxY.coerceAtLeast(landmark.y())
                }
            }

            val p5 = landmarks[5]
            val p13 = landmarks[13]
            val d = sqrt((p5.x() - p13.x()).pow(2) + (p5.y() - p13.y()).pow(2).toDouble()).toFloat()

            items.add(AIDetectedItem(
                label = "Palm (\$label)",
                confidence = score,
                boundingBox = RectF(minX, minY, maxX, maxY),
                extra = mapOf(
                    "side" to label,
                    "roi_dist_d" to d,
                    "area_type" to "concrete",
                    "landmarks_count" to landmarks.size,
                    "palm_roi" to mapOf(
                         "center_x" to (p5.x() + p13.x()) / 2f,
                         "center_y" to (p5.y() + p13.y()) / 2f,
                         "size_multiplier" to 1.4f
                    )
                )
            ))

            AIResult(true, items, duration)
        } catch (e: Exception) {
            AIResult(false, emptyList(), 0, e.message)
        } finally {
            // Nullify/Destroy immediately after getting the result to return RAM 2GB to system
            handLandmarker?.close()
        }
    }

    override fun release() {
        // Nothing kept in memory persistently
        appContext = null
        appConfig = null
    }
}
