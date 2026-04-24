package com.example.android_screen_relay.core

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.subject.SubjectSegmenter
import com.google.mlkit.vision.segmentation.subject.SubjectSegmentation
import com.google.mlkit.vision.segmentation.subject.SubjectSegmenterOptions

class SubjectSegmenterProcessor : AIProcessor {
    override val name: String = "SubjectSegmenter"
    private var segmenter: SubjectSegmenter? = null

    override fun init(context: Context, config: AIConfig): Boolean {
        return try {
            val options = SubjectSegmenterOptions.Builder()
                .enableForegroundConfidenceMask()
                .build()
            segmenter = SubjectSegmentation.getClient(options)
            true
        } catch (e: Exception) {
            Log.e("Subject", "Init error", e)
            false
        }
    }

    override fun process(bitmap: Bitmap, options: Map<String, Any>): AIResult {
        val start = System.currentTimeMillis()
        return try {
            val image = InputImage.fromBitmap(bitmap, 0)
            val result = Tasks.await(segmenter!!.process(image))
            val duration = System.currentTimeMillis() - start

            Log.d("Subject", "Detected ${result.subjects.size} subjects in ${duration}ms")

            val items = mutableListOf<AIDetectedItem>()
            result.subjects.forEachIndexed { index, subject ->
                val extra = mutableMapOf<String, Any>()
                val mask = subject.getConfidenceMask()
                if (mask != null) {
                    // 🌟 FIX: Use full reflection to avoid 'Unresolved reference' at compile time
                    try {
                        val getBufferMethod = mask.javaClass.methods.find { it.name == "getBuffer" }
                        val buffer = getBufferMethod?.invoke(mask)
                        if (buffer != null) {
                            extra["mask_buffer"] = buffer
                            extra["width"] = subject.getWidth()
                            extra["height"] = subject.getHeight()
                            Log.d("Subject", "Subject #$index: mask buffer extracted successfully")
                        }
                    } catch (e: Exception) {
                        Log.e("Subject", "Failed to extract buffer via reflection", e)
                    }
                } else {
                    Log.d("Subject", "Subject #$index: NO mask found")
                }
                
                items.add(
                    AIDetectedItem(
                        label = "Subject",
                        confidence = 1.0f,
                        boundingBox = android.graphics.RectF(
                            subject.getStartX().toFloat(),
                            subject.getStartY().toFloat(),
                            (subject.getStartX() + subject.getWidth()).toFloat(),
                            (subject.getStartY() + subject.getHeight()).toFloat()
                        ),
                        extra = extra
                    )
                )
            }

            AIResult(true, items, duration)
        } catch (e: Exception) {
            Log.e("Subject", "Process error", e)
            AIResult(false, emptyList(), 0, e.message)
        }
    }

    override fun release() {
        segmenter?.close()
        segmenter = null
    }
}
