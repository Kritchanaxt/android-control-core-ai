package com.example.android_screen_relay.core

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.Segmentation
import com.google.mlkit.vision.segmentation.Segmenter
import com.google.mlkit.vision.segmentation.selfie.SelfieSegmenterOptions

class SelfieSegmenterProcessor : AIProcessor {
    override val name: String = "SelfieSegmenter"
    private var segmenter: Segmenter? = null

    override fun init(context: Context, config: AIConfig): Boolean {
        return try {
            val options = SelfieSegmenterOptions.Builder()
                .setDetectorMode(SelfieSegmenterOptions.STREAM_MODE)
                .build()
            segmenter = Segmentation.getClient(options)
            true
        } catch (e: Exception) {
            Log.e("Selfie", "Init error", e)
            false
        }
    }

    override fun process(bitmap: Bitmap, options: Map<String, Any>): AIResult {
        val start = System.currentTimeMillis()
        return try {
            val image = InputImage.fromBitmap(bitmap, 0)
            val mask = Tasks.await(segmenter!!.process(image))
            val duration = System.currentTimeMillis() - start

            // ✅ Logic: ตรวจสอบว่ามี "คน" อยู่ในภาพจริงๆ หรือไม่ (Person Validation)
            // นับพิกเซลที่มีค่าความมั่นใจสูงกว่า 0.5 (Background vs Foreground)
            val maskBuffer = mask.buffer
            val maskWidth = mask.width
            val maskHeight = mask.height
            val totalPixels = maskWidth * maskHeight
            
            maskBuffer.rewind()
            val floatBuffer = maskBuffer.asFloatBuffer()
            var foregroundPixels = 0
            
            // Generate Bitmap in background thread to prevent UI thread OOM and concurrent access crashes
            val maskBitmap = Bitmap.createBitmap(maskWidth, maskHeight, Bitmap.Config.ARGB_8888)
            val pixels = IntArray(totalPixels)
            val tintColor = 0xCCFF00FF.toInt()

            val capacity = floatBuffer.capacity()
            for (i in 0 until totalPixels) {
                if (i < capacity) {
                    val conf = floatBuffer.get(i)
                    if (conf > 0.4f) {
                        pixels[i] = tintColor
                        foregroundPixels++
                    } else {
                        pixels[i] = android.graphics.Color.TRANSPARENT
                    }
                }
            }
            maskBitmap.setPixels(pixels, 0, maskWidth, 0, 0, maskWidth, maskHeight)
            
            // คำนวณสัดส่วนคนในภาพ
            val personRatio = (foregroundPixels.toFloat() / totalPixels.toFloat())
            val isPersonFound = personRatio > 0.015f // 1.5% threshold

            val items = mutableListOf<AIDetectedItem>()
            if (isPersonFound) {
                items.add(
                    AIDetectedItem(
                        label = "Selfie Mask",
                        confidence = personRatio.coerceAtMost(1.0f),
                        boundingBox = android.graphics.RectF(0f, 0f, bitmap.width.toFloat(), bitmap.height.toFloat()),
                        extra = mapOf(
                            "width" to maskWidth,
                            "height" to maskHeight,
                            "mask_bitmap" to maskBitmap,
                            "mask_buffer" to mask.buffer
                        )
                    )
                )
            }

            AIResult(true, items, duration)
        } catch (e: Exception) {
            Log.e("Selfie", "Process error", e)
            AIResult(false, emptyList(), 0, e.message)
        }
    }

    override fun release() {
        segmenter?.close()
        segmenter = null
    }
}
