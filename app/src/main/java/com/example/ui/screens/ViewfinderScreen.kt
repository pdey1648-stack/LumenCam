package com.example.ui.screens

import android.os.Build
import android.os.SystemClock
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.R
import com.example.color.LiveToneAgslEffect
import com.example.domain.model.CameraMode
import com.example.domain.model.CameraUiEvent
import com.example.domain.model.CameraUiState
import com.example.domain.model.LutPreset
import com.example.ui.components.AeAfLockChip
import com.example.ui.components.ApertureControlBar
import com.example.ui.components.ColorToneControlPanel
import com.example.ui.components.CountdownOverlay
import com.example.ui.components.ExposureCompensationBar
import com.example.ui.components.FaceTrackingOverlay
import com.example.ui.components.FocusPeakingOverlay
import com.example.ui.components.FocusReticleOverlay
import com.example.ui.components.LivePortraitOverlay
import com.example.ui.components.ShutterControlDeck
import com.example.ui.components.TopDslrControlBar
import com.example.ui.components.ViewfinderGridOverlay
import com.example.ui.components.ZoomControlStrip
import com.example.ui.theme.AlertCrimson
import com.example.ui.theme.GlassOverlay
import com.example.ui.theme.HairlineDivider
import com.example.ui.theme.MutedSilver
import com.example.ui.theme.ObsidianBlack
import com.example.ui.theme.OpticalWhite
import com.example.ui.theme.ThermalGoodGreen
import com.example.ui.theme.TungstenAmber
import kotlinx.coroutines.flow.SharedFlow
import java.util.Locale
import kotlin.math.abs

