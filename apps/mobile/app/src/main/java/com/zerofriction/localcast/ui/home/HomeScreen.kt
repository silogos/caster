package com.zerofriction.localcast.ui.home

import android.Manifest
import android.app.Activity
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.content.Context
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.zerofriction.localcast.BuildConfig
import com.zerofriction.localcast.R
import com.zerofriction.localcast.adaptive.AdaptiveQualityController
import com.zerofriction.localcast.adaptive.QualityLevel
import com.zerofriction.localcast.audio.GameAudioState
import com.zerofriction.localcast.audio.MicDeviceResolver
import com.zerofriction.localcast.audio.MicState
import com.zerofriction.localcast.capture.DisplaySize
import com.zerofriction.localcast.config.CastSettings
import com.zerofriction.localcast.config.CastSettingsStore
import com.zerofriction.localcast.config.MicDeviceSource
import com.zerofriction.localcast.config.QualityProfile
import com.zerofriction.localcast.config.matchingProfile
import com.zerofriction.localcast.debug.DebugTestTone
import com.zerofriction.localcast.pairing.DeviceIdentity
import com.zerofriction.localcast.pairing.PairingClient
import com.zerofriction.localcast.pairing.PairingError
import com.zerofriction.localcast.pairing.SavedDesktops
import com.zerofriction.localcast.pairing.KeystoreTrustVault
import com.zerofriction.localcast.pairing.defaultSignalingScheduler
import com.zerofriction.localcast.pairing.defaultTransportFactory
import com.zerofriction.localcast.ui.pairing.defaultUserAgent
import com.zerofriction.localcast.pairing.applyGrant
import com.zerofriction.localcast.service.AdaptiveUiState
import com.zerofriction.localcast.service.CastState
import com.zerofriction.localcast.service.CastService
import com.zerofriction.localcast.service.ConnectedDesktop
import com.zerofriction.localcast.service.MicRoutingUiState
import com.zerofriction.localcast.service.AudioRoutingUiState
import com.zerofriction.localcast.service.VcCapability
import com.zerofriction.localcast.service.VirtualCameraUiState
import com.zerofriction.localcast.signaling.Envelope
import com.zerofriction.localcast.thermal.ThermalState
import com.zerofriction.localcast.thermal.ThermalStatus
import com.zerofriction.localcast.ui.components.AnnuncTone
import com.zerofriction.localcast.ui.components.Annunciator
import com.zerofriction.localcast.ui.components.BrandMark
import com.zerofriction.localcast.ui.components.CardFoot
import com.zerofriction.localcast.ui.components.CastSegRow
import com.zerofriction.localcast.ui.components.CastCard
import com.zerofriction.localcast.ui.components.CastChipRow
import com.zerofriction.localcast.ui.components.CastSwitch
import com.zerofriction.localcast.ui.components.CastTextButton
import com.zerofriction.localcast.ui.components.IconListRow
import com.zerofriction.localcast.ui.components.LedDot
import com.zerofriction.localcast.ui.components.LedListRow
import com.zerofriction.localcast.ui.components.LedTone
import com.zerofriction.localcast.ui.components.SilkscreenLabel
import com.zerofriction.localcast.ui.settings.SettingsViewModel
import com.zerofriction.localcast.ui.theme.LocalCastTheme
import kotlinx.coroutines.delay

/**
 * The Home hub (designs/mobile-app.html — the broadcast-console redesign):
 * reached only through a successful pairing on the scan screen. The on-air
 * hero (tally lamp + desktop name, elapsed timer while casting) states the
 * connection; the transport control below it starts and stops the **video**
 * — "Stop share screen" leaves the pairing connected — and Disconnect ends
 * everything. The audio rows are the live toggles *and* the next-cast
 * defaults: only the microphone is ever fully removed from a cast;
 * game-audio "off" mutes the track, which stays part of the cast.
 */
