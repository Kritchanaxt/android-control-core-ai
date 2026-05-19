package com.example.android_screen_relay.presenter.state

import android.graphics.Bitmap
import android.util.Size
import com.example.android_screen_relay.core.AiMode
import com.example.android_screen_relay.core.ComputeMode
import com.example.android_screen_relay.core.UiAspectRatio
import com.example.android_screen_relay.presenter.AiState
import com.example.android_screen_relay.presenter.WatchdogStatus

/**
 * Sub-state groupings for AiState.
 *
 * These provide logical grouping WITHOUT breaking existing code.
 * Existing code continues to access AiState fields directly.
 * New code can use these sub-states for cleaner access patterns.
 *
 * Usage:
 *   val state = AiStateManager.state.value
 *   val cam = state.cameraState   // extension property
 *   val perf = state.performanceState
 */

// ─── Camera-related state ───

data class CameraState(
    val zoomScale: Float,
    val isFrontCamera: Boolean,
    val autoFramingEnabled: Boolean,
    val horizontalFlip: Boolean,
    val verticalFlip: Boolean,
    val selectedResolution: Size?,
    val availableResolutions: List<Size>,
    val selectedCameraId: String,
    val selectedAspectRatio: UiAspectRatio,
    val isCapturing: Boolean
)

// ─── AI Processing state ───

data class AiProcessingState(
    val currentAiMode: AiMode,
    val isProcessing: Boolean,
    val computeMode: ComputeMode,
    val currentImage: Bitmap?,
    val cropImage: Bitmap?,
    val processingResultMsg: String?,
    val useCropMode: Boolean,
    val isIdCardMode: Boolean,
    val maxFps: Int,
    val isThrottled: Boolean
)

// ─── Performance metrics ───

data class PerformanceState(
    val fps: Int,
    val detectorLatency: Long,
    val frameLatency: Long,
    val ramUsed: Long,
    val ramTotal: Long,
    val freeRamMb: Long,
    val cpuUsage: String
)

// ─── OCR-specific state ───

data class OcrState(
    val ocrResultJson: String,
    val ocrTimeMs: Long,
    val selectedOcrModel: String
)

// ─── Watchdog state ───

data class WatchdogState(
    val message: String?,
    val status: WatchdogStatus
)

// ─── Extension properties on AiState for zero-breaking-change access ───

val AiState.cameraState: CameraState
    get() = CameraState(
        zoomScale = zoomScale,
        isFrontCamera = isFrontCamera,
        autoFramingEnabled = autoFramingEnabled,
        horizontalFlip = horizontalFlip,
        verticalFlip = verticalFlip,
        selectedResolution = selectedResolution,
        availableResolutions = availableResolutions,
        selectedCameraId = selectedCameraId,
        selectedAspectRatio = selectedAspectRatio,
        isCapturing = isCapturing
    )

val AiState.aiProcessingState: AiProcessingState
    get() = AiProcessingState(
        currentAiMode = currentAiMode,
        isProcessing = isProcessing,
        computeMode = computeMode,
        currentImage = currentImage,
        cropImage = cropImage,
        processingResultMsg = processingResultMsg,
        useCropMode = useCropMode,
        isIdCardMode = isIdCardMode,
        maxFps = maxFps,
        isThrottled = isThrottled
    )

val AiState.performanceState: PerformanceState
    get() = PerformanceState(
        fps = fps,
        detectorLatency = detectorLatency,
        frameLatency = frameLatency,
        ramUsed = ramUsed,
        ramTotal = ramTotal,
        freeRamMb = freeRamMb,
        cpuUsage = cpuUsage
    )

val AiState.ocrState: OcrState
    get() = OcrState(
        ocrResultJson = ocrResultJson,
        ocrTimeMs = ocrTimeMs,
        selectedOcrModel = selectedOcrModel
    )

val AiState.watchdogState: WatchdogState
    get() = WatchdogState(
        message = watchdogMessage,
        status = watchdogStatus
    )
