package com.example.ui.components

import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Camera
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.FlashAuto
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.GridOff
import androidx.compose.material.icons.filled.GridOn
import androidx.compose.material.icons.filled.HdrOn
import androidx.compose.material.icons.filled.Hexagon
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.R
import com.example.domain.model.BokehShape
import com.example.domain.model.CameraMode
import com.example.domain.model.ColorToneSettings
import com.example.domain.model.FlashMode
import com.example.domain.model.FocusReticleState
import com.example.domain.model.LutPreset
import com.example.domain.model.NormalizedRect
import com.example.domain.model.ThermalTier
import com.example.domain.model.TimerDuration
import com.example.ui.theme.AlertCrimson
import com.example.ui.theme.FocusCyan
import com.example.ui.theme.GlassOverlay
import com.example.ui.theme.HairlineDivider
import com.example.ui.theme.MutedSilver
import com.example.ui.theme.ObsidianBlack
import com.example.ui.theme.OpticalWhite
import com.example.ui.theme.ThermalGoodGreen
import com.example.ui.theme.ThermalWarmYellow
import com.example.ui.theme.TungstenAmber
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

@Composable
fun TopDslrControlBar(
    flashMode: FlashMode,
    hasFlashUnit: Boolean,
    timerDuration: TimerDuration,
    isGridEnabled: Boolean,
    supportsRawDng: Boolean,
    isRawEnabled: Boolean,
    isFocusPeakingEnabled: Boolean,
    isFaceFocusEnabled: Boolean,
    isHdrEnabled: Boolean,
    isTonePanelOpen: Boolean,
    activeLut: LutPreset,
    thermalTier: ThermalTier,
    onFlashClick: () -> Unit,
    onTimerClick: () -> Unit,
    onGridClick: () -> Unit,
    onRawClick: () -> Unit,
    onFocusPeakingClick: () -> Unit,
    onFaceFocusClick: () -> Unit,
    onHdrClick: () -> Unit,
    onTonePanelClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                color = GlassOverlay,
                shape = RoundedCornerShape(24.dp),
                border = BorderStroke(1.dp, HairlineDivider),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                ) {
                    IconButton(
                        onClick = onFlashClick,
                        enabled = hasFlashUnit && thermalTier < ThermalTier.SEVERE,
                        modifier = Modifier
                            .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                            .testTag("flash_toggle_button"),
                    ) {
                        val (icon, tint) = when {
                            !hasFlashUnit || flashMode == FlashMode.OFF ->
                                Icons.Filled.FlashOff to MutedSilver
                            flashMode == FlashMode.AUTO ->
                                Icons.Filled.FlashAuto to OpticalWhite
                            else ->
                                Icons.Filled.FlashOn to TungstenAmber
                        }
                        Icon(
                            imageVector = icon,
                            contentDescription = stringResource(R.string.cd_flash_mode),
                            tint = tint,
                            modifier = Modifier.size(20.dp),
                        )
                    }

                    Box(
                        modifier = Modifier
                            .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                            .clip(RoundedCornerShape(20.dp))
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = ripple(bounded = true),
                                role = Role.Button,
                                onClick = onTimerClick,
                            )
                            .semantics(mergeDescendants = true) {}
                            .padding(horizontal = 8.dp)
                            .testTag("timer_toggle_button"),
                        contentAlignment = Alignment.Center,
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Filled.Timer,
                                contentDescription = stringResource(R.string.cd_timer_mode),
                                tint = if (timerDuration != TimerDuration.OFF) TungstenAmber else MutedSilver,
                                modifier = Modifier.size(18.dp),
                            )
                            if (timerDuration != TimerDuration.OFF) {
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = timerDuration.label,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = TungstenAmber,
                                )
                            }
                        }
                    }

                    IconButton(
                        onClick = onGridClick,
                        modifier = Modifier
                            .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                            .testTag("grid_toggle_button"),
                    ) {
                        Icon(
                            imageVector = if (isGridEnabled) Icons.Filled.GridOn else Icons.Filled.GridOff,
                            contentDescription = stringResource(R.string.cd_grid_overlay),
                            tint = if (isGridEnabled) TungstenAmber else MutedSilver,
                            modifier = Modifier.size(20.dp),
                        )
                    }

                    if (supportsRawDng) {
                        Box(
                            modifier = Modifier
                                .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                                .clip(RoundedCornerShape(16.dp))
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = ripple(bounded = true),
                                    role = Role.Button,
                                    onClick = onRawClick,
                                )
                                .padding(horizontal = 8.dp)
                                .testTag("raw_toggle_button"),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = "RAW",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = if (isRawEnabled) TungstenAmber else MutedSilver,
                            )
                        }
                    }
                }
            }

            ThermalStatusPill(thermalTier = thermalTier)
        }

        // Optical Focus + Tone Aids row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            HudToggleChip(
                label = stringResource(R.string.badge_peaking),
                icon = Icons.Filled.CenterFocusStrong,
                isActive = isFocusPeakingEnabled,
                activeColor = FocusCyan,
                contentDescription = stringResource(R.string.cd_focus_peaking),
                onClick = onFocusPeakingClick,
                modifier = Modifier.testTag("focus_peaking_button"),
            )

            HudToggleChip(
                label = stringResource(R.string.badge_face_af),
                icon = Icons.Filled.Face,
                isActive = isFaceFocusEnabled,
                activeColor = TungstenAmber,
                contentDescription = stringResource(R.string.cd_face_focus),
                onClick = onFaceFocusClick,
                modifier = Modifier.testTag("face_focus_button"),
            )

            HudToggleChip(
                label = stringResource(R.string.badge_hdr),
                icon = Icons.Filled.HdrOn,
                isActive = isHdrEnabled,
                activeColor = ThermalGoodGreen,
                contentDescription = stringResource(R.string.cd_hdr_mode),
                onClick = onHdrClick,
                modifier = Modifier.testTag("hdr_toggle_button"),
            )

            HudToggleChip(
                label = if (activeLut == LutPreset.NATURAL) {
                    stringResource(R.string.badge_tone)
                } else {
                    activeLut.label.uppercase(Locale.US)
                },
                icon = Icons.Filled.Palette,
                isActive = isTonePanelOpen || activeLut != LutPreset.NATURAL,
                activeColor = TungstenAmber,
                contentDescription = stringResource(R.string.cd_lut_selector),
                onClick = onTonePanelClick,
                modifier = Modifier.testTag("tone_panel_button"),
            )
        }
    }
}

