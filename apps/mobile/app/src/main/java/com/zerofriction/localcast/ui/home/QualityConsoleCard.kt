package com.zerofriction.localcast.ui.home

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zerofriction.localcast.R
import com.zerofriction.localcast.adaptive.QualityLevel
import com.zerofriction.localcast.config.CastSettings
import com.zerofriction.localcast.config.CustomProfile
import com.zerofriction.localcast.config.CustomProfileRules
import com.zerofriction.localcast.config.DegradationStrategy
import com.zerofriction.localcast.config.ProfileSelection
import com.zerofriction.localcast.config.ProfileValues
import com.zerofriction.localcast.config.QualityProfile
import com.zerofriction.localcast.config.toValues
import com.zerofriction.localcast.ui.components.CardFoot
import com.zerofriction.localcast.ui.components.CastCard
import com.zerofriction.localcast.ui.components.CastTextButton
import com.zerofriction.localcast.ui.components.ZoneHeader
import kotlinx.coroutines.delay

/**
 * The quality console (ADR-024): ONE card on Home in both states, a list of
 * profiles — the four built-in ones, then the user's own, then *Add
 * profile*. The state only changes what a pick does:
 *  - **idle**: it selects the profile for the next cast ([onSelect]);
 *  - **casting**: it steers the RUNNING cast to the profile's picture
 *    ([onApplyLive] → the service applies resolution, frame rate and bitrate
 *    live and resets auto quality onto them; the stored selection is
 *    untouched — "until the cast ends").
 *
 * Every row says in words what the profile gives up when the network is
 * short, so a combination that will hurt is visible before the cast starts.
 * Only the user's own profiles have a menu (edit, duplicate, delete); the
 * built-in ones are fixed. STATUS — casting only — is the card's last zone:
 * the readout header (amber timecode + shorthand quality) over the lamp
 * rows ([statusRows], supplied by Home).
 */
@Composable
fun QualityConsoleCard(
    casting: Boolean,
    settings: CastSettings,
    liveQuality: QualityLevel?,
    startedAtMillis: Long,
    onSelect: (ProfileSelection) -> Unit,
    onApplyLive: (QualityLevel) -> Unit,
    onAddProfile: () -> Unit,
    onEditProfile: (Int) -> Unit,
    onDuplicateProfile: (Int) -> Unit,
    onDeleteProfile: (Int) -> Unit,
    modifier: Modifier = Modifier,
    statusRows: @Composable () -> Unit = {},
) {
    val current: QualityLevel = if (casting) liveQuality ?: settings.active.toLevel() else settings.active.toLevel()
    val lit = litSelection(casting, settings, current)
    var deleting by remember { mutableStateOf<CustomProfile?>(null) }
    val pick: (ProfileSelection, ProfileValues) -> Unit = { selection, values ->
        if (casting) onApplyLive(values.toLevel()) else onSelect(selection)
    }

    CastCard(modifier = modifier) {
        ZoneHeader(label = stringResource(R.string.profiles_zone_built_in))
        for (preset in QualityProfile.entries) {
            val selection = ProfileSelection.BuiltIn(preset)
            ProfileRow(
                name = profileName(preset),
                tag = pressureTag(preset.degradation),
                intent = profileIntent(preset),
                spec = profileSpec(preset.toValues()),
                selected = lit == selection,
                topDivider = preset != QualityProfile.entries.first(),
                onClick = { pick(selection, preset.toValues()) },
            )
        }

        if (settings.customProfiles.isNotEmpty()) {
            ZoneHeader(label = stringResource(R.string.profiles_zone_yours))
        }
        settings.customProfiles.forEachIndexed { index, profile ->
            val selection = ProfileSelection.Custom(profile.id)
            ProfileRow(
                name = profile.name,
                tag = pressureTag(profile.values.degradationStrategy),
                intent = null,
                spec = profileSpec(profile.values),
                selected = lit == selection,
                topDivider = index > 0,
                onClick = { pick(selection, profile.values) },
                menu = ProfileMenu(
                    onEdit = { onEditProfile(profile.id) },
                    onDuplicate = { onDuplicateProfile(profile.id) }.takeIf { settings.canAddProfile },
                    onDelete = { deleting = profile },
                ),
            )
        }

        AddProfileRow(enabled = settings.canAddProfile, onClick = onAddProfile)
        CardFoot(
            text = when {
                !settings.canAddProfile ->
                    stringResource(R.string.profiles_foot_full, CustomProfileRules.MAX_PROFILES)

                casting -> stringResource(R.string.profiles_foot_live)
                else -> stringResource(R.string.profiles_foot_next_cast)
            },
        )

        // -- STATUS (casting only): the readout header over the lamps. --
        if (casting) {
            ZoneHeader(
                label = stringResource(R.string.status_zone),
                trailing = {
                    Row {
                        LiveTimecode(startedAtMillis)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = formatLiveReadout(current),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            fontFamily = FontFamily.Monospace,
                            letterSpacing = 0.2.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
            )
            statusRows()
        }
    }

    deleting?.let { profile ->
        DeleteProfileDialog(
            profile = profile,
            selected = settings.selection == ProfileSelection.Custom(profile.id),
            onConfirm = {
                deleting = null
                onDeleteProfile(profile.id)
            },
            onDismiss = { deleting = null },
        )
    }
}

/**
 * The row to light. Idle: the stored selection. Casting: the profile the
 * running picture matches — the stored selection when it does (two profiles
 * may share a picture), else the first one that does, else none (auto
 * quality or a live pick moved the cast somewhere no profile names).
 */
internal fun litSelection(casting: Boolean, settings: CastSettings, current: QualityLevel): ProfileSelection? {
    if (!casting || settings.active.toLevel() == current) return settings.selection
    QualityProfile.entries.firstOrNull { it.toValues().toLevel() == current }
        ?.let { return ProfileSelection.BuiltIn(it) }
    return settings.customProfiles.firstOrNull { it.values.toLevel() == current }
        ?.let { ProfileSelection.Custom(it.id) }
}

/**
 * The STATUS header's timecode — amber, tabular monospace so the digits
 * don't jitter. Anchored at the service's streaming transition
 * ([startedAtMillis]); a 0 (unknown) falls back to the first composition,
 * which stays honest on the screen the timer lives on.
 */
@Composable
internal fun LiveTimecode(startedAtMillis: Long) {
    var fallbackStart by rememberSaveable { mutableLongStateOf(0L) }
    val start = if (startedAtMillis > 0) {
        startedAtMillis
    } else {
        fallbackStart.also { if (it == 0L) fallbackStart = System.currentTimeMillis() }
    }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(start) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1_000)
        }
    }
    Text(
        text = formatCastDuration(((now - start).coerceAtLeast(0L)) / 1_000),
        fontSize = 12.sp,
        fontWeight = FontWeight.Medium,
        fontFamily = FontFamily.Monospace,
        letterSpacing = 0.2.sp,
        color = MaterialTheme.colorScheme.primary,
    )
}

