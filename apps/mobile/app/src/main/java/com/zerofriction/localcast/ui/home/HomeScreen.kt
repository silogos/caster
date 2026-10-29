package com.zerofriction.localcast.ui.home

import android.Manifest
import android.app.Activity
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
import com.zerofriction.localcast.ui.components.CastCard
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
    val casting = castState is CastState.Casting

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
        // ---- App bar: product name + the gear (all cast settings) ----
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(
                text = stringResource(R.string.home_title),
                fontSize = 17.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
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
                    contentDescription = stringResource(R.string.cast_settings_title),
                    modifier = Modifier.size(19.dp),
                )
            }
        }

        // ---- On-air hero: the tally section (who, and live-or-not) ----
        if (castState is CastState.Failed) {
            Spacer(Modifier.height(12.dp))
            CastCard {
                IconListRow(iconRes = R.drawable.ic_warning, title = castState.message, err = true)
            }
        } else {
            OnAirHero(
                casting = casting,
                desktopName = if (casting) {
                    (castState as CastState.Casting).desktopName
                } else {
                    (connection as? ConnectedDesktop.State.Connected)?.desktopName
                        ?: stringResource(R.string.home_not_connected)
                },
                startedAtMillis = (castState as? CastState.Casting)?.startedAtMillis ?: 0L,
                onDisconnect = onDisconnect,
            )
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
            IconListRow(
                iconRes = R.drawable.ic_mic,
                title = stringResource(R.string.home_microphone),
                sub = stringResource(R.string.home_mic_hint),
                fact = when (micState) {
                    MicState.Silenced -> stringResource(R.string.mic_silenced)
                    MicState.Failed -> stringResource(R.string.mic_unavailable)
                    else -> null
                },
                topDivider = true,
                right = {
                    CastSwitch(
                        checked = if (casting) micState != MicState.Off else settings.mic,
                        onCheckedChange = onSetMic,
                    )
                },
            )
        }

        // ---- "This cast": the live status lines while the video runs ----
        if (casting) {
            SilkscreenLabel(stringResource(R.string.live_cast_section))
            CastCard {
                if (micState == MicState.Active &&
                    (gameAudioState == GameAudioState.Active || gameAudioState == GameAudioState.Silent)
                ) {
                    IconListRow(
                        iconRes = R.drawable.ic_headphones,
                        title = stringResource(R.string.mic_headphones_hint),
                    )
                }
                ThermalLedRow(thermalState, topDivider = micState == MicState.Active &&
                    (gameAudioState == GameAudioState.Active || gameAudioState == GameAudioState.Silent))
                AdaptiveLedRow(adaptiveState, topDivider = true)
                if (BuildConfig.DEBUG) {
                    DebugToneButton(topDivider = true)
                }
            }
        }
        Spacer(Modifier.height(28.dp))
        }
    }
}

/**
 * The hero (the prototype's .hero): tally lamp + caps overline + desktop
 * name, the elapsed readout while casting, Disconnect quiet on the right.
 */
@Composable
private fun OnAirHero(
    casting: Boolean,
    desktopName: String,
    startedAtMillis: Long,
    onDisconnect: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 18.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                LedDot(tone = LedTone.ON, pulse = casting)
                Spacer(Modifier.width(8.dp))
                Text(
                    text = stringResource(
                        if (casting) R.string.casting_overline else R.string.connected_overline,
                    ).uppercase(),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    letterSpacing = 1.1.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = desktopName,
                fontSize = 22.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
                letterSpacing = (-0.25).sp,
                lineHeight = 27.sp,
                modifier = Modifier.padding(top = 6.dp),
            )
            if (casting) {
                LiveElapsed(startedAtMillis)
            }
        }
        Spacer(Modifier.width(12.dp))
        CastTextButton(text = stringResource(R.string.disconnect), onClick = onDisconnect, quiet = true)
    }
}

/**
 * The live elapsed readout under the hero name — tabular monospace so the
 * digits don't jitter. Anchored at the service's streaming transition
 * ([CastState.Casting.startedAtMillis]); a 0 (unknown) falls back to the
 * first composition, which stays honest on the screen the timer lives on.
 */
@Composable
private fun LiveElapsed(startedAtMillis: Long) {
    var fallbackStart by rememberSaveable { mutableLongStateOf(0L) }
    val start = if (startedAtMillis > 0) startedAtMillis else fallbackStart.also { if (it == 0L) fallbackStart = System.currentTimeMillis() }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(start) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1_000)
        }
    }
    Text(
        text = formatCastDuration(((now - start).coerceAtLeast(0L)) / 1_000),
        fontSize = 13.sp,
        fontFamily = FontFamily.Monospace,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 10.dp),
    )
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
