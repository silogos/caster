package com.zerofriction.localcast.ui.home

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zerofriction.localcast.R
import com.zerofriction.localcast.adaptive.QualityLevel
import com.zerofriction.localcast.config.CastSettingChoices
import com.zerofriction.localcast.config.CastSettings
import com.zerofriction.localcast.config.QualityProfile
import com.zerofriction.localcast.config.matchingProfile
import com.zerofriction.localcast.ui.components.CardFoot
import com.zerofriction.localcast.ui.components.CastCard
import com.zerofriction.localcast.ui.components.CastChipRow
import com.zerofriction.localcast.ui.components.CastSegRow
import com.zerofriction.localcast.ui.components.ZoneHeader
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/**
 * The quality console (designs/mobile-app.html — the prototype's
 * qualityCard(casting)): ONE tabbed card on Home in both states, identical
 * layout. The state only changes what the tabs write —
 *  - **idle**: the next-cast defaults (a preset pick writes the settings
 *    store and its Advanced defaults; the Custom dials write the store),
 *  - **casting**: the RUNNING cast ([onApplyLive] → the service applies the
 *    values live and resets auto quality onto them; the persisted settings
 *    are untouched — "until the cast ends").
 *
 * PRESET rides a named thermal bundle; CUSTOM dials the values (any dial
 * leaves the preset — no row lit when nothing matches). STATUS — casting
 * only — is the card's third zone: the readout header (amber timecode +
 * shorthand quality) over the lamp rows ([statusRows], supplied by Home).
 */
