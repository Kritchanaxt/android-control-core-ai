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
            maskBuffer.rewind()
            val floatBuffer = maskBuffer.asFloatBuffer()
            var foregroundPixels = 0
            val totalPixels = mask.width * mask.height
            
            // สุ่มตรวจทุกๆ 10 พิกเซลเพื่อความรวดเร็ว (Sampling)
            for (i in 0 until totalPixels step 10) {
                if (floatBuffer.get(i) > 0.5f) {
                    foregroundPixels++
                }
            }
            
            // คำนวณสัดส่วนคนในภาพ (ต้องมีอย่างน้อย 1% ของพิกเซลที่สุ่มตรวจ)
            val personRatio = (foregroundPixels.toFloat() / (totalPixels / 10f))
            val isPersonFound = personRatio > 0.015f // 1.5% threshold

            val items = mutableListOf<AIDetectedItem>()
            if (isPersonFound) {
                items.add(
                    AIDetectedItem(
                        label = "Selfie Mask",
                        confidence = personRatio.coerceAtMost(1.0f),
                        boundingBox = android.graphics.RectF(0f, 0f, bitmap.width.toFloat(), bitmap.height.toFloat()),
                        extra = mapOf(
                            "width" to mask.width,
                            "height" to mask.height,
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
