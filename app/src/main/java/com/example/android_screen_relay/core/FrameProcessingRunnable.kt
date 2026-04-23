package com.example.android_screen_relay.core

import android.graphics.Bitmap
import android.util.Log

/**
 * Ported from ML Kit Vision Quickstart CameraSource.java FrameProcessingRunnable.
 * This keeps a single background thread running continuously. When a new frame arrives,
 * it replaces the pending frame. The background thread wakes up, takes the frame,
 * and processes it. This is efficient for normal/high-spec devices as it avoids
 * constant thread creation or task dispatching overhead.
 */
class FrameProcessingRunnable(
    private val frameProcessor: (Bitmap) -> Unit
) : Runnable {
    private val lock = Object()
    private var active = true
    private var pendingFrameData: Bitmap? = null

    /**
     * Marks the runnable as active/not active. Signals any blocked threads to continue.
     */
    fun release() {
        synchronized(lock) {
            active = false
            lock.notifyAll()
        }
    }

    /**
     * Sets the frame data received from the camera. This replaces the previous unused frame.
     */
    fun setNextFrame(bitmap: Bitmap) {
        synchronized(lock) {
            if (pendingFrameData != null) {
                pendingFrameData?.recycle()
                pendingFrameData = null
            }
            pendingFrameData = bitmap
            lock.notifyAll()
        }
    }

    override fun run() {
        var data: Bitmap? = null

        while (true) {
            synchronized(lock) {
                while (active && pendingFrameData == null) {
                    try {
                        lock.wait()
                    } catch (e: InterruptedException) {
                        Log.d("FrameProcessing", "Frame processing loop terminated.", e)
                        return
                    }
                }

                if (!active) {
                    return
                }

                data = pendingFrameData
                pendingFrameData = null
            }

            try {
                if (data != null) {
                    frameProcessor(data!!)
                    // Ensure the processed bitmap is recycled after use to prevent memory leaks
                    if (!data!!.isRecycled) {
                         data!!.recycle()
                    }
                }
            } catch (t: Throwable) {
                Log.e("FrameProcessing", "Exception thrown from receiver.", t)
            } finally {
                data = null
            }
        }
    }
}
