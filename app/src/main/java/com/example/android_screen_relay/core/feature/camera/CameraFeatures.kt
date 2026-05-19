package com.example.android_screen_relay.core.feature.camera

import android.content.Context
import android.os.Build
import android.util.Log
import com.example.android_screen_relay.core.feature.Feature
import com.example.android_screen_relay.core.feature.FeatureCategory
import com.example.android_screen_relay.core.feature.FeatureConfig

/**
 * Camera Control Features — wrap Camera2Controller capabilities.
 *
 * These features don't switch AI processors; they toggle camera hardware settings.
 * They interact with Camera2Controller which is managed per-session.
 */

class AutoFramingFeature : Feature {
    override val id = "auto_framing"
    override val displayName = "Auto-Framing (Center Stage)"
    override val category = FeatureCategory.CAMERA_CONTROL
    override val requiredApiLevel = 34

    override fun isSupported(context: Context): Boolean {
        return Build.VERSION.SDK_INT >= 34
        // Hardware-level check requires Camera2Controller instance,
        // which is done at runtime via Camera2Controller.isAutoFramingSupported()
    }

    override fun onEnable(context: Context, config: FeatureConfig) {
        // Camera2Controller.setAutoFraming(true) is called from UI via LaunchedEffect
        Log.d("AutoFramingFeature", "Auto-framing enabled (UI will sync to Camera2Controller)")
    }

    override fun onDisable(context: Context) {
        Log.d("AutoFramingFeature", "Auto-framing disabled")
    }
}

class FlashFeature : Feature {
    override val id = "flash"
    override val displayName = "Flash / Torch"
    override val category = FeatureCategory.CAMERA_CONTROL

    override fun onEnable(context: Context, config: FeatureConfig) {
        Log.d("FlashFeature", "Flash enabled")
    }

    override fun onDisable(context: Context) {
        Log.d("FlashFeature", "Flash disabled")
    }
}

class HorizontalFlipFeature : Feature {
    override val id = "horizontal_flip"
    override val displayName = "Horizontal Flip"
    override val category = FeatureCategory.CAMERA_CONTROL

    override fun onEnable(context: Context, config: FeatureConfig) {
        Log.d("HorizontalFlipFeature", "Horizontal flip enabled")
    }

    override fun onDisable(context: Context) {
        Log.d("HorizontalFlipFeature", "Horizontal flip disabled")
    }
}

class VerticalFlipFeature : Feature {
    override val id = "vertical_flip"
    override val displayName = "Vertical Flip"
    override val category = FeatureCategory.CAMERA_CONTROL

    override fun onEnable(context: Context, config: FeatureConfig) {
        Log.d("VerticalFlipFeature", "Vertical flip enabled")
    }

    override fun onDisable(context: Context) {
        Log.d("VerticalFlipFeature", "Vertical flip disabled")
    }
}
