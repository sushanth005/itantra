package com.itantra

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.rememberMultiplePermissionsState
import com.itantra.ui.MainViewModel
import com.itantra.ui.screens.HomeScreen
import com.itantra.ui.screens.PermissionsScreen
import com.itantra.ui.theme.IsroAmber
import com.itantra.ui.theme.SpaceNavy
import com.itantra.ui.theme.SurfaceDark
import com.itantra.ui.theme.OnSurface
import com.itantra.ui.theme.iTantraTheme

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    // Launcher for the system Bluetooth enable dialog
    private val enableBtLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val btManager = getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
        viewModel.onBluetoothStateChanged(btManager.adapter?.isEnabled == true)
    }

    @OptIn(ExperimentalPermissionsApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            iTantraTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = SpaceNavy) {

                    val requiredPermissions = buildList {
                        add(android.Manifest.permission.RECORD_AUDIO)
                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                            add(android.Manifest.permission.BLUETOOTH_CONNECT)
                            add(android.Manifest.permission.BLUETOOTH_SCAN)
                        }
                        add(android.Manifest.permission.ACCESS_FINE_LOCATION)
                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                            add(android.Manifest.permission.POST_NOTIFICATIONS)
                        }
                    }

                    val permissionsState = rememberMultiplePermissionsState(requiredPermissions)

                    if (permissionsState.allPermissionsGranted) {
                        val state by viewModel.state.collectAsState()

                        // Bluetooth service-check dialog — shown immediately if BT is off
                        if (!state.isBluetoothEnabled) {
                            AlertDialog(
                                onDismissRequest = {},
                                containerColor = SurfaceDark,
                                title = {
                                    Text("Bluetooth Required", color = IsroAmber)
                                },
                                text = {
                                    Text(
                                        "iTantra needs Bluetooth to connect to other devices for voice transmission. Please enable Bluetooth to continue.",
                                        color = OnSurface
                                    )
                                },
                                confirmButton = {
                                    Button(
                                        onClick = {
                                            @Suppress("DEPRECATION")
                                            enableBtLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
                                        },
                                        colors = ButtonDefaults.buttonColors(containerColor = IsroAmber, contentColor = SpaceNavy)
                                    ) { Text("Turn On") }
                                },
                                dismissButton = {
                                    TextButton(onClick = { /* user may proceed without BT, session won't start */ }) {
                                        Text("Dismiss", color = OnSurface)
                                    }
                                }
                            )
                        }

                        HomeScreen(viewModel = viewModel)
                    } else {
                        PermissionsScreen(
                            permissions = permissionsState,
                            onGranted = { permissionsState.launchMultiplePermissionRequest() },
                        )
                    }
                }
            }
        }
    }
}
