package com.example.android_screen_relay.core

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import android.util.Log
import com.googlecode.tesseract.android.TessBaseAPI
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream

class TesseractOCRProcessor : AIProcessor {
    override val name: String = "Tesseract Fast OCR"
    private var tess: TessBaseAPI? = null
    
    override fun init(context: Context, config: AIConfig): Boolean {
        try {
            tess = TessBaseAPI()
            
            val dataPath = File(context.filesDir, "tesseract")
            val tessdataDir = File(dataPath, "tessdata")
            if (!tessdataDir.exists()) {
                tessdataDir.mkdirs()
            }
            
            // Copy eng and tha models from assets
            copyAsset(context, "tessdata/eng.traineddata", File(tessdataDir, "eng.traineddata"))
            copyAsset(context, "tessdata/tha.traineddata", File(tessdataDir, "tha.traineddata"))
            
            // Initialize with tha+eng
            if (!tess!!.init(dataPath.absolutePath, "tha+eng")) {
                Log.e("TesseractOCR", "Failed to init Tesseract")
                return false
            }
            tess!!.pageSegMode = TessBaseAPI.PageSegMode.PSM_AUTO
            return true
        } catch (e: Exception) {
            Log.e("TesseractOCR", "Init error", e)
            return false
        }
    }

    private fun copyAsset(context: Context, assetPath: String, outFile: File) {
        if (outFile.exists()) return
        try {
            val input: InputStream = context.assets.open(assetPath)
            val out: OutputStream = FileOutputStream(outFile)
            val buffer = ByteArray(1024)
            var read: Int
            while (input.read(buffer).also { read = it } != -1) {
                out.write(buffer, 0, read)
            }
            input.close()
            out.flush()
            out.close()
        } catch (e: Exception) {
            Log.e("TesseractOCR", "Error copying asset", e)
        }
    }

    fun getRawJson(bitmap: Bitmap): String? {
        try {
            if (bitmap.isRecycled) return "[]"
            val result = process(bitmap)
            if (!result.success || result.items.isEmpty()) return "[]"
            
            val jsonArray = org.json.JSONArray()
            for (item in result.items) {
                val obj = org.json.JSONObject()
                obj.put("label", item.label)
                obj.put("prob", item.confidence.toDouble())
                
                val r = item.boundingBox
                obj.put("x0", r.left.toDouble())
                obj.put("y0", r.top.toDouble())
                obj.put("x1", r.right.toDouble())
                obj.put("y1", r.top.toDouble())
                obj.put("x2", r.right.toDouble())
                obj.put("y2", r.bottom.toDouble())
                obj.put("x3", r.left.toDouble())
                obj.put("y3", r.bottom.toDouble())
                
                jsonArray.put(obj)
            }
            return jsonArray.toString()
        } catch (e: Throwable) {
            Log.e("TesseractOCR", "getRawJson error", e)
            return "[]"
        }
    }

    override fun process(bitmap: Bitmap, options: Map<String, Any>): AIResult {
        if (tess == null) return AIResult(false, emptyList(), 0, "Not initialized")
        val start = System.currentTimeMillis()
        
        return try {
            if (bitmap.isRecycled) return AIResult(false, emptyList(), 0, "Bitmap is recycled")
            
            tess!!.setImage(bitmap)
            val text = tess!!.utF8Text ?: ""
            
            val items = mutableListOf<AIDetectedItem>()
            if (text.isNotBlank()) {
                items.add(
                    AIDetectedItem(
                        label = text.trim(),
                        confidence = 1.0f,
                        boundingBox = RectF(0f, 0f, bitmap.width.toFloat(), bitmap.height.toFloat())
                    )
                )
            }
            
            val processTime = System.currentTimeMillis() - start
            AIResult(true, items, processTime)
        } catch (e: Throwable) {
            Log.e("TesseractOCR", "Process error", e)
            AIResult(false, emptyList(), System.currentTimeMillis() - start, e.message)
        }
    }

    override fun release() {
        tess?.recycle()
        tess = null
    }
}
