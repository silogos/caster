package io.github.silogos.caster.ui.link

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.silogos.caster.R
import io.github.silogos.caster.link.Link
import io.github.silogos.caster.ui.components.Annunciator
import io.github.silogos.caster.ui.components.AnnuncTone
import io.github.silogos.caster.ui.components.BrandMark
import io.github.silogos.caster.ui.components.CastCard
import io.github.silogos.caster.ui.theme.CastBad
import io.github.silogos.caster.ui.theme.CastGood
import io.github.silogos.caster.usb.UsbHandshake
import io.github.silogos.caster.usb.UsbLink
import kotlinx.coroutines.delay

/** How long the guide waits at step 1 before suggesting the computer may be on Wireless (design D3). */
private const val CHOOSE_CABLE_HINT_AFTER_MS = 10_000L

/** The app bar every link screen shares: nameplate + STANDBY. */
@Composable
private fun LinkBar() {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp).padding(top = 2.dp, bottom = 14.dp),
    ) {
        BrandMark(onAir = false)
        Text(
            text = stringResource(R.string.app_name),
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            fontFamily = FontFamily.Monospace,
            letterSpacing = 1.7.sp,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        Annunciator(word = stringResource(R.string.app_bar_state_standby), tone = AnnuncTone.QUIET)
    }
}

@Composable
private fun LinkPage(content: @Composable () -> Unit) {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
        ) { content() }
    }
}