@Composable
private fun HudToggleChip(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    isActive: Boolean,
    activeColor: Color,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        color = if (isActive) activeColor.copy(alpha = 0.18f) else GlassOverlay,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, if (isActive) activeColor else HairlineDivider),
        modifier = modifier
            .defaultMinSize(minWidth = 48.dp, minHeight = 36.dp)
            .clip(RoundedCornerShape(16.dp))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = ripple(bounded = true),
                role = Role.Button,
                onClickLabel = contentDescription,
                onClick = onClick,
            )
            .semantics(mergeDescendants = true) {},
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 6.dp),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (isActive) activeColor else MutedSilver,
                modifier = Modifier.size(14.dp),
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal,
                color = if (isActive) activeColor else MutedSilver,
            )
        }
    }
}

@Composable
fun ColorToneControlPanel(
    colorTone: ColorToneSettings,
    showSharpnessSlider: Boolean,
    onLutSelected: (LutPreset) -> Unit,
    onAwbToggle: () -> Unit,
    onKelvinChanged: (Int) -> Unit,
    onSharpnessChanged: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        color = GlassOverlay,
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, HairlineDivider),
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            // 3D LUT Preset Pills
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                LutPreset.entries.forEach { preset ->
                    val selected = colorTone.lutPreset == preset
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .defaultMinSize(minHeight = 34.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(if (selected) TungstenAmber else ObsidianBlack.copy(alpha = 0.55f))
                            .border(
                                1.dp,
                                if (selected) TungstenAmber else HairlineDivider,
                                RoundedCornerShape(12.dp),
                            )
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = ripple(bounded = true),
                                role = Role.Button,
                                onClick = { onLutSelected(preset) },
                            )
                            .testTag("lut_preset_${preset.name.lowercase()}"),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = preset.label,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                            color = if (selected) ObsidianBlack else OpticalWhite,
                        )
                    }
                }
            }

            // AWB + Manual Kelvin Slider (2800K - 8000K)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Box(
                    modifier = Modifier
                        .defaultMinSize(minWidth = 48.dp, minHeight = 32.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(
                            if (colorTone.isAutoWhiteBalance) TungstenAmber else ObsidianBlack.copy(alpha = 0.6f)
                        )
                        .border(1.dp, HairlineDivider, RoundedCornerShape(10.dp))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = ripple(bounded = true),
                            role = Role.Button,
                            onClick = onAwbToggle,
                        )
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                        .testTag("awb_toggle_button"),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = stringResource(R.string.badge_awb),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = if (colorTone.isAutoWhiteBalance) ObsidianBlack else OpticalWhite,
                    )
                }

                Spacer(modifier = Modifier.width(8.dp))

                Slider(
                    value = colorTone.kelvin.toFloat(),
                    onValueChange = { onKelvinChanged((it / 100f).roundToInt() * 100) },
                    valueRange = 2800f..8000f,
                    colors = SliderDefaults.colors(
                        thumbColor = if (colorTone.isAutoWhiteBalance) MutedSilver else TungstenAmber,
                        activeTrackColor = if (colorTone.isAutoWhiteBalance) MutedSilver else TungstenAmber,
                        inactiveTrackColor = HairlineDivider,
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .testTag("kelvin_slider"),
                )

                Spacer(modifier = Modifier.width(8.dp))

                Text(
                    text = if (colorTone.isAutoWhiteBalance) "AUTO" else "${colorTone.kelvin}K",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (colorTone.isAutoWhiteBalance) MutedSilver else TungstenAmber,
                )
            }

            if (showSharpnessSlider) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = "SHARP",
                        style = MaterialTheme.typography.labelSmall,
                        color = MutedSilver,
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Slider(
                        value = colorTone.subjectSharpenAmount,
                        onValueChange = onSharpnessChanged,
                        valueRange = 0f..0.75f,
                        colors = SliderDefaults.colors(
                            thumbColor = FocusCyan,
                            activeTrackColor = FocusCyan,
                            inactiveTrackColor = HairlineDivider,
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("subject_sharpness_slider"),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "${(colorTone.subjectSharpenAmount * 100).roundToInt()}%",
                        style = MaterialTheme.typography.labelSmall,
                        color = FocusCyan,
                    )
                }
            }
        }
    }
}

