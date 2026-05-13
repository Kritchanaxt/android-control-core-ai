package com.example.android_screen_relay

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.Response
import android.util.Log

import okio.ByteString

class WebSocketManager(
    private val url: String,
    private val authKey: String? = null,
    private val onMessageReceived: ((String) -> Unit)? = null,
    private val onBinaryReceived: ((ByteArray) -> Unit)? = null,
    private val onConnectionOpened: (() -> Unit)? = null,
    private val onConnectionFailed: ((String) -> Unit)? = null
) {
    private var client: OkHttpClient = OkHttpClient()
    private var webSocket: WebSocket? = null

    fun connect() {
        val request = Request.Builder().url(url).build()
        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d("WebSocketManager", "Connected to $url")
                
                // Auto-Send Auth Key immediately on connection
                if (authKey != null) {
                    val authPayload = "{\"type\": \"auth\", \"key\": \"$authKey\"}"
                    webSocket.send(authPayload)
                    Log.d("WebSocketManager", "Sent Auth Key")
                }
                
                onConnectionOpened?.invoke()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                onMessageReceived?.invoke(text)
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                onBinaryReceived?.invoke(bytes.toByteArray())
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Log.d("WebSocketManager", "Closing: $code / $reason")
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e("WebSocketManager", "Error: ${t.message}")
                onConnectionFailed?.invoke(t.message ?: "Unknown error")
            }
        })
    }

    fun send(message: String) {
        webSocket?.send(message)
    }

    fun disconnect() {
        webSocket?.close(1000, "Goodbye")
    }
}
