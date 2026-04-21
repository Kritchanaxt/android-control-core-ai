package com.example.android_screen_relay.core

import android.content.Context
import android.graphics.*
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import kotlinx.coroutines.tasks.await
import org.json.JSONArray
import org.json.JSONObject

class IDCardFaceBenchmarker {

    data class BenchResult(
        val scale: Float,
        val prep: String,
        val success: Boolean,
        val latencyMs: Long
    )

    suspend fun runScaleStressTest(context: Context, originalBitmap: Bitmap): String {
        val results = mutableListOf<BenchResult>()
        
        // 1. First, check if image is already a crop or full frame
        // If width > 1200, it's likely a full frame, we need ROI
        val isFullFrame = originalBitmap.width > 1200
        
        val faceCrop = if (isFullFrame) {
            val roiLeft = (originalBitmap.width * 0.65f).toInt()
            val roiTop = (originalBitmap.height * 0.2f).toInt()
            val roiWidth = (originalBitmap.width * 0.3f).toInt()
            val roiHeight = (originalBitmap.height * 0.6f).toInt()
            Bitmap.createBitmap(originalBitmap, roiLeft, roiTop, roiWidth, roiHeight)
        } else {
            originalBitmap
        }

        // Expanded Scales for Homework
        val scales = listOf(2.0f, 2.5f, 3.0f, 3.5f, 4.0f, 5.0f, 6.0f, 8.0f)
        val preprocessors = listOf("Original", "Grayscale")
        
        val options = FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
            .build()
        val detector = FaceDetection.getClient(options)

        var bestScale = 3.0f
        var bestLatency = Long.MAX_VALUE
        var foundAny = false

        for (scale in scales) {
            for (prep in preprocessors) {
                val startTime = System.currentTimeMillis()
                try {
                    // 1. Scale Up
                    val scaledBmp = Bitmap.createScaledBitmap(
                        faceCrop,
                        (faceCrop.width * scale).toInt(),
                        (faceCrop.height * scale).toInt(),
                        true
                    )
                    
                    // 2. Preprocess
                    val processedBmp = if (prep == "Grayscale") {
                        val dest = Bitmap.createBitmap(scaledBmp.width, scaledBmp.height, Bitmap.Config.ARGB_8888)
                        val canvas = Canvas(dest)
                        val paint = Paint()
                        val cm = ColorMatrix()
                        cm.setSaturation(0f)
                        paint.colorFilter = ColorMatrixColorFilter(cm)
                        canvas.drawBitmap(scaledBmp, 0f, 0f, paint)
                        scaledBmp.recycle()
                        dest
                    } else {
                        scaledBmp
                    }

                    // 3. Detect
                    val image = InputImage.fromBitmap(processedBmp, 0)
                    val faces = detector.process(image).await()
                    val duration = System.currentTimeMillis() - startTime
                    
                    val success = faces.isNotEmpty()
                    results.add(BenchResult(scale, prep, success, duration))
                    
                    if (success) {
                        foundAny = true
                        if (duration < bestLatency) {
                            bestLatency = duration
                            bestScale = scale
                        }
                    }
                    
                    processedBmp.recycle()
                } catch (e: Exception) {
                    Log.e("Bench", "Error at $scale $prep", e)
                }
            }
        }
        
        detector.close()
        if (isFullFrame) faceCrop.recycle()

        // JSON Report
        val root = JSONObject()
        val arr = JSONArray()
        // Sort results for consistent table view
        results.sortBy { it.scale }
        results.forEach { res ->
            val obj = JSONObject()
            obj.put("scale", "${res.scale}x")
            obj.put("status", if (res.success) "Success" else "Failed")
            obj.put("latency", "${res.latencyMs}ms")
            obj.put("prep", res.prep)
            arr.put(obj)
        }
        root.put("details", arr)
        root.put("best_scale", if (foundAny) "${bestScale}x" else "N/A")
        root.put("recommendation", if (foundAny) "Scale ${bestScale}x with Original/Grayscale is optimal." else "Unable to detect face. Please try holding the card closer or improving lighting.")
        
        return root.toString()
    }
}
