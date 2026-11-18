package io.github.silogos.caster.ui.home

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.silogos.caster.R
import io.github.silogos.caster.link.Link
import io.github.silogos.caster.ui.components.CastCard
import io.github.silogos.caster.ui.theme.CastBad
import io.github.silogos.caster.ui.theme.CastGood
import kotlinx.coroutines.delay

private const val COUNTDOWN_TICK_MS = 1_000L

/**
 * Home's top card (ADR-037 §7, "Mobile flow v8"): the computer, the link and
 * its status in one line, a hint when something is needed, ADR-036's
 * countdown on a drop, and the pairing's actions in the footer — Terminate
 * set apart, in red. It draws [ui] and decides nothing.
 */
@Composable
internal fun ConnectionCard(
    ui: LinkUi,
    onDisconnect: () -> Unit,
    onTerminate: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tone = when (ui.status) {
        LinkStatus.Connected -> CastGood
        is LinkStatus.Reconnecting -> MaterialTheme.colorScheme.primary
        LinkStatus.NotConnected -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val statusWord = stringResource(
        when (ui.status) {
            LinkStatus.Connected -> R.string.link_connected
            is LinkStatus.Reconnecting -> R.string.link_reconnecting
            LinkStatus.NotConnected -> R.string.link_not_connected
        },
    )
    val linkWord = stringResource(if (ui.link == Link.Cable) R.string.link_cable else R.string.link_wireless)
    val glyphInk = if (ui.status == LinkStatus.NotConnected) MaterialTheme.colorScheme.onSurface else tone
    CastCard(modifier.padding(top = 18.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier.padding(16.dp),
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(38.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(glyphInk.copy(alpha = 0.10f)),
            ) {
                Icon(
                    painter = painterResource(if (ui.link == Link.Cable) R.drawable.ic_usb else R.drawable.ic_wifi),
                    contentDescription = null,
                    tint = glyphInk,
                    modifier = Modifier.size(20.dp),
                )
            }
            Column(Modifier.weight(1f)) {
                Text(
                    text = ui.computerName,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.SemiBold,
                    lineHeight = 26.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(tone))
                    Spacer(Modifier.size(8.dp))
                    Text(
                        text = stringResource(R.string.link_status_line, linkWord, statusWord),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        color = if (ui.status == LinkStatus.NotConnected) MaterialTheme.colorScheme.onSurface else tone,
                    )
                }
                val hint = ui.hint?.takeIf { it != LinkHint.UnplugToDisconnect }
                if (hint != null) {
                    Text(
                        text = stringResource(hintRes(hint)),
                        fontSize = 13.sp,
                        lineHeight = 19.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }
        val reconnecting = ui.status as? LinkStatus.Reconnecting
        if (reconnecting != null) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outline)
            Countdown(reconnecting.endsAtMillis)
        } else if (ui.footer.isNotEmpty()) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outline)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp),
            ) {
                if (LinkAction.Disconnect in ui.footer) {
                    FooterButton(stringResource(R.string.disconnect), MaterialTheme.colorScheme.onSurface, onDisconnect)
                } else if (ui.hint == LinkHint.UnplugToDisconnect) {
                    Text(
                        text = stringResource(R.string.link_hint_unplug),
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 12.dp),
                    )
                }
                Spacer(Modifier.weight(1f))
                if (LinkAction.Terminate in ui.footer) {
                    FooterButton(stringResource(R.string.home_terminate), CastBad, onTerminate)
                }
            }
        }
    }
}

@Composable
private fun FooterButton(text: String, color: Color, onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.heightIn(min = 40.dp)) {
        Text(text = text, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = color)
    }
}

@Composable
private fun Countdown(endsAtMillis: Long) {
    val remainingMs by produceState(endsAtMillis - System.currentTimeMillis(), endsAtMillis) {
        while (true) {
            value = endsAtMillis - System.currentTimeMillis()
            delay(COUNTDOWN_TICK_MS)
        }
    }
    Row(
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Text(
            text = stringResource(R.string.cast_waiting_stops_in).uppercase(),
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            letterSpacing = 1.1.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = formatCastDuration(maxOf(0L, remainingMs) / 1000).trimStart('0', ':'),
            fontSize = 15.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

private fun hintRes(hint: LinkHint): Int = when (hint) {
    LinkHint.PlugIn -> R.string.link_hint_plug_in
    LinkHint.UnplugToDisconnect -> R.string.link_hint_unplug
    LinkHint.PlugBackIn -> R.string.link_hint_plug_back
    LinkHint.SameWifi -> R.string.link_hint_same_wifi
    LinkHint.WaitingForWifi -> R.string.link_hint_waiting_wifi
    LinkHint.OtherComputer -> R.string.cable_other_computer
    LinkHint.Busy -> R.string.cable_busy
    LinkHint.UpdateComputer -> R.string.cable_update_computer
    LinkHint.CableFailed -> R.string.cable_failed
}

/**
 * The big button under the card: always there, always the next step
 * (ADR-037 §7), so Home keeps its shape in every state.
 */
@Composable
internal fun PrimaryButton(action: PrimaryAction, onShare: () -> Unit, onConnect: () -> Unit, onStop: () -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    val modifier = Modifier.fillMaxWidth().padding(top = 16.dp).heightIn(min = 54.dp)
    when (action) {
        PrimaryAction.Stop -> OutlinedButton(
            onClick = onStop,
            shape = shape,
            modifier = modifier,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.30f)),
            colors = ButtonDefaults.outlinedButtonColors(containerColor = Color.Transparent, contentColor = MaterialTheme.colorScheme.onSurface),
        ) {
            Text(text = stringResource(R.string.stop_share_screen), fontSize = 15.sp, fontWeight = FontWeight.Medium)
        }
        PrimaryAction.Starting -> Button(onClick = {}, enabled = false, shape = shape, modifier = modifier) {
            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            Spacer(Modifier.size(10.dp))
            Text(text = stringResource(R.string.starting_cast), fontSize = 15.sp, fontWeight = FontWeight.Bold)
        }
        else -> {
            val (label, enabled, onClick) = when (action) {
                PrimaryAction.Share -> Triple(R.string.share_screen_action, true, onShare)
                PrimaryAction.ShareDisabled -> Triple(R.string.share_screen_action, false, {})
                PrimaryAction.Connect -> Triple(R.string.home_connect, true, onConnect)
                else -> Triple(R.string.home_connecting, false, {})
            }
            Button(
                onClick = onClick,
                enabled = enabled,
                shape = shape,
                modifier = modifier,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    disabledContainerColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f),
                    disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
            ) {
                Text(text = stringResource(label), fontSize = 15.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}
