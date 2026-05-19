package com.example.android_screen_relay.presenter.system
import com.example.android_screen_relay.core.ResourceUsage
import com.example.android_screen_relay.core.ComputeMode
import android.content.Context
import android.util.Log
import com.example.android_screen_relay.presenter.AiStateManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow



enum class ResourceStatus {
    NORMAL,
    WARNING,  // Devices starting to get warm or RAM getting low
    CRITICAL  // Imminent OOM or thermal throttling
}

object ResourceWatchdog {
    private const val TAG = "ResourceWatchdog"
    private var job: Job? = null
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private val _status = MutableStateFlow(ResourceStatus.NORMAL)
    val status = _status.asStateFlow()

    @Volatile
    private var lastHeartbeat: Long = System.currentTimeMillis()
    
    private var recoveryCount = 0
    private const val MAX_RECOVERY_ATTEMPTS = 3
    private var appContext: Context? = null
    private var deadlockJob: Job? = null

    fun notifyHeartbeat() {
        lastHeartbeat = System.currentTimeMillis()
    }

    fun startMonitoring(context: Context) {
        if (job?.isActive == true) return

        appContext = context.applicationContext
        job = scope.launch {
            while (isActive) {
                try {
                    val usage = com.example.android_screen_relay.core.SystemMonitor.getCurrentResourceUsage(appContext!!)
                    evaluateResourcesAndAct(usage)
                } catch (e: Exception) {
                    Log.e(TAG, "Error in watchdog loop", e)
                }
                delay(3000L) // Check every 3 seconds
            }
        }

        // Start Deadlock Detection loop
        deadlockJob = scope.launch {
            while (isActive) {
                delay(5000L) // Check every 5 seconds
                checkDeadlock()
            }
        }
        
        Log.d(TAG, "Resource Watchdog Started")
    }

    private fun checkDeadlock() {
        val state = AiStateManager.state.value
        // Only check for deadlock if AI is supposed to be processing
        if (state.isProcessing || state.currentAiMode != com.example.android_screen_relay.core.AiMode.PREVIEW) {
            val now = System.currentTimeMillis()
            val timeSinceLastHeartbeat = now - lastHeartbeat
            
            if (timeSinceLastHeartbeat > 8000L) { // 8 seconds threshold for deadlock
                Log.e(TAG, "DEADLOCK DETECTED! No AI heartbeat for ${timeSinceLastHeartbeat}ms")
                performRecovery()
            }
        } else {
            // Update heartbeat when not processing to avoid false positives on start
            notifyHeartbeat()
        }
    }

    private fun performRecovery() {
        val context = appContext ?: return
        if (recoveryCount >= MAX_RECOVERY_ATTEMPTS) {
            Log.e(TAG, "Max recovery attempts reached. Stopping AI to prevent crash.")
            AiStateManager.updateState { it.copy(
                watchdogStatus = com.example.android_screen_relay.presenter.WatchdogStatus.CRITICAL,
                watchdogMessage = "AI System Failure: Recover failed multiple times. Please restart app.",
                isProcessing = false
            ) }
            return
        }

        recoveryCount++
        Log.w(TAG, "Attempting Auto-Recovery ($recoveryCount/$MAX_RECOVERY_ATTEMPTS)...")
        
        AiStateManager.updateState { it.copy(
            watchdogStatus = com.example.android_screen_relay.presenter.WatchdogStatus.WARNING,
            watchdogMessage = "AI Hang detected. Recovering... ($recoveryCount)"
        ) }

        scope.launch {
            try {
                val currentMode = AiStateManager.state.value.currentAiMode
                // 1. Release
                com.example.android_screen_relay.core.AIManager.release()
                delay(1000L)
                
                // 2. Re-init
                Log.i(TAG, "Re-initializing ${currentMode.name}")
                com.example.android_screen_relay.core.AIManager.switchProcessor(context, currentMode)
                
                notifyHeartbeat()
                
                AiStateManager.updateState { it.copy(
                    watchdogStatus = com.example.android_screen_relay.presenter.WatchdogStatus.NORMAL,
                    watchdogMessage = "System Recovered Successfully."
                ) }
                
                // Reset message after a while
                delay(3000L)
                AiStateManager.updateState { it.copy(watchdogMessage = null) }
                
            } catch (e: Exception) {
                Log.e(TAG, "Recovery failed", e)
            }
        }
    }

