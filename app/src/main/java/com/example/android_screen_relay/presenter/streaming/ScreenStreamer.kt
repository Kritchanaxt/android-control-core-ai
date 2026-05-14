package com.example.android_screen_relay.presenter.streaming

import com.example.android_screen_relay.RelayServer
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

    private var relayServer: RelayServer? = null

    fun updateState(transform: (StreamState) -> StreamState) {
        _state.update(transform)
    }

    fun attachServer(server: RelayServer?) {
        relayServer = server
    }

    fun startStreaming() {
        updateState { it.copy(isStreaming = true, statusMessage = "Broadcasting stream...") }
    }

    fun broadcastFrame(imageBytes: ByteArray) {
        if (_state.value.isStreaming) {
            relayServer?.broadcastToAuthenticated(imageBytes)
        }
    }

    fun stopStreaming() {
        updateState { it.copy(isStreaming = false, statusMessage = "Disconnected") }
        relayServer = null
    }
}
