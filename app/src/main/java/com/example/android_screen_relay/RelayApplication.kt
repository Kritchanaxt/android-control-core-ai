package com.example.android_screen_relay

import android.app.AlarmManager
import android.app.Application
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.Toast
import android.os.Handler
import android.os.Looper
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.example.android_screen_relay.core.FirebaseLogger
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

class RelayApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        
        // บันทึก Log เมื่อแอปเริ่มทำงาน (App Start) พร้อมสถานะเครื่อง
        FirebaseLogger.logStep(this, "APP_START", "SUCCESS")
        
        // 1. Initialize App State Manager (Requirement: App State Awareness)
        AppStateManager.init(this)

        // 2. Setup Watchdog for Self-healing (Requirement: Auto Restart)
        setupWatchdog()
        
        // 3. Global Exception Logger to Google Sheets & Firebase
        setupExceptionHandler()
    }

    /**
     * ดักจับการเตือนเรื่อง Memory จาก OS
     * (สำคัญมากสำหรับเครื่อง RAM 2GB เพราะถ้าระบบค้างหรือเด้ง มักเกิดจากการโดน OS ฆ่าทิ้ง ไม่ใช่ Crash ปกติ)
     */
    override fun onLowMemory() {
        super.onLowMemory()
        FirebaseLogger.logStep(
            this,
            "OS_LOW_MEMORY_CRITICAL",
            "WARNING",
            extraData = mapOf("action" to "OS แจ้งว่า RAM เครื่องเหลือน้อยมาก (เสี่ยงต่อแอปเด้ง)")
        )
        
        // Threshold Warning: เตือนผู้ใช้งานบนหน้าจอทันทีเมื่อ RAM < 2GB แล้วแอปรับมือไม่ไหว
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(this, "⚠️ ระบบเตือน: RAM ใกล้หมด ระบบกำลังป้องกันตัวเองไม่ให้เด้ง", Toast.LENGTH_LONG).show()
        }
        
        // สั่งเคลียร์แคชภาพและหน่วยความจำที่ไม่จำเป็นทิ้ง เพื่อป้องกันแอปเด้ง
        System.gc()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // ระดับเกิน 15 (TRIM_MEMORY_RUNNING_CRITICAL) ถือว่าเริ่มอันตราย
        if (level >= 15) {
            FirebaseLogger.logStep(
                this,
                "OS_TRIM_MEMORY",
                "WARNING",
                extraData = mapOf(
                    "trim_level" to level,
                    "action" to "OS สั่งให้แอปคืนสภาพ RAM ชั่วคราว (Level: $level)"
                )
            )
        }
    }

    private fun setupExceptionHandler() {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                // ส่ง Crash Log ไปที่ Firebase ก่อนแอปปิดตัวลง พร้อมสถานะสุดท้ายเสมอ
                FirebaseLogger.logStep(
                    this, 
                    "APP_CRASH", 
                    "FATAL",
                    error = throwable,
                    extraData = mapOf(
                        "fatal_error" to true,
                        "error_msg" to (throwable.message ?: "Unknown"),
                        "stack_trace" to Log.getStackTraceString(throwable).take(2000),
                        "thread_name" to thread.name
                    )
                )

                // ถ่วงเวลา 200ms ให้ตัว Firestore SDK เอาข้อมูลฝังลงฐานข้อมูลออฟไลน์ในเครื่องก่อนถูกเด้ง
                Thread.sleep(200)

                // เก็บลง Google Sheets เดิมด้วย (ถ้ามี)
                // ...existing logic...
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
                // Auto-Restart: สั่งให้แอปพยายามฟื้นคืนชีพกลับมาเอง ภายใน 1.5 วินาที
                try {
                    val intent = Intent(this@RelayApplication, MainActivity::class.java)
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                    
                    // ใช้ FLAG_IMMUTABLE เพื่อให้รองรับ Android 12 ขึ้นไป
                    val pendingIntent = PendingIntent.getActivity(
                        this@RelayApplication, 0, intent, 
                        PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
                    )
                    
                    val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
                    alarmManager.set(AlarmManager.RTC, System.currentTimeMillis() + 1500, pendingIntent)
                    
                    Log.d("RelayApplication", "Auto-Restart scheduled in 1.5 seconds.")
                    
                    // ฆ่า Process เก่าทิ้งให้สะอาดก่อน เพื่อเตรียมขึ้น Process ใหม่
                    android.os.Process.killProcess(android.os.Process.myPid())
                    exitProcess(1)

                } catch (e: Exception) {
                    // หาก Restart ล้มเหลว ปล่อยให้มัน Crash ไปตามธรรมชาติของ OS
                    defaultHandler?.uncaughtException(thread, throwable)
                }
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
