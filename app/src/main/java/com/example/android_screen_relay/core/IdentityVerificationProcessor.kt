package com.example.android_screen_relay.core

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PointF
import android.graphics.RectF
import android.util.Log

enum class VerificationState {
    WAITING_FOR_CARD,
    OCR_PROCESSING,
    WAITING_FOR_FACE,
    STABILITY_COUNT,
    RESULT_SUMMARY
}

class IdentityVerificationProcessor : AIProcessor {
    override val name: String = "IdentityVerificationPipeline"

    private var ocrProcessor: OCRProcessor? = null
    private var faceProcessor: FaceDetectorProcessor? = null

    private var currentState = VerificationState.WAITING_FOR_CARD
    private var startTimeForStability: Long = 0L
    private var capturedFaceImage: Bitmap? = null
    private var capturedCardImage: Bitmap? = null
    private var capturedCardFaceImage: Bitmap? = null
    internal var extractedOcrText: String = ""
    private var extractedIdInfo: String = ""
    private var extractedNameInfo: String = ""
    private var extractedDobInfo: String = ""
    private val ocrEngineName = "PaddleOCRv5"
    private var lastFaceMetrics: String = "{}"

    override fun init(context: Context, config: AIConfig): Boolean {
        ocrProcessor = OCRProcessor()
        faceProcessor = FaceDetectorProcessor()
        val ocrInit = ocrProcessor?.init(context, config) ?: false
        val faceInit = faceProcessor?.init(context, config) ?: false
        return ocrInit && faceInit
    }

