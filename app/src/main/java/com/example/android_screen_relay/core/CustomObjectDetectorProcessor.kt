package com.example.android_screen_relay.core

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.common.model.LocalModel
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.objects.ObjectDetection
import com.google.mlkit.vision.objects.ObjectDetector
import com.google.mlkit.vision.objects.custom.CustomObjectDetectorOptions

class CustomObjectDetectorProcessor : AIProcessor {
    override val name: String = "ML Kit - Custom Object detection"
    private var detector: ObjectDetector? = null

    override fun init(context: Context, config: AIConfig): Boolean {
        return try {
            val localModel = LocalModel.Builder()
                .setAssetFilePath("custom_models/bird_classifier.tflite")
                .build()
            
            val options = CustomObjectDetectorOptions.Builder(localModel)
                .setDetectorMode(CustomObjectDetectorOptions.STREAM_MODE)
                .enableClassification()
                .setClassificationConfidenceThreshold(0.5f)
                .setMaxPerObjectLabelCount(3)
                .enableMultipleObjects()
                .build()
                
            detector = ObjectDetection.getClient(options)
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    override fun process(bitmap: Bitmap, options: Map<String, Any>): AIResult {
        val currentDetector = detector ?: return AIResult(false, emptyList(), 0, "Not initialized")
        val startTime = System.currentTimeMillis()
        
        return try {
            val image = InputImage.fromBitmap(bitmap, 0)
            val task = currentDetector.process(image)
            
            val objects = Tasks.await(task)
            val duration = System.currentTimeMillis() - startTime
            
            val items = objects.map { obj ->
                val firstLabel = obj.labels.firstOrNull()
                val label = firstLabel?.text ?: "Unknown"
                val confidence = firstLabel?.confidence ?: 0.0f
                val index = firstLabel?.index ?: -1
                
                AIDetectedItem(
                    label = label,
                    confidence = confidence,
                    boundingBox = RectF(obj.boundingBox),
                    extra = mapOf(
                        "tracking_id" to (obj.trackingId ?: -1),
                        "index" to index,
                        "all_labels" to obj.labels.map { mapOf("text" to it.text, "confidence" to it.confidence, "index" to it.index) }
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
