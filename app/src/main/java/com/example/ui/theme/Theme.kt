package com.example.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val LumenDarkColorScheme = darkColorScheme(
    primary = TungstenAmber,
    onPrimary = ObsidianBlack,
    primaryContainer = TungstenAmberSoft,
    onPrimaryContainer = TungstenAmber,
    secondary = FocusCyan,
    onSecondary = ObsidianBlack,
    background = ObsidianBlack,
    onBackground = OpticalWhite,
    surface = AnodizedSlate,
    onSurface = OpticalWhite,
    surfaceVariant = ElevatedHudSlate,
    onSurfaceVariant = MutedSilver,
    outline = HairlineDivider,
    error = AlertCrimson,
    onError = OpticalWhite,
)

@Composable
fun LumenCamTheme(
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = LumenDarkColorScheme,
        typography = LumenCamTypography,
        content = content,
    )
}
