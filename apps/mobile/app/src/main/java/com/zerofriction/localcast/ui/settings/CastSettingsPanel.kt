package com.zerofriction.localcast.ui.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zerofriction.localcast.R
import com.zerofriction.localcast.config.AdvancedSettingChoices.ENCODER_FPS_LIMIT_CHOICES
import com.zerofriction.localcast.config.CastSettingChoices
import com.zerofriction.localcast.config.CastSettings
import com.zerofriction.localcast.config.DegradationStrategy
import com.zerofriction.localcast.config.EncoderBitrateMode
import com.zerofriction.localcast.config.EncoderImplementation
import com.zerofriction.localcast.config.MicDeviceSource
import com.zerofriction.localcast.config.PreferredVideoCodec
import com.zerofriction.localcast.config.QualityProfile
import com.zerofriction.localcast.ui.components.CastCard
import com.zerofriction.localcast.ui.components.CastSwitch
import com.zerofriction.localcast.ui.components.IconListRow
import com.zerofriction.localcast.ui.components.SilkscreenLabel
import kotlin.math.roundToInt

/**
 * The cast settings sections (designs/mobile-app.html — the
 * broadcast-console redesign) — rendered by [CastSettingsScreen] (the gear
 * from Home). The mobile is the configuration owner (overview.md: every
 * cast decision lives on the phone; the desktop exposes none of this).
 * Changes persist immediately via the callbacks (SettingsViewModel) and
 * take effect on the next cast: the cast-start path reads the store fresh.
 *
 * [ShareScreenSettingsSection] holds the profile rows (radio + intent +
 * the preset's real numbers) and the send targets; [AdvancedSettingsSection]
 * is the collapsible encoder-level section — its defaults reproduce today's
 * cast, every knob is an explicit override, and the presets reset it
 * ([SettingsViewModel.selectProfile]).
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
    // The cast's named profiles (Phase11, thermal.md): thermal presets
    // first-class — the intent line under the name states each one's
    // thermal cost, the mono spec line states its actual numbers. No row
    // selected when the values were tweaked past the preset — the honest
    // "Custom" row appears instead, never a fake preset match.
    val effectiveProfile = settings.toConfig().profile
    SilkscreenLabel(stringResource(R.string.settings_sharescreen))
    CastCard {
        for (preset in QualityProfile.entries) {
            val selected = preset.label == effectiveProfile
            ProfileRow(
                name = profileName(preset),
                intent = profileIntent(preset),
                spec = profileSpec(preset),
                selected = selected,
                topDivider = preset != QualityProfile.entries.first(),
                onClick = { onSelectProfile(preset) },
            )
        }
        if (effectiveProfile == "custom") {
            ProfileRow(
                name = stringResource(R.string.profile_custom),
                intent = stringResource(R.string.profile_intent_custom),
                spec = null,
                selected = true,
                topDivider = true,
                onClick = null,
            )
        }

        SegLabel(R.string.settings_resolution)
        SegRow(
            options = CastSettingChoices.LONG_EDGE_CHOICES.map {
                stringResource(R.string.settings_px, it) to (it == settings.longEdgePx)
            },
            onSelect = { index -> onSelectLongEdge(CastSettingChoices.LONG_EDGE_CHOICES[index]) },
        )

        SegLabel(R.string.settings_fps)
        SegRow(
            options = CastSettingChoices.FPS_CHOICES.map {
                stringResource(R.string.settings_fps_value, it) to (it == settings.fps)
            },
            onSelect = { index -> onSelectFps(CastSettingChoices.FPS_CHOICES[index]) },
        )

        SegLabel(R.string.settings_bitrate)
        IconListRow(
            iconRes = R.drawable.ic_tune,
            title = stringResource(R.string.settings_bitrate_auto),
            topDivider = true,
            right = { CastSwitch(checked = settings.bitrateAuto, onCheckedChange = onSetBitrateAuto) },
        )
        if (!settings.bitrateAuto) {
            val maxMbps = settings.bitrateMaxBps / BPS_PER_MBPS
            Column(Modifier.padding(horizontal = 16.dp)) {
                Slider(
                    value = maxMbps.toFloat(),
                    onValueChange = { mbps -> onSetManualBitrateMax((mbps * BPS_PER_MBPS).roundToInt()) },
                    valueRange = (CastSettingChoices.MANUAL_BITRATE_MIN_BPS / BPS_PER_MBPS).toFloat()..
                        (CastSettingChoices.MANUAL_BITRATE_MAX_BPS / BPS_PER_MBPS).toFloat(),
                    steps = ((CastSettingChoices.MANUAL_BITRATE_MAX_BPS - CastSettingChoices.MANUAL_BITRATE_MIN_BPS) / BPS_PER_MBPS) - 1,
                    modifier = Modifier.padding(top = 8.dp),
                )
                Text(
                    text = stringResource(R.string.settings_bitrate_max, maxMbps),
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // Phase12 (thermal.md): auto quality — conservative step-downs with
        // hysteresis, announced and reversible; off leaves the cast exactly
        // at these settings. The hint states when it acts, not how it works.
        IconListRow(
            iconRes = R.drawable.ic_check_circle,
            title = stringResource(R.string.settings_auto_quality),
            sub = stringResource(R.string.settings_auto_quality_hint),
            topDivider = true,
            right = { CastSwitch(checked = settings.autoQuality, onCheckedChange = onSetAutoQuality) },
        )

        CardFoot(R.string.settings_preset_sets_advanced_hint)
    }
}

/**
 * One selectable entry of the mic device section: the mic kind (config) plus
 * its user-facing label. The list is built from the currently connected
 * input devices (CastSettingsScreen) — a kind that isn't plugged in isn't
 * offered.
 */
