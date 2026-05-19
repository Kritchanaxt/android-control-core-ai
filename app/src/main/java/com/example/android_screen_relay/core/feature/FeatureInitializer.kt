package com.example.android_screen_relay.core.feature

import com.example.android_screen_relay.core.feature.ai.*
import com.example.android_screen_relay.core.feature.camera.*

/**
 * Convenience function to register all built-in features.
 * Call this once during app startup (e.g., in RelayApplication.onCreate()).
 *
 * This does NOT affect any existing code — it simply populates the registry
 * so that future UI/logic can query available features.
 */
object FeatureInitializer {

    fun registerAll() {
        FeatureRegistry.registerAll(
            // AI Detection (5)
            FaceDetectionFeature(),
            PoseDetectionFeature(),
            ObjectDetectionFeature(),
            CustomObjectDetectionFeature(),
            HandDetectionFeature(),

            // AI Segmentation (4)
            SelfieSegmentationFeature(),
            MultiClassSelfieFeature(),
            SubjectSegmentationFeature(),
            VerificationSegmentationFeature(),

            // AI OCR (3)
            PaddleOCRFeature(),
            TesseractOCRFeature(),
            TextRecognitionFeature(),

            // AI Verification (2)
            IdentityVerificationFeature(),
            VerifiedAutoCaptureFeature(),

            // Camera Controls (4)
            AutoFramingFeature(),
            FlashFeature(),
            HorizontalFlipFeature(),
            VerticalFlipFeature()
        )

        FeatureRegistry.dump()
    }
}
