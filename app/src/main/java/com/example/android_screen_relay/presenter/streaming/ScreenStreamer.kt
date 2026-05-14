package com.example.android_screen_relay.presenter.streaming

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class StreamState(
    val isStreaming: Boolean = false,
    val targetIp: String = "",
    val fps: Int = 0,
    val resolution: String = "720p",
    val statusMessage: String = "Disconnected"
)

object ScreenStreamer {
    private val _state = MutableStateFlow(StreamState())
    val state = _state.asStateFlow()

    fun updateState(transform: (StreamState) -> StreamState) {
        _state.update(transform)
    }

    fun startStreaming(context: Context, ip: String) {
        updateState { it.copy(isStreaming = true, targetIp = ip, statusMessage = "Connecting to $ip...") }
        // TODO: Implement actual streaming logic (e.g., MediaProjection, WebRTC, WebSocket relay)
        // Simulated connection success:
        updateState { it.copy(statusMessage = "Streaming to $ip") }
    }

    fun stopStreaming() {
        updateState { it.copy(isStreaming = false, statusMessage = "Disconnected") }
        // TODO: Stop streaming logic
    }
}