data class MicDeviceOption(val source: MicDeviceSource, val labelRes: Int)

/**
 * The microphone device section: which mic the cast records from — the
 * built-in mic (always offered), plus wired/USB/Bluetooth when connected.
 * Applied when the mic session is built (mic toggle); a saved kind that
 * isn't connected falls back to the built-in mic at capture time
 * (MicDeviceResolver) — the same fact as its chip being gone from this list.
 */
@Composable
fun MicDeviceSection(
    selected: MicDeviceSource,
    options: List<MicDeviceOption>,
    onSelectMicDevice: (MicDeviceSource) -> Unit,
) {
    SilkscreenLabel(stringResource(R.string.settings_mic_device))
    CastCard {
        Column(Modifier.padding(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 14.dp)) {
            ChipRow(
                options = options.map { option ->
                    stringResource(option.labelRes) to (option.source == selected)
                },
                onSelect = { index -> onSelectMicDevice(options[index].source) },
            )
            HintText(R.string.settings_mic_device_hint)
        }
    }
}

/**
 * The collapsible Advanced section (designs/mobile-app.html): the encoder
 * implementation, codec/profile, pressure strategy, fps cap and rate-control
 * mode — all next-cast settings. Collapsed by default (the header row is
 * the toggle); the read-only "Encoder in use" readout shows what libwebrtc
 * actually picked for the active cast ([SenderStats.encoderImplementation]
 * via the service), never a faked value.
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
    SilkscreenLabel(stringResource(R.string.settings_advanced))
    CastCard {
        IconListRow(
            iconRes = R.drawable.ic_tune,
            title = stringResource(R.string.settings_advanced),
            right = {
                Icon(
                    painter = painterResource(
                        if (expanded) R.drawable.ic_chevron_down else R.drawable.ic_chevron_right,
                    ),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            },
            onClick = { expanded = !expanded },
        )
        AnimatedVisibility(visible = expanded) {
            val outline = MaterialTheme.colorScheme.outline
            Column(
                Modifier
                    .fillMaxWidth()
                    .drawBehind {
                        val y = 0.5.dp.toPx()
                        drawLine(
                            color = outline,
                            start = Offset(0f, y),
                            end = Offset(size.width, y),
                            strokeWidth = 1.dp.toPx(),
                        )
                    }
                    .padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 14.dp),
            ) {
                SubLabel(R.string.settings_encoder)
                ChipRow(
                    options = listOf(
                        stringResource(R.string.settings_encoder_hardware) to (settings.encoderImpl == EncoderImplementation.HARDWARE),
                        stringResource(R.string.settings_encoder_software) to (settings.encoderImpl == EncoderImplementation.SOFTWARE),
                    ),
                    onSelect = { index ->
                        onSetEncoderImpl(
                            if (index == 0) EncoderImplementation.HARDWARE else EncoderImplementation.SOFTWARE,
                        )
                    },
                )
                HintText(
                    if (settings.encoderImpl == EncoderImplementation.HARDWARE) R.string.settings_encoder_hardware_hint
                    else R.string.settings_encoder_software_hint,
                )

                SubLabel(R.string.settings_preferred_codec)
                ChipRow(
                    options = listOf(
                        stringResource(R.string.settings_codec_h264) to (settings.preferredCodec == PreferredVideoCodec.H264),
                        stringResource(R.string.settings_codec_vp8) to (settings.preferredCodec == PreferredVideoCodec.VP8),
                    ),
                    onSelect = { index ->
                        onSetPreferredCodec(
                            if (index == 0) PreferredVideoCodec.H264 else PreferredVideoCodec.VP8,
                        )
                    },
                )

                SubLabel(R.string.settings_h264_profile)
                ChipRow(
                    options = listOf(
                        stringResource(R.string.settings_h264_profile_baseline) to !settings.h264HighProfile,
                        stringResource(R.string.settings_h264_profile_high) to settings.h264HighProfile,
                    ),
                    enabled = settings.preferredCodec == PreferredVideoCodec.H264,
                    onSelect = { index -> onSetH264HighProfile(index == 1) },
                )
                if (settings.preferredCodec != PreferredVideoCodec.H264) {
                    HintText(R.string.settings_h264_profile_codec_hint)
                }

                SubLabel(R.string.settings_degradation)
                ChipRow(
                    options = DegradationStrategy.entries.map { strategy ->
                        stringResource(
                            when (strategy) {
                                DegradationStrategy.BALANCED -> R.string.degradation_balanced
                                DegradationStrategy.MAINTAIN_FRAMERATE -> R.string.degradation_framerate
                                DegradationStrategy.MAINTAIN_RESOLUTION -> R.string.degradation_resolution
                            },
                        ) to (strategy == settings.degradationStrategy)
                    },
                    onSelect = { index -> onSetDegradationStrategy(DegradationStrategy.entries[index]) },
                )
                HintText(R.string.settings_degradation_hint)

                SubLabel(R.string.settings_encoder_fps_limit)
                val fpsChoices = listOf(null) + ENCODER_FPS_LIMIT_CHOICES
                ChipRow(
                    options = fpsChoices.map { limit ->
                        (limit?.let { stringResource(R.string.settings_fps_value, it) }
                            ?: stringResource(R.string.settings_encoder_fps_off)) to
                            (limit == settings.encoderFpsLimit)
                    },
                    onSelect = { index -> onSetEncoderFpsLimit(fpsChoices[index]) },
                )
                HintText(R.string.settings_encoder_fps_hint)

                SubLabel(R.string.settings_bitrate_mode)
                ChipRow(
                    options = listOf(
                        stringResource(R.string.settings_bitrate_mode_cbr) to (settings.bitrateMode == EncoderBitrateMode.CBR),
                        stringResource(R.string.settings_bitrate_mode_vbr) to (settings.bitrateMode == EncoderBitrateMode.VBR),
                    ),
                    onSelect = { index ->
                        onSetBitrateMode(if (index == 0) EncoderBitrateMode.CBR else EncoderBitrateMode.VBR)
                    },
                )
                HintText(R.string.settings_bitrate_mode_hint)

                // Read-only fact (the prototype's console readout strip): what
                // libwebrtc actually picked for the active cast — shown only
                // when known, never faked.
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.04f),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp),
                ) {
                    Text(
                        text = if (encoderInUse !== null) {
                            stringResource(R.string.settings_encoder_in_use) + ": $encoderInUse"
                        } else {
                            stringResource(R.string.settings_encoder_in_use) + ": " +
                                stringResource(R.string.settings_encoder_in_use_none)
                        },
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    )
                }
                HintText(R.string.settings_advanced_note)
            }
        }
    }
}

// ---- section vocabulary (the prototype's .pick-row / .seg / .chip) ----

/** A profile row: radio + name + thermal intent + the preset's real spec. */
@Composable
private fun ProfileRow(
    name: String,
    intent: String,
    spec: String?,
    selected: Boolean,
    topDivider: Boolean,
    onClick: (() -> Unit)?,

    ) {
    val dividerColor = MaterialTheme.colorScheme.outline
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .then(if (topDivider) Modifier.drawBehind {
        val y = 0.5.dp.toPx()
        drawLine(
            color = dividerColor,
            start = Offset(0f, y),
            end = Offset(size.width, y),
            strokeWidth = 1.dp.toPx(),
        )
    } else Modifier)
            .background(
                if (selected) {
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.07f)
                } else {
                    Color.Transparent
                },
            )
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        // The prototype's .pick-radio: a 20dp ring, the 10dp dot when on.
        val ringColor = if (selected) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.30f)
        }
        Box(
            modifier = Modifier
                .size(20.dp)
                .drawBehind {
                    drawCircle(
                        color = ringColor,
                        style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.dp.toPx()),
                    )
                },
            contentAlignment = Alignment.Center,
        ) {
            if (selected) {
                Box(
                    Modifier
                        .size(10.dp)
                        .clip(RoundedCornerShape(50))
                        .background(MaterialTheme.colorScheme.primary),
                )
            }
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(text = name, fontSize = 15.sp, fontWeight = FontWeight.Medium, lineHeight = 20.sp)
            Text(
                text = intent,
                fontSize = 13.sp,
                lineHeight = 19.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
            if (spec != null) {
                Text(
                    text = spec,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    letterSpacing = 0.3.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 3.dp),
                )
            }
        }
    }
}