@Composable
fun ThermalStatusPill(
    thermalTier: ThermalTier,
    modifier: Modifier = Modifier,
) {
    val (dotColor, label) = when (thermalTier) {
        ThermalTier.NORMAL -> ThermalGoodGreen to stringResource(R.string.status_thermal_normal)
        ThermalTier.MODERATE -> ThermalWarmYellow to stringResource(R.string.status_thermal_throttled)
        ThermalTier.SEVERE, ThermalTier.CRITICAL -> AlertCrimson to stringResource(R.string.status_thermal_throttled)
    }

    Surface(
        color = GlassOverlay,
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.dp, HairlineDivider),
        modifier = modifier.semantics(mergeDescendants = true) {},
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(dotColor),
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = OpticalWhite,
            )
        }
    }
}

@Composable
fun ViewfinderGridOverlay(
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier.fillMaxSize()) {
        val lineColor = Color.White.copy(alpha = 0.24f)
        val strokePx = 1.dp.toPx()

        val thirdW = size.width / 3f
        val thirdH = size.height / 3f

        for (i in 1..2) {
            drawLine(
                color = lineColor,
                start = Offset(thirdW * i, 0f),
                end = Offset(thirdW * i, size.height),
                strokeWidth = strokePx,
            )
            drawLine(
                color = lineColor,
                start = Offset(0f, thirdH * i),
                end = Offset(size.width, thirdH * i),
                strokeWidth = strokePx,
            )
        }
    }
}

