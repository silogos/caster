package com.zerofriction.localcast.ui.profiles

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Surface
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.zerofriction.localcast.R
import com.zerofriction.localcast.config.AdvancedSettingChoices
import com.zerofriction.localcast.config.CastSettingChoices
import com.zerofriction.localcast.config.CustomProfile
import com.zerofriction.localcast.config.CustomProfileRules
import com.zerofriction.localcast.config.StrugglePolicy
import com.zerofriction.localcast.config.EncoderImplementation
import com.zerofriction.localcast.config.PreferredVideoCodec
import com.zerofriction.localcast.config.ProfileSelection
import com.zerofriction.localcast.config.ProfileValues
import com.zerofriction.localcast.ui.components.CastCard
import com.zerofriction.localcast.ui.components.CastChipRow
import com.zerofriction.localcast.ui.components.SilkscreenLabel
import com.zerofriction.localcast.ui.home.DeleteProfileDialog
import com.zerofriction.localcast.ui.home.struggleName
import com.zerofriction.localcast.ui.settings.SettingsViewModel
import com.zerofriction.localcast.ui.settings.settingsFactory
import kotlin.math.roundToInt

/**
 * Editing one of the user's profiles (ADR-024): its name and everything the
 * profile fixes for a cast. Every change is saved at once and applies on the
 * next cast; the note at the top says so. A profile is the only home of
 * these values — there is no separate settings page behind it.
 *
 * Back — the button and the system gesture alike — returns to Home. A
 * profile that no longer exists (deleted here) closes the screen.
 */
@Composable
fun ProfileEditScreen(
    profileId: Int,
    onBack: () -> Unit,
    onOpenProfile: (Int) -> Unit,
    viewModel: SettingsViewModel = viewModel(factory = settingsFactory()),
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val profile = settings.customProfile(profileId)

    BackHandler(onBack = onBack)
    LaunchedEffect(profile == null) {
        if (profile == null) onBack()
    }
    if (profile == null) return

    ProfileEditContent(
        profile = profile,
        selected = settings.selection == ProfileSelection.Custom(profile.id),
        canDuplicate = settings.canAddProfile,
        onRename = { name -> viewModel.renameProfile(profile.id, name) },
        onEdit = { edit -> viewModel.editProfile(profile.id, edit) },
        onDuplicate = { viewModel.duplicateProfile(profile.id)?.let(onOpenProfile) },
        onDelete = { viewModel.deleteProfile(profile.id) },
        onBack = onBack,
    )
}