@Composable
fun HomeScreen(
    onOpenSettings: () -> Unit,
    onDisconnected: () -> Unit,
    viewModel: HomeViewModel = viewModel(factory = homeFactory()),
    settingsViewModel: SettingsViewModel = viewModel(factory = settingsFactory()),
) {
    val context = LocalContext.current
    val castState by viewModel.castState.collectAsStateWithLifecycle()
    val pairingState by viewModel.pairingState.collectAsStateWithLifecycle()
    val connection by ConnectedDesktop.state.collectAsStateWithLifecycle()
    val gameAudioState by CastService.gameAudioState.collectAsStateWithLifecycle()
    val micState by CastService.micState.collectAsStateWithLifecycle()
    val micRouting by CastService.micRouting.collectAsStateWithLifecycle()
    val thermalState by CastService.thermalState.collectAsStateWithLifecycle()
    val adaptiveState by CastService.adaptiveState.collectAsStateWithLifecycle()
    val liveQuality by CastService.liveQuality.collectAsStateWithLifecycle()
    val vcState by CastService.vcState.collectAsStateWithLifecycle()
    val audioRouting by CastService.audioRouting.collectAsStateWithLifecycle()
    val settings by settingsViewModel.settings.collectAsStateWithLifecycle()

    // No pairing session → nothing to show here: back to the scan screen.
    LaunchedEffect(connection) {
        if (connection is ConnectedDesktop.State.None) onDisconnected()
    }

    // The resume machine's outcomes (ADR-006): a reconnected session is
    // adopted into the hub exactly like a scan pairing. A failure is NOT
    // auto-cleared — the failure card below owns the moment: the message plus
    // the two escapes (Scan QR code / Terminate), the user decides (found in
    // live review: auto-terminating flashed the card away and left a dead
    // Connect hero). Only a mid-connect `bye` (Ended) is terminal by itself.
    LaunchedEffect(pairingState) {
        when (val ps = pairingState) {
            is PairingClient.State.Connected ->
                viewModel.releaseSignalingClient()?.let { ConnectedDesktop.adopt(it, ps.desktopName) }

            PairingClient.State.Ended -> {
                viewModel.terminateSaved()
                ConnectedDesktop.clear()
                onDisconnected()
            }
            else -> {}
        }
    }

    // ---- The share consent ladder (mobile.md): every grant is non-fatal —
    // RECORD_AUDIO denial means a video-only cast, the notification prompt is
    // visibility-only, and the projection consent is per cast, never stored.
    val projectionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val consentData = result.data
        if (result.resultCode == Activity.RESULT_OK && consentData != null) {
            val signaling = ConnectedDesktop.releaseForCast()
            if (signaling !== null) {
                val (width, height) = DisplaySize.physicalPx(context)
                // "Changes take effect on the next cast": read the store fresh.
                val castSettings = CastSettingsStore(context).load()
                CastService.start(
                    context,
                    CastService.StartArgs(
                        signaling = signaling,
                        config = castSettings.toConfig(),
                        projectionIntent = consentData,
                        physicalWidth = width,
                        physicalHeight = height,
                        desktopName = (ConnectedDesktop.state.value as? ConnectedDesktop.State.Connected)?.desktopName
                            ?: "",
                    ),
                )
            }
        }
    }
    val notificationPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        val manager = context.getSystemService(MediaProjectionManager::class.java)
        projectionLauncher.launch(manager.createScreenCaptureIntent())
    }
    // Non-fatal on denial (mobile.md): a video-only cast beats no cast.
    val recordAudioLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            val manager = context.getSystemService(MediaProjectionManager::class.java)
            projectionLauncher.launch(manager.createScreenCaptureIntent())
        }
    }
    val micPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) CastService.requestToggleMic(context)
    }

    HomeContent(
        castState = castState,
        connection = connection,
        pairingState = pairingState,
        gameAudioState = gameAudioState,
        micState = micState,
        thermalState = thermalState,
        adaptiveState = adaptiveState,
        micRouting = micRouting,
        settings = settings,
        liveQuality = liveQuality,
        onSetGameAudio = { on ->
            settingsViewModel.setGameAudio(on)
            if (castState is CastState.Casting && (gameAudioState == GameAudioState.Muted) != !on) {
                CastService.requestToggleGameAudio(context)
            }
        },
        onSetMic = { on ->
            settingsViewModel.setMic(on)
            if (castState is CastState.Casting) {
                when {
                    on && micState == MicState.NeedsPermission ->
                        micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)

                    on != (micState != MicState.Off) -> CastService.requestToggleMic(context)
                }
            }
        },
        onSetMicDevice = { source ->
            settingsViewModel.setMicDevice(source)
            // Realtime while the mic records: reroute the live record. Without
            // a live mic the pick is just the stored default (next start).
            if (castState is CastState.Casting && micState != MicState.Off) {
                CastService.requestSetMicDevice(context, source)
            }
        },
        onSelectProfile = settingsViewModel::selectProfile,
        onSelectLongEdge = settingsViewModel::selectLongEdge,
        onSelectFps = settingsViewModel::selectFps,
        onSetManualBitrateMax = settingsViewModel::setManualBitrateMax,
        onApplyLiveQuality = { level -> CastService.requestSetLiveQuality(context, level) },
        vcState = vcState,
        onToggleVirtualCamera = { CastService.requestToggleVirtualCamera(context) },
        audioRouting = audioRouting,
        onSetAudioRouting = { mode -> CastService.requestSetAudioRouting(context, mode) },
        onShareScreen = {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                recordAudioLauncher.launch(Manifest.permission.RECORD_AUDIO)
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                val manager = context.getSystemService(MediaProjectionManager::class.java)
                projectionLauncher.launch(manager.createScreenCaptureIntent())
            }
        },
        onStopShareScreen = { CastService.requestStop(context) },
        onDisconnect = { CastService.requestDisconnect(context) },
        onTerminate = { CastService.requestTerminate(context) },
        onConnectSaved = { viewModel.connectSaved() },
        onScanQr = {
            viewModel.prepareForScan()
            onDisconnected()
        },
        onTerminateSaved = {
            viewModel.terminateSaved()
            ConnectedDesktop.clear()
            onDisconnected()
        },
        onOpenSettings = onOpenSettings,
    )
}

