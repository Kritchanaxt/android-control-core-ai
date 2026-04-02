package com.example.android_screen_relay.ocr

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build

import android.system.Os
import android.system.OsConstants
import android.os.SystemClock

object SystemMonitor {
    private var lastCpuTotal: Long = 0
    private var lastCpuIdle: Long = 0
    
    private var lastAppCpuTime: Long = 0
    private var lastAppUptime: Long = 0

    private fun getCpuUsage(): String {
        // Attempt 1: Try System-wide CPU using "top" command (Works on Android 8+)
        try {
            val process = Runtime.getRuntime().exec("top -n 1")
            val reader = java.io.BufferedReader(java.io.InputStreamReader(process.inputStream))
            var line = reader.readLine()
            var cpuParsed = ""
            
            while (line != null) {
                val l = line.lowercase(java.util.Locale.US)
                if (l.contains("user") && l.contains("sys")) {
                    // Typical output: "%cpu 12%user 1%sys 87%idle" or "User 12.5%, System 5.1%"
                    val userRegex = Regex("(\\d+\\.?\\d*)[%\\s]+user|user\\s+(\\d+\\.?\\d*)[%\\s]")
                    val sysRegex = Regex("(\\d+\\.?\\d*)[%\\s]+sys|system\\s+(\\d+\\.?\\d*)[%\\s]")
                    
                    val uMatch = userRegex.find(l)
                    val sMatch = sysRegex.find(l)
                    
                    if (uMatch != null && sMatch != null) {
                        val uStr = uMatch.groupValues[1].takeIf { it.isNotEmpty() } ?: uMatch.groupValues[2]
                        val sStr = sMatch.groupValues[1].takeIf { it.isNotEmpty() } ?: sMatch.groupValues[2]
                        val uVal = uStr.toFloatOrNull() ?: 0f
                        val sVal = sStr.toFloatOrNull() ?: 0f
                        cpuParsed = String.format("%.1f%%", uVal + sVal)
                        break
                    } else if (l.contains("idle")) {
                         // Parse idle instead
                         val idleRegex = Regex("(\\d+\\.?\\d*)[%\\s]+idle")
                         val iMatch = idleRegex.find(l)
                         if (iMatch != null) {
                             val idleStr = iMatch.groupValues[1]
                             val idleVal = idleStr.toFloatOrNull() ?: 100f
                             // If it's overall CPU percentage over 100%, we normalize it by getting cores count or just assume 100% is max.
                             cpuParsed = String.format("%.1f%%", 100f - idleVal.coerceAtMost(100f))
                             break
                         }
                    }
                    
                    // Fallback to raw line excerpt if regex parsing didn't catch expected forms
                    cpuParsed = line.trim().take(15)
                    break
                }
                line = reader.readLine()
            }
            process.destroy()
            if (cpuParsed.isNotEmpty()) {
                return cpuParsed
            }
        } catch(e: Exception) {
            // Ignored
        }

        // Attempt 2: Try System-wide CPU from /proc/stat (Works on older Android or rooted)
        try {
            val reader = java.io.BufferedReader(java.io.FileReader("/proc/stat"))
            val line = reader.readLine()
            reader.close()
            
            val toks = line.split("\\s+".toRegex()).filter { it.isNotEmpty() }
            val idle = toks[4].toLong()
            val total = toks.drop(1).map { it.toLong() }.sum()
            
            if (lastCpuTotal == 0L) {
                lastCpuTotal = total
                lastCpuIdle = idle
                return "0.0%"
            }
            
            val totalDiff = total - lastCpuTotal
            val idleDiff = idle - lastCpuIdle
            
            lastCpuTotal = total
            lastCpuIdle = idle
            
            if (totalDiff == 0L) return "0.0%"
            
            val cpuUsage = ((totalDiff - idleDiff) * 100.0) / totalDiff
            return String.format("%.1f%%", cpuUsage)
        } catch (e: Exception) {
            // Attempt 2: Fallback to App Process CPU (Works on most devices)
            return getAppCpuUsage()
        }
    }

    private fun getAppCpuUsage(): String {
        try {
            val reader = java.io.BufferedReader(java.io.FileReader("/proc/self/stat"))
            val line = reader.readLine()
            reader.close()

            val stats = line.split("\\s+".toRegex())
            // Field 13 (utime) and 14 (stime) are usually user and system time for the process
            val utime = stats[13].toLong()
            val stime = stats[14].toLong()
            val cpuTime = utime + stime
            
            val uptime = SystemClock.uptimeMillis()

            if (lastAppUptime == 0L) {
                lastAppCpuTime = cpuTime
                lastAppUptime = uptime
                return "0.0% (App)"
            }

            val timeDelta = uptime - lastAppUptime
            val cpuDelta = cpuTime - lastAppCpuTime

            lastAppCpuTime = cpuTime
            lastAppUptime = uptime

            if (timeDelta == 0L) return "0.0% (App)"

            // Get Clock Ticks per Second (usually 100 on Android)
            val clkIk = Os.sysconf(OsConstants._SC_CLK_TCK)
            
            // Calculate usage: (cpu_ticks / clock_ticks_per_sec) / (time_ms / 1000)
            val cpuSeconds = cpuDelta.toDouble() / clkIk.toDouble()
            val timeSeconds = timeDelta.toDouble() / 1000.0
            
            val usage = (cpuSeconds / timeSeconds) * 100.0
            val cores = Runtime.getRuntime().availableProcessors()
            
            // Usage relative to one core, divide by cores for total system impact
            val totalUsage = usage / cores 
            
            return String.format("%.1f%% (App)", totalUsage)
        } catch (e: Exception) {
            return "N/A"
        }
    }

    fun getDeviceInfo(context: Context): DeviceInfo {
        return DeviceInfo(
            model = Build.MODEL,
            manufacturer = Build.MANUFACTURER,
            androidVersion = Build.VERSION.RELEASE,
            apiLevel = Build.VERSION.SDK_INT
        )
    }

    fun getCurrentResourceUsage(context: Context): ResourceUsage {
        val actManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        actManager.getMemoryInfo(memInfo)
        
        val ramUsed = (memInfo.totalMem - memInfo.availMem) / (1024 * 1024)
        val ramTotal = memInfo.totalMem / (1024 * 1024)
        
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val batteryPct = if (level != -1 && scale != -1) (level * 100 / scale) else 0
        val temp = intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0
        
        return ResourceUsage(
            cpuUsage = getCpuUsage(), 
            ramUsedMb = ramUsed,
            ramTotalMb = ramTotal,
            batteryLevel = batteryPct,
            batteryTemp = temp / 10.0f
        )
    }
}