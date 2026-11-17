package com.example.camera

import android.graphics.Bitmap
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.example.domain.model.BokehShape
import com.example.domain.model.NormalizedRect
import com.example.domain.model.ThermalTier
import com.example.portrait.PortraitPipeline
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetector
import com.google.mlkit.vision.face.FaceDetectorOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

class ViewfinderAnalyzer(
    private val portraitPipeline: PortraitPipeline,
    private val onPeakingFrame: (ImageBitmap?) -> Unit,
    private val onPortraitPreviewFrame: (ImageBitmap?) -> Unit,
    private val onFacesUpdated: (List<NormalizedRect>) -> Unit,
    private val onAutoFocusFace: (normX: Float, normY: Float) -> Unit,
) : ImageAnalysis.Analyzer {

    private val analyzerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val isPortraitFrameInFlight = AtomicBoolean(false)

    @Volatile
    var isFocusPeakingEnabled: Boolean = false
        set(value) {
            field = value
            if (!value) {
                onPeakingFrame(null)
            }
        }

    @Volatile
    var isFaceFocusEnabled: Boolean = true
        set(value) {
            field = value
            if (!value) {
                onFacesUpdated(emptyList())
            }
        }

    @Volatile
    var isPortraitBokehEnabled: Boolean = false
        set(value) {
            field = value
            if (!value) {
                onPortraitPreviewFrame(null)
            }
        }

    @Volatile
    var apertureFStop: Float = 1.8f

    @Volatile
    var zoomRatio: Float = 1f

    @Volatile
    var portraitFocusX: Float = 0.5f

    @Volatile
    var portraitFocusY: Float = 0.42f

    @Volatile
    var bokehShape: BokehShape = BokehShape.HEXAGON

    @Volatile
    var colorTone: com.example.domain.model.ColorToneSettings =
        com.example.domain.model.ColorToneSettings()

    @Volatile
    var isFrontCamera: Boolean = false

    @Volatile
    var isAeAfLocked: Boolean = false

    @Volatile
    var thermalTier: ThermalTier = ThermalTier.NORMAL

    @Volatile
    private var latestFaces: List<NormalizedRect> = emptyList()

    private val faceDetector: FaceDetector by lazy {
        val options = FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setContourMode(FaceDetectorOptions.CONTOUR_MODE_NONE)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_NONE)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
            .setMinFaceSize(0.12f)
            .build()
        FaceDetection.getClient(options)
    }

    private var lastPeakingTimestampMs = 0L
    private var lastFaceDetectTimestampMs = 0L
    private var lastAutoFocusTimestampMs = 0L
    private var lastPortraitTimestampMs = 0L
    private var lastFocusedFaceCenter: Pair<Float, Float>? = null

    private var peakingBufferA: Bitmap? = null
    private var peakingBufferB: Bitmap? = null
    private var useBufferA = true
    private var peakingPixels: IntArray = IntArray(0)

    private var portraitPreviewBuffer: Bitmap? = null

    @OptIn(ExperimentalGetImage::class)
    override fun analyze(imageProxy: ImageProxy) {
        val now = SystemClock.elapsedRealtime()
        val runPeaking = isFocusPeakingEnabled &&
            thermalTier < ThermalTier.CRITICAL &&
            (now - lastPeakingTimestampMs >= 66L)

        if (runPeaking) {
            lastPeakingTimestampMs = now
            computeFocusPeaking(imageProxy)
        }

        val portraitIntervalMs = when (thermalTier) {
            ThermalTier.NORMAL -> 48L
            ThermalTier.MODERATE -> 100L
            ThermalTier.SEVERE -> 200L
            ThermalTier.CRITICAL -> Long.MAX_VALUE
        }
        val runPortrait = isPortraitBokehEnabled &&
            thermalTier < ThermalTier.CRITICAL &&
            (now - lastPortraitTimestampMs >= portraitIntervalMs) &&
            isPortraitFrameInFlight.compareAndSet(false, true)

        if (runPortrait) {
            lastPortraitTimestampMs = now
            val targetEdge = if (thermalTier >= ThermalTier.MODERATE) 224 else 288
            val snapshotFStop = apertureFStop
            val snapshotZoom = zoomRatio
            val snapshotFx = portraitFocusX
            val snapshotFy = portraitFocusY
            val snapshotShape = bokehShape
            val snapshotTone = colorTone
            val snapshotFaces = latestFaces
            val snapshotFront = isFrontCamera

            analyzerScope.launch {
                try {
                    val rendered = portraitPipeline.renderLivePreview(
                        imageProxy = imageProxy,
                        fNumber = snapshotFStop,
                        zoomRatio = snapshotZoom,
                        focusX = snapshotFx,
                        focusY = snapshotFy,
                        bokehShape = snapshotShape,
                        colorTone = snapshotTone,
                        detectedFaces = snapshotFaces,
                        isFrontCamera = snapshotFront,
                        targetLongEdge = targetEdge,
                    )
                    if (rendered != null && isPortraitBokehEnabled) {
                        val old = portraitPreviewBuffer
                        portraitPreviewBuffer = rendered
                        onPortraitPreviewFrame(rendered.asImageBitmap())
                        if (old != null && old !== rendered && !old.isRecycled) {
                            old.recycle()
                        }
                    } else {
                        rendered?.recycle()
                    }
                } finally {
                    isPortraitFrameInFlight.set(false)
                    imageProxy.close()
                }
            }
            return
        }

        val faceIntervalMs = if (thermalTier >= ThermalTier.MODERATE) 550L else 280L
        val runFaceDetect = isFaceFocusEnabled &&
            thermalTier < ThermalTier.SEVERE &&
            (now - lastFaceDetectTimestampMs >= faceIntervalMs)

        val mediaImage = imageProxy.image
        if (runFaceDetect && mediaImage != null) {
            lastFaceDetectTimestampMs = now
            val rotation = imageProxy.imageInfo.rotationDegrees
            val frameWidth = imageProxy.width
            val frameHeight = imageProxy.height
            val inputImage = InputImage.fromMediaImage(mediaImage, rotation)

            faceDetector.process(inputImage)
                .addOnSuccessListener { faces ->
                    val uprightWidth = if (rotation == 90 || rotation == 270) frameHeight else frameWidth
                    val uprightHeight = if (rotation == 90 || rotation == 270) frameWidth else frameHeight

                    if (uprightWidth <= 0 || uprightHeight <= 0) {
                        latestFaces = emptyList()
                        onFacesUpdated(emptyList())
                        return@addOnSuccessListener
                    }

                    val normalized = faces.map { face ->
                        val box = face.boundingBox
                        val rawLeft = (box.left.toFloat() / uprightWidth).coerceIn(0f, 1f)
                        val rawRight = (box.right.toFloat() / uprightWidth).coerceIn(0f, 1f)
                        val top = (box.top.toFloat() / uprightHeight).coerceIn(0f, 1f)
                        val bottom = (box.bottom.toFloat() / uprightHeight).coerceIn(0f, 1f)

                        if (isFrontCamera) {
                            NormalizedRect(
                                left = 1f - rawRight,
                                top = top,
                                right = 1f - rawLeft,
                                bottom = bottom,
                            )
                        } else {
                            NormalizedRect(
                                left = rawLeft,
                                top = top,
                                right = rawRight,
                                bottom = bottom,
                            )
                        }
                    }

                    latestFaces = normalized
                    onFacesUpdated(normalized)

                    val primary = normalized.maxByOrNull {
                        (it.right - it.left) * (it.bottom - it.top)
                    }
                    if (primary != null && !isAeAfLocked) {
                        val currentTime = SystemClock.elapsedRealtime()
                        val prev = lastFocusedFaceCenter
                        val movedSignificantly = prev == null ||
                            abs(primary.centerX - prev.first) > 0.08f ||
                            abs(primary.centerY - prev.second) > 0.08f
                        if (movedSignificantly && currentTime - lastAutoFocusTimestampMs >= 1400L) {
                            lastAutoFocusTimestampMs = currentTime
                            lastFocusedFaceCenter = primary.centerX to primary.centerY
                            onAutoFocusFace(primary.centerX, primary.centerY)
                        }
                    } else if (normalized.isEmpty()) {
                        lastFocusedFaceCenter = null
                    }
                }
                .addOnCompleteListener {
                    imageProxy.close()
                }
        } else {
            imageProxy.close()
        }
    }

    private fun computeFocusPeaking(imageProxy: ImageProxy) {
        val yPlane = imageProxy.planes.firstOrNull() ?: return
        val yBuffer = yPlane.buffer
        val rowStride = yPlane.rowStride
        val pixelStride = yPlane.pixelStride
        val srcW = imageProxy.width
        val srcH = imageProxy.height
        val step = 4
        val dsW = srcW / step
        val dsH = srcH / step
        if (dsW < 4 || dsH < 4) return

        val rotation = imageProxy.imageInfo.rotationDegrees
        val outW = if (rotation == 90 || rotation == 270) dsH else dsW
        val outH = if (rotation == 90 || rotation == 270) dsW else dsH

        val neededSize = outW * outH
        if (peakingPixels.size != neededSize) {
            peakingPixels = IntArray(neededSize)
        } else {
            peakingPixels.fill(0)
        }

        val peakColor = 0xD938BDF8.toInt()
        val threshold = 115

        val yCapacity = yBuffer.capacity()
        for (y in 1 until dsH - 1) {
            val sy = y * step
            val rowPrev = (sy - step) * rowStride
            val rowCurr = sy * rowStride
            val rowNext = (sy + step) * rowStride

            for (x in 1 until dsW - 1) {
                val sx = x * step
                val idxTL = rowPrev + (sx - step) * pixelStride
                val idxTC = rowPrev + sx * pixelStride
                val idxTR = rowPrev + (sx + step) * pixelStride
                val idxML = rowCurr + (sx - step) * pixelStride
                val idxMR = rowCurr + (sx + step) * pixelStride
                val idxBL = rowNext + (sx - step) * pixelStride
                val idxBC = rowNext + sx * pixelStride
                val idxBR = rowNext + (sx + step) * pixelStride

                if (idxBR >= yCapacity) continue

                val tl = yBuffer.get(idxTL).toInt() and 0xFF
                val tc = yBuffer.get(idxTC).toInt() and 0xFF
                val tr = yBuffer.get(idxTR).toInt() and 0xFF
                val ml = yBuffer.get(idxML).toInt() and 0xFF
                val mr = yBuffer.get(idxMR).toInt() and 0xFF
                val bl = yBuffer.get(idxBL).toInt() and 0xFF
                val bc = yBuffer.get(idxBC).toInt() and 0xFF
                val br = yBuffer.get(idxBR).toInt() and 0xFF

                val gx = -tl + tr - 2 * ml + 2 * mr - bl + br
                val gy = -tl - 2 * tc - tr + bl + 2 * bc + br
                val mag = abs(gx) + abs(gy)

                if (mag > threshold) {
                    val (ox, oy) = mapRotatedCoord(x, y, dsW, dsH, rotation, isFrontCamera)
                    if (ox in 0 until outW && oy in 0 until outH) {
                        peakingPixels[oy * outW + ox] = peakColor
                    }
                }
            }
        }

        val targetBitmap = obtainPeakingBitmap(outW, outH)
        targetBitmap.setPixels(peakingPixels, 0, outW, 0, 0, outW, outH)
        onPeakingFrame(targetBitmap.asImageBitmap())
    }

    private fun mapRotatedCoord(
        x: Int,
        y: Int,
        srcW: Int,
        srcH: Int,
        rotation: Int,
        mirrorX: Boolean,
    ): Pair<Int, Int> {
        val (rx, ry, outW) = when (rotation) {
            90 -> Triple(srcH - 1 - y, x, srcH)
            180 -> Triple(srcW - 1 - x, srcH - 1 - y, srcW)
            270 -> Triple(y, srcW - 1 - x, srcH)
            else -> Triple(x, y, srcW)
        }
        val finalX = if (mirrorX) (outW - 1 - rx) else rx
        return finalX to ry
    }

    private fun obtainPeakingBitmap(width: Int, height: Int): Bitmap {
        val current = if (useBufferA) peakingBufferA else peakingBufferB
        val valid = if (current != null && !current.isRecycled && current.width == width && current.height == height) {
            current
        } else {
            current?.recycle()
            Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        }
        if (useBufferA) {
            peakingBufferA = valid
        } else {
            peakingBufferB = valid
        }
        useBufferA = !useBufferA
        return valid
    }

    fun release() {
        analyzerScope.cancel()
        runCatching { faceDetector.close() }
        peakingBufferA?.recycle()
        peakingBufferB?.recycle()
        portraitPreviewBuffer?.recycle()
        peakingBufferA = null
        peakingBufferB = null
        portraitPreviewBuffer = null
    }
}
