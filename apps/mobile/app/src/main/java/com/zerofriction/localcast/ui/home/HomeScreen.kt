package com.zerofriction.localcast.ui.home

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
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
import com.zerofriction.localcast.audio.MicState
import com.zerofriction.localcast.capture.DisplaySize
import com.zerofriction.localcast.config.CastSettings
import com.zerofriction.localcast.config.CastSettingsStore
import com.zerofriction.localcast.config.QualityProfile
import com.zerofriction.localcast.config.matchingProfile
import com.zerofriction.localcast.debug.DebugTestTone
import com.zerofriction.localcast.service.AdaptiveUiState
import com.zerofriction.localcast.service.CastState
import com.zerofriction.localcast.service.CastService
import com.zerofriction.localcast.service.ConnectedDesktop
import com.zerofriction.localcast.thermal.ThermalState
import com.zerofriction.localcast.thermal.ThermalStatus
import com.zerofriction.localcast.ui.settings.SettingsViewModel
import com.zerofriction.localcast.ui.theme.LocalCastTheme

/**
 * The Home hub (designs/mobile-app.html): reached only through a successful
 * pairing on the scan screen. The header states the connection, the share
 * trigger (with the gear to all cast settings beside it) starts and stops
 * the **video** — "Stop share screen" leaves the pairing connected — and
 * Disconnect ends everything. The audio rows are the live toggles *and* the
 * next-cast defaults: only the microphone is ever fully removed from a cast;
 * game-audio "off" mutes the track, which stays part of the cast.
 */
@Composable
fun HomeScreen(
    onOpenSettings: () -> Unit,
    onDisconnected: () -> Unit,
    viewModel: HomeViewModel = viewModel(),
    settingsViewModel: SettingsViewModel = viewModel(factory = settingsFactory()),
) {
    val context = LocalContext.current
    val castState by viewModel.castState.collectAsStateWithLifecycle()
    val connection by ConnectedDesktop.state.collectAsStateWithLifecycle()
    val gameAudioState by CastService.gameAudioState.collectAsStateWithLifecycle()
    val micState by CastService.micState.collectAsStateWithLifecycle()
    val thermalState by CastService.thermalState.collectAsStateWithLifecycle()
    val adaptiveState by CastService.adaptiveState.collectAsStateWithLifecycle()
    val settings by settingsViewModel.settings.collectAsStateWithLifecycle()

    // No pairing session → nothing to show here: back to the scan screen.
    LaunchedEffect(connection) {
        if (connection is ConnectedDesktop.State.None) onDisconnected()
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
        gameAudioState = gameAudioState,
        micState = micState,
        thermalState = thermalState,
        adaptiveState = adaptiveState,
        settings = settings,
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
        onOpenSettings = onOpenSettings,
    )
}

