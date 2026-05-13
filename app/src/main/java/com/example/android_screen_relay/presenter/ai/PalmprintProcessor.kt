package com.example.android_screen_relay.core

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import android.util.Log
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

        return try {
            if (handLandmarker == null) {
                // Force CPU to prevent native JNI crashes on low-end device GPUs
                tryInit(false)
            } else true
        } catch (e: Throwable) {
            Log.e("Palmprint", "Fatal init error", e)
            false
        }
    }

    private fun tryInit(useGpu: Boolean): Boolean {
        return try {
            val baseOptionsBuilder = BaseOptions.builder().setModelAssetPath("hand_landmarker.task")
            if (useGpu) {
                baseOptionsBuilder.setDelegate(com.google.mediapipe.tasks.core.Delegate.GPU)
            } else {
                baseOptionsBuilder.setDelegate(com.google.mediapipe.tasks.core.Delegate.CPU)
            }

            val optionsBuilder = HandLandmarker.HandLandmarkerOptions.builder()
                .setBaseOptions(baseOptionsBuilder.build())
                .setMinHandDetectionConfidence(0.15f)
                .setMinHandPresenceConfidence(0.15f)
                .setMinTrackingConfidence(0.15f)
                .setNumHands(1)
                .setRunningMode(RunningMode.IMAGE)

            handLandmarker = HandLandmarker.createFromOptions(appContext, optionsBuilder.build())
            true
        } catch (e: Throwable) {
            Log.e("Palmprint", "Init attempt failed (GPU=$useGpu)", e)
            false
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

            val w = processingBitmap.width.toFloat()
            val h = processingBitmap.height.toFloat()

            var mpImage = BitmapImageBuilder(processingBitmap).build()
            var result = handLandmarker!!.detect(mpImage)
            var usedScale = 1.0f

            // Retry with padding if no hand is detected
            // This tricks MediaPipe into detecting a close-up palm that fills the whole screen
            // The palm detector usually fails if fingers are missing or if the hand anchor box is too large
            if (result.landmarks().isEmpty()) {
                val scalesToTry = listOf(2.0f, 2.5f, 3.0f)
                for (scale in scalesToTry) {
                    val padW = (w * scale).toInt()
                    val padH = (h * scale).toInt()
                    val paddedBitmap = Bitmap.createBitmap(padW, padH, Bitmap.Config.ARGB_8888)
                    val canvas = android.graphics.Canvas(paddedBitmap)
                    canvas.drawColor(android.graphics.Color.BLACK)
                    val offsetX = (padW - w) / 2f
                    val offsetY = (padH - h) / 2f
                    canvas.drawBitmap(processingBitmap, offsetX, offsetY, null)

                    val paddedMpImage = BitmapImageBuilder(paddedBitmap).build()
                    result = handLandmarker!!.detect(paddedMpImage)
                    paddedBitmap.recycle()

                    if (result.landmarks().isNotEmpty()) {
                        usedScale = scale
                        break
                    }
                }
            }

            val duration = System.currentTimeMillis() - startTime

            if (isFront && processingBitmap !== bitmap) processingBitmap.recycle()

            if (result.landmarks().isEmpty()) {
                // Fallback: Check if the central UI guide area is predominantly skin color.
                // The UI guide is a circle at center (cw/2, ch*0.45) with radius cw*0.38
                val uiCenterX = w / 2f
                val uiCenterY = h * 0.45f
                val uiRadius = w * 0.38f

                val startX = (uiCenterX - uiRadius * 0.7f).toInt().coerceAtLeast(0)
                val startY = (uiCenterY - uiRadius * 0.7f).toInt().coerceAtLeast(0)
                val endX = (uiCenterX + uiRadius * 0.7f).toInt().coerceAtMost(w.toInt() - 1)
                val endY = (uiCenterY + uiRadius * 0.7f).toInt().coerceAtMost(h.toInt() - 1)

                val boxW = endX - startX
                val boxH = endY - startY

                if (boxW > 0 && boxH > 0) {
                    val pixels = IntArray(boxW * boxH)
                    bitmap.getPixels(pixels, 0, boxW, startX, startY, boxW, boxH)

                    var skinPixels = 0
                    var totalPixels = 0
                    val step = 10 // sample every 10th pixel for speed

                    for (i in pixels.indices step step) {
                        totalPixels++
                        val color = pixels[i]
                        val r = (color shr 16) and 0xFF
                        val g = (color shr 8) and 0xFF
                        val b = color and 0xFF

                        // Enhanced skin color heuristic for fallback
                        if (r > 45 && g > 30 && b > 20 &&
                            r > g && r > b && (r - g) > 10) {
                            skinPixels++
                        }
                    }

                    if (totalPixels > 0 && (skinPixels.toFloat() / totalPixels) > 0.4f) {
                        val d = uiRadius * 2f
                        // For fallback, use the target hand requested by options to reflect correct scanning intent
                        val targetHand = options["target_hand"] as? String ?: "Unknown"
                        val items = mutableListOf<AIDetectedItem>()
                        items.add(AIDetectedItem(
                            label = "Palm ($targetHand)",
                            confidence = 0.9f,
                            boundingBox = RectF(
                                (uiCenterX - uiRadius).coerceAtLeast(0f),
                                (uiCenterY - uiRadius).coerceAtLeast(0f),
                                (uiCenterX + uiRadius).coerceAtMost(w),
                                (uiCenterY + uiRadius).coerceAtMost(h)
                            ).let { rect ->
                                if (isFront) RectF(w - rect.right, rect.top, w - rect.left, rect.bottom) else rect
                            },
                            extra = mapOf(
                                "hand" to targetHand,
                                "roi_dist_d" to d,
                                "area_type" to "fallback",
                                "landmarks_count" to 0,
                                "palm_roi" to mapOf(
                                     "center_x" to uiCenterX,
                                     "center_y" to uiCenterY,
                                     "size" to d * 1.1f, // Crop size based on UI guide
                                     "rotation" to 0f
                                )
                            )
                        ))
                        return AIResult(true, items, duration, "Close-up fallback used")
                    }
                }

                return AIResult(true, emptyList(), duration, "No hand detected")
            }

            val items = mutableListOf<AIDetectedItem>()

            val landmarks = result.landmarks()[0]
            val handedness = result.handednesses().getOrNull(0)?.getOrNull(0)

            val label = handedness?.categoryName() ?: "Unknown"
            val score = handedness?.score() ?: 0f

            val padW = w * usedScale
            val padH = h * usedScale
            val padOffsetX = (padW - w) / 2f
            val padOffsetY = (padH - h) / 2f

            fun px(lm: com.google.mediapipe.tasks.components.containers.NormalizedLandmark): Float {
                return (lm.x() * padW) - padOffsetX
            }
            fun py(lm: com.google.mediapipe.tasks.components.containers.NormalizedLandmark): Float {
                return (lm.y() * padH) - padOffsetY
            }

            val palmLandmarks = listOf(0, 1, 2, 5, 9, 13, 17)
            var minX = Float.MAX_VALUE
            var minY = Float.MAX_VALUE
            var maxX = Float.MIN_VALUE
            var maxY = Float.MIN_VALUE

            for (index in landmarks.indices) {
                if (index in palmLandmarks) {
                    val lm = landmarks[index]
                    val lmx = px(lm)
                    val lmy = py(lm)
                    minX = minX.coerceAtMost(lmx)
                    minY = minY.coerceAtMost(lmy)
                    maxX = maxX.coerceAtLeast(lmx)
                    maxY = maxY.coerceAtLeast(lmy)
                }
            }

            // Constrain bounding box to original image dimensions
            minX = minX.coerceIn(0f, w)
            minY = minY.coerceIn(0f, h)
            maxX = maxX.coerceIn(0f, w)
            maxY = maxY.coerceIn(0f, h)

            // Map coordinates back if we flipped for UI drawing
            fun flipX(x: Float): Float = if (isFront) w - x else x

            val p5 = landmarks[5]; val p9 = landmarks[9]
            val p13 = landmarks[13]; val p17 = landmarks[17]
            val p0 = landmarks[0] // Wrist

            val v1X = flipX((px(p13) + px(p17)) / 2f)
            val v1Y = (py(p13) + py(p17)) / 2f

            val v2X = flipX((px(p5) + px(p9)) / 2f)
            val v2Y = (py(p5) + py(p9)) / 2f

            val dx = v2X - v1X
            val dy = v2Y - v1Y
            val d = sqrt((dx * dx + dy * dy).toDouble()).toFloat()

            val midX = (v1X + v2X) / 2f
            val midY = (v1Y + v2Y) / 2f

            // Normal vector
            val ux = dx / d
            val uy = dy / d
            var nx = -uy
            var ny = ux

            // Ensure normal points towards wrist
            val wristX = flipX(px(p0))
            val wristY = py(p0)
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
                    minX,
                    minY,
                    maxX,
                    maxY
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
        } catch (e: Throwable) {
            Log.e("Palmprint", "Process error", e)
            AIResult(false, emptyList(), 0, e.message ?: "Unknown process error")
        }
    }

    override fun release() {
        handLandmarker?.close()
        handLandmarker = null
        appContext = null
        appConfig = null
    }
}
