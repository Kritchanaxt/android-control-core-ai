package com.example.android_screen_relay.core

import android.graphics.Bitmap
import android.graphics.RectF
import android.util.Log

class FaceDetector : AIProcessor {
    override val name: String = "FaceDetector"

    override fun init(context: android.content.Context, config: AIConfig): Boolean {
        Log.d("FaceDetector", "Initializing Face Detector (Mock)")
        return true
    }

    override fun process(bitmap: Bitmap): AIResult {
        val start = System.currentTimeMillis()
        // Mock processing angle requirement
        val angleZ = 0.5f // Placeholder logic
        val angleY = 0.1f // Placeholder logic
        
        // If angle is too much, return failure as requested (no FaceEdgeDetection if tilted too much)
        if (angleZ > 10 || angleY > 10) {
             return AIResult(false, emptyList(), System.currentTimeMillis() - start, "Tilt too high")
        }

        val items = listOf(
            AIDetectedItem(
                label = "Face",
                confidence = 0.99f,
                boundingBox = RectF(0.3f, 0.3f, 0.7f, 0.7f),
                extra = mapOf("pitch" to 1.0f, "yaw" to 0.5f)
            )
        )
        
        return AIResult(
            success = true,
            items = items,
            processTimeMs = System.currentTimeMillis() - start
        )
    }

    override fun release() {
        Log.d("FaceDetector", "Releasing Face Detector")
    }
}
