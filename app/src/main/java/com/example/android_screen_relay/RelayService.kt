package com.example.android_screen_relay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import android.content.pm.ServiceInfo
import kotlinx.coroutines.*
import kotlin.random.Random

class RelayService : Service() {

    private var relayServer: RelayServer? = null
    // private lateinit var webSocketManager: WebSocketManager // Removed
    private lateinit var overlayManager: OverlayManager
    private lateinit var screenCaptureManager: ScreenCaptureManager
    private val CHANNEL_ID = "RelayServiceChannel"
    
    private var discoveryJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        
        // Generate Passkey
        val passkey = String.format("%06d", Random.nextInt(0, 999999))
        currentPasskey = passkey
        
        // Start UDP Discovery Listener
        discoveryJob = scope.launch {
            NetworkDiscovery.startHostListeners(this@RelayService, passkey, 8887) {
                isActive // check if scope is active
            }
        }
        
        // Start Local Server
        try {
            android.util.Log.d("RelayService", "Initializing RelayServer on port 8887...")
            relayServer = RelayServer(8887)
            relayServer?.isReuseAddr = true // Force reuse address at service level too just in case
            relayServer?.updatePasskey(passkey) // Set the passkey
            
            // Handle Notification Requests from Server
            relayServer?.onShowNotification = { title, msg ->
                showClientNotification(title, msg)
            }
            
            relayServer?.start()
            android.util.Log.d("RelayService", "RelayServer started successfully on port 8887")
            // Start heartbeat for testing background connectivity
            startHeartbeat()
        } catch (e: Exception) {
            android.util.Log.e("RelayService", "Failed to start RelayServer: ${e.message}")
            e.printStackTrace()
        }

        overlayManager = OverlayManager(this)
        screenCaptureManager = ScreenCaptureManager(this)
        
        // Expose instance for simple singleton access (not ideal for strict architectures, but fine here)
        sInstance = this
    }

    override fun onDestroy() {
        super.onDestroy()
        sInstance = null
        try {
            android.util.Log.d("RelayService", "Stopping RelayServer...")
            relayServer?.stop(2000) // Wait up to 2 seconds for clean shutdown
            android.util.Log.d("RelayService", "RelayServer stopped.")
        } catch (e: Exception) {
            e.printStackTrace()
        }
        discoveryJob?.cancel()
        screenCaptureManager.stopCapture()
        // overlayManager.hideOverlay() // Assuming this method exists or you might need to check OverlayManager content
        overlayManager.removeOverlay()
    }

    // Public method to broadcast message to all connected clients
    fun broadcastMessage(message: String) {
        relayServer?.broadcast(message)
    }

    companion object {
        const val ACTION_STOP = "com.example.android_screen_relay.STOP"
        var currentPasskey: String? = null
            private set
            
        private var sInstance: RelayService? = null
        fun getInstance(): RelayService? {
            return sInstance
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        val notification = createNotification()

        // IMPORTANT: Start Foreground Service BEFORE creating the virtual display (Android 14+ requirement)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(1, notification)
        }
        
        // Show Overlay
        overlayManager.showOverlay()

        // Start Screen Capture if data is present
        if (intent != null) {
            val resultCode = intent.getIntExtra("RESULT_CODE", 0)
            val dataIntent = intent.getParcelableExtra<Intent>("DATA_INTENT")
            
            if (resultCode != 0 && dataIntent != null) {
                try {
                    val quality = intent.getIntExtra("QUALITY_MODE", 1) // Default to Medium (1)
                    android.util.Log.d("RelayService", "Starting Screen Capture with Quality: $quality")
                    /* 
                    // TEMPORARILY DISABLED SCREEN CAPTURE TO TEST COMMAND STABILITY
                    screenCaptureManager.startCapture(resultCode, dataIntent, quality) { imageBytes ->
                        // Pass raw bytes to authenticated clients
                        relayServer?.broadcastToAuthenticated(imageBytes)
                    }
                    */
                } catch (e: Exception) {
                    android.util.Log.e("RelayService", "Error starting capture: ${e.message}")
                    e.printStackTrace()
                }
            }
        }

        // Using START_STICKY so if the service is killed, it restarts (without the intent data, so no screen capture, but server is alive)
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    private fun startHeartbeat() {
        var lastStatusCheck: Map<String, Any>? = null
        
        scope.launch {
            LogRepository.addLog(
                component = "RelayService",
                event = "heartbeat_started",
                data = emptyMap(),
                type = LogRepository.LogType.INFO
            )
            try {
                while (isActive) {
                    delay(1000)
                    try {
                        val statusMap = mapOf<String, Any>(
                            "uptime_sec" to (System.currentTimeMillis() - 0L) / 1000, 
                            "foreground_service" to true,
                            "screen_capture_active" to (screenCaptureManager != null),
                            "is_background" to true
                        )
                        
                        val statusJson = org.json.JSONObject()
                        statusJson.put("type", "heartbeat")
                        statusJson.put("data", org.json.JSONObject(statusMap)) 
                        statusMap.forEach { (k, v) -> statusJson.put(k, v) }
                        
                        relayServer?.broadcastToAuthenticated(statusJson.toString())
                        
                        // Check if status changed (ignoring uptime)
                        val statusCheck = statusMap.minus("uptime_sec")
                        if (lastStatusCheck != statusCheck) {
                            LogRepository.addLog(
                                component = "RelayService",
                                event = "heartbeat",
                                data = statusMap,
                                type = LogRepository.LogType.OUTGOING
                            )
                            lastStatusCheck = statusCheck
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            } finally {
                LogRepository.addLog(
                    component = "RelayService",
                    event = "heartbeat_stopped",
                    data = emptyMap(),
                    type = LogRepository.LogType.INFO
                )
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "Relay Service Channel",
                NotificationManager.IMPORTANCE_DEFAULT
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(serviceChannel)
        }
    }

    private fun createNotification(): Notification {
        val stopIntent = Intent(this, RelayService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 0, stopIntent, PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Screen Relay Active")
            .setContentText("Listening for commands...")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopPendingIntent)
            .build()
    }

    private fun showClientNotification(title: String, message: String) {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_email)
            .setContentTitle(title)
            .setContentText(message)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()
        
        notificationManager.notify(System.currentTimeMillis().toInt(), notification)
    }
}
