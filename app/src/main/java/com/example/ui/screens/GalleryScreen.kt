package com.example.ui.screens

import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.example.R
import com.example.domain.model.BokehShape
import com.example.domain.model.CapturedMediaItem
import com.example.domain.model.LutPreset
import com.example.domain.model.PortraitSidecarInfo
import com.example.ui.components.ApertureControlBar
import com.example.ui.theme.AnodizedSlate
import com.example.ui.theme.GlassOverlay
import com.example.ui.theme.HairlineDivider
import com.example.ui.theme.MutedSilver
import com.example.ui.theme.ObsidianBlack
import com.example.ui.theme.OpticalWhite
import com.example.ui.theme.TungstenAmber
import kotlinx.coroutines.launch
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GalleryScreen(
    captures: List<CapturedMediaItem>,
    onRenderEditPreview: suspend (PortraitSidecarInfo, Float, Float, Float, BokehShape, LutPreset) -> Bitmap?,
    onSaveEditedCopy: (PortraitSidecarInfo, Float, Float, Float, BokehShape, LutPreset, (Uri?) -> Unit) -> Unit,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    var selectedItem by remember { mutableStateOf<CapturedMediaItem?>(null) }
    var isEditingPortrait by remember { mutableStateOf(false) }

    var editFStop by remember { mutableFloatStateOf(1.8f) }
    var editFocusX by remember { mutableFloatStateOf(0.5f) }
    var editFocusY by remember { mutableFloatStateOf(0.42f) }
    var editBokehShape by remember { mutableStateOf(BokehShape.HEXAGON) }
    var editLutPreset by remember { mutableStateOf(LutPreset.NATURAL) }
    var editedPreviewBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var isSavingCopy by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        onDispose {
            editedPreviewBitmap?.recycle()
        }
    }

    val activeSidecar = selectedItem?.portraitSidecar
    LaunchedEffect(
        isEditingPortrait,
        activeSidecar,
        editFStop,
        editFocusX,
        editFocusY,
        editBokehShape,
        editLutPreset,
    ) {
        if (isEditingPortrait && activeSidecar != null) {
            val rendered = onRenderEditPreview(
                activeSidecar,
                editFStop,
                editFocusX,
                editFocusY,
                editBokehShape,
                editLutPreset,
            )
            val prev = editedPreviewBitmap
            editedPreviewBitmap = rendered
            if (prev != null && prev !== rendered && !prev.isRecycled) {
                prev.recycle()
            }
        }
    }

    BackHandler(enabled = selectedItem != null) {
        if (isEditingPortrait) {
            isEditingPortrait = false
        } else {
            selectedItem = null
        }
    }

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .background(ObsidianBlack),
        containerColor = ObsidianBlack,
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = selectedItem?.displayName ?: stringResource(R.string.gallery_title),
                        style = MaterialTheme.typography.titleMedium,
                        color = OpticalWhite,
                        maxLines = 1,
                    )
                },
                navigationIcon = {
                    IconButton(
                        onClick = {
                            if (isEditingPortrait) {
                                isEditingPortrait = false
                            } else if (selectedItem != null) {
                                selectedItem = null
                            } else {
                                onNavigateBack()
                            }
                        },
                        modifier = Modifier.testTag("gallery_back_button"),
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.cd_back),
                            tint = OpticalWhite,
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = AnodizedSlate,
                    titleContentColor = OpticalWhite,
                ),
                modifier = Modifier.statusBarsPadding(),
            )
        },
    ) { innerPadding ->
        val activeItem = selectedItem
        if (activeItem != null) {
            var imageBoxSize by remember { mutableStateOf(IntSize.Zero) }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .navigationBarsPadding(),
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(12.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(RoundedCornerShape(16.dp))
                            .onSizeChanged { imageBoxSize = it }
                            .pointerInput(isEditingPortrait) {
                                if (!isEditingPortrait) return@pointerInput
                                detectTapGestures { offset ->
                                    if (imageBoxSize.width > 0 && imageBoxSize.height > 0) {
                                        editFocusX = (offset.x / imageBoxSize.width).coerceIn(0.02f, 0.98f)
                                        editFocusY = (offset.y / imageBoxSize.height).coerceIn(0.02f, 0.98f)
                                    }
                                }
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        val previewBmp = editedPreviewBitmap
                        if (isEditingPortrait && previewBmp != null && !previewBmp.isRecycled) {
                            Image(
                                bitmap = previewBmp.asImageBitmap(),
                                contentDescription = activeItem.displayName,
                                contentScale = ContentScale.Fit,
                                modifier = Modifier.fillMaxSize(),
                            )
                        } else {
                            AsyncImage(
                                model = activeItem.uri,
                                contentDescription = activeItem.displayName,
                                contentScale = ContentScale.Fit,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }

                        if (isEditingPortrait) {
                            Canvas(modifier = Modifier.fillMaxSize()) {
                                val cx = editFocusX * size.width
                                val cy = editFocusY * size.height
                                drawCircle(
                                    color = TungstenAmber,
                                    radius = 24.dp.toPx(),
                                    center = Offset(cx, cy),
                                    style = Stroke(width = 2.dp.toPx()),
                                )
                                drawCircle(
                                    color = TungstenAmber,
                                    radius = 4.dp.toPx(),
                                    center = Offset(cx, cy),
                                )
                            }
                        }
                    }
                }

                if (isEditingPortrait && activeSidecar != null) {
                    Surface(
                        color = AnodizedSlate,
                        border = BorderStroke(1.dp, HairlineDivider),
                        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 14.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text(
                                text = stringResource(R.string.tap_to_refocus_hint),
                                style = MaterialTheme.typography.labelSmall,
                                color = MutedSilver,
                            )
                            ApertureControlBar(
                                apertureFStop = editFStop,
                                bokehShape = editBokehShape,
                                isHardwareApertureActive = false,
                                onApertureChanged = { editFStop = it },
                                onBokehShapeToggle = { editBokehShape = editBokehShape.toggle() },
                            )
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 20.dp),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                LutPreset.entries.forEach { preset ->
                                    val selected = editLutPreset == preset
                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .defaultMinSize(minHeight = 36.dp)
                                            .clip(RoundedCornerShape(10.dp))
                                            .background(
                                                if (selected) TungstenAmber else ObsidianBlack.copy(alpha = 0.65f)
                                            )
                                            .border(
                                                1.dp,
                                                if (selected) TungstenAmber else HairlineDivider,
                                                RoundedCornerShape(10.dp),
                                            )
                                            .clickable { editLutPreset = preset }
                                            .testTag("gallery_lut_${preset.name.lowercase()}"),
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
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 20.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                OutlinedButton(
                                    onClick = { isEditingPortrait = false },
                                    border = BorderStroke(1.dp, HairlineDivider),
                                    modifier = Modifier.weight(1f),
                                ) {
                                    Text(
                                        text = stringResource(R.string.cancel_edit),
                                        color = OpticalWhite,
                                    )
                                }
                                Button(
                                    onClick = {
                                        isSavingCopy = true
                                        onSaveEditedCopy(
                                            activeSidecar,
                                            editFStop,
                                            editFocusX,
                                            editFocusY,
                                            editBokehShape,
                                            editLutPreset,
                                        ) { savedUri ->
                                            isSavingCopy = false
                                            isEditingPortrait = false
                                            if (savedUri != null) {
                                                scope.launch {
                                                    snackbarHostState.showSnackbar(
                                                        context.getString(R.string.edited_saved_msg)
                                                    )
                                                }
                                            }
                                        }
                                    },
                                    enabled = !isSavingCopy,
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = TungstenAmber,
                                        contentColor = ObsidianBlack,
                                    ),
                                    modifier = Modifier
                                        .weight(1f)
                                        .testTag("save_edited_portrait_button"),
                                ) {
                                    if (isSavingCopy) {
                                        CircularProgressIndicator(
                                            color = ObsidianBlack,
                                            strokeWidth = 2.dp,
                                            modifier = Modifier.size(16.dp),
                                        )
                                    } else {
                                        Text(text = stringResource(R.string.save_edited_copy))
                                    }
                                }
                            }
                        }
                    }
                } else {
                    Surface(
                        color = AnodizedSlate,
                        border = BorderStroke(1.dp, HairlineDivider),
                        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 20.dp, vertical = 14.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column {
                                Text(
                                    text = "${activeItem.width} x ${activeItem.height}",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = OpticalWhite,
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                val mb = activeItem.sizeBytes / (1024f * 1024f)
                                Text(
                                    text = String.format(Locale.US, "%.2f MB", mb),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MutedSilver,
                                )
                            }

                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                if (activeItem.portraitSidecar != null) {
                                    Button(
                                        onClick = {
                                            val sc = activeItem.portraitSidecar
                                            editFStop = sc.apertureFStop
                                            editFocusX = sc.focusX
                                            editFocusY = sc.focusY
                                            editBokehShape = sc.bokehShape
                                            editLutPreset = sc.lutPreset
                                            isEditingPortrait = true
                                        },
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = TungstenAmber,
                                            contentColor = ObsidianBlack,
                                        ),
                                        shape = RoundedCornerShape(12.dp),
                                        modifier = Modifier.testTag("refocus_aperture_button"),
                                    ) {
                                        Icon(
                                            imageVector = Icons.Filled.Tune,
                                            contentDescription = null,
                                            modifier = Modifier.size(16.dp),
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = stringResource(R.string.edit_portrait_button),
                                            style = MaterialTheme.typography.labelSmall,
                                        )
                                    }
                                }

                                Surface(
                                    color = GlassOverlay,
                                    shape = RoundedCornerShape(12.dp),
                                    border = BorderStroke(
                                        1.dp,
                                        if (activeItem.hasRawCompanion) TungstenAmber else HairlineDivider,
                                    ),
                                ) {
                                    Text(
                                        text = if (activeItem.hasRawCompanion) "RAW + JPEG" else "JPEG",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (activeItem.hasRawCompanion) TungstenAmber else OpticalWhite,
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        } else if (captures.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(32.dp),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Filled.CameraAlt,
                        contentDescription = null,
                        tint = MutedSilver,
                        modifier = Modifier.size(48.dp),
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = stringResource(R.string.gallery_empty_title),
                        style = MaterialTheme.typography.titleMedium,
                        color = OpticalWhite,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.gallery_empty_subtitle),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MutedSilver,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 112.dp),
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .navigationBarsPadding(),
            ) {
                items(captures, key = { it.id }) { item ->
                    Box(
                        modifier = Modifier
                            .aspectRatio(1f)
                            .clip(RoundedCornerShape(12.dp))
                            .background(AnodizedSlate)
                            .clickable {
                                isEditingPortrait = false
                                selectedItem = item
                            }
                            .testTag("gallery_item_${item.id}"),
                    ) {
                        AsyncImage(
                            model = item.uri,
                            contentDescription = item.displayName,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                        if (item.hasRawCompanion || item.portraitSidecar != null) {
                            Surface(
                                color = ObsidianBlack.copy(alpha = 0.78f),
                                shape = RoundedCornerShape(6.dp),
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(6.dp),
                            ) {
                                Text(
                                    text = when {
                                        item.portraitSidecar != null ->
                                            String.format(Locale.US, "f/%.1f", item.portraitSidecar.apertureFStop)
                                        else -> "RAW"
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = TungstenAmber,
                                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
