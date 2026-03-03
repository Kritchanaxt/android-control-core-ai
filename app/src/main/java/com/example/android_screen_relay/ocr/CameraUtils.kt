package com.example.android_screen_relay.ocr

import android.util.Size

enum class UiAspectRatio(
    val displayName: String,
    val shortDescription: String,
    val value: Float?,
    val targetWidthFactorForPreview: Int,
    val targetHeightFactorForPreview: Int,
    val isPortraitDefault: Boolean = false
) {
    FULL("Full (Sensor Ratio)", "Full", null, 0, 0),
    RATIO_1_1("1:1", "1:1", 1f / 1f, 1, 1),
    RATIO_4_3("4:3", "4:3", 4f / 3f, 4, 3),
    RATIO_3_4("3:4 Portrait", "3:4", 3f / 4f, 3, 4, true),
    RATIO_16_9("16:9", "16:9", 16f / 9f, 16, 9),
    RATIO_9_16("9:16 Portrait", "9:16", 9f / 16f, 9, 16, true);

    val isSpecialRatio: Boolean
        get() = when (this) {
            RATIO_4_3, RATIO_3_4, RATIO_16_9, RATIO_9_16 -> true
            else -> false
        }

    companion object {
        fun fromName(name: String?): UiAspectRatio? = entries.find { it.name == name }
    }
}

enum class VerticalAlignment {
    TOP,
    CENTER,
    BOTTOM
}

data class ResolutionItem(val size: Size?, val displayText: String) {
    override fun toString(): String = displayText
}
