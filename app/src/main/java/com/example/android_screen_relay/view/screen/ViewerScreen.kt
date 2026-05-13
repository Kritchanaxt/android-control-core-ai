package com.example.android_screen_relay

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import org.json.JSONObject

import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.toSize
import androidx.compose.ui.geometry.Offset

@Composable
fun ViewerScreen(hostIp: String, passkey: String) {
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    var status by remember { mutableStateOf("Connecting to $hostIp...") }
    
    // WebSocket Manager Reference to send commands
    var wsManagerRef by remember { mutableStateOf<WebSocketManager?>(null) }
    
    // Layout info for touch scaling
    var imageSize by remember { mutableStateOf(androidx.compose.ui.geometry.Size.Zero) }

    DisposableEffect(hostIp) {
        val wsManager = WebSocketManager(
            url = "ws://$hostIp:8887",
            authKey = passkey,
            onMessageReceived = { message ->
                try {
                    val json = JSONObject(message)
                    val type = json.optString("type")
                    
                    if (type == "auth_response") {
                         val authStatus = json.optString("status")
                         if (authStatus == "ok") {
                             status = "Connected"
                         } else {
                             status = "Authentication Failed!"
                         }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            },
            onBinaryReceived = { bytes ->
                // Decode raw JPEG bytes directly
                try {
                    val decodedBitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    bitmap = decodedBitmap
                    if (status != "Connected") status = "Connected"
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            },
            onConnectionOpened = {
                status = "Verifying Passkey..."
            },
            onConnectionFailed = { error ->
                status = "Socket Error: $error"
            }
        )
        wsManagerRef = wsManager
        wsManager.connect()
        
        onDispose {
            wsManager.disconnect()
        }
    }
    
    // LaunchedEffect removed as Auth is handled internally by WebSocketManager now.

    Box(modifier = Modifier.fillMaxSize()) {
        if (bitmap != null) {
            val bmp = bitmap!!
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = "Screen Stream",
                modifier = Modifier
                    .fillMaxSize()
                    .onGloballyPositioned { coordinates ->
                        imageSize = coordinates.size.toSize()
                    }
                    .pointerInput(Unit) {
                        detectTapGestures { offset ->
                            // Calculate scaling relative to the actual image size vs displayed size
                            // This depends on ContentScale.Fit logic. For simplicity, we assume Fill or basic math.
                            
                            // Simple relative Mapping (Assuming full screen coverage or adjusting for aspect ratio later)
                            // Ideally, we need the source resolution from the host safely.
                            // For now, we send relative coordinates (0..1) or raw display coordinates if Host can handle it.
                            // Best practice: Host expects raw pixels. Client sends normalized (0.0 - 1.0) and Host scales it up.
                            
                            val normX = offset.x / imageSize.width
                            val normY = offset.y / imageSize.height
                            
                            // Send to Host. Host must interpret relative vs absolute. 
                            // Our Host expects Absolute pixels currently.
                            // To fix this properly, let's assume a standard 1080x1920 host for a moment OR
                            // update Host to accept normalized coordinates (Better).
                            
                            // Temporary: Send generic normalized, Host side needs to scale.
                            // But wait, our `RelayServer` expects just X, Y float.
                            // Let's modify logic to send "Percent" and let Host multiply by screen width/height.
                            
                            val cmd = JSONObject()
                            cmd.put("type", "click")
                            cmd.put("x_percent", normX)
                            cmd.put("y_percent", normY)
                            
                            wsManagerRef?.send(cmd.toString())
                        }
                    },
                contentScale = ContentScale.FillBounds // Changed to FillBounds to make coordinate mapping easier for this MVP
            )
        } else {
            Text(text = status, modifier = Modifier.align(Alignment.Center))
        }
    }
}
