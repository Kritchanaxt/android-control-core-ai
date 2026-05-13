package com.example.android_screen_relay

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.work.Worker
import androidx.work.WorkerParameters

class WatchdogWorker(
    private val context: Context,
    workerParams: WorkerParameters
) : Worker(context, workerParams) {

    companion object {
        private const val TAG = "WatchdogWorker"
    }

    override fun doWork(): Result {
        Log.d(TAG, "Watchdog running... checking if RelayService is alive")
        
        if (!isServiceRunning(context, RelayService::class.java)) {
            Log.w(TAG, "RelayService is NOT running. Attempting to self-heal (restart)...")
            
            try {
                val serviceIntent = Intent(context, RelayService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent)
                } else {
                    context.startService(serviceIntent)
                }
                Log.d(TAG, "RelayService restart triggered by Watchdog.")
            } catch (e: Exception) {
                Log.e(TAG, "Watchdog failed to restart RelayService", e)
                return Result.retry()
            }
        } else {
            Log.d(TAG, "RelayService is already running. All good.")
        }
        
        return Result.success()
    }

    @Suppress("DEPRECATION")
    private fun isServiceRunning(context: Context, serviceClass: Class<*>): Boolean {
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        for (service in manager.getRunningServices(Integer.MAX_VALUE)) {
            if (serviceClass.name == service.service.className) {
                return true
            }
        }
        return false
    }
}