@Composable
fun ProfileEditContent(
    profile: CustomProfile,
    selected: Boolean,
    canDuplicate: Boolean,
    onRename: (String) -> Unit,
    onEdit: ((ProfileValues) -> ProfileValues) -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit,
    onBack: () -> Unit = {},
) {
    val values = profile.values
    var confirmingDelete by remember { mutableStateOf(false) }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
        ) {
            // App bar: back chevron + title.
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(
                    onClick = onBack,
                    modifier = Modifier
                        .size(40.dp)
                        .padding(start = 0.dp),
                    shape = RoundedCornerShape(12.dp),
                    contentPadding = PaddingValues(0.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = Color.Transparent,
                        contentColor = MaterialTheme.colorScheme.onSurface,
                    ),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_chevron_left),
                        contentDescription = stringResource(R.string.back),
                        modifier = Modifier.size(20.dp),
                    )
                }
                Text(
                    text = stringResource(R.string.profile_edit_title),
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(start = 10.dp),
                )
            }
            Text(
                text = stringResource(R.string.profile_edit_note),
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp, bottom = 8.dp),
            )

            // A name is saved as it is typed, as long as it is one a profile may have.
            var nameDraft by rememberSaveable(profile.id) { mutableStateOf(profile.name) }
            ProfileNameField(
                value = nameDraft,
                onValueChange = { typed ->
                    nameDraft = typed
                    CustomProfileRules.cleanName(typed)?.let(onRename)
                },
            )

            // ---- Picture ----
            SilkscreenLabel(stringResource(R.string.profile_card_picture))
            CastCard {
                Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 14.dp)) {
                    SubLabel(R.string.settings_resolution)
                    val edges = CastSettingChoices.LONG_EDGE_CHOICES.sortedDescending()
                    CastChipRow(
                        options = edges.map { stringResource(R.string.settings_px, it) to (it == values.longEdgePx) },
                        onSelect = { index -> onEdit { it.copy(longEdgePx = edges[index]) } },
                    )

                    SubLabel(R.string.settings_fps)
                    val rates = CastSettingChoices.FPS_CHOICES.sortedDescending()
                    CastChipRow(
                        options = rates.map { stringResource(R.string.settings_fps_value, it) to (it == values.fps) },
                        onSelect = { index -> onEdit { it.copy(fps = rates[index]) } },
                    )

                    BitrateRange(
                        minBps = values.bitrateMinBps,
                        maxBps = values.bitrateMaxBps,
                        onCommit = { least, most -> onEdit { it.withBitrateRange(least, most) } },
                    )
                }
            }

            // ---- Behaviour: what the cast gives up when it struggles (ADR-025) ----
            SilkscreenLabel(stringResource(R.string.profile_card_behaviour))
            CastCard {
                Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 14.dp)) {
                    SubLabel(R.string.struggle_title)
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                        for (policy in StrugglePolicy.entries) {
                            StruggleOption(
                                policy = policy,
                                selected = policy == values.struggle,
                                onClick = { onEdit { it.copy(struggle = policy) } },
                            )
                        }
                    }
                    HintText(R.string.struggle_hint)
                }
            }

            // ---- Encoder ----
            SilkscreenLabel(stringResource(R.string.profile_card_encoder))
            CastCard {
                Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 14.dp)) {
                    SubLabel(R.string.settings_encoder)
                    CastChipRow(
                        options = listOf(
                            stringResource(R.string.settings_encoder_hardware) to
                                (values.encoderImpl == EncoderImplementation.HARDWARE),
                            stringResource(R.string.settings_encoder_software) to
                                (values.encoderImpl == EncoderImplementation.SOFTWARE),
                        ),
                        onSelect = { index ->
                            onEdit {
                                it.copy(
                                    encoderImpl = if (index == 0) {
                                        EncoderImplementation.HARDWARE
                                    } else {
                                        EncoderImplementation.SOFTWARE
                                    },
                                )
                            }
                        },
                    )
                    HintText(
                        if (values.encoderImpl == EncoderImplementation.HARDWARE) {
                            R.string.settings_encoder_hardware_hint
                        } else {
                            R.string.settings_encoder_software_hint
                        },
                    )

                    SubLabel(R.string.profile_video_codec)
                    CastChipRow(
                        options = listOf(
                            stringResource(R.string.settings_codec_h264) to (values.preferredCodec == PreferredVideoCodec.H264),
                            stringResource(R.string.settings_codec_vp8) to (values.preferredCodec == PreferredVideoCodec.VP8),
                        ),
                        onSelect = { index ->
                            onEdit {
                                it.copy(preferredCodec = if (index == 0) PreferredVideoCodec.H264 else PreferredVideoCodec.VP8)
                            }
                        },
                    )

                    SubLabel(R.string.settings_h264_profile)
                    CastChipRow(
                        options = listOf(
                            stringResource(R.string.settings_h264_profile_baseline) to !values.h264HighProfile,
                            stringResource(R.string.settings_h264_profile_high) to values.h264HighProfile,
                        ),
                        enabled = values.preferredCodec == PreferredVideoCodec.H264,
                        onSelect = { index -> onEdit { it.copy(h264HighProfile = index == 1) } },
                    )
                    if (values.preferredCodec != PreferredVideoCodec.H264) {
                        HintText(R.string.settings_h264_profile_codec_hint)
                    }

                    SubLabel(R.string.settings_encoder_fps_limit)
                    val limits = listOf(null) + AdvancedSettingChoices.ENCODER_FPS_LIMIT_CHOICES
                    CastChipRow(
                        options = limits.map { limit ->
                            (limit?.let { stringResource(R.string.settings_fps_value, it) }
                                ?: stringResource(R.string.settings_encoder_fps_off)) to
                                (limit == values.encoderFpsLimit)
                        },
                        onSelect = { index -> onEdit { it.copy(encoderFpsLimit = limits[index]) } },
                    )
                    HintText(R.string.settings_encoder_fps_hint)
                }
            }

            // ---- Audio: how the game audio travels (which tracks ride the cast is Home's) ----
            SilkscreenLabel(stringResource(R.string.profile_card_audio))
            CastCard {
                Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 14.dp)) {
                    SubLabel(R.string.settings_low_latency_game_audio)
                    CastChipRow(
                        options = listOf(
                            stringResource(R.string.low_latency_audio_on) to values.lowLatencyGameAudio,
                            stringResource(R.string.low_latency_audio_off) to !values.lowLatencyGameAudio,
                        ),
                        onSelect = { index -> onEdit { it.copy(lowLatencyGameAudio = index == 0) } },
                    )
                    HintText(R.string.settings_low_latency_game_audio_hint)
                }
            }

            Row(
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp),
            ) {
                TextButton(onClick = onDuplicate, enabled = canDuplicate) {
                    Text(
                        text = stringResource(R.string.profile_action_duplicate),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                    )
                }
                TextButton(onClick = { confirmingDelete = true }) {
                    Text(
                        text = stringResource(R.string.profile_delete_action),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            Spacer(Modifier.height(28.dp))
        }
    }

    if (confirmingDelete) {
        DeleteProfileDialog(
            profile = profile,
            selected = selected,
            onConfirm = {
                confirmingDelete = false
                onDelete()
            },
            onDismiss = { confirmingDelete = false },
        )
    }
}