@Composable
fun LivePortraitOverlay(
    portraitBitmap: ImageBitmap?,
    modifier: Modifier = Modifier,
) {
    if (portraitBitmap != null) {
        Image(
            bitmap = portraitBitmap,
            contentDescription = null,
            contentScale = ContentScale.FillBounds,
            modifier = modifier.fillMaxSize(),
        )
    }
}

@Composable
fun FocusPeakingOverlay(
    peakingBitmap: ImageBitmap?,
    modifier: Modifier = Modifier,
) {
    if (peakingBitmap != null) {
        Image(
            bitmap = peakingBitmap,
            contentDescription = null,
            contentScale = ContentScale.FillBounds,
            modifier = modifier.fillMaxSize(),
        )
    }
}

@Composable
fun FaceTrackingOverlay(
    faces: List<NormalizedRect>,
    modifier: Modifier = Modifier,
) {
    if (faces.isEmpty()) return

    Canvas(modifier = modifier.fillMaxSize()) {
        val strokePx = 1.8.dp.toPx()
        faces.forEachIndexed { index, rect ->
            val left = rect.left * size.width
            val top = rect.top * size.height
            val right = rect.right * size.width
            val bottom = rect.bottom * size.height
            val boxW = right - left
            val boxH = bottom - top
            val corner = minOf(boxW, boxH) * 0.22f
            val color = if (index == 0) TungstenAmber else MutedSilver.copy(alpha = 0.7f)

            drawLine(color, Offset(left, top), Offset(left + corner, top), strokePx, StrokeCap.Round)
            drawLine(color, Offset(left, top), Offset(left, top + corner), strokePx, StrokeCap.Round)
            drawLine(color, Offset(right, top), Offset(right - corner, top), strokePx, StrokeCap.Round)
            drawLine(color, Offset(right, top), Offset(right, top + corner), strokePx, StrokeCap.Round)
            drawLine(color, Offset(left, bottom), Offset(left + corner, bottom), strokePx, StrokeCap.Round)
            drawLine(color, Offset(left, bottom), Offset(left, bottom - corner), strokePx, StrokeCap.Round)
            drawLine(color, Offset(right, bottom), Offset(right - corner, bottom), strokePx, StrokeCap.Round)
            drawLine(color, Offset(right, bottom), Offset(right, bottom - corner), strokePx, StrokeCap.Round)
        }
    }
}

