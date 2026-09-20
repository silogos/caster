package com.zerofriction.localcast.ui.settings

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zerofriction.localcast.R
import com.zerofriction.localcast.config.CastSettingChoices
import com.zerofriction.localcast.config.CastSettings
import com.zerofriction.localcast.config.QualityProfile
import kotlin.math.roundToInt

/**
 * The cast settings panel (Phase10) — the home page's content. The mobile is
 * the configuration owner (overview.md: every cast decision lives on the
 * phone; the desktop exposes none of this). Changes persist immediately via
 * the callbacks (SettingsViewModel) and take effect on the next cast: the
 * cast-start path reads the store fresh.
 *
 * Sections: *Share screen* (profile, resolution, frame rate, bitrate, and
 * the Phase12 auto-quality switch) and *Audio* (game sound + mic defaults
 * for the next cast). The profile row is the Phase11 thermal vocabulary
 * (thermal.md): cool · balanced · performance (+ sharp, fidelity over
 * thermals).
 */
@Composable
fun CastSettingsPanel(
    settings: CastSettings,
    onSelectProfile: (QualityProfile) -> Unit,
    onSelectLongEdge: (Int) -> Unit,
    onSelectFps: (Int) -> Unit,
    onSetBitrateAuto: (Boolean) -> Unit,
    onSetManualBitrateMax: (Int) -> Unit,
    onSetGameAudio: (Boolean) -> Unit,
    onSetMic: (Boolean) -> Unit,
    onSetAutoQuality: (Boolean) -> Unit,
) {
    Column {
        SectionLabel(R.string.settings_sharescreen)
        // The cast's named profiles (Phase11, thermal.md): thermal presets
        // first-class — the intent line under the chips states each one's
        // thermal cost. No chip selected when the values were tweaked past
        // the preset — the honest "custom" state, not a fake preset match.
        SubLabel(R.string.settings_profile)
        val effectiveProfile = settings.toConfig().profile
        ChipRow(QualityProfile.entries) { preset ->
            FilterChip(
                selected = preset.label == effectiveProfile,
                onClick = { onSelectProfile(preset) },
                label = { Text(profileName(preset)) },
            )
        }
        Text(
            text = profileIntent(effectiveProfile),
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        SubLabel(R.string.settings_resolution)
        ChipRow(CastSettingChoices.LONG_EDGE_CHOICES) { longEdge ->
            FilterChip(
                selected = longEdge == settings.longEdgePx,
                onClick = { onSelectLongEdge(longEdge) },
                label = { Text(stringResource(R.string.settings_px, longEdge)) },
            )
        }

        SubLabel(R.string.settings_fps)
        ChipRow(CastSettingChoices.FPS_CHOICES) { fps ->
            FilterChip(
                selected = fps == settings.fps,
                onClick = { onSelectFps(fps) },
                label = { Text(stringResource(R.string.settings_fps_value, fps)) },
            )
        }

        SubLabel(R.string.settings_bitrate)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = stringResource(R.string.settings_bitrate_auto),
                fontSize = 15.sp,
                modifier = Modifier.padding(end = 16.dp),
            )
            Switch(checked = settings.bitrateAuto, onCheckedChange = onSetBitrateAuto)
        }
        if (!settings.bitrateAuto) {
            val maxMbps = settings.bitrateMaxBps / BPS_PER_MBPS
            Slider(
                value = maxMbps.toFloat(),
                onValueChange = { mbps -> onSetManualBitrateMax((mbps * BPS_PER_MBPS).roundToInt()) },
                valueRange = (CastSettingChoices.MANUAL_BITRATE_MIN_BPS / BPS_PER_MBPS).toFloat()..
                    (CastSettingChoices.MANUAL_BITRATE_MAX_BPS / BPS_PER_MBPS).toFloat(),
                steps = ((CastSettingChoices.MANUAL_BITRATE_MAX_BPS - CastSettingChoices.MANUAL_BITRATE_MIN_BPS) / BPS_PER_MBPS) - 1,
            )
            Text(
                text = stringResource(R.string.settings_bitrate_max, maxMbps),
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // Phase12 (thermal.md): auto quality — conservative step-downs with
        // hysteresis, announced and reversible; off leaves the cast exactly
        // at these settings. The hint states when it acts, not how it works.
        ToggleRow(
            label = stringResource(R.string.settings_auto_quality),
            checked = settings.autoQuality,
            onCheckedChange = onSetAutoQuality,
        )
        Text(
            text = stringResource(R.string.settings_auto_quality_hint),
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        SectionLabel(R.string.settings_audio)
        ToggleRow(
            label = stringResource(R.string.settings_game_audio),
            checked = settings.gameAudio,
            onCheckedChange = onSetGameAudio,
        )
        ToggleRow(
            label = stringResource(R.string.settings_mic),
            checked = settings.mic,
            onCheckedChange = onSetMic,
        )
        Text(
            text = stringResource(R.string.settings_mic_hint),
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
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

/** A lighter label for the items inside a section. */
@Composable
private fun SubLabel(labelRes: Int) {
    Spacer(Modifier.height(12.dp))
    Text(
        text = stringResource(labelRes),
        fontSize = 13.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun <T> ChipRow(choices: List<T>, chip: @Composable (T) -> Unit) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
    ) {
        for (choice in choices) chip(choice)
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
    ) {
        Text(text = label, fontSize = 15.sp, modifier = Modifier.padding(end = 16.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun profileName(profile: QualityProfile): String = when (profile) {
    QualityProfile.COOL -> stringResource(R.string.profile_cool)
    QualityProfile.BALANCED -> stringResource(R.string.profile_balanced)
    QualityProfile.PERFORMANCE -> stringResource(R.string.profile_performance)
    QualityProfile.SHARP -> stringResource(R.string.profile_sharp)
}

/**
 * The selected profile's thermal intent (thermal.md) — the user-facing half
 * of why these presets exist: choosing among them *is* the thermal decision.
 */
@Composable
private fun profileIntent(label: String): String = when (label) {
    QualityProfile.COOL.label -> stringResource(R.string.profile_intent_cool)
    QualityProfile.BALANCED.label -> stringResource(R.string.profile_intent_balanced)
    QualityProfile.PERFORMANCE.label -> stringResource(R.string.profile_intent_performance)
    QualityProfile.SHARP.label -> stringResource(R.string.profile_intent_sharp)
    else -> stringResource(R.string.profile_intent_custom)
}

/** bps ↔ Mbps for the manual-bitrate slider's display range. */
private const val BPS_PER_MBPS = 1_000_000
