package com.zerofriction.localcast.ui.home

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zerofriction.localcast.R
import com.zerofriction.localcast.adaptive.AdaptiveQualityController
import com.zerofriction.localcast.adaptive.QualityLevel
import com.zerofriction.localcast.config.QualityProfile
import com.zerofriction.localcast.config.matchingProfile
import com.zerofriction.localcast.debug.DebugTestTone
import com.zerofriction.localcast.service.AdaptiveUiState
import com.zerofriction.localcast.service.CastService
import com.zerofriction.localcast.thermal.ThermalState
import com.zerofriction.localcast.thermal.ThermalStatus
import com.zerofriction.localcast.ui.components.CastTextButton
import com.zerofriction.localcast.ui.components.LedListRow
import com.zerofriction.localcast.ui.components.LedTone

/** The 13sp muted explanation under a control. */
@Composable
internal fun HintText(text: String) {
    Text(
        text = text,
        fontSize = 13.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 8.dp),
        lineHeight = 18.sp,
    )
}

/**
 * What libwebrtc actually picked for the running cast — a read-only fact,
 * shown only once it is known, never faked. It sits with the cast's other
 * facts since the Advanced page went (ADR-024).
 */
@Composable
internal fun EncoderInUseRow(encoderInUse: String?, topDivider: Boolean) {
    if (encoderInUse === null) return
    LedListRow(
        text = stringResource(R.string.status_encoder_in_use, encoderInUse),
        tone = LedTone.DIM,
        topDivider = topDivider,
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
internal fun ThermalLedRow(thermalState: ThermalState, topDivider: Boolean) {
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
internal fun AdaptiveLedRow(adaptiveState: AdaptiveUiState, topDivider: Boolean) {
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
internal fun levelName(level: QualityLevel): String = when (
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
internal fun DebugToneButton(topDivider: Boolean) {
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
