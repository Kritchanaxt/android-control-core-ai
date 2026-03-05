package com.example.android_screen_relay.ocr

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

data class BenchmarkResult(
    val title: String,
    val resolution: String,
    val latencyMs: Long,
    val resourceUsage: ResourceUsage,
    val deviceInfo: DeviceInfo,
    val resultJson: String
) {
    fun toJson(): JSONObject {
        return JSONObject().apply {
            put("title", title)
            put("resolution", resolution)
            put("latency_ms", latencyMs)
            put("resource_usage", resourceUsage.toJson())
            put("device_info", deviceInfo.toJson())
            put("result", JSONArray(resultJson))
        }
    }
}

object OCRBenchmarkRunner {
    private const val TAG = "OCRBenchmark"

    fun runBenchmark(
        context: Context,
        paddleOCR: PaddleOCR,
        originalBitmap: Bitmap,
        title: String,
        bitmapToProcess: Bitmap
    ): BenchmarkResult {
        Log.d(TAG, "Starting benchmark: $title")
        
        // Measure resource usage before inference to baseline
        val device = SystemMonitor.getDeviceInfo(context)
        
        val startCpu = SystemMonitor.getCurrentResourceUsage(context)
        
        val startTime = System.currentTimeMillis()
        val resultJson = paddleOCR.detect(bitmapToProcess)
        val endTime = System.currentTimeMillis()
        
        val latencyMs = endTime - startTime
        
        // Resource after inference
        val endCpu = SystemMonitor.getCurrentResourceUsage(context)
        
        val resolution = "${bitmapToProcess.width}x${bitmapToProcess.height}"
        Log.d(TAG, "Benchmark $title completed in $latencyMs ms at $resolution")

        val result = BenchmarkResult(
            title = title,
            resolution = resolution,
            latencyMs = latencyMs,
            resourceUsage = endCpu,
            deviceInfo = device,
            resultJson = resultJson
        )
        
        return result
    }

    fun runFullBenchmarkSuite(
        context: Context, 
        paddleOCR: PaddleOCR, 
        bitmap: Bitmap
    ): JSONArray {
        val resultsArray = JSONArray()
        
        // 1. Full Image (Baseline)
        resultsArray.put(
            runBenchmark(context, paddleOCR, bitmap, "Full Image Baseline", bitmap).toJson()
        )
        
        // 2. Downscaled 720p (Max dimension 720)
        val downscaled = OCROptimizer.scaleDownToMaxDimension(bitmap, 720)
        resultsArray.put(
            runBenchmark(context, paddleOCR, bitmap, "Downscaled 720p", downscaled).toJson()
        )
        
        // 3. Downscaled Low-End (Max dimension 480)
        val lowEnd = OCROptimizer.scaleDownToMaxDimension(bitmap, 480)
        resultsArray.put(
            runBenchmark(context, paddleOCR, bitmap, "Low-end device spec (480p)", lowEnd).toJson()
        )
        
        // 4. Center Crop (Assuming game UI or dialog is in center)
        val centerCropped = OCROptimizer.cropCenter(bitmap, 0.5f, 0.5f)
        resultsArray.put(
            runBenchmark(context, paddleOCR, bitmap, "Center Cropped (50%)", centerCropped).toJson()
        )
        
        return resultsArray
    }
}
