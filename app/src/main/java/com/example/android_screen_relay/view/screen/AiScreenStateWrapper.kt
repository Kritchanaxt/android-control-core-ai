package com.example.android_screen_relay.core

import android.graphics.Bitmap
import android.util.Size
import com.example.android_screen_relay.core.AiMode
import com.example.android_screen_relay.core.ComputeMode
import com.example.android_screen_relay.core.UiAspectRatio
import com.example.android_screen_relay.presenter.AiState
import com.example.android_screen_relay.presenter.AiStateManager
import com.example.android_screen_relay.presenter.WatchdogStatus

/**
 * Wrapper that bridges Compose State<AiState> to mutable property syntax.
 * Reads delegate to composeState.value, writes delegate to AiStateManager.updateState.
 *
 * Extracted from AIScreen.kt for clarity and reuse.
 */
class AiScreenStateWrapper(private val composeState: androidx.compose.runtime.State<AiState>) {
    var currentAiMode: AiMode
        get() = composeState.value.currentAiMode
        set(value) { AiStateManager.updateState { it.copy(currentAiMode = value) } }
    var isProcessing: Boolean
        get() = composeState.value.isProcessing
        set(value) { AiStateManager.updateState { it.copy(isProcessing = value) } }
    var showAiModeSheet: Boolean
        get() = composeState.value.showAiModeSheet
        set(value) { AiStateManager.updateState { it.copy(showAiModeSheet = value) } }
    var currentImage: Bitmap?
        get() = composeState.value.currentImage
        set(value) { AiStateManager.updateState { it.copy(currentImage = value) } }
    var leftPalmImage: Bitmap?
        get() = composeState.value.leftPalmImage
        set(value) { AiStateManager.updateState { it.copy(leftPalmImage = value) } }
    var rightPalmImage: Bitmap?
        get() = composeState.value.rightPalmImage
        set(value) { AiStateManager.updateState { it.copy(rightPalmImage = value) } }
    var ocrResultJson: String
        get() = composeState.value.ocrResultJson
        set(value) { AiStateManager.updateState { it.copy(ocrResultJson = value) } }
    var ocrTimeMs: Long
        get() = composeState.value.ocrTimeMs
        set(value) { AiStateManager.updateState { it.copy(ocrTimeMs = value) } }
    var computeMode: ComputeMode
        get() = composeState.value.computeMode
        set(value) { AiStateManager.updateState { it.copy(computeMode = value) } }
    var targetHand: String
        get() = composeState.value.targetHand
        set(value) { AiStateManager.updateState { it.copy(targetHand = value) } }
    var targetFaceMode: String
        get() = composeState.value.targetFaceMode
        set(value) { AiStateManager.updateState { it.copy(targetFaceMode = value) } }
    var zoomScale: Float
        get() = composeState.value.zoomScale
        set(value) { AiStateManager.updateState { it.copy(zoomScale = value) } }
    var useCropMode: Boolean
        get() = composeState.value.useCropMode
        set(value) { AiStateManager.updateState { it.copy(useCropMode = value) } }
    var selectedResolution: Size?
        get() = composeState.value.selectedResolution
        set(value) { AiStateManager.updateState { it.copy(selectedResolution = value) } }
    var availableResolutions: List<Size>
        get() = composeState.value.availableResolutions
        set(value) { AiStateManager.updateState { it.copy(availableResolutions = value) } }
    var selectedCameraId: String
        get() = composeState.value.selectedCameraId
        set(value) { AiStateManager.updateState { it.copy(selectedCameraId = value) } }
    var selectedAspectRatio: UiAspectRatio
        get() = composeState.value.selectedAspectRatio
        set(value) { AiStateManager.updateState { it.copy(selectedAspectRatio = value) } }
    var cropImage: Bitmap?
        get() = composeState.value.cropImage
        set(value) { AiStateManager.updateState { it.copy(cropImage = value) } }
    var processingResultMsg: String?
        get() = composeState.value.processingResultMsg
        set(value) { AiStateManager.updateState { it.copy(processingResultMsg = value) } }
    var horizontalFlip: Boolean
        get() = composeState.value.horizontalFlip
        set(value) { AiStateManager.updateState { it.copy(horizontalFlip = value) } }
    var verticalFlip: Boolean
        get() = composeState.value.verticalFlip
        set(value) { AiStateManager.updateState { it.copy(verticalFlip = value) } }
    var selfieOutputType: String
        get() = composeState.value.selfieOutputType
        set(value) { AiStateManager.updateState { it.copy(selfieOutputType = value) } }
    var selfieSelectClass: String
        get() = composeState.value.selfieSelectClass
        set(value) { AiStateManager.updateState { it.copy(selfieSelectClass = value) } }
    var isIdCardMode: Boolean
        get() = composeState.value.isIdCardMode
        set(value) { AiStateManager.updateState { it.copy(isIdCardMode = value) } }
    var selectedOcrModel: String
        get() = composeState.value.selectedOcrModel
        set(value) { AiStateManager.updateState { it.copy(selectedOcrModel = value) } }
    var autoFramingEnabled: Boolean
        get() = composeState.value.autoFramingEnabled
        set(value) { AiStateManager.updateState { it.copy(autoFramingEnabled = value) } }
    val watchdogMessage: String?
        get() = composeState.value.watchdogMessage
    val watchdogStatus: WatchdogStatus
        get() = composeState.value.watchdogStatus
}
