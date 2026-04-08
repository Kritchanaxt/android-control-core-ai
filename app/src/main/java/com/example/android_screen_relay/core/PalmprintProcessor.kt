package com.example.android_screen_relay.core

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

            val w = bitmap.width.toFloat()
            val h = bitmap.height.toFloat()

            // Reference points based on KBY-AI algorithm
            // v1: Valley between Ring (13) and Pinky (17)
            // v2: Valley between Index (5) and Middle (9)
            val p5 = landmarks[5]; val p9 = landmarks[9]
            val p13 = landmarks[13]; val p17 = landmarks[17]
            val p0 = landmarks[0] // Wrist

            val v1X = (p13.x() + p17.x()) / 2f * w
            val v1Y = (p13.y() + p17.y()) / 2f * h

            val v2X = (p5.x() + p9.x()) / 2f * w
            val v2Y = (p5.y() + p9.y()) / 2f * h

            val dx = v2X - v1X
            val dy = v2Y - v1Y
            val d = sqrt(dx * dx + dy * dy.toDouble()).toFloat()

            val midX = (v1X + v2X) / 2f
            val midY = (v1Y + v2Y) / 2f

            // Normal vector
            val ux = dx / d
            val uy = dy / d
            var nx = -uy
            var ny = ux

            // Ensure normal points towards wrist
            val wristDx = p0.x() * w - midX
            val wristDy = p0.y() * h - midY
            if (nx * wristDx + ny * wristDy < 0) {
                nx = -nx
                ny = -ny
            }

            // ROI Center is 0.9d away from mid point along normal vector
            // (0.2d from mid to top edge, then + 0.7d to center)
            val cx = midX + nx * 0.9f * d
            val cy = midY + ny * 0.9f * d
            val roiSize = 1.4f * d

            // Calculate rotation to make palm upright
            val angleRad = kotlin.math.atan2(ny.toDouble(), nx.toDouble())
            val angleDeg = Math.toDegrees(angleRad).toFloat()
            val rotationToUpright = 90f - angleDeg

            items.add(AIDetectedItem(
                label = "Palm (\$label)",
                confidence = score,
                boundingBox = RectF(
                    minX * w,
                    minY * h,
                    maxX * w,
                    maxY * h
                ),
                extra = mapOf(
                    "hand" to label,
                    "roi_dist_d" to d,
                    "area_type" to "concrete",
                    "landmarks_count" to landmarks.size,
                    "palm_roi" to mapOf(
                         "center_x" to cx,
                         "center_y" to cy,
                         "size" to roiSize,
                         "rotation" to rotationToUpright
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
