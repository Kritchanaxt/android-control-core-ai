package com.example.android_screen_relay.core.feature.ai

import android.content.Context
import com.example.android_screen_relay.core.AIManager
import com.example.android_screen_relay.core.AiMode
import com.example.android_screen_relay.core.feature.Feature
import com.example.android_screen_relay.core.feature.FeatureCategory
import com.example.android_screen_relay.core.feature.FeatureConfig

/**
 * AI Segmentation Features — wrap existing segmentation processors.
 */

class SelfieSegmentationFeature : Feature {
    override val id = "selfie_segmentation"
    override val displayName = "Selfie Segmentation"
    override val category = FeatureCategory.AI_SEGMENTATION

    override fun onEnable(context: Context, config: FeatureConfig) {
        AIManager.switchProcessor(context, AiMode.SELFIE_SEGMENTATION, config.options)
    }

    override fun onDisable(context: Context) {
        AIManager.release()
    }
}

class MultiClassSelfieFeature : Feature {
    override val id = "multi_class_selfie"
    override val displayName = "Multi-Class Selfie Segmentation"
    override val category = FeatureCategory.AI_SEGMENTATION

    override fun onEnable(context: Context, config: FeatureConfig) {
        AIManager.switchProcessor(context, AiMode.MULTI_CLASS_SELFIE_SEGMENTATION, config.options)
    }

    override fun onDisable(context: Context) {
        AIManager.release()
    }
}

class SubjectSegmentationFeature : Feature {
    override val id = "subject_segmentation"
    override val displayName = "Subject Segmentation"
    override val category = FeatureCategory.AI_SEGMENTATION

    override fun onEnable(context: Context, config: FeatureConfig) {
        AIManager.switchProcessor(context, AiMode.SUBJECT_SEGMENTATION, config.options)
    }

    override fun onDisable(context: Context) {
        AIManager.release()
    }
}

class VerificationSegmentationFeature : Feature {
    override val id = "verification_segmentation"
    override val displayName = "Verification Segmentation"
    override val category = FeatureCategory.AI_SEGMENTATION

    override fun onEnable(context: Context, config: FeatureConfig) {
        AIManager.switchProcessor(context, AiMode.VERIFICATION_SEGMENTATION, config.options)
    }

    override fun onDisable(context: Context) {
        AIManager.release()
    }
}