/**
 * The bitrate range: one slider, two thumbs — the least and the most the
 * video may use. It applies on release: one save per gesture, not one per
 * drag tick.
 */
@Composable
private fun BitrateRange(minBps: Int, maxBps: Int, onCommit: (Int, Int) -> Unit) {
    val stored = (minBps / BPS_PER_MBPS).toFloat()..(maxBps / BPS_PER_MBPS).toFloat()
    var drag by remember(minBps, maxBps) { mutableStateOf<ClosedFloatingPointRange<Float>?>(null) }
    val shown = drag ?: stored
    Text(
        text = stringResource(R.string.profile_bitrate_label, shown.start.roundToInt(), shown.endInclusive.roundToInt()),
        fontSize = 13.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 12.dp),
    )
    val sliderMin = CastSettingChoices.BITRATE_SLIDER_MIN_BPS / BPS_PER_MBPS
    val sliderMax = CastSettingChoices.MANUAL_BITRATE_MAX_BPS / BPS_PER_MBPS
    RangeSlider(
        value = shown,
        onValueChange = { drag = it },
        onValueChangeFinished = finished@{
            val picked = drag ?: return@finished
            drag = null
            onCommit(picked.start.roundToInt() * BPS_PER_MBPS, picked.endInclusive.roundToInt() * BPS_PER_MBPS)
        },
        valueRange = sliderMin.toFloat()..sliderMax.toFloat(),
        steps = (sliderMax - sliderMin) / (CastSettingChoices.BITRATE_STEP_BPS / BPS_PER_MBPS) - 1,
        modifier = Modifier.padding(top = 8.dp),
    )
    Text(
        text = stringResource(R.string.profile_bitrate_hint, sliderMin, sliderMax),
        fontSize = 13.sp,
        lineHeight = 19.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** One choice of [StrugglePolicy]: its name over what it means, the whole row one radio button. */
@Composable
private fun StruggleOption(policy: StrugglePolicy, selected: Boolean, onClick: () -> Unit) {
    val meaning = when (policy) {
        StrugglePolicy.SMOOTH -> R.string.struggle_smooth_meaning
        StrugglePolicy.SHARP -> R.string.struggle_sharp_meaning
        StrugglePolicy.BALANCED -> R.string.struggle_balanced_meaning
        StrugglePolicy.FIXED -> R.string.struggle_fixed_meaning
    }
    Surface(
        selected = selected,
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.07f) else Color.Transparent,
        border = BorderStroke(1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline),
        modifier = Modifier
            .fillMaxWidth()
            .semantics { role = Role.RadioButton },
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Text(
                text = struggleName(policy),
                fontSize = 14.sp,
                lineHeight = 20.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = stringResource(meaning),
                fontSize = 12.5.sp,
                lineHeight = 18.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** A lighter label for the items inside a card. */
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

private const val BPS_PER_MBPS = 1_000_000
