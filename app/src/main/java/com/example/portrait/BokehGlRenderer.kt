package com.example.portrait

import android.graphics.Bitmap
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.opengl.GLUtils
import com.example.color.LutGenerator
import com.example.domain.model.BokehShape
import com.example.domain.model.ColorToneSettings
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.max

/**
 * Two-pass GPU OpenGL ES 2.0 offscreen renderer for DSLR optical bokeh + color grading:
 * Pass 1 (FBO): Edge-preserving Noise Reduction FIRST -> 37-tap Disc/Hexagonal Bokeh with Highlight Preservation
 * Pass 2 (Screen): Subject-only Unsharp Mask AFTER NR -> Kelvin WB -> HDR Tone -> Skin-Tone Protection -> 3D LUT
 */
class BokehGlRenderer {

    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var surfaceWidth = 0
    private var surfaceHeight = 0

    private var pass1Program = 0
    private var pass2Program = 0

    private var imageTexId = 0
    private var matteDepthTexId = 0
    private var lutTexId = 0
    private var fboId = 0
    private var fboTexId = 0
    private var fboWidth = 0
    private var fboHeight = 0

    private val lock = Any()

    private val quadVertices: FloatBuffer = ByteBuffer
        .allocateDirect(16 * 4)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()
        .apply {
            put(
                floatArrayOf(
                    -1f, -1f, 0f, 0f,
                    1f, -1f, 1f, 0f,
                    -1f, 1f, 0f, 1f,
                    1f, 1f, 1f, 1f,
                )
            )
            position(0)
        }

    fun renderBokehAndTone(
        sourceBitmap: Bitmap,
        matteDepthBitmap: Bitmap?,
        fNumber: Float,
        zoomRatio: Float,
        focusX: Float,
        focusY: Float,
        bokehShape: BokehShape,
        colorTone: ColorToneSettings,
        applyBokeh: Boolean,
        denoiseStrength: Float = 0.42f,
    ): Bitmap {
        synchronized(lock) {
            val w = sourceBitmap.width
            val h = sourceBitmap.height
            ensureEglSurface(w, h)
            ensurePrograms()
            ensureFbo(w, h)

            uploadTexture(imageTexId, sourceBitmap)
            if (matteDepthBitmap != null) {
                uploadTexture(matteDepthTexId, matteDepthBitmap)
            } else {
                // Upload 1x1 fallback white-matte/zero-depth pixel when bokeh is off
                val fallback = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888).apply {
                    setPixel(0, 0, android.graphics.Color.argb(255, 255, 0, 0))
                }
                uploadTexture(matteDepthTexId, fallback)
                fallback.recycle()
            }
            uploadTexture(lutTexId, LutGenerator.getLutBitmap(colorTone.lutPreset))

            val longEdge = max(w, h).toFloat()
            val apertureFactor = ((16f - fNumber.coerceIn(1.4f, 16f)) / (16f - 1.4f))
                .let { it * it * 0.55f + it * 0.45f }
            val zoomCompression = (0.85f + 0.15f * zoomRatio.coerceIn(0.6f, 4.0f))
            val maxRadiusPx = if (applyBokeh) {
                (longEdge * 0.022f * apertureFactor * zoomCompression).coerceAtLeast(0f)
            } else {
                0f
            }

            // -------- PASS 1: Noise Reduction -> Disc/Hexagonal Bokeh (render into FBO) --------
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fboId)
            GLES20.glViewport(0, 0, w, h)
            GLES20.glClearColor(0f, 0f, 0f, 1f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

            GLES20.glUseProgram(pass1Program)
            bindQuadAttributes(pass1Program)

            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, imageTexId)
            GLES20.glUniform1i(GLES20.glGetUniformLocation(pass1Program, "uImageTex"), 0)

            GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, matteDepthTexId)
            GLES20.glUniform1i(GLES20.glGetUniformLocation(pass1Program, "uMatteDepthTex"), 1)

            GLES20.glUniform2f(
                GLES20.glGetUniformLocation(pass1Program, "uTexelSize"),
                1f / w.toFloat(),
                1f / h.toFloat(),
            )
            GLES20.glUniform1f(
                GLES20.glGetUniformLocation(pass1Program, "uBlurRadiusPx"),
                maxRadiusPx,
            )
            GLES20.glUniform2f(
                GLES20.glGetUniformLocation(pass1Program, "uFocusUv"),
                focusX.coerceIn(0.02f, 0.98f),
                focusY.coerceIn(0.02f, 0.98f),
            )
            GLES20.glUniform1i(
                GLES20.glGetUniformLocation(pass1Program, "uBokehShape"),
                bokehShape.shaderValue,
            )
            GLES20.glUniform1f(
                GLES20.glGetUniformLocation(pass1Program, "uHighlightBoost"),
                2.4f,
            )
            GLES20.glUniform1f(
                GLES20.glGetUniformLocation(pass1Program, "uDenoiseStrength"),
                denoiseStrength,
            )

            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

            // -------- PASS 2: Subject-Only Unsharp Mask + Kelvin WB + HDR + Skin-Tone + 3D LUT --------
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
            GLES20.glViewport(0, 0, w, h)
            GLES20.glClearColor(0f, 0f, 0f, 1f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

            GLES20.glUseProgram(pass2Program)
            bindQuadAttributes(pass2Program)

            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, fboTexId)
            GLES20.glUniform1i(GLES20.glGetUniformLocation(pass2Program, "uDenoisedBokehTex"), 0)

            GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, matteDepthTexId)
            GLES20.glUniform1i(GLES20.glGetUniformLocation(pass2Program, "uMatteDepthTex"), 1)

            GLES20.glActiveTexture(GLES20.GL_TEXTURE2)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, lutTexId)
            GLES20.glUniform1i(GLES20.glGetUniformLocation(pass2Program, "uLutTex"), 2)

            GLES20.glUniform2f(
                GLES20.glGetUniformLocation(pass2Program, "uTexelSize"),
                1f / w.toFloat(),
                1f / h.toFloat(),
            )
            GLES20.glUniform1f(
                GLES20.glGetUniformLocation(pass2Program, "uSharpenAmount"),
                colorTone.subjectSharpenAmount,
            )
            val (rGain, gGain, bGain) = if (colorTone.isAutoWhiteBalance) {
                Triple(1f, 1f, 1f)
            } else {
                LutGenerator.kelvinToRelativeRgb(colorTone.kelvin)
            }
            GLES20.glUniform3f(
                GLES20.glGetUniformLocation(pass2Program, "uWbGain"),
                rGain,
                gGain,
                bGain,
            )
            GLES20.glUniform1f(
                GLES20.glGetUniformLocation(pass2Program, "uHdrStrength"),
                if (colorTone.isHdrEnabled) 1.0f else 0.0f,
            )
            GLES20.glUniform1f(
                GLES20.glGetUniformLocation(pass2Program, "uSkinProtect"),
                colorTone.skinToneProtection,
            )

            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

            val pixelBuffer = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder())
            GLES20.glReadPixels(0, 0, w, h, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, pixelBuffer)
            pixelBuffer.rewind()

            val resultBitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            resultBitmap.copyPixelsFromBuffer(pixelBuffer)
            return resultBitmap
        }
    }

    private fun bindQuadAttributes(program: Int) {
        val posLoc = GLES20.glGetAttribLocation(program, "aPosition")
        val texLoc = GLES20.glGetAttribLocation(program, "aTexCoord")

        quadVertices.position(0)
        GLES20.glEnableVertexAttribArray(posLoc)
        GLES20.glVertexAttribPointer(posLoc, 2, GLES20.GL_FLOAT, false, 16, quadVertices)

        quadVertices.position(2)
        GLES20.glEnableVertexAttribArray(texLoc)
        GLES20.glVertexAttribPointer(texLoc, 2, GLES20.GL_FLOAT, false, 16, quadVertices)
    }

    private fun ensureEglSurface(width: Int, height: Int) {
        if (eglDisplay == EGL14.EGL_NO_DISPLAY) {
            eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            val version = IntArray(2)
            EGL14.eglInitialize(eglDisplay, version, 0, version, 1)

            val configAttribs = intArrayOf(
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_NONE,
            )
            val configs = arrayOfNulls<EGLConfig>(1)
            val numConfigs = IntArray(1)
            EGL14.eglChooseConfig(eglDisplay, configAttribs, 0, configs, 0, 1, numConfigs, 0)
            val config = configs[0] ?: error("Unable to choose EGLConfig")

            val ctxAttribs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
            eglContext = EGL14.eglCreateContext(eglDisplay, config, EGL14.EGL_NO_CONTEXT, ctxAttribs, 0)
        }

        if (eglSurface == EGL14.EGL_NO_SURFACE || surfaceWidth != width || surfaceHeight != height) {
            if (eglSurface != EGL14.EGL_NO_SURFACE) {
                EGL14.eglDestroySurface(eglDisplay, eglSurface)
            }
            val configAttribs = intArrayOf(
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_NONE,
            )
            val configs = arrayOfNulls<EGLConfig>(1)
            val numConfigs = IntArray(1)
            EGL14.eglChooseConfig(eglDisplay, configAttribs, 0, configs, 0, 1, numConfigs, 0)
            val pbufferAttribs = intArrayOf(
                EGL14.EGL_WIDTH, width,
                EGL14.EGL_HEIGHT, height,
                EGL14.EGL_NONE,
            )
            eglSurface = EGL14.eglCreatePbufferSurface(eglDisplay, configs[0], pbufferAttribs, 0)
            surfaceWidth = width
            surfaceHeight = height
        }

        EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)
    }

    private fun ensurePrograms() {
        if (pass1Program == 0) {
            pass1Program = buildProgram(VERTEX_SHADER, PASS1_NR_BOKEH_SHADER)
        }
        if (pass2Program == 0) {
            pass2Program = buildProgram(VERTEX_SHADER, PASS2_SHARPEN_LUT_SHADER)
        }
        if (imageTexId == 0) {
            val texIds = IntArray(3)
            GLES20.glGenTextures(3, texIds, 0)
            imageTexId = texIds[0]
            matteDepthTexId = texIds[1]
            lutTexId = texIds[2]
            texIds.forEach { id ->
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, id)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            }
        }
    }

    private fun ensureFbo(width: Int, height: Int) {
        if (fboId != 0 && fboWidth == width && fboHeight == height) return
        if (fboId != 0) {
            GLES20.glDeleteFramebuffers(1, intArrayOf(fboId), 0)
            GLES20.glDeleteTextures(1, intArrayOf(fboTexId), 0)
        }

        val fbos = IntArray(1)
        val texs = IntArray(1)
        GLES20.glGenFramebuffers(1, fbos, 0)
        GLES20.glGenTextures(1, texs, 0)
        fboId = fbos[0]
        fboTexId = texs[0]
        fboWidth = width
        fboHeight = height

        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, fboTexId)
        GLES20.glTexImage2D(
            GLES20.GL_TEXTURE_2D,
            0,
            GLES20.GL_RGBA,
            width,
            height,
            0,
            GLES20.GL_RGBA,
            GLES20.GL_UNSIGNED_BYTE,
            null,
        )
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fboId)
        GLES20.glFramebufferTexture2D(
            GLES20.GL_FRAMEBUFFER,
            GLES20.GL_COLOR_ATTACHMENT0,
            GLES20.GL_TEXTURE_2D,
            fboTexId,
            0,
        )
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
    }

    private fun buildProgram(vsSource: String, fsSource: String): Int {
        val vs = compileShader(GLES20.GL_VERTEX_SHADER, vsSource)
        val fs = compileShader(GLES20.GL_FRAGMENT_SHADER, fsSource)
        return GLES20.glCreateProgram().also { prog ->
            GLES20.glAttachShader(prog, vs)
            GLES20.glAttachShader(prog, fs)
            GLES20.glLinkProgram(prog)
            GLES20.glDeleteShader(vs)
            GLES20.glDeleteShader(fs)
        }
    }

    private fun uploadTexture(texId: Int, bitmap: Bitmap) {
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texId)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
    }

    private fun compileShader(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        return shader
    }

    fun release() {
        synchronized(lock) {
            if (pass1Program != 0) {
                GLES20.glDeleteProgram(pass1Program)
                pass1Program = 0
            }
            if (pass2Program != 0) {
                GLES20.glDeleteProgram(pass2Program)
                pass2Program = 0
            }
            if (fboId != 0) {
                GLES20.glDeleteFramebuffers(1, intArrayOf(fboId), 0)
                GLES20.glDeleteTextures(1, intArrayOf(fboTexId), 0)
                fboId = 0
                fboTexId = 0
            }
            if (imageTexId != 0) {
                GLES20.glDeleteTextures(3, intArrayOf(imageTexId, matteDepthTexId, lutTexId), 0)
                imageTexId = 0
                matteDepthTexId = 0
                lutTexId = 0
            }
            if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(
                    eglDisplay,
                    EGL14.EGL_NO_SURFACE,
                    EGL14.EGL_NO_SURFACE,
                    EGL14.EGL_NO_CONTEXT,
                )
                if (eglSurface != EGL14.EGL_NO_SURFACE) {
                    EGL14.eglDestroySurface(eglDisplay, eglSurface)
                    eglSurface = EGL14.EGL_NO_SURFACE
                }
                if (eglContext != EGL14.EGL_NO_CONTEXT) {
                    EGL14.eglDestroyContext(eglDisplay, eglContext)
                    eglContext = EGL14.EGL_NO_CONTEXT
                }
                EGL14.eglTerminate(eglDisplay)
                eglDisplay = EGL14.EGL_NO_DISPLAY
            }
        }
    }

    companion object {
        private const val VERTEX_SHADER = """
            attribute vec2 aPosition;
            attribute vec2 aTexCoord;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = vec4(aPosition, 0.0, 1.0);
                vTexCoord = aTexCoord;
            }
        """

        // PASS 1: Edge-preserving Bilateral Noise Reduction BEFORE 37-tap Disc/Hexagonal Bokeh
        private const val PASS1_NR_BOKEH_SHADER = """
            precision mediump float;
            uniform sampler2D uImageTex;
            uniform sampler2D uMatteDepthTex;
            uniform vec2 uTexelSize;
            uniform float uBlurRadiusPx;
            uniform vec2 uFocusUv;
            uniform int uBokehShape;
            uniform float uHighlightBoost;
            uniform float uDenoiseStrength;
            varying vec2 vTexCoord;

            const float GOLDEN_ANGLE = 2.39996323;
            const int TAP_COUNT = 36;

            float luminance(vec3 c) {
                return dot(c, vec3(0.299, 0.587, 0.114));
            }

            vec3 bilateralDenoise(vec2 uv, vec3 centerRgb) {
                if (uDenoiseStrength < 0.01) return centerRgb;
                vec3 sum = centerRgb;
                float wSum = 1.0;
                float sigmaColor = 0.085;
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dx = -1; dx <= 1; dx++) {
                        if (dx == 0 && dy == 0) continue;
                        vec2 sUv = clamp(uv + vec2(float(dx), float(dy)) * uTexelSize, 0.0, 1.0);
                        vec3 sRgb = texture2D(uImageTex, sUv).rgb;
                        vec3 diff = sRgb - centerRgb;
                        float dist2 = dot(diff, diff);
                        float w = exp(-dist2 / (2.0 * sigmaColor * sigmaColor)) * uDenoiseStrength;
                        sum += sRgb * w;
                        wSum += w;
                    }
                }
                return sum / wSum;
            }

            void main() {
                vec3 rawCenter = texture2D(uImageTex, vTexCoord).rgb;
                vec3 denoisedCenter = bilateralDenoise(vTexCoord, rawCenter);

                if (uBlurRadiusPx < 0.75) {
                    gl_FragColor = vec4(denoisedCenter, 1.0);
                    return;
                }

                vec4 centerMD = texture2D(uMatteDepthTex, vTexCoord);
                float centerMatte = centerMD.r;
                float centerDepth = centerMD.g;
                float focusDepth = texture2D(uMatteDepthTex, uFocusUv).g;
                float coc = clamp(abs(centerDepth - focusDepth) * 1.6, 0.0, 1.0);
                float bgFactor = clamp(1.0 - centerMatte, 0.0, 1.0);
                float pixelRadius = uBlurRadiusPx * coc * bgFactor;

                if (pixelRadius < 0.75) {
                    gl_FragColor = vec4(denoisedCenter, 1.0);
                    return;
                }

                vec3 accumColor = denoisedCenter;
                float accumWeight = 1.0;

                for (int i = 1; i <= TAP_COUNT; i++) {
                    float fi = float(i);
                    float normR = sqrt(fi / float(TAP_COUNT));
                    float theta = fi * GOLDEN_ANGLE;

                    float shapeScale = 1.0;
                    if (uBokehShape == 1) {
                        float sector = mod(theta, 1.04719755) - 0.52359877;
                        shapeScale = 0.8660254 / max(0.5, cos(sector));
                    }

                    vec2 offset = vec2(cos(theta), sin(theta)) * (normR * shapeScale * pixelRadius) * uTexelSize;
                    vec2 sampleUv = clamp(vTexCoord + offset, 0.0, 1.0);

                    vec3 tapRgb = texture2D(uImageTex, sampleUv).rgb;
                    vec4 tapMD = texture2D(uMatteDepthTex, sampleUv);
                    float tapMatte = tapMD.r;
                    float tapDepth = tapMD.g;

                    float sampleBgAllowed = mix(1.0, 0.12, smoothstep(0.35, 0.85, tapMatte));
                    float depthMatch = smoothstep(0.0, 0.45, abs(tapDepth - focusDepth));

                    float luma = luminance(tapRgb);
                    float highlightWeight = 1.0 + uHighlightBoost * pow(smoothstep(0.55, 0.98, luma), 2.2);

                    float w = sampleBgAllowed * mix(0.35, 1.0, depthMatch) * highlightWeight;
                    accumColor += tapRgb * w;
                    accumWeight += w;
                }

                vec3 bokehRgb = accumColor / max(accumWeight, 0.0001);
                float sharpBlend = smoothstep(0.15, 0.88, centerMatte);
                vec3 finalRgb = mix(bokehRgb, denoisedCenter, sharpBlend);
                gl_FragColor = vec4(finalRgb, 1.0);
            }
        """

        // PASS 2: Subject-only Unsharp Mask AFTER NR -> Kelvin WB -> HDR -> Skin-Tone Protection -> 3D LUT
        private const val PASS2_SHARPEN_LUT_SHADER = """
            precision mediump float;
            uniform sampler2D uDenoisedBokehTex;
            uniform sampler2D uMatteDepthTex;
            uniform sampler2D uLutTex;
            uniform vec2 uTexelSize;
            uniform float uSharpenAmount;
            uniform vec3 uWbGain;
            uniform float uHdrStrength;
            uniform float uSkinProtect;
            varying vec2 vTexCoord;

            float detectSkinMask(vec3 rgb) {
                float cb = 128.0 - 37.797 * rgb.r - 74.203 * rgb.g + 112.0 * rgb.b;
                float cr = 128.0 + 112.0 * rgb.r - 93.786 * rgb.g - 18.214 * rgb.b;
                float dCb = (cb - 102.0) / 16.0;
                float dCr = (cr - 151.0) / 15.0;
                float chromaOk = smoothstep(0.05, 0.25, rgb.r - min(rgb.g, rgb.b));
                return exp(-0.5 * (dCb * dCb + dCr * dCr)) * chromaOk;
            }

            vec3 sample3dLut(vec3 c) {
                vec3 clamped = clamp(c, 0.0, 1.0);
                float blueIndex = clamped.b * 15.0;
                float slice0 = floor(blueIndex);
                float slice1 = min(15.0, slice0 + 1.0);
                float frac = blueIndex - slice0;

                float u0 = (slice0 * 16.0 + clamped.r * 15.0 + 0.5) / 256.0;
                float u1 = (slice1 * 16.0 + clamped.r * 15.0 + 0.5) / 256.0;
                float v = (clamped.g * 15.0 + 0.5) / 16.0;

                vec3 c0 = texture2D(uLutTex, vec2(u0, v)).rgb;
                vec3 c1 = texture2D(uLutTex, vec2(u1, v)).rgb;
                return mix(c0, c1, frac);
            }

            void main() {
                vec3 center = texture2D(uDenoisedBokehTex, vTexCoord).rgb;
                float subjectMatte = texture2D(uMatteDepthTex, vTexCoord).r;

                // Subject-only Unsharp Mask (background bokeh is never sharpened)
                vec3 sharpened = center;
                if (uSharpenAmount > 0.01 && subjectMatte > 0.15) {
                    vec3 n = texture2D(uDenoisedBokehTex, clamp(vTexCoord + vec2(0.0, -uTexelSize.y), 0.0, 1.0)).rgb;
                    vec3 s = texture2D(uDenoisedBokehTex, clamp(vTexCoord + vec2(0.0, uTexelSize.y), 0.0, 1.0)).rgb;
                    vec3 e = texture2D(uDenoisedBokehTex, clamp(vTexCoord + vec2(uTexelSize.x, 0.0), 0.0, 1.0)).rgb;
                    vec3 w = texture2D(uDenoisedBokehTex, clamp(vTexCoord + vec2(-uTexelSize.x, 0.0), 0.0, 1.0)).rgb;
                    vec3 localAvg = (n + s + e + w) * 0.25;
                    vec3 detail = center - localAvg;
                    float mask = smoothstep(0.20, 0.80, subjectMatte);
                    sharpened = clamp(center + detail * (uSharpenAmount * mask), 0.0, 1.0);
                }

                // White balance gain
                vec3 rgb = clamp(sharpened * uWbGain, 0.0, 1.0);

                // Optional HDR shadow lift & highlight compression
                if (uHdrStrength > 0.01) {
                    float luma = dot(rgb, vec3(0.299, 0.587, 0.114));
                    float shadowLift = 1.0 + uHdrStrength * 0.28 * pow(1.0 - luma, 2.0);
                    vec3 hdr = rgb * shadowLift;
                    rgb = hdr / (1.0 + uHdrStrength * 0.18 * hdr);
                }

                // Skin-tone locus protection + 3D LUT grading
                float skinMask = detectSkinMask(rgb);
                vec3 lutColor = sample3dLut(rgb);

                vec3 balancedSkin = mix(rgb, lutColor, 1.0 - uSkinProtect * 0.65);
                float skinLuma = dot(balancedSkin, vec3(0.299, 0.587, 0.114));
                vec3 healthySkin = mix(vec3(skinLuma), balancedSkin, 1.04);
                vec3 finalRgb = mix(lutColor, clamp(healthySkin, 0.0, 1.0), skinMask * uSkinProtect);

                gl_FragColor = vec4(clamp(finalRgb, 0.0, 1.0), 1.0);
            }
        """
    }
}
