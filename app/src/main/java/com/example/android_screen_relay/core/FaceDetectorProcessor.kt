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
            val image = InputImage.fromBitmap(bitmap, 0)
            val task = currentDetector.process(image)
            
            // Block since process is called synchronously by analyzer
            val faces = Tasks.await(task)
            val duration = System.currentTimeMillis() - startTime
            
            if (faces.isEmpty()) {
                return AIResult(true, emptyList(), duration, "No face detected")
            }
            
            val items = faces.map { face ->
                val contourTypes = mapOf(
                    "FACE_OVAL" to com.google.mlkit.vision.face.FaceContour.FACE,
                    "LEFT_EYEBROW_TOP" to com.google.mlkit.vision.face.FaceContour.LEFT_EYEBROW_TOP,
                    "LEFT_EYEBROW_BOTTOM" to com.google.mlkit.vision.face.FaceContour.LEFT_EYEBROW_BOTTOM,
                    "RIGHT_EYEBROW_TOP" to com.google.mlkit.vision.face.FaceContour.RIGHT_EYEBROW_TOP,
                    "RIGHT_EYEBROW_BOTTOM" to com.google.mlkit.vision.face.FaceContour.RIGHT_EYEBROW_BOTTOM,
                    "LEFT_EYE" to com.google.mlkit.vision.face.FaceContour.LEFT_EYE,
                    "RIGHT_EYE" to com.google.mlkit.vision.face.FaceContour.RIGHT_EYE,
                    "UPPER_LIP_TOP" to com.google.mlkit.vision.face.FaceContour.UPPER_LIP_TOP,
                    "UPPER_LIP_BOTTOM" to com.google.mlkit.vision.face.FaceContour.UPPER_LIP_BOTTOM,
                    "LOWER_LIP_TOP" to com.google.mlkit.vision.face.FaceContour.LOWER_LIP_TOP,
                    "LOWER_LIP_BOTTOM" to com.google.mlkit.vision.face.FaceContour.LOWER_LIP_BOTTOM,
                    "NOSE_BRIDGE" to com.google.mlkit.vision.face.FaceContour.NOSE_BRIDGE,
                    "NOSE_BOTTOM" to com.google.mlkit.vision.face.FaceContour.NOSE_BOTTOM,
                    "LEFT_CHEEK" to com.google.mlkit.vision.face.FaceContour.LEFT_CHEEK,
                    "RIGHT_CHEEK" to com.google.mlkit.vision.face.FaceContour.RIGHT_CHEEK
                )
                
                val contoursJson = org.json.JSONObject()
                contourTypes.forEach { (name, type) ->
                    try {
                        val contour = face.getContour(type)
                        if (contour != null) {
                            val ptsArray = org.json.JSONArray()
                            contour.points.forEach { p ->
                                val ptArr = org.json.JSONArray()
                                ptArr.put(p.x)
                                ptArr.put(p.y)
                                ptsArray.put(ptArr)
                            }
                            contoursJson.put(name, ptsArray)
                        }
                    } catch (e: Exception) {}
                }
                
                // Landmarks fallbacks
                val landmarksJson = org.json.JSONObject()
                val leftEye = face.getLandmark(com.google.mlkit.vision.face.FaceLandmark.LEFT_EYE)
                val rightEye = face.getLandmark(com.google.mlkit.vision.face.FaceLandmark.RIGHT_EYE)
                val bottomMouth = face.getLandmark(com.google.mlkit.vision.face.FaceLandmark.MOUTH_BOTTOM)
                val noseBase = face.getLandmark(com.google.mlkit.vision.face.FaceLandmark.NOSE_BASE)
                
                if (leftEye != null) landmarksJson.put("LEFT_EYE", org.json.JSONArray().put(leftEye.position.x).put(leftEye.position.y))
                if (rightEye != null) landmarksJson.put("RIGHT_EYE", org.json.JSONArray().put(rightEye.position.x).put(rightEye.position.y))
                if (bottomMouth != null) landmarksJson.put("MOUTH_BOTTOM", org.json.JSONArray().put(bottomMouth.position.x).put(bottomMouth.position.y))
                if (noseBase != null) landmarksJson.put("NOSE_BASE", org.json.JSONArray().put(noseBase.position.x).put(noseBase.position.y))
                
                AIDetectedItem(
                    label = "Face",
                    confidence = face.smilingProbability ?: 1.0f,
                    boundingBox = RectF(face.boundingBox),
                    extra = mapOf(
                        "smiling_prob" to (face.smilingProbability ?: 0f),
                        "right_eye_open_prob" to (face.rightEyeOpenProbability ?: 0f),
                        "left_eye_open_prob" to (face.leftEyeOpenProbability ?: 0f),
                        "head_euler_x" to face.headEulerAngleX,
                        "head_euler_y" to face.headEulerAngleY,
                        "head_euler_z" to face.headEulerAngleZ,
                        "tracking_id" to (face.trackingId ?: -1),
                        "contours" to contoursJson.toString(),
                        "landmarks" to landmarksJson.toString()
                    )
                )
            }
            
            AIResult(true, items, duration)
        } catch (e: Exception) {
            e.printStackTrace()
            AIResult(false, emptyList(), 0, e.message)
        }
    }

    override fun release() {
        detector?.close()
        detector = null
    }
}
