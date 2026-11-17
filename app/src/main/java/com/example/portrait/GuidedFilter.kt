package com.example.portrait

import android.graphics.Bitmap
import android.graphics.Color
import com.example.domain.model.NormalizedRect
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Fast O(N) Separable Guided Filter + Monocular Depth & Alpha Matte Builder.
 * Refines coarse ML segmentation masks around fine hair strands and ear contours
 * using the luminance guide image, keeping memory usage minimal on 4GB RAM devices.
 */
object GuidedFilter {

    fun buildMatteDepthBitmap(
        guideBitmap: Bitmap,
        rawPersonMask: FloatArray?,
        maskWidth: Int,
        maskHeight: Int,
        focusX: Float,
        focusY: Float,
        detectedFaces: List<NormalizedRect>,
        filterRadius: Int,
    ): Bitmap {
        val w = guideBitmap.width
        val h = guideBitmap.height
        val n = w * h

        val pixels = IntArray(n)
        guideBitmap.getPixels(pixels, 0, w, 0, 0, w, h)

        val guideLuma = FloatArray(n)
        for (i in 0 until n) {
            val c = pixels[i]
            val r = (c shr 16) and 0xFF
            val g = (c shr 8) and 0xFF
            val b = c and 0xFF
            guideLuma[i] = (0.299f * r + 0.587f * g + 0.114f * b) / 255f
        }

        // Determine if ML Kit found a meaningful person foreground; otherwise use
        // focus-point + face-guided saliency prior so objects/scenes also get natural bokeh.
        val hasPersonMask = rawPersonMask != null &&
            maskWidth > 0 &&
            maskHeight > 0 &&
            hasSignificantForeground(rawPersonMask)

        val coarseAlpha = FloatArray(n)
        val depthMap = FloatArray(n)

        val primaryFace = detectedFaces.maxByOrNull { (it.right - it.left) * (it.bottom - it.top) }
        val targetFx = primaryFace?.centerX ?: focusX.coerceIn(0.05f, 0.95f)
        val targetFy = primaryFace?.centerY ?: focusY.coerceIn(0.05f, 0.95f)

        val focusLumaIdx = (
            (targetFy * (h - 1)).toInt().coerceIn(0, h - 1) * w +
                (targetFx * (w - 1)).toInt().coerceIn(0, w - 1)
            )
        val focusLuma = guideLuma[focusLumaIdx]

        for (y in 0 until h) {
            val ny = y.toFloat() / max(1, h - 1)
            for (x in 0 until w) {
                val nx = x.toFloat() / max(1, w - 1)
                val idx = y * w + x

                val rawMaskVal = if (hasPersonMask) {
                    bilinearSample(rawPersonMask!!, maskWidth, maskHeight, nx, ny)
                } else {
                    // Focus-centered + luma-affinity foreground prior for non-person objects
                    val dx = (nx - targetFx) / 0.28f
                    val dy = (ny - targetFy) / 0.34f
                    val spatial = exp(-0.5f * (dx * dx + dy * dy))
                    val lumaDiff = kotlin.math.abs(guideLuma[idx] - focusLuma)
                    val affinity = exp(-(lumaDiff * lumaDiff) / (2f * 0.22f * 0.22f))
                    (spatial * 0.72f + spatial * affinity * 0.28f).coerceIn(0f, 1f)
                }

                coarseAlpha[idx] = rawMaskVal

                // Monocular depth map:
                // Foreground subject sits near depth ~0.08..0.15; ground plane near bottom is closer (~0.35),
                // upper horizon/background walls recede smoothly toward 0.85..1.0.
                val distFromFocus = hypot(nx - targetFx, ny - targetFy)
                val verticalHorizonCue = (1f - ny).coerceIn(0f, 1f) // top of frame tends to be farther
                val bgDepth = (0.28f + 0.42f * verticalHorizonCue + 0.30f * (distFromFocus / 1.1f))
                    .coerceIn(0.20f, 1.0f)

                val fgDepth = 0.08f
                depthMap[idx] = fgDepth * rawMaskVal + bgDepth * (1f - rawMaskVal)
            }
        }

        // Refine coarse alpha matte using fast O(N) Guided Image Filter for crisp hair/ear edges
        val refinedAlpha = runGuidedFilter(
            guide = guideLuma,
            input = coarseAlpha,
            width = w,
            height = h,
            radius = filterRadius.coerceAtLeast(2),
            eps = 0.004f,
        )

        val packed = IntArray(n)
        for (i in 0 until n) {
            val alphaByte = (refinedAlpha[i].coerceIn(0f, 1f) * 255f).toInt()
            // Re-anchor depth near refined alpha so soft hair strands blend naturally
            val finalDepth = (0.08f * refinedAlpha[i] + depthMap[i] * (1f - refinedAlpha[i]))
                .coerceIn(0f, 1f)
            val depthByte = (finalDepth * 255f).toInt()
            packed[i] = Color.argb(255, alphaByte, depthByte, depthByte)
        }

        return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply {
            setPixels(packed, 0, w, 0, 0, w, h)
        }
    }

