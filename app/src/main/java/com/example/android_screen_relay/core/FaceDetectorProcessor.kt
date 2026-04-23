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

    override fun init(context: Context, config: AIConfig): Boolean {
        val isLowSpec = config.options["low_spec_mode"] as? Boolean ?: false
        
        return try {
            val optionsBuilder = FaceDetectorOptions.Builder()
                .setPerformanceMode(
                    if (isLowSpec) FaceDetectorOptions.PERFORMANCE_MODE_FAST 
                    else FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE
                )
                .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
                .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
            
            if (!isLowSpec) {
                // Contours are heavy, disable on low spec for speed
                optionsBuilder.setContourMode(FaceDetectorOptions.CONTOUR_MODE_ALL)
            }
            
            optionsBuilder.enableTracking()
            detector = FaceDetection.getClient(optionsBuilder.build())
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    override fun process(bitmap: Bitmap): AIResult {
        val currentDetector = detector ?: return AIResult(false, emptyList(), 0, "Not initialized")
        val startTime = System.currentTimeMillis()
        
        return try {
            val width = bitmap.width
            val height = bitmap.height
            
            // Strategy: Thai ID cards usually have faces on the Right side.
            // 1. Try Right 50% ROI
            var result = processROI(bitmap, currentDetector, width / 2, 0, width / 2, height, offsetPixels = true)
            
            // 2. If not found, try Left 50% ROI
            if (result.items.isEmpty()) {
                result = processROI(bitmap, currentDetector, 0, 0, width / 2, height, offsetPixels = false)
            }
            
            // 3. Fallback: Full Image (if ROI strategy didn't catch it somehow)
            if (result.items.isEmpty()) {
                result = processROI(bitmap, currentDetector, 0, 0, width, height, offsetPixels = false)
            }

            AIResult(true, result.items, System.currentTimeMillis() - startTime)
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
        val image = InputImage.fromBitmap(roiBitmap, 0)
        val task = detector.process(image)
        val faces = Tasks.await(task)
        
        if (roiBitmap !== fullBitmap) roiBitmap.recycle()

        val items = faces.map { face ->
            val box = face.boundingBox
            val shiftedBox = if (offsetPixels) {
                android.graphics.Rect(box.left + x, box.top + y, box.right + x, box.bottom + y)
            } else box
            
            val landmarksJson = org.json.JSONObject()
            val leftEye = face.getLandmark(com.google.mlkit.vision.face.FaceLandmark.LEFT_EYE)
            val rightEye = face.getLandmark(com.google.mlkit.vision.face.FaceLandmark.RIGHT_EYE)
            val bottomMouth = face.getLandmark(com.google.mlkit.vision.face.FaceLandmark.MOUTH_BOTTOM)
            
            if (leftEye != null) {
                val lx = if (offsetPixels) leftEye.position.x + x else leftEye.position.x
                landmarksJson.put("LEFT_EYE", org.json.JSONArray().put(lx).put(leftEye.position.y))
            }
            if (rightEye != null) {
                val rx = if (offsetPixels) rightEye.position.x + x else rightEye.position.x
                landmarksJson.put("RIGHT_EYE", org.json.JSONArray().put(rx).put(rightEye.position.y))
            }
            if (bottomMouth != null) {
                val mx = if (offsetPixels) bottomMouth.position.x + x else bottomMouth.position.y
                landmarksJson.put("MOUTH_BOTTOM", org.json.JSONArray().put(mx).put(bottomMouth.position.y))
            }

            AIDetectedItem(
                label = "Face",
                confidence = face.smilingProbability ?: 1.0f,
                boundingBox = RectF(shiftedBox),
                extra = mapOf(
                    "tracking_id" to (face.trackingId ?: -1),
                    "landmarks" to landmarksJson.toString()
                )
            )
        }
        return AIResult(true, items, 0)
    }

    override fun release() {
        detector?.close()
        detector = null
    }
}
