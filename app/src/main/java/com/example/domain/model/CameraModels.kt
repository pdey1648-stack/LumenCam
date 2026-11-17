package com.example.domain.model

import android.net.Uri
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture

enum class CameraMode(val label: String) {
    PHOTO("PHOTO"),
    PORTRAIT("PORTRAIT"),
    PRO("PRO"),
}

enum class FlashMode(val cameraXMode: Int, val label: String) {
    OFF(ImageCapture.FLASH_MODE_OFF, "OFF"),
    AUTO(ImageCapture.FLASH_MODE_AUTO, "AUTO"),
    ON(ImageCapture.FLASH_MODE_ON, "ON");

    fun next(): FlashMode = when (this) {
        OFF -> AUTO
        AUTO -> ON
        ON -> OFF
    }
}

enum class TimerDuration(val seconds: Int, val label: String) {
    OFF(0, "0s"),
    THREE_SEC(3, "3s"),
    TEN_SEC(10, "10s");

    fun next(): TimerDuration = when (this) {
        OFF -> THREE_SEC
        THREE_SEC -> TEN_SEC
        TEN_SEC -> OFF
    }
}

enum class LensFacing(val selector: Int) {
    BACK(CameraSelector.LENS_FACING_BACK),
    FRONT(CameraSelector.LENS_FACING_FRONT);

    fun toggle(): LensFacing = if (this == BACK) FRONT else BACK
}

enum class ThermalTier {
    NORMAL,
    MODERATE,
    SEVERE,
    CRITICAL,
}

enum class BokehShape(val shaderValue: Int, val label: String) {
    HEXAGON(1, "HEX"),
    DISC(0, "DISC");

    fun toggle(): BokehShape = if (this == HEXAGON) DISC else HEXAGON
}

enum class LutPreset(val label: String) {
    NATURAL("Natural"),
    WARM("Warm"),
    COOL("Cool"),
    FILM("Film"),
}

data class NormalizedRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    val centerX: Float get() = (left + right) * 0.5f
    val centerY: Float get() = (top + bottom) * 0.5f
}

data class FocusReticleState(
    val normalizedX: Float,
    val normalizedY: Float,
    val isLocked: Boolean,
    val isFocusing: Boolean,
    val token: Long,
)

data class CameraHardwareCapabilities(
    val hasFlashUnit: Boolean = false,
    val supportsRawDng: Boolean = false,
    val supportsHardwareHdrScene: Boolean = false,
    val supportsManualAwbGains: Boolean = false,
    val availableHardwareApertures: List<Float> = emptyList(),
    val minZoomRatio: Float = 1f,
    val maxZoomRatio: Float = 1f,
    val hasFrontCamera: Boolean = true,
    val exposureRange: IntRange = 0..0,
    val exposureStepEv: Float = 0f,
    val availableFocalLengths: List<Float> = emptyList(),
    val physicalCameraCount: Int = 1,
) {
    val supportsVariableHardwareAperture: Boolean
        get() = availableHardwareApertures.size > 1
}

data class ColorToneSettings(
    val isAutoWhiteBalance: Boolean = true,
    val kelvin: Int = 5500,
    val lutPreset: LutPreset = LutPreset.NATURAL,
    val isHdrEnabled: Boolean = false,
    val skinToneProtection: Float = 0.72f,
    val subjectSharpenAmount: Float = 0.35f,
)

data class PortraitSidecarInfo(
    val baseName: String,
    val originalPath: String,
    val matteDepthPath: String,
    val apertureFStop: Float,
    val focusX: Float,
    val focusY: Float,
    val bokehShape: BokehShape,
    val zoomRatio: Float,
    val lutPreset: LutPreset = LutPreset.NATURAL,
)

data class CapturedMediaItem(
    val id: Long,
    val uri: Uri,
    val displayName: String,
    val timestampMs: Long,
    val width: Int,
    val height: Int,
    val sizeBytes: Long,
    val hasRawCompanion: Boolean = false,
    val portraitSidecar: PortraitSidecarInfo? = null,
)

data class CameraUiState(
    val hasCameraPermission: Boolean = false,
    val mode: CameraMode = CameraMode.PHOTO,
    val lensFacing: LensFacing = LensFacing.BACK,
    val flashMode: FlashMode = FlashMode.AUTO,
    val timerDuration: TimerDuration = TimerDuration.OFF,
    val isGridEnabled: Boolean = true,
    val isRawEnabled: Boolean = false,
    val isFocusPeakingEnabled: Boolean = false,
    val isFaceFocusEnabled: Boolean = true,
    val isTonePanelOpen: Boolean = false,
    val zoomRatio: Float = 1f,
    val apertureFStop: Float = 1.8f,
    val bokehShape: BokehShape = BokehShape.HEXAGON,
    val isHardwareApertureActive: Boolean = false,
    val portraitFocusX: Float = 0.5f,
    val portraitFocusY: Float = 0.42f,
    val colorTone: ColorToneSettings = ColorToneSettings(),
    val isAeAfLocked: Boolean = false,
    val exposureIndex: Int = 0,
    val focusReticle: FocusReticleState? = null,
    val detectedFaces: List<NormalizedRect> = emptyList(),
    val isCapturing: Boolean = false,
    val isProcessingPortrait: Boolean = false,
    val countdownRemainingSec: Int = 0,
    val shutterPulseToken: Long = 0L,
    val thermalTier: ThermalTier = ThermalTier.NORMAL,
    val capabilities: CameraHardwareCapabilities = CameraHardwareCapabilities(),
    val recentCaptures: List<CapturedMediaItem> = emptyList(),
    val latestCaptureUri: Uri? = null,
    val isCameraReady: Boolean = false,
    val cameraErrorMessage: String? = null,
)

sealed interface CameraUiEvent {
    data class CaptureSaved(val item: CapturedMediaItem, val savedRaw: Boolean) : CameraUiEvent
    data class CaptureFailed(val message: String) : CameraUiEvent
    data class InfoMessage(val message: String) : CameraUiEvent
}
