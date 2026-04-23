package com.example.android_screen_relay.core

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.objects.ObjectDetection
import com.google.mlkit.vision.objects.ObjectDetector
import com.google.mlkit.vision.objects.defaults.ObjectDetectorOptions

class ObjectDetectorProcessor : AIProcessor {
    override val name: String = "ML Kit - Object detection"
    private var detector: ObjectDetector? = null

    override fun init(context: Context, config: AIConfig): Boolean {
        return try {
            val options = ObjectDetectorOptions.Builder()
                .setDetectorMode(ObjectDetectorOptions.STREAM_MODE)
                .enableClassification()
                .enableMultipleObjects()
                .build()
            detector = ObjectDetection.getClient(options)
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
            
            val objects = Tasks.await(task)
            val duration = System.currentTimeMillis() - startTime
            
            val items = objects.map { obj ->
                val label = obj.labels.firstOrNull()?.text ?: "Object"
                val confidence = obj.labels.firstOrNull()?.confidence ?: 1.0f
                
                AIDetectedItem(
                    label = label,
                    confidence = confidence,
                    boundingBox = RectF(obj.boundingBox),
                    extra = mapOf(
                        "tracking_id" to (obj.trackingId ?: -1)
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
