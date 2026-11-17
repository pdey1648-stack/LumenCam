package com.example.ui.screens

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.ui.theme.AnodizedSlate
import com.example.ui.theme.HairlineDivider
import com.example.ui.theme.MutedSilver
import com.example.ui.theme.ObsidianBlack
import com.example.ui.theme.OpticalWhite
import com.example.ui.theme.TungstenAmber
import com.example.ui.theme.TungstenAmberSoft

@Composable
fun CameraPermissionScreen(
    permanentlyDenied: Boolean,
    onRequestPermission: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(ObsidianBlack, AnodizedSlate, ObsidianBlack),
                )
            )
            .systemBarsPadding()
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            color = AnodizedSlate,
            shape = RoundedCornerShape(24.dp),
            border = BorderStroke(1.dp, HairlineDivider),
            modifier = Modifier.widthIn(max = 420.dp),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Surface(
                    color = TungstenAmberSoft,
                    shape = CircleShape,
                    modifier = Modifier.size(72.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Filled.CameraAlt,
                            contentDescription = null,
                            tint = TungstenAmber,
                            modifier = Modifier.size(34.dp),
                        )
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))

                Text(
                    text = stringResource(R.string.camera_permission_title),
                    style = MaterialTheme.typography.headlineSmall,
                    color = OpticalWhite,
                    textAlign = TextAlign.Center,
                )

                Spacer(modifier = Modifier.height(10.dp))

                Text(
                    text = stringResource(R.string.camera_permission_rationale),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MutedSilver,
                    textAlign = TextAlign.Center,
                )

                Spacer(modifier = Modifier.height(24.dp))

                Button(
                    onClick = onRequestPermission,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = TungstenAmber,
                        contentColor = ObsidianBlack,
                    ),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp)
                        .testTag("grant_camera_permission_button"),
                ) {
                    Text(
                        text = stringResource(R.string.grant_camera_permission),
                        style = MaterialTheme.typography.titleMedium,
                    )
                }

                if (permanentlyDenied) {
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedButton(
                        onClick = {
                            val intent = Intent(
                                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                Uri.fromParts("package", context.packageName, null),
                            )
                            context.startActivity(intent)
                        },
                        border = BorderStroke(1.dp, HairlineDivider),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .testTag("open_settings_button"),
                    ) {
                        Text(
                            text = stringResource(R.string.open_app_settings),
                            color = OpticalWhite,
                        )
                    }
                }
            }
        }
    }
}
