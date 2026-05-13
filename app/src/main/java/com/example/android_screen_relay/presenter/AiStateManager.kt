package com.example.android_screen_relay.presenter

import android.graphics.Bitmap
import android.util.Size
import com.example.android_screen_relay.core.AiMode
import com.example.android_screen_relay.core.ComputeMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class AiState(
    val currentAiMode: AiMode = AiMode.PREVIEW,
    val isProcessing: Boolean = false,
    val showAiModeSheet: Boolean = false,
    val currentImage: Bitmap? = null,
    val leftPalmImage: Bitmap? = null,
    val rightPalmImage: Bitmap? = null,
    val ocrResultJson: String = "[]",
    val ocrTimeMs: Long = 0L,
    val computeMode: ComputeMode = ComputeMode.GPU,
    val targetHand: String = "Left",
    val targetFaceMode: String = "Straight",
    val zoomScale: Float = 1.0f,
    val useCropMode: Boolean = true,
    val selectedResolution: Size? = null,
    val availableResolutions: List<Size> = emptyList(),
    val selectedCameraId: String = "0",
    val selectedAspectRatio: String = "4:3",
    val cropImage: Bitmap? = null,
    val processingResultMsg: String? = null,
    val horizontalFlip: Boolean = false,
    val verticalFlip: Boolean = false,
    val selectedOcrModel: String = "",
    val hasPermission: Boolean = false,
    val maxFps: Int = 30,
    val isThrottled: Boolean = false
)

object AiStateManager {
    private val _state = MutableStateFlow(AiState())
    val state = _state.asStateFlow()

    fun updateState(transform: (AiState) -> AiState) {
        _state.update(transform)
    }
}
