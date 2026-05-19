package com.example.android_screen_relay.core.feature.ai

import android.content.Context
import com.example.android_screen_relay.core.AIManager
import com.example.android_screen_relay.core.AiMode
import com.example.android_screen_relay.core.feature.Feature
import com.example.android_screen_relay.core.feature.FeatureCategory
import com.example.android_screen_relay.core.feature.FeatureConfig

/**
 * AI OCR Features — wrap existing OCR processors (PaddleOCR, Tesseract, ML Kit Text).
 */

class PaddleOCRFeature : Feature {
    override val id = "paddle_ocr"
    override val displayName = "PaddleOCR (Thai+English)"
    override val category = FeatureCategory.AI_OCR

    override fun onEnable(context: Context, config: FeatureConfig) {
        AIManager.switchProcessor(context, AiMode.PADDLE_OCR, config.options)
    }

    override fun onDisable(context: Context) {
        AIManager.release()
    }
}

class TesseractOCRFeature : Feature {
    override val id = "tesseract_ocr"
    override val displayName = "Tesseract Fast OCR"
    override val category = FeatureCategory.AI_OCR

    override fun onEnable(context: Context, config: FeatureConfig) {
        AIManager.switchProcessor(context, AiMode.TESSERACT_FAST_OCR, config.options)
    }

    override fun onDisable(context: Context) {
        AIManager.release()
    }
}

class TextRecognitionFeature : Feature {
    override val id = "text_recognition"
    override val displayName = "ML Kit Text Recognition"
    override val category = FeatureCategory.AI_OCR

    override fun onEnable(context: Context, config: FeatureConfig) {
        AIManager.switchProcessor(context, AiMode.TEXT_RECOGNITION, config.options)
    }

    override fun onDisable(context: Context) {
        AIManager.release()
    }
}
