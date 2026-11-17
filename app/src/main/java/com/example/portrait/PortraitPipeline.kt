package com.example.portrait

import android.app.ActivityManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.camera.core.ImageProxy
import androidx.exifinterface.media.ExifInterface
import com.example.color.LutGenerator
import com.example.data.media.MediaStoreRepository
import com.example.domain.model.BokehShape
import com.example.domain.model.ColorToneSettings
import com.example.domain.model.LutPreset
import com.example.domain.model.NormalizedRect
import com.example.domain.model.PortraitSidecarInfo
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.Segmentation
import com.google.mlkit.vision.segmentation.Segmenter
import com.google.mlkit.vision.segmentation.selfie.SelfieSegmenterOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import kotlin.coroutines.resume
import kotlin.math.max

class PortraitPipeline(
    private val context: Context,
    private val mediaStoreRepository: MediaStoreRepository,
) {
    private val previewRenderer = BokehGlRenderer()
    private val captureRenderer = BokehGlRenderer()

    private val streamSegmenter: Segmenter by lazy {
        val opts = SelfieSegmenterOptions.Builder()
            .setDetectorMode(SelfieSegmenterOptions.STREAM_MODE)
            .build()
        Segmentation.getClient(opts)
    }

    private val singleSegmenter: Segmenter by lazy {
        val opts = SelfieSegmenterOptions.Builder()
            .setDetectorMode(SelfieSegmenterOptions.SINGLE_IMAGE_MODE)
            .build()
        Segmentation.getClient(opts)
    }

    private var reusablePreviewPixels = IntArray(0)

    suspend fun renderLivePreview(
        imageProxy: ImageProxy,
        fNumber: Float,
        zoomRatio: Float,
        focusX: Float,
        focusY: Float,
        bokehShape: BokehShape,
        colorTone: ColorToneSettings,
        detectedFaces: List<NormalizedRect>,
        isFrontCamera: Boolean,
        targetLongEdge: Int = 288,
    ): Bitmap? {
        val smallRgb = yuvToDownscaledOrientedBitmap(
            imageProxy = imageProxy,
            targetLongEdge = targetLongEdge,
            mirrorHorizontal = isFrontCamera,
        ) ?: return null

        var matteDepth: Bitmap? = null
        return try {
            val maskResult = runSegmenter(streamSegmenter, smallRgb)
            matteDepth = GuidedFilter.buildMatteDepthBitmap(
                guideBitmap = smallRgb,
                rawPersonMask = maskResult?.first,
                maskWidth = maskResult?.second ?: 0,
                maskHeight = maskResult?.third ?: 0,
                focusX = focusX,
                focusY = focusY,
                detectedFaces = detectedFaces,
                filterRadius = 5,
            )
            previewRenderer.renderBokehAndTone(
                sourceBitmap = smallRgb,
                matteDepthBitmap = matteDepth,
                fNumber = fNumber,
                zoomRatio = zoomRatio,
                focusX = focusX,
                focusY = focusY,
                bokehShape = bokehShape,
                colorTone = colorTone,
                applyBokeh = true,
                denoiseStrength = 0.25f,
            )
        } finally {
            matteDepth?.recycle()
            smallRgb.recycle()
        }
    }

    suspend fun processFullResPortraitCapture(
        capturedUri: Uri,
        baseName: String,
        fNumber: Float,
        zoomRatio: Float,
        focusX: Float,
        focusY: Float,
        bokehShape: BokehShape,
        colorTone: ColorToneSettings,
        detectedFaces: List<NormalizedRect>,
    ): PortraitSidecarInfo? = withContext(Dispatchers.Default) {
        var originalBitmap: Bitmap? = null
        var analysisBitmap: Bitmap? = null
        var matteDepthSmall: Bitmap? = null
        var renderedPortrait: Bitmap? = null

        try {
            val captureLongEdge = chooseCaptureMaxLongEdge()
            originalBitmap = decodeOrientedBitmapFromUri(capturedUri, maxLongEdge = captureLongEdge)
                ?: return@withContext null

            val sidecarDir = File(context.filesDir, "portrait_sidecars").apply { mkdirs() }
            pruneOldInternalSidecarsIfNeeded(sidecarDir, maxTotalBytes = 512L * 1024 * 1024)
            val origFile = File(sidecarDir, "${baseName}_orig.jpg")
            val depthFile = File(sidecarDir, "${baseName}_depth.png")

            FileOutputStream(origFile).use { out ->
                originalBitmap.compress(Bitmap.CompressFormat.JPEG, 92, out)
            }

            mediaStoreRepository.saveBitmapToMediaStore(
                bitmap = originalBitmap,
                displayName = "${baseName}_ORIG.jpg",
                mimeType = "image/jpeg",
                format = Bitmap.CompressFormat.JPEG,
                quality = 94,
            )

            analysisBitmap = scaleBitmapMaxEdge(originalBitmap, maxEdge = 640)
            val maskResult = runSegmenter(singleSegmenter, analysisBitmap)
            matteDepthSmall = GuidedFilter.buildMatteDepthBitmap(
                guideBitmap = analysisBitmap,
                rawPersonMask = maskResult?.first,
                maskWidth = maskResult?.second ?: 0,
                maskHeight = maskResult?.third ?: 0,
                focusX = focusX,
                focusY = focusY,
                detectedFaces = detectedFaces,
                filterRadius = 10,
            )
            // Free 640px guide bitmap immediately before allocating GL readback buffer
            analysisBitmap.recycle()
            analysisBitmap = null

            FileOutputStream(depthFile).use { out ->
                matteDepthSmall.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            mediaStoreRepository.saveBitmapToMediaStore(
                bitmap = matteDepthSmall,
                displayName = "${baseName}_DEPTH.png",
                mimeType = "image/png",
                format = Bitmap.CompressFormat.PNG,
                quality = 100,
            )

            // Upload 640px matte/depth directly; BokehGlRenderer upsamples smoothly via GL_LINEAR (~38MB RAM saved)
            renderedPortrait = captureRenderer.renderBokehAndTone(
                sourceBitmap = originalBitmap,
                matteDepthBitmap = matteDepthSmall,
                fNumber = fNumber,
                zoomRatio = zoomRatio,
                focusX = focusX,
                focusY = focusY,
                bokehShape = bokehShape,
                colorTone = colorTone,
                applyBokeh = true,
                denoiseStrength = 0.45f,
            )

            context.contentResolver.openOutputStream(capturedUri, "wt")?.use { out ->
                renderedPortrait.compress(Bitmap.CompressFormat.JPEG, 93, out)
            }

            val sidecar = PortraitSidecarInfo(
                baseName = baseName,
                originalPath = origFile.absolutePath,
                matteDepthPath = depthFile.absolutePath,
                apertureFStop = fNumber,
                focusX = focusX,
                focusY = focusY,
                bokehShape = bokehShape,
                zoomRatio = zoomRatio,
                lutPreset = colorTone.lutPreset,
            )
            mediaStoreRepository.savePortraitSidecarMetadata(sidecar)
            sidecar
        } finally {
            renderedPortrait?.recycle()
            matteDepthSmall?.recycle()
            analysisBitmap?.recycle()
            originalBitmap?.recycle()
        }
    }

    /**
     * Applies Noise Reduction -> Subject/Center Unsharp Mask -> Kelvin WB -> HDR ->
     * Skin-Tone Protection -> 3D LUT grading for Photo / Pro captures.
     */
    suspend fun processStandardPhotoToneCapture(
        capturedUri: Uri,
        colorTone: ColorToneSettings,
    ) = withContext(Dispatchers.Default) {
        var originalBitmap: Bitmap? = null
        var rendered: Bitmap? = null
        try {
            originalBitmap = decodeOrientedBitmapFromUri(capturedUri, maxLongEdge = chooseCaptureMaxLongEdge())
                ?: return@withContext
            rendered = captureRenderer.renderBokehAndTone(
                sourceBitmap = originalBitmap,
                matteDepthBitmap = null,
                fNumber = 16.0f,
                zoomRatio = 1.0f,
                focusX = 0.5f,
                focusY = 0.5f,
                bokehShape = BokehShape.DISC,
                colorTone = colorTone,
                applyBokeh = false,
                denoiseStrength = 0.35f,
            )
            context.contentResolver.openOutputStream(capturedUri, "wt")?.use { out ->
                rendered.compress(Bitmap.CompressFormat.JPEG, 94, out)
            }
        } finally {
            rendered?.recycle()
            originalBitmap?.recycle()
        }
    }

    suspend fun renderSidecarEditPreview(
        sidecar: PortraitSidecarInfo,
        newFStop: Float,
        newFocusX: Float,
        newFocusY: Float,
        newBokehShape: BokehShape,
        lutPreset: LutPreset,
        maxLongEdge: Int = 960,
    ): Bitmap? = withContext(Dispatchers.Default) {
        var origBitmap: Bitmap? = null
        var depthBitmapRaw: Bitmap? = null
        try {
            origBitmap = decodeFileCapped(sidecar.originalPath, maxLongEdge) ?: return@withContext null
            depthBitmapRaw = BitmapFactory.decodeFile(sidecar.matteDepthPath) ?: return@withContext null

            captureRenderer.renderBokehAndTone(
                sourceBitmap = origBitmap,
                matteDepthBitmap = depthBitmapRaw,
                fNumber = newFStop,
                zoomRatio = sidecar.zoomRatio,
                focusX = newFocusX,
                focusY = newFocusY,
                bokehShape = newBokehShape,
                colorTone = ColorToneSettings(lutPreset = lutPreset),
                applyBokeh = true,
                denoiseStrength = 0.30f,
            )
        } finally {
            depthBitmapRaw?.recycle()
            origBitmap?.recycle()
        }
    }

    suspend fun saveEditedPortraitCopy(
        sidecar: PortraitSidecarInfo,
        newFStop: Float,
        newFocusX: Float,
        newFocusY: Float,
        newBokehShape: BokehShape,
        lutPreset: LutPreset,
    ): Uri? = withContext(Dispatchers.Default) {
        val edited = renderSidecarEditPreview(
            sidecar = sidecar,
            newFStop = newFStop,
            newFocusX = newFocusX,
            newFocusY = newFocusY,
            newBokehShape = newBokehShape,
            lutPreset = lutPreset,
            maxLongEdge = chooseCaptureMaxLongEdge(),
        ) ?: return@withContext null

        try {
            val newName = "${mediaStoreRepository.generateCaptureBaseName()}_EDIT.jpg"
            mediaStoreRepository.saveBitmapToMediaStore(
                bitmap = edited,
                displayName = newName,
                mimeType = "image/jpeg",
                format = Bitmap.CompressFormat.JPEG,
                quality = 94,
            )
        } finally {
            edited.recycle()
        }
    }

    private fun chooseCaptureMaxLongEdge(): Int {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            ?: return 2560
        val info = ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        val availMb = info.availMem / (1024L * 1024L)
        val totalGb = info.totalMem / (1024.0 * 1024.0 * 1024.0)
        return when {
            info.lowMemory || availMb < 650L -> 2048
            totalGb <= 4.5 -> 2560
            else -> 3072
        }
    }

    private fun pruneOldInternalSidecarsIfNeeded(sidecarDir: File, maxTotalBytes: Long) {
        val files = sidecarDir.listFiles()?.toList().orEmpty()
        var total = files.sumOf { it.length() }
        if (total <= maxTotalBytes) return
        val sortedOldestFirst = files.sortedBy { it.lastModified() }
        for (file in sortedOldestFirst) {
            if (total <= maxTotalBytes * 3 / 4) break
            val len = file.length()
            if (file.delete()) {
                total -= len
            }
        }
    }

    private suspend fun runSegmenter(
        segmenter: Segmenter,
        bitmap: Bitmap,
    ): Triple<FloatArray, Int, Int>? = suspendCancellableCoroutine { cont ->
        val input = InputImage.fromBitmap(bitmap, 0)
        segmenter.process(input)
            .addOnSuccessListener { mask ->
                val mw = mask.width
                val mh = mask.height
                val buf = mask.buffer
                buf.rewind()
                val arr = FloatArray(mw * mh)
                for (i in arr.indices) {
                    arr[i] = buf.float
                }
                cont.resume(Triple(arr, mw, mh))
            }
            .addOnFailureListener {
                cont.resume(null)
            }
    }

    private fun yuvToDownscaledOrientedBitmap(
        imageProxy: ImageProxy,
        targetLongEdge: Int,
        mirrorHorizontal: Boolean,
    ): Bitmap? {
        val planes = imageProxy.planes
        if (planes.size < 3) return null

        val srcW = imageProxy.width
        val srcH = imageProxy.height
        val longEdge = max(srcW, srcH)
        val step = max(1, longEdge / targetLongEdge)
        val dsW = srcW / step
        val dsH = srcH / step
        if (dsW < 8 || dsH < 8) return null

        val yPlane = planes[0]
        val uPlane = planes[1]
        val vPlane = planes[2]

        val yBuf = yPlane.buffer
        val uBuf = uPlane.buffer
        val vBuf = vPlane.buffer

        val yRowStride = yPlane.rowStride
        val yPixStride = yPlane.pixelStride
        val uvRowStride = uPlane.rowStride
        val uvPixStride = uPlane.pixelStride

        val needed = dsW * dsH
        if (reusablePreviewPixels.size != needed) {
            reusablePreviewPixels = IntArray(needed)
        }

        val yCap = yBuf.capacity()
        val uCap = uBuf.capacity()
        val vCap = vBuf.capacity()

        for (y in 0 until dsH) {
            val sy = y * step
            val yRow = sy * yRowStride
            val uvRow = (sy shr 1) * uvRowStride
            for (x in 0 until dsW) {
                val sx = x * step
                val yIdx = yRow + sx * yPixStride
                val uvIdx = uvRow + (sx shr 1) * uvPixStride
                if (yIdx >= yCap || uvIdx >= uCap || uvIdx >= vCap) continue

                val yy = (yBuf.get(yIdx).toInt() and 0xFF) - 16
                val uu = (uBuf.get(uvIdx).toInt() and 0xFF) - 128
                val vv = (vBuf.get(uvIdx).toInt() and 0xFF) - 128

                val y1192 = if (yy < 0) 0 else 1192 * yy
                val r = ((y1192 + 1634 * vv) shr 10).coerceIn(0, 255)
                val g = ((y1192 - 833 * vv - 400 * uu) shr 10).coerceIn(0, 255)
                val b = ((y1192 + 2066 * uu) shr 10).coerceIn(0, 255)

                reusablePreviewPixels[y * dsW + x] =
                    (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }

        val rawBmp = Bitmap.createBitmap(dsW, dsH, Bitmap.Config.ARGB_8888)
        rawBmp.setPixels(reusablePreviewPixels, 0, dsW, 0, 0, dsW, dsH)

        val rotation = imageProxy.imageInfo.rotationDegrees
        if (rotation == 0 && !mirrorHorizontal) return rawBmp

        val matrix = Matrix()
        if (rotation != 0) matrix.postRotate(rotation.toFloat())
        if (mirrorHorizontal) matrix.postScale(-1f, 1f)
        val oriented = Bitmap.createBitmap(rawBmp, 0, 0, dsW, dsH, matrix, true)
        rawBmp.recycle()
        return oriented
    }

    private fun decodeOrientedBitmapFromUri(uri: Uri, maxLongEdge: Int): Bitmap? {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        val longEdge = max(bounds.outWidth, bounds.outHeight)
        if (longEdge <= 0) return null

        var sampleSize = 1
        while (longEdge / sampleSize > maxLongEdge * 1.5f) {
            sampleSize *= 2
        }

        val decodeOpts = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = resolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, decodeOpts)
        } ?: return null

        val orientation = resolver.openInputStream(uri)?.use { stream ->
            ExifInterface(stream).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL,
            )
        } ?: ExifInterface.ORIENTATION_NORMAL

        val oriented = applyExifOrientation(decoded, orientation)
        return if (max(oriented.width, oriented.height) > maxLongEdge) {
            val scaled = scaleBitmapMaxEdge(oriented, maxLongEdge)
            oriented.recycle()
            scaled
        } else {
            oriented
        }
    }

    private fun decodeFileCapped(path: String, maxLongEdge: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        val longEdge = max(bounds.outWidth, bounds.outHeight)
        if (longEdge <= 0) return null

        var sampleSize = 1
        while (longEdge / sampleSize > maxLongEdge * 1.5f) {
            sampleSize *= 2
        }
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val bmp = BitmapFactory.decodeFile(path, opts) ?: return null
        return if (max(bmp.width, bmp.height) > maxLongEdge) {
            val scaled = scaleBitmapMaxEdge(bmp, maxLongEdge)
            bmp.recycle()
            scaled
        } else {
            bmp
        }
    }

    private fun applyExifOrientation(bitmap: Bitmap, orientation: Int): Bitmap {
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            else -> return bitmap
        }
        val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        bitmap.recycle()
        return rotated
    }

    private fun scaleBitmapMaxEdge(source: Bitmap, maxEdge: Int): Bitmap {
        val w = source.width
        val h = source.height
        val longEdge = max(w, h)
        if (longEdge <= maxEdge) return source.copy(Bitmap.Config.ARGB_8888, false)
        val scale = maxEdge.toFloat() / longEdge.toFloat()
        val nw = max(1, (w * scale).toInt())
        val nh = max(1, (h * scale).toInt())
        return Bitmap.createScaledBitmap(source, nw, nh, true)
    }

    fun release() {
        runCatching { streamSegmenter.close() }
        runCatching { singleSegmenter.close() }
        previewRenderer.release()
        captureRenderer.release()
        LutGenerator.releaseAll()
    }
}
