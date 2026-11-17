package io.github.silogos.caster.usb

import io.github.silogos.caster.config.CastDropWindow
import io.github.silogos.caster.usb.UsbCastDrop.Decision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** ADR-036 over the cable: wait for the same desktop, for five minutes, then end. */
class UsbCastDropTest {

    @Test
    fun `an unplug waits for the window`() {
        val drop = UsbCastDrop("desk-1")
        assertEquals(Decision.Wait(1_000, 1_000 + CastDropWindow.MS), drop.onLinkLost(1_000))
        assertEquals(Decision.Ignore, drop.onTick(1_000 + CastDropWindow.MS - 1))
    }

    @Test
    fun `the link closing twice for one unplug is one wait`() {
        val drop = UsbCastDrop("desk-1")
        drop.onLinkLost(1_000) // the encoder's failed write
        assertEquals(Decision.Ignore, drop.onLinkLost(1_005)) // the link's own close
        assertEquals(1_000L, drop.waitingSinceMs)
    }

    @Test
    fun `the same desktop back in time resumes, and the next unplug gets a fresh window`() {
        val drop = UsbCastDrop("desk-1")
        drop.onLinkLost(0)
        assertEquals(Decision.Resume, drop.onLinkReady("desk-1", 60_000))
        assertEquals(null, drop.waitingSinceMs)
        assertEquals(Decision.Wait(100_000, 100_000 + CastDropWindow.MS), drop.onLinkLost(100_000))
    }

    @Test
    fun `another desktop on the cable ends the waiting cast`() {
        val drop = UsbCastDrop("desk-1")
        drop.onLinkLost(0)
        assertTrue(drop.onLinkReady("desk-2", 10_000) is Decision.End)
    }

    @Test
    fun `the window running out ends the cast, by the clock or on a late replug`() {
        val ticked = UsbCastDrop("desk-1")
        ticked.onLinkLost(0)
        assertTrue(ticked.onTick(CastDropWindow.MS) is Decision.End)

        val late = UsbCastDrop("desk-1")
        late.onLinkLost(0)
        assertTrue(late.onLinkReady("desk-1", CastDropWindow.MS + 1) is Decision.End)
    }

    @Test
    fun `a link that is ready while the cast runs changes nothing`() {
        val drop = UsbCastDrop("desk-1")
        assertEquals(Decision.Ignore, drop.onLinkReady("desk-1", 5_000))
        assertEquals(Decision.Ignore, drop.onTick(CastDropWindow.MS * 2))
    }
}
