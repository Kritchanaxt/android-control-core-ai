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

    override fun process(bitmap: Bitmap): AIResult {
        val start = System.currentTimeMillis()
        return try {
            val image = InputImage.fromBitmap(bitmap, 0)
            val mask = Tasks.await(segmenter!!.process(image))
            val duration = System.currentTimeMillis() - start

            // For segmentation, we return a success with metadata.
            val items = mutableListOf<AIDetectedItem>()
            items.add(
                AIDetectedItem(
                    label = "Selfie Mask",
                    confidence = 1.0f,
                    boundingBox = android.graphics.RectF(0f, 0f, bitmap.width.toFloat(), bitmap.height.toFloat()),
                    extra = mapOf(
                        "width" to mask.width,
                        "height" to mask.height
                    )
                )
            )

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
