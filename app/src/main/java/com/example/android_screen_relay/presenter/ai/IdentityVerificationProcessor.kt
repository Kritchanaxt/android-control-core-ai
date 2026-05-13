package com.example.android_screen_relay.core

import android.content.Context
import android.graphics.Bitmap
import android.util.Log

/**
 * Composite processor for Identity Verification.
 * Sequentially runs OCR and then Face Detection on the same frame.
 */
class IdentityVerificationProcessor : AIProcessor {
    override val name: String = "Identity Verification Pipeline"
    
    private var ocrProcessor: AIProcessor? = null
    private var faceProcessor: FaceDetectorProcessor? = null
    private var appContext: Context? = null

    override fun init(context: Context, config: AIConfig): Boolean {
        appContext = context.applicationContext
        
        // 1. Determine which OCR engine to use from config options
        val ocrEngine = config.options["ocr_engine"] as? String ?: "PaddleOCR"
        ocrProcessor = if (ocrEngine.contains("Tesseract", ignoreCase = true)) {
            TesseractOCRProcessor()
        } else {
            OCRProcessor()
        }
        
        val ocrInit = ocrProcessor?.init(context, config) ?: false
        
        // 2. Init Face Detector
        faceProcessor = FaceDetectorProcessor()
        val faceInit = faceProcessor?.init(context, config) ?: false
        
        return ocrInit && faceInit
    }

    override fun process(bitmap: Bitmap, options: Map<String, Any>): AIResult {
        val startTime = System.currentTimeMillis()
        val results = mutableListOf<AIDetectedItem>()
        
        try {
            // Step 1: Run OCR
            val ocrResult = ocrProcessor?.process(bitmap, options)
            if (ocrResult?.success == true) {
                results.addAll(ocrResult.items)
            }
            
            // Step 2: Run Face Detection (Specifically for ID card faces)
            // We force "face_mode" to "card" to use the ROI logic in FaceDetectorProcessor
            val faceOptions = options.toMutableMap()
            faceOptions["face_mode"] = "card"
            
            val faceResult = faceProcessor?.process(bitmap, faceOptions)
            if (faceResult?.success == true) {
                results.addAll(faceResult.items)
            }
            
            val totalTime = System.currentTimeMillis() - startTime
            return AIResult(true, results, totalTime)
            
        } catch (e: Exception) {
            Log.e("IdentityVerification", "Pipeline error", e)
            return AIResult(false, emptyList(), 0, e.message)
        }
    }

    override fun release() {
        ocrProcessor?.release()
        ocrProcessor = null
        faceProcessor?.release()
        faceProcessor = null
        appContext = null
    }
}
