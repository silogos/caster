package com.zerofriction.localcast.ui.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zerofriction.localcast.R
import com.zerofriction.localcast.config.AdvancedSettingChoices.ENCODER_FPS_LIMIT_CHOICES
import com.zerofriction.localcast.config.CastSettingChoices
import com.zerofriction.localcast.config.CastSettings
import com.zerofriction.localcast.config.DegradationStrategy
import com.zerofriction.localcast.config.EncoderBitrateMode
import com.zerofriction.localcast.config.EncoderImplementation
import com.zerofriction.localcast.config.PreferredVideoCodec
import com.zerofriction.localcast.config.QualityProfile
import kotlin.math.roundToInt

/**
 * The cast settings sections (designs/mobile-app.html) — rendered by
 * [CastSettingsScreen] (the gear from Home). The mobile is the configuration
 * owner (overview.md: every cast decision lives on the phone; the desktop
 * exposes none of this). Changes persist immediately via the callbacks
 * (SettingsViewModel) and take effect on the next cast: the cast-start path
 * reads the store fresh.
 *
 * [ShareScreenSettingsSection] holds the presets and send targets;
 * [AdvancedSettingsSection] is the collapsible encoder-level section — its
 * defaults reproduce today's cast, every knob is an explicit override, and
 * the presets reset it ([SettingsViewModel.selectProfile]).
 */
@Composable
fun ShareScreenSettingsSection(
    settings: CastSettings,
    onSelectProfile: (QualityProfile) -> Unit,
    onSelectLongEdge: (Int) -> Unit,
    onSelectFps: (Int) -> Unit,
    onSetBitrateAuto: (Boolean) -> Unit,
    onSetManualBitrateMax: (Int) -> Unit,
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
        HintText(R.string.settings_preset_sets_advanced_hint)

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
        HintText(R.string.settings_auto_quality_hint)
    }
}

/**
 * The collapsible Advanced section (designs/mobile-app.html): the encoder
 * implementation, codec/profile, pressure strategy, fps cap and rate-control
 * mode — all next-cast settings. Collapsed by default; the read-only
 * "Encoder in use" readout shows what libwebrtc actually picked for the
 * active cast ([SenderStats.encoderImplementation] via the service), never a
 * faked value.
 */
