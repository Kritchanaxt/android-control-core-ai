package com.example.android_screen_relay.core

import org.json.JSONObject

data class DeviceInfo(
    val model: String,
    val manufacturer: String,
    val androidVersion: String,
    val apiLevel: Int,
    // Spaces / Specs Additions
    val totalRomGb: Double = 0.0,
    val batteryCapacityMAh: Int = 0,
    val backCameraLabel: String = "",
    val frontCameraLabel: String = "",
    val hasFrontCamera: Boolean = false,
    val hasBackCamera: Boolean = false,
    val totalCameras: Int = 0,
    val supportedResolutions: String = "",
    val cameraPermissionGranted: Boolean = false,
    val osName: String = "Android"
) {
    fun toJson(): JSONObject {
        return JSONObject().apply {
            put("model", model)
            put("manufacturer", manufacturer)
            put("android_version", androidVersion)
            put("api_level", apiLevel)
            put("total_rom_gb", totalRomGb)
            put("battery_capacity_mah", batteryCapacityMAh)
            put("back_camera_label", backCameraLabel)
            put("front_camera_label", frontCameraLabel)
            put("has_front_camera", hasFrontCamera)
            put("has_back_camera", hasBackCamera)
            put("total_cameras", totalCameras)
            put("os_name", osName)
            put("supported_resolutions", supportedResolutions)
            put("camera_permission", cameraPermissionGranted)
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