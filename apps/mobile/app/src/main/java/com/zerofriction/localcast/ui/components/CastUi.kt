package com.zerofriction.localcast.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zerofriction.localcast.R

/**
 * The shared UI vocabulary of the broadcast-console design
 * (designs/mobile-app.html — a faithful port of its CSS classes): grouped
 * inset [CastCard]s, silkscreen caps labels, icon list rows, LED status
 * lamps. One treatment per concept on every screen; colors come from the
 * theme (tints are alpha-composited over the card surface exactly like the
 * prototype's color-mix).
 */

/** The prototype's .card: inset surface, hairline border, 18dp radius. */
@Composable
fun CastCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Column { content() }
    }
}

/**
 * The prototype's .brand-mark — the console's 24dp tally chip (the cast
 * glyph in a rounded tinted square; the row-ico vocabulary at lockup
 * scale). [onAir] lights it amber: the console lit at the top.
 */
@Composable
fun BrandMark(onAir: Boolean, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(24.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(
                if (onAir) {
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                } else {
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_cast),
            contentDescription = null,
            tint = if (onAir) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(14.dp),
        )
    }
}

/** Annunciator coloring: the machine's state word rides the LED ladder. */
enum class AnnuncTone { QUIET, LIVE, FAULT }

/**
 * The prototype's .annun — the bar's state lamp word (machine register):
 * a 9dp LED + mono caps 12sp, one size with the nameplate, differentiated
 * by ink. [pulse] is the ON AIR tally blink (the screen's only blink).
 */
@Composable
fun Annunciator(
    word: String,
    tone: AnnuncTone,
    pulse: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val ink = when (tone) {
        AnnuncTone.QUIET -> MaterialTheme.colorScheme.onSurfaceVariant
        AnnuncTone.LIVE -> MaterialTheme.colorScheme.primary
        AnnuncTone.FAULT -> MaterialTheme.colorScheme.error
    }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = modifier) {
        LedDot(tone = when (tone) {
            AnnuncTone.QUIET -> LedTone.DIM
            AnnuncTone.LIVE -> LedTone.ON
            AnnuncTone.FAULT -> LedTone.HOT
        }, pulse = pulse)
        Spacer(Modifier.width(7.dp))
        Text(
            text = word.uppercase(),
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            letterSpacing = 1.4.sp,
            fontFamily = FontFamily.Monospace,
            color = ink,
        )
    }
}

/**
 * The prototype's .zone-head — a named zone inside a card: hairline above,
 * caps label left, [trailing] (the STATUS header's readout) right.
 */
@Composable
fun ZoneHeader(
    label: String,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
) {
    val dividerColor = MaterialTheme.colorScheme.outline
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
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
            .padding(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 2.dp),
    ) {
        Text(
            text = label.uppercase(),
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            letterSpacing = 1.1.sp,
            color = lerp(MaterialTheme.colorScheme.onSurfaceVariant, MaterialTheme.colorScheme.onSurface, 0.2f),
            modifier = Modifier.weight(1f),
        )
        if (trailing != null) trailing()
    }
}

/**
 * The prototype's .card-foot — a muted note under the card's last row,
 * above nothing (the card's floor). Takes the resolved string so both
 * resource and composed callers share one treatment.
 */
@Composable
fun CardFoot(text: String, modifier: Modifier = Modifier) {
    val dividerColor = MaterialTheme.colorScheme.outline
    Text(
        text = text,
        fontSize = 13.sp,
        lineHeight = 19.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier
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

/**
 * The prototype's .seg — a connected switch bank for short option sets:
 * the settings' selectors and the quality console's PRESET | CUSTOM tabs.
 * Padding-free; the caller places it (the tabs sit in their own padded
 * strip, settings rows carry their own insets).
 */
@Composable
fun CastSegRow(
    options: List<Pair<String, Boolean>>,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val outline = MaterialTheme.colorScheme.outline
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = Color.Transparent,
        border = BorderStroke(1.dp, outline),
        modifier = modifier.fillMaxWidth(),
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

/** The prototype's .silkscreen: caps panel label above a card (its .card
 *  carries margin-top 8px — ported as this label's bottom padding). */
@Composable
fun SilkscreenLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text.uppercase(),
        fontSize = 11.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 1.1.sp,
        color = lerp(MaterialTheme.colorScheme.onSurfaceVariant, MaterialTheme.colorScheme.onSurface, 0.2f),
        modifier = modifier.padding(start = 4.dp, top = 20.dp, bottom = 8.dp),
    )
}

/** The prototype's .row-ico: a stroke glyph in a rounded tinted square. */
@Composable
fun CastIconContainer(iconRes: Int, tint: Color, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(38.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(19.dp),
        )
    }
}

/**
 * The prototype's .icon-row: glyph + title/sub + trailing controls, one
 * treatment everywhere. [topDivider] draws the hairline between stacked
 * rows (the CSS sibling selector's port). [fact] is an extra plain-words
 * line about the live cast (e.g. "this app's audio can't be captured").
 */