/** Streamer shorthand (the prototype's liveReadout): "720p30 · 3–6M". */
internal fun formatLiveReadout(level: QualityLevel): String =
    pNotation(level.longEdgePx) + level.fps + " · " +
        mbps(level.bitrateMinBps) + "–" + mbps(level.bitrateMaxBps) + "M"

/**
 * p-notation for a capture long edge — 1080p/720p/540p for the shipped
 * rungs (assuming the 16:9 capture aspect), raw pixels for anything else.
 */
internal fun pNotation(longEdgePx: Int): String = when (longEdgePx) {
    1920 -> "1080p"
    1280 -> "720p"
    960 -> "540p"
    else -> "${longEdgePx}px"
}

private fun mbps(bps: Int): String {
    val m = bps / BPS_PER_MBPS
    val half = (bps % BPS_PER_MBPS).let { if (it == 0) "" else "." + (it * 10 / BPS_PER_MBPS) }
    return "$m$half"
}

private fun ProfileValues.toLevel(): QualityLevel =
    QualityLevel(longEdgePx, fps, bitrateMinBps, bitrateMaxBps)

private const val BPS_PER_MBPS = 1_000_000

// ---- the profile row vocabulary (the prototype's .pick-row) ----

/** What a row's menu offers; a null action is not offered. */
private class ProfileMenu(
    val onEdit: () -> Unit,
    val onDuplicate: (() -> Unit)?,
    val onDelete: () -> Unit,
)

/** A profile row: radio + name with its pressure tag + the built-in's intent + the real numbers. */
@Composable
private fun ProfileRow(
    name: String,
    tag: String,
    intent: String?,
    spec: String,
    selected: Boolean,
    topDivider: Boolean,
    onClick: () -> Unit,
    menu: ProfileMenu? = null,
) {
    val dividerColor = MaterialTheme.colorScheme.outline
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .then(if (topDivider) Modifier.topHairline(dividerColor) else Modifier)
            .background(if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.07f) else Color.Transparent),
    ) {
        // The pick is the row minus its menu: one radio button for TalkBack,
        // with the menu a separate button beside it.
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .weight(1f)
                .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
                .padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = if (menu == null) 16.dp else 8.dp),
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
                        drawCircle(color = ringColor, style = Stroke(width = 2.dp.toPx()))
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
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = name,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                        lineHeight = 20.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    Spacer(Modifier.width(8.dp))
                    PressureTag(text = tag, selected = selected)
                }
                if (intent != null) {
                    Text(
                        text = intent,
                        fontSize = 13.sp,
                        lineHeight = 19.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
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
        if (menu != null) {
            ProfileMenuButton(name = name, menu = menu, modifier = Modifier.padding(end = 12.dp))
        }
    }
}

