package com.zerofriction.localcast.ui.home

import android.Manifest
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
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.zerofriction.localcast.BuildConfig
import com.zerofriction.localcast.R
import com.zerofriction.localcast.audio.GameAudioState
import com.zerofriction.localcast.audio.MicState
import com.zerofriction.localcast.config.CastSettings
import com.zerofriction.localcast.config.CastSettingsStore
import com.zerofriction.localcast.config.QualityProfile
import com.zerofriction.localcast.debug.DebugTestTone
import com.zerofriction.localcast.service.CastState
import com.zerofriction.localcast.service.CastService
import com.zerofriction.localcast.thermal.ThermalState
import com.zerofriction.localcast.thermal.ThermalStatus
import com.zerofriction.localcast.ui.settings.CastSettingsPanel
import com.zerofriction.localcast.ui.settings.SettingsViewModel
import com.zerofriction.localcast.ui.theme.LocalCastTheme

/**
 * The home page (Phase10 restructure): a **header** for the connection
 * state (status + Start/Stop button) and, as its content, the cast settings
 * themselves (overview.md: the mobile is the configuration owner; the desktop
 * exposes none of this). The settings persist immediately and take effect on
 * the next cast — the note under the header says so.
 *
 * While a cast runs, the content also shows the live controls for that cast
 * (game-audio mute, mic on/off — Phases 7/8): settings apply to the *next*
 * cast, the live toggles to the *current* one.
 */
@Composable
fun HomeScreen(
    onStartCast: () -> Unit,
    viewModel: HomeViewModel = viewModel(),
) {
    val castState by viewModel.castState.collectAsStateWithLifecycle()
    val gameAudioState by CastService.gameAudioState.collectAsStateWithLifecycle()
    val micState by CastService.micState.collectAsStateWithLifecycle()
    val thermalState by CastService.thermalState.collectAsStateWithLifecycle()

    // The settings state (Phase10): the home page is its home now — one
    // ViewModel scoped to the activity, backed by the persistent store.
    val context = LocalContext.current
    val settingsViewModel: SettingsViewModel = viewModel(
        factory = remember(context) {
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    val store = CastSettingsStore(context.applicationContext)
                    return SettingsViewModel(store.load(), store::save) as T
                }
            }
        },
    )
    val settings by settingsViewModel.settings.collectAsStateWithLifecycle()

    HomeContent(
        castState = castState,
        gameAudioState = gameAudioState,
        micState = micState,
        thermalState = thermalState,
        settings = settings,
        onSelectProfile = settingsViewModel::selectProfile,
        onSelectLongEdge = settingsViewModel::selectLongEdge,
        onSelectFps = settingsViewModel::selectFps,
        onSetBitrateAuto = settingsViewModel::setBitrateAuto,
        onSetManualBitrateMax = settingsViewModel::setManualBitrateMax,
        onSetGameAudio = settingsViewModel::setGameAudio,
        onSetMic = settingsViewModel::setMic,
        onStartCast = onStartCast,
    )
}

