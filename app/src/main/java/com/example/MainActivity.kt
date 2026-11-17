package com.example

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.ui.screens.CameraPermissionScreen
import com.example.ui.screens.GalleryScreen
import com.example.ui.screens.ViewfinderScreen
import com.example.ui.state.CameraViewModel
import com.example.ui.theme.LumenCamTheme

object CameraRoutes {
    const val VIEWFINDER = "viewfinder"
    const val GALLERY = "gallery"
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )

        setContent {
            LumenCamTheme {
                val cameraViewModel: CameraViewModel = viewModel()
                val uiState by cameraViewModel.uiState.collectAsStateWithLifecycle()
                val peakingBitmap by cameraViewModel.peakingBitmap.collectAsStateWithLifecycle()
                val livePortraitBitmap by cameraViewModel.livePortraitBitmap.collectAsStateWithLifecycle()
                val navController = rememberNavController()

                var hasRequestedPermissionOnce by remember { mutableStateOf(false) }
                var permanentlyDenied by remember { mutableStateOf(false) }

                val permissionLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.RequestPermission(),
                ) { granted ->
                    hasRequestedPermissionOnce = true
                    cameraViewModel.onCameraPermissionChanged(granted)
                    if (!granted) {
                        permanentlyDenied = !ActivityCompat.shouldShowRequestPermissionRationale(
                            this,
                            Manifest.permission.CAMERA,
                        )
                    } else {
                        permanentlyDenied = false
                    }
                }

                fun syncPermissionState() {
                    val granted = ContextCompat.checkSelfPermission(
                        this,
                        Manifest.permission.CAMERA,
                    ) == PackageManager.PERMISSION_GRANTED
                    cameraViewModel.onCameraPermissionChanged(granted)
                    if (granted) {
                        permanentlyDenied = false
                    }
                }

                LaunchedEffect(Unit) {
                    syncPermissionState()
                    if (!uiState.hasCameraPermission && !hasRequestedPermissionOnce) {
                        hasRequestedPermissionOnce = true
                        permissionLauncher.launch(Manifest.permission.CAMERA)
                    }
                }

                LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
                    syncPermissionState()
                }

                if (!uiState.hasCameraPermission) {
                    CameraPermissionScreen(
                        permanentlyDenied = permanentlyDenied,
                        onRequestPermission = {
                            permissionLauncher.launch(Manifest.permission.CAMERA)
                        },
                    )
                } else {
                    NavHost(
                        navController = navController,
                        startDestination = CameraRoutes.VIEWFINDER,
                    ) {
                        composable(CameraRoutes.VIEWFINDER) {
                            ViewfinderScreen(
                                uiState = uiState,
                                peakingBitmap = peakingBitmap,
                                livePortraitBitmap = livePortraitBitmap,
                                events = cameraViewModel.events,
                                onBindCamera = cameraViewModel::bindCamera,
                                onModeSelected = cameraViewModel::selectMode,
                                onFlashClick = cameraViewModel::cycleFlashMode,
                                onTimerClick = cameraViewModel::cycleTimerDuration,
                                onGridClick = cameraViewModel::toggleGrid,
                                onRawClick = cameraViewModel::toggleRawCapture,
                                onFocusPeakingClick = cameraViewModel::toggleFocusPeaking,
                                onFaceFocusClick = cameraViewModel::toggleFaceFocus,
                                onHdrClick = cameraViewModel::toggleHdr,
                                onTonePanelClick = cameraViewModel::toggleTonePanel,
                                onLutSelected = cameraViewModel::setLutPreset,
                                onAwbToggle = cameraViewModel::toggleAutoWhiteBalance,
                                onKelvinChanged = cameraViewModel::setKelvin,
                                onSharpnessChanged = cameraViewModel::setSubjectSharpenAmount,
                                onApertureChanged = cameraViewModel::setApertureFStop,
                                onBokehShapeToggle = cameraViewModel::toggleBokehShape,
                                onZoomSelected = cameraViewModel::selectZoomRatio,
                                onPinchZoom = cameraViewModel::onPinchZoom,
                                onTapToFocus = cameraViewModel::onTapToFocus,
                                onLongPressAeAfLock = cameraViewModel::onLongPressAeAfLock,
                                onUnlockAeAf = cameraViewModel::unlockAeAf,
                                onExposureIndexChanged = cameraViewModel::setExposureIndex,
                                onShutterClick = cameraViewModel::onShutterPressed,
                                onSwitchCameraClick = cameraViewModel::toggleLensFacing,
                                onGalleryClick = {
                                    cameraViewModel.refreshGallery()
                                    navController.navigate(CameraRoutes.GALLERY)
                                },
                            )
                        }

                        composable(CameraRoutes.GALLERY) {
                            GalleryScreen(
                                captures = uiState.recentCaptures,
                                onRenderEditPreview = cameraViewModel::renderPortraitEditPreview,
                                onSaveEditedCopy = cameraViewModel::saveEditedPortraitCopy,
                                onNavigateBack = {
                                    navController.popBackStack()
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}
