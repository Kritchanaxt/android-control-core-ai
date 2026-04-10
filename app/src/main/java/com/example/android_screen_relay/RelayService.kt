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
import com.example.android_screen_relay.core.SystemMonitor
import com.example.android_screen_relay.core.PaddleOCR
import com.example.android_screen_relay.core.AIManager
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

            // Handle Connection Requests
            relayServer?.onConnectionRequest = { requestId, ip ->
                showConnectionRequestNotification(requestId, ip)
            }
            
            relayServer?.start()
            android.util.Log.d("RelayService", "RelayServer started successfully on port 8887")
            // Start heartbeat for testing background connectivity
            startHeartbeat()
            
            // Listen to App State Changes and broadcast to clients
            AppStateManager.setOnStateChangeListener { newState ->
                val stateJson = org.json.JSONObject().apply {
                    put("type", "state_update")
                    put("state", newState.name)
                }
                relayServer?.broadcastToAuthenticated(stateJson.toString())
            }
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
        
        // P'Bear: Automatic Release (Release Native/JNI memory)
        try {
            PaddleOCR().release() // Force release native engines
            android.util.Log.d("RelayService", "Native PaddleOCR resources released.")
        } catch (e: Exception) {
            android.util.Log.e("RelayService", "Release failed: ${e.message}")
        }

        discoveryJob?.cancel()
        screenCaptureManager.stopCapture()
        // overlayManager.hideOverlay() // Assuming this method exists or you might need to check OverlayManager content
        overlayManager.removeOverlay()
        
        // Final cleanup GC hint
        System.gc()
    }

    // Public method to broadcast message to all connected clients
    fun broadcastMessage(message: String) {
        relayServer?.broadcastToAuthenticated(message)
    }

    fun approveConnection(requestId: String) {
        relayServer?.approveConnection(requestId)
    }

    fun denyConnection(requestId: String) {
        relayServer?.denyConnection(requestId)
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
        var consecutiveLowMemory = 0
        var loopCount = 0
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
                    delay(5000)
                    loopCount++
                    try {
                        val usage = SystemMonitor.getCurrentResourceUsage(this@RelayService)
                        val statusMap = mutableMapOf<String, Any>(
                            "uptime_sec" to (System.currentTimeMillis() - 0L) / 1000, 
                            "foreground_service" to true,
                            "screen_capture_active" to (screenCaptureManager != null),
                            "device_model" to Build.MODEL,
                            "device_manufacturer" to Build.MANUFACTURER,
                            "android_version" to Build.VERSION.RELEASE,
                            "api_level" to Build.VERSION.SDK_INT,
                            "cpu_abi" to Build.SUPPORTED_ABIS[0]
                        )

                        val availableRam = usage.ramFreeMb
                        val ocrModeLabel = com.example.android_screen_relay.core.ComputeModeManager.getMode().displayName
                        
                        val deviceInfo = SystemMonitor.getDeviceInfo(this@RelayService)

                        statusMap["cpu_usage"] = usage.cpuUsage
                        statusMap["ram_used_mb"] = usage.ramUsedMb
                        statusMap["ram_free_mb"] = usage.ramFreeMb
                        statusMap["ram_total_mb"] = usage.ramTotalMb
                        statusMap["battery_level"] = usage.batteryLevel
                        statusMap["battery_temp"] = usage.batteryTemp
                        statusMap["ocr_mode"] = ocrModeLabel

                        // Hardware Specs
                        statusMap["total_rom_gb"] = deviceInfo.totalRomGb
                        statusMap["battery_capacity_mah"] = deviceInfo.batteryCapacityMAh
                        statusMap["back_camera_label"] = deviceInfo.backCameraLabel
                        statusMap["front_camera_label"] = deviceInfo.frontCameraLabel
                        statusMap["has_front_camera"] = deviceInfo.hasFrontCamera
                        statusMap["has_back_camera"] = deviceInfo.hasBackCamera
                        statusMap["total_cameras"] = deviceInfo.totalCameras
                        statusMap["supported_resolutions"] = deviceInfo.supportedResolutions
                        statusMap["camera_permission"] = deviceInfo.cameraPermissionGranted
                        statusMap["os_name"] = deviceInfo.osName
                        
                        // Add AI monitoring info
                        val activeAI = AIManager.getActiveProcessor()
                        statusMap["ai_active"] = activeAI?.name ?: "none"
                        statusMap["ai_memory_state"] = "ok"
                        statusMap["paddle_ocr_loaded"] = AIManager.paddleOCRLoaded() // Need to implement this function or check
                        statusMap["status"] = "Active"

                        val powerManager = getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
                        statusMap["power_save_mode"] = powerManager?.isPowerSaveMode == true
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            statusMap["thermal_status"] = powerManager?.currentThermalStatus ?: 0
                        } else {
                            statusMap["thermal_status"] = -1
                        }

                        val totalRam = usage.ramTotalMb
                        
                        // % based thresholds. For a phone with 1742MB:
                        // warning: 209MB | critical: 139MB | fatal: 69MB
                        val warningThreshold = (totalRam * 0.12).toLong().coerceAtMost(400)
                        val criticalThreshold = (totalRam * 0.08).toLong().coerceAtMost(250)
                        val fatalThreshold = (totalRam * 0.04).toLong().coerceAtMost(120)

                        if (availableRam < warningThreshold) {
                            android.util.Log.w("RelayService", "LOW MEMORY: ${availableRam}MB available")
                            
                            // Only accumulate crash timer if memory is dangerously low (Critical) 
                            // Merely staying in the 'Warning' zone is normal for Android OS caching on 2GB devices.
                            if (availableRam < criticalThreshold) {
                                consecutiveLowMemory++
                                statusMap["ai_memory_state"] = "releasing_models"
                                AIManager.release()
                            } else {
                                consecutiveLowMemory = 0
                            }
                        } else {
                            consecutiveLowMemory = 0
                        }

                        // Crash ONLY if memory drops below fatal immediately, OR stays under critical for ~60 seconds (12 loops)
                        if (availableRam < fatalThreshold || consecutiveLowMemory > 12) {
                            val activeFeature = statusMap["ai_active"]?.toString() ?: "None"
                            android.util.Log.e("RelayService", "Emergency Stop: OOM Prevention during [$activeFeature]")
                            statusMap["fatal_error"] = "OOM_PREVENTION_${availableRam}MB_FEATURE_$activeFeature"
                            
                            val fatalJson = org.json.JSONObject()
                            fatalJson.put("type", "heartbeat")
                            statusMap.forEach { (k, v) -> fatalJson.put(k, v) }
                            GoogleSheetsLogger.log(fatalJson.toString())
                            
                            showClientNotification("Emergency Stop", "Low Memory (${availableRam}MB). App closed.")
                            stopSelf()
                        }
                        
                        val statusJson = org.json.JSONObject()
                        statusJson.put("type", "heartbeat")
                        statusMap.forEach { (k, v) -> statusJson.put(k, v) }
                        
                        relayServer?.broadcastToAuthenticated(statusJson.toString())
                        
                        if (loopCount % 5 == 0) {
                            GoogleSheetsLogger.log(statusJson.toString())
                        }
                        
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
            } catch (e: Exception) {
                e.printStackTrace()
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
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(message)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(Random.nextInt(), notification)
    }

    private fun showConnectionRequestNotification(requestId: String, ip: String) {
        val intent = Intent(this, ConnectionRequestActivity::class.java).apply {
            putExtra("REQUEST_ID", requestId)
            putExtra("CLIENT_IP", ip)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        }
        
        // Launch Activity immediately
        startActivity(intent)
        
        val pendingIntent = PendingIntent.getActivity(
            this, 
            requestId.hashCode(), 
            intent, 
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Still show a high priority notification as backup and for fullScreenIntent requirement
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Connection Request")
            .setContentText("Web Client ($ip) wants to connect.")
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setFullScreenIntent(pendingIntent, true) 
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(requestId.hashCode(), notification)
    }
}
