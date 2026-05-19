package com.example.android_screen_relay.core.feature

import android.content.Context

/**
 * Category classification for features.
 * Used by FeatureRegistry to group and filter features.
 */
enum class FeatureCategory(val displayName: String) {
    AI_DETECTION("AI Detection"),
    AI_SEGMENTATION("AI Segmentation"),
    AI_OCR("AI OCR"),
    AI_VERIFICATION("AI Verification"),
    CAMERA_CONTROL("Camera Control"),
    STREAMING("Streaming"),
    SYSTEM("System")
}

/**
 * Configuration passed to a feature during enable/init.
 * Replaces ad-hoc Map<String, Any> options with a structured config.
 */
data class FeatureConfig(
    val useGpu: Boolean = true,
    val threads: Int = 4,
    val options: Map<String, Any> = emptyMap()
)

/**
 * Core abstraction for a modular, swappable feature.
 *
 * Each Feature wraps an existing processor/controller and exposes a
 * uniform interface that the FeatureRegistry can manage.
 *
 * Design goals:
 *  - Existing code is NOT modified; features wrap around existing singletons.
 *  - Features can be registered/unregistered at runtime.
 *  - UI can query available features per category and render toggles dynamically.
 */
interface Feature {
    /** Unique identifier, e.g. "face_detection", "auto_framing" */
    val id: String

    /** Human-readable display name for UI */
    val displayName: String

    /** Category for grouping in UI and logic */
    val category: FeatureCategory

    /** Minimum Android API level required (0 = no restriction) */
    val requiredApiLevel: Int
        get() = 0

    /**
     * Runtime check: is this feature supported on the current device?
     * Override to add hardware/permission/API-level checks.
     */
    fun isSupported(context: Context): Boolean = true

    /**
     * Called when the feature is enabled/activated.
     * Implementations should delegate to existing processor init/switch logic.
     */
    fun onEnable(context: Context, config: FeatureConfig = FeatureConfig())

    /**
     * Called when the feature is disabled/deactivated.
     * Implementations should delegate to existing processor release logic.
     */
    fun onDisable(context: Context)
}