    override fun process(bitmap: Bitmap, options: Map<String, Any>): AIResult {
        val start = System.currentTimeMillis()
        
        try {
            if (bitmap.isRecycled) return AIResult(false, emptyList(), 0, "Bitmap is recycled")

            when (currentState) {
                VerificationState.WAITING_FOR_CARD -> {
                    // Frame-by-frame seq flow: Step 1 Crop (using the exact Virtual Frame logic)
                    val cw = bitmap.width.toFloat()
                    val ch = bitmap.height.toFloat()
                    val cardW = cw * 0.85f
                    val cardH = cardW / 1.58f
                    val cardLeft = (cw - cardW) / 2f
                    val cardTop = (ch - cardH) / 2f
                    
                    // Simple logic to transition since we assume the person presents the card.
                    // For the pipeline, we immediately proceed to OCR next frame based on prompt instruction.
                    currentState = VerificationState.OCR_PROCESSING
                    
                    return AIResult(
                        success = true,
                        items = emptyList(),
                        processTimeMs = System.currentTimeMillis() - start,
                        errorMessage = "STATE: WAITING_FOR_CARD -> Proceeding to Crop & OCR"
                    )
                }

                VerificationState.OCR_PROCESSING -> {
                    // Step 2: Use PaddleOCR with Logic Crop
                    val cw = bitmap.width.toFloat()
                    val ch = bitmap.height.toFloat()
                    val cardW = cw * 0.85f
                    val cardH = cardW / 1.58f
                    val cardLeft = ((cw - cardW) / 2f).toInt()
                    val cardTop = ((ch - cardH) / 2f).toInt()
                    
                    val cardBitmap = Bitmap.createBitmap(bitmap, cardLeft, cardTop, cardW.toInt(), cardH.toInt())
                    val ocrResult = ocrProcessor?.process(cardBitmap, options)

                    // Use OCRFormatter to get structured text
                    val rawJson = ocrProcessor?.getRawJson(cardBitmap) ?: "[]"
                    val formattedText = OCRFormatter.formatRawOCRResult(rawJson)
                    val text = formattedText
                    
                    // 1. Regex for ID: 13 digits with optional spaces or dashes
                    val idRegex = Regex("""\d[\s-]*\d{4}[\s-]*\d{5}[\s-]*\d{2}[\s-]*\d""")
                    val idMatch = idRegex.find(text)?.value

                    // 2. Regex for Name: Look for Thai titles or "ชื่อ" followed by Thai/English words
                    val nameRegex = Regex("""(?:ชื่อ|นาย|นาง|นางสาว|ด\.ช\.|ด\.ญ\.|Mr\.|Mrs\.|Ms\.)\s*([ก-๙a-zA-Z]+)[\s\n]+([ก-๙a-zA-Z]+)""")
                    val nameMatch = nameRegex.find(text)?.value

                    // 3. Regex for DOB: dd MMM yyyy (Thai abbreviation or full name)
                    val dobRegex = Regex("""\d{1,2}\s*(?:ม\.ค\.|ก\.พ\.|มี\.ค\.|เม\.ย\.|พ\.ค\.|มิ\.ย\.|ก\.ค\.|ส\.ค\.|ก\.ย\.|ต\.ค\.|พ\.ย\.|ธ\.ค\.|มกราคม|กุมภาพันธ์|มีนาคม|เมษายน|พฤษภาคม|มิถุนายน|กรกฎาคม|สิงหาคม|กันยายน|ตุลาคม|พฤศจิกายน|ธันวาคม)\s*\d{4}""")
                    val dobMatch = dobRegex.find(text)?.value

                    val isFoundExpectedData = idMatch != null && nameMatch != null
                    
                    if (!isFoundExpectedData) {
                        Log.d("IdentityVerification", "OCR Fail: ID=$idMatch, Name=$nameMatch")
                    }
                    
                    if (isFoundExpectedData) {
                         extractedOcrText = text
                         extractedIdInfo = idMatch!!.replace(Regex("""\s+"""), " ")
                         extractedNameInfo = nameMatch!!.replace(Regex("""\s+"""), " ")
                         extractedDobInfo = dobMatch?.replace(Regex("""\s+"""), " ") ?: ""
                         
                         // LOGIC CROP: Save the card image
                         capturedCardImage = cardBitmap 

                         // NEW: Crop face on card
                         // Use "normal" mode to avoid the card-specific ROI (since we already have the card crop)
                         // Set is_front to false for card processing to avoid mirrored coordinate logic
                         val cardFaceOptions = options.toMutableMap()
                         cardFaceOptions["face_mode"] = "normal"
                         cardFaceOptions["is_front"] = false
                         
                         val faceOnCardResult = faceProcessor?.process(cardBitmap, cardFaceOptions)
                         if (faceOnCardResult != null && faceOnCardResult.success && faceOnCardResult.items.isNotEmpty()) {
                             // Pick the largest face found on the card (to avoid small text false positives)
                             val faceItem = faceOnCardResult.items.maxByOrNull { 
                                 val b = it.boundingBox
                                 b.width() * b.height() 
                             }!!
                             
                             val rect = faceItem.boundingBox
                             
                             // Add some padding to the face crop (20%)
                             val paddingW = (rect.width() * 0.2f).toInt()
                             val paddingH = (rect.height() * 0.2f).toInt()
                             val left = (rect.left - paddingW).toInt().coerceIn(0, cardBitmap.width - 1)
                             val top = (rect.top - paddingH).toInt().coerceIn(0, cardBitmap.height - 1)
                             val right = (rect.right + paddingW).toInt().coerceIn(0, cardBitmap.width)
                             val bottom = (rect.bottom + paddingH).toInt().coerceIn(0, cardBitmap.height)
                             val width = right - left
                             val height = bottom - top
                             
                             if (width > 0 && height > 0) {
                                 capturedCardFaceImage = Bitmap.createBitmap(cardBitmap, left, top, width, height)
                             }
                         }

                         currentState = VerificationState.WAITING_FOR_FACE
                    } else {
                         cardBitmap.recycle() 
                    }
                    
                    val missing = mutableListOf<String>()
                    if (idMatch == null) missing.add("ID")
                    if (nameMatch == null) missing.add("Name")

                    return AIResult(
                        success = isFoundExpectedData,
                        items = if (isFoundExpectedData) ocrResult?.items ?: emptyList() else emptyList(),
                        processTimeMs = System.currentTimeMillis() - start,
                        errorMessage = if (isFoundExpectedData) "OCR OK -> Show Face" else "Missing: ${missing.joinToString(", ")}"
                    )
                }

                VerificationState.WAITING_FOR_FACE -> {
                     // Step 3: Transition frame gap
                     currentState = VerificationState.STABILITY_COUNT
                      return AIResult(
                          success = true, 
                          items = emptyList(), 
                          processTimeMs = System.currentTimeMillis() - start, 
                          errorMessage = "Transition: Look at Camera"
                      )
                }


                VerificationState.STABILITY_COUNT -> {
                     // Step 5: Check stability again to ensure conditions maintain for 3 seconds
                     // (Pose validation removed by request)

                     val faceResult = faceProcessor?.process(bitmap, options)
                     var isFaceValid = faceResult?.success == true && faceResult?.items?.isNotEmpty() == true
                     if (isFaceValid) {
                         val faceItem = faceResult!!.items.first()
                         val yaw = (faceItem.extra["headEulerAngleY"] as? Float) ?: 0f
                         val pitch = (faceItem.extra["headEulerAngleX"] as? Float) ?: 0f
                         val roll = (faceItem.extra["headEulerAngleZ"] as? Float) ?: 0f
                         isFaceValid = Math.abs(yaw) < 25f && Math.abs(pitch) < 25f && Math.abs(roll) < 25f
                         
                         val box = faceItem.boundingBox
                         val centerX = box.centerX()
                         val centerY = box.centerY()
                         val frameCenterX = bitmap.width / 2f
                         val frameCenterY = bitmap.height / 2f
                         val offsetXPct = kotlin.math.abs(centerX - frameCenterX) / bitmap.width
                         val offsetYPct = kotlin.math.abs(centerY - frameCenterY) / bitmap.height
                         val isCentered = offsetXPct < 0.15f && offsetYPct < 0.15f
                         val faceAreaPct = (box.width() * box.height()) / (bitmap.width * bitmap.height).toFloat()
                         val isProperSize = faceAreaPct > 0.15f

                         lastFaceMetrics = org.json.JSONObject().apply {
                             put("is_centered", isCentered)
                             put("center_offset_x_pct", offsetXPct)
                             put("center_offset_y_pct", offsetYPct)
                             put("is_proper_size", isProperSize)
                             put("face_area_pct", faceAreaPct)
                             put("is_straight", isFaceValid)
                             put("yaw", yaw)
                             put("pitch", pitch)
                             put("roll", roll)
                             put("4_pillars_passed", isCentered && isProperSize && isFaceValid)
                         }.toString()
                     }

                     if (!isFaceValid) {
                         resetToPoseFace()
                         return AIResult(false, emptyList(), System.currentTimeMillis() - start, "STATE: STABILITY_COUNT -> Face lost or angles > 25")
                     }
                     
                     val elapsed = System.currentTimeMillis() - startTimeForStability
                     
                     // Capture at T+2 seconds (2000ms). Memory check: avoid multiple allocations.
                     if (elapsed > 2000 && capturedFaceImage == null) {
                         val faceBox = faceResult!!.items.first().boundingBox
                         // Try to capture a localized crop to save memory instead of full frame
                         val safeLeft = maxOf(0, faceBox.left.toInt())
                         val safeTop = maxOf(0, faceBox.top.toInt())
                         val safeWidth = minOf(bitmap.width - safeLeft, faceBox.width().toInt())
                         val safeHeight = minOf(bitmap.height - safeTop, faceBox.height().toInt())
                         
                         if (safeWidth > 0 && safeHeight > 0) {
                             capturedFaceImage = Bitmap.createBitmap(bitmap, safeLeft, safeTop, safeWidth, safeHeight)
                         }
                     }
                     
                     // End of stability at 3.0 seconds
                     if (elapsed >= 3000) {
                         currentState = VerificationState.RESULT_SUMMARY
                     }
                     
                     return AIResult(
                         success = true, 
                         items = faceResult?.items ?: emptyList(), 
                         processTimeMs = System.currentTimeMillis() - start, 
                         errorMessage = "Stability: ${elapsed}ms"
                     )
                }

                VerificationState.RESULT_SUMMARY -> {
                    // Step 6: End pipeline summary frame
                    val verificationMetrics = org.json.JSONObject().apply {
                        put("step_0_ocr", org.json.JSONObject().apply {
                            put("engine", ocrEngineName)
                            put("text_extracted", extractedOcrText)
                            put("id_found", extractedIdInfo)
                            put("name_found", extractedNameInfo)
                            put("dob_found", extractedDobInfo)
                        })
                        put("step_2_face", org.json.JSONObject(if (lastFaceMetrics.isNotEmpty()) lastFaceMetrics else "{}"))
                    }.toString()

                    val summaryItems = listOf(
                        AIDetectedItem(
                            label = "IDENTITY_VERIFICATION", 
                            confidence = 1.0f, 
                            boundingBox = RectF(), 
                            extra = mapOf(
                                "text" to extractedOcrText,
                                "has_image" to (capturedFaceImage != null),
                                "has_card_image" to (capturedCardImage != null),
                                "has_card_face" to (capturedCardFaceImage != null),
                                "face_bitmap" to (capturedFaceImage ?: Unit), 
                                "card_bitmap" to (capturedCardImage ?: Unit),
                                "card_face_bitmap" to (capturedCardFaceImage ?: Unit),
                                "verification_metrics" to verificationMetrics
                            )
                        )
                    )
                    
                    return AIResult(
                        success = true, 
                        items = summaryItems, 
                        processTimeMs = System.currentTimeMillis() - start, 
                        errorMessage = null
                    )
                }
            }
        } catch (e: Exception) {
             Log.e("IdentityVerification", "Error Process", e)
             return AIResult(false, emptyList(), System.currentTimeMillis() - start, e.message)
        }
        return AIResult(false, emptyList(), System.currentTimeMillis() - start, "Unknown state")
    }

    private fun resetToPoseFace() {
        startTimeForStability = 0L
        currentState = VerificationState.WAITING_FOR_FACE
        capturedFaceImage?.recycle()
        capturedFaceImage = null
        capturedCardImage?.recycle()
        capturedCardImage = null
        capturedCardFaceImage?.recycle()
        capturedCardFaceImage = null
    }

    // Explicit method to manually test states or reset pipeline
    fun resetPipeline() {
        currentState = VerificationState.WAITING_FOR_CARD
        startTimeForStability = 0L
        extractedOcrText = ""
        capturedFaceImage?.recycle()
        capturedFaceImage = null
        capturedCardImage?.recycle()
        capturedCardImage = null
        capturedCardFaceImage?.recycle()
        capturedCardFaceImage = null
    }

    override fun release() {
        ocrProcessor?.release()
        faceProcessor?.release()
        capturedFaceImage?.recycle()
        capturedFaceImage = null
        capturedCardImage?.recycle()
        capturedCardImage = null
        capturedCardFaceImage?.recycle()
        capturedCardFaceImage = null
        ocrProcessor = null
        faceProcessor = null
    }
}