@Composable
fun IconListRow(
    iconRes: Int,
    title: String,
    modifier: Modifier = Modifier,
    sub: String? = null,
    fact: String? = null,
    err: Boolean = false,
    topDivider: Boolean = false,
    right: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
) {
    val dividerColor = MaterialTheme.colorScheme.outline
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .topHairline(topDivider, dividerColor)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 13.dp),
    ) {
        CastIconContainer(
            iconRes = iconRes,
            tint = if (err) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                lineHeight = 20.sp,
                color = if (err) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            )
            if (sub != null) {
                Text(
                    text = sub,
                    fontSize = 13.sp,
                    lineHeight = 19.sp,
                    color = if (err) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            if (fact != null) {
                Text(
                    text = fact,
                    fontSize = 13.sp,
                    lineHeight = 19.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
        if (right != null) {
            Spacer(Modifier.width(10.dp))
            right()
        }
    }
}

/** Console annunciator states: muted, amber (attention/live), hot (error). */
enum class LedTone { DIM, ON, HOT }

/**
 * The prototype's .led — a 9dp lamp. [pulse] is the on-air tally blink
 * (opacity only; subtle by design, no spatial motion).
 */
@Composable
fun LedDot(tone: LedTone, pulse: Boolean = false, modifier: Modifier = Modifier) {
    val color = when (tone) {
        LedTone.DIM -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)
        LedTone.ON -> MaterialTheme.colorScheme.primary
        LedTone.HOT -> MaterialTheme.colorScheme.error
    }
    var alpha = 1f
    if (pulse) {
        val transition = rememberInfiniteTransition(label = "tally")
        val a by transition.animateFloat(
            initialValue = 1f,
            targetValue = 0.35f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 2400, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "tally-alpha",
        )
        alpha = a
    }
    Box(contentAlignment = Alignment.Center, modifier = modifier.size(21.dp)) {
        // The prototype's box-shadow glow: a soft halo behind the lamp.
        Box(
            Modifier
                .size(21.dp)
                .clip(CircleShape)
                .background(color.copy(alpha = 0.25f * alpha)),
        )
        Box(
            Modifier
                .size(9.dp)
                .clip(CircleShape)
                .background(color.copy(alpha = alpha)),
        )
    }
}

/**
 * The prototype's .led-row: lamp + plain words (the thermal/adaptive
 * read-outs — read-only diagnostics, never a technical dump).
 */
@Composable
fun LedListRow(
    text: String,
    tone: LedTone,
    modifier: Modifier = Modifier,
    hot: Boolean = false,
    topDivider: Boolean = false,
    right: (@Composable () -> Unit)? = null,
) {
    val dividerColor = MaterialTheme.colorScheme.outline
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .topHairline(topDivider, dividerColor)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        LedDot(tone = tone)
        Spacer(Modifier.width(12.dp))
        Text(
            text = text,
            fontSize = 14.sp,
            lineHeight = 20.sp,
            color = if (hot) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        if (right != null) right()
    }
}

/** The prototype's .text-btn — quiet (muted) by default, accent when loud. */
@Composable
fun CastTextButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    quiet: Boolean = false,
) {
    TextButton(
        onClick = onClick,
        modifier = modifier.height(40.dp),
    ) {
        Text(
            text = text,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            color = if (quiet) {
                lerp(MaterialTheme.colorScheme.onSurfaceVariant, MaterialTheme.colorScheme.onSurface, 0.6f)
            } else {
                MaterialTheme.colorScheme.primary
            },
        )
    }
}

/**
 * The prototype's .chip-row: pill selectors (amber when selected), one
 * vocabulary everywhere — the settings' advanced knobs and the Home mic
 * picker. [options] pairs each label with its selected state; [onSelect]
 * receives the chosen index.
 */
@Composable
fun CastChipRow(
    options: List<Pair<String, Boolean>>,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier
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

/** The prototype's row-divider hairline (1dp line at the row's top edge). */
private fun Modifier.topHairline(enabled: Boolean, color: Color): Modifier =
    if (!enabled) {
        this
    } else {
        drawBehind {
            val y = 0.5.dp.toPx()
            drawLine(
                color = color,
                start = Offset(0f, y),
                end = Offset(size.width, y),
                strokeWidth = 1.dp.toPx(),
            )
        }
    }

/**
 * The prototype's .switch — its exact colors, not stock M3: an unchecked
 * thumb is near-white (on 92% over the surface, never the muted variant
 * that disappears into the track), an unchecked track barely-there
 * (on 14%), and checked = amber track with the dark thumb. No track border.
 */
@Composable
fun CastSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        modifier = modifier,
        colors = SwitchDefaults.colors(
            checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
            checkedTrackColor = MaterialTheme.colorScheme.primary,
            checkedBorderColor = Color.Transparent,
            uncheckedThumbColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.92f),
            uncheckedTrackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.14f),
            uncheckedBorderColor = Color.Transparent,
        ),
    )
}