@Composable
fun AdvancedSettingsSection(
    settings: CastSettings,
    encoderInUse: String?,
    onSetEncoderImpl: (EncoderImplementation) -> Unit,
    onSetH264HighProfile: (Boolean) -> Unit,
    onSetPreferredCodec: (PreferredVideoCodec) -> Unit,
    onSetDegradationStrategy: (DegradationStrategy) -> Unit,
    onSetEncoderFpsLimit: (Int?) -> Unit,
    onSetBitrateMode: (EncoderBitrateMode) -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Column {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded },
        ) {
            // The prototype's text chevron — core icons don't ship one.
            Text(
                text = if (expanded) "▾" else "▸",
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = stringResource(R.string.settings_advanced),
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
            )
        }
        AnimatedVisibility(visible = expanded) {
            Column {
                SubLabel(R.string.settings_encoder)
                ChipRow(listOf(EncoderImplementation.HARDWARE, EncoderImplementation.SOFTWARE)) { impl ->
                    FilterChip(
                        selected = impl == settings.encoderImpl,
                        onClick = { onSetEncoderImpl(impl) },
                        label = {
                            Text(
                                stringResource(
                                    if (impl == EncoderImplementation.HARDWARE) R.string.settings_encoder_hardware
                                    else R.string.settings_encoder_software,
                                ),
                            )
                        },
                    )
                }
                HintText(
                    if (settings.encoderImpl == EncoderImplementation.HARDWARE) R.string.settings_encoder_hardware_hint
                    else R.string.settings_encoder_software_hint,
                )

                SubLabel(R.string.settings_preferred_codec)
                ChipRow(listOf(PreferredVideoCodec.H264, PreferredVideoCodec.VP8)) { codec ->
                    FilterChip(
                        selected = codec == settings.preferredCodec,
                        onClick = { onSetPreferredCodec(codec) },
                        label = {
                            Text(
                                stringResource(
                                    if (codec == PreferredVideoCodec.H264) R.string.settings_codec_h264
                                    else R.string.settings_codec_vp8,
                                ),
                            )
                        },
                    )
                }

                SubLabel(R.string.settings_h264_profile)
                ChipRow(listOf(false, true)) { high ->
                    FilterChip(
                        selected = high == settings.h264HighProfile,
                        enabled = settings.preferredCodec == PreferredVideoCodec.H264,
                        onClick = { onSetH264HighProfile(high) },
                        label = {
                            Text(
                                stringResource(
                                    if (high) R.string.settings_h264_profile_high
                                    else R.string.settings_h264_profile_baseline,
                                ),
                            )
                        },
                    )
                }
                if (settings.preferredCodec != PreferredVideoCodec.H264) {
                    HintText(R.string.settings_h264_profile_codec_hint)
                }

                SubLabel(R.string.settings_degradation)
                ChipRow(DegradationStrategy.entries) { strategy ->
                    FilterChip(
                        selected = strategy == settings.degradationStrategy,
                        onClick = { onSetDegradationStrategy(strategy) },
                        label = {
                            Text(
                                stringResource(
                                    when (strategy) {
                                        DegradationStrategy.BALANCED -> R.string.degradation_balanced
                                        DegradationStrategy.MAINTAIN_FRAMERATE -> R.string.degradation_framerate
                                        DegradationStrategy.MAINTAIN_RESOLUTION -> R.string.degradation_resolution
                                    },
                                ),
                            )
                        },
                    )
                }
                HintText(R.string.settings_degradation_hint)

                SubLabel(R.string.settings_encoder_fps_limit)
                ChipRow(listOf(null) + ENCODER_FPS_LIMIT_CHOICES) { limit ->
                    FilterChip(
                        selected = limit == settings.encoderFpsLimit,
                        onClick = { onSetEncoderFpsLimit(limit) },
                        label = {
                            Text(
                                limit?.let { stringResource(R.string.settings_fps_value, it) }
                                    ?: stringResource(R.string.settings_encoder_fps_off),
                            )
                        },
                    )
                }
                HintText(R.string.settings_encoder_fps_hint)

                SubLabel(R.string.settings_bitrate_mode)
                ChipRow(listOf(EncoderBitrateMode.CBR, EncoderBitrateMode.VBR)) { mode ->
                    FilterChip(
                        selected = mode == settings.bitrateMode,
                        onClick = { onSetBitrateMode(mode) },
                        label = {
                            Text(
                                stringResource(
                                    if (mode == EncoderBitrateMode.CBR) R.string.settings_bitrate_mode_cbr
                                    else R.string.settings_bitrate_mode_vbr,
                                ),
                            )
                        },
                    )
                }
                HintText(R.string.settings_bitrate_mode_hint)

                // Read-only fact: what libwebrtc actually picked for the
                // active cast — shown only when known, never faked.
                Spacer(Modifier.height(8.dp))
                Text(
                    text = if (encoderInUse !== null) {
                        stringResource(R.string.settings_encoder_in_use) + ": $encoderInUse"
                    } else {
                        stringResource(R.string.settings_encoder_in_use) + ": " +
                            stringResource(R.string.settings_encoder_in_use_none)
                    },
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                HintText(R.string.settings_advanced_note)
            }
        }
    }
}

// ---- shared primitives (the settings vocabulary, designs/mobile-app.html) ----

@Composable
internal fun SectionLabel(labelRes: Int) {
    Spacer(Modifier.height(20.dp))
    Text(
        text = stringResource(labelRes),
        fontSize = 15.sp,
        fontWeight = FontWeight.Medium,
    )
}

/** A lighter label for the items inside a section. */
@Composable
internal fun SubLabel(labelRes: Int) {
    Spacer(Modifier.height(12.dp))
    Text(
        text = stringResource(labelRes),
        fontSize = 13.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** The 13sp muted explanation under a control. */
@Composable
internal fun HintText(labelRes: Int) {
    Text(
        text = stringResource(labelRes),
        fontSize = 13.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 8.dp),
    )
}

@Composable
internal fun <T> ChipRow(choices: List<T>, chip: @Composable (T) -> Unit) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
            .horizontalScroll(rememberScrollState()),
    ) {
        for (choice in choices) chip(choice)
    }
}

@Composable
internal fun ToggleRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
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
