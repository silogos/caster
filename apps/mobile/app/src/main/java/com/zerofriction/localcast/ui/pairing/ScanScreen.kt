package com.zerofriction.localcast.ui.pairing

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
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
import androidx.compose.runtime.DisposableEffect
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
import com.zerofriction.localcast.audio.GameAudioState
import com.zerofriction.localcast.audio.MicState
import com.zerofriction.localcast.capture.DisplaySize
import com.zerofriction.localcast.config.CastSettingsStore
import com.zerofriction.localcast.pairing.PairingClient
import com.zerofriction.localcast.pairing.PairingError
import com.zerofriction.localcast.service.CastState
import com.zerofriction.localcast.service.CastService
import com.zerofriction.localcast.signaling.SignalingClient
import com.zerofriction.localcast.thermal.ThermalState
import com.zerofriction.localcast.ui.home.DebugToneButton
import com.zerofriction.localcast.ui.home.GameAudioControls
import com.zerofriction.localcast.ui.home.MicControls
import com.zerofriction.localcast.ui.home.ThermalStatusLine
import com.zerofriction.localcast.ui.theme.LocalCastTheme

@Composable
fun ScanScreen(
    viewModel: ScanViewModel = viewModel(),
    /**
     * The system back button leaves the scan screen. Without it, back
     * backgrounds the whole app and rememberSaveable reopens the scan screen
     * on return — a dead end (found live in Phase7). The cast is service-owned
     * and survives; an un-cast pairing is dropped by the ViewModel as usual.
     */
    onBackToHome: () -> Unit = {},
) {
    val context = LocalContext.current
    val backDispatcher = (context as? ComponentActivity)?.onBackPressedDispatcher
    DisposableEffect(backDispatcher, onBackToHome) {
        val callback = object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = onBackToHome()
        }
        backDispatcher?.addCallback(callback)
        onDispose { callback.remove() }
    }

    val pairingState by viewModel.pairingState.collectAsStateWithLifecycle()
    val castState by viewModel.castState.collectAsStateWithLifecycle()
    val gameAudioState by CastService.gameAudioState.collectAsStateWithLifecycle()
    val micState by CastService.micState.collectAsStateWithLifecycle()
    val thermalState by CastService.thermalState.collectAsStateWithLifecycle()

    ScanContent(
        pairingState = pairingState,
        castState = castState,
        gameAudioState = gameAudioState,
        micState = micState,
        thermalState = thermalState,
        releaseSignalingClient = viewModel::releaseSignalingClient,
        onQrScanned = viewModel::onQrScanned,
        onManualPayloadSubmit = viewModel::onManualPayloadSubmit,
        onDisconnect = viewModel::onDisconnect,
    )
}

/**
 * Phase6 render rules: the cast service owns the session, so a running cast
 * (Starting/Casting) takes precedence over the pairing machine — while a cast
 * is live there is nothing to scan and the pairing machine owns nothing. A
 * Failed cast falls through to the scan UI: ending the cast ends the pairing
 * session too (the desktop shows a fresh QR), so the user rescans.
 */
@Composable
fun ScanContent(
    pairingState: PairingClient.State,
    castState: CastState = CastState.Idle,
    gameAudioState: GameAudioState = GameAudioState.Off,
    micState: MicState = MicState.Off,
    thermalState: ThermalState = ThermalState(),
    releaseSignalingClient: () -> SignalingClient? = { null },
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

            when (castState) {
                is CastState.Starting -> {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(16.dp))
                    Text(
                        text = stringResource(R.string.starting_cast),
                        fontSize = 15.sp,
                    )
                }

                is CastState.Casting -> {
                    Text(
                        text = stringResource(R.string.casting_to, castState.desktopName),
                        fontSize = 18.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(16.dp))
                    Text(
                        text = stringResource(R.string.casting_background_hint),
                        fontSize = 14.sp,
                        modifier = Modifier.padding(bottom = 16.dp),
                    )
                    GameAudioControls(gameAudioState)
                    MicControls(micState = micState, gameAudioState = gameAudioState)
                    // Phase11: the thermal read-out rides wherever the cast's
                    // status is rendered — the scan screen is where the user
                    // actually watches a started cast (found live in review).
                    ThermalStatusLine(thermalState)
                    if (BuildConfig.DEBUG) {
                        DebugToneButton()
                    }
                    OutlinedButton(onClick = { CastService.requestStop(context) }) {
                        Text(stringResource(R.string.stop_casting))
                    }
                }

                is CastState.Failed -> {
                    Text(
                        text = castState.message,
                        fontSize = 15.sp,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(bottom = 16.dp),
                    )
                    ScanUi(
                        hasCameraPermission = hasCameraPermission,
                        onGrantCamera = { permissionLauncher.launch(Manifest.permission.CAMERA) },
                        onQrScanned = onQrScanned,
                        onManualPayloadSubmit = onManualPayloadSubmit,
                    )
                }

                CastState.Idle -> when (val state = pairingState) {
                    PairingClient.State.Idle,
                    is PairingClient.State.Failed,
                    PairingClient.State.Ended -> {
                        if (state is PairingClient.State.Failed) {
                            Text(
                                text = pairingErrorMessage(state.error),
                                fontSize = 15.sp,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.padding(bottom = 16.dp),
                            )
                        }
                        if (state == PairingClient.State.Ended) {
                            Text(
                                text = stringResource(R.string.session_ended),
                                fontSize = 15.sp,
                                modifier = Modifier.padding(bottom = 16.dp),
                            )
                        }
                        ScanUi(
                            hasCameraPermission = hasCameraPermission,
                            onGrantCamera = { permissionLauncher.launch(Manifest.permission.CAMERA) },
                            onQrScanned = onQrScanned,
                            onManualPayloadSubmit = onManualPayloadSubmit,
                        )
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
                        CastControls(castState = castState, desktopName = state.desktopName, releaseSignalingClient = releaseSignalingClient)
                        Spacer(Modifier.height(16.dp))
                        Button(onClick = onDisconnect) {
                            Text(stringResource(R.string.disconnect))
                        }
                    }
                }
            }
        }
    }
}

/** The scanner (camera / debug manual input) for every state that needs a QR. */
@Composable
private fun ScanUi(
    hasCameraPermission: Boolean,
    onGrantCamera: () -> Unit,
    onQrScanned: (String) -> Unit,
    onManualPayloadSubmit: (String) -> Unit,
) {
    if (!hasCameraPermission) {
        Text(
            text = stringResource(R.string.camera_permission_needed),
            fontSize = 15.sp,
            modifier = Modifier.padding(bottom = 16.dp),
        )
        Button(onClick = onGrantCamera) {
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

/**
 * The cast trigger, shown while paired. `Start casting` runs the
 * MediaProjection consent (per session, never pre-granted — mobile.md) and
 * then hands the pairing's signaling connection to the foreground service —
 * Phase6 ownership: the service owns it from here on, so this screen can be
 * left freely while the cast runs. The notification permission is requested
 * first on 13+ but is not fatal — the cast runs either way, only the FGS
 * notification's visibility depends on it.
 */
@Composable
private fun CastControls(
    castState: CastState,
    desktopName: String,
    releaseSignalingClient: () -> SignalingClient?,
) {
    val context = LocalContext.current
    val mediaProjectionManager = remember { context.getSystemService(MediaProjectionManager::class.java) }

    val projectionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val consentData = result.data
        if (result.resultCode == Activity.RESULT_OK && consentData != null) {
            val signaling = releaseSignalingClient()
            if (signaling != null) {
                val (width, height) = DisplaySize.physicalPx(context)
                // Phase10: the cast uses the persisted settings, read fresh at
                // start — settings changes take effect on the next cast.
                val settings = CastSettingsStore(context).load()
                CastService.start(
                    context,
                    CastService.StartArgs(
                        signaling = signaling,
                        config = settings.toConfig(),
                        projectionIntent = consentData,
                        physicalWidth = width,
                        physicalHeight = height,
                        desktopName = desktopName,
                    ),
                )
            }
        }
    }
    val notificationPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        projectionLauncher.launch(mediaProjectionManager.createScreenCaptureIntent())
    }

    fun requestNotificationsOrConsent() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            projectionLauncher.launch(mediaProjectionManager.createScreenCaptureIntent())
        }
    }

    // Phase7: the platform requires RECORD_AUDIO for playback capture (and
    // to start the WebRTC ADM). Non-fatal on denial — the cast then runs
    // video-only and the home screen says game audio is off.
    val recordAudioLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        requestNotificationsOrConsent()
    }

    fun requestConsentAndStart() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            recordAudioLauncher.launch(Manifest.permission.RECORD_AUDIO)
        } else {
            requestNotificationsOrConsent()
        }
    }

    // Starting/Casting is rendered by the outer card; here only the trigger.
    when (castState) {
        CastState.Idle, is CastState.Failed -> Button(onClick = ::requestConsentAndStart) {
            Text(stringResource(R.string.start_casting))
        }

        is CastState.Starting, is CastState.Casting -> Unit
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
            castState = CastState.Casting("MacBook Pro"),
            onQrScanned = {},
            onManualPayloadSubmit = {},
            onDisconnect = {},
        )
    }
}
