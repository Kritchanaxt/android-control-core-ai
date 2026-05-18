package com.example.android_screen_relay.core

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.util.Log
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.ByteBufferExtractor
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.imagesegmenter.ImageSegmenter
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult
import java.nio.ByteOrder

/**
 * Verification Segmentation (Multi-class Selfie + Hand detection)
 */
class VerificationSegmentationProcessor : AIProcessor {
    override val name: String = "Verification Segmetation (Multi-class Selfie + Hand detection)"
    private var segmenter: ImageSegmenter? = null
    private var handLandmarker: HandLandmarker? = null
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

                // Initialize HandLandmarker on CPU to avoid GPU OOM issues with dual models
                val handBaseOptions = BaseOptions.builder()
                    .setModelAssetPath("hand_landmarker.task")
                    .setDelegate(com.google.mediapipe.tasks.core.Delegate.CPU)
                    .build()

                val handOptions = HandLandmarker.HandLandmarkerOptions.builder()
                    .setBaseOptions(handBaseOptions)
                    .setMinHandDetectionConfidence(0.15f)
                    .setMinHandPresenceConfidence(0.15f)
                    .setMinTrackingConfidence(0.15f)
                    .setNumHands(2)
                    .setRunningMode(RunningMode.IMAGE)
                    .build()

