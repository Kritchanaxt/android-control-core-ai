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

    fun startMonitoring(context: Context) {
        if (job?.isActive == true) return

        val appContext = context.applicationContext
        job = scope.launch {
            while (isActive) {
                try {
                    val usage = com.example.android_screen_relay.core.SystemMonitor.getCurrentResourceUsage(appContext)
                    evaluateResourcesAndAct(usage)
                } catch (e: Exception) {
                    Log.e(TAG, "Error in watchdog loop", e)
                }
                delay(3000L) // Check every 3 seconds
            }
        }
        Log.d(TAG, "Resource Watchdog Started")
    }

    fun stopMonitoring() {
        job?.cancel()
        job = null
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
            applyThrottling(newStatus)
        }
    }

    private fun applyThrottling(status: ResourceStatus) {
        // Here we link to AiStateManager to dynamically adjust the app behavior
        when (status) {
            ResourceStatus.CRITICAL -> {
                // Emergency: Reduce to minimum viable configuration
                AiStateManager.updateState { state ->
                    state.copy(
                        computeMode = ComputeMode.CPU_4_CORE,
                        useCropMode = true,
                        maxFps = 10, // Throttled frame rate
                        isThrottled = true
                    )
                }
                Log.e(TAG, "CRITICAL: Forced Throttling applied (Dropped to CPU_4_CORE, Forced Crop)")
            }
            ResourceStatus.WARNING -> {
                // Pre-emptive: Switch off heaviest modes
                val currentMode = AiStateManager.state.value.computeMode
                if (currentMode.name.contains("GPU") || currentMode.name.contains("NNAPI")) {
                    AiStateManager.updateState { it.copy(computeMode = ComputeMode.CPU_6_CORE, maxFps = 15, isThrottled = true) }
                    Log.w(TAG, "WARNING: Reduced compute mode to CPU_6_CORE to save power/RAM")
                }
            }
            ResourceStatus.NORMAL -> {
                // Restore max capabilities when system recovers
                    val currentState = AiStateManager.state.value
                    if (currentState.isThrottled) {
                        AiStateManager.updateState { it.copy(isThrottled = false, maxFps = 30) }
                        Log.i(TAG, "NORMAL: Resources recovered, removing throttle restrictions.")
                    }
            }
        }
    }
}