@Composable
fun HomeContent(
    castState: CastState,
    connection: ConnectedDesktop.State = ConnectedDesktop.State.Connected("Gaming PC"),
    gameAudioState: GameAudioState = GameAudioState.Off,
    micState: MicState = MicState.Off,
    thermalState: ThermalState = ThermalState(),
    adaptiveState: AdaptiveUiState = AdaptiveUiState(),
    settings: CastSettings = CastSettings.default(),
    onSetGameAudio: (Boolean) -> Unit = {},
    onSetMic: (Boolean) -> Unit = {},
    onShareScreen: () -> Unit = {},
    onStopShareScreen: () -> Unit = {},
    onDisconnect: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
    ) {
        // ---- Header: title, connection status, Disconnect ----
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.home_title),
                    fontSize = 20.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(2.dp))
                when (val state = castState) {
                    is CastState.Failed -> StatusRow(
                        message = state.message,
                        color = MaterialTheme.colorScheme.error,
                    )

                    else -> Text(
                        text = when (state) {
                            is CastState.Casting -> stringResource(R.string.casting_to, state.desktopName)
                            else -> (connection as? ConnectedDesktop.State.Connected)
                                ?.let { stringResource(R.string.connected_to, it.desktopName) }
                                ?: stringResource(R.string.home_not_connected)
                        },
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }
            TextButton(onClick = onDisconnect) {
                Text(stringResource(R.string.disconnect))
            }
        }

        // ---- Share screen + gear (all cast settings) ----
        when (castState) {
            is CastState.Starting -> {
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator()
                    Spacer(Modifier.width(10.dp))
                    Text(text = stringResource(R.string.starting_cast), fontSize = 14.sp)
                }
                // mobile.md consent affordance (Android 14+): the dialog shown
                // during this window defaults to the cast-killing choice.
                HintText(stringResource(R.string.share_full_screen_hint))
            }

            else -> {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(top = 12.dp),
                ) {
                    Button(onClick = if (castState is CastState.Casting) onStopShareScreen else onShareScreen) {
                        Text(
                            stringResource(
                                if (castState is CastState.Casting) R.string.stop_share_screen else R.string.share_screen_action,
                            ),
                        )
                    }
                    OutlinedButton(onClick = onOpenSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.cast_settings_title))
                    }
                }
            }
        }

        // ---- Audio rows: live toggles and next-cast defaults ----
        SectionLabel(R.string.settings_audio)
        // Game audio: the track always rides the cast (when permission
        // allows) — the switch is audibility. Only the microphone is ever
        // fully removed (designs/mobile-app.html, ADR-004 semantics).
        AudioRow(
            label = stringResource(R.string.home_game_audio),
            checked = when {
                castState is CastState.Casting && gameAudioState != GameAudioState.Off ->
                    gameAudioState != GameAudioState.Muted

                else -> settings.gameAudio
            },
            onCheckedChange = onSetGameAudio,
        )
        HintText(stringResource(R.string.home_game_audio_hint))
        when (gameAudioState) {
            GameAudioState.Silent -> FactText(stringResource(R.string.game_audio_cannot_capture))
            GameAudioState.Failed -> FactText(stringResource(R.string.game_audio_unavailable))
            GameAudioState.Off -> if (castState is CastState.Casting) FactText(stringResource(R.string.game_audio_off))
            else -> Unit
        }

        AudioRow(
            label = stringResource(R.string.home_microphone),
            checked = if (castState is CastState.Casting) micState != MicState.Off else settings.mic,
            onCheckedChange = onSetMic,
        )
        HintText(stringResource(R.string.home_mic_hint))
        when (micState) {
            MicState.Silenced -> FactText(stringResource(R.string.mic_silenced))
            MicState.Failed -> FactText(stringResource(R.string.mic_unavailable))
            else -> Unit
        }

        // ---- "This cast": the live status lines while the video runs ----
        if (castState is CastState.Casting) {
            SectionLabel(R.string.live_cast_section)
            if (micState == MicState.Active &&
                (gameAudioState == GameAudioState.Active || gameAudioState == GameAudioState.Silent)
            ) {
                FactText(stringResource(R.string.mic_headphones_hint))
            }
            ThermalStatusLine(thermalState)
            AdaptiveStatusLine(adaptiveState)
            if (BuildConfig.DEBUG) {
                DebugToneButton()
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

/** Icon + message row — the one failure treatment on every screen (prototype). */
@Composable
private fun StatusRow(message: String, color: Color) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(text = "⚠", fontSize = 15.sp, color = color)
        Text(text = message, fontSize = 14.sp, color = color)
    }
}

@Composable
private fun SectionLabel(labelRes: Int) {
    Spacer(Modifier.height(20.dp))
    Text(
        text = stringResource(labelRes),
        fontSize = 15.sp,
        fontWeight = FontWeight.Medium,
    )
}

/** The 13sp muted explanation under a control. */
@Composable
private fun HintText(text: String) {
    Text(
        text = text,
        fontSize = 13.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top =8.dp),
        lineHeight = 18.sp,
    )
}

