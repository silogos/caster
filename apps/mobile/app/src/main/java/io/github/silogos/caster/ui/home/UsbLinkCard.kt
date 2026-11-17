package io.github.silogos.caster.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.silogos.caster.R
import io.github.silogos.caster.ui.components.CastCard
import io.github.silogos.caster.ui.components.CastTextButton
import io.github.silogos.caster.ui.components.IconListRow
import io.github.silogos.caster.usb.UsbLink

/**
 * The cable's state on Home (ADR-034; designs/mobile-app.figcraft, page
 * "Mobile design v3 · USB cast", U1–U3). Nothing when no cable link is open.
 */
@Composable
internal fun UsbLinkCard(
    state: UsbLink.State,
    onScanQr: () -> Unit,
    modifier: Modifier = Modifier,
    /** The profile the cable uses (ADR-034), named on the ready and casting rows. */
    cableProfileName: String = "",
) {
    when (state) {
        UsbLink.State.None, UsbLink.State.NotPaired -> return
        else -> Unit
    }
    CastCard(modifier) {
        when (state) {
            UsbLink.State.Checking -> IconListRow(
                iconRes = R.drawable.ic_usb,
                title = stringResource(R.string.usb_checking_title),
                sub = stringResource(R.string.usb_checking_sub),
                right = { UsbPill() },
            )
            is UsbLink.State.Ready -> IconListRow(
                iconRes = R.drawable.ic_usb,
                title = stringResource(R.string.usb_ready_title),
                sub = stringResource(R.string.usb_ready_sub) + "\n" + stringResource(R.string.usb_ready_profile, cableProfileName),
                right = { UsbPill(live = true) },
            )
            is UsbLink.State.Casting -> {
                IconListRow(
                    iconRes = R.drawable.ic_usb,
                    title = stringResource(R.string.usb_casting_title),
                    sub = stringResource(R.string.usb_ready_profile, cableProfileName) + " " +
                        stringResource(R.string.usb_casting_sub),
                    right = { UsbPill(live = true) },
                )
                Text(
                    text = stringResource(R.string.usb_casting_quality),
                    fontSize = 13.sp,
                    lineHeight = 19.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 68.dp, end = 16.dp, bottom = 14.dp),
                )
            }
            UsbLink.State.Refused -> {
                IconListRow(
                    iconRes = R.drawable.ic_usb,
                    title = stringResource(R.string.usb_refused_title),
                    sub = stringResource(R.string.usb_refused_sub),
                    err = true,
                )
                CastTextButton(
                    text = stringResource(R.string.home_scan_qr),
                    onClick = onScanQr,
                    modifier = Modifier.padding(start = 68.dp, bottom = 8.dp),
                )
            }
            else -> Unit
        }
    }
}

/** The link's tag: amber once the desktop is proven, quiet while checking. */
@Composable
internal fun UsbPill(live: Boolean = false) {
    val tone = if (live) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    Text(
        text = stringResource(R.string.usb_pill),
        fontSize = 10.sp,
        fontWeight = FontWeight.Bold,
        fontFamily = FontFamily.Monospace,
        letterSpacing = 0.8.sp,
        color = tone,
        modifier = Modifier
            .background(tone.copy(alpha = 0.14f), RoundedCornerShape(10.dp))
            .padding(horizontal = 10.dp, vertical = 3.dp),
    )
}
