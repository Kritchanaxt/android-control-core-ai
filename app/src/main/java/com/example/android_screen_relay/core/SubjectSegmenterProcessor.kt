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
            val subjectResultOptions = SubjectSegmenterOptions.SubjectResultOptions.Builder()
                .enableConfidenceMask()
                .enableSubjectBitmap()
                .build()

            val options = SubjectSegmenterOptions.Builder()
                .enableForegroundConfidenceMask()
                .enableMultipleSubjects(subjectResultOptions)
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

            var unionLeft = bitmap.width
            var unionTop = bitmap.height
            var unionRight = 0
            var unionBottom = 0

            val items = mutableListOf<AIDetectedItem>()
            result.subjects.forEachIndexed { index, subject ->
                val extra = mutableMapOf<String, Any>()
                val mask = subject.confidenceMask
                
                // Extract the actual image of the subject with background removed
                val subjectBitmap = subject.bitmap
                if (subjectBitmap != null) {
                    // Copy to prevent ML Kit from recycling the buffer when moving to next frame
                    extra["subject_bitmap"] = subjectBitmap.copy(Bitmap.Config.ARGB_8888, true)
                }

                if (mask != null) {
                    val width = subject.width
                    val height = subject.height
                    
                    // Generate Bitmap in background thread to prevent UI thread OOM and concurrent access crashes
                    val maskBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                    val pixels = IntArray(width * height)
                    
                    val colors = listOf(0xCCFF00FF.toInt(), 0xCC00FFFF.toInt(), 0xCCFFFF00.toInt(), 0xCC00FF00.toInt())
                    val tintColor = colors[index % colors.size]
                    
                    val capacity = mask.capacity()
                    for (i in 0 until width * height) {
                        if (i < capacity) {
                            val conf = mask.get(i)
                            if (conf > 0.4f) {
                                pixels[i] = tintColor
                            } else {
                                pixels[i] = android.graphics.Color.TRANSPARENT
                            }
                        }
                    }
                    maskBitmap.setPixels(pixels, 0, width, 0, 0, width, height)
                    
                    extra["mask_bitmap"] = maskBitmap
                    extra["width"] = width
                    extra["height"] = height
                    Log.d("Subject", "Subject #$index: mask bitmap generated successfully")
                } else {
                    Log.d("Subject", "Subject #$index: NO mask found")
                }
                
                items.add(
                    AIDetectedItem(
                        label = "Subject",
                        confidence = 1.0f,
                        boundingBox = android.graphics.RectF(
                            subject.startX.toFloat(),
                            subject.startY.toFloat(),
                            (subject.startX + subject.width).toFloat(),
                            (subject.startY + subject.height).toFloat()
                        ),
                        extra = extra
                    )
                )

                unionLeft = minOf(unionLeft, subject.startX)
                unionTop = minOf(unionTop, subject.startY)
                unionRight = maxOf(unionRight, subject.startX + subject.width)
                unionBottom = maxOf(unionBottom, subject.startY + subject.height)
            }

            // Create combined cropped foreground bitmap for ALL subjects using subject.bitmaps to avoid ML Kit foregroundBitmap bug
            if (result.subjects.isNotEmpty() && items.isNotEmpty()) {
                val pad = 30
                unionLeft = (unionLeft - pad).coerceAtLeast(0)
                unionTop = (unionTop - pad).coerceAtLeast(0)
                unionRight = (unionRight + pad).coerceAtMost(bitmap.width)
                unionBottom = (unionBottom + pad).coerceAtMost(bitmap.height)
                
                val width = unionRight - unionLeft
                val height = unionBottom - unionTop
                if (width > 0 && height > 0) {
                    // Create a transparent bitmap for the union area
                    val combinedBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                    val canvas = android.graphics.Canvas(combinedBitmap)
                    
                    // Draw each subject's bitmap onto the combined canvas
                    result.subjects.forEach { subject ->
                        val subBmp = subject.bitmap
                        if (subBmp != null) {
                            val drawX = (subject.startX - unionLeft).toFloat()
                            val drawY = (subject.startY - unionTop).toFloat()
                            canvas.drawBitmap(subBmp, drawX, drawY, null)
                        }
                    }
                    
                    (items[0].extra as MutableMap<String, Any>)["combined_subject_bitmap"] = combinedBitmap
                }
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