@Composable
fun FocusReticleOverlay(
    reticle: FocusReticleState?,
    modifier: Modifier = Modifier,
) {
    if (reticle == null) return

    val animatedScale by animateFloatAsState(
        targetValue = if (reticle.isFocusing) 1.25f else 1.0f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium,
        ),
        label = "reticle_scale",
    )

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val boxSizePx = with(density) { 74.dp.toPx() }
        val maxWidthPx = with(density) { maxWidth.toPx() }
        val maxHeightPx = with(density) { maxHeight.toPx() }

        val targetX = (reticle.normalizedX * maxWidthPx - boxSizePx / 2f)
            .coerceIn(0f, (maxWidthPx - boxSizePx).coerceAtLeast(0f))
        val targetY = (reticle.normalizedY * maxHeightPx - boxSizePx / 2f)
            .coerceIn(0f, (maxHeightPx - boxSizePx).coerceAtLeast(0f))

        val reticleColor = if (reticle.isLocked) TungstenAmber else OpticalWhite

        Box(
            modifier = Modifier
                .offset { IntOffset(targetX.roundToInt(), targetY.roundToInt()) }
                .size(74.dp)
                .scale(animatedScale),
            contentAlignment = Alignment.Center,
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val stroke = 2.dp.toPx()
                val pad = 4.dp.toPx()
                drawRect(
                    color = reticleColor,
                    topLeft = Offset(pad, pad),
                    size = androidx.compose.ui.geometry.Size(
                        size.width - pad * 2,
                        size.height - pad * 2,
                    ),
                    style = Stroke(width = stroke),
                )
            }
            if (reticle.isLocked) {
                Surface(
                    color = ObsidianBlack.copy(alpha = 0.8f),
                    shape = CircleShape,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .size(22.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Filled.Lock,
                            contentDescription = null,
                            tint = TungstenAmber,
                            modifier = Modifier.size(13.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun AeAfLockChip(
    isLocked: Boolean,
    onUnlockClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = isLocked,
        enter = fadeIn() + scaleIn(initialScale = 0.85f),
        exit = fadeOut() + scaleOut(targetScale = 0.85f),
        modifier = modifier,
    ) {
        Surface(
            color = TungstenAmber,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier
                .defaultMinSize(minHeight = 36.dp)
                .clip(RoundedCornerShape(16.dp))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = ripple(bounded = true),
                    role = Role.Button,
                    onClickLabel = stringResource(R.string.cd_unlock_ae_af),
                    onClick = onUnlockClick,
                )
                .testTag("ae_af_lock_chip"),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            ) {
                Icon(
                    imageVector = Icons.Filled.Lock,
                    contentDescription = null,
                    tint = ObsidianBlack,
                    modifier = Modifier.size(14.dp),
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "${stringResource(R.string.badge_af_ae_locked)} • TAP TO UNLOCK",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = ObsidianBlack,
                )
            }
        }
    }
}

@Composable
fun ApertureControlBar(
    apertureFStop: Float,
    bokehShape: BokehShape,
    isHardwareApertureActive: Boolean,
    onApertureChanged: (Float) -> Unit,
    onBokehShapeToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        color = GlassOverlay,
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, HairlineDivider),
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
        ) {
            Icon(
                imageVector = Icons.Filled.Camera,
                contentDescription = null,
                tint = TungstenAmber,
                modifier = Modifier.size(18.dp),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = String.format(Locale.US, "f/%.1f", apertureFStop),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = TungstenAmber,
            )
            if (isHardwareApertureActive) {
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = "HW",
                    style = MaterialTheme.typography.labelSmall,
                    color = ThermalGoodGreen,
                )
            }
            Spacer(modifier = Modifier.width(8.dp))

            Slider(
                value = apertureFStop,
                onValueChange = { raw ->
                    val snapped = (raw * 10f).roundToInt() / 10f
                    onApertureChanged(snapped)
                },
                valueRange = 1.4f..16.0f,
                colors = SliderDefaults.colors(
                    thumbColor = TungstenAmber,
                    activeTrackColor = TungstenAmber,
                    inactiveTrackColor = HairlineDivider,
                ),
                modifier = Modifier
                    .weight(1f)
                    .testTag("aperture_slider"),
            )

            Spacer(modifier = Modifier.width(6.dp))
            Surface(
                color = ObsidianBlack.copy(alpha = 0.65f),
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, HairlineDivider),
                modifier = Modifier
                    .defaultMinSize(minWidth = 48.dp, minHeight = 36.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = ripple(bounded = true),
                        role = Role.Button,
                        onClick = onBokehShapeToggle,
                    )
                    .testTag("bokeh_shape_button"),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Hexagon,
                        contentDescription = null,
                        tint = OpticalWhite,
                        modifier = Modifier.size(13.dp),
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = bokehShape.label,
                        style = MaterialTheme.typography.labelSmall,
                        color = OpticalWhite,
                    )
                }
            }
        }
    }
}