/** What the profile gives up first when the network is short, as a quiet tag beside its name. */
@Composable
private fun PressureTag(text: String, selected: Boolean) {
    val ink = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    Text(
        text = text.uppercase(),
        fontSize = 10.sp,
        fontWeight = FontWeight.Bold,
        fontFamily = FontFamily.Monospace,
        letterSpacing = 0.8.sp,
        lineHeight = 14.sp,
        color = ink,
        maxLines = 1,
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(ink.copy(alpha = 0.14f))
            .padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

/** The ⋯ button of one of the user's profiles and its menu. */
@Composable
private fun ProfileMenuButton(name: String, menu: ProfileMenu, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    Box(modifier = modifier) {
        Surface(
            onClick = { open = true },
            shape = RoundedCornerShape(12.dp),
            color = Color.Transparent,
            contentColor = MaterialTheme.colorScheme.onSurface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            modifier = Modifier.size(40.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    painter = painterResource(R.drawable.ic_more),
                    contentDescription = stringResource(R.string.profile_more_actions, name),
                    modifier = Modifier.size(20.dp),
                )
            }
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.profile_action_edit)) },
                onClick = {
                    open = false
                    menu.onEdit()
                },
            )
            menu.onDuplicate?.let { duplicate ->
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.profile_action_duplicate)) },
                    onClick = {
                        open = false
                        duplicate()
                    },
                )
            }
            DropdownMenuItem(
                text = {
                    Text(stringResource(R.string.profile_action_delete), color = MaterialTheme.colorScheme.error)
                },
                onClick = {
                    open = false
                    menu.onDelete()
                },
            )
        }
    }
}

/** The list's last row: add a profile of your own. */
@Composable
private fun AddProfileRow(enabled: Boolean, onClick: () -> Unit) {
    val dividerColor = MaterialTheme.colorScheme.outline
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .topHairline(dividerColor)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .alpha(if (enabled) 1f else 0.45f)
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Text(
            text = "+",
            fontSize = 20.sp,
            lineHeight = 20.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.width(20.dp),
        )
        Spacer(Modifier.width(14.dp))
        Text(
            text = stringResource(R.string.profile_add),
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

/** Deleting asks first; a selected profile says where the next cast lands. */
@Composable
internal fun DeleteProfileDialog(
    profile: CustomProfile,
    selected: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.profile_delete_title, profile.name)) },
        text = {
            Text(
                stringResource(
                    if (selected) R.string.profile_delete_selected else R.string.profile_delete_other,
                ),
            )
        },
        confirmButton = {
            CastTextButton(text = stringResource(R.string.profile_action_delete), onClick = onConfirm)
        },
        dismissButton = {
            CastTextButton(text = stringResource(R.string.cancel), onClick = onDismiss, quiet = true)
        },
    )
}

@Composable
private fun pressureTag(strategy: DegradationStrategy): String = stringResource(
    when (strategy) {
        DegradationStrategy.BALANCED -> R.string.profile_tag_balanced
        DegradationStrategy.MAINTAIN_FRAMERATE -> R.string.profile_tag_framerate
        DegradationStrategy.MAINTAIN_RESOLUTION -> R.string.profile_tag_resolution
    },
)

@Composable
internal fun profileName(preset: QualityProfile): String = when (preset) {
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

/** A profile's real numbers as a tabular spec (the prototype's .pick-spec). */
internal fun profileSpec(values: ProfileValues): String =
    "${values.longEdgePx} px · ${values.fps} fps · " +
        "${mbps(values.bitrateMinBps)}–${mbps(values.bitrateMaxBps)} Mbps"

/** The row-divider hairline (1dp line at the row's top edge). */
private fun Modifier.topHairline(color: Color): Modifier =
    drawBehind {
        val y = 0.5.dp.toPx()
        drawLine(
            color = color,
            start = Offset(0f, y),
            end = Offset(size.width, y),
            strokeWidth = 1.dp.toPx(),
        )
    }
