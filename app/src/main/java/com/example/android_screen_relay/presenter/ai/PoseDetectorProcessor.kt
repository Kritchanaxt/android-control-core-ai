package com.example.android_screen_relay.core

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.pose.PoseDetection
import com.google.mlkit.vision.pose.PoseDetector
import com.google.mlkit.vision.pose.PoseDetectorOptionsBase
import com.google.mlkit.vision.pose.defaults.PoseDetectorOptions
import com.google.mlkit.vision.pose.accurate.AccuratePoseDetectorOptions
import org.json.JSONArray
import org.json.JSONObject

class PoseDetectorProcessor : AIProcessor {
    override val name: String = "PoseDetector"
    private var detector: PoseDetector? = null

    override fun init(context: Context, config: AIConfig): Boolean {
        val isLowSpec = config.options["low_spec_mode"] as? Boolean ?: false
        
        return try {
            val options: PoseDetectorOptionsBase = if (isLowSpec) {
                // Use fast mode for low spec
                PoseDetectorOptions.Builder()
                    .setDetectorMode(PoseDetectorOptions.STREAM_MODE)
                    .build()
            } else {
                // Use accurate mode for high spec
                AccuratePoseDetectorOptions.Builder()
                    .setDetectorMode(AccuratePoseDetectorOptions.STREAM_MODE)
                    .build()
            }
            detector = PoseDetection.getClient(options)
            true
        } catch (e: Exception) {
            Log.e("Pose", "Init error", e)
            false
        }
    }

    override fun process(bitmap: Bitmap, options: Map<String, Any>): AIResult {
        val start = System.currentTimeMillis()
        return try {
            val image = InputImage.fromBitmap(bitmap, 0)
            val pose = Tasks.await(detector!!.process(image))
            val duration = System.currentTimeMillis() - start

            val items = mutableListOf<AIDetectedItem>()
            val landmarks = pose.getAllPoseLandmarks()
            
            if (landmarks.isNotEmpty()) {
                val extra = mutableMapOf<String, Any>()
                val landmarksJson = JSONObject()
                val landmarksRaw = mutableMapOf<Int, android.graphics.PointF>()
                
                landmarks.forEach { landmark ->
                    val point = JSONObject()
                    point.put("x", landmark.position.x)
                    point.put("y", landmark.position.y)
                    point.put("z", landmark.getPosition3D().z)
                    point.put("likelihood", landmark.inFrameLikelihood)
                    landmarksJson.put(landmark.landmarkType.toString(), point)
                    
                    // Add raw for UI
                    landmarksRaw[landmark.landmarkType] = landmark.position
                }
                extra["landmarks"] = landmarksJson.toString()
                extra["landmarks_raw"] = landmarksRaw
                
                items.add(
                    AIDetectedItem(
                        label = "Pose",
                        confidence = 1.0f,
                        boundingBox = android.graphics.RectF(0f, 0f, bitmap.width.toFloat(), bitmap.height.toFloat()),
                        extra = extra
                    )
                )
            }

            AIResult(true, items, duration)
        } catch (e: Exception) {
            Log.e("Pose", "Process error", e)
            AIResult(false, emptyList(), 0, e.message)
        }
    }

    override fun release() {
        detector?.close()
        detector = null
    }
}
