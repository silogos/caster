package io.github.silogos.caster.ui.home

import io.github.silogos.caster.link.Link
import io.github.silogos.caster.pairing.PairingClient
import io.github.silogos.caster.service.CastLink
import io.github.silogos.caster.service.CastState
import io.github.silogos.caster.service.ConnectedDesktop
import io.github.silogos.caster.usb.UsbHandshake
import io.github.silogos.caster.usb.UsbLink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Home's connection card and big button, state by state ("Mobile flow v8",
 * ADR-037 §7). Each case names the design frame it draws.
 */
class LinkUiTest {

    private fun cable(usb: UsbLink.State, cast: CastState = CastState.Idle) =
        linkUi(Link.Cable, "MacBook Pro", ConnectedDesktop.State.None, PairingClient.State.Idle, usb, CastState.Idle, cast)

    private fun wireless(connection: ConnectedDesktop.State, cast: CastState = CastState.Idle, pairing: PairingClient.State = PairingClient.State.Idle) =
        linkUi(Link.Wireless, "", connection, pairing, UsbLink.State.None, cast, CastState.Idle)

    @Test
    fun `B5 C2 - cable connected - Share, unplug hint, Terminate`() {
        val ui = cable(UsbLink.State.Ready("MacBook Pro"))
        assertEquals(LinkStatus.Connected, ui.status)
        assertEquals(LinkHint.UnplugToDisconnect, ui.hint)
        assertEquals(listOf(LinkAction.Terminate), ui.footer)
        assertEquals(PrimaryAction.Share, ui.primary)
    }

    @Test
    fun `C1 - cable not plugged in - greyed Share, the stored name, Terminate`() {
        val ui = cable(UsbLink.State.None)
        assertEquals(LinkStatus.NotConnected, ui.status)
        assertEquals("MacBook Pro", ui.computerName)
        assertEquals(LinkHint.PlugIn, ui.hint)
        assertEquals(PrimaryAction.ShareDisabled, ui.primary)
        assertEquals(listOf(LinkAction.Terminate), ui.footer)
    }

    @Test
    fun `C4 - cable on air - Stop, and no footer`() {
        val ui = cable(UsbLink.State.Casting("MacBook Pro"), CastState.Casting("MacBook Pro"))
        assertEquals(LinkStatus.Connected, ui.status)
        assertEquals(PrimaryAction.Stop, ui.primary)
        assertTrue(ui.footer.isEmpty())
    }

    @Test
    fun `D5 - unplugged mid-cast - Reconnecting with the window's end, Stop, no footer`() {
        val waiting = CastState.Interrupted("MacBook Pro", CastLink.CABLE, sinceMillis = 1_000, endsAtMillis = 301_000)
        val ui = cable(UsbLink.State.None, waiting)
        assertEquals(LinkStatus.Reconnecting(301_000), ui.status)
        assertEquals(LinkHint.PlugBackIn, ui.hint)
        assertEquals(PrimaryAction.Stop, ui.primary)
        assertTrue(ui.footer.isEmpty())
    }

    @Test
    fun `a refused cable says why`() {
        assertEquals(LinkHint.Busy, cable(UsbLink.State.Refused(UsbHandshake.Refusal.Busy)).hint)
        assertEquals(LinkHint.UpdateComputer, cable(UsbLink.State.Refused(UsbHandshake.Refusal.OldComputer)).hint)
        assertEquals(LinkHint.OtherComputer, cable(UsbLink.State.Refused(UsbHandshake.Refusal.OtherComputer)).hint)
    }

    @Test
    fun `A4 - wireless connected - Share, Disconnect and Terminate`() {
        val ui = wireless(ConnectedDesktop.State.Connected("MacBook Pro"))
        assertEquals("MacBook Pro", ui.computerName)
        assertEquals(LinkStatus.Connected, ui.status)
        assertEquals(listOf(LinkAction.Disconnect, LinkAction.Terminate), ui.footer)
        assertEquals(PrimaryAction.Share, ui.primary)
    }

    @Test
    fun `D1 - wireless not connected - Connect, then Connecting while it runs`() {
        val saved = ConnectedDesktop.State.Saved("MacBook Pro")
        val ui = wireless(saved)
        assertEquals(LinkStatus.NotConnected, ui.status)
        assertEquals(LinkHint.SameWifi, ui.hint)
        assertEquals(listOf(LinkAction.Terminate), ui.footer)
        assertEquals(PrimaryAction.Connect, ui.primary)
        assertEquals(PrimaryAction.Connecting, wireless(saved, pairing = PairingClient.State.Connecting).primary)
    }

    @Test
    fun `wireless starting and casting have no footer`() {
        assertEquals(PrimaryAction.Starting, wireless(ConnectedDesktop.State.Connected("M"), CastState.Starting("M")).primary)
        val casting = wireless(ConnectedDesktop.State.Connected("M"), CastState.Casting("M"))
        assertEquals(PrimaryAction.Stop, casting.primary)
        assertTrue(casting.footer.isEmpty())
    }

    @Test
    fun `each link reads only its own state - a stray cable state never shows in Wireless mode`() {
        val ui = linkUi(
            Link.Wireless, "", ConnectedDesktop.State.Saved("MacBook Pro"), PairingClient.State.Idle,
            UsbLink.State.Ready("Other Mac"), CastState.Idle, CastState.Casting("Other Mac"),
        )
        assertEquals("MacBook Pro", ui.computerName)
        assertEquals(LinkStatus.NotConnected, ui.status)
        assertEquals(PrimaryAction.Connect, ui.primary)
        val cableUi = linkUi(
            Link.Cable, "MacBook Pro", ConnectedDesktop.State.Connected("Wi-Fi Mac"), PairingClient.State.Idle,
            UsbLink.State.None, CastState.Casting("Wi-Fi Mac"), CastState.Idle,
        )
        assertEquals("MacBook Pro", cableUi.computerName)
        assertEquals(PrimaryAction.ShareDisabled, cableUi.primary)
    }
}
