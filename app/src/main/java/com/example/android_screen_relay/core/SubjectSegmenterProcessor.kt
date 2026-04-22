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

    override fun process(bitmap: Bitmap): AIResult {
        val start = System.currentTimeMillis()
        return try {
            val image = InputImage.fromBitmap(bitmap, 0)
            val result = Tasks.await(segmenter!!.process(image))
            val duration = System.currentTimeMillis() - start

            val items = mutableListOf<AIDetectedItem>()
            result.subjects.forEach { subject ->
                items.add(
                    AIDetectedItem(
                        label = "Subject",
                        confidence = 1.0f,
                        boundingBox = android.graphics.RectF(
                            subject.getStartX().toFloat(),
                            subject.getStartY().toFloat(),
                            (subject.getStartX() + subject.getWidth()).toFloat(),
                            (subject.getStartY() + subject.getHeight()).toFloat()
                        )
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
