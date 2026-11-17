package com.example.color

import android.graphics.Bitmap
import android.graphics.Color
import com.example.domain.model.LutPreset
import kotlin.math.ln
import kotlin.math.pow

/**
 * Generates packed 16x16x16 3D LUT textures (256x16 RGBA, ~16KB each)
 * and Planckian blackbody Kelvin-to-RGB white balance gains.
 */
object LutGenerator {

    private const val LUT_SIZE = 16
    private val cache = mutableMapOf<LutPreset, Bitmap>()

    @Synchronized
    fun getLutBitmap(preset: LutPreset): Bitmap {
        cache[preset]?.let { if (!it.isRecycled) return it }
        val bmp = build3dLutStrip(preset)
        cache[preset] = bmp
        return bmp
    }

    @Synchronized
    fun releaseAll() {
        cache.values.forEach { if (!it.isRecycled) it.recycle() }
        cache.clear()
    }

    private fun build3dLutStrip(preset: LutPreset): Bitmap {
        val width = LUT_SIZE * LUT_SIZE // 256
        val height = LUT_SIZE           // 16
        val pixels = IntArray(width * height)

        for (bIdx in 0 until LUT_SIZE) {
            val b = bIdx / (LUT_SIZE - 1f)
            for (gIdx in 0 until LUT_SIZE) {
                val g = gIdx / (LUT_SIZE - 1f)
                for (rIdx in 0 until LUT_SIZE) {
                    val r = rIdx / (LUT_SIZE - 1f)
                    val (or, og, ob) = transformColor(r, g, b, preset)
                    val x = bIdx * LUT_SIZE + rIdx
                    val y = gIdx
                    pixels[y * width + x] = Color.argb(
                        255,
                        (or.coerceIn(0f, 1f) * 255f).toInt(),
                        (og.coerceIn(0f, 1f) * 255f).toInt(),
                        (ob.coerceIn(0f, 1f) * 255f).toInt(),
                    )
                }
            }
        }

        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
            setPixels(pixels, 0, width, 0, 0, width, height)
        }
    }

    private fun transformColor(
        r: Float,
        g: Float,
        b: Float,
        preset: LutPreset,
    ): Triple<Float, Float, Float> {
        return when (preset) {
            LutPreset.NATURAL -> {
                // Gentle filmic S-curve for DSLR micro-contrast without hue distortion
                val sr = sCurve(r, 0.14f)
                val sg = sCurve(g, 0.14f)
                val sb = sCurve(b, 0.14f)
                Triple(sr, sg, sb)
            }
            LutPreset.WARM -> {
                // Golden-hour highlight warmth + clean neutral shadows
                val luma = 0.299f * r + 0.587f * g + 0.114f * b
                val sr = sCurve(r, 0.16f) + 0.055f * luma * (1f - r * 0.4f)
                val sg = sCurve(g, 0.15f) + 0.018f * luma
                val sb = sCurve(b, 0.16f) - 0.048f * luma + 0.015f * (1f - luma)
                Triple(sr, sg, sb)
            }
            LutPreset.COOL -> {
                // Crisp editorial slate-blue shadow bias + clean highlights
                val luma = 0.299f * r + 0.587f * g + 0.114f * b
                val sr = sCurve(r, 0.16f) - 0.038f * (1f - luma * 0.5f)
                val sg = sCurve(g, 0.15f) + 0.008f * (1f - luma)
                val sb = sCurve(b, 0.16f) + 0.058f * (1f - luma * 0.35f)
                Triple(sr, sg, sb)
            }
            LutPreset.FILM -> {
                // Classic analog film toe lift + warm highlights / subtle teal-cyan shadows
                val luma = 0.299f * r + 0.587f * g + 0.114f * b
                val toe = 0.032f
                val sr = toe + (1f - toe) * sCurve(r, 0.22f) + 0.04f * luma - 0.012f * (1f - luma)
                val sg = toe + (1f - toe) * sCurve(g, 0.20f) + 0.012f * luma
                val sb = toe + (1f - toe) * sCurve(b, 0.22f) - 0.035f * luma + 0.025f * (1f - luma)
                // Gentle saturation moderation in deep highlights/shadows
                val avg = (sr + sg + sb) / 3f
                val sat = 0.92f
                Triple(
                    avg + (sr - avg) * sat,
                    avg + (sg - avg) * sat,
                    avg + (sb - avg) * sat,
                )
            }
        }
    }

    private fun sCurve(x: Float, strength: Float): Float {
        val c = x.coerceIn(0f, 1f)
        val smooth = c * c * (3f - 2f * c)
        return c * (1f - strength) + smooth * strength
    }

    /**
     * Computes RGB multipliers for a given Kelvin temperature (2800K..8000K).
     * Returns (rMultiplier, gMultiplier, bMultiplier) normalized around 5500K daylight = (1, 1, 1).
     */
    fun kelvinToRelativeRgb(kelvin: Int): Triple<Float, Float, Float> {
        val (rTarget, gTarget, bTarget) = blackbodyRgb(kelvin.coerceIn(2800, 8000))
        val (rRef, gRef, bRef) = blackbodyRgb(5500)
        // Warm scene lighting -> inverse hardware gains; for visual preview tint we invert gently
        // so 7500K makes image warmer (compensating cool shade) and 3200K cools tungsten cast.
        val rGain = (rRef / rTarget).coerceIn(0.65f, 1.45f)
        val gGain = (gRef / gTarget).coerceIn(0.85f, 1.15f)
        val bGain = (bRef / bTarget).coerceIn(0.65f, 1.45f)
        return Triple(rGain, gGain, bGain)
    }

    /**
     * Hardware Camera2 RggbChannelVector gains (compensates ambient color temperature).
     */
    fun kelvinToHardwareAwbGains(kelvin: Int): FloatArray {
        val (rRel, gRel, bRel) = kelvinToRelativeRgb(kelvin)
        // Scale to typical Camera2 sensor gains around 1.0..2.5
        val r = (1.55f * rRel).coerceIn(0.8f, 3.2f)
        val g = (1.05f * gRel).coerceIn(0.8f, 2.0f)
        val b = (1.55f * bRel).coerceIn(0.8f, 3.2f)
        return floatArrayOf(r, g, g, b)
    }

    private fun blackbodyRgb(kelvin: Int): Triple<Float, Float, Float> {
        val temp = kelvin / 100.0
        val r = if (temp <= 66.0) {
            255.0
        } else {
            val v = temp - 60.0
            (329.698727446 * v.pow(-0.1332047592)).coerceIn(0.0, 255.0)
        }
        val g = if (temp <= 66.0) {
            (99.4708025861 * ln(temp) - 161.1195681661).coerceIn(0.0, 255.0)
        } else {
            val v = temp - 60.0
            (288.1221695283 * v.pow(-0.0755148492)).coerceIn(0.0, 255.0)
        }
        val b = when {
            temp >= 66.0 -> 255.0
            temp <= 19.0 -> 0.0
            else -> {
                val v = temp - 10.0
                (138.5177312231 * ln(v) - 305.0447927307).coerceIn(0.0, 255.0)
            }
        }
        return Triple((r / 255.0).toFloat(), (g / 255.0).toFloat(), (b / 255.0).toFloat())
    }
}
