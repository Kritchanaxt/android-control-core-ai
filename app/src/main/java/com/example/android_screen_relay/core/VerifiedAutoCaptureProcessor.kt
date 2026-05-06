package com.example.android_screen_relay.core

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PointF
import android.util.Log

class VerifiedAutoCaptureProcessor : AIProcessor {
    override val name: String = "VerifiedAutoCapture"
    
    private var poseProcessor: PoseDetectorProcessor? = null
    private var faceProcessor: FaceDetectorProcessor? = null

    override fun init(context: Context, config: AIConfig): Boolean {
        poseProcessor = PoseDetectorProcessor()
        faceProcessor = FaceDetectorProcessor()
        
        val poseInit = poseProcessor?.init(context, config) ?: false
        val faceInit = faceProcessor?.init(context, config) ?: false
        
        return poseInit && faceInit
    }

    override fun process(bitmap: Bitmap, options: Map<String, Any>): AIResult {
        val start = System.currentTimeMillis()
        
        return try {
            if (bitmap.isRecycled) return AIResult(false, emptyList(), 0, "Bitmap is recycled")
            
            // STEP 1: Pose Detection to check for hands blocking face
            // Using the exact same bitmap instance sequentially (No parallel processing)
            val poseResult = poseProcessor?.process(bitmap, options)
            var poseMetrics = "{}"
            if (poseResult != null && poseResult.success && poseResult.items.isNotEmpty()) {
                val poseItem = poseResult.items.first()
                @Suppress("UNCHECKED_CAST")
                val landmarksRaw = poseItem.extra["landmarks_raw"] as? Map<Int, PointF>
                
                if (landmarksRaw != null) {
                    val nose = landmarksRaw[0] // NOSE
                    val leftShoulder = landmarksRaw[11] // LEFT_SHOULDER
                    val rightShoulder = landmarksRaw[12] // RIGHT_SHOULDER
                    
                    val leftWrist = landmarksRaw[15] // LEFT_WRIST
                    val rightWrist = landmarksRaw[16] // RIGHT_WRIST
                    
                    val leftIndex = landmarksRaw[19] // LEFT_INDEX
                    val rightIndex = landmarksRaw[20] // RIGHT_INDEX
                    
                    // Simple heuristic: If wrist or index finger is higher than the shoulder (smaller Y),
                    // or very close to the nose Y, we consider it blocking/raised hands.
                    val shoulderY = minOf(leftShoulder?.y ?: Float.MAX_VALUE, rightShoulder?.y ?: Float.MAX_VALUE)
                    
                    val isLeftHandRaised = (leftWrist != null && leftWrist.y < shoulderY) || (leftIndex != null && leftIndex.y < shoulderY)
                    val isRightHandRaised = (rightWrist != null && rightWrist.y < shoulderY) || (rightIndex != null && rightIndex.y < shoulderY)
                    
                    if (isLeftHandRaised || isRightHandRaised) {
                        return AIResult(
                            success = false,
                            items = emptyList(),
                            processTimeMs = System.currentTimeMillis() - start,
                            errorMessage = "HAND_DETECTED"
                        )
                    }

                    // Construct Pose Metrics JSON
                    poseMetrics = org.json.JSONObject().apply {
                        put("hand_detected", false)
                        put("shoulder_y", shoulderY)
                        put("left_wrist_y", leftWrist?.y ?: -1f)
                        put("right_wrist_y", rightWrist?.y ?: -1f)
                    }.toString()
                }
            }
            
            // STEP 2: Face Detection (4 Pillars)
            val faceResult = faceProcessor?.process(bitmap, options)
            if (faceResult != null && faceResult.success && faceResult.items.isNotEmpty()) {
                val faceItem = faceResult.items.first()
                
                // Calculate 4 Pillar Metrics
                val box = faceItem.boundingBox
                val centerX = box.centerX()
                val centerY = box.centerY()
                val frameCenterX = bitmap.width / 2f
                val frameCenterY = bitmap.height / 2f
                val offsetXPct = kotlin.math.abs(centerX - frameCenterX) / bitmap.width
                val offsetYPct = kotlin.math.abs(centerY - frameCenterY) / bitmap.height
                val isCentered = offsetXPct < 0.15f && offsetYPct < 0.15f
                
                val faceAreaPct = (box.width() * box.height()) / (bitmap.width * bitmap.height).toFloat()
                val isProperSize = faceAreaPct > 0.15f
                
                val yaw = (faceItem.extra["head_euler_y"] as? Float) ?: 0f
                val pitch = (faceItem.extra["head_euler_x"] as? Float) ?: 0f
                val roll = (faceItem.extra["head_euler_z"] as? Float) ?: 0f
                val isStraight = kotlin.math.abs(yaw) < 25f && kotlin.math.abs(pitch) < 25f && kotlin.math.abs(roll) < 25f
                
                // 🌟 STEP 2.5: Eye-based Occlusion Detection (Fallback for when Pose can't see body)
                // If one eye is much more closed than the other, it's likely a hand blocking half the face.
                val leftEyeProb = (faceItem.extra["left_eye_open_prob"] as? Float) ?: -1f
                val rightEyeProb = (faceItem.extra["right_eye_open_prob"] as? Float) ?: -1f
                
                var isOccluded = false
                var occlusionReason = ""
                
                if (leftEyeProb >= 0f && rightEyeProb >= 0f) {
                    // Case 1: One eye blocked, other open → partial occlusion (hand blocking half face)
                    val eyeDiff = kotlin.math.abs(leftEyeProb - rightEyeProb)
                    if (eyeDiff > 0.35f && (leftEyeProb < 0.3f || rightEyeProb < 0.3f)) {
                        isOccluded = true
                        occlusionReason = "PARTIAL_OCCLUSION (L:${String.format("%.2f", leftEyeProb)} R:${String.format("%.2f", rightEyeProb)})"
                    }
                    // Case 2: Both eyes very low → heavy occlusion
                    if (leftEyeProb < 0.15f && rightEyeProb < 0.15f) {
                        isOccluded = true
                        occlusionReason = "HEAVY_OCCLUSION (L:${String.format("%.2f", leftEyeProb)} R:${String.format("%.2f", rightEyeProb)})"
                    }
                }
                
                if (isOccluded) {
                    return AIResult(
                        success = false,
                        items = emptyList(),
                        processTimeMs = System.currentTimeMillis() - start,
                        errorMessage = "FACE_OCCLUDED: $occlusionReason"
                    )
                }
                
                val metricsJson = org.json.JSONObject().apply {
                    put("step_1_pose", org.json.JSONObject(if (poseMetrics.isNotEmpty() && poseMetrics != "{}") poseMetrics else "{\"hand_detected\": false}"))
                    put("step_2_face", org.json.JSONObject().apply {
                        put("is_centered", isCentered)
                        put("center_offset_x_pct", offsetXPct)
                        put("center_offset_y_pct", offsetYPct)
                        put("is_proper_size", isProperSize)
                        put("face_area_pct", faceAreaPct)
                        put("is_straight", isStraight)
                        put("yaw", yaw)
                        put("pitch", pitch)
                        put("roll", roll)
                        put("left_eye_prob", leftEyeProb)
                        put("right_eye_prob", rightEyeProb)
                        put("is_occluded", false)
                        put("4_pillars_passed", isCentered && isProperSize && isStraight)
                    })
                }

                val updatedExtra = faceItem.extra.toMutableMap()
                updatedExtra["verification_metrics"] = metricsJson.toString()

                return AIResult(
                    success = isCentered && isProperSize && isStraight,
                    items = listOf(faceItem.copy(extra = updatedExtra)),
                    processTimeMs = System.currentTimeMillis() - start,
                    errorMessage = if (isCentered && isProperSize && isStraight) null else "FACE_4PILLAR_FAILED"
                )
            }
            
            AIResult(
                success = false,
                items = emptyList(),
                processTimeMs = System.currentTimeMillis() - start,
                errorMessage = "FACE_NOT_FOUND"
            )
            
        } catch (e: Throwable) {
            Log.e("VerifiedAutoCapture", "Process error", e)
            AIResult(false, emptyList(), System.currentTimeMillis() - start, e.message)
        }
    }

    override fun release() {
        poseProcessor?.release()
        faceProcessor?.release()
        poseProcessor = null
        faceProcessor = null
    }
}
