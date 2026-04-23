package com.example.android_screen_relay.core

import android.graphics.RectF

/**
 * Utility to map coordinates between pixel-based systems and normalized (%) systems.
 */
object CoordinateMapper {

    /**
     * Converts pixel coordinates to normalized (0.0 - 1.0) coordinates.
     */
    fun toRelative(pixel: Float, total: Float): Float {
        return if (total > 0) pixel / total else 0f
    }

    /**
     * Converts a RectF from pixels to normalized (0.0 - 1.0) values.
     */
    fun toRelativeRect(rect: RectF, width: Float, height: Float): RectF {
        return RectF(
            toRelative(rect.left, width),
            toRelative(rect.top, height),
            toRelative(rect.right, width),
            toRelative(rect.bottom, height)
        )
    }

    /**
     * Converts normalized coordinates back to pixels for a specific resolution.
     */
    fun toPixel(relative: Float, total: Float): Float {
        return relative * total
    }

    /**
     * Converts a normalized RectF back to pixel-based RectF for a specific resolution.
     */
    fun toPixelRect(relativeRect: RectF, width: Float, height: Float): RectF {
        return RectF(
            toPixel(relativeRect.left, width),
            toPixel(relativeRect.top, height),
            toPixel(relativeRect.right, width),
            toPixel(relativeRect.bottom, height)
        )
    }
    
    /**
     * Flips the X coordinate for mirrored views (e.g., front camera).
     */
    fun flipX(relativeX: Float): Float {
        return 1.0f - relativeX
    }

    /**
     * Flips a relative RectF horizontally for mirrored views.
     */
    fun flipRectX(relativeRect: RectF): RectF {
        return RectF(
            1.0f - relativeRect.right,
            relativeRect.top,
            1.0f - relativeRect.left,
            relativeRect.bottom
        )
    }
}