/** The prototype's .seg-label — a muted label above a segmented control. */
@Composable
private fun SegLabel(labelRes: Int) {
    Text(
        text = stringResource(labelRes),
        fontSize = 13.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 16.dp, top = 14.dp),
    )
}

/** The prototype's .seg: a connected switch bank for short option sets. */
@Composable
private fun SegRow(options: List<Pair<String, Boolean>>, onSelect: (Int) -> Unit) {
    val outline = MaterialTheme.colorScheme.outline
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = Color.Transparent,
        border = BorderStroke(1.dp, outline),
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 8.dp),
    ) {
        Row {
            options.forEachIndexed { index, (label, selected) ->
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .then(
                            if (index > 0) {
                                Modifier.drawBehind {
                                    val x = 0.5.dp.toPx()
                                    drawLine(
                                        color = outline,
                                        start = Offset(x, 0f),
                                        end = Offset(x, size.height),
                                        strokeWidth = 1.dp.toPx(),
                                    )
                                }
                            } else {
                                Modifier
                            },
                        )
                        .background(
                            if (selected) {
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                            } else {
                                Color.Transparent
                            },
                        )
                        .clickable { onSelect(index) }
                        .padding(vertical = 10.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = label,
                        fontSize = 14.sp,
                        textAlign = TextAlign.Center,
                        fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
                        color = if (selected) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                    )
                }
            }
        }
    }
}

