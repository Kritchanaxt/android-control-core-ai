package com.example.android_screen_relay.core

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.subject.SubjectSegmenter
import com.google.mlkit.vision.segmentation.subject.SubjectSegmentation
import com.google.mlkit.vision.segmentation.subject.SubjectSegmenterOptions
import java.nio.FloatBuffer

class SubjectSegmenterProcessor : AIProcessor {
    override val name: String = "SubjectSegmenter"
    private var segmenter: SubjectSegmenter? = null

    // Lock object เพื่อป้องกันแอปเด้งตอนปิดหน้าจอ (Use-after-free)
    private val lock = Any()

    override fun init(context: Context, config: AIConfig): Boolean {
        return try {
            synchronized(lock) {
                val subjectResultOptions = SubjectSegmenterOptions.SubjectResultOptions.Builder()
                    .enableSubjectBitmap()
                    // 🌟 เปิดใช้งาน Confidence Mask เพื่อสร้างไฮไลท์รูปร่าง
                    .enableConfidenceMask()
                    .build()

                val options = SubjectSegmenterOptions.Builder()
                    .enableMultipleSubjects(subjectResultOptions)
                    .build()
                segmenter = SubjectSegmentation.getClient(options)
            }
            true
        } catch (e: Throwable) {
            Log.e("Subject", "Init error", e)
            false
        }
    }

