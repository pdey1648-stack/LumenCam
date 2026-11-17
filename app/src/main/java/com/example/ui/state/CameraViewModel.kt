package com.example.ui.state

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.camera.view.PreviewView
import androidx.compose.ui.graphics.ImageBitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.viewModelScope
import com.example.camera.CameraXController
import com.example.camera.ThermalMonitor
import com.example.camera.ViewfinderAnalyzer
import com.example.data.media.MediaStoreRepository
import com.example.domain.model.BokehShape
import com.example.domain.model.CameraMode
import com.example.domain.model.CameraUiEvent
import com.example.domain.model.CameraUiState
import com.example.domain.model.FlashMode
import com.example.domain.model.FocusReticleState
import com.example.domain.model.LensFacing
import com.example.domain.model.LutPreset
import com.example.domain.model.PortraitSidecarInfo
import com.example.domain.model.ThermalTier
import com.example.portrait.PortraitPipeline
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class CameraViewModel(application: Application) : AndroidViewModel(application) {

    private val appContext: Context = application.applicationContext
    private val mediaStoreRepository = MediaStoreRepository(appContext)
    private val thermalMonitor = ThermalMonitor(appContext)
    private val portraitPipeline = PortraitPipeline(appContext, mediaStoreRepository)
    private val cameraController = CameraXController(appContext, mediaStoreRepository)

    private val _uiState = MutableStateFlow(CameraUiState())
    val uiState: StateFlow<CameraUiState> = _uiState.asStateFlow()

    private val _peakingBitmap = MutableStateFlow<ImageBitmap?>(null)
    val peakingBitmap: StateFlow<ImageBitmap?> = _peakingBitmap.asStateFlow()

    private val _livePortraitBitmap = MutableStateFlow<ImageBitmap?>(null)
    val livePortraitBitmap: StateFlow<ImageBitmap?> = _livePortraitBitmap.asStateFlow()

    private val _events = MutableSharedFlow<CameraUiEvent>(extraBufferCapacity = 8)
    val events: SharedFlow<CameraUiEvent> = _events.asSharedFlow()

    private var activePreviewView: PreviewView? = null
    private var bindJob: Job? = null
    private var countdownJob: Job? = null
    private var reticleHideJob: Job? = null
    private var zoomAutoSwitchedForPortrait = false
    private var userModifiedPortraitZoom = false

    private val viewfinderAnalyzer = ViewfinderAnalyzer(
        portraitPipeline = portraitPipeline,
        onPeakingFrame = { frame ->
            _peakingBitmap.value = frame
        },
        onPortraitPreviewFrame = { frame ->
            _livePortraitBitmap.value = frame
        },
        onFacesUpdated = { faces ->
            val primary = faces.maxByOrNull { (it.right - it.left) * (it.bottom - it.top) }
            _uiState.update { state ->
                if (primary != null && !state.isAeAfLocked) {
                    viewfinderAnalyzerFocusPoint(primary.centerX, primary.centerY)
                    state.copy(
                        detectedFaces = faces,
                        portraitFocusX = primary.centerX,
                        portraitFocusY = primary.centerY,
                    )
                } else {
                    state.copy(detectedFaces = faces)
                }
            }
        },
        onAutoFocusFace = { normX, normY ->
            val pv = activePreviewView ?: return@ViewfinderAnalyzer
            if (!_uiState.value.isAeAfLocked && pv.width > 0 && pv.height > 0) {
                cameraController.triggerFocusAndMetering(
                    previewView = pv,
                    xPx = normX * pv.width,
                    yPx = normY * pv.height,
                    lockAeAf = false,
                )
            }
        },
    )

    private fun viewfinderAnalyzerFocusPoint(fx: Float, fy: Float) {
        viewfinderAnalyzer.portraitFocusX = fx
        viewfinderAnalyzer.portraitFocusY = fy
    }

    init {
        observeThermalState()
        refreshGallery()
    }

    private fun observeThermalState() {
        viewModelScope.launch {
            thermalMonitor.observeThermalTier().collect { tier ->
                viewfinderAnalyzer.thermalTier = tier
                _uiState.update { state ->
                    val updatedFlash = if (tier >= ThermalTier.SEVERE && state.flashMode == FlashMode.ON) {
                        FlashMode.OFF
                    } else {
                        state.flashMode
                    }
                    state.copy(
                        thermalTier = tier,
                        flashMode = updatedFlash,
                    )
                }
            }
        }
    }

    fun onCameraPermissionChanged(granted: Boolean) {
        _uiState.update { it.copy(hasCameraPermission = granted) }
        if (granted) {
            refreshGallery()
        }
    }

    fun bindCamera(
        lifecycleOwner: LifecycleOwner,
        previewView: PreviewView,
    ) {
        val current = _uiState.value
        if (!current.hasCameraPermission || current.thermalTier == ThermalTier.CRITICAL) return

        activePreviewView = previewView
        bindJob?.cancel()
        bindJob = viewModelScope.launch {
            runCatching {
                cameraController.bindSession(
                    lifecycleOwner = lifecycleOwner,
                    previewView = previewView,
                    lensFacing = current.lensFacing,
                    flashMode = current.flashMode,
                    isRawEnabled = current.isRawEnabled,
                    initialZoomRatio = current.zoomRatio,
                    exposureIndex = current.exposureIndex,
                    apertureFStop = current.apertureFStop,
                    colorTone = current.colorTone,
                    thermalTier = current.thermalTier,
                    analyzer = viewfinderAnalyzer,
                    onZoomStateChanged = { zoomState ->
                        viewfinderAnalyzer.zoomRatio = zoomState.zoomRatio
                        _uiState.update { state ->
                            state.copy(
                                zoomRatio = zoomState.zoomRatio,
                                capabilities = state.capabilities.copy(
                                    minZoomRatio = zoomState.minZoomRatio,
                                    maxZoomRatio = zoomState.maxZoomRatio,
                                ),
                            )
                        }
                    },
                )
            }.onSuccess { capabilities ->
                _uiState.update { state ->
                    val targetZoom = if (
                        state.mode == CameraMode.PORTRAIT &&
                        state.lensFacing == LensFacing.BACK &&
                        !userModifiedPortraitZoom &&
                        state.zoomRatio < 1.8f
                    ) {
                        2.0f.coerceIn(capabilities.minZoomRatio, capabilities.maxZoomRatio)
                    } else {
                        state.zoomRatio.coerceIn(capabilities.minZoomRatio, capabilities.maxZoomRatio)
                    }
                    if (targetZoom != state.zoomRatio) {
                        cameraController.setZoomRatio(targetZoom)
                    }
                    viewfinderAnalyzer.zoomRatio = targetZoom
                    state.copy(
                        capabilities = capabilities,
                        zoomRatio = targetZoom,
                        isHardwareApertureActive = capabilities.supportsVariableHardwareAperture,
                        isCameraReady = true,
                        cameraErrorMessage = null,
                        isRawEnabled = state.isRawEnabled && capabilities.supportsRawDng,
                    )
                }
            }.onFailure { error ->
                _uiState.update {
                    it.copy(
                        isCameraReady = false,
                        cameraErrorMessage = error.localizedMessage ?: "Unable to start camera",
                    )
                }
            }
        }
    }

    fun selectMode(mode: CameraMode) {
        val current = _uiState.value
        if (current.mode == mode) return

        val minZoom = current.capabilities.minZoomRatio
        val maxZoom = current.capabilities.maxZoomRatio
        val portraitActive = mode == CameraMode.PORTRAIT
        viewfinderAnalyzer.isPortraitBokehEnabled = portraitActive
        if (!portraitActive) {
            _livePortraitBitmap.value = null
        }

        if (portraitActive && current.lensFacing == LensFacing.BACK) {
            userModifiedPortraitZoom = false
            val portraitZoom = 2.0f.coerceIn(minZoom, maxZoom)
            if (current.zoomRatio < 1.8f && maxZoom >= 1.8f) {
                zoomAutoSwitchedForPortrait = true
                cameraController.setZoomRatio(portraitZoom)
                viewfinderAnalyzer.zoomRatio = portraitZoom
                _uiState.update { it.copy(mode = mode, zoomRatio = portraitZoom) }
                return
            }
        } else if (current.mode == CameraMode.PORTRAIT && zoomAutoSwitchedForPortrait) {
            zoomAutoSwitchedForPortrait = false
            userModifiedPortraitZoom = false
            val defaultZoom = 1.0f.coerceIn(minZoom, maxZoom)
            cameraController.setZoomRatio(defaultZoom)
            viewfinderAnalyzer.zoomRatio = defaultZoom
            _uiState.update { it.copy(mode = mode, zoomRatio = defaultZoom) }
            return
        }

        _uiState.update { it.copy(mode = mode) }
    }

    fun toggleTonePanel() {
        _uiState.update { it.copy(isTonePanelOpen = !it.isTonePanelOpen) }
    }

    fun setLutPreset(preset: LutPreset) {
        val nextTone = _uiState.value.colorTone.copy(lutPreset = preset)
        viewfinderAnalyzer.colorTone = nextTone
        _uiState.update { it.copy(colorTone = nextTone) }
    }

    fun toggleAutoWhiteBalance() {
        val current = _uiState.value.colorTone
        val nextTone = current.copy(isAutoWhiteBalance = !current.isAutoWhiteBalance)
        viewfinderAnalyzer.colorTone = nextTone
        cameraController.applyCamera2ToneAndAperture(
            _uiState.value.capabilities,
            nextTone,
            _uiState.value.apertureFStop,
        )
        _uiState.update { it.copy(colorTone = nextTone) }
    }

    fun setKelvin(kelvin: Int) {
        val clamped = kelvin.coerceIn(2800, 8000)
        val nextTone = _uiState.value.colorTone.copy(
            isAutoWhiteBalance = false,
            kelvin = clamped,
        )
        viewfinderAnalyzer.colorTone = nextTone
        cameraController.applyCamera2ToneAndAperture(
            _uiState.value.capabilities,
            nextTone,
            _uiState.value.apertureFStop,
        )
        _uiState.update { it.copy(colorTone = nextTone) }
    }

    fun toggleHdr() {
        val current = _uiState.value.colorTone
        val nextTone = current.copy(isHdrEnabled = !current.isHdrEnabled)
        viewfinderAnalyzer.colorTone = nextTone
        cameraController.applyCamera2ToneAndAperture(
            _uiState.value.capabilities,
            nextTone,
            _uiState.value.apertureFStop,
        )
        _uiState.update { it.copy(colorTone = nextTone) }
    }

    fun setSubjectSharpenAmount(amount: Float) {
        val nextTone = _uiState.value.colorTone.copy(
            subjectSharpenAmount = amount.coerceIn(0f, 0.8f),
        )
        viewfinderAnalyzer.colorTone = nextTone
        _uiState.update { it.copy(colorTone = nextTone) }
    }

    fun setApertureFStop(fStop: Float) {
        val clamped = fStop.coerceIn(1.4f, 16.0f)
        viewfinderAnalyzer.apertureFStop = clamped
        val caps = _uiState.value.capabilities
        if (caps.supportsVariableHardwareAperture) {
            cameraController.applyCamera2ToneAndAperture(caps, _uiState.value.colorTone, clamped)
        }
        _uiState.update { it.copy(apertureFStop = clamped) }
    }

    fun toggleBokehShape() {
        val next = _uiState.value.bokehShape.toggle()
        viewfinderAnalyzer.bokehShape = next
        _uiState.update { it.copy(bokehShape = next) }
    }

    fun selectZoomRatio(ratio: Float) {
        zoomAutoSwitchedForPortrait = false
        userModifiedPortraitZoom = true
        val caps = _uiState.value.capabilities
        val clamped = ratio.coerceIn(caps.minZoomRatio, caps.maxZoomRatio)
        cameraController.setZoomRatio(clamped)
        viewfinderAnalyzer.zoomRatio = clamped
        _uiState.update { it.copy(zoomRatio = clamped) }
    }

    fun onPinchZoom(scaleFactor: Float) {
        zoomAutoSwitchedForPortrait = false
        userModifiedPortraitZoom = true
        val state = _uiState.value
        val nextZoom = (state.zoomRatio * scaleFactor).coerceIn(
            state.capabilities.minZoomRatio,
            state.capabilities.maxZoomRatio,
        )
        cameraController.setZoomRatio(nextZoom)
        viewfinderAnalyzer.zoomRatio = nextZoom
        _uiState.update { it.copy(zoomRatio = nextZoom) }
    }

    fun onTapToFocus(
        previewView: PreviewView,
        xPx: Float,
        yPx: Float,
        widthPx: Float,
        heightPx: Float,
    ) {
        if (widthPx <= 0f || heightPx <= 0f) return
        val normX = (xPx / widthPx).coerceIn(0f, 1f)
        val normY = (yPx / heightPx).coerceIn(0f, 1f)
        val token = System.currentTimeMillis()

        viewfinderAnalyzer.isAeAfLocked = false
        viewfinderAnalyzerFocusPoint(normX, normY)
        _uiState.update {
            it.copy(
                isAeAfLocked = false,
                portraitFocusX = normX,
                portraitFocusY = normY,
                focusReticle = FocusReticleState(
                    normalizedX = normX,
                    normalizedY = normY,
                    isLocked = false,
                    isFocusing = true,
                    token = token,
                ),
            )
        }

        cameraController.triggerFocusAndMetering(
            previewView = previewView,
            xPx = xPx,
            yPx = yPx,
            lockAeAf = false,
        ) {
            _uiState.update { state ->
                val currentReticle = state.focusReticle
                if (currentReticle?.token == token) {
                    state.copy(focusReticle = currentReticle.copy(isFocusing = false))
                } else {
                    state
                }
            }
        }

        scheduleReticleAutoHide(token)
    }

    fun onLongPressAeAfLock(
        previewView: PreviewView,
        xPx: Float,
        yPx: Float,
        widthPx: Float,
        heightPx: Float,
    ) {
        if (widthPx <= 0f || heightPx <= 0f) return
        val normX = (xPx / widthPx).coerceIn(0f, 1f)
        val normY = (yPx / heightPx).coerceIn(0f, 1f)
        val token = System.currentTimeMillis()

        reticleHideJob?.cancel()
        viewfinderAnalyzer.isAeAfLocked = true
        viewfinderAnalyzerFocusPoint(normX, normY)

        _uiState.update {
            it.copy(
                isAeAfLocked = true,
                portraitFocusX = normX,
                portraitFocusY = normY,
                focusReticle = FocusReticleState(
                    normalizedX = normX,
                    normalizedY = normY,
                    isLocked = true,
                    isFocusing = true,
                    token = token,
                ),
            )
        }

        cameraController.triggerFocusAndMetering(
            previewView = previewView,
            xPx = xPx,
            yPx = yPx,
            lockAeAf = true,
        ) {
            _uiState.update { state ->
                val currentReticle = state.focusReticle
                if (currentReticle?.token == token) {
                    state.copy(focusReticle = currentReticle.copy(isFocusing = false))
                } else {
                    state
                }
            }
        }
    }

    fun unlockAeAf() {
        reticleHideJob?.cancel()
        viewfinderAnalyzer.isAeAfLocked = false
        cameraController.cancelFocusAndMetering()
        _uiState.update {
            it.copy(
                isAeAfLocked = false,
                focusReticle = null,
            )
        }
    }

    fun setExposureIndex(index: Int) {
        val range = _uiState.value.capabilities.exposureRange
        val clamped = index.coerceIn(range.first, range.last)
        cameraController.setExposureCompensationIndex(clamped)
        _uiState.update { it.copy(exposureIndex = clamped) }
        _uiState.value.focusReticle?.let { reticle ->
            if (!reticle.isLocked) {
                scheduleReticleAutoHide(reticle.token)
            }
        }
    }

    fun toggleFocusPeaking() {
        val next = !_uiState.value.isFocusPeakingEnabled
        viewfinderAnalyzer.isFocusPeakingEnabled = next
        if (!next) {
            _peakingBitmap.value = null
        }
        _uiState.update { it.copy(isFocusPeakingEnabled = next) }
    }

    fun toggleFaceFocus() {
        val next = !_uiState.value.isFaceFocusEnabled
        viewfinderAnalyzer.isFaceFocusEnabled = next
        if (!next) {
            _uiState.update { it.copy(isFaceFocusEnabled = false, detectedFaces = emptyList()) }
        } else {
            _uiState.update { it.copy(isFaceFocusEnabled = true) }
        }
    }

    private fun scheduleReticleAutoHide(token: Long) {
        reticleHideJob?.cancel()
        reticleHideJob = viewModelScope.launch {
            delay(3500L)
            _uiState.update { state ->
                if (state.focusReticle?.token == token && !state.isAeAfLocked) {
                    state.copy(focusReticle = null)
                } else {
                    state
                }
            }
        }
    }

    fun toggleLensFacing() {
        val current = _uiState.value
        if (!current.capabilities.hasFrontCamera && current.lensFacing == LensFacing.BACK) return
        zoomAutoSwitchedForPortrait = false
        userModifiedPortraitZoom = false
        viewfinderAnalyzer.isAeAfLocked = false
        viewfinderAnalyzer.zoomRatio = 1f
        _livePortraitBitmap.value = null
        _uiState.update {
            it.copy(
                lensFacing = it.lensFacing.toggle(),
                zoomRatio = 1f,
                exposureIndex = 0,
                isAeAfLocked = false,
                focusReticle = null,
                detectedFaces = emptyList(),
                isCameraReady = false,
            )
        }
    }

    fun cycleFlashMode() {
        _uiState.update { state ->
            val next = if (state.thermalTier >= ThermalTier.SEVERE) {
                FlashMode.OFF
            } else {
                state.flashMode.next()
            }
            cameraController.updateFlashMode(next)
            state.copy(flashMode = next)
        }
    }

    fun cycleTimerDuration() {
        if (_uiState.value.countdownRemainingSec > 0) {
            countdownJob?.cancel()
            _uiState.update { it.copy(countdownRemainingSec = 0) }
            return
        }
        _uiState.update { it.copy(timerDuration = it.timerDuration.next()) }
    }

    fun toggleGrid() {
        _uiState.update { it.copy(isGridEnabled = !it.isGridEnabled) }
    }

    fun toggleRawCapture() {
        val caps = _uiState.value.capabilities
        if (!caps.supportsRawDng) return
        _uiState.update { it.copy(isRawEnabled = !it.isRawEnabled) }
    }

    fun refreshGallery() {
        viewModelScope.launch {
            val items = runCatching { mediaStoreRepository.queryCapturedPhotos() }
                .getOrDefault(emptyList())
            _uiState.update {
                it.copy(
                    recentCaptures = items,
                    latestCaptureUri = items.firstOrNull()?.uri ?: it.latestCaptureUri,
                )
            }
        }
    }

    fun onShutterPressed() {
        val state = _uiState.value
        if (state.isCapturing || !state.isCameraReady) return

        if (state.countdownRemainingSec > 0) {
            countdownJob?.cancel()
            _uiState.update { it.copy(countdownRemainingSec = 0) }
            return
        }

        val timerSeconds = state.timerDuration.seconds
        if (timerSeconds > 0) {
            countdownJob?.cancel()
            countdownJob = viewModelScope.launch {
                for (remaining in timerSeconds downTo 1) {
                    _uiState.update { it.copy(countdownRemainingSec = remaining) }
                    delay(1000L)
                }
                _uiState.update { it.copy(countdownRemainingSec = 0) }
                executeCapture()
            }
        } else {
            executeCapture()
        }
    }

    private fun executeCapture() {
        val state = _uiState.value
        if (state.isCapturing) return

        val captureMode = state.mode
        val snapshotFStop = state.apertureFStop
        val snapshotZoom = state.zoomRatio
        val snapshotFx = state.portraitFocusX
        val snapshotFy = state.portraitFocusY
        val snapshotShape = state.bokehShape
        val snapshotTone = state.colorTone
        val snapshotFaces = state.detectedFaces
        val usesSoftwareBokeh = captureMode == CameraMode.PORTRAIT && !state.isHardwareApertureActive

        _uiState.update {
            it.copy(
                isCapturing = true,
                shutterPulseToken = System.currentTimeMillis(),
            )
        }

        cameraController.capturePhoto(
            isFrontCamera = state.lensFacing == LensFacing.FRONT,
            isRawEnabled = state.isRawEnabled,
            capabilities = state.capabilities,
            onSaved = { savedUri, baseName, savedRaw ->
                viewModelScope.launch {
                    if (savedUri != null) {
                        if (usesSoftwareBokeh) {
                            _uiState.update { it.copy(isProcessingPortrait = true) }
                            runCatching {
                                portraitPipeline.processFullResPortraitCapture(
                                    capturedUri = savedUri,
                                    baseName = baseName,
                                    fNumber = snapshotFStop,
                                    zoomRatio = snapshotZoom,
                                    focusX = snapshotFx,
                                    focusY = snapshotFy,
                                    bokehShape = snapshotShape,
                                    colorTone = snapshotTone,
                                    detectedFaces = snapshotFaces,
                                )
                            }
                        } else if (
                            snapshotTone.lutPreset != LutPreset.NATURAL ||
                            !snapshotTone.isAutoWhiteBalance ||
                            snapshotTone.isHdrEnabled ||
                            snapshotTone.subjectSharpenAmount > 0.05f
                        ) {
                            _uiState.update { it.copy(isProcessingPortrait = true) }
                            runCatching {
                                portraitPipeline.processStandardPhotoToneCapture(
                                    capturedUri = savedUri,
                                    colorTone = snapshotTone,
                                )
                            }
                        }
                    }

                    val refreshed = runCatching { mediaStoreRepository.queryCapturedPhotos() }
                        .getOrDefault(emptyList())
                    val latestItem = refreshed.firstOrNull()
                    _uiState.update {
                        it.copy(
                            isCapturing = false,
                            isProcessingPortrait = false,
                            recentCaptures = refreshed,
                            latestCaptureUri = savedUri ?: latestItem?.uri ?: it.latestCaptureUri,
                        )
                    }
                    if (latestItem != null) {
                        _events.emit(CameraUiEvent.CaptureSaved(latestItem, savedRaw))
                    }
                }
            },
            onError = { throwable ->
                viewModelScope.launch {
                    _uiState.update { it.copy(isCapturing = false, isProcessingPortrait = false) }
                    _events.emit(
                        CameraUiEvent.CaptureFailed(
                            throwable.localizedMessage ?: "Capture failed",
                        )
                    )
                }
            },
        )
    }

    suspend fun renderPortraitEditPreview(
        sidecar: PortraitSidecarInfo,
        fStop: Float,
        focusX: Float,
        focusY: Float,
        bokehShape: BokehShape,
        lutPreset: LutPreset,
    ): Bitmap? {
        return portraitPipeline.renderSidecarEditPreview(
            sidecar = sidecar,
            newFStop = fStop,
            newFocusX = focusX,
            newFocusY = focusY,
            newBokehShape = bokehShape,
            lutPreset = lutPreset,
        )
    }

    fun saveEditedPortraitCopy(
        sidecar: PortraitSidecarInfo,
        fStop: Float,
        focusX: Float,
        focusY: Float,
        bokehShape: BokehShape,
        lutPreset: LutPreset,
        onComplete: (Uri?) -> Unit,
    ) {
        viewModelScope.launch {
            val savedUri = portraitPipeline.saveEditedPortraitCopy(
                sidecar = sidecar,
                newFStop = fStop,
                newFocusX = focusX,
                newFocusY = focusY,
                newBokehShape = bokehShape,
                lutPreset = lutPreset,
            )
            refreshGallery()
            onComplete(savedUri)
        }
    }

    override fun onCleared() {
        super.onCleared()
        bindJob?.cancel()
        countdownJob?.cancel()
        reticleHideJob?.cancel()
        viewfinderAnalyzer.release()
        portraitPipeline.release()
        cameraController.release()
    }
}