@Composable
fun ViewfinderScreen(
    uiState: CameraUiState,
    peakingBitmap: ImageBitmap?,
    livePortraitBitmap: ImageBitmap?,
    events: SharedFlow<CameraUiEvent>,
    onBindCamera: (androidx.lifecycle.LifecycleOwner, PreviewView) -> Unit,
    onModeSelected: (CameraMode) -> Unit,
    onFlashClick: () -> Unit,
    onTimerClick: () -> Unit,
    onGridClick: () -> Unit,
    onRawClick: () -> Unit,
    onFocusPeakingClick: () -> Unit,
    onFaceFocusClick: () -> Unit,
    onHdrClick: () -> Unit,
    onTonePanelClick: () -> Unit,
    onLutSelected: (LutPreset) -> Unit,
    onAwbToggle: () -> Unit,
    onKelvinChanged: (Int) -> Unit,
    onSharpnessChanged: (Float) -> Unit,
    onApertureChanged: (Float) -> Unit,
    onBokehShapeToggle: () -> Unit,
    onZoomSelected: (Float) -> Unit,
    onPinchZoom: (Float) -> Unit,
    onTapToFocus: (PreviewView, Float, Float, Float, Float) -> Unit,
    onLongPressAeAfLock: (PreviewView, Float, Float, Float, Float) -> Unit,
    onUnlockAeAf: () -> Unit,
    onExposureIndexChanged: (Int) -> Unit,
    onShutterClick: () -> Unit,
    onSwitchCameraClick: () -> Unit,
    onGalleryClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val snackbarHostState = remember { SnackbarHostState() }

    val previewView = remember {
        PreviewView(context).apply {
            implementationMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                PreviewView.ImplementationMode.COMPATIBLE
            } else {
                PreviewView.ImplementationMode.PERFORMANCE
            }
            scaleType = PreviewView.ScaleType.FIT_CENTER
        }
    }
    var isPreviewViewAttached by remember { mutableStateOf(false) }

    val shutterBlackoutAlpha = remember { Animatable(0f) }

    LaunchedEffect(uiState.shutterPulseToken) {
        if (uiState.shutterPulseToken > 0L) {
            shutterBlackoutAlpha.snapTo(0.75f)
            shutterBlackoutAlpha.animateTo(0f, animationSpec = tween(durationMillis = 180))
        }
    }

    LaunchedEffect(events) {
        events.collect { event ->
            val message = when (event) {
                is CameraUiEvent.CaptureSaved -> {
                    if (event.savedRaw) {
                        context.getString(R.string.capture_saved_raw_jpeg)
                    } else {
                        context.getString(R.string.capture_saved_jpeg)
                    }
                }
                is CameraUiEvent.CaptureFailed -> {
                    "${context.getString(R.string.capture_error)}: ${event.message}"
                }
                is CameraUiEvent.InfoMessage -> event.message
            }
            snackbarHostState.showSnackbar(message)
        }
    }

    LaunchedEffect(
        isPreviewViewAttached,
        uiState.hasCameraPermission,
        uiState.lensFacing,
        uiState.thermalTier,
        uiState.isRawEnabled,
        lifecycleOwner,
    ) {
        if (isPreviewViewAttached && uiState.hasCameraPermission) {
            onBindCamera(lifecycleOwner, previewView)
        }
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(ObsidianBlack),
    ) {
        val isWideLandscape = maxWidth > maxHeight && maxWidth >= 600.dp

        if (isWideLandscape) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .padding(12.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    ViewfinderFrame(
                        previewView = previewView,
                        uiState = uiState,
                        peakingBitmap = peakingBitmap,
                        livePortraitBitmap = livePortraitBitmap,
                        shutterBlackoutAlpha = shutterBlackoutAlpha.value,
                        onPreviewAttached = { isPreviewViewAttached = true },
                        onPinchZoom = onPinchZoom,
                        onTapToFocus = { x, y, w, h -> onTapToFocus(previewView, x, y, w, h) },
                        onLongPressAeAfLock = { x, y, w, h ->
                            onLongPressAeAfLock(previewView, x, y, w, h)
                        },
                        onUnlockAeAf = onUnlockAeAf,
                        modifier = Modifier
                            .fillMaxHeight()
                            .aspectRatio(4f / 3f, matchHeightConstraintsFirst = true),
                    )
                    TopDslrControlBar(
                        flashMode = uiState.flashMode,
                        hasFlashUnit = uiState.capabilities.hasFlashUnit,
                        timerDuration = uiState.timerDuration,
                        isGridEnabled = uiState.isGridEnabled,
                        supportsRawDng = uiState.capabilities.supportsRawDng,
                        isRawEnabled = uiState.isRawEnabled,
                        isFocusPeakingEnabled = uiState.isFocusPeakingEnabled,
                        isFaceFocusEnabled = uiState.isFaceFocusEnabled,
                        isHdrEnabled = uiState.colorTone.isHdrEnabled,
                        isTonePanelOpen = uiState.isTonePanelOpen,
                        activeLut = uiState.colorTone.lutPreset,
                        thermalTier = uiState.thermalTier,
                        onFlashClick = onFlashClick,
                        onTimerClick = onTimerClick,
                        onGridClick = onGridClick,
                        onRawClick = onRawClick,
                        onFocusPeakingClick = onFocusPeakingClick,
                        onFaceFocusClick = onFaceFocusClick,
                        onHdrClick = onHdrClick,
                        onTonePanelClick = onTonePanelClick,
                        modifier = Modifier.align(Alignment.TopCenter),
                    )
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 12.dp),
                    ) {
                        if (uiState.isTonePanelOpen || uiState.mode == CameraMode.PRO) {
                            ColorToneControlPanel(
                                colorTone = uiState.colorTone,
                                showSharpnessSlider = uiState.mode == CameraMode.PRO || uiState.isTonePanelOpen,
                                onLutSelected = onLutSelected,
                                onAwbToggle = onAwbToggle,
                                onKelvinChanged = onKelvinChanged,
                                onSharpnessChanged = onSharpnessChanged,
                            )
                        }
                        if (uiState.mode == CameraMode.PORTRAIT) {
                            ApertureControlBar(
                                apertureFStop = uiState.apertureFStop,
                                bokehShape = uiState.bokehShape,
                                isHardwareApertureActive = uiState.isHardwareApertureActive,
                                onApertureChanged = onApertureChanged,
                                onBokehShapeToggle = onBokehShapeToggle,
                            )
                        }
                        ZoomControlStrip(
                            currentZoomRatio = uiState.zoomRatio,
                            minZoomRatio = uiState.capabilities.minZoomRatio,
                            maxZoomRatio = uiState.capabilities.maxZoomRatio,
                            onZoomSelected = onZoomSelected,
                        )
                    }
                }

                Column(
                    modifier = Modifier
                        .width(268.dp)
                        .fillMaxHeight(),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    if (uiState.mode == CameraMode.PRO || uiState.focusReticle != null) {
                        ExposureCompensationBar(
                            exposureIndex = uiState.exposureIndex,
                            exposureRange = uiState.capabilities.exposureRange,
                            exposureStepEv = uiState.capabilities.exposureStepEv,
                            onExposureIndexChanged = onExposureIndexChanged,
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                    ShutterControlDeck(
                        latestCaptureUri = uiState.latestCaptureUri,
                        isCapturing = uiState.isCapturing,
                        isCameraReady = uiState.isCameraReady,
                        canSwitchCamera = uiState.capabilities.hasFrontCamera,
                        selectedMode = uiState.mode,
                        onModeSelected = onModeSelected,
                        onShutterClick = onShutterClick,
                        onGalleryClick = onGalleryClick,
                        onSwitchCameraClick = onSwitchCameraClick,
                    )
                }
            }
        } else {
            Column(
                modifier = Modifier.fillMaxSize(),
            ) {
                TopDslrControlBar(
                    flashMode = uiState.flashMode,
                    hasFlashUnit = uiState.capabilities.hasFlashUnit,
                    timerDuration = uiState.timerDuration,
                    isGridEnabled = uiState.isGridEnabled,
                    supportsRawDng = uiState.capabilities.supportsRawDng,
                    isRawEnabled = uiState.isRawEnabled,
                    isFocusPeakingEnabled = uiState.isFocusPeakingEnabled,
                    isFaceFocusEnabled = uiState.isFaceFocusEnabled,
                    isHdrEnabled = uiState.colorTone.isHdrEnabled,
                    isTonePanelOpen = uiState.isTonePanelOpen,
                    activeLut = uiState.colorTone.lutPreset,
                    thermalTier = uiState.thermalTier,
                    onFlashClick = onFlashClick,
                    onTimerClick = onTimerClick,
                    onGridClick = onGridClick,
                    onRawClick = onRawClick,
                    onFocusPeakingClick = onFocusPeakingClick,
                    onFaceFocusClick = onFaceFocusClick,
                    onHdrClick = onHdrClick,
                    onTonePanelClick = onTonePanelClick,
                    modifier = Modifier.statusBarsPadding(),
                )

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    ViewfinderFrame(
                        previewView = previewView,
                        uiState = uiState,
                        peakingBitmap = peakingBitmap,
                        livePortraitBitmap = livePortraitBitmap,
                        shutterBlackoutAlpha = shutterBlackoutAlpha.value,
                        onPreviewAttached = { isPreviewViewAttached = true },
                        onPinchZoom = onPinchZoom,
                        onTapToFocus = { x, y, w, h -> onTapToFocus(previewView, x, y, w, h) },
                        onLongPressAeAfLock = { x, y, w, h ->
                            onLongPressAeAfLock(previewView, x, y, w, h)
                        },
                        onUnlockAeAf = onUnlockAeAf,
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(3f / 4f, matchHeightConstraintsFirst = false),
                    )

                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 10.dp),
                    ) {
                        if (uiState.isTonePanelOpen || uiState.mode == CameraMode.PRO) {
                            ColorToneControlPanel(
                                colorTone = uiState.colorTone,
                                showSharpnessSlider = uiState.mode == CameraMode.PRO || uiState.isTonePanelOpen,
                                onLutSelected = onLutSelected,
                                onAwbToggle = onAwbToggle,
                                onKelvinChanged = onKelvinChanged,
                                onSharpnessChanged = onSharpnessChanged,
                            )
                        }
                        if (uiState.mode == CameraMode.PRO || uiState.focusReticle != null) {
                            ExposureCompensationBar(
                                exposureIndex = uiState.exposureIndex,
                                exposureRange = uiState.capabilities.exposureRange,
                                exposureStepEv = uiState.capabilities.exposureStepEv,
                                onExposureIndexChanged = onExposureIndexChanged,
                            )
                        }
                        if (uiState.mode == CameraMode.PORTRAIT) {
                            ApertureControlBar(
                                apertureFStop = uiState.apertureFStop,
                                bokehShape = uiState.bokehShape,
                                isHardwareApertureActive = uiState.isHardwareApertureActive,
                                onApertureChanged = onApertureChanged,
                                onBokehShapeToggle = onBokehShapeToggle,
                            )
                        }
                        ZoomControlStrip(
                            currentZoomRatio = uiState.zoomRatio,
                            minZoomRatio = uiState.capabilities.minZoomRatio,
                            maxZoomRatio = uiState.capabilities.maxZoomRatio,
                            onZoomSelected = onZoomSelected,
                        )
                        TelemetryStrip(uiState = uiState)
                    }
                }

                ShutterControlDeck(
                    latestCaptureUri = uiState.latestCaptureUri,
                    isCapturing = uiState.isCapturing,
                    isCameraReady = uiState.isCameraReady,
                    canSwitchCamera = uiState.capabilities.hasFrontCamera,
                    selectedMode = uiState.mode,
                    onModeSelected = onModeSelected,
                    onShutterClick = onShutterClick,
                    onGalleryClick = onGalleryClick,
                    onSwitchCameraClick = onSwitchCameraClick,
                    modifier = Modifier.navigationBarsPadding(),
                )
            }
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(top = 60.dp, start = 16.dp, end = 16.dp),
        )
    }
}

