package com.example.android_screen_relay.opengl

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.Matrix
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import com.example.android_screen_relay.opengl.grafika.FullFrameRect
import com.example.android_screen_relay.opengl.grafika.Texture2dProgram

class GLScreenSharingPipeline(
    private val width: Int,
    private val height: Int,
    private val surface1: Surface, // e.g., ImageReader for streaming
    private val surface2: Surface  // e.g., MediaRecorder for recording
) {
    private val TAG = "GLScreenSharing"

    private var renderThread: HandlerThread? = null
    private var renderHandler: Handler? = null

    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface1: EGLSurface = EGL14.EGL_NO_SURFACE
    private var eglSurface2: EGLSurface = EGL14.EGL_NO_SURFACE

    private var fullFrameRect: FullFrameRect? = null
    private var textureId: Int = -1
    private var surfaceTexture: SurfaceTexture? = null
    private var inputSurface: Surface? = null
    
    private val transformMatrix = FloatArray(16)
    
    private val EGL_RECORDABLE_ANDROID = 0x3142
    
    @Volatile
    private var isRunning = false

    private var virtualDisplay: android.hardware.display.VirtualDisplay? = null

    interface PipelineCallback {
        fun onPipelineReady()
        fun onError(e: Exception)
    }

    fun updateSurface2(newSurface: Surface) {
        renderHandler?.post {
            if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
                // Destroy old surface
                if (eglSurface2 != EGL14.EGL_NO_SURFACE) {
                    EGL14.eglDestroySurface(eglDisplay, eglSurface2)
                    eglSurface2 = EGL14.EGL_NO_SURFACE
                }
                
                // Get config
                val attribList = intArrayOf(
                    EGL14.EGL_RED_SIZE, 8,
                    EGL14.EGL_GREEN_SIZE, 8,
                    EGL14.EGL_BLUE_SIZE, 8,
                    EGL14.EGL_ALPHA_SIZE, 8,
                    EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                    EGL_RECORDABLE_ANDROID, 1,
                    EGL14.EGL_NONE
                )
                val configs = arrayOfNulls<EGLConfig>(1)
                val numConfigs = IntArray(1)
                EGL14.eglChooseConfig(eglDisplay, attribList, 0, configs, 0, configs.size, numConfigs, 0)
                if (numConfigs[0] > 0) {
                    val config = configs[0]
                    val surfaceAttribs = intArrayOf(EGL14.EGL_NONE)
                    eglSurface2 = EGL14.eglCreateWindowSurface(eglDisplay, config, newSurface, surfaceAttribs, 0)
                }
            }
        }
    }

    fun start(mediaProjection: android.media.projection.MediaProjection, densityDpi: Int, callback: PipelineCallback) {
        isRunning = true
        renderThread = HandlerThread("GLRenderThread")
        renderThread?.start()
        renderHandler = Handler(renderThread!!.looper)

        renderHandler?.post {
            try {
                initGL()
                
                virtualDisplay = mediaProjection.createVirtualDisplay(
                    "GLScreenSharing",
                    width, height, densityDpi,
                    android.hardware.display.DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    inputSurface, null, null
                )
                
                callback.onPipelineReady()
            } catch (e: Exception) {
                Log.e(TAG, "Error initializing GL", e)
                callback.onError(e)
            }
        }
    }

    fun stop() {
        isRunning = false
        renderHandler?.post {
            virtualDisplay?.release()
            virtualDisplay = null
            releaseGL()
            renderThread?.quitSafely()
        }
    }

    private fun initGL() {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (eglDisplay == EGL14.EGL_NO_DISPLAY) throw RuntimeException("unable to get EGL14 display")

        val version = IntArray(2)
        if (!EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) {
            throw RuntimeException("unable to initialize EGL14")
        }

        val attribList = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL_RECORDABLE_ANDROID, 1,
            EGL14.EGL_NONE
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val numConfigs = IntArray(1)
        EGL14.eglChooseConfig(eglDisplay, attribList, 0, configs, 0, configs.size, numConfigs, 0)
        if (numConfigs[0] == 0) throw RuntimeException("unable to find RGB888+recordable ES2 EGL config")
        val config = configs[0]

        val contextAttribs = intArrayOf(
            EGL14.EGL_CONTEXT_CLIENT_VERSION, 2,
            EGL14.EGL_NONE
        )
        eglContext = EGL14.eglCreateContext(eglDisplay, config, EGL14.EGL_NO_CONTEXT, contextAttribs, 0)
        checkEglError("eglCreateContext")

        val surfaceAttribs = intArrayOf(EGL14.EGL_NONE)
        eglSurface1 = EGL14.eglCreateWindowSurface(eglDisplay, config, surface1, surfaceAttribs, 0)
        checkEglError("eglCreateWindowSurface 1")
        eglSurface2 = EGL14.eglCreateWindowSurface(eglDisplay, config, surface2, surfaceAttribs, 0)
        checkEglError("eglCreateWindowSurface 2")

        EGL14.eglMakeCurrent(eglDisplay, eglSurface1, eglSurface1, eglContext)

        fullFrameRect = FullFrameRect(Texture2dProgram(Texture2dProgram.ProgramType.TEXTURE_EXT))
        textureId = fullFrameRect!!.createTextureObject()

        surfaceTexture = SurfaceTexture(textureId)
        surfaceTexture?.setDefaultBufferSize(width, height)
        inputSurface = Surface(surfaceTexture)

        surfaceTexture?.setOnFrameAvailableListener { st ->
            if (isRunning) {
                renderHandler?.post {
                    drawFrame()
                }
            }
        }
    }

    private fun drawFrame() {
        if (!isRunning) return
        
        try {
            surfaceTexture?.updateTexImage()
            surfaceTexture?.getTransformMatrix(transformMatrix)
    
            // Render to surface 1 (Stream)
            EGL14.eglMakeCurrent(eglDisplay, eglSurface1, eglSurface1, eglContext)
            GLES20.glViewport(0, 0, width, height)
            fullFrameRect?.drawFrame(textureId, transformMatrix)
            
            // Pass timestamp for smoother recording / stream
            val timestamp = surfaceTexture?.timestamp ?: 0L
            EGLExt.eglPresentationTimeANDROID(eglDisplay, eglSurface1, timestamp)
            EGL14.eglSwapBuffers(eglDisplay, eglSurface1)
    
            // Render to surface 2 (Record)
            EGL14.eglMakeCurrent(eglDisplay, eglSurface2, eglSurface2, eglContext)
            GLES20.glViewport(0, 0, width, height)
            fullFrameRect?.drawFrame(textureId, transformMatrix)
            EGLExt.eglPresentationTimeANDROID(eglDisplay, eglSurface2, timestamp)
            EGL14.eglSwapBuffers(eglDisplay, eglSurface2)
        } catch (e: Exception) {
            Log.e(TAG, "Error drawing frame", e)
        }
    }

    private fun releaseGL() {
        if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            if (eglSurface1 != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(eglDisplay, eglSurface1)
            if (eglSurface2 != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(eglDisplay, eglSurface2)
            EGL14.eglDestroyContext(eglDisplay, eglContext)
            EGL14.eglReleaseThread()
            EGL14.eglTerminate(eglDisplay)
        }
        
        eglDisplay = EGL14.EGL_NO_DISPLAY
        eglContext = EGL14.EGL_NO_CONTEXT
        eglSurface1 = EGL14.EGL_NO_SURFACE
        eglSurface2 = EGL14.EGL_NO_SURFACE

        inputSurface?.release()
        inputSurface = null
        surfaceTexture?.release()
        surfaceTexture = null
        fullFrameRect?.release()
        fullFrameRect = null
    }

    private fun checkEglError(msg: String) {
        val error = EGL14.eglGetError()
        if (error != EGL14.EGL_SUCCESS) {
            throw RuntimeException(msg + ": EGL error: 0x" + Integer.toHexString(error))
        }
    }
}