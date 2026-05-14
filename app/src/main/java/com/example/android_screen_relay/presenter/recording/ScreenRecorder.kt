package com.example.android_screen_relay.presenter.recording

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class RecordState(
    val isRecording: Boolean = false,
    val outputPath: String? = null,
    val durationSeconds: Long = 0L,
    val statusMessage: String = "Idle"
)

object ScreenRecorder {
    private val _state = MutableStateFlow(RecordState())
    val state = _state.asStateFlow()

    fun updateState(transform: (RecordState) -> RecordState) {
        _state.update(transform)
    }

    fun startRecording(context: Context) {
        updateState { it.copy(isRecording = true, statusMessage = "Recording...") }
        // TODO: Implement actual recording logic (e.g., MediaRecorder, MediaProjection API)
    }

    fun stopRecording() {
        updateState { it.copy(isRecording = false, statusMessage = "Recording Saved") }
        // TODO: Stop recording logic and save file
    }
}