@Composable
private fun ViewfinderFrame(
    previewView: PreviewView,
    uiState: CameraUiState,
    peakingBitmap: ImageBitmap?,
    livePortraitBitmap: ImageBitmap?,
    shutterBlackoutAlpha: Float,
    onPreviewAttached: () -> Unit,
    onPinchZoom: (Float) -> Unit,
    onTapToFocus: (Float, Float, Float, Float) -> Unit,
    onLongPressAeAfLock: (Float, Float, Float, Float) -> Unit,
    onUnlockAeAf: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var frameSize by remember { mutableStateOf(IntSize.Zero) }
    val viewConfig = LocalViewConfiguration.current

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(18.dp))
            .background(Color.Black)
            .onSizeChanged { frameSize = it }
            .pointerInput(viewConfig) {
                awaitEachGesture {
                    val firstDown = awaitFirstDown(requireUnconsumed = false)
                    val downTime = SystemClock.uptimeMillis()
                    var lastPosition: Offset = firstDown.position
                    var isMultiTouch = false
                    var movedTooMuch = false

                    while (true) {
                        val event = awaitPointerEvent()
                        val pressedPointers = event.changes.count { it.pressed }
                        if (pressedPointers >= 2) {
                            isMultiTouch = true
                            val zoomChange = event.calculateZoom()
                            if (abs(zoomChange - 1f) > 0.004f) {
                                onPinchZoom(zoomChange)
                                event.changes.forEach { it.consume() }
                            }
                        } else if (pressedPointers == 1 && !isMultiTouch) {
                            val change = event.changes.firstOrNull { it.id == firstDown.id }
                            if (change != null) {
                                lastPosition = change.position
                                if ((change.position - firstDown.position).getDistance() > viewConfig.touchSlop) {
                                    movedTooMuch = true
                                }
                            }
                        }

                        if (event.changes.none { it.pressed }) {
                            break
                        }
                    }

                    if (!isMultiTouch && !movedTooMuch && frameSize.width > 0 && frameSize.height > 0) {
                        val elapsed = SystemClock.uptimeMillis() - downTime
                        val w = frameSize.width.toFloat()
                        val h = frameSize.height.toFloat()
                        if (elapsed >= viewConfig.longPressTimeoutMillis) {
                            onLongPressAeAfLock(lastPosition.x, lastPosition.y, w, h)
                        } else {
                            onTapToFocus(lastPosition.x, lastPosition.y, w, h)
                        }
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        val needsLiveAgslTone = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            (uiState.colorTone.lutPreset != LutPreset.NATURAL ||
                !uiState.colorTone.isAutoWhiteBalance ||
                uiState.colorTone.isHdrEnabled) &&
            (uiState.mode != CameraMode.PORTRAIT || uiState.isHardwareApertureActive || livePortraitBitmap == null)

        val liveAgslEffect = remember(needsLiveAgslTone, uiState.colorTone) {
            if (needsLiveAgslTone && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                LiveToneAgslEffect.createComposeRenderEffect(uiState.colorTone)
            } else {
                null
            }
        }

        AndroidView(
            factory = {
                onPreviewAttached()
                previewView
            },
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    renderEffect = liveAgslEffect
                }
                .testTag("camera_preview_view"),
        )

        if (uiState.mode == CameraMode.PORTRAIT && !uiState.isHardwareApertureActive) {
            LivePortraitOverlay(portraitBitmap = livePortraitBitmap)
        }

        if (uiState.isFocusPeakingEnabled) {
            FocusPeakingOverlay(peakingBitmap = peakingBitmap)
        }

        AnimatedVisibility(
            visible = uiState.isGridEnabled,
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            ViewfinderGridOverlay()
        }

        if (uiState.isFaceFocusEnabled) {
            FaceTrackingOverlay(faces = uiState.detectedFaces)
        }

        FocusReticleOverlay(reticle = uiState.focusReticle)

        AeAfLockChip(
            isLocked = uiState.isAeAfLocked,
            onUnlockClick = onUnlockAeAf,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 12.dp),
        )

        if (uiState.isProcessingPortrait) {
            Surface(
                color = GlassOverlay,
                shape = RoundedCornerShape(18.dp),
                border = BorderStroke(1.dp, TungstenAmber),
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 14.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                ) {
                    CircularProgressIndicator(
                        color = TungstenAmber,
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.processing_portrait),
                        style = MaterialTheme.typography.labelSmall,
                        color = OpticalWhite,
                    )
                }
            }
        }

        if (shutterBlackoutAlpha > 0.01f) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(ObsidianBlack.copy(alpha = shutterBlackoutAlpha)),
            )
        }

        CountdownOverlay(secondsRemaining = uiState.countdownRemainingSec)

        if (uiState.cameraErrorMessage != null) {
            Surface(
                color = GlassOverlay,
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.dp, AlertCrimson),
                modifier = Modifier.padding(20.dp),
            ) {
                Text(
                    text = uiState.cameraErrorMessage,
                    style = MaterialTheme.typography.bodyMedium,
                    color = OpticalWhite,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                )
            }
        }
    }
}