                handLandmarker = HandLandmarker.createFromOptions(context, handOptions)
            }
            Log.d("VerificationSeg", "Initialized successfully")
            true
        } catch (e: Exception) {
            Log.e("VerificationSeg", "Init error", e)
            false
        }
    }

    private val processLock = java.util.concurrent.atomic.AtomicBoolean(false)

    override fun process(bitmap: Bitmap, options: Map<String, Any>): AIResult {
        val isSnap = options["is_snap"] as? Boolean ?: false

        if (isSnap) {
            // For SNAP: We MUST process it. Wait until the processor is free.
            var waitCount = 0
            while (!processLock.compareAndSet(false, true)) {
                Thread.sleep(10)
                waitCount++
                if (waitCount > 500) { // Timeout after 5 seconds to prevent deadlock
                    return AIResult(false, emptyList(), 0, "Timeout waiting for processor")
                }
            }
        } else {
            // For PREVIEW: Strictly drop the frame if already processing to prevent concurrent crashes
            if (!processLock.compareAndSet(false, true)) {
                return AIResult(false, emptyList(), 0, "Dropped preview frame")
            }
        }

        val start = System.currentTimeMillis()
        return try {
            val outputType = options["output_type"] as? String ?: "Category Mask"
            val selectClassStr = options["select_class"] as? String ?: "0 - background"
            val selectedClassIndex = selectClassStr.substringBefore(" ").toIntOrNull() ?: 0
            val isIdCardMode = options["is_id_card_mode"] as? Boolean ?: true

            Log.d("VerificationSeg", "process() isSnap=$isSnap isIdCardMode=$isIdCardMode bitmap=${bitmap.width}x${bitmap.height}")

            val mpImage = BitmapImageBuilder(bitmap).build()
            var freshMpImage: com.google.mediapipe.framework.image.MPImage? = null
            var paddedMpImage: com.google.mediapipe.framework.image.MPImage? = null

            // ── AI 1: Selfie Segmentation ──
            val result = synchronized(lock) {
                val currentSegmenter = segmenter ?: throw IllegalStateException("Segmenter is closed")
                currentSegmenter.segment(mpImage)
            }

            val duration = System.currentTimeMillis() - start
            val items = mutableListOf<AIDetectedItem>()

            // ── FACE ZONE DETECTION + CLASS ARRAY (single buffer read) ──
            val catMask = result.categoryMask().orElse(null)
            var faceMinX = Int.MAX_VALUE; var faceMaxX = 0
            var faceMinY = Int.MAX_VALUE; var faceMaxY = 0
            var faceFound = false
            var catMaskWidth = 256; var catMaskHeight = 256
            var classArray: ByteArray? = null

            if (catMask != null) {
                catMaskWidth = catMask.width
                catMaskHeight = catMask.height
                val totalPx = catMaskWidth * catMaskHeight

                // 🌟 Read buffer ONCE into array — reused for both face detection AND pixel building
                val buf = ByteBufferExtractor.extract(catMask)
                buf.rewind()
                classArray = ByteArray(totalPx)
                buf.get(classArray)

                // Find face-skin (class 3) and hair (class 1) bounding box
                for (i in 0 until totalPx) {
                    val cls = classArray[i].toInt() and 0xFF
                    if (cls == 1 || cls == 3) {
                        val x = i % catMaskWidth
                        val y = i / catMaskWidth
                        if (x < faceMinX) faceMinX = x
                        if (x > faceMaxX) faceMaxX = x
                        if (y < faceMinY) faceMinY = y
                        if (y > faceMaxY) faceMaxY = y
                        faceFound = true
                    }
                }
            }

            // Define "Square Face Zone" (Inner Box 1x) and "Outer Zone" (Outer Box 2x)
            val rawFaceW = if (faceFound) (faceMaxX - faceMinX) else 0
            val rawFaceH = if (faceFound) (faceMaxY - faceMinY) else 0
            val faceSize = maxOf(rawFaceW, rawFaceH)
            val faceCenterX = if (faceFound) (faceMinX + faceMaxX) / 2 else catMaskWidth / 2
            val faceCenterY = if (faceFound) (faceMinY + faceMaxY) / 2 else catMaskHeight / 2

            // Inner Box (1x)
            val innerBoxLeft = faceCenterX - faceSize / 2
            val innerBoxRight = faceCenterX + faceSize / 2
            val innerBoxTop = faceCenterY - faceSize / 2
            val innerBoxBottom = faceCenterY + faceSize / 2

            // Outer Box (2x)
            val outerBoxLeft = faceCenterX - faceSize
            val outerBoxRight = faceCenterX + faceSize
            val outerBoxTop = faceCenterY - faceSize
            val outerBoxBottom = faceCenterY + faceSize

            Log.d("VerificationSeg", "Face zone: faceFound=$faceFound, faceSize=$faceSize, innerBox=[$innerBoxLeft,$innerBoxTop,$innerBoxRight,$innerBoxBottom], outerBox=[$outerBoxLeft,$outerBoxTop,$outerBoxRight,$outerBoxBottom]")

            // ── AI 2: Hand Detection (Real-time optimized for 2GB RAM devices) ──
            val handBBoxes = mutableListOf<android.graphics.Rect>()

            Log.d("VerificationSeg", "Running Hand Detection...")

            // Scale down to 256px for live preview (lightning fast) and 480px for snap (high res)
            val handDetectMaxDim = if (isSnap) 480f else 256f
            val currentMax = maxOf(bitmap.width, bitmap.height).toFloat()
            val handScale = if (currentMax > handDetectMaxDim) handDetectMaxDim / currentMax else 1f
            val handBitmap = if (handScale < 1f) {
                Bitmap.createScaledBitmap(bitmap, (bitmap.width * handScale).toInt(), (bitmap.height * handScale).toInt(), true)
            } else {
                bitmap
            }

            freshMpImage = BitmapImageBuilder(handBitmap).build()

            // Attempt 1: Detect on scaled image
            var detectedResult: HandLandmarkerResult? = synchronized(lock) {
                try {
                    handLandmarker?.detect(freshMpImage)
                } catch (e: Exception) {
                    Log.e("VerificationSeg", "Hand detection failed", e)
                    null
                }
            }
            var finalScale = 1.0f

            Log.d("VerificationSeg", "Hand detect attempt 1 (${handBitmap.width}x${handBitmap.height}): found=${detectedResult?.landmarks()?.size ?: 0} hands")

            // Attempt 2-4: Fallback with padded scaling (ONLY on Snap to prevent OOM on 2GB RAM devices)
            if (isSnap && (detectedResult == null || detectedResult!!.landmarks().isEmpty())) {
                val w = handBitmap.width.toFloat()
                val h = handBitmap.height.toFloat()
                val scalesToTry = listOf(2.0f, 2.5f, 3.0f)
                for (scale in scalesToTry) {
                    val padW = (w * scale).toInt()
                    val padH = (h * scale).toInt()
                    // Safety: skip if padded bitmap would be too large (>2000px)
                    if (padW > 2000 || padH > 2000) continue

                    val paddedBitmap = Bitmap.createBitmap(padW, padH, Bitmap.Config.ARGB_8888)
                    val canvas = android.graphics.Canvas(paddedBitmap)
                    canvas.drawColor(android.graphics.Color.BLACK)
                    val offsetX = (padW - w) / 2f
                    val offsetY = (padH - h) / 2f
                    canvas.drawBitmap(handBitmap, offsetX, offsetY, null)

                    paddedMpImage = BitmapImageBuilder(paddedBitmap).build()
                    val paddedHandResult = synchronized(lock) { 
                        try { handLandmarker?.detect(paddedMpImage) } catch (e: Exception) { null } 
                    }
                    paddedBitmap.recycle()

                    Log.d("VerificationSeg", "Hand detect fallback scale=$scale: found=${paddedHandResult?.landmarks()?.size ?: 0} hands")

                    if (paddedHandResult != null && paddedHandResult.landmarks().isNotEmpty()) {
                        detectedResult = paddedHandResult
                        finalScale = scale
                        break
                    }
                }
            }

            // Recycle the scaled hand detection bitmap if we created one
            if (handBitmap !== bitmap && !handBitmap.isRecycled) {
                handBitmap.recycle()
            }

            // Convert hand landmarks to mask-space bounding boxes
            val handLandmarkResult = detectedResult
            if (handLandmarkResult != null && handLandmarkResult.landmarks().isNotEmpty()) {
                val maskWidth = catMaskWidth
                val maskHeight = catMaskHeight

                for ((handIdx, landmarks) in handLandmarkResult.landmarks().withIndex()) {
                    var hMinX = Float.MAX_VALUE
                    var hMinY = Float.MAX_VALUE
                    var hMaxX = Float.MIN_VALUE
                    var hMaxY = Float.MIN_VALUE
                    for (lm in landmarks) {
                        val px: Float
                        val py: Float
                        if (finalScale > 1f) {
                            px = (lm.x() * finalScale - (finalScale - 1f) / 2f) * maskWidth
                            py = (lm.y() * finalScale - (finalScale - 1f) / 2f) * maskHeight
                        } else {
                            px = lm.x() * maskWidth
                            py = lm.y() * maskHeight
                        }
                        if (px < hMinX) hMinX = px
                        if (px > hMaxX) hMaxX = px
                        if (py < hMinY) hMinY = py
                        if (py > hMaxY) hMaxY = py
                    }
                    val bw = hMaxX - hMinX
                    val bh = hMaxY - hMinY
                    
                    // 1. Tight BBox for Intersection Check (0% padding)
                    val tightLeft = hMinX.toInt().coerceAtLeast(0)
                    val tightTop = hMinY.toInt().coerceAtLeast(0)
                    val tightRight = hMaxX.toInt().coerceAtMost(maskWidth - 1)
                    val tightBottom = hMaxY.toInt().coerceAtMost(maskHeight - 1)

                    // 🔥 CRITICAL REQUIREMENT: If Hand overlaps Face Zone, we MUST reject capture immediately!
                    // Using tight bbox so hands *next* to the face don't falsely trigger rejection.
                    if (faceFound) {
                        val intersectX = maxOf(tightLeft, innerBoxLeft) < minOf(tightRight, innerBoxRight)
                        val intersectY = maxOf(tightTop, innerBoxTop) < minOf(tightBottom, innerBoxBottom)
                        if (intersectX && intersectY) {
                            Log.w("VerificationSeg", "🚨 Hand detected OVERLAPPING face zone! Rejecting capture.")
                            return AIResult(false, emptyList(), (System.currentTimeMillis() - start), "Hand covering face")
                        }
                    }

                    // 2. Padded BBox for Hole-Punching (20% padding to cover finger edges cleanly)
                    val padX = bw * 0.2f
                    val padY = bh * 0.2f
                    val left = (hMinX - padX).toInt().coerceAtLeast(0)
                    val top = (hMinY - padY).toInt().coerceAtLeast(0)
                    val right = (hMaxX + padX).toInt().coerceAtMost(maskWidth - 1)
                    val bottom = (hMaxY + padY).toInt().coerceAtMost(maskHeight - 1)
                    
                    val bbox = android.graphics.Rect(left, top, right, bottom)
                    handBBoxes.add(bbox)
                    Log.d("VerificationSeg", "Hand #$handIdx bbox: [$left, $top, $right, $bottom] (mask ${maskWidth}x${maskHeight})")
                }
            }

            Log.d("VerificationSeg", "Total hand bboxes to subtract: ${handBBoxes.size}")

            if (outputType == "Category Mask") {
                if (catMask != null && classArray != null) {
                    val maskWidth = catMask.width
                    val maskHeight = catMask.height
                    val totalPixels = maskWidth * maskHeight

                    val maskBitmap = Bitmap.createBitmap(maskWidth, maskHeight, Bitmap.Config.ARGB_8888)
                    val pixels = IntArray(totalPixels)

                    var bodySkinRemoved = 0
                    var handBBoxRemoved = 0

                    for (i in 0 until totalPixels) {
                        val classIndex = classArray[i].toInt() and 0xFF
                        val x = i % maskWidth
                        val y = i / maskWidth

                        // Check HandLandmarker bounding boxes
                        var isHandBBox = false
                        for (bbox in handBBoxes) {
                            if (x >= bbox.left && x <= bbox.right && y >= bbox.top && y <= bbox.bottom) {
                                isHandBBox = true
                                break
                            }
                        }

                        // Determine zones
                        val isInsideOuter = faceFound && x >= outerBoxLeft && x <= outerBoxRight && y >= outerBoxTop && y <= outerBoxBottom

                        // Spatial Heuristic filtering
                        var keepPixel = false
                        if (faceFound) {
                            if (isIdCardMode) {
                                // Strict ID Card Mode: Must be inside Outer Box
                                if (isInsideOuter) {
                                    if (classIndex == 1 || classIndex == 3 || classIndex == 4) {
                                        keepPixel = true
                                    } else if (classIndex == 2) {
                                        val isBelowFace = y >= (innerBoxBottom - faceSize * 0.15f)
                                        val isUnderFaceHorizontally = x >= innerBoxLeft && x <= innerBoxRight
                                        if (isBelowFace && isUnderFaceHorizontally) {
                                            keepPixel = true
                                        }
                                    }
                                }
                            } else {
                                // Live Face Mode: No Outer Box constraint, allow full body/shoulders
                                if (classIndex == 1 || classIndex == 3 || classIndex == 4) {
                                    keepPixel = true
                                } else if (classIndex == 2) {
                                    // Body skin only allowed below face to prevent covering face
                                    val isBelowFace = y >= (innerBoxBottom - faceSize * 0.15f)
                                    if (isBelowFace) {
                                        keepPixel = true
                                    }
                                }
                            }
                        } else {
                            // Fallback if face not found
                            if (classIndex > 0 && classIndex < CLASS_COLORS.size) keepPixel = true
                        }

                        pixels[i] = if (isHandBBox) {
                            handBBoxRemoved++
                            Color.TRANSPARENT
                        } else if (!keepPixel) {
                            bodySkinRemoved++ // Reusing counter for any removed non-background pixels
                            Color.TRANSPARENT
                        } else if (classIndex > 0 && classIndex < CLASS_COLORS.size) {
                            CLASS_COLORS[classIndex]
                        } else {
                            Color.TRANSPARENT
                        }
                    }

                    Log.d("VerificationSeg", "Mask subtraction: bodySkinRemoved=$bodySkinRemoved, handBBoxRemoved=$handBBoxRemoved")

                    maskBitmap.setPixels(pixels, 0, maskWidth, 0, 0, maskWidth, maskHeight)

                    // Calculate Bounding Box
                    var minX = maskWidth; var minY = maskHeight
                    var maxX = -1; var maxY = -1
                    var found = false

                    for (y in 0 until maskHeight) {
                        for (x in 0 until maskWidth) {
                            val pixel = pixels[y * maskWidth + x]
                            if (pixel != Color.TRANSPARENT && pixel != CLASS_COLORS[0]) {
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
                                label = "Verification Segmetation Mask",
                                confidence = 1.0f,
                                boundingBox = bbox,
                                extra = mapOf(
                                    "width" to maskWidth,
                                    "height" to maskHeight,
                                    "mask_bitmap" to maskBitmap,
                                    "output_type" to "category",
                                    "hands_subtracted" to handBBoxes.size,
                                    "body_skin_removed" to bodySkinRemoved
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

                    // Also read category mask for spatial filtering
                    val catBuf = if (catMask != null) {
                        val b = ByteBufferExtractor.extract(catMask)
                        b.rewind()
                        b
                    } else null

                    val maskBitmap = Bitmap.createBitmap(maskWidth, maskHeight, Bitmap.Config.ARGB_8888)
                    val pixels = IntArray(totalPixels)

                    // Use the color for the selected class
                    val highlightColor = CLASS_COLORS.getOrElse(selectedClassIndex) { HIGHLIGHT_COLOR }
                    val r = Color.red(highlightColor)
                    val g = Color.green(highlightColor)
                    val b = Color.blue(highlightColor)

                    for (i in 0 until totalPixels) {
                        // Read category class for spatial heuristic
                        val catClass = if (catBuf != null && i < catMaskWidth * catMaskHeight) {
                            catBuf.get().toInt() and 0xFF
                        } else -1

                        if (i < floatBuffer.capacity()) {
                            val confidence = floatBuffer.get(i)
                            val x = i % maskWidth
                            val y = i / maskWidth

                            // Check HandLandmarker bounding boxes
                            var isHandBBox = false
                            for (bbox in handBBoxes) {
                                if (x >= bbox.left && x <= bbox.right && y >= bbox.top && y <= bbox.bottom) {
                                    isHandBBox = true
                                    break
                                }
                            }

                            // Determine zones
                            val isInsideOuter = faceFound && x >= outerBoxLeft && x <= outerBoxRight && y >= outerBoxTop && y <= outerBoxBottom

                            // Check Spatial Heuristic
                            var keepPixel = false
                            if (faceFound) {
                                if (isIdCardMode) {
                                    // Strict ID Card Mode
                                    if (isInsideOuter) {
                                        if (catClass == 1 || catClass == 3 || catClass == 4) {
                                            keepPixel = true
                                        } else if (catClass == 2) {
                                            val isBelowFace = y >= (innerBoxBottom - faceSize * 0.15f)
                                            val isUnderFaceHorizontally = x >= innerBoxLeft && x <= innerBoxRight
                                            if (isBelowFace && isUnderFaceHorizontally) {
                                                keepPixel = true
                                            }
                                        }
                                    }
                                } else {
                                    // Live Face Mode
                                    if (catClass == 1 || catClass == 3 || catClass == 4) {
                                        keepPixel = true
                                    } else if (catClass == 2) {
                                        val isBelowFace = y >= (innerBoxBottom - faceSize * 0.15f)
                                        if (isBelowFace) {
                                            keepPixel = true
                                        }
                                    }
                                }
                            } else {
                                if (catClass > 0) keepPixel = true
                            }

                            if (isHandBBox || !keepPixel) {
                                pixels[i] = Color.TRANSPARENT
                            } else if (confidence > 0.3f) {
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
                                    "selected_class" to selectedClassIndex,
                                    "hands_subtracted" to handBBoxes.size
                                )
                            )
                        )
                    } else {
                        maskBitmap.recycle()
                    }
                }
            }
            // ── CLEANUP NATIVE MEMORY TO PREVENT OOM AND BATTERY DRAIN ──
            try {
                // DO NOT close mpImage, freshMpImage, or paddedMpImage because closing an MPImage built from a Bitmap
                // will call bitmap.recycle() under the hood, crashing the Camera preview loop!
                result.categoryMask().ifPresent { it.close() }
                result.confidenceMasks().ifPresent { masks -> masks.forEach { it.close() } }
            } catch (e: Exception) {
                Log.e("VerificationSeg", "Error closing MPImage", e)
            }

            AIResult(true, items, duration)
        } catch (e: Exception) {
            Log.e("VerificationSeg", "Process error", e)
            AIResult(false, emptyList(), 0, e.message)
        } finally {
            processLock.set(false)
        }
    }

    override fun release() {
        synchronized(lock) {
            segmenter?.close()
            segmenter = null
            handLandmarker?.close()
            handLandmarker = null
        }
    }
}
