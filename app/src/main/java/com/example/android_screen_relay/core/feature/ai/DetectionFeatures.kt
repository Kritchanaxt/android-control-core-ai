package com.example.android_screen_relay.core.feature.ai

import android.content.Context
import com.example.android_screen_relay.core.AIManager
import com.example.android_screen_relay.core.AiMode
import com.example.android_screen_relay.core.feature.Feature
import com.example.android_screen_relay.core.feature.FeatureCategory
import com.example.android_screen_relay.core.feature.FeatureConfig

/**
 * AI Detection Features — wrap existing processors without modifying them.
 * Each feature delegates to AIManager.switchProcessor(AiMode) under the hood.
 */

class FaceDetectionFeature : Feature {
    override val id = "face_detection"
    override val displayName = "Face Detection"
    override val category = FeatureCategory.AI_DETECTION

    override fun onEnable(context: Context, config: FeatureConfig) {
        AIManager.switchProcessor(context, AiMode.FACE_DETECTION, config.options)
    }

    override fun onDisable(context: Context) {
        AIManager.release()
    }
}

class PoseDetectionFeature : Feature {
    override val id = "pose_detection"
    override val displayName = "Pose Detection"
    override val category = FeatureCategory.AI_DETECTION

    override fun onEnable(context: Context, config: FeatureConfig) {
        AIManager.switchProcessor(context, AiMode.POSE_DETECTION, config.options)
    }

    override fun onDisable(context: Context) {
        AIManager.release()
    }
}

class ObjectDetectionFeature : Feature {
    override val id = "object_detection"
    override val displayName = "Object Detection"
    override val category = FeatureCategory.AI_DETECTION

    override fun onEnable(context: Context, config: FeatureConfig) {
        AIManager.switchProcessor(context, AiMode.OBJECT_DETECTION, config.options)
    }

    override fun onDisable(context: Context) {
        AIManager.release()
    }
}

class CustomObjectDetectionFeature : Feature {
    override val id = "custom_object_detection"
    override val displayName = "Custom Object Detection"
    override val category = FeatureCategory.AI_DETECTION

    override fun onEnable(context: Context, config: FeatureConfig) {
        AIManager.switchProcessor(context, AiMode.CUSTOM_OBJECT_DETECTION, config.options)
    }

    override fun onDisable(context: Context) {
        AIManager.release()
    }
}

class HandDetectionFeature : Feature {
    override val id = "hand_detection"
    override val displayName = "Palmprint / Hand Detection"
    override val category = FeatureCategory.AI_DETECTION

    override fun onEnable(context: Context, config: FeatureConfig) {
        AIManager.switchProcessor(context, AiMode.HAND_DETECTION, config.options)
    }

    override fun onDisable(context: Context) {
        AIManager.release()
    }
}