    fun stopMonitoring() {
        job?.cancel()
        deadlockJob?.cancel()
        job = null
        deadlockJob = null
        Log.d(TAG, "Resource Watchdog Stopped")
    }

    private fun evaluateResourcesAndAct(usage: com.example.android_screen_relay.core.ResourceUsage) {
        val freeRam = usage.ramFreeMb
        val batteryTemp = usage.batteryTemp

        val newStatus = when {
            freeRam < 400 || batteryTemp > 42.0 -> ResourceStatus.CRITICAL
            freeRam < 800 || batteryTemp > 38.0 -> ResourceStatus.WARNING
            else -> ResourceStatus.NORMAL
        }

        if (newStatus != _status.value) {
            _status.value = newStatus
            Log.w(TAG, "Resource Status Changed: $newStatus (Free RAM: ${freeRam}MB, Temp: $batteryTemp C)")
            applyThrottling(newStatus, freeRam)
        }
    }

    private fun applyThrottling(status: ResourceStatus, freeRam: Long) {
        // Here we link to AiStateManager to dynamically adjust the app behavior
        when (status) {
            ResourceStatus.CRITICAL -> {
                // Emergency: Reduce to minimum viable configuration
                AiStateManager.updateState { state ->
                    state.copy(
                        computeMode = com.example.android_screen_relay.core.ComputeMode.CPU_4_CORE,
                        useCropMode = true,
                        maxFps = 10, // Throttled frame rate
                        isThrottled = true,
                        watchdogStatus = com.example.android_screen_relay.presenter.WatchdogStatus.CRITICAL,
                        watchdogMessage = "Emergency: Low RAM (${freeRam}MB). Throttling applied."
                    )
                }
                Log.e(TAG, "CRITICAL: Forced Throttling applied (Dropped to CPU_4_CORE, Forced Crop)")
            }
            ResourceStatus.WARNING -> {
                // Pre-emptive: Switch off heaviest modes
                val currentMode = AiStateManager.state.value.computeMode
                AiStateManager.updateState { state ->
                    state.copy(
                        isThrottled = true,
                        maxFps = 15,
                        watchdogStatus = com.example.android_screen_relay.presenter.WatchdogStatus.WARNING,
                        watchdogMessage = "System warming up. Performance reduced to save power."
                    )
                }
                if (currentMode.name.contains("GPU") || currentMode.name.contains("NNAPI")) {
                    AiStateManager.updateState { it.copy(computeMode = com.example.android_screen_relay.core.ComputeMode.CPU_6_CORE) }
                    Log.w(TAG, "WARNING: Reduced compute mode to CPU_6_CORE to save power/RAM")
                }
            }
            ResourceStatus.NORMAL -> {
                // Restore max capabilities when system recovers
                    val currentState = AiStateManager.state.value
                    if (currentState.isThrottled) {
                        AiStateManager.updateState { it.copy(
                            isThrottled = false, 
                            maxFps = 30,
                            watchdogStatus = com.example.android_screen_relay.presenter.WatchdogStatus.NORMAL,
                            watchdogMessage = "System healthy. Performance restored."
                        ) }
                        Log.i(TAG, "NORMAL: Resources recovered, removing throttle restrictions.")
                        scope.launch {
                            delay(3000L)
                            AiStateManager.updateState { it.copy(watchdogMessage = null) }
                        }
                    }
            }
        }
    }
}