@Composable
fun HomeContent(
    castState: CastState,
    connection: ConnectedDesktop.State = ConnectedDesktop.State.Connected("Gaming PC"),
    pairingState: PairingClient.State = PairingClient.State.Idle,
    gameAudioState: GameAudioState = GameAudioState.Off,
    micState: MicState = MicState.Off,
    thermalState: ThermalState = ThermalState(),
    adaptiveState: AdaptiveUiState = AdaptiveUiState(),
    micRouting: MicRoutingUiState? = null,
    settings: CastSettings = CastSettings.default(),
    liveQuality: QualityLevel? = null,
    onSetGameAudio: (Boolean) -> Unit = {},
    onSetMic: (Boolean) -> Unit = {},
    onSetMicDevice: (MicDeviceSource) -> Unit = {},
    onSelectProfile: (QualityProfile) -> Unit = {},
    onSelectLongEdge: (Int) -> Unit = {},
    onSelectFps: (Int) -> Unit = {},
    onSetManualBitrateMax: (Int) -> Unit = {},
    onApplyLiveQuality: (QualityLevel) -> Unit = {},
    vcState: VirtualCameraUiState = VirtualCameraUiState(),
    onToggleVirtualCamera: () -> Unit = {},
    audioRouting: AudioRoutingUiState = AudioRoutingUiState(),
    onSetAudioRouting: (String) -> Unit = {},
    onShareScreen: () -> Unit = {},
    onStopShareScreen: () -> Unit = {},
    onDisconnect: () -> Unit = {},
    onTerminate: () -> Unit = {},
    onConnectSaved: () -> Unit = {},
    onScanQr: () -> Unit = {},
    onTerminateSaved: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
) {
    val casting = castState is CastState.Casting
    val annunc = annunciatorFor(castState)
    val connected = connection is ConnectedDesktop.State.Connected
    val saved = connection as? ConnectedDesktop.State.Saved
    val reconnecting = pairingState is PairingClient.State.Connecting ||
        pairingState is PairingClient.State.Authenticating ||
        pairingState is PairingClient.State.Reconnecting
    val resumeFailure = pairingState as? PairingClient.State.Failed

    // Surface (like the scan screen's Scaffold): paints the theme
    // background and provides contentColor — plain Columns default to
    // black text, not the theme's.
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
    Column(
        // Edge-to-edge: the hub scrolls below the system bars, but its
        // resting content starts below the status bar and ends above the
        // gesture bar (the scan screen gets this from its Scaffold).
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
    ) {
        // ---- App bar: the console's nameplate + annunciator strip ----
        // The bar owns ALL the state words (machine register) and the ONE
        // blinking tally (ON AIR); the hero below stays wordless.
        val barInk = lerp(MaterialTheme.colorScheme.onSurfaceVariant, MaterialTheme.colorScheme.onSurface, 0.2f)
        val barOutline = MaterialTheme.colorScheme.outline
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 2.dp)
                .heightIn(min = 48.dp)
                .drawBehind {
                    val y = size.height - 0.5.dp.toPx()
                    drawLine(
                        color = barOutline,
                        start = Offset(0f, y),
                        end = Offset(size.width, y),
                        strokeWidth = 1.dp.toPx(),
                    )
                }
                .padding(bottom = 14.dp),
        ) {
            BrandMark(onAir = casting)
            Text(
                text = stringResource(R.string.app_name),
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                fontFamily = FontFamily.Monospace,
                letterSpacing = 1.7.sp,
                color = barInk,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Annunciator(
                word = stringResource(annunc.labelRes),
                tone = annunc.tone,
                pulse = annunc == ConsoleAnnunciator.ON_AIR,
            )
            OutlinedButton(
                onClick = onOpenSettings,
                modifier = Modifier.size(40.dp),
                shape = RoundedCornerShape(12.dp),
                contentPadding = PaddingValues(0.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                colors = ButtonDefaults.outlinedButtonColors(
                    containerColor = Color.Transparent,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                ),
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_gear),
                    contentDescription = stringResource(R.string.advanced_settings_title),
                    modifier = Modifier.size(19.dp),
                )
            }
        }

        // ---- Hero: the destination plate — name only, both states; the
        // live timecode and quality readout live in the STATUS header. Saved
        // but offline (ADR-006): the Disconnect slot becomes Connect — the
        // one-tap QR-less reconnect. The hero STAYS when a cast failed (found
        // live 2026-10-31: "The connection to the desktop…" used to replace
        // it, leaving the failure without its destination plate — Connect and
        // Terminate must stay reachable in that exact moment). ----
        if (castState is CastState.Failed) {
            Spacer(Modifier.height(12.dp))
            CastCard {
                IconListRow(iconRes = R.drawable.ic_warning, title = castState.message, err = true)
            }
        }
        OnAirHero(
            desktopName = when {
                casting -> (castState as CastState.Casting).desktopName
                connected -> (connection as ConnectedDesktop.State.Connected).desktopName
                saved != null -> saved.desktopName
                else -> stringResource(R.string.home_not_connected)
            },
            saved = saved,
            connecting = reconnecting,
            onConnect = onConnectSaved,
            onDisconnect = onDisconnect,
            onTerminate = if (connected) onTerminate else onTerminateSaved,
        )

        // ---- A failed reconnect (ADR-06): why it failed, plus the two
        // escapes — scan a QR instead, or forget this desktop entirely (the
        // saved entry is kept on plain failures: the desktop may just be off).
        if (resumeFailure != null && !connected) {
            Spacer(Modifier.height(8.dp))
            CastCard {
                IconListRow(iconRes = R.drawable.ic_warning, title = resumeFailureMessage(resumeFailure.error), err = true)
                Row(
                    horizontalArrangement = Arrangement.spacedBy(18.dp),
                    modifier = Modifier.padding(start = 16.dp, top = 2.dp, bottom = 12.dp),
                ) {
                    CastTextButton(text = stringResource(R.string.home_scan_qr), onClick = onScanQr)
                    CastTextButton(text = stringResource(R.string.home_terminate), onClick = onTerminateSaved)
                }
            }
        }

        // ---- The transport control: amber to go, outlined to stop ----
        // (stopping leaves the pairing connected — only the video stops)
        if (castState is CastState.Starting) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.padding(top = 18.dp),
            ) {
                CircularProgressIndicator(Modifier.size(16.dp))
                Text(text = stringResource(R.string.starting_cast), fontSize = 14.sp)
            }
            // mobile.md consent affordance (Android 14+): the dialog shown
            // during this window defaults to the cast-killing choice.
            HintText(stringResource(R.string.share_full_screen_hint))
        } else if (!connected && !casting) {
            // Saved/offline (ADR-006): no Share button — the hero's Connect
            // leads; casting needs a live pairing first.
        } else {
            val goShape = RoundedCornerShape(16.dp)
            if (casting) {
                OutlinedButton(
                    onClick = onStopShareScreen,
                    shape = goShape,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 16.dp)
                        .height(54.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.30f)),
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = Color.Transparent,
                        contentColor = MaterialTheme.colorScheme.onSurface,
                    ),
                ) {
                    Text(text = stringResource(R.string.stop_share_screen), fontSize = 15.sp, fontWeight = FontWeight.Medium)
                }
            } else {
                Button(
                    onClick = onShareScreen,
                    shape = goShape,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 16.dp)
                        .height(54.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                    ),
                ) {
                    Text(text = stringResource(R.string.share_screen_action), fontSize = 15.sp, fontWeight = FontWeight.Bold)
                }
            }
        }

        // ---- The quality console (Preset | Custom) — same card in both
        // states, 16dp off the transport (the prototype's 16/16 rhythm):
        // idle = next-cast defaults, casting = the running cast. STATUS
        // rides with it, casting only. ----
        QualityConsoleCard(
            casting = casting,
            settings = settings,
            liveQuality = liveQuality,
            startedAtMillis = (castState as? CastState.Casting)?.startedAtMillis ?: 0L,
            onSelectProfile = onSelectProfile,
            onSelectLongEdge = onSelectLongEdge,
            onSelectFps = onSelectFps,
            onSetManualBitrateMax = onSetManualBitrateMax,
            onApplyLive = onApplyLiveQuality,
            modifier = Modifier.padding(top = 16.dp),
            statusRows = {
                ThermalLedRow(thermalState, topDivider = false)
                AdaptiveLedRow(adaptiveState, topDivider = true)
                if (BuildConfig.DEBUG) {
                    DebugToneButton(topDivider = true)
                }
            },
        )

        // ---- Audio card — one switch per track ----
        SilkscreenLabel(stringResource(R.string.settings_audio))
        CastCard {
            // Game audio: the track always rides the cast (when permission
            // allows) — the switch is audibility. Only the microphone is ever
            // fully removed (designs/mobile-app.html, ADR-004 semantics).
            IconListRow(
                iconRes = R.drawable.ic_gamepad,
                title = stringResource(R.string.home_game_audio),
                sub = stringResource(R.string.home_game_audio_hint),
                fact = when (gameAudioState) {
                    GameAudioState.Silent -> stringResource(R.string.game_audio_cannot_capture)
                    GameAudioState.Failed -> stringResource(R.string.game_audio_unavailable)
                    GameAudioState.Off -> if (casting) stringResource(R.string.game_audio_off) else null
                    else -> null
                },
                topDivider = true,
                right = {
                    CastSwitch(
                        checked = when {
                            casting && gameAudioState != GameAudioState.Off ->
                                gameAudioState != GameAudioState.Muted

                            else -> settings.gameAudio
                        },
                        onCheckedChange = onSetGameAudio,
                    )
                },
            )
            // The routing fact line: off → the freeing hint; on → which mic
            // records (live while casting — the reroute updates it instantly).
            val micOn = if (casting) micState != MicState.Off else settings.mic
            val effectiveDevice = if (casting) (micRouting?.effective ?: settings.micDevice) else settings.micDevice
            IconListRow(
                iconRes = R.drawable.ic_mic,
                title = stringResource(R.string.home_microphone),
                sub = when {
                    !micOn -> stringResource(R.string.home_mic_hint)
                    casting -> stringResource(R.string.mic_recording_from, micSourceName(effectiveDevice))
                    else -> stringResource(R.string.mic_records_from, micSourceName(effectiveDevice))
                },
                fact = when (micState) {
                    MicState.Silenced -> stringResource(R.string.mic_silenced)
                    MicState.Failed -> stringResource(R.string.mic_unavailable)
                    else -> null
                },
                topDivider = true,
                right = {
                    CastSwitch(
                        checked = micOn,
                        onCheckedChange = onSetMic,
                    )
                },
            )
            if (micOn) {
                MicDevicePicker(
                    effective = effectiveDevice,
                    fallbackFrom = if (casting) micRouting?.fallbackFrom else null,
                    onSelect = onSetMicDevice,
                )
            }
            // ---- Audio output routing (proto 2): the phone's mirror of the
            // desktop's receiver setting — speakers / virtual cable / both.
            // Shown while casting on desktops that answer the query; without
            // a cable device only Speakers stays pickable (honest hint). ----
            if (casting && audioRouting.known) {
                IconListRow(
                    iconRes = R.drawable.ic_headphones,
                    title = stringResource(R.string.audio_routing_title),
                    sub = if (audioRouting.cableAvailable) {
                        stringResource(R.string.audio_routing_hint)
                    } else {
                        stringResource(R.string.audio_routing_no_cable)
                    },
                    topDivider = true,
                )
                CastSegRow(
                    options = buildList {
                        add(stringResource(R.string.audio_routing_speakers) to (audioRouting.mode == Envelope.AUDIO_ROUTING_SPEAKERS))
                        if (audioRouting.cableAvailable) {
                            add(stringResource(R.string.audio_routing_cable) to (audioRouting.mode == Envelope.AUDIO_ROUTING_CABLE))
                            add(stringResource(R.string.audio_routing_both) to (audioRouting.mode == Envelope.AUDIO_ROUTING_BOTH))
                        }
                    },
                    onSelect = { index ->
                        val modes = if (audioRouting.cableAvailable) {
                            listOf(Envelope.AUDIO_ROUTING_SPEAKERS, Envelope.AUDIO_ROUTING_CABLE, Envelope.AUDIO_ROUTING_BOTH)
                        } else {
                            listOf(Envelope.AUDIO_ROUTING_SPEAKERS)
                        }
                        modes.getOrNull(index)?.let(onSetAudioRouting)
                    },
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
                )
            }
            // The headphones tip is audio advice, not cast status — it lives
            // with the audio sources (card foot), only while both tracks run.
            if (casting && micState == MicState.Active &&
                (gameAudioState == GameAudioState.Active || gameAudioState == GameAudioState.Silent)
            ) {
                CardFoot(text = stringResource(R.string.mic_headphones_hint))
            }
        }

        // ---- Virtual camera (ADR-007): visible only while casting AND the
        // desktop announced the capability (proto 2 + vc-status available) —
        // the switch is the phone's control; the desktop shows state only. ----
        if (casting && vcState.capability != VcCapability.Hidden) {
            SilkscreenLabel(stringResource(R.string.home_virtual_camera))
            CastCard {
                IconListRow(
                    iconRes = R.drawable.ic_cast,
                    title = stringResource(R.string.home_virtual_camera),
                    sub = stringResource(vcSublineRes(vcState)),
                    right = {
                        CastSwitch(
                            checked = vcState.active,
                            onCheckedChange = { onToggleVirtualCamera() },
                        )
                    },
                )
            }
        }
        Spacer(Modifier.height(28.dp))
        }
    }
}

