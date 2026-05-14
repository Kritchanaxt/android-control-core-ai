package com.example.android_screen_relay.presenter.recording

import android.content.Context
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.os.Environment
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class RecordState(
    val isRecording: Boolean = false,
    val outputPath: String? = null,
    val durationSeconds: Long = 0L,
    val statusMessage: String = "Idle"
)

object ScreenRecorder {
    private val _state = MutableStateFlow(RecordState())
    val state = _state.asStateFlow()

    private var mediaRecorder: MediaRecorder? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var currentProjection: MediaProjection? = null
    private var context: Context? = null
    
    private var screenWidth = 720
    private var screenHeight = 1280
    private var screenDensity = 320

    // Set max file size for Auto Split (e.g., 500MB)
    private const val MAX_FILE_SIZE_BYTES = 500L * 1024 * 1024

    fun updateState(transform: (RecordState) -> RecordState) {
        _state.update(transform)
    }

    private var onSplitListener: ((android.view.Surface) -> Unit)? = null

    fun getSurface(): android.view.Surface? {
        return mediaRecorder?.surface
    }

    fun startRecording(ctx: Context, width: Int, height: Int, onSplit: (android.view.Surface) -> Unit) {
        if (_state.value.isRecording) return
        
        context = ctx.applicationContext
        screenWidth = width
        screenHeight = height
        onSplitListener = onSplit

        try {
            initMediaRecorder()
            
            mediaRecorder?.start()
            updateState { it.copy(isRecording = true, statusMessage = "Recording...") }
            Log.d("ScreenRecorder", "Recording started successfully")
        } catch (e: Exception) {
            Log.e("ScreenRecorder", "Failed to start recording", e)
            stopRecording()
        }
    }
    
    private fun initMediaRecorder() {
        mediaRecorder = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            MediaRecorder(context!!)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }

        val outputFile = getOutputFile()
        
        mediaRecorder?.apply {
            setVideoSource(MediaRecorder.VideoSource.SURFACE)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setOutputFile(outputFile.absolutePath)
            setVideoSize(screenWidth, screenHeight)
            setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            setVideoEncodingBitRate(5 * 1024 * 1024) // 5 Mbps
            setVideoFrameRate(30)
            
            setMaxFileSize(MAX_FILE_SIZE_BYTES)
            
            setOnInfoListener { _, what, _ ->
                if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_REACHED) {
                    Log.i("ScreenRecorder", "Max file size reached. Auto splitting file...")
                    splitRecording()
                }
            }
            
            prepare()
        }
        
        updateState { it.copy(outputPath = outputFile.absolutePath) }
    }

    private fun splitRecording() {
        Log.d("ScreenRecorder", "Restarting MediaRecorder for split...")
        try {
            mediaRecorder?.stop()
        } catch (e: Exception) {
            Log.e("ScreenRecorder", "Error stopping during split", e)
        }
        mediaRecorder?.reset()
        mediaRecorder?.release()
        
        try {
            initMediaRecorder()
            mediaRecorder?.start()
            
            // Notify pipeline to swap surface
            mediaRecorder?.surface?.let { newSurface ->
                onSplitListener?.invoke(newSurface)
            }
            
            Log.d("ScreenRecorder", "Split successful, recording to new file: ${_state.value.outputPath}")
        } catch (e: Exception) {
            Log.e("ScreenRecorder", "Failed to resume recording after split", e)
            stopRecording()
        }
    }

    fun stopRecording() {
        if (!_state.value.isRecording) return

        try {
            mediaRecorder?.stop()
        } catch (e: Exception) {
            Log.e("ScreenRecorder", "Error stopping recording", e)
        } finally {
            mediaRecorder?.reset()
            mediaRecorder?.release()
            mediaRecorder = null
            
            onSplitListener = null
        }
        updateState { it.copy(isRecording = false, statusMessage = "Recording Saved") }
        Log.d("ScreenRecorder", "Recording stopped")
    }

    private fun getOutputFile(): File {
        val moviesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)
        val appDir = File(moviesDir, "ScreenRelayRecords")
        if (!appDir.exists()) {
            appDir.mkdirs()
        }
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        return File(appDir, "Record_${timestamp}.mp4")
    }
}
