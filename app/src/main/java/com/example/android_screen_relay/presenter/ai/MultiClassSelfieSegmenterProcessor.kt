package com.example.android_screen_relay.core

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.util.Log
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.ByteBufferExtractor
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.imagesegmenter.ImageSegmenter
import java.nio.ByteOrder

/**
 * Multi-Class Selfie Segmenter using MediaPipe's ImageSegmenter with selfie_multiclass_256x256.tflite
 *
 * Categories:
 *  0 - background
 *  1 - hair
 *  2 - body-skin
 *  3 - face-skin
 *  4 - clothes
 *  5 - others (accessories)
 *
 * Supports two output modes:
 *  - "Category Mask": Shows all 6 classes with distinct colors overlaid
 *  - "Confidence Mask": Shows only the selected class highlighted with a single color
 */
class MultiClassSelfieSegmenterProcessor : AIProcessor {
    override val name: String = "MultiClassSelfieSegmenter"
    private var segmenter: ImageSegmenter? = null
    private val lock = Any()

    // Colors for each category (matching MediaPipe demo)
    companion object {
        val CLASS_COLORS = intArrayOf(
            Color.argb(128, 66, 133, 244),   // 0 - background: rgb(66, 133, 244)
            Color.argb(200, 128, 0, 0),       // 1 - hair: rgba(128, 0, 0, 0.784)
            Color.argb(200, 0, 128, 0),       // 2 - body-skin: rgba(0, 128, 0, 0.784)
            Color.argb(200, 128, 128, 0),     // 3 - face-skin: rgba(128, 128, 0, 0.784)
            Color.argb(200, 0, 0, 128),       // 4 - clothes: rgba(0, 0, 128, 0.784)
            Color.argb(200, 128, 0, 128),     // 5 - others: rgba(128, 0, 128, 0.784)
        )

        // Solid highlight color for single-class mode (confidence mask)
        val HIGHLIGHT_COLOR = Color.argb(180, 0, 80, 255)  // Solid Blue for single class
    }

    override fun init(context: Context, config: AIConfig): Boolean {
        return try {
            synchronized(lock) {
                val baseOptions = BaseOptions.builder()
                    .setModelAssetPath("selfie_multiclass_256x256.tflite")
                    .build()

                val options = ImageSegmenter.ImageSegmenterOptions.builder()
                    .setBaseOptions(baseOptions)
                    .setOutputCategoryMask(true)
                    .setOutputConfidenceMasks(true)
                    .build()

                segmenter = ImageSegmenter.createFromOptions(context, options)
            }
            Log.d("MultiClassSelfie", "Initialized successfully")
            true
        } catch (e: Exception) {
            Log.e("MultiClassSelfie", "Init error", e)
            false
        }
    }

