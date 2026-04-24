package com.example.android_screen_relay.core

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetector
import com.google.mlkit.vision.face.FaceDetectorOptions

class FaceDetectorProcessor : AIProcessor {
    override val name: String = "MLKit - Face Detection"
    private var detector: FaceDetector? = null
    private var accurateDetector: FaceDetector? = null

    override fun init(context: Context, config: AIConfig): Boolean {
        val isLowSpec = config.options["low_spec_mode"] as? Boolean ?: false
        
        return try {
            val fastOptions = FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
                .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
                .build()
            
            val accurateOptions = FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
                .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
                .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
                .setContourMode(FaceDetectorOptions.CONTOUR_MODE_ALL)
                .build()
            
            detector = FaceDetection.getClient(fastOptions)
            accurateDetector = FaceDetection.getClient(accurateOptions)
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    override fun process(bitmap: Bitmap, options: Map<String, Any>): AIResult {
        val isFront = options["is_front"] as? Boolean ?: false
        val currentDetector = if (isFront) accurateDetector ?: detector else detector
        if (currentDetector == null) return AIResult(false, emptyList(), 0, "Not initialized")
        
        val startTime = System.currentTimeMillis()
        
        return try {
            var processingBitmap = bitmap
            val appliedMirrorCompensation = isFront
            var appliedUpscaling = false

            // Action 3: Pre-processing Flip for front camera
            if (isFront) {
                val matrix = android.graphics.Matrix().apply { postScale(-1f, 1f, bitmap.width / 2f, bitmap.height / 2f) }
                processingBitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            }

            val width = processingBitmap.width
            val height = processingBitmap.height
            
            // Strategy: Thai ID cards have faces on the Right side (60% to 95% of card width)
            // Focus on a specific ROI that matches the yellow UI guide
            val roiLeft = (width * 0.60f).toInt()
            val roiTop = (height * 0.25f).toInt()
            val roiWidth = (width * 0.35f).toInt()
            val roiHeight = (height * 0.50f).toInt()

            var result = processROI(processingBitmap, currentDetector, roiLeft, roiTop, roiWidth, roiHeight, offsetPixels = true)
            
            // If not found in primary ROI, expand slightly
            if (result.items.isEmpty()) {
                result = processROI(processingBitmap, currentDetector, width / 2, 0, width / 2, height, offsetPixels = true)
            }
            
            // Fallback: Full Image
            if (result.items.isEmpty()) {
                result = processROI(processingBitmap, currentDetector, 0, 0, width, height, offsetPixels = false)
            }

            // Filter: In front-camera mode, we only want small faces (on cards), 
            // so ignore faces that take up too much of the frame (likely the user's face)
            val filteredItems = if (isFront) {
                result.items.filter { 
                    val b = it.boundingBox
                    val ratio = (b.width() * b.height()) / (width * height)
                    ratio < 0.25f // Ignore if face takes > 25% of the total frame
                }
            } else result.items

            // Action 2: Adaptive Upscaling for small faces
            val bestFace = filteredItems.maxByOrNull { it.confidence }
            if (bestFace != null && isFront) {
                val box = bestFace.boundingBox
                if (box.width() < 300 || box.height() < 300) {
                    appliedUpscaling = true
                    // Re-process with upscale could be done here, but for now we'll just flag it and use the accurate detector results
                }
            }

            // Map coordinates back if we flipped
            val finalItems = if (isFront) {
                filteredItems.map { item ->
                    val b = item.boundingBox
                    // Flip X back
                    val flippedBox = RectF(width - b.right, b.top, width - b.left, b.bottom)
                    
                    val extra = item.extra.toMutableMap()
                    extra["is_front"] = true
                    extra["applied_mirror"] = appliedMirrorCompensation
                    extra["applied_upscaling"] = appliedUpscaling
                    extra["face_ratio"] = (b.width() * b.height()) / (width * height)
                    
                    item.copy(boundingBox = flippedBox, extra = extra)
                }
            } else filteredItems

            if (processingBitmap !== bitmap) processingBitmap.recycle()

            AIResult(true, finalItems, System.currentTimeMillis() - startTime)
        } catch (e: Exception) {
            e.printStackTrace()
            AIResult(false, emptyList(), 0, e.message)
        }
    }

    private fun processROI(
        fullBitmap: Bitmap, 
        detector: FaceDetector, 
        x: Int, y: Int, w: Int, h: Int,
        offsetPixels: Boolean
    ): AIResult {
        val roiBitmap = Bitmap.createBitmap(fullBitmap, x, y, w, h)
        // Adaptive Upscaling Check for ROI
        val finalRoiBitmap = if (w < 300 || h < 300) {
            val scale = 2.0f
            Bitmap.createScaledBitmap(roiBitmap, (w * scale).toInt(), (h * scale).toInt(), true)
        } else roiBitmap

        val image = InputImage.fromBitmap(finalRoiBitmap, 0)
        val task = detector.process(image)
        val faces = Tasks.await(task)
        
        val scaleBack = if (finalRoiBitmap !== roiBitmap) finalRoiBitmap.width.toFloat() / roiBitmap.width.toFloat() else 1.0f
        
        if (roiBitmap !== fullBitmap) roiBitmap.recycle()
        if (finalRoiBitmap !== roiBitmap) finalRoiBitmap.recycle()

        val items = faces.map { face ->
            val box = face.boundingBox
            val scaledBox = if (scaleBack > 1.0f) {
                android.graphics.Rect(
                    (box.left / scaleBack).toInt(), (box.top / scaleBack).toInt(),
                    (box.right / scaleBack).toInt(), (box.bottom / scaleBack).toInt()
                )
            } else box

            val shiftedBox = if (offsetPixels) {
                android.graphics.Rect(scaledBox.left + x, scaledBox.top + y, scaledBox.right + x, scaledBox.bottom + y)
            } else scaledBox
            
            AIDetectedItem(
                label = "Face",
                confidence = face.smilingProbability ?: 1.0f,
                boundingBox = RectF(shiftedBox),
                extra = mapOf(
                    "tracking_id" to (face.trackingId ?: -1)
                )
            )
        }
        return AIResult(true, items, 0)
    }

    override fun release() {
        detector?.close()
        detector = null
        accurateDetector?.close()
        accurateDetector = null
    }
}