    fun runGuidedFilter(
        guide: FloatArray,
        input: FloatArray,
        width: Int,
        height: Int,
        radius: Int,
        eps: Float,
    ): FloatArray {
        val n = width * height
        val ii = FloatArray(n)
        val ip = FloatArray(n)
        for (i in 0 until n) {
            val g = guide[i]
            ii[i] = g * g
            ip[i] = g * input[i]
        }

        val meanI = boxFilter(guide, width, height, radius)
        val meanP = boxFilter(input, width, height, radius)
        val meanII = boxFilter(ii, width, height, radius)
        val meanIP = boxFilter(ip, width, height, radius)

        val a = FloatArray(n)
        val b = FloatArray(n)
        for (i in 0 until n) {
            val mi = meanI[i]
            val mp = meanP[i]
            val covIp = meanIP[i] - mi * mp
            val varI = (meanII[i] - mi * mi).coerceAtLeast(0f)
            val ai = covIp / (varI + eps)
            a[i] = ai
            b[i] = mp - ai * mi
        }

        val meanA = boxFilter(a, width, height, radius)
        val meanB = boxFilter(b, width, height, radius)

        val out = FloatArray(n)
        for (i in 0 until n) {
            out[i] = (meanA[i] * guide[i] + meanB[i]).coerceIn(0f, 1f)
        }
        return out
    }

    private fun boxFilter(src: FloatArray, w: Int, h: Int, r: Int): FloatArray {
        val horiz = FloatArray(w * h)
        val dst = FloatArray(w * h)
        val window = (2 * r + 1).toFloat()

        // Horizontal pass with running sum
        for (y in 0 until h) {
            val rowOff = y * w
            var sum = 0f
            for (k in -r..r) {
                sum += src[rowOff + k.coerceIn(0, w - 1)]
            }
            horiz[rowOff] = sum / window
            for (x in 1 until w) {
                val addIdx = (x + r).coerceIn(0, w - 1)
                val subIdx = (x - r - 1).coerceIn(0, w - 1)
                sum += src[rowOff + addIdx] - src[rowOff + subIdx]
                horiz[rowOff + x] = sum / window
            }
        }

        // Vertical pass with running sum
        for (x in 0 until w) {
            var sum = 0f
            for (k in -r..r) {
                sum += horiz[k.coerceIn(0, h - 1) * w + x]
            }
            dst[x] = sum / window
            for (y in 1 until h) {
                val addY = (y + r).coerceIn(0, h - 1)
                val subY = (y - r - 1).coerceIn(0, h - 1)
                sum += horiz[addY * w + x] - horiz[subY * w + x]
                dst[y * w + x] = sum / window
            }
        }

        return dst
    }

    private fun hasSignificantForeground(mask: FloatArray): Boolean {
        var count = 0
        val step = max(1, mask.size / 512)
        var sampled = 0
        var i = 0
        while (i < mask.size) {
            if (mask[i] > 0.45f) count++
            sampled++
            i += step
        }
        val ratio = count.toFloat() / max(1, sampled)
        return ratio in 0.03f..0.92f
    }

    private fun bilinearSample(
        data: FloatArray,
        w: Int,
        h: Int,
        nx: Float,
        ny: Float,
    ): Float {
        val fx = (nx * (w - 1)).coerceIn(0f, (w - 1).toFloat())
        val fy = (ny * (h - 1)).coerceIn(0f, (h - 1).toFloat())
        val x0 = fx.toInt()
        val y0 = fy.toInt()
        val x1 = min(w - 1, x0 + 1)
        val y1 = min(h - 1, y0 + 1)
        val dx = fx - x0
        val dy = fy - y0

        val v00 = data[y0 * w + x0]
        val v10 = data[y0 * w + x1]
        val v01 = data[y1 * w + x0]
        val v11 = data[y1 * w + x1]

        val top = v00 * (1f - dx) + v10 * dx
        val bot = v01 * (1f - dx) + v11 * dx
        return top * (1f - dy) + bot * dy
    }
}
