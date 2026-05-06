package com.example.android_screen_relay

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream

class ScreenCaptureManager(private val context: Context) {
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var backgroundThread: android.os.HandlerThread? = null
    private var backgroundHandler: android.os.Handler? = null
    
    private var onFrameCaptured: ((ByteArray) -> Unit)? = null
    
    // Configurable props
    private var targetWidth = 0
    private var targetHeight = 0
    private var jpegQuality = 50

    private val density = Resources.getSystem().displayMetrics.densityDpi
    
    private var reusableBitmap: Bitmap? = null

    @SuppressLint("WrongConstant")
    fun startCapture(resultCode: Int, data: Intent, qualityMode: Int, onFrameCaptured: (ByteArray) -> Unit) {
        this.onFrameCaptured = onFrameCaptured
        
        // Configure Quality
        val screenWidth = Resources.getSystem().displayMetrics.widthPixels
        val screenHeight = Resources.getSystem().displayMetrics.heightPixels
        
        val isPortrait = screenHeight > screenWidth
        val maxSafeWidth = if (isPortrait) 1080 else 1920
        val maxSafeHeight = if (isPortrait) 1920 else 1080
        val maxUltraWidth = if (isPortrait) 1440 else 2560
        val maxUltraHeight = if (isPortrait) 2560 else 1440

        when (qualityMode) {
            0 -> { // Low (SD)
                val scale = 0.4f
                targetWidth = (screenWidth * scale).toInt()
                targetHeight = (screenHeight * scale).toInt()
                jpegQuality = 40
            }
            1 -> { // Medium (HD) - 720p approx
                val scale = 0.6f
                targetWidth = (screenWidth * scale).toInt()
                targetHeight = (screenHeight * scale).toInt()
                jpegQuality = 60
            }
            2 -> { // High (Full HD)
                val scaleW = maxSafeWidth.toFloat() / screenWidth
                val scaleH = maxSafeHeight.toFloat() / screenHeight
                val scale = minOf(scaleW, scaleH, 1.0f)
                targetWidth = (screenWidth * scale).toInt()
                targetHeight = (screenHeight * scale).toInt()
                jpegQuality = 75
            }
            3 -> { // Ultra (2K/Native High Quality)
                val scaleW = maxUltraWidth.toFloat() / screenWidth
                val scaleH = maxUltraHeight.toFloat() / screenHeight
                val scale = minOf(scaleW, scaleH, 1.0f)
                targetWidth = (screenWidth * scale).toInt()
                targetHeight = (screenHeight * scale).toInt()
                jpegQuality = 90
            }
            else -> {
                targetWidth = screenWidth / 2
                targetHeight = screenHeight / 2
                jpegQuality = 50
            }
        }
        
        // Ensure even dimensions for video encoding standards (though we use JPEG, it's safer for ImageReader)
        if (targetWidth % 2 != 0) targetWidth--
        if (targetHeight % 2 != 0) targetHeight--

        // Start background thread
        backgroundThread = android.os.HandlerThread("ScreenCaptureThread")
        backgroundThread?.start()
        backgroundHandler = android.os.Handler(backgroundThread!!.looper)

        val mpManager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = mpManager.getMediaProjection(resultCode, data)

        mediaProjection?.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                super.onStop()
                stopCapture()
            }
        }, backgroundHandler)

        try {
            // Setup ImageReader
            imageReader = ImageReader.newInstance(targetWidth, targetHeight, PixelFormat.RGBA_8888, 2)
            
            virtualDisplay = mediaProjection?.createVirtualDisplay(
                "ScreenCapture",
                targetWidth, targetHeight, density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader!!.surface, null, backgroundHandler
            )
            
            val width = targetWidth
            val height = targetHeight

            imageReader?.setOnImageAvailableListener({ reader ->
                val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
                try {
                    val planes = image.planes
                    val buffer = planes[0].buffer
                    val pixelStride = planes[0].pixelStride
                    val rowStride = planes[0].rowStride
                    val rowPadding = rowStride - pixelStride * width
                    
                    val totalWidth = width + rowPadding / pixelStride

                    if (reusableBitmap == null || reusableBitmap!!.width != totalWidth || reusableBitmap!!.height != height) {
                        reusableBitmap?.recycle()
                        reusableBitmap = Bitmap.createBitmap(totalWidth, height, Bitmap.Config.ARGB_8888)
                    }

                    reusableBitmap!!.copyPixelsFromBuffer(buffer)
                    
                    if (rowPadding == 0) {
                        sendBitmap(reusableBitmap!!)
                    } else { 
                        val croppedBitmap = Bitmap.createBitmap(reusableBitmap!!, 0, 0, width, height)
                        sendBitmap(croppedBitmap)
                        croppedBitmap.recycle()
                    }
                } catch (e: Throwable) {
                    // Log.e("ScreenCapture", "Error processing image: ${e.message}")
                } finally {
                    image.close()
                }
            }, backgroundHandler)
        } catch (e: Throwable) {
            android.util.Log.e("ScreenCaptureManager", "Fatal error starting VirtualDisplay", e)
            stopCapture()
        } 
    }

    private fun sendBitmap(bitmap: Bitmap) {
        try {
            val outputStream = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, jpegQuality, outputStream)
            val imageBytes = outputStream.toByteArray()
            onFrameCaptured?.invoke(imageBytes)
        } catch (e: Throwable) {
            e.printStackTrace()
        }
    }

    fun stopCapture() {
        try {
            mediaProjection?.stop()
        } catch(e: Exception) {}
        mediaProjection = null
        
        try {
            virtualDisplay?.release()
        } catch(e: Exception) {}
        virtualDisplay = null
        
        try {
            imageReader?.close()
        } catch(e: Exception) {}
        imageReader = null
        
        backgroundThread?.quitSafely()
        backgroundThread = null // Don't join, just let it die
        backgroundHandler = null
        
        reusableBitmap?.recycle()
        reusableBitmap = null
    }
}