@Composable
fun HomeContent(
    castState: CastState,
    gameAudioState: GameAudioState = GameAudioState.Off,
    micState: MicState = MicState.Off,
    thermalState: ThermalState = ThermalState(),
    settings: CastSettings = CastSettings.default(),
    onSelectProfile: (QualityProfile) -> Unit = {},
    onSelectLongEdge: (Int) -> Unit = {},
    onSelectFps: (Int) -> Unit = {},
    onSetBitrateAuto: (Boolean) -> Unit = {},
    onSetManualBitrateMax: (Int) -> Unit = {},
    onSetGameAudio: (Boolean) -> Unit = {},
    onSetMic: (Boolean) -> Unit = {},
    onStartCast: () -> Unit = {},
) {
    val context = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
    ) {
        // ---- Header: the connection state + the cast trigger ----
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
                Text(
                    text = when (val state = castState) {
                        is CastState.Casting -> stringResource(R.string.casting_to, state.desktopName)
                        is CastState.Starting -> stringResource(R.string.starting_cast)
                        is CastState.Failed -> state.message
                        CastState.Idle -> stringResource(R.string.home_not_connected)
                    },
                    fontSize = 14.sp,
                    color = if (castState is CastState.Failed) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
            Spacer(Modifier.width(12.dp))
            when (castState) {
                CastState.Idle, is CastState.Failed -> Button(onClick = onStartCast) {
                    Text(stringResource(R.string.start_cast))
                }

                is CastState.Starting -> CircularProgressIndicator()

                is CastState.Casting -> OutlinedButton(
                    onClick = { CastService.requestStop(context) },
                ) {
                    Text(stringResource(R.string.stop_casting))
                }
            }
        }

        Text(
            text = stringResource(R.string.settings_changes_next_cast),
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )

        // ---- Content: live controls for the running cast (if any), then
        // the settings for the next one ----
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
        ) {
            if (castState is CastState.Casting) {
                Spacer(Modifier.height(12.dp))
                Text(
                    text = stringResource(R.string.live_cast_section),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                )
                GameAudioControls(gameAudioState)
                MicControls(micState = micState, gameAudioState = gameAudioState)
                ThermalStatusLine(thermalState)
                if (BuildConfig.DEBUG) {
                    DebugToneButton()
                }
            }

            CastSettingsPanel(
                settings = settings,
                onSelectProfile = onSelectProfile,
                onSelectLongEdge = onSelectLongEdge,
                onSelectFps = onSelectFps,
                onSetBitrateAuto = onSetBitrateAuto,
                onSetManualBitrateMax = onSetManualBitrateMax,
                onSetGameAudio = onSetGameAudio,
                onSetMic = onSetMic,
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}

/**
 * The game-audio row of a running cast (Phase7, audio.md): a live mute
 * toggle plus an honest fact line. The capture can't produce sound for
 * opted-out apps or without the RECORD_AUDIO grant — those surface as plain
 * statements, never as technical errors (AGENTS.md).
 */
@Composable
fun GameAudioControls(gameAudioState: GameAudioState) {
    val context = LocalContext.current
    when (gameAudioState) {
        GameAudioState.Off -> Text(
            text = stringResource(R.string.game_audio_off),
            fontSize = 13.sp,
        )

        GameAudioState.Failed -> Text(
            text = stringResource(R.string.game_audio_unavailable),
            fontSize = 13.sp,
        )

        GameAudioState.Silent -> {
            Text(
                text = stringResource(R.string.game_audio_cannot_capture),
                fontSize = 13.sp,
            )
            Spacer(Modifier.height(4.dp))
        }

        else -> Unit
    }
    when (gameAudioState) {
        GameAudioState.Active, GameAudioState.Muted, GameAudioState.Silent -> OutlinedButton(
            onClick = { CastService.requestToggleGameAudio(context) },
        ) {
            Text(
                stringResource(
                    if (gameAudioState == GameAudioState.Muted) {
                        R.string.unmute_game_audio
                    } else {
                        R.string.mute_game_audio
                    },
                ),
            )
        }
        else -> Unit
    }
}

/**
 * The mic row of a running cast (Phase8, audio.md): a live on/off toggle —
 * on builds the `mic` pc on demand, off tears it down; the cast itself is
 * never renegotiated or stopped. Needs-permission surfaces as a plain fact
 * whose button asks for the grant, never as a technical error (AGENTS.md).
 * The headphones tip appears only when both audio sources are in this cast:
 * the phone speaker + live mic is the documented echo trap (audio.md).
 */
@Composable
fun MicControls(micState: MicState, gameAudioState: GameAudioState) {
    val context = LocalContext.current
    when (micState) {
        MicState.Off -> Text(
            text = stringResource(R.string.mic_off),
            fontSize = 13.sp,
        )

        MicState.NeedsPermission -> Text(
            text = stringResource(R.string.mic_needs_permission),
            fontSize = 13.sp,
        )

        MicState.Failed -> Text(
            text = stringResource(R.string.mic_unavailable),
            fontSize = 13.sp,
        )

        MicState.Active -> Unit
    }
    when (micState) {
        MicState.Off, MicState.Active -> OutlinedButton(
            onClick = { CastService.requestToggleMic(context) },
        ) {
            Text(
                stringResource(
                    if (micState == MicState.Active) {
                        R.string.turn_off_mic
                    } else {
                        R.string.turn_on_mic
                    },
                ),
            )
        }

        // Only the app (not the service) can show the permission dialog;
        // granting turns the mic on right away — denial keeps the honest fact.
        MicState.NeedsPermission -> {
            val permissionLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestPermission(),
            ) { granted ->
                if (granted) CastService.requestToggleMic(context)
            }
            OutlinedButton(
                onClick = { permissionLauncher.launch(Manifest.permission.RECORD_AUDIO) },
            ) {
                Text(stringResource(R.string.turn_on_mic))
            }
        }

        MicState.Failed -> Unit
    }
    if (micState == MicState.Active && gameAudioState != GameAudioState.Off) {
        Text(
            text = stringResource(R.string.mic_headphones_hint),
            fontSize = 13.sp,
            modifier = Modifier.padding(top = 4.dp),
        )
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

@Preview(showBackground = true, widthDp = 360, heightDp = 640)
@Composable
private fun HomeContentPreview() {
    LocalCastTheme {
        HomeContent(
            castState = CastState.Idle,
            gameAudioState = GameAudioState.Off,
            settings = CastSettings.default(),
        )
    }
}

@Preview(showBackground = true, widthDp = 360, heightDp = 640)
@Composable
private fun HomeContentCastingPreview() {
    LocalCastTheme {
        HomeContent(
            castState = CastState.Casting("MacBook Pro"),
            gameAudioState = GameAudioState.Active,
            micState = MicState.Active,
            settings = CastSettings.default(),
        )
    }
}
