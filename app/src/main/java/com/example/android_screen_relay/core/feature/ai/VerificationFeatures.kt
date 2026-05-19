package com.example.android_screen_relay.core.feature.ai

import android.content.Context
import com.example.android_screen_relay.core.AIManager
import com.example.android_screen_relay.core.AiMode
import com.example.android_screen_relay.core.feature.Feature
import com.example.android_screen_relay.core.feature.FeatureCategory
import com.example.android_screen_relay.core.feature.FeatureConfig

/**
 * AI Verification Features — wrap identity verification and auto-capture processors.
 */

class IdentityVerificationFeature : Feature {
    override val id = "identity_verification"
    override val displayName = "Identity Verification"
    override val category = FeatureCategory.AI_VERIFICATION

    override fun onEnable(context: Context, config: FeatureConfig) {
        AIManager.switchProcessor(context, AiMode.IDENTITY_VERIFICATION, config.options)
    }

    override fun onDisable(context: Context) {
        AIManager.release()
    }
}

class VerifiedAutoCaptureFeature : Feature {
    override val id = "verified_auto_capture"
    override val displayName = "Verified Auto Capture"
    override val category = FeatureCategory.AI_VERIFICATION

    override fun onEnable(context: Context, config: FeatureConfig) {
        AIManager.switchProcessor(context, AiMode.VERIFIED_AUTO_CAPTURE, config.options)
    }

    override fun onDisable(context: Context) {
        AIManager.release()
    }
}
