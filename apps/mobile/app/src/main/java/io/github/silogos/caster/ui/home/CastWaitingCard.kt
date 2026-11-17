package io.github.silogos.caster.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.silogos.caster.R
import io.github.silogos.caster.service.CastLink
import io.github.silogos.caster.service.CastState
import io.github.silogos.caster.ui.components.CastCard
import io.github.silogos.caster.ui.components.IconListRow
import kotlinx.coroutines.delay

private const val COUNTDOWN_TICK_MS = 1_000L

/**
 * The cast waits for its link (ADR-036; designs/mobile-app.figcraft, page
 * "Mobile design v5 · Cast survives a drop", D1/D2): why, that nothing needs
 * sharing again, and how long it waits. Stop stays the transport button below.
 */
@Composable
internal fun CastWaitingCard(state: CastState.Interrupted, modifier: Modifier = Modifier) {
    val remainingMs by produceState(state.endsAtMillis - System.currentTimeMillis(), state.endsAtMillis) {
        while (true) {
            value = state.endsAtMillis - System.currentTimeMillis()
            delay(COUNTDOWN_TICK_MS)
        }
    }
    val cable = state.link == CastLink.CABLE
    CastCard(modifier) {
        IconListRow(
            iconRes = if (cable) R.drawable.ic_usb else R.drawable.ic_wifi,
            title = stringResource(if (cable) R.string.cast_waiting_cable_card_title else R.string.cast_waiting_wifi_card_title),
            sub = stringResource(if (cable) R.string.cast_waiting_cable_card_sub else R.string.cast_waiting_wifi_card_sub),
            right = if (cable) ({ UsbPill(live = true) }) else null,
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outline)
        Row(
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Text(
                text = stringResource(R.string.cast_waiting_stops_in).uppercase(),
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                letterSpacing = 1.1.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = formatCastDuration(remainingMs / 1000).trimStart('0'),
                fontSize = 15.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}
