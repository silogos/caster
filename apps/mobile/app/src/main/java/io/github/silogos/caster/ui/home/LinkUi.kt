package io.github.silogos.caster.ui.home

import io.github.silogos.caster.link.Link
import io.github.silogos.caster.pairing.PairingClient
import io.github.silogos.caster.service.CastState
import io.github.silogos.caster.service.ConnectedDesktop
import io.github.silogos.caster.service.liveDesktopName
import io.github.silogos.caster.usb.UsbHandshake
import io.github.silogos.caster.usb.UsbLink

/**
 * Home's connection card and big button (ADR-037 §7; designs/mobile-app.figcraft,
 * page "Mobile flow v8"), decided in one place: which computer, over which
 * link, its status, the card's footer, and the one next step. Only the
 * active link's state is read — in Cable mode the Wi-Fi state is never
 * looked at, and the other way round — so the card can't mix two links.
 */
data class LinkUi(
    val link: Link,
    val computerName: String,
    val status: LinkStatus,
    val hint: LinkHint?,
    /** The card's footer; empty while a cast runs or waits (no Terminate by a stray tap). */
    val footer: List<LinkAction>,
    val primary: PrimaryAction,
)

sealed interface LinkStatus {
    data object Connected : LinkStatus
    data object NotConnected : LinkStatus
    /** ADR-036: the cast waits for its link until [endsAtMillis]. */
    data class Reconnecting(val endsAtMillis: Long) : LinkStatus
}

enum class LinkHint {
    /** Cable, not connected. */
    PlugIn,
    /** Cable, connected: unplugging is the cable's disconnect. */
    UnplugToDisconnect,
    /** Cable, a cast waiting for it. */
    PlugBackIn,
    /** Wireless, not connected. */
    SameWifi,
    /** Wireless, a cast waiting for Wi-Fi. */
    WaitingForWifi,
    /** Cable: this phone is paired with another computer. */
    OtherComputer,
    /** Cable: the computer is paired with another phone. */
    Busy,
    /** Cable: the computer runs an older Caster. */
    UpdateComputer,
    /** Cable: the link failed. */
    CableFailed,
}

enum class LinkAction { Disconnect, Terminate }

enum class PrimaryAction {
    Share,
    /** Cable, not plugged in: Share stays in place, greyed. */
    ShareDisabled,
    Connect,
    /** A Wi-Fi reconnect is in flight. */
    Connecting,
    /** A Wi-Fi cast is starting (the consent window). */
    Starting,
    Stop,
}

fun linkUi(
    link: Link,
    savedName: String,
    connection: ConnectedDesktop.State,
    pairingState: PairingClient.State,
    usbState: UsbLink.State,
    castState: CastState,
    usbCastState: CastState,
): LinkUi = when (link) {
    Link.Cable -> cableUi(savedName, usbState, usbCastState)
    Link.Wireless -> wirelessUi(savedName, connection, pairingState, castState)
}

private fun cableUi(savedName: String, usb: UsbLink.State, cast: CastState): LinkUi {
    val name = cast.liveDesktopName()
        ?: (usb as? UsbLink.State.Ready)?.desktopName
        ?: (usb as? UsbLink.State.Casting)?.desktopName
        ?: savedName
    fun ui(status: LinkStatus, hint: LinkHint?, footer: List<LinkAction>, primary: PrimaryAction) =
        LinkUi(Link.Cable, name, status, hint, footer, primary)
    if (cast is CastState.Interrupted) return ui(LinkStatus.Reconnecting(cast.endsAtMillis), LinkHint.PlugBackIn, emptyList(), PrimaryAction.Stop)
    if (cast is CastState.Casting) return ui(LinkStatus.Connected, null, emptyList(), PrimaryAction.Stop)
    return when (usb) {
        is UsbLink.State.Ready, is UsbLink.State.Casting ->
            ui(LinkStatus.Connected, LinkHint.UnplugToDisconnect, listOf(LinkAction.Terminate), PrimaryAction.Share)
        is UsbLink.State.Refused -> ui(LinkStatus.NotConnected, refusalHint(usb.refusal), listOf(LinkAction.Terminate), PrimaryAction.ShareDisabled)
        // Checking lasts a moment (the stored key's proof); Asking can't happen to a paired phone.
        else -> ui(LinkStatus.NotConnected, LinkHint.PlugIn, listOf(LinkAction.Terminate), PrimaryAction.ShareDisabled)
    }
}

private fun refusalHint(refusal: UsbHandshake.Refusal): LinkHint = when (refusal) {
    UsbHandshake.Refusal.OtherComputer -> LinkHint.OtherComputer
    UsbHandshake.Refusal.Busy -> LinkHint.Busy
    UsbHandshake.Refusal.OldComputer -> LinkHint.UpdateComputer
    UsbHandshake.Refusal.NotAllowed, UsbHandshake.Refusal.Failed -> LinkHint.CableFailed
}

private fun wirelessUi(savedName: String, connection: ConnectedDesktop.State, pairing: PairingClient.State, cast: CastState): LinkUi {
    val name = cast.liveDesktopName()
        ?: (connection as? ConnectedDesktop.State.Connected)?.desktopName
        ?: (connection as? ConnectedDesktop.State.Saved)?.desktopName
        ?: savedName
    fun ui(status: LinkStatus, hint: LinkHint?, footer: List<LinkAction>, primary: PrimaryAction) =
        LinkUi(Link.Wireless, name, status, hint, footer, primary)
    return when {
        cast is CastState.Interrupted -> ui(LinkStatus.Reconnecting(cast.endsAtMillis), LinkHint.WaitingForWifi, emptyList(), PrimaryAction.Stop)
        cast is CastState.Casting -> ui(LinkStatus.Connected, null, emptyList(), PrimaryAction.Stop)
        cast is CastState.Starting -> ui(LinkStatus.Connected, null, emptyList(), PrimaryAction.Starting)
        connection is ConnectedDesktop.State.Connected ->
            ui(LinkStatus.Connected, null, listOf(LinkAction.Disconnect, LinkAction.Terminate), PrimaryAction.Share)
        else -> {
            val connecting = pairing is PairingClient.State.Connecting ||
                pairing is PairingClient.State.Authenticating ||
                pairing is PairingClient.State.Reconnecting
            ui(LinkStatus.NotConnected, LinkHint.SameWifi, listOf(LinkAction.Terminate), if (connecting) PrimaryAction.Connecting else PrimaryAction.Connect)
        }
    }
}
