package com.zerofriction.localcast.ui.home

import android.Manifest
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
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.zerofriction.localcast.BuildConfig
import com.zerofriction.localcast.R
import com.zerofriction.localcast.audio.GameAudioState
import com.zerofriction.localcast.audio.MicState
import com.zerofriction.localcast.debug.DebugTestTone
import com.zerofriction.localcast.service.CastState
import com.zerofriction.localcast.service.CastService
import com.zerofriction.localcast.ui.theme.LocalCastTheme

@Composable
fun HomeScreen(
    onStartCast: () -> Unit,
    onOpenSettings: () -> Unit = {},
    viewModel: HomeViewModel = viewModel(),
) {
    val castState by viewModel.castState.collectAsStateWithLifecycle()
    val gameAudioState by CastService.gameAudioState.collectAsStateWithLifecycle()
    val micState by CastService.micState.collectAsStateWithLifecycle()

    HomeContent(
        castState = castState,
        gameAudioState = gameAudioState,
        micState = micState,
        onStartCast = onStartCast,
        onOpenSettings = onOpenSettings,
    )
}

/**
 * The home screen is session-aware (Phase6): the cast service owns the
 * session, so this screen is the cast's status line — a running cast shows
 * who it's going to and how to stop it, without going through the scan screen.
 */
@Composable
fun HomeContent(
    castState: CastState,
    gameAudioState: GameAudioState = GameAudioState.Off,
    micState: MicState = MicState.Off,
    onStartCast: () -> Unit,
    onOpenSettings: () -> Unit = {},
) {
    val context = LocalContext.current

    Scaffold { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = stringResource(R.string.home_title),
                fontSize = 24.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(8.dp))

            when (val state = castState) {
                CastState.Idle -> {
                    Text(text = stringResource(R.string.home_not_connected), fontSize = 14.sp)
                    Spacer(Modifier.height(24.dp))
                    Button(
                        // Phase3: opens the pairing scan (QR → WebSocket → handshake).
                        onClick = onStartCast,
                        modifier = Modifier.fillMaxWidth(0.6f),
                    ) {
                        Text(stringResource(R.string.start_cast))
                    }
                    // Phase10: the configuration owner's settings entry point.
                    SettingsEntryButton(onOpenSettings)
                }

                is CastState.Starting -> {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(12.dp))
                    Text(text = stringResource(R.string.starting_cast), fontSize = 14.sp)
                }

                is CastState.Casting -> {
                    Text(
                        text = stringResource(R.string.casting_to, state.desktopName),
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium,
                    )
                    Spacer(Modifier.height(12.dp))
                    GameAudioControls(gameAudioState)
                    MicControls(micState = micState, gameAudioState = gameAudioState)
                    if (BuildConfig.DEBUG) {
                        DebugToneButton()
                    }
                    Spacer(Modifier.height(24.dp))
                    OutlinedButton(
                        onClick = { CastService.requestStop(context) },
                        modifier = Modifier.fillMaxWidth(0.6f),
                    ) {
                        Text(stringResource(R.string.stop_casting))
                    }
                }

                is CastState.Failed -> {
                    Text(
                        text = state.message,
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Spacer(Modifier.height(24.dp))
                    Button(
                        onClick = onStartCast,
                        modifier = Modifier.fillMaxWidth(0.6f),
                    ) {
                        Text(stringResource(R.string.start_cast))
                    }
                    SettingsEntryButton(onOpenSettings)
                }
            }
        }
    }
}

/** The Phase10 settings entry point — under the cast trigger, both idle and after a failure. */
@Composable
private fun SettingsEntryButton(onOpenSettings: () -> Unit) {
    TextButton(onClick = onOpenSettings) {
        Text(stringResource(R.string.open_settings), fontSize = 13.sp)
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

@Preview(showBackground = true, widthDp =360, heightDp = 640)
@Composable
private fun HomeContentPreview() {
    LocalCastTheme {
        HomeContent(
            castState = CastState.Casting("MacBook Pro"),
            gameAudioState = GameAudioState.Active,
            onStartCast = {},
        )
    }
}