@Composable
fun ZoomControlStrip(
    currentZoomRatio: Float,
    minZoomRatio: Float,
    maxZoomRatio: Float,
    onZoomSelected: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    var showFineZoomSlider by remember { mutableStateOf(false) }

    val zoomStops = remember(minZoomRatio, maxZoomRatio) {
        buildList {
            if (minZoomRatio <= 0.72f) {
                add(0.6f.coerceAtLeast(minZoomRatio))
            }
            add(1.0f.coerceIn(minZoomRatio, maxZoomRatio))
            if (maxZoomRatio >= 1.8f) add(2.0f)
            if (maxZoomRatio >= 2.8f) add(3.0f)
            if (maxZoomRatio >= 4.8f) add(5.0f)
        }.distinct()
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = modifier,
    ) {
        if (showFineZoomSlider && maxZoomRatio > minZoomRatio + 0.05f) {
            Surface(
                color = GlassOverlay,
                shape = RoundedCornerShape(18.dp),
                border = BorderStroke(1.dp, HairlineDivider),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 28.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 2.dp),
                ) {
                    Text(
                        text = String.format(Locale.US, "%.1fx", currentZoomRatio),
                        style = MaterialTheme.typography.labelSmall,
                        color = TungstenAmber,
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Slider(
                        value = currentZoomRatio.coerceIn(minZoomRatio, maxZoomRatio),
                        onValueChange = onZoomSelected,
                        valueRange = minZoomRatio..maxZoomRatio,
                        colors = SliderDefaults.colors(
                            thumbColor = TungstenAmber,
                            activeTrackColor = TungstenAmber,
                            inactiveTrackColor = HairlineDivider,
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("fine_zoom_slider"),
                    )
                }
            }
        }

        Surface(
            color = GlassOverlay,
            shape = RoundedCornerShape(24.dp),
            border = BorderStroke(1.dp, HairlineDivider),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp),
            ) {
                zoomStops.forEach { stop ->
                    val isSelected = abs(currentZoomRatio - stop) < 0.25f
                    val label = if (stop < 1f) {
                        String.format(Locale.US, "%.1fx", stop)
                    } else {
                        "${stop.roundToInt()}x"
                    }

                    Box(
                        modifier = Modifier
                            .defaultMinSize(minWidth = 44.dp, minHeight = 36.dp)
                            .clip(CircleShape)
                            .background(if (isSelected) TungstenAmber else Color.Transparent)
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = ripple(bounded = true),
                                role = Role.Button,
                                onClick = {
                                    if (isSelected && maxZoomRatio > minZoomRatio + 0.05f) {
                                        showFineZoomSlider = !showFineZoomSlider
                                    } else {
                                        onZoomSelected(stop)
                                    }
                                },
                            )
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                            .testTag("zoom_pill_${label}"),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = if (isSelected) {
                                String.format(Locale.US, "%.1fx", currentZoomRatio)
                            } else {
                                label
                            },
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                            color = if (isSelected) ObsidianBlack else OpticalWhite,
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun ExposureCompensationBar(
    exposureIndex: Int,
    exposureRange: IntRange,
    exposureStepEv: Float,
    onExposureIndexChanged: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (exposureRange.first >= exposureRange.last) return

    val evValue = exposureIndex * exposureStepEv
    val evText = if (evValue >= 0f) {
        String.format(Locale.US, "+%.1f EV", evValue)
    } else {
        String.format(Locale.US, "%.1f EV", evValue)
    }

    Surface(
        color = GlassOverlay,
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.dp, HairlineDivider),
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 2.dp),
        ) {
            Icon(
                imageVector = Icons.Filled.WbSunny,
                contentDescription = null,
                tint = if (exposureIndex != 0) TungstenAmber else MutedSilver,
                modifier = Modifier.size(16.dp),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Slider(
                value = exposureIndex.toFloat(),
                onValueChange = { onExposureIndexChanged(it.roundToInt()) },
                valueRange = exposureRange.first.toFloat()..exposureRange.last.toFloat(),
                colors = SliderDefaults.colors(
                    thumbColor = TungstenAmber,
                    activeTrackColor = TungstenAmber,
                    inactiveTrackColor = HairlineDivider,
                ),
                modifier = Modifier
                    .weight(1f)
                    .testTag("exposure_compensation_slider"),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = evText,
                style = MaterialTheme.typography.labelSmall,
                color = if (exposureIndex != 0) TungstenAmber else MutedSilver,
            )
        }
    }
}

@Composable
fun CountdownOverlay(
    secondsRemaining: Int,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = secondsRemaining > 0,
        enter = fadeIn() + scaleIn(initialScale = 0.7f),
        exit = fadeOut() + scaleOut(targetScale = 1.15f),
        modifier = modifier,
    ) {
        Surface(
            color = GlassOverlay,
            shape = CircleShape,
            border = BorderStroke(1.5.dp, TungstenAmber),
        ) {
            Box(
                modifier = Modifier.size(96.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = secondsRemaining.toString(),
                    style = MaterialTheme.typography.displaySmall.copy(fontSize = 44.sp),
                    color = TungstenAmber,
                )
            }
        }
    }
}

