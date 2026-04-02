package com.example.android_screen_relay

import android.app.Application
import android.util.Log
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

class RelayApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        
        // 1. Initialize App State Manager (Requirement: App State Awareness)
        AppStateManager.init(this)

        // 2. Setup Watchdog for Self-healing (Requirement: Auto Restart)
        setupWatchdog()
        
        // 3. Global Exception Logger to Google Sheets
        setupExceptionHandler()
    }

    private fun setupExceptionHandler() {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                // Collect Device Info
                val statusMap = mutableMapOf<String, Any>(
                    "device_model" to android.os.Build.MODEL,
                    "device_manufacturer" to android.os.Build.MANUFACTURER,
                    "android_version" to android.os.Build.VERSION.RELEASE,
                    "api_level" to android.os.Build.VERSION.SDK_INT,
                    "cpu_abi" to android.os.Build.SUPPORTED_ABIS[0],
                    "fatal_error" to "UNCAUGHT_EXCEPTION"
                )
                
                // Get stack trace safely
                val stackTrace = Log.getStackTraceString(throwable)
                statusMap["crash_log"] = stackTrace
                
                // Manually check ram/battery real quick just for context to be sent with crash report
                try {
                    val powerManager = getSystemService(android.content.Context.POWER_SERVICE) as? android.os.PowerManager
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                        statusMap["thermal_status"] = powerManager?.currentThermalStatus ?: -1
                    }
                } catch (e: Exception) {}

                val jsonPayload = org.json.JSONObject()
                jsonPayload.put("type", "heartbeat")
                jsonPayload.put("data", org.json.JSONObject(statusMap))
                
                // Log synchronously so it sends before killing the process
                Log.e("RelayApplication", "FATAL CRASH! Attempting to log to Google Sheets...", throwable)
                GoogleSheetsLogger.logSync(jsonPayload.toString())
                
            } catch (loggingException: Exception) {
                Log.e("RelayApplication", "Failed to log crash", loggingException)
            } finally {
                // Let the app crash normally
                defaultHandler?.uncaughtException(thread, throwable)
            }
        }
    }

    private fun setupWatchdog() {
        val constraints = Constraints.Builder()
            .build()

        // Minimum periodic interval is 15 minutes
        val watchdogRequest = PeriodicWorkRequestBuilder<WatchdogWorker>(15, TimeUnit.MINUTES)
            .setConstraints(constraints)
            .build()

        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "RelayServiceWatchdog",
            ExistingPeriodicWorkPolicy.KEEP,
            watchdogRequest
        )
        Log.d("RelayApplication", "Watchdog service scheduled.")
    }
}
