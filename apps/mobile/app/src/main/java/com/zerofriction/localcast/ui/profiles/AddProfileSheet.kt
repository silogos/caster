package com.zerofriction.localcast.ui.profiles

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zerofriction.localcast.R
import com.zerofriction.localcast.config.CastSettings
import com.zerofriction.localcast.config.CustomProfileRules
import com.zerofriction.localcast.config.DegradationStrategy
import com.zerofriction.localcast.config.EncoderImplementation
import com.zerofriction.localcast.config.PreferredVideoCodec
import com.zerofriction.localcast.config.ProfileValues
import com.zerofriction.localcast.config.QualityProfile
import com.zerofriction.localcast.config.toValues
import com.zerofriction.localcast.ui.components.CastChipRow
import com.zerofriction.localcast.ui.components.CastTextButton
import com.zerofriction.localcast.ui.home.profileName
import com.zerofriction.localcast.ui.home.profileSpec

/**
 * Adding a profile (ADR-024): a sheet over Home. A new profile is always a
 * copy — of the values on screen, or of any profile — so it never starts
 * from values nobody chose; the strip shows what will be copied before it is
 * created.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddProfileSheet(
    settings: CastSettings,
    onCreate: (String, ProfileValues) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        AddProfileContent(settings = settings, onCreate = onCreate, onCancel = onDismiss)
    }
}

@Composable
fun AddProfileContent(
    settings: CastSettings,
    onCreate: (String, ProfileValues) -> Unit,
    onCancel: () -> Unit,
) {
    // What a new profile may start from: the values on screen, then every profile by name.
    val sources: List<Pair<String, ProfileValues>> = buildList {
        add(stringResource(R.string.profile_start_from_current) to settings.active)
        for (preset in QualityProfile.entries) add(profileName(preset) to preset.toValues())
        for (profile in settings.customProfiles) add(profile.name to profile.values)
    }
    var picked by rememberSaveable { mutableIntStateOf(0) }
    var name by rememberSaveable { mutableStateOf(CustomProfileRules.nextName(settings.customProfiles)) }
    val values = sources[picked.coerceIn(sources.indices)].second
    val cleanName = CustomProfileRules.cleanName(name)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(start = 24.dp, end = 24.dp, bottom = 24.dp),
    ) {
        Text(
            text = stringResource(R.string.profile_new_title),
            fontSize = 20.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(bottom = 12.dp),
        )
        ProfileNameField(value = name, onValueChange = { name = it })

        Text(
            text = stringResource(R.string.profile_start_from),
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
        CastChipRow(
            options = sources.mapIndexed { index, (label, _) -> label to (index == picked) },
            onSelect = { picked = it },
        )

        // What will be copied: the picture, then what it gives up and its encoder.
        Surface(
            shape = RoundedCornerShape(10.dp),
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.04f),
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 14.dp),
        ) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                for (line in listOf(profileSpec(values), behaviourSummary(values))) {
                    Text(
                        text = line,
                        fontSize = 12.sp,
                        lineHeight = 17.sp,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        Row(
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 16.dp),
        ) {
            CastTextButton(text = stringResource(R.string.cancel), onClick = onCancel, quiet = true)
            Button(
                onClick = { cleanName?.let { onCreate(it, values) } },
                enabled = cleanName != null,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.padding(start = 12.dp),
            ) {
                Text(text = stringResource(R.string.profile_create), fontSize = 15.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

/** "Keep frame rate · Hardware · H.264 High": what a profile gives up and its encoder, in the app's own words. */
@Composable
private fun behaviourSummary(values: ProfileValues): String = listOf(
    stringResource(
        when (values.degradationStrategy) {
            DegradationStrategy.BALANCED -> R.string.degradation_balanced
            DegradationStrategy.MAINTAIN_FRAMERATE -> R.string.degradation_framerate
            DegradationStrategy.MAINTAIN_RESOLUTION -> R.string.degradation_resolution
        },
    ),
    stringResource(
        if (values.encoderImpl == EncoderImplementation.HARDWARE) {
            R.string.settings_encoder_hardware
        } else {
            R.string.settings_encoder_software
        },
    ),
    when (values.preferredCodec) {
        PreferredVideoCodec.VP8 -> stringResource(R.string.settings_codec_vp8)
        PreferredVideoCodec.H264 -> stringResource(R.string.settings_codec_h264) + " " + stringResource(
            if (values.h264HighProfile) R.string.settings_h264_profile_high else R.string.settings_h264_profile_baseline,
        )
    },
).joinToString(" · ")
