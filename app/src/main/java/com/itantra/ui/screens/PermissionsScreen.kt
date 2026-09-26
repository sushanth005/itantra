package com.itantra.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.MultiplePermissionsState
import com.itantra.ui.theme.*

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun PermissionsScreen(
    permissions: MultiplePermissionsState,
    onGranted: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(SpaceNavy, CosmoBlue)))
            .statusBarsPadding()
            .navigationBarsPadding(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                "iTantra",
                style = MaterialTheme.typography.displayMedium.copy(
                    color = IsroAmber,
                    fontWeight = FontWeight.Bold,
                ),
            )
            Text(
                "Multilingual Voice Transceiver",
                style = MaterialTheme.typography.titleMedium.copy(color = OnSurfaceDim),
                textAlign = TextAlign.Center,
            )

            Spacer(Modifier.height(16.dp))

            // Permission items
            PermissionItem(Icons.Default.Mic, "Microphone", "Required for speech-to-text capture")
            PermissionItem(Icons.Default.Bluetooth, "Bluetooth", "Required for device-to-device communication")
            PermissionItem(Icons.Default.LocationOn, "Location", "Required by Android for Wi-Fi Direct & BT scanning")
            PermissionItem(Icons.Default.Notifications, "Notifications", "Required for the active session notification")

            Spacer(Modifier.height(16.dp))

            Button(
                onClick = onGranted,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                colors = ButtonDefaults.buttonColors(containerColor = IsroAmber, contentColor = SpaceNavy),
                shape = RoundedCornerShape(14.dp),
            ) {
                Text("Grant Permissions", style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold))
            }

            Text(
                "All processing is fully offline. No data is sent to any server.",
                style = MaterialTheme.typography.bodySmall.copy(color = OnSurfaceDim),
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun PermissionItem(icon: ImageVector, title: String, description: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = SurfaceDark,
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(shape = RoundedCornerShape(8.dp), color = StellarBlue) {
                Icon(
                    icon, contentDescription = null,
                    tint = IsroAmber,
                    modifier = Modifier.padding(8.dp).size(20.dp),
                )
            }
            Spacer(Modifier.width(12.dp))
            Column {
                Text(title, style = MaterialTheme.typography.titleSmall, color = OnSurface)
                Text(description, style = MaterialTheme.typography.bodySmall, color = OnSurfaceDim)
            }
        }
    }
}