/**
 * The hero (the prototype's .hero) — the destination plate: desktop name +
 * its one action. Connected: Disconnect (as before). Saved/offline (ADR-006):
 * the same slot becomes Connect — one tap resumes without a QR; while a
 * reconnect is in flight it reads Connecting… and is disabled.
 */
@Composable
private fun OnAirHero(
    desktopName: String,
    saved: ConnectedDesktop.State.Saved?,
    connecting: Boolean,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onTerminate: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 18.dp),
    ) {
        Text(
            text = desktopName,
            fontSize = 22.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
            letterSpacing = (-0.25).sp,
            lineHeight = 27.sp,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(12.dp))
        if (saved != null) {
            CastTextButton(
                text = stringResource(if (connecting) R.string.home_connecting else R.string.home_connect),
                onClick = onConnect,
                quiet = true,
            )
        } else {
            CastTextButton(text = stringResource(R.string.disconnect), onClick = onDisconnect, quiet = true)
        }
        // Terminate is the session-level exit (ADR-006): deleting the saved
        // session is deliberate, so it sits next to — quieter than — the
        // connection-level action, never replacing it.
        CastTextButton(text = stringResource(R.string.home_terminate), onClick = onTerminate, quiet = true)
    }
}

/** A failed reconnect's plain-words reason (pairing.md failure table). */
@Composable
private fun resumeFailureMessage(error: PairingError): String = stringResource(
    when (error) {
        PairingError.CONNECT_UNREACHABLE -> R.string.pairing_error_connect_unreachable
        PairingError.BUSY -> R.string.pairing_error_busy
        PairingError.DESKTOP_FORGOT -> R.string.pairing_error_desktop_forgotten
        else -> R.string.pairing_error_bad_auth
    }
)