/** A 13sp plain-words fact about the running cast (AGENTS.md: no jargon). */
@Composable
private fun FactText(text: String) {
    Text(
        text = text,
        fontSize = 13.sp,
        modifier = Modifier.padding(top = 4.dp),
        lineHeight = 18.sp,
    )
}

/** Label + switch — one control per track (prototype: no separate mute buttons). */
@Composable
private fun AudioRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 6.dp),
    ) {
        Text(text = label, fontSize = 15.sp, modifier = Modifier.padding(end = 16.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

/**
 * The thermal read-out of a running cast (Phase11, thermal.md): plain words
 * for the platform ladder — read-only diagnostics, never a technical dump
 * and never an automatic action (AGENTS.md; the numbers live in the service
 * logs). The advice lines (cooler profile / stop) are suggestions for the
 * user, the app changes nothing on its own.
 */
@Composable
fun ThermalStatusLine(thermalState: ThermalState) {
    val text = when (thermalState.status) {
        ThermalStatus.NONE -> stringResource(R.string.thermal_normal)
        ThermalStatus.LIGHT -> stringResource(R.string.thermal_light)
        ThermalStatus.MODERATE -> stringResource(R.string.thermal_moderate)
        ThermalStatus.SEVERE -> stringResource(R.string.thermal_severe)
        ThermalStatus.CRITICAL, ThermalStatus.EMERGENCY,
        ThermalStatus.SHUTDOWN,
        -> stringResource(R.string.thermal_critical)
    }
    Text(
        text = text,
        fontSize = 13.sp,
        modifier = Modifier.padding(top = 4.dp),
    )
}

/**
 * The auto-quality read-out of a running cast (Phase12, thermal.md): plain
 * words for what the policy did — the current level against the ceiling
 * (the user's settings at cast start) and, when one fired, what happened
 * and why. Quiet while nothing changed: no line, no log noise.
 */
@Composable
fun AdaptiveStatusLine(adaptiveState: AdaptiveUiState) {
    val context = LocalContext.current
    val ceiling = adaptiveState.ceiling ?: return
    val current = adaptiveState.current ?: return
    if (current == ceiling && adaptiveState.lastChange == null) return
    val change = adaptiveState.lastChange ?: return
    Column(modifier = Modifier.padding(top = 4.dp)) {
        Text(
            text = when {
                change.direction == AdaptiveQualityController.Direction.DOWN &&
                    change.reason == AdaptiveQualityController.Reason.THERMAL ->
                    stringResource(R.string.adaptive_lowered_thermal, levelName(change.to))

                change.direction == AdaptiveQualityController.Direction.DOWN ->
                    stringResource(R.string.adaptive_lowered_stream, levelName(change.to))

                change.reason == AdaptiveQualityController.Reason.USER ->
                    stringResource(R.string.adaptive_restored, levelName(change.to))

                else -> stringResource(R.string.adaptive_raised, levelName(change.to))
            },
            fontSize = 13.sp,
        )
        if (current != ceiling) {
            OutlinedButton(
                onClick = { CastService.requestRestoreQuality(context) },
                modifier = Modifier.padding(top = 4.dp),
            ) {
                Text(stringResource(R.string.adaptive_restore))
            }
        }
    }
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
fun DebugToneButton() {
    var playing by rememberSaveable { mutableStateOf(false) }
    TextButton(
        onClick = {
            if (playing) DebugTestTone.stop() else DebugTestTone.start()
            playing = !playing
        },
    ) {
        Text(
            stringResource(
                if (playing) R.string.debug_stop_test_tone else R.string.debug_play_test_tone,
            ),
            fontSize = 13.sp,
        )
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
            castState = CastState.Casting("Gaming PC"),
            connection = ConnectedDesktop.State.Connected("Gaming PC"),
            gameAudioState = GameAudioState.Active,
            micState = MicState.Active,
            settings = CastSettings.default(),
        )
    }
}