@Composable
fun QualityConsoleCard(
    casting: Boolean,
    settings: CastSettings,
    liveQuality: QualityLevel?,
    startedAtMillis: Long,
    onSelectProfile: (QualityProfile) -> Unit,
    onSelectLongEdge: (Int) -> Unit,
    onSelectFps: (Int) -> Unit,
    onSetManualBitrateMax: (Int) -> Unit,
    onApplyLive: (QualityLevel) -> Unit,
    modifier: Modifier = Modifier,
    statusRows: @Composable () -> Unit = {},
) {
    // The tab the user chose; null = derive from reality (custom values →
    // Custom). The derivation runs only until the first explicit choice.
    var chosenTab by rememberSaveable { mutableStateOf<String?>(null) }
    val current: QualityLevel = if (casting) liveQuality ?: settings.toLevel() else settings.toLevel()
    val matched = matchingProfile(current.longEdgePx, current.fps, current.bitrateMinBps, current.bitrateMaxBps)
    val tab = chosenTab ?: if (matched != null) TAB_PRESET else TAB_CUSTOM

    CastCard(modifier = modifier) {
        // -- Tabs (Preset | Custom): navigation, not a mode pair — the tab
        //    decides HOW you steer: by bundle or by dial. --
        CastSegRow(
            options = listOf(
                stringResource(R.string.quality_tab_preset) to (tab == TAB_PRESET),
                stringResource(R.string.quality_tab_custom) to (tab == TAB_CUSTOM),
            ),
            onSelect = { index -> chosenTab = if (index == 0) TAB_PRESET else TAB_CUSTOM },
            modifier = Modifier.padding(start = 16.dp, top = 14.dp, end = 16.dp),
        )

        if (tab == TAB_PRESET) {
            // The thermal intents matter most while casting — same rows both
            // states; only the target differs (running cast vs. next cast).
            for (preset in QualityProfile.entries) {
                ProfileRow(
                    name = profileName(preset),
                    intent = profileIntent(preset),
                    spec = presetSpec(preset),
                    selected = matched == preset,
                    topDivider = preset != QualityProfile.entries.first(),
                    onClick = {
                        if (casting) onApplyLive(preset.toLevel()) else onSelectProfile(preset)
                    },
                )
            }
            if (!casting && matched == null) {
                ProfileRow(
                    name = stringResource(R.string.profile_custom),
                    intent = stringResource(R.string.profile_intent_custom),
                    spec = null,
                    selected = true,
                    topDivider = true,
                    onClick = null,
                )
            }
            if (!casting) {
                CardFoot(text = stringResource(R.string.quality_foot_next_cast))
            }
        } else {
            Column(Modifier.padding(start = 16.dp, top = 2.dp, end = 16.dp, bottom = 14.dp)) {
                Text(
                    text = stringResource(
                        if (casting) R.string.quality_custom_hint_live else R.string.quality_custom_hint_next,
                    ),
                    fontSize = 13.sp,
                    lineHeight = 19.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )

                SubLabelText(stringResource(R.string.settings_resolution))
                CastChipRow(
                    options = RES_RUNGS_DOWN.map {
                        stringResource(R.string.settings_px, it) to (it == current.longEdgePx)
                    },
                    onSelect = { index ->
                        val v = RES_RUNGS_DOWN[index]
                        if (casting) onApplyLive(current.copy(longEdgePx = v)) else onSelectLongEdge(v)
                    },
                )

                SubLabelText(stringResource(R.string.settings_fps))
                CastChipRow(
                    options = FPS_RUNGS_DOWN.map {
                        stringResource(R.string.settings_fps_value, it) to (it == current.fps)
                    },
                    onSelect = { index ->
                        val v = FPS_RUNGS_DOWN[index]
                        if (casting) onApplyLive(current.copy(fps = v)) else onSelectFps(v)
                    },
                )

                SubLabelText(stringResource(R.string.quality_bitrate_max_label, current.bitrateMaxBps / BPS_PER_MBPS))
                // The dial applies on release — one request per gesture, not
                // one per drag tick (idle writes the store, live hits the
                // service; both deserve deliberate commits).
                var dragMaxBps by remember(current) { mutableFloatStateOf(Float.NaN) }
                val shownMaxBps = if (dragMaxBps.isNaN()) current.bitrateMaxBps else dragMaxBps.roundToInt()
                Slider(
                    value = (shownMaxBps / BPS_PER_MBPS).toFloat(),
                    onValueChange = { mbps -> dragMaxBps = mbps * BPS_PER_MBPS },
                    onValueChangeFinished = {
                        val bps = dragMaxBps.roundToInt()
                        dragMaxBps = Float.NaN
                        if (casting) {
                            onApplyLive(
                                current.copy(
                                    bitrateMaxBps = bps,
                                    bitrateMinBps = (bps * CastSettingChoices.MANUAL_BITRATE_MIN_FRACTION).roundToInt(),
                                ),
                            )
                        } else {
                            onSetManualBitrateMax(bps)
                        }
                    },
                    valueRange = (CastSettingChoices.MANUAL_BITRATE_MIN_BPS / BPS_PER_MBPS).toFloat()..
                        (CastSettingChoices.MANUAL_BITRATE_MAX_BPS / BPS_PER_MBPS).toFloat(),
                    steps = ((CastSettingChoices.MANUAL_BITRATE_MAX_BPS - CastSettingChoices.MANUAL_BITRATE_MIN_BPS) / BPS_PER_MBPS) - 1,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }

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

private fun CastSettings.toLevel(): QualityLevel =
    QualityLevel(longEdgePx, fps, bitrateMinBps, bitrateMaxBps)

private fun QualityProfile.toLevel(): QualityLevel =
    QualityLevel(longEdgePx, fps, bitrateMinBps, bitrateMaxBps)

/** The custom tab's ladders — the shared choices, heaviest rung first. */
private val RES_RUNGS_DOWN = CastSettingChoices.LONG_EDGE_CHOICES.sortedDescending()
private val FPS_RUNGS_DOWN = CastSettingChoices.FPS_CHOICES.sortedDescending()

private const val BPS_PER_MBPS = 1_000_000
private const val TAB_PRESET = "preset"
private const val TAB_CUSTOM = "custom"

// ---- the preset row vocabulary (the prototype's .pick-row) ----

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
            .then(if (topDivider) Modifier.topHairline(dividerColor) else Modifier)
            .background(if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.07f) else Color.Transparent)
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

/** The prototype's .sub-label — a muted 13sp label above a control. */
@Composable
private fun SubLabelText(text: String) {
    Text(
        text = text,
        fontSize = 13.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 14.dp),
    )
}

@Composable
private fun profileName(preset: QualityProfile): String = when (preset) {
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
private fun presetSpec(preset: QualityProfile): String =
    "${preset.longEdgePx} px · ${preset.fps} fps · " +
        "${preset.bitrateMinBps / BPS_PER_MBPS}–${preset.bitrateMaxBps / BPS_PER_MBPS} Mbps"

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
