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

        return try {
            val image = InputImage.fromBitmap(bitmap, 0)

            val result = synchronized(lock) {
                val currentSegmenter = segmenter ?: throw IllegalStateException("Segmenter is closed")
                Tasks.await(currentSegmenter.process(image))
            }

            val duration = System.currentTimeMillis() - start

            var unionLeft = bitmap.width
            var unionTop = bitmap.height
            var unionRight = 0
            var unionBottom = 0

            if (result.subjects.isEmpty()) {
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
            val pad = 20
            unionLeft = (unionLeft - pad).coerceAtLeast(0)
            unionTop = (unionTop - pad).coerceAtLeast(0)
            unionRight = (unionRight + pad).coerceAtMost(bitmap.width)
            unionBottom = (unionBottom + pad).coerceAtMost(bitmap.height)

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
                        
                        highlightCanvas.drawBitmap(maskBitmap, drawX, drawY, null)

                        // 3. เพิ่มข้อมูลชิ้นเดี่ยวๆ ลงใน AIDetectedItem
                        val singleExtra = mutableMapOf<String, Any>()
                        singleExtra["mask_bitmap"] = maskBitmap.copy(Bitmap.Config.ARGB_8888, true)
                        
                        items.add(
                            AIDetectedItem(
                                label = "Subject ${index + 1}",
                                confidence = 1.0f,
                                boundingBox = android.graphics.RectF(
                                    subject.startX.toFloat(),
                                    subject.startY.toFloat(),
                                    (subject.startX + subject.width).toFloat(),
                                    (subject.startY + subject.height).toFloat()
                                ),
                                extra = singleExtra
                            )
                        )
                        
                        maskBitmap.recycle()
                    }
                }

                if (hasAnyBitmap) {
                    val combinedExtra = mutableMapOf<String, Any>()
                    combinedExtra["combined_subject_bitmap"] = combinedBitmap
                    combinedExtra["mask_bitmap"] = highlightCombinedBitmap

                    // ชิ้นรวมอยู่ท้ายสุดเพื่อไม่ให้ทับกรอบเส้นปะรายชิ้น
                    items.add(0,
                        AIDetectedItem(
                            label = "Combined Subjects",
                            confidence = 1.0f,
                            boundingBox = android.graphics.RectF(
                                unionLeft.toFloat(),
                                unionTop.toFloat(),
                                unionRight.toFloat(),
                                unionBottom.toFloat()
                            ),
                            extra = combinedExtra
                        )
                    )
                } else {
                    combinedBitmap.recycle()
                    highlightCombinedBitmap.recycle()
                }
            }

            AIResult(true, items, duration)
        } catch (e: Throwable) {
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