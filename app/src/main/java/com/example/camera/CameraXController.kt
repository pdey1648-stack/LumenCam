package com.example.camera

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.RggbChannelVector
import android.net.Uri
import android.os.Build
import android.util.Range
import android.util.Size
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.core.ZoomState
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.Observer
import com.example.color.LutGenerator
import com.example.data.media.MediaStoreRepository
import com.example.domain.model.CameraHardwareCapabilities
import com.example.domain.model.ColorToneSettings
import com.example.domain.model.FlashMode
import com.example.domain.model.LensFacing
import com.example.domain.model.ThermalTier
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.abs

class CameraXController(
    private val context: Context,
    private val mediaStoreRepository: MediaStoreRepository,
) {
    private val captureExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val analysisExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    private var cameraProvider: ProcessCameraProvider? = null
    private var boundCamera: Camera? = null
    private var previewUseCase: Preview? = null
    private var imageCaptureUseCase: ImageCapture? = null
    private var imageAnalysisUseCase: ImageAnalysis? = null

    private var activeZoomObserver: Pair<CameraInfo, Observer<ZoomState>>? = null

    suspend fun bindSession(
        lifecycleOwner: LifecycleOwner,
        previewView: PreviewView,
        lensFacing: LensFacing,
        flashMode: FlashMode,
        isRawEnabled: Boolean,
        initialZoomRatio: Float,
        exposureIndex: Int,
        apertureFStop: Float,
        colorTone: ColorToneSettings,
        thermalTier: ThermalTier,
        analyzer: ViewfinderAnalyzer,
        onZoomStateChanged: (ZoomState) -> Unit,
    ): CameraHardwareCapabilities {
        val provider = getCameraProvider()
        val requestedSelector = CameraSelector.Builder()
            .requireLensFacing(lensFacing.selector)
            .build()

        val activeSelector = when {
            provider.hasCamera(requestedSelector) -> requestedSelector
            provider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA) -> CameraSelector.DEFAULT_BACK_CAMERA
            else -> CameraSelector.DEFAULT_FRONT_CAMERA
        }

        val targetInfo = provider.getCameraInfo(activeSelector)
        val capabilities = probeCapabilities(provider, targetInfo)

        val previewResolutionSelector = buildPreviewResolutionSelector(thermalTier)
        val captureResolutionSelector = ResolutionSelector.Builder()
            .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
            .setResolutionStrategy(ResolutionStrategy.HIGHEST_AVAILABLE_STRATEGY)
            .build()

        val analysisResolutionSelector = ResolutionSelector.Builder()
            .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
            .setResolutionStrategy(
                ResolutionStrategy(
                    Size(640, 480),
                    ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER,
                )
            )
            .build()

        val preview = Preview.Builder()
            .setResolutionSelector(previewResolutionSelector)
            .apply {
                applyPreviewCamera2Interop(this, targetInfo)
            }
            .build()
            .also {
                it.surfaceProvider = previewView.surfaceProvider
            }

        val effectiveRaw = isRawEnabled && capabilities.supportsRawDng
        val imageCapture = ImageCapture.Builder()
            .setResolutionSelector(captureResolutionSelector)
            .setFlashMode(flashMode.cameraXMode)
            .setCaptureMode(
                if (thermalTier >= ThermalTier.SEVERE) {
                    ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY
                } else {
                    ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY
                }
            )
            .setIoExecutor(captureExecutor)
            .apply {
                if (effectiveRaw) {
                    setOutputFormat(ImageCapture.OUTPUT_FORMAT_RAW_JPEG)
                } else {
                    setOutputFormat(ImageCapture.OUTPUT_FORMAT_JPEG)
                }
                applyStillCaptureCamera2Interop(this, targetInfo)
            }
            .build()

        analyzer.isFrontCamera = lensFacing == LensFacing.FRONT
        analyzer.thermalTier = thermalTier
        analyzer.colorTone = colorTone

        val imageAnalysis = ImageAnalysis.Builder()
            .setResolutionSelector(analysisResolutionSelector)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
            .build()
            .also {
                it.setAnalyzer(analysisExecutor, analyzer)
            }

        detachZoomObserver()
        provider.unbindAll()

        val camera = provider.bindToLifecycle(
            lifecycleOwner,
            activeSelector,
            preview,
            imageCapture,
            imageAnalysis,
        )
        boundCamera = camera
        previewUseCase = preview
        imageCaptureUseCase = imageCapture
        imageAnalysisUseCase = imageAnalysis

        val zoomObserver = Observer<ZoomState> { zoomState ->
            onZoomStateChanged(zoomState)
        }
        camera.cameraInfo.zoomState.observe(lifecycleOwner, zoomObserver)
        activeZoomObserver = camera.cameraInfo to zoomObserver

        val clampedZoom = initialZoomRatio.coerceIn(
            capabilities.minZoomRatio,
            capabilities.maxZoomRatio,
        )
        if (clampedZoom != 1f) {
            camera.cameraControl.setZoomRatio(clampedZoom)
        }
        if (exposureIndex in capabilities.exposureRange && exposureIndex != 0) {
            camera.cameraControl.setExposureCompensationIndex(exposureIndex)
        }
        applyCamera2ToneAndAperture(capabilities, colorTone, apertureFStop)

        return capabilities
    }

    @OptIn(ExperimentalCamera2Interop::class)
    fun applyCamera2ToneAndAperture(
        capabilities: CameraHardwareCapabilities,
        colorTone: ColorToneSettings,
        apertureFStop: Float,
    ) {
        val camera = boundCamera ?: return
        val c2Control = Camera2CameraControl.from(camera.cameraControl)
        val builder = CaptureRequestOptions.Builder()

        if (capabilities.supportsVariableHardwareAperture) {
            val nearest = capabilities.availableHardwareApertures
                .minByOrNull { abs(it - apertureFStop) }
            if (nearest != null) {
                builder.setCaptureRequestOption(CaptureRequest.LENS_APERTURE, nearest)
            }
        }

        if (capabilities.supportsManualAwbGains && !colorTone.isAutoWhiteBalance) {
            val gains = LutGenerator.kelvinToHardwareAwbGains(colorTone.kelvin)
            builder.setCaptureRequestOption(
                CaptureRequest.CONTROL_AWB_MODE,
                CameraMetadata.CONTROL_AWB_MODE_OFF,
            )
            builder.setCaptureRequestOption(
                CaptureRequest.COLOR_CORRECTION_MODE,
                CameraMetadata.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX,
            )
            builder.setCaptureRequestOption(
                CaptureRequest.COLOR_CORRECTION_GAINS,
                RggbChannelVector(gains[0], gains[1], gains[2], gains[3]),
            )
        } else {
            builder.setCaptureRequestOption(
                CaptureRequest.CONTROL_AWB_MODE,
                CameraMetadata.CONTROL_AWB_MODE_AUTO,
            )
            builder.setCaptureRequestOption(
                CaptureRequest.COLOR_CORRECTION_MODE,
                CameraMetadata.COLOR_CORRECTION_MODE_FAST,
            )
        }

        if (colorTone.isHdrEnabled && capabilities.supportsHardwareHdrScene) {
            builder.setCaptureRequestOption(
                CaptureRequest.CONTROL_MODE,
                CameraMetadata.CONTROL_MODE_USE_SCENE_MODE,
            )
            builder.setCaptureRequestOption(
                CaptureRequest.CONTROL_SCENE_MODE,
                CameraMetadata.CONTROL_SCENE_MODE_HDR,
            )
        } else {
            builder.setCaptureRequestOption(
                CaptureRequest.CONTROL_MODE,
                CameraMetadata.CONTROL_MODE_AUTO,
            )
        }

        c2Control.setCaptureRequestOptions(builder.build())
    }

    fun setZoomRatio(ratio: Float) {
        val camera = boundCamera ?: return
        val zoomState = camera.cameraInfo.zoomState.value
        val minZoom = zoomState?.minZoomRatio ?: 1f
        val maxZoom = zoomState?.maxZoomRatio ?: 1f
        camera.cameraControl.setZoomRatio(ratio.coerceIn(minZoom, maxZoom))
    }

    fun setExposureCompensationIndex(index: Int) {
        val camera = boundCamera ?: return
        val range = camera.cameraInfo.exposureState.exposureCompensationRange
        if (index in range.lower..range.upper) {
            camera.cameraControl.setExposureCompensationIndex(index)
        }
    }

    fun triggerFocusAndMetering(
        previewView: PreviewView,
        xPx: Float,
        yPx: Float,
        lockAeAf: Boolean,
        onResult: (Boolean) -> Unit = {},
    ) {
        val camera = boundCamera ?: return
        val point = previewView.meteringPointFactory.createPoint(xPx, yPx)
        val flags = FocusMeteringAction.FLAG_AF or
            FocusMeteringAction.FLAG_AE or
            FocusMeteringAction.FLAG_AWB
        val builder = FocusMeteringAction.Builder(point, flags)
        if (lockAeAf) {
            builder.disableAutoCancel()
        } else {
            builder.setAutoCancelDuration(4, TimeUnit.SECONDS)
        }
        val future = camera.cameraControl.startFocusAndMetering(builder.build())
        future.addListener(
            {
                val focused = runCatching { future.get().isFocusSuccessful }.getOrDefault(false)
                onResult(focused)
            },
            ContextCompat.getMainExecutor(context),
        )
    }

    fun cancelFocusAndMetering() {
        boundCamera?.cameraControl?.cancelFocusAndMetering()
    }

    fun updateFlashMode(flashMode: FlashMode) {
        imageCaptureUseCase?.flashMode = flashMode.cameraXMode
    }

    fun capturePhoto(
        isFrontCamera: Boolean,
        isRawEnabled: Boolean,
        capabilities: CameraHardwareCapabilities,
        onSaved: (savedUri: Uri?, baseName: String, savedRaw: Boolean) -> Unit,
        onError: (Throwable) -> Unit,
    ) {
        val imageCapture = imageCaptureUseCase ?: run {
            onError(IllegalStateException("Camera capture session is not initialized."))
            return
        }

        val baseName = mediaStoreRepository.generateCaptureBaseName()
        val jpegOptions = mediaStoreRepository.createJpegOutputOptions(
            baseName = baseName,
            isFrontCamera = isFrontCamera,
        )
        val useRawPair = isRawEnabled &&
            capabilities.supportsRawDng &&
            imageCapture.outputFormat == ImageCapture.OUTPUT_FORMAT_RAW_JPEG

        val callback = object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                onSaved(outputFileResults.savedUri, baseName, useRawPair)
            }

            override fun onError(exception: ImageCaptureException) {
                onError(exception)
            }
        }

        if (useRawPair) {
            val rawOptions = mediaStoreRepository.createRawOutputOptions(baseName)
            imageCapture.takePicture(
                rawOptions,
                jpegOptions,
                captureExecutor,
                callback,
            )
        } else {
            imageCapture.takePicture(
                jpegOptions,
                captureExecutor,
                callback,
            )
        }
    }

    private fun detachZoomObserver() {
        activeZoomObserver?.let { (info, observer) ->
            info.zoomState.removeObserver(observer)
        }
        activeZoomObserver = null
    }

    fun unbindAll() {
        detachZoomObserver()
        imageAnalysisUseCase?.clearAnalyzer()
        cameraProvider?.unbindAll()
        boundCamera = null
        previewUseCase = null
        imageCaptureUseCase = null
        imageAnalysisUseCase = null
    }

    fun release() {
        unbindAll()
        captureExecutor.shutdown()
        analysisExecutor.shutdown()
    }

    private suspend fun getCameraProvider(): ProcessCameraProvider {
        cameraProvider?.let { return it }
        return suspendCancellableCoroutine { continuation ->
            val future = ProcessCameraProvider.getInstance(context)
            future.addListener(
                {
                    try {
                        val provider = future.get()
                        cameraProvider = provider
                        continuation.resume(provider)
                    } catch (t: Throwable) {
                        continuation.resumeWithException(t)
                    }
                },
                ContextCompat.getMainExecutor(context),
            )
        }
    }

    private fun buildPreviewResolutionSelector(thermalTier: ThermalTier): ResolutionSelector {
        val targetSize = when (thermalTier) {
            ThermalTier.NORMAL -> Size(1920, 1440)
            ThermalTier.MODERATE -> Size(1440, 1080)
            ThermalTier.SEVERE, ThermalTier.CRITICAL -> Size(1280, 960)
        }
        return ResolutionSelector.Builder()
            .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
            .setResolutionStrategy(
                ResolutionStrategy(
                    targetSize,
                    ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,
                )
            )
            .build()
    }

    @OptIn(ExperimentalCamera2Interop::class)
    private fun probeCapabilities(
        provider: ProcessCameraProvider,
        cameraInfo: CameraInfo,
    ): CameraHardwareCapabilities {
        val camera2Info = Camera2CameraInfo.from(cameraInfo)
        val apertures = camera2Info
            .getCameraCharacteristic(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES)
            ?.toList()
            ?: emptyList()

        val focalLengths = camera2Info
            .getCameraCharacteristic(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
            ?.toList()
            ?: emptyList()

        val sceneModes = camera2Info
            .getCameraCharacteristic(CameraCharacteristics.CONTROL_AVAILABLE_SCENE_MODES)
            ?: intArrayOf()
        val supportsHdrScene = CameraMetadata.CONTROL_SCENE_MODE_HDR in sceneModes

        val awbModes = camera2Info
            .getCameraCharacteristic(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES)
            ?: intArrayOf()
        val supportsManualAwb = CameraMetadata.CONTROL_AWB_MODE_OFF in awbModes

        val physicalCount = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            cameraInfo.physicalCameraInfos.size.coerceAtLeast(1)
        } else {
            1
        }

        val imageCaptureCaps = ImageCapture.getImageCaptureCapabilities(cameraInfo)
        val supportsRaw = ImageCapture.OUTPUT_FORMAT_RAW_JPEG in imageCaptureCaps.supportedOutputFormats

        val zoomState = cameraInfo.zoomState.value
        val hasFront = runCatching {
            provider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA)
        }.getOrDefault(false)

        val expState = cameraInfo.exposureState
        val expRange = if (expState.isExposureCompensationSupported) {
            expState.exposureCompensationRange.lower..expState.exposureCompensationRange.upper
        } else {
            0..0
        }

        return CameraHardwareCapabilities(
            hasFlashUnit = cameraInfo.hasFlashUnit(),
            supportsRawDng = supportsRaw,
            supportsHardwareHdrScene = supportsHdrScene,
            supportsManualAwbGains = supportsManualAwb,
            availableHardwareApertures = apertures,
            minZoomRatio = zoomState?.minZoomRatio ?: 1f,
            maxZoomRatio = zoomState?.maxZoomRatio ?: 1f,
            hasFrontCamera = hasFront,
            exposureRange = expRange,
            exposureStepEv = expState.exposureCompensationStep.toFloat(),
            availableFocalLengths = focalLengths,
            physicalCameraCount = physicalCount,
        )
    }

    @OptIn(ExperimentalCamera2Interop::class)
    private fun applyPreviewCamera2Interop(
        builder: Preview.Builder,
        cameraInfo: CameraInfo,
    ) {
        val extender = Camera2Interop.Extender(builder)
        val camera2Info = Camera2CameraInfo.from(cameraInfo)

        val fpsRanges = camera2Info.getCameraCharacteristic(
            CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES
        )
        val preferredRange = selectBalanced30FpsRange(fpsRanges)
        if (preferredRange != null) {
            extender.setCaptureRequestOption(
                CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
                preferredRange,
            )
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val useCases = camera2Info.getCameraCharacteristic(
                CameraCharacteristics.SCALER_AVAILABLE_STREAM_USE_CASES
            )
            if (useCases?.contains(CameraMetadata.SCALER_AVAILABLE_STREAM_USE_CASES_PREVIEW.toLong()) == true) {
                extender.setStreamUseCase(
                    CameraMetadata.SCALER_AVAILABLE_STREAM_USE_CASES_PREVIEW.toLong()
                )
            }
        }
    }

    @OptIn(ExperimentalCamera2Interop::class)
    private fun applyStillCaptureCamera2Interop(
        builder: ImageCapture.Builder,
        cameraInfo: CameraInfo,
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val camera2Info = Camera2CameraInfo.from(cameraInfo)
            val useCases = camera2Info.getCameraCharacteristic(
                CameraCharacteristics.SCALER_AVAILABLE_STREAM_USE_CASES
            )
            if (useCases?.contains(CameraMetadata.SCALER_AVAILABLE_STREAM_USE_CASES_STILL_CAPTURE.toLong()) == true) {
                Camera2Interop.Extender(builder).setStreamUseCase(
                    CameraMetadata.SCALER_AVAILABLE_STREAM_USE_CASES_STILL_CAPTURE.toLong()
                )
            }
        }
    }

    private fun selectBalanced30FpsRange(ranges: Array<Range<Int>>?): Range<Int>? {
        if (ranges.isNullOrEmpty()) return null
        return ranges.firstOrNull { it.lower == 30 && it.upper == 30 }
            ?: ranges.filter { it.upper <= 30 }.maxByOrNull { it.upper * 100 + it.lower }
    }
}
