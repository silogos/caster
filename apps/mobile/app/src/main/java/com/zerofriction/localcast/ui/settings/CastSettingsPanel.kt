package com.zerofriction.localcast.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zerofriction.localcast.R
import com.zerofriction.localcast.config.AdvancedSettingChoices.ENCODER_FPS_LIMIT_CHOICES
import com.zerofriction.localcast.config.CastSettings
import com.zerofriction.localcast.config.DegradationStrategy
import com.zerofriction.localcast.config.EncoderBitrateMode
import com.zerofriction.localcast.config.EncoderImplementation
import com.zerofriction.localcast.config.PreferredVideoCodec
import com.zerofriction.localcast.ui.components.CastCard
import com.zerofriction.localcast.ui.components.CastChipRow

/**
 * The Advanced card (designs/mobile-app.html — Settings is Advanced ONLY:
 * the profile/share-screen sections moved to Home's quality console). One
 * card, always expanded — the screen itself is the drawer. The mobile is
 * the configuration owner (overview.md); changes persist immediately via
 * the callbacks (SettingsViewModel) and take effect on the next cast.
 *
 * The policy master switch leads — "Automatic quality" as an On/Off chip
 * pair in the card's own vocabulary — then the encoder-level knobs. Their
 * defaults reproduce today's cast; the read-only "Encoder in use" readout
 * shows what libwebrtc actually picked for the active cast
 * ([SenderStats.encoderImplementation] via the service), never faked.
 */
@Composable
fun AdvancedSettingsSection(
    settings: CastSettings,
    encoderInUse: String?,
    onSetAutoQuality: (Boolean) -> Unit,
    onSetEncoderImpl: (EncoderImplementation) -> Unit,
    onSetH264HighProfile: (Boolean) -> Unit,
    onSetPreferredCodec: (PreferredVideoCodec) -> Unit,
    onSetDegradationStrategy: (DegradationStrategy) -> Unit,
    onSetEncoderFpsLimit: (Int?) -> Unit,
    onSetBitrateMode: (EncoderBitrateMode) -> Unit,
) {
    CastCard {
        Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 14.dp)) {
            // Phase12 (thermal.md) as the card's first block: whether quality
            // may adapt at all. Behavior belongs with the other policies,
            // not with the capture values (which live on Home now).
            SubLabel(R.string.settings_auto_quality)
            ChipRow(
                options = listOf(
                    stringResource(R.string.auto_quality_on) to settings.autoQuality,
                    stringResource(R.string.auto_quality_off) to !settings.autoQuality,
                ),
                onSelect = { index -> onSetAutoQuality(index == 0) },
            )
            HintText(R.string.settings_auto_quality_hint)

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

/** The advanced knobs' chip rows — the shared vocabulary (CastChipRow). */
@Composable
private fun ChipRow(options: List<Pair<String, Boolean>>, enabled: Boolean = true, onSelect: (Int) -> Unit) {
    CastChipRow(options = options, enabled = enabled, onSelect = onSelect)
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