@Composable
private fun TelemetryStrip(
    uiState: CameraUiState,
    modifier: Modifier = Modifier,
) {
    val apertureText = if (uiState.mode == CameraMode.PORTRAIT) {
        String.format(Locale.US, "f/%.1f", uiState.apertureFStop)
    } else {
        val hw = uiState.capabilities.availableHardwareApertures.firstOrNull()
        if (hw != null) String.format(Locale.US, "f/%.1f", hw) else "f/1.8"
    }
    val formatText = if (uiState.isRawEnabled && uiState.capabilities.supportsRawDng) {
        "RAW+JPG"
    } else {
        "JPEG"
    }
    val zoomText = String.format(Locale.US, "%.1fx", uiState.zoomRatio)
    val wbText = if (uiState.colorTone.isAutoWhiteBalance) "AWB" else "${uiState.colorTone.kelvin}K"

    Surface(
        color = GlassOverlay,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, HairlineDivider),
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
            horizontalArrangement = Arrangement.spacedBy(9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = uiState.mode.label,
                style = MaterialTheme.typography.labelSmall,
                color = TungstenAmber,
            )
            Text(
                text = zoomText,
                style = MaterialTheme.typography.labelSmall,
                color = OpticalWhite,
            )
            Text(
                text = apertureText,
                style = MaterialTheme.typography.labelSmall,
                color = if (uiState.mode == CameraMode.PORTRAIT) TungstenAmber else OpticalWhite,
            )
            Text(
                text = wbText,
                style = MaterialTheme.typography.labelSmall,
                color = if (!uiState.colorTone.isAutoWhiteBalance) TungstenAmber else MutedSilver,
            )
            if (uiState.colorTone.lutPreset != LutPreset.NATURAL) {
                Text(
                    text = uiState.colorTone.lutPreset.label.uppercase(Locale.US),
                    style = MaterialTheme.typography.labelSmall,
                    color = TungstenAmber,
                )
            }
            if (uiState.colorTone.isHdrEnabled) {
                Text(
                    text = "HDR",
                    style = MaterialTheme.typography.labelSmall,
                    color = ThermalGoodGreen,
                )
            }
            Text(
                text = formatText,
                style = MaterialTheme.typography.labelSmall,
                color = if (uiState.isRawEnabled) TungstenAmber else MutedSilver,
            )
        }
    }
}