/** The prototype's .chip: a pill selector (amber when selected). */
@Composable
private fun ChipRow(options: List<Pair<String, Boolean>>, enabled: Boolean = true, onSelect: (Int) -> Unit) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
            .horizontalScroll(rememberScrollState()),
    ) {
        options.forEachIndexed { index, (label, selected) ->
            Surface(
                onClick = { onSelect(index) },
                enabled = enabled,
                shape = RoundedCornerShape(50),
                color = if (selected) {
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                } else {
                    Color.Transparent
                },
                contentColor = if (selected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                border = BorderStroke(
                    1.dp,
                    if (selected) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.outline
                    },
                ),
                modifier = Modifier.then(
                    if (enabled) Modifier else Modifier.alpha(0.45f),
                ),
            ) {
                Text(
                    text = label,
                    fontSize = 14.sp,
                    fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                )
            }
        }
    }
}

/** The prototype's .card-foot: muted note under the card's last row. */
@Composable
private fun CardFoot(labelRes: Int) {
    val dividerColor = MaterialTheme.colorScheme.outline
    Text(
        text = stringResource(labelRes),
        fontSize = 13.sp,
        lineHeight = 19.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
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
            .padding(horizontal = 16.dp, vertical = 12.dp),
    )
}

/** A lighter label for the items inside a section. */
@Composable
private fun SubLabel(labelRes: Int) {
    Text(
        text = stringResource(labelRes),
        fontSize = 13.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 12.dp),
    )
}

/** The 13sp muted explanation under a control. */
@Composable
private fun HintText(labelRes: Int) {
    Text(
        text = stringResource(labelRes),
        fontSize = 13.sp,
        lineHeight = 19.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 8.dp),
    )
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
private fun profileIntent(preset: QualityProfile): String = when (preset) {
    QualityProfile.COOL -> stringResource(R.string.profile_intent_cool)
    QualityProfile.BALANCED -> stringResource(R.string.profile_intent_balanced)
    QualityProfile.PERFORMANCE -> stringResource(R.string.profile_intent_performance)
    QualityProfile.SHARP -> stringResource(R.string.profile_intent_sharp)
}

/** The preset's real numbers as a tabular spec (the prototype's .pick-spec). */
private fun profileSpec(preset: QualityProfile): String =
    "${preset.longEdgePx} px · ${preset.fps} fps · " +
        "${preset.bitrateMinBps / BPS_PER_MBPS}–${preset.bitrateMaxBps / BPS_PER_MBPS} Mbps"

/** bps ↔ Mbps for the manual-bitrate slider's display range. */
private const val BPS_PER_MBPS = 1_000_000
