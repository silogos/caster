package com.zerofriction.localcast.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zerofriction.localcast.R
import com.zerofriction.localcast.hotspot.HotspotMachine
import com.zerofriction.localcast.ui.components.CastCard
import com.zerofriction.localcast.ui.components.CastTextButton
import com.zerofriction.localcast.ui.components.IconListRow

/**
 * The hotspot card (issue #96, ADR-011) — the guided-manual flow's anchor:
 * credentials while Active, honesty while Starting/Failed. Every failure is
 * plain words with a one-tap retry; OS revocation says so.
 */
@Composable
internal fun HotspotCard(
    state: HotspotMachine.State,
    onStop: () -> Unit,
    onRetry: () -> Unit,
) {
    CastCard {
        when (state) {
            HotspotMachine.State.Idle -> {}
            HotspotMachine.State.NeedPermission, HotspotMachine.State.Starting -> {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                ) {
                    CircularProgressIndicator(Modifier.size(16.dp))
                    Text(text = stringResource(R.string.hotspot_turning_on), fontSize = 14.sp)
                }
            }

            is HotspotMachine.State.Active -> {
                IconListRow(iconRes = R.drawable.ic_info, title = stringResource(R.string.hotspot_active_title))
                Text(
                    text = stringResource(
                        R.string.hotspot_credentials_line,
                        state.ssid,
                        state.password,
                    ),
                    fontSize = 15.sp,
                    lineHeight = 21.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(start = 16.dp, top = 2.dp, end = 16.dp),
                )
                HintText(stringResource(R.string.hotspot_join_hint))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(18.dp),
                    modifier = Modifier.padding(start = 16.dp, bottom = 12.dp),
                ) {
                    CastTextButton(text = stringResource(R.string.hotspot_stop), onClick = onStop)
                }
            }

            is HotspotMachine.State.Failed -> {
                IconListRow(iconRes = R.drawable.ic_warning, title = hotspotFailureMessage(state.reason), err = true)
                Row(
                    horizontalArrangement = Arrangement.spacedBy(18.dp),
                    modifier = Modifier.padding(start = 16.dp, top = 2.dp, bottom = 12.dp),
                ) {
                    CastTextButton(text = stringResource(R.string.hotspot_retry), onClick = onRetry)
                }
            }
        }
    }
}

/** The hotspot failure table — plain words per AGENTS.md, codes stay in logs. */
@Composable
internal fun hotspotFailureMessage(reason: HotspotMachine.Reason): String = stringResource(
    when (reason) {
        HotspotMachine.Reason.PERMISSION_DECLINED -> R.string.hotspot_error_permission
        HotspotMachine.Reason.NOT_SUPPORTED -> R.string.hotspot_error_not_supported
        HotspotMachine.Reason.NO_CHANNEL -> R.string.hotspot_error_no_channel
        HotspotMachine.Reason.INCOMPATIBLE_MODE -> R.string.hotspot_error_incompatible_mode
        HotspotMachine.Reason.OS_REVOKED -> R.string.hotspot_error_os_revoked
        HotspotMachine.Reason.UNKNOWN -> R.string.hotspot_error_unknown
    }
)
