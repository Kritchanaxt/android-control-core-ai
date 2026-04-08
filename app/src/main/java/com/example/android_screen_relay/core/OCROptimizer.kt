package com.example.android_screen_relay.core

import android.graphics.Bitmap

object OCROptimizer {
    
    /**
     * Scales an image down so its longest side is at most maxDimension.
     * Useful for optimizing OCR on low-end devices without losing aspect ratio.
     */
    fun scaleDownToMaxDimension(bitmap: Bitmap, maxDimension: Int): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        
        if (width <= maxDimension && height <= maxDimension) {
            return bitmap // No scaling needed
        }
        
        val ratio: Float = Math.min(
            maxDimension.toFloat() / width,
            maxDimension.toFloat() / height
        )
        
        val newWidth = Math.round(ratio * width)
        val newHeight = Math.round(ratio * height)
        
        return Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
    }
    
    /**
     * Crop from the center of the image.
     * widthRatio and heightRatio: e.g. 0.5f will crop 50% of width and height from center.
     */
    fun cropCenter(bitmap: Bitmap, widthRatio: Float, heightRatio: Float): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        
        val targetWidth = (width * widthRatio).toInt()
        val targetHeight = (height * heightRatio).toInt()
        
        val startX = (width - targetWidth) / 2
        val startY = (height - targetHeight) / 2
        
        return Bitmap.createBitmap(bitmap, startX, startY, targetWidth, targetHeight)
    }
    
    /**
     * Crop a specific region of interest. (e.g., only upper half where subtitles might be)
     */
    fun cropRegion(bitmap: Bitmap, xStartRatio: Float, yStartRatio: Float, widthRatio: Float, heightRatio: Float): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        
        val startX = (width * xStartRatio).toInt()
        val startY = (height * yStartRatio).toInt()
        val cropWidth = (width * widthRatio).toInt()
        val cropHeight = (height * heightRatio).toInt()
        
        return Bitmap.createBitmap(bitmap, startX, startY, cropWidth, cropHeight)
    }
}
