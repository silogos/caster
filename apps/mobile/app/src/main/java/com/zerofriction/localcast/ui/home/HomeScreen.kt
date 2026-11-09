package com.zerofriction.localcast.ui.home

import android.Manifest
import android.app.Activity
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.zerofriction.localcast.BuildConfig
import com.zerofriction.localcast.R
import com.zerofriction.localcast.adaptive.QualityLevel
import com.zerofriction.localcast.audio.GameAudioState
import com.zerofriction.localcast.audio.MicState
import com.zerofriction.localcast.capture.DisplaySize
import com.zerofriction.localcast.config.CastSettings
import com.zerofriction.localcast.config.CastSettingsStore
import com.zerofriction.localcast.config.MicDeviceSource
import com.zerofriction.localcast.config.QualityProfile
import com.zerofriction.localcast.hotspot.HotspotControl
import com.zerofriction.localcast.hotspot.HotspotMachine
import com.zerofriction.localcast.hotspot.hotspotHintFor
import com.zerofriction.localcast.pairing.PairingClient
import com.zerofriction.localcast.pairing.PairingError
import com.zerofriction.localcast.service.AdaptiveUiState
import com.zerofriction.localcast.service.AudioRoutingUiState
import com.zerofriction.localcast.service.CastService
import com.zerofriction.localcast.service.CastState
import com.zerofriction.localcast.service.ConnectedDesktop
import com.zerofriction.localcast.service.MicRoutingUiState
import com.zerofriction.localcast.service.VcCapability
import com.zerofriction.localcast.service.VirtualCameraUiState
import com.zerofriction.localcast.signaling.Envelope
import com.zerofriction.localcast.thermal.ThermalState
import com.zerofriction.localcast.ui.components.AnnuncTone
import com.zerofriction.localcast.ui.components.Annunciator
import com.zerofriction.localcast.ui.components.BrandMark
import com.zerofriction.localcast.ui.components.CardFoot
import com.zerofriction.localcast.ui.components.CastCard
import com.zerofriction.localcast.ui.components.CastSegRow
import com.zerofriction.localcast.ui.components.CastSwitch
import com.zerofriction.localcast.ui.components.CastTextButton
import com.zerofriction.localcast.ui.components.IconListRow
import com.zerofriction.localcast.ui.components.SilkscreenLabel
import com.zerofriction.localcast.ui.settings.SettingsViewModel
import com.zerofriction.localcast.ui.theme.LocalCastTheme

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

    // The share consent ladder (ShareConsent.kt): every grant is non-fatal;
    // the projection consent is per cast, never stored.
    val shareScreen = rememberShareScreenFlow { consentData ->
        val signaling = ConnectedDesktop.releaseForCast() ?: return@rememberShareScreenFlow
        val (width, height) = DisplaySize.physicalPx(context)
        // "Changes take effect on the next cast": read the store fresh.
        val castSettings = CastSettingsStore(context).load()
        val started = CastService.start(
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
        if (!started) ConnectedDesktop.takeBack(signaling)
    }
    val micPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) CastService.requestToggleMic(context)
    }

    // Hotspot pairing mode (issue #96, ADR-011): the failure card's escape
    // for the no-shared-network case. The permission the OS ties to
    // local-only hotspots is version-dependent (HotspotControl decides).
    val hotspotState by HotspotControl.state.collectAsStateWithLifecycle()
    val hotspotPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        HotspotControl.permissionResult(granted, context)
    }
    val startHotspot: () -> Unit = {
        if (HotspotControl.hasPermission(context)) HotspotControl.start(context)
        else hotspotPermissionLauncher.launch(HotspotControl.requiredPermission())
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
        onShareScreen = shareScreen,
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
        hotspotState = hotspotState,
        onStartHotspot = startHotspot,
        onStopHotspot = { HotspotControl.stop() },
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
        hotspotState: HotspotMachine.State = HotspotMachine.State.Idle,
        onStartHotspot: () -> Unit = {},
        onStopHotspot: () -> Unit = {},
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
        // The unreachable case (the #1 error — no shared network) gains the
        // hotspot escape (issue #96): the failure becomes a three-step
        // solution instead of a dead end.
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
                if (hotspotHintFor(resumeFailure.error)) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(18.dp),
                        modifier = Modifier.padding(start = 16.dp, bottom = 12.dp),
                    ) {
                        CastTextButton(text = stringResource(R.string.hotspot_hint_button), onClick = onStartHotspot)
                    }
                }
            }
        }

        // ---- Hotspot pairing mode (issue #96, ADR-011): the phone hosts the
        // network the desktop joins; SSID + password live here while the
        // guided-manual flow runs (start → join on the desktop → scan its
        // fresh QR). The hotspot is explicit: it stays on while casting and
        // only the Stop button (or the OS) ends it. ----
        if (hotspotState != HotspotMachine.State.Idle) {
            Spacer(Modifier.height(8.dp))
            HotspotCard(state = hotspotState, onStop = onStopHotspot, onRetry = onStartHotspot)
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

/**
 * The virtual camera row's honest subline (ADR-007): what turning it on does
 * right now — setup pending, publishing, or plain ready. Pure — JVM-tested.
 */
internal fun vcSublineRes(state: VirtualCameraUiState): Int = when {
    state.active -> R.string.vc_hint_active
    state.capability == VcCapability.NeedsSetup -> R.string.vc_hint_needs_setup
    else -> R.string.vc_hint_ready
}

/** Idle-with-a-pairing reads STANDBY — Home is only shown while connected. */
internal fun annunciatorFor(castState: CastState): ConsoleAnnunciator = when (castState) {
    is CastState.Starting -> ConsoleAnnunciator.STARTING
    is CastState.Casting -> ConsoleAnnunciator.ON_AIR
    is CastState.Failed -> ConsoleAnnunciator.FAULT
    CastState.Idle -> ConsoleAnnunciator.STANDBY
}

/** h:mm:ss / mm:ss — the hero timer's format (unit-tested; clamps at zero). */
internal fun formatCastDuration(totalSeconds: Long): String {
    val total = totalSeconds.coerceAtLeast(0L)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
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
