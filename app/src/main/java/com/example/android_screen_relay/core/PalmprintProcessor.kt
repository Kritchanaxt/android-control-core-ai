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
    override val name: String = "MediaPipe Hand Gesture"
    private var appContext: Context? = null
    private var appConfig: AIConfig? = null
    private var handLandmarker: HandLandmarker? = null

    override fun init(context: Context, config: AIConfig): Boolean {
        this.appContext = context.applicationContext
        this.appConfig = config
        
        try {
            if (handLandmarker == null) {
                val baseOptionsBuilder = BaseOptions.builder().setModelAssetPath("hand_landmarker.task")
                if (config.useGpu) {
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
            }
            return true
        } catch (e: Exception) {
            e.printStackTrace()
            return false
        }
    }

    override fun process(bitmap: Bitmap, options: Map<String, Any>): AIResult {
        if (appContext == null || appConfig == null || handLandmarker == null) return AIResult(false, emptyList(), 0, "Not initialized")
        val isFront = options["is_front"] as? Boolean ?: false
        val startTime = System.currentTimeMillis()
        
        return try {
            var processingBitmap = bitmap
            if (isFront) {
                val matrix = android.graphics.Matrix().apply { postScale(-1f, 1f, bitmap.width / 2f, bitmap.height / 2f) }
                processingBitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            }

            val mpImage = BitmapImageBuilder(processingBitmap).build()
            val result = handLandmarker!!.detect(mpImage)
            val duration = System.currentTimeMillis() - startTime
            
            if (isFront && processingBitmap !== bitmap) processingBitmap.recycle()

            if (result.landmarks().isEmpty()) {
                return AIResult(true, emptyList(), duration, "No hand detected")
            }

            val items = mutableListOf<AIDetectedItem>()
            
            val landmarks = result.landmarks()[0]
            val handedness = result.handednesses().getOrNull(0)?.getOrNull(0)
            
            val label = handedness?.categoryName() ?: "Unknown"
            // No manual swap needed here because we already flipped the bitmap to natural orientation before processing.
            // MediaPipe now correctly identifies the hand based on its true anatomical structure.

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

            // Map coordinates back if we flipped for UI drawing
            fun flipX(x: Float): Float = if (isFront) 1.0f - x else x

            val p5 = landmarks[5]; val p9 = landmarks[9]
            val p13 = landmarks[13]; val p17 = landmarks[17]
            val p0 = landmarks[0] // Wrist

            val v1X = flipX((p13.x() + p17.x()) / 2f) * w
            val v1Y = (p13.y() + p17.y()) / 2f * h

            val v2X = flipX((p5.x() + p9.x()) / 2f) * w
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
            val wristX = flipX(p0.x()) * w
            val wristY = p0.y() * h
            val wristDx = wristX - midX
            val wristDy = wristY - midY
            if (nx * wristDx + ny * wristDy < 0) {
                nx = -nx
                ny = -ny
            }

            // ROI Center is 0.9d away from mid point along normal vector
            val cx = midX + nx * 0.9f * d
            val cy = midY + ny * 0.9f * d
            val roiSize = 1.4f * d

            // Calculate rotation to make palm upright
            val angleRad = kotlin.math.atan2(ny.toDouble(), nx.toDouble())
            val angleDeg = Math.toDegrees(angleRad).toFloat()
            val rotationToUpright = 90f - angleDeg

            items.add(AIDetectedItem(
                label = "Palm ($label)",
                confidence = score,
                boundingBox = RectF(
                    minX * w,
                    minY * h,
                    maxX * w,
                    maxY * h
                ).let { rect ->
                    // Map the bounding box back to screen space if flipped
                    if (isFront) RectF(w - rect.right, rect.top, w - rect.left, rect.bottom) else rect
                },
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
        }
    }

    override fun release() {
        handLandmarker?.close()
        handLandmarker = null
        appContext = null
        appConfig = null
    }
}