    override fun process(bitmap: Bitmap, options: Map<String, Any>): AIResult {
        val start = System.currentTimeMillis()

        var processBitmap = bitmap
        var scale = 1f
        var wasScaled = false

        // ย่อขนาดภาพก่อนประมวลผลเพื่อป้องกัน Native Memory Crash
        val maxDimension = 448
        if (bitmap.width > maxDimension || bitmap.height > maxDimension) {
            scale = maxDimension.toFloat() / maxOf(bitmap.width, bitmap.height)
            val newWidth = (bitmap.width * scale).toInt()
            val newHeight = (bitmap.height * scale).toInt()
            processBitmap = Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
            wasScaled = true
        }

        return try {
            val image = InputImage.fromBitmap(processBitmap, 0)

            val result = synchronized(lock) {
                val currentSegmenter = segmenter ?: throw IllegalStateException("Segmenter is closed")
                Tasks.await(currentSegmenter.process(image))
            }

            val duration = System.currentTimeMillis() - start

            var unionLeft = processBitmap.width
            var unionTop = processBitmap.height
            var unionRight = 0
            var unionBottom = 0

            if (result.subjects.isEmpty()) {
                if (wasScaled) processBitmap.recycle()
                return AIResult(true, emptyList(), duration)
            }

            // Phase 1: หาขอบเขต Bounding Box รวม
            result.subjects.forEach { subject ->
                unionLeft = minOf(unionLeft, subject.startX)
                unionTop = minOf(unionTop, subject.startY)
                unionRight = maxOf(unionRight, subject.startX + subject.width)
                unionBottom = maxOf(unionBottom, subject.startY + subject.height)
            }

            // Phase 2: สร้างภาพรวม
            val pad = (20 * scale).toInt()
            unionLeft = (unionLeft - pad).coerceAtLeast(0)
            unionTop = (unionTop - pad).coerceAtLeast(0)
            unionRight = (unionRight + pad).coerceAtMost(processBitmap.width)
            unionBottom = (unionBottom + pad).coerceAtMost(processBitmap.height)

            val uWidth = unionRight - unionLeft
            val uHeight = unionBottom - unionTop

            val items = mutableListOf<AIDetectedItem>()

            if (uWidth > 0 && uHeight > 0) {
                // 🎨 สีนีออนสำหรับวัตถุสูงสุด 4 ชิ้น
                val subjectColors = listOf(
                    Color.argb(160, 0, 255, 255), // Cyan
                    Color.argb(160, 255, 0, 255), // Magenta
                    Color.argb(160, 255, 255, 0), // Yellow
                    Color.argb(160, 0, 255, 0)    // Green
                )

                // Canvas สำหรับภาพวัตถุที่ตัดพื้นหลัง (ภาพรวม)
                val combinedBitmap = Bitmap.createBitmap(uWidth, uHeight, Bitmap.Config.ARGB_8888)
                val canvas = android.graphics.Canvas(combinedBitmap)

                // 🌟 Canvas สำหรับวาดสีไฮไลท์ (ภาพรวม)
                val highlightCombinedBitmap = Bitmap.createBitmap(uWidth, uHeight, Bitmap.Config.ARGB_8888)
                val highlightCanvas = android.graphics.Canvas(highlightCombinedBitmap)

                var hasAnyBitmap = false

                result.subjects.forEachIndexed { index, subject ->
                    val drawX = (subject.startX - unionLeft).toFloat()
                    val drawY = (subject.startY - unionTop).toFloat()
                    val tintColor = subjectColors[index % subjectColors.size]

                    // 1. วาดภาพวัตถุลงในภาพรวม
                    subject.bitmap?.let { subBmp ->
                        canvas.drawBitmap(subBmp, drawX, drawY, null)
                        hasAnyBitmap = true
                    }

                    // 2. สร้างสีไฮไลท์เฉพาะชิ้น และวาดลงในภาพรวม
                    val maskBuffer: FloatBuffer? = subject.confidenceMask
                    if (maskBuffer != null) {
                        val mWidth = subject.width
                        val mHeight = subject.height
                        val maskBitmap = Bitmap.createBitmap(mWidth, mHeight, Bitmap.Config.ARGB_8888)
                        val pixels = IntArray(mWidth * mHeight)

                        maskBuffer.rewind()
                        for (i in 0 until mWidth * mHeight) {
                            val confidence = maskBuffer.get()
                            // ถ้าระบบมั่นใจว่าเป็นวัตถุเกิน 30% ให้ระบายสีตามลำดับ
                            pixels[i] = if (confidence > 0.3f) {
                                tintColor
                            } else {
                                Color.TRANSPARENT
                            }
                        }
                        maskBitmap.setPixels(pixels, 0, mWidth, 0, 0, mWidth, mHeight)
                        
                        // วาดขอบบางๆ ให้แต่ละชิ้น
                        val borderCanvas = android.graphics.Canvas(maskBitmap)
                        val borderPaint = android.graphics.Paint().apply {
                            color = tintColor or (0xFF shl 24) // ทึบแสง 100% สำหรับเส้นขอบ
                            style = android.graphics.Paint.Style.STROKE
                            strokeWidth = 2f
                            isAntiAlias = true
                        }
                        // Note: drawRect(mask) is not enough for complex shapes, but adding it to the bitmap directly
                        highlightCanvas.drawBitmap(maskBitmap, drawX, drawY, null)

                        // 3. เพิ่มข้อมูลชิ้นเดี่ยวๆ ลงใน AIDetectedItem เพื่อให้ UI วาดกรอบเส้นปะได้รายชิ้น
                        val invScale = if (wasScaled) 1f / scale else 1f
                        val singleExtra = mutableMapOf<String, Any>()
                        
                        // Copy bitmaps for individual detection (Safe for memory since it's only up to 4)
                        val individualMask = maskBitmap.copy(Bitmap.Config.ARGB_8888, true)
                        if (wasScaled) {
                            val targetW = (individualMask.width * invScale).toInt()
                            val targetH = (individualMask.height * invScale).toInt()
                            val scaledMask = Bitmap.createScaledBitmap(individualMask, targetW, targetH, false)
                            singleExtra["mask_bitmap"] = scaledMask
                            individualMask.recycle()
                        } else {
                            singleExtra["mask_bitmap"] = individualMask
                        }
                        
                        items.add(
                            AIDetectedItem(
                                label = "Subject ${index + 1}",
                                confidence = 1.0f,
                                boundingBox = android.graphics.RectF(
                                    subject.startX.toFloat() * invScale,
                                    subject.startY.toFloat() * invScale,
                                    (subject.startX + subject.width).toFloat() * invScale,
                                    (subject.startY + subject.height).toFloat() * invScale
                                ),
                                extra = singleExtra
                            )
                        )
                        
                        maskBitmap.recycle()
                    }
                }

                if (hasAnyBitmap) {
                    val invScale = if (wasScaled) 1f / scale else 1f
                    
                    // เพิ่มชิ้น "Combined" เข้าไปเป็นชิ้นแรกเพื่อให้ UI ใหญ่ๆ เห็น (Optionally used)
                    val combinedExtra = mutableMapOf<String, Any>()

                    if (wasScaled) {
                        val targetW = (combinedBitmap.width * invScale).toInt()
                        val targetH = (combinedBitmap.height * invScale).toInt()

                        val scaledBitmap = Bitmap.createScaledBitmap(combinedBitmap, targetW, targetH, true)
                        val scaledHighlight = Bitmap.createScaledBitmap(highlightCombinedBitmap, targetW, targetH, false)

                        combinedExtra["combined_subject_bitmap"] = scaledBitmap
                        combinedExtra["mask_bitmap"] = scaledHighlight

                        combinedBitmap.recycle()
                        highlightCombinedBitmap.recycle()
                    } else {
                        combinedExtra["combined_subject_bitmap"] = combinedBitmap
                        combinedExtra["mask_bitmap"] = highlightCombinedBitmap
                    }

                    // ชิ้นรวมอยู่ท้ายสุดเพื่อไม่ให้ทับกรอบเส้นปะรายชิ้น
                    items.add(0,
                        AIDetectedItem(
                            label = "Combined Subjects",
                            confidence = 1.0f,
                            boundingBox = android.graphics.RectF(
                                unionLeft.toFloat() * invScale,
                                unionTop.toFloat() * invScale,
                                unionRight.toFloat() * invScale,
                                unionBottom.toFloat() * invScale
                            ),
                            extra = combinedExtra
                        )
                    )
                } else {
                    combinedBitmap.recycle()
                    highlightCombinedBitmap.recycle()
                }
            }

            if (wasScaled) {
                processBitmap.recycle()
            }

            AIResult(true, items, duration)
        } catch (e: Throwable) {
            if (wasScaled) {
                processBitmap.recycle()
            }
            Log.e("Subject", "Process error", e)
            AIResult(false, emptyList(), 0, e.message)
        }
    }

    override fun release() {
        synchronized(lock) {
            segmenter?.close()
            segmenter = null
        }
    }
}