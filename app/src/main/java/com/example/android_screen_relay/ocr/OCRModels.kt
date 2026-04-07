package com.example.android_screen_relay.ocr

import org.json.JSONObject

data class DeviceInfo(
    val model: String,
    val manufacturer: String,
    val androidVersion: String,
    val apiLevel: Int,
    // Spaces / Specs Additions
    val totalRomGb: Double = 0.0,
    val batteryCapacityMAh: Int = 0,
    val backCameraMp: Float = 0f,
    val frontCameraMp: Float = 0f
) {
    fun toJson(): JSONObject {
        return JSONObject().apply {
            put("model", model)
            put("manufacturer", manufacturer)
            put("android_version", androidVersion)
            put("api_level", apiLevel)
            put("total_rom_gb", totalRomGb)
            put("battery_capacity_mah", batteryCapacityMAh)
            put("back_camera_mp", backCameraMp)
            put("front_camera_mp", frontCameraMp)
        }
    }
}

data class ResourceUsage(
    val cpuUsage: String,
    val ramUsedMb: Long,
    val ramTotalMb: Long,
    val ramFreeMb: Long,
    val batteryLevel: Int,
    val batteryTemp: Float
) {
    fun toJson(): JSONObject {
        return JSONObject().apply {
            put("cpu_usage", cpuUsage)
            put("ram_used_mb", ramUsedMb)
            put("ram_free_mb", ramFreeMb)
            put("ram_total_mb", ramTotalMb)
            put("battery_level", batteryLevel)
            put("battery_temp_c", batteryTemp)
        }
    }
}