/** The bar's annunciator states — machine words (designs/mobile-app.html). */
internal enum class ConsoleAnnunciator(val labelRes: Int, val tone: AnnuncTone) {
    STANDBY(R.string.app_bar_state_standby, AnnuncTone.QUIET),
    STARTING(R.string.app_bar_state_starting, AnnuncTone.LIVE),
    ON_AIR(R.string.app_bar_state_on_air, AnnuncTone.LIVE),
    FAULT(R.string.app_bar_state_fault, AnnuncTone.FAULT),
}

/** Idle-with-a-pairing reads STANDBY — Home is only shown while connected. */
/**
 * The virtual camera row's honest subline (ADR-007): what turning it on does
 * right now — setup pending, publishing, or plain ready. Pure — JVM-tested.
 */
internal fun vcSublineRes(state: VirtualCameraUiState): Int = when {
    state.active -> R.string.vc_hint_active
    state.capability == VcCapability.NeedsSetup -> R.string.vc_hint_needs_setup
    else -> R.string.vc_hint_ready
}

internal fun annunciatorFor(castState: CastState): ConsoleAnnunciator = when (castState) {
    is CastState.Starting -> ConsoleAnnunciator.STARTING
    is CastState.Casting -> ConsoleAnnunciator.ON_AIR
    is CastState.Failed -> ConsoleAnnunciator.FAULT
    CastState.Idle -> ConsoleAnnunciator.STANDBY
}

/** One offered mic kind: the config value plus its localized label. */
private data class MicDeviceOption(val source: MicDeviceSource, val labelRes: Int)

/** Connected input device types as AudioManager reports them right now. */
private fun connectedInputTypes(context: Context): Set<Int> =
    (context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager)
        ?.getDevices(AudioManager.GET_DEVICES_INPUTS)
        ?.map { it.type }
        ?.toSet()
        ?: emptySet()

/** Localized kind name ("Phone mic", …). */
@Composable
private fun micSourceName(source: MicDeviceSource): String = stringResource(
    when (source) {
        MicDeviceSource.BUILTIN -> R.string.mic_device_builtin
        MicDeviceSource.WIRED_HEADSET -> R.string.mic_device_wired
        MicDeviceSource.USB -> R.string.mic_device_usb
        MicDeviceSource.BLUETOOTH -> R.string.mic_device_bluetooth
    },
)

/**
 * The connected kinds from the inputs plugged in right now — the phone mic
 * always, a kind only while one of its inputs is connected (MicDeviceResolver
 * resolves the same way at capture time, so the offered chips and the actual
 * fallback can't drift apart). Follows plug/unplug **live**.
 */
@Composable
private fun rememberMicOptions(): List<MicDeviceOption> {
    val context = LocalContext.current
    var connectedTypes by remember { mutableStateOf(connectedInputTypes(context)) }
    DisposableEffect(context) {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        val callback = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
                connectedTypes = connectedInputTypes(context)
            }

            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
                connectedTypes = connectedInputTypes(context)
            }
        }
        audioManager?.registerAudioDeviceCallback(callback, Handler(Looper.getMainLooper()))
        onDispose { audioManager?.unregisterAudioDeviceCallback(callback) }
    }
    return remember(connectedTypes) {
        Log.d(TAG, "mic options from connected input types: \$connectedTypes")
        listOf(
            MicDeviceOption(MicDeviceSource.BUILTIN, R.string.mic_device_builtin),
            MicDeviceOption(MicDeviceSource.WIRED_HEADSET, R.string.mic_device_wired),
            MicDeviceOption(MicDeviceSource.USB, R.string.mic_device_usb),
            MicDeviceOption(MicDeviceSource.BLUETOOTH, R.string.mic_device_bluetooth),
        ).filter {
            it.source == MicDeviceSource.BUILTIN ||
                MicDeviceResolver.resolveType(it.source, connectedTypes) != MicDeviceResolver.FALLBACK_TYPE
        }
    }
}

/**
 * The mic-device picker under the Microphone row (designs/mobile-app.html):
 * the connected kinds as chips — picking one while casting reroutes the LIVE
 * record (no session rebuild); idle it sets the next-start default. A kind
 * that vanished mid-cast shows the plain-words fallback fact instead.
 */
@Composable
private fun MicDevicePicker(
    effective: MicDeviceSource,
    fallbackFrom: MicDeviceSource?,
    onSelect: (MicDeviceSource) -> Unit,
) {
    val options = rememberMicOptions()
    val dividerColor = MaterialTheme.colorScheme.outline
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .drawBehind {
                val y = 0.5.dp.toPx()
                drawLine(
                    color = dividerColor,
                    start = Offset(0f, y),
                    end = Offset(size.width, y),
                    strokeWidth = 1.dp.toPx(),
                )
            }
            .padding(start = 16.dp, top = 4.dp, end = 16.dp, bottom = 14.dp),
    ) {
        CastChipRow(
            options = options.map { option -> micSourceName(option.source) to (option.source == effective) },
            onSelect = { index -> onSelect(options[index].source) },
        )
        if (fallbackFrom != null) {
            Text(
                text = stringResource(R.string.mic_device_fallback_fact, micSourceName(fallbackFrom)),
                fontSize = 13.sp,
                lineHeight = 19.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

/** h:mm:ss / mm:ss — the hero timer's format (unit-tested; clamps at zero). */
internal fun formatCastDuration(totalSeconds: Long): String {
    val total = totalSeconds.coerceAtLeast(0L)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
}

/** The 13sp muted explanation under a control. */
@Composable
private fun HintText(text: String) {
    Text(
        text = text,
        fontSize = 13.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 8.dp),
        lineHeight = 18.sp,
    )
}

/**
 * The thermal read-out of a running cast (Phase11, thermal.md) as a console
 * lamp: plain words for the platform ladder — read-only diagnostics, never
 * a technical dump and never an automatic action (AGENTS.md; the numbers
 * live in the service logs). DIM when normal, amber from warm, hot at
 * severe+. The advice lines are suggestions for the user.
 */
@Composable
private fun ThermalLedRow(thermalState: ThermalState, topDivider: Boolean) {
    val hot = thermalState.status == ThermalStatus.SEVERE ||
        thermalState.status == ThermalStatus.CRITICAL ||
        thermalState.status == ThermalStatus.EMERGENCY ||
        thermalState.status == ThermalStatus.SHUTDOWN
    LedListRow(
        text = when (thermalState.status) {
            ThermalStatus.NONE -> stringResource(R.string.thermal_normal)
            ThermalStatus.LIGHT -> stringResource(R.string.thermal_light)
            ThermalStatus.MODERATE -> stringResource(R.string.thermal_moderate)
            ThermalStatus.SEVERE -> stringResource(R.string.thermal_severe)
            ThermalStatus.CRITICAL, ThermalStatus.EMERGENCY, ThermalStatus.SHUTDOWN ->
                stringResource(R.string.thermal_critical)
        },
        tone = when {
            hot -> LedTone.HOT
            thermalState.status == ThermalStatus.NONE -> LedTone.DIM
            else -> LedTone.ON
        },
        hot = hot,
        topDivider = topDivider,
    )
}

/**
 * The auto-quality read-out of a running cast (Phase12, thermal.md) as a
 * lamp: plain words for what the policy did — the current level against the
 * ceiling (the user's settings at cast start) and, when one fired, what
 * happened and why. Quiet while nothing changed: no line, no log noise.
 */
@Composable
private fun AdaptiveLedRow(adaptiveState: AdaptiveUiState, topDivider: Boolean) {
    val context = LocalContext.current
    val ceiling = adaptiveState.ceiling ?: return
    val current = adaptiveState.current ?: return
    val change = adaptiveState.lastChange ?: return
    if (current == ceiling && adaptiveState.lastChange == null) return
    val down = change.direction == AdaptiveQualityController.Direction.DOWN
    LedListRow(
        text = when {
            down && change.reason == AdaptiveQualityController.Reason.THERMAL ->
                stringResource(R.string.adaptive_lowered_thermal, levelName(change.to))

            down -> stringResource(R.string.adaptive_lowered_stream, levelName(change.to))

            change.reason == AdaptiveQualityController.Reason.USER ->
                stringResource(R.string.adaptive_restored, levelName(change.to))

            else -> stringResource(R.string.adaptive_raised, levelName(change.to))
        },
        tone = if (down) LedTone.ON else LedTone.DIM,
        topDivider = topDivider,
        right = if (current != ceiling) {
            {
                CastTextButton(
                    text = stringResource(R.string.adaptive_restore),
                    onClick = { CastService.requestRestoreQuality(context) },
                )
            }
        } else {
            null
        },
    )
}

/** Localized name for a quality level — "your settings" for a tweaked top rung. */
@Composable
private fun levelName(level: QualityLevel): String = when (
    matchingProfile(level.longEdgePx, level.fps, level.bitrateMinBps, level.bitrateMaxBps)
) {
    QualityProfile.COOL -> stringResource(R.string.profile_cool)
    QualityProfile.BALANCED -> stringResource(R.string.profile_balanced)
    QualityProfile.PERFORMANCE -> stringResource(R.string.profile_performance)
    QualityProfile.SHARP -> stringResource(R.string.profile_sharp)
    null -> stringResource(R.string.adaptive_your_settings)
}

/**
 * Debug-only capture test signal (Phase7): a loud continuous tone from this
 * app — one of the few capturable sources, since apps targeting API29+ opt
 * OUT of playback capture by default. Audible on the desktop = the whole
 * game-audio chain works.
 */
@Composable
private fun DebugToneButton(topDivider: Boolean) {
    var playing by rememberSaveable { mutableStateOf(false) }
    val dividerColor = MaterialTheme.colorScheme.outline
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (topDivider) {
                    Modifier.drawBehind {
                        drawLine(
                            color = dividerColor,
                            start = Offset(0f, 0.5.dp.toPx()),
                            end = Offset(size.width, 0.5.dp.toPx()),
                            strokeWidth = 1.dp.toPx(),
                        )
                    }
                } else {
                    Modifier
                },
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(onClick = {
            if (playing) DebugTestTone.stop() else DebugTestTone.start()
            playing = !playing
        }) {
            Text(
                stringResource(if (playing) R.string.debug_stop_test_tone else R.string.debug_play_test_tone),
                fontSize = 13.sp,
            )
        }
    }
}

private const val TAG = "HomeScreen"

/**
 * The Home's ViewModels, injected once (ADR-006): the saved-desktop store
 * (Keystore-sealed) and the resume pairing machine — the machine writes every
 * granted/refreshed desktop identity straight into the store.
 */
@Composable
private fun homeFactory(): ViewModelProvider.Factory {
    val context = LocalContext.current
    return remember(context) {
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                val appContext = context.applicationContext
                val store = SavedDesktops(appContext, KeystoreTrustVault())
                return HomeViewModel(
                    savedDesktops = store,
                    pairingClient = PairingClient(
                        ::defaultTransportFactory,
                        ::defaultSignalingScheduler,
                        ::defaultUserAgent,
                        deviceIdProvider = { DeviceIdentity.id(appContext) },
                        onDesktopIdentity = { name, grant -> store.applyGrant(grant, name) },
                    ),
                ) as T
            }
        }
    }
}

/** The settings store, injected once — the Home's audio rows persist through it. */
@Composable
private fun settingsFactory(): ViewModelProvider.Factory {
    val context = LocalContext.current
    return remember(context) {
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                val store = CastSettingsStore(context.applicationContext)
                return SettingsViewModel(store.load(), store::save) as T
            }
        }
    }
}

@Preview(showBackground = true, widthDp = 360, heightDp = 640)
@Composable
private fun HomeContentConnectedPreview() {
    LocalCastTheme {
        HomeContent(
            castState = CastState.Idle,
            connection = ConnectedDesktop.State.Connected("Gaming PC"),
            settings = CastSettings.default(),
        )
    }
}

@Preview(showBackground = true, widthDp = 360, heightDp = 640)
@Composable
private fun HomeContentCastingPreview() {
    LocalCastTheme {
        HomeContent(
            castState = CastState.Casting("Gaming PC", startedAtMillis = System.currentTimeMillis() - 754_000),
            connection = ConnectedDesktop.State.Connected("Gaming PC"),
            gameAudioState = GameAudioState.Active,
            micState = MicState.Active,
            settings = CastSettings.default(),
        )
    }
}

@Preview(showBackground = true, widthDp = 360, heightDp = 640)
@Composable
private fun HomeContentStartingPreview() {
    LocalCastTheme {
        HomeContent(
            castState = CastState.Starting("Gaming PC"),
            connection = ConnectedDesktop.State.Connected("Gaming PC"),
            settings = CastSettings.default(),
        )
    }
}
