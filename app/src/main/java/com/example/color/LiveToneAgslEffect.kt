package com.example.color

import android.graphics.BitmapShader
import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.ui.graphics.asComposeRenderEffect
import com.example.domain.model.ColorToneSettings

/**
 * Android 13+ (API 33+) zero-copy AGSL RuntimeShader RenderEffect for live 30fps
 * 3D LUT color grading, manual Kelvin tint, HDR tone compression, and Skin-Tone Protection.
 */
object LiveToneAgslEffect {

    private const val AGSL_TONE_SHADER = """
        uniform shader inputShader;
        uniform shader lutShader;
        uniform float3 uWbGain;
        uniform float uHdrStrength;
        uniform float uSkinProtect;
        uniform float uLutBlend;

        float detectSkinMask(float3 rgb) {
            // YCbCr skin-locus detector (prevents orange or washed-out gray skin tones)
            float cb = 128.0 - 37.797 * rgb.r - 74.203 * rgb.g + 112.0 * rgb.b;
            float cr = 128.0 + 112.0 * rgb.r - 93.786 * rgb.g - 18.214 * rgb.b;
            float dCb = (cb - 102.0) / 16.0;
            float dCr = (cr - 151.0) / 15.0;
            float chromaOk = smoothstep(0.05, 0.25, rgb.r - min(rgb.g, rgb.b));
            return exp(-0.5 * (dCb * dCb + dCr * dCr)) * chromaOk;
        }

        float3 sample3dLut(float3 c) {
            float3 clamped = clamp(c, 0.0, 1.0);
            float blueIndex = clamped.b * 15.0;
            float slice0 = floor(blueIndex);
            float slice1 = min(15.0, slice0 + 1.0);
            float frac = blueIndex - slice0;

            float u0 = slice0 * 16.0 + clamped.r * 15.0 + 0.5;
            float u1 = slice1 * 16.0 + clamped.r * 15.0 + 0.5;
            float v = clamped.g * 15.0 + 0.5;

            float3 c0 = lutShader.eval(float2(u0, v)).rgb;
            float3 c1 = lutShader.eval(float2(u1, v)).rgb;
            return mix(c0, c1, frac);
        }

        half4 main(float2 fragCoord) {
            half4 src = inputShader.eval(fragCoord);
            float3 rgb = clamp(float3(src.rgb) * uWbGain, 0.0, 1.0);

            if (uHdrStrength > 0.01) {
                float luma = dot(rgb, float3(0.299, 0.587, 0.114));
                float shadowLift = 1.0 + uHdrStrength * 0.28 * pow(1.0 - luma, 2.0);
                float3 hdr = rgb * shadowLift;
                // Soft highlight roll-off to recover bright sky/window detail
                rgb = hdr / (1.0 + uHdrStrength * 0.18 * hdr);
            }

            float skinMask = detectSkinMask(rgb);
            float3 lutColor = sample3dLut(rgb);
            float3 graded = mix(rgb, lutColor, uLutBlend);

            // Preserve natural warm-neutral skin tones so faces never turn overly orange or gray
            float3 balancedSkin = mix(rgb, graded, 1.0 - uSkinProtect * 0.65);
            float skinLuma = dot(balancedSkin, float3(0.299, 0.587, 0.114));
            float3 healthySkin = mix(float3(skinLuma), balancedSkin, 1.04);
            float3 finalRgb = mix(graded, clamp(healthySkin, 0.0, 1.0), skinMask * uSkinProtect);

            return half4(half3(clamp(finalRgb, 0.0, 1.0)), src.a);
        }
    """

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    fun createComposeRenderEffect(
        settings: ColorToneSettings,
    ): androidx.compose.ui.graphics.RenderEffect {
        val shader = RuntimeShader(AGSL_TONE_SHADER)
        val lutBitmap = LutGenerator.getLutBitmap(settings.lutPreset)
        val lutShader = BitmapShader(
            lutBitmap,
            Shader.TileMode.CLAMP,
            Shader.TileMode.CLAMP,
        )
        shader.setInputShader("lutShader", lutShader)

        val (rGain, gGain, bGain) = if (settings.isAutoWhiteBalance) {
            Triple(1f, 1f, 1f)
        } else {
            LutGenerator.kelvinToRelativeRgb(settings.kelvin)
        }
        shader.setFloatUniform("uWbGain", rGain, gGain, bGain)
        shader.setFloatUniform("uHdrStrength", if (settings.isHdrEnabled) 1.0f else 0.0f)
        shader.setFloatUniform("uSkinProtect", settings.skinToneProtection)
        shader.setFloatUniform("uLutBlend", 1.0f)

        val platformEffect = RenderEffect.createRuntimeShaderEffect(shader, "inputShader")
        return platformEffect.asComposeRenderEffect()
    }
}
