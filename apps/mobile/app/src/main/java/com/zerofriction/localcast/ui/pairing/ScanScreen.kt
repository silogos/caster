package com.zerofriction.localcast.ui.pairing

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.zerofriction.localcast.BuildConfig
import com.zerofriction.localcast.R
import com.zerofriction.localcast.pairing.PairingClient
import com.zerofriction.localcast.pairing.PairingError
import com.zerofriction.localcast.ui.theme.LocalCastTheme

@Composable
fun ScanScreen(
    viewModel: ScanViewModel = viewModel(),
) {
    val pairingState by viewModel.pairingState.collectAsStateWithLifecycle()

    ScanContent(
        pairingState = pairingState,
        onQrScanned = viewModel::onQrScanned,
        onManualPayloadSubmit = viewModel::onManualPayloadSubmit,
        onDisconnect = viewModel::onDisconnect,
    )
}

@Composable
fun ScanContent(
    pairingState: PairingClient.State,
    onQrScanned: (String) -> Unit,
    onManualPayloadSubmit: (String) -> Unit,
    onDisconnect: () -> Unit,
) {
    val context = LocalContext.current
    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        hasCameraPermission = granted
    }

    Scaffold { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = stringResource(R.string.scan_title),
                fontSize = 20.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(16.dp))

            when (val state = pairingState) {
                PairingClient.State.Idle,
                is PairingClient.State.Failed,
                PairingClient.State.Ended -> {
                    when (state) {
                        is PairingClient.State.Failed ->
                            Text(
                                text = pairingErrorMessage(state.error),
                                fontSize = 15.sp,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.padding(bottom = 16.dp),
                            )
                        PairingClient.State.Ended ->
                            Text(
                                text = stringResource(R.string.session_ended),
                                fontSize = 15.sp,
                                modifier = Modifier.padding(bottom = 16.dp),
                            )
                        else -> {}
                    }

                    if (!hasCameraPermission) {
                        Text(
                            text = stringResource(R.string.camera_permission_needed),
                            fontSize = 15.sp,
                            modifier = Modifier.padding(bottom = 16.dp),
                        )
                        Button(onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) }) {
                            Text(stringResource(R.string.grant_camera))
                        }
                    } else {
                        Text(
                            text = stringResource(R.string.scan_hint),
                            fontSize = 14.sp,
                            modifier = Modifier.padding(bottom = 16.dp),
                        )
                        QrCamera(
                            onQrText = onQrScanned,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(320.dp),
                        )
                    }

                    if (BuildConfig.DEBUG) {
                        // Debug-only: lets the WebSocket+handshake path be tested on an
                        // emulator, which cannot scan a real desktop QR (Phase3 decision).
                        var manualPayload by rememberSaveable { mutableStateOf("") }
                        OutlinedTextField(
                            value = manualPayload,
                            onValueChange = { manualPayload = it },
                            label = { Text(stringResource(R.string.manual_payload_label)) },
                            visualTransformation = PasswordVisualTransformation(),
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 16.dp),
                        )
                        OutlinedButton(
                            onClick = {
                                if (manualPayload.isNotBlank()) onManualPayloadSubmit(manualPayload)
                            },
                            modifier = Modifier.padding(top = 8.dp),
                        ) {
                            Text(stringResource(R.string.manual_payload_connect))
                        }
                    }
                }

                PairingClient.State.Connecting, PairingClient.State.Authenticating -> {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(16.dp))
                    Text(
                        text = if (state == PairingClient.State.Connecting) {
                            stringResource(R.string.connecting)
                        } else {
                            stringResource(R.string.authenticating)
                        },
                        fontSize = 15.sp,
                    )
                }

                is PairingClient.State.Reconnecting -> {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(16.dp))
                    Text(
                        text = state.desktopName?.let { stringResource(R.string.reconnecting_to, it) }
                            ?: stringResource(R.string.reconnecting),
                        fontSize = 15.sp,
                    )
                }

                is PairingClient.State.Connected -> {
                    Text(
                        text = stringResource(R.string.connected_to, state.desktopName),
                        fontSize = 18.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(24.dp))
                    Button(onClick = onDisconnect) {
                        Text(stringResource(R.string.disconnect))
                    }
                }
            }
        }
    }
}

/** pairing.md failure-mode table — simple, non-technical; codes stay in logs. */
@Composable
private fun pairingErrorMessage(error: PairingError): String = when (error) {
    PairingError.NOT_ZFC_QR -> stringResource(R.string.pairing_error_not_zfc_qr)
    PairingError.UNSUPPORTED_VERSION -> stringResource(R.string.pairing_error_unsupported_version)
    PairingError.CONNECT_UNREACHABLE -> stringResource(R.string.pairing_error_connect_unreachable)
    PairingError.EXPIRED -> stringResource(R.string.pairing_error_expired)
    PairingError.BAD_AUTH -> stringResource(R.string.pairing_error_bad_auth)
    PairingError.BUSY -> stringResource(R.string.pairing_error_busy)
    PairingError.BAD_VERSION -> stringResource(R.string.pairing_error_bad_version)
}

@Preview(showBackground = true, widthDp = 360, heightDp = 640)
@Composable
private fun ScanContentPreview() {
    LocalCastTheme {
        ScanContent(
            pairingState = PairingClient.State.Idle,
            onQrScanned = {},
            onManualPayloadSubmit = {},
            onDisconnect = {},
        )
    }
}
