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
