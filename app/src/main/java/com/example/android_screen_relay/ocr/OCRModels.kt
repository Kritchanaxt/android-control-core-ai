package com.example.android_screen_relay.ocr

import org.json.JSONObject

data class DeviceInfo(
    val model: String,
    val manufacturer: String,
    val androidVersion: String,
    val apiLevel: Int
) {
    fun toJson(): JSONObject {
        return JSONObject().apply {
            put("model", model)
            put("manufacturer", manufacturer)
            put("android_version", androidVersion)
            put("api_level", apiLevel)
        }
    }
}

data class ResourceUsage(
    val cpuUsage: String,
    val ramUsedMb: Long,
    val ramTotalMb: Long,
    val batteryLevel: Int,
    val batteryTemp: Float
) {
    fun toJson(): JSONObject {
        return JSONObject().apply {
            put("cpu_usage", cpuUsage)
            put("ram_used_mb", ramUsedMb)
            put("ram_total_mb", ramTotalMb)
            put("battery_level", batteryLevel)
            put("battery_temp_c", batteryTemp)
        }
    }
}