@Composable
fun BackLink(onBack: () -> Unit, modifier: Modifier = Modifier) {
    TextButton(onClick = onBack, modifier = modifier) {
        Text(
            text = "‹  " + stringResource(R.string.back),
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun Heading(title: String, sub: String) {
    Text(title, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.padding(top = 8.dp))
    Text(sub, fontSize = 13.sp, lineHeight = 19.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
}

/**
 * The first screen while nothing is paired (ADR-037 §1; "Mobile flow v8" A1):
 * Wireless or Cable. Nothing runs until one is picked.
 */
@Composable
fun ChoiceScreen(onChoose: (Link) -> Unit) {
    LinkPage {
        LinkBar()
        Heading(stringResource(R.string.link_choice_title), stringResource(R.string.link_choice_sub))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 18.dp)) {
            ChoiceTile(R.drawable.ic_wifi, stringResource(R.string.link_wireless), stringResource(R.string.link_wireless_sub), Modifier.weight(1f)) {
                onChoose(Link.Wireless)
            }
            ChoiceTile(R.drawable.ic_usb, stringResource(R.string.link_cable), stringResource(R.string.link_cable_sub), Modifier.weight(1f)) {
                onChoose(Link.Cable)
            }
        }
        HintLine(stringResource(R.string.link_choice_note))
    }
}

@Composable
private fun ChoiceTile(icon: Int, title: String, sub: String, modifier: Modifier, onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(BorderStroke(1.dp, MaterialTheme.colorScheme.outline), RoundedCornerShape(18.dp))
            .clickable(onClick = onClick)
            .padding(16.dp),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(38.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
        ) {
            Icon(painterResource(icon), contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
        }
        Text(title, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.padding(top = 14.dp))
        Text(sub, fontSize = 12.sp, lineHeight = 17.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
    }
}

@Composable
private fun HintLine(text: String) {
    Row(verticalAlignment = Alignment.Top, modifier = Modifier.padding(top = 16.dp, start = 4.dp, end = 4.dp)) {
        Box(Modifier.padding(top = 6.dp).size(6.dp).clip(CircleShape).background(MaterialTheme.colorScheme.onSurfaceVariant))
        Spacer(Modifier.size(10.dp))
        Text(text, fontSize = 13.sp, lineHeight = 19.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private enum class StepState { Done, Now, Later }

/**
 * The cable guide (ADR-037 §1; "Mobile flow v8" B1/B3/D3/D4): plug in →
 * Android's "Open Caster" → the computer's one-time Allow. Each step ticks
 * as it happens — no Done buttons. Once the computer allows, the cable
 * pairing is stored and the app moves to Home by itself.
 */
@Composable
fun CableGuideScreen(phoneModel: String, onBack: () -> Unit) {
    val usb by UsbLink.state.collectAsStateWithLifecycle()
    var waitedLong by remember { mutableStateOf(false) }
    LaunchedEffect(usb) {
        waitedLong = false
        if (usb == UsbLink.State.None) {
            delay(CHOOSE_CABLE_HINT_AFTER_MS)
            waitedLong = true
        }
    }
    val at = when (usb) {
        UsbLink.State.None -> 0
        UsbLink.State.Checking -> 2
        is UsbLink.State.Asking, is UsbLink.State.Refused -> 2
        else -> 3
    }
    fun state(i: Int) = if (i < at) StepState.Done else if (i == at) StepState.Now else StepState.Later
    LinkPage {
        LinkBar()
        BackLink(onBack, Modifier.padding(start = 0.dp))
        Heading(stringResource(R.string.cable_guide_title), stringResource(R.string.cable_guide_sub))
        CastCard(Modifier.padding(top = 18.dp)) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 18.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                Step(1, stringResource(R.string.cable_step_plug_title), stringResource(R.string.cable_step_plug_sub), state(0))
                Step(2, stringResource(R.string.cable_step_open_title), stringResource(R.string.cable_step_open_sub), state(1))
                Step(3, stringResource(R.string.cable_step_allow_title), stringResource(R.string.cable_step_allow_sub, phoneModel), state(2))
            }
        }
        when (val s = usb) {
            is UsbLink.State.Asking -> StatusCard(s.desktopName, stringResource(R.string.cable_waiting_allow), MaterialTheme.colorScheme.primary)
            is UsbLink.State.Refused -> {
                val (title, sub) = when (s.refusal) {
                    UsbHandshake.Refusal.NotAllowed -> stringResource(R.string.cable_not_allowed_title) to stringResource(R.string.cable_not_allowed_sub)
                    UsbHandshake.Refusal.Busy -> stringResource(R.string.cable_busy) to null
                    UsbHandshake.Refusal.OldComputer -> stringResource(R.string.cable_update_computer) to null
                    UsbHandshake.Refusal.OtherComputer -> stringResource(R.string.cable_other_computer) to null
                    UsbHandshake.Refusal.Failed -> stringResource(R.string.cable_failed) to null
                }
                StatusCard(title, sub, CastBad)
            }
            else -> if (waitedLong) HintLine(stringResource(R.string.cable_hint_choose_cable))
        }
    }
}

@Composable
private fun Step(n: Int, title: String, sub: String, state: StepState) {
    Row(verticalAlignment = Alignment.Top) {
        val primary = MaterialTheme.colorScheme.primary
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(24.dp)
                .clip(CircleShape)
                .background(if (state == StepState.Done) CastGood else Color.Transparent)
                .border(1.5.dp, if (state == StepState.Now) primary else if (state == StepState.Done) CastGood else MaterialTheme.colorScheme.outline, CircleShape),
        ) {
            Text(
                text = if (state == StepState.Done) "✓" else n.toString(),
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = when (state) {
                    StepState.Done -> MaterialTheme.colorScheme.background
                    StepState.Now -> primary
                    StepState.Later -> MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
        Spacer(Modifier.size(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = if (state == StepState.Later) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
            Text(sub, fontSize = 13.sp, lineHeight = 19.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp))
        }
    }
}

@Composable
private fun StatusCard(title: String, sub: String?, tone: Color) {
    CastCard(Modifier.padding(top = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(16.dp)) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.size(38.dp).clip(RoundedCornerShape(12.dp)).background(tone.copy(alpha = 0.10f)),
            ) {
                Icon(painterResource(R.drawable.ic_usb), contentDescription = null, tint = tone, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.size(14.dp))
            Column(Modifier.weight(1f)) {
                Text(title, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = tone)
                if (sub != null) Text(sub, fontSize = 13.sp, lineHeight = 19.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