@Composable
fun ModeSelectorStrip(
    selectedMode: CameraMode,
    onModeSelected: (CameraMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CameraMode.entries.forEach { mode ->
            val isSelected = mode == selectedMode
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = ripple(bounded = true),
                        role = Role.Tab,
                        onClick = { onModeSelected(mode) },
                    )
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .testTag("mode_tab_${mode.name.lowercase()}"),
            ) {
                Text(
                    text = mode.label,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                    color = if (isSelected) TungstenAmber else MutedSilver,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Box(
                    modifier = Modifier
                        .size(width = 18.dp, height = 2.5.dp)
                        .clip(CircleShape)
                        .background(if (isSelected) TungstenAmber else Color.Transparent),
                )
            }
        }
    }
}

@Composable
fun ShutterControlDeck(
    latestCaptureUri: Uri?,
    isCapturing: Boolean,
    isCameraReady: Boolean,
    canSwitchCamera: Boolean,
    selectedMode: CameraMode,
    onModeSelected: (CameraMode) -> Unit,
    onShutterClick: () -> Unit,
    onGalleryClick: () -> Unit,
    onSwitchCameraClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shutterScale by animateFloatAsState(
        targetValue = if (isCapturing) 0.88f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium,
        ),
        label = "shutter_scale",
    )

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(ObsidianBlack.copy(alpha = 0.88f))
            .padding(top = 6.dp, bottom = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        ModeSelectorStrip(
            selectedMode = selectedMode,
            onModeSelected = onModeSelected,
        )

        Spacer(modifier = Modifier.height(10.dp))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 28.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(54.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(GlassOverlay)
                    .border(1.5.dp, HairlineDivider, RoundedCornerShape(14.dp))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = ripple(bounded = true),
                        role = Role.Button,
                        onClick = onGalleryClick,
                    )
                    .testTag("gallery_button"),
                contentAlignment = Alignment.Center,
            ) {
                if (latestCaptureUri != null) {
                    AsyncImage(
                        model = latestCaptureUri,
                        contentDescription = stringResource(R.string.cd_gallery),
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Icon(
                        imageVector = Icons.Filled.PhotoLibrary,
                        contentDescription = stringResource(R.string.cd_gallery),
                        tint = OpticalWhite,
                        modifier = Modifier.size(24.dp),
                    )
                }
            }

            Box(
                modifier = Modifier
                    .size(78.dp)
                    .scale(shutterScale)
                    .clip(CircleShape)
                    .border(3.dp, OpticalWhite, CircleShape)
                    .padding(6.dp)
                    .clip(CircleShape)
                    .background(
                        if (isCameraReady) {
                            if (selectedMode == CameraMode.PORTRAIT) TungstenAmber else OpticalWhite
                        } else {
                            MutedSilver.copy(alpha = 0.4f)
                        }
                    )
                    .clickable(
                        enabled = isCameraReady && !isCapturing,
                        interactionSource = remember { MutableInteractionSource() },
                        indication = ripple(bounded = true),
                        role = Role.Button,
                        onClickLabel = stringResource(R.string.cd_shutter),
                        onClick = onShutterClick,
                    )
                    .testTag("shutter_button"),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .size(22.dp)
                        .clip(CircleShape)
                        .border(1.dp, ObsidianBlack.copy(alpha = 0.25f), CircleShape),
                )
            }

            Surface(
                color = GlassOverlay,
                shape = CircleShape,
                border = BorderStroke(1.dp, HairlineDivider),
            ) {
                IconButton(
                    onClick = onSwitchCameraClick,
                    enabled = canSwitchCamera,
                    modifier = Modifier
                        .size(54.dp)
                        .testTag("switch_camera_button"),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Cameraswitch,
                        contentDescription = stringResource(R.string.cd_switch_camera),
                        tint = if (canSwitchCamera) OpticalWhite else MutedSilver.copy(alpha = 0.4f),
                        modifier = Modifier.size(24.dp),
                    )
                }
            }
        }
    }
}