    override fun process(bitmap: Bitmap, options: Map<String, Any>): AIResult {
        val start = System.currentTimeMillis()
        return try {
            val outputType = options["output_type"] as? String ?: "Category Mask"
            val selectClassStr = options["select_class"] as? String ?: "0 - background"
            val selectedClassIndex = selectClassStr.substringBefore(" ").toIntOrNull() ?: 0

            val mpImage = BitmapImageBuilder(bitmap).build()

            val result = synchronized(lock) {
                val currentSegmenter = segmenter ?: throw IllegalStateException("Segmenter is closed")
                currentSegmenter.segment(mpImage)
            }

            val duration = System.currentTimeMillis() - start

            val items = mutableListOf<AIDetectedItem>()

            if (outputType == "Category Mask") {
                // Category Mask mode: Show all classes with distinct colors
                val categoryMask = result.categoryMask().orElse(null)
                if (categoryMask != null) {
                    val maskWidth = categoryMask.width
                    val maskHeight = categoryMask.height
                    val totalPixels = maskWidth * maskHeight

                    val byteBuffer = ByteBufferExtractor.extract(categoryMask)
                    byteBuffer.rewind()

                    val maskBitmap = Bitmap.createBitmap(maskWidth, maskHeight, Bitmap.Config.ARGB_8888)
                    val pixels = IntArray(totalPixels)

                    for (i in 0 until totalPixels) {
                        val classIndex = (byteBuffer.get().toInt() and 0xFF)
                        pixels[i] = if (classIndex > 0 && classIndex < CLASS_COLORS.size) {
                            CLASS_COLORS[classIndex]
                        } else {
                            Color.TRANSPARENT
                        }
                    }
                    maskBitmap.setPixels(pixels, 0, maskWidth, 0, 0, maskWidth, maskHeight)

                    // Calculate Bounding Box for Category Mask (All non-background classes)
                    var minX = maskWidth
                    var minY = maskHeight
                    var maxX = -1
                    var maxY = -1
                    var found = false

                    for (y in 0 until maskHeight) {
                        for (x in 0 until maskWidth) {
                            val pixel = pixels[y * maskWidth + x]
                            if (pixel != Color.TRANSPARENT && pixel != CLASS_COLORS[0]) { // 0 is background
                                if (x < minX) minX = x
                                if (x > maxX) maxX = x
                                if (y < minY) minY = y
                                if (y > maxY) maxY = y
                                found = true
                            }
                        }
                    }

                    if (found) {
                        val scaleX = bitmap.width.toFloat() / maskWidth
                        val scaleY = bitmap.height.toFloat() / maskHeight
                        val bbox = android.graphics.RectF(minX * scaleX, minY * scaleY, (maxX + 1) * scaleX, (maxY + 1) * scaleY)
                        items.add(
                            AIDetectedItem(
                                label = "Multi-Class Selfie Mask",
                                confidence = 1.0f,
                                boundingBox = bbox,
                                extra = mapOf(
                                    "width" to maskWidth,
                                    "height" to maskHeight,
                                    "mask_bitmap" to maskBitmap,
                                    "output_type" to "category"
                                )
                            )
                        )
                    } else {
                        maskBitmap.recycle()
                    }
                }
            } else {
                // Confidence Mask mode: Show only the selected class
                val confidenceMasks = result.confidenceMasks().orElse(null)
                if (confidenceMasks != null && selectedClassIndex < confidenceMasks.size) {
                    val selectedMask = confidenceMasks[selectedClassIndex]
                    val maskWidth = selectedMask.width
                    val maskHeight = selectedMask.height
                    val totalPixels = maskWidth * maskHeight

                    val byteBuffer = ByteBufferExtractor.extract(selectedMask)
                    byteBuffer.order(ByteOrder.nativeOrder())
                    val floatBuffer = byteBuffer.asFloatBuffer()

                    val maskBitmap = Bitmap.createBitmap(maskWidth, maskHeight, Bitmap.Config.ARGB_8888)
                    val pixels = IntArray(totalPixels)

                    // Use the color for the selected class
                    val highlightColor = CLASS_COLORS.getOrElse(selectedClassIndex) { HIGHLIGHT_COLOR }
                    val r = Color.red(highlightColor)
                    val g = Color.green(highlightColor)
                    val b = Color.blue(highlightColor)

                    for (i in 0 until totalPixels) {
                        if (i < floatBuffer.capacity()) {
                            val confidence = floatBuffer.get(i)
                            if (confidence > 0.3f) {
                                // Vary alpha based on confidence for smoother edges
                                val alpha = (confidence * 200).toInt().coerceIn(0, 220)
                                pixels[i] = Color.argb(alpha, r, g, b)
                            } else {
                                pixels[i] = Color.TRANSPARENT
                            }
                        }
                    }
                    maskBitmap.setPixels(pixels, 0, maskWidth, 0, 0, maskWidth, maskHeight)

                    // Calculate Bounding Box for Confidence Mask
                    var minX = maskWidth
                    var minY = maskHeight
                    var maxX = -1
                    var maxY = -1
                    var found = false

                    for (y in 0 until maskHeight) {
                        for (x in 0 until maskWidth) {
                            val pixel = pixels[y * maskWidth + x]
                            if (pixel != Color.TRANSPARENT) {
                                if (x < minX) minX = x
                                if (x > maxX) maxX = x
                                if (y < minY) minY = y
                                if (y > maxY) maxY = y
                                found = true
                            }
                        }
                    }

                    if (found) {
                        val scaleX = bitmap.width.toFloat() / maskWidth
                        val scaleY = bitmap.height.toFloat() / maskHeight
                        val bbox = android.graphics.RectF(minX * scaleX, minY * scaleY, (maxX + 1) * scaleX, (maxY + 1) * scaleY)
                        items.add(
                            AIDetectedItem(
                                label = "Confidence Mask: $selectClassStr",
                                confidence = 1.0f,
                                boundingBox = bbox,
                                extra = mapOf(
                                    "width" to maskWidth,
                                    "height" to maskHeight,
                                    "mask_bitmap" to maskBitmap,
                                    "output_type" to "confidence",
                                    "selected_class" to selectedClassIndex
                                )
                            )
                        )
                    } else {
                        maskBitmap.recycle()
                    }
                }
            }

            AIResult(true, items, duration)
        } catch (e: Exception) {
            Log.e("MultiClassSelfie", "Process error", e)
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
