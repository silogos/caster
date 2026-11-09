package com.zerofriction.localcast.webrtc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ADR-013's in-band format handshake (mobile side): the wire starts as
 * ADR-012 PCM, an ack switches it to AAC, silence past the window (old
 * desktop) keeps PCM for the cast, a lost announce self-heals on retries,
 * and an encoder failure reverts to PCM and announces the revert. Pure
 * state machine — a fake clock drives every timing edge.
 */
class GameAudioFormatNegotiatorTest {

    private var nowMs = 0L

    private fun negotiator(supportsAac: Boolean = true): GameAudioFormatNegotiator =
        GameAudioFormatNegotiator(nowMs = { nowMs }, selfSupportsAac = supportsAac)

    private fun GameAudioFormatNegotiator.announceNow(): String? {
        val payload = pollAnnounce()
        nowMs += 1 // a poll that returns non-null must not repeat inside the retry window
        return payload
    }

    @Test
    fun `opens as pcm and announces aac immediately`() {
        val n = negotiator()
        assertEquals(GameAudioFormatNegotiator.Mode.PCM, n.mode)
        assertFalse(n.sendsAac)
        n.onChannelOpen()
        val payload = n.announceNow()
        assertTrue(payload!!.contains("\"type\":\"announce\""))
        assertTrue(payload.contains("\"format\":\"${GameAudioFormatNegotiator.FORMAT_AAC}\""))
        assertTrue(payload.contains("\"v\":1"))
    }

    @Test
    fun `ack switches the wire to aac and stops announcing`() {
        val n = negotiator()
        n.onChannelOpen()
        n.announceNow()
        n.onAck(GameAudioFormatNegotiator.FORMAT_AAC)
        assertEquals(GameAudioFormatNegotiator.Mode.AAC, n.mode)
        assertTrue(n.sendsAac)
        nowMs += 10_000
        assertNull(n.pollAnnounce())
    }

    @Test
    fun `ack for an unhealthy encoder keeps pcm`() {
        val n = negotiator(supportsAac = false)
        n.onChannelOpen()
        n.announceNow()
        n.onAck(GameAudioFormatNegotiator.FORMAT_AAC)
        assertEquals(GameAudioFormatNegotiator.Mode.PCM, n.mode)
        assertFalse(n.sendsAac)
    }

    @Test
    fun `silence past the window stops announcing and keeps pcm`() {
        val n = negotiator()
        n.onChannelOpen()
        var sent = 0
        // Poll far past the window at retry cadence; count what goes out.
        while (true) {
            val payload = n.pollAnnounce() ?: break
            sent++
            nowMs += GameAudioFormatNegotiator.ANNOUNCE_RETRY_MS
        }
        assertEquals(GameAudioFormatNegotiator.Mode.PCM, n.mode)
        assertFalse(n.sendsAac)
        // 2 s window / 0.5 s retry → the first announce plus 3 retries.
        assertEquals(4, sent)
        // and nothing further, ever, until a reopen.
        nowMs += 60_000
        assertNull(n.pollAnnounce())
    }

    @Test
    fun `a late ack after the window is still honored`() {
        val n = negotiator()
        n.onChannelOpen()
        while (n.pollAnnounce() != null) nowMs += GameAudioFormatNegotiator.ANNOUNCE_RETRY_MS
        nowMs += 30_000
        n.onAck(GameAudioFormatNegotiator.FORMAT_AAC)
        assertEquals(GameAudioFormatNegotiator.Mode.AAC, n.mode)
        assertTrue(n.sendsAac)
    }

    @Test
    fun `announce retries inside the window at the retry cadence`() {
        val n = negotiator()
        n.onChannelOpen()
        // First announce is immediate; a poll inside the retry window returns null.
        n.announceNow()
        assertNull(n.pollAnnounce())
        nowMs += GameAudioFormatNegotiator.ANNOUNCE_RETRY_MS
        assertTrue(n.pollAnnounce() != null)
    }

    @Test
    fun `encoder failure reverts to pcm and announces pcm16`() {
        val n = negotiator()
        n.onChannelOpen()
        n.onAck(GameAudioFormatNegotiator.FORMAT_AAC)
        assertTrue(n.sendsAac)

        n.onEncoderFailed()
        assertEquals(GameAudioFormatNegotiator.Mode.PCM, n.mode)
        assertFalse(n.sendsAac)
        val payload = n.announceNow()
        assertTrue(payload!!.contains("\"format\":\"${GameAudioFormatNegotiator.FORMAT_PCM}\""))
    }

    @Test
    fun `announce parses back into an ack on the receiving side`() {
        val n = negotiator()
        n.onChannelOpen()
        val wire = n.announceNow()!!
        // The desktop parses the same JSON shape it received; its ack parses here.
        val parsed = HandshakeMessage.parse(
            """{"v":1,"type":"ack","format":"${GameAudioFormatNegotiator.FORMAT_AAC}"}""",
        )
        assertTrue(parsed is HandshakeMessage.Ack)
        assertEquals(GameAudioFormatNegotiator.FORMAT_AAC, (parsed as HandshakeMessage.Ack).format)
        assertTrue(wire.isNotEmpty())
    }

    @Test
    fun `parse rejects garbage and other versions`() {
        assertNull(HandshakeMessage.parse("not json"))
        assertNull(HandshakeMessage.parse("""{"v":2,"type":"ack","format":"x"}"""))
        assertNull(HandshakeMessage.parse("""{"v":1,"type":"announce","format":"x"}"""))
        assertNull(HandshakeMessage.parse("""{"v":1,"type":"ack"}"""))
    }

    @Test
    fun `channel reopen restarts the announce window`() {
        val n = negotiator()
        n.onChannelOpen()
        while (n.pollAnnounce() != null) nowMs += GameAudioFormatNegotiator.ANNOUNCE_RETRY_MS
        n.onChannelOpen()
        assertTrue(n.pollAnnounce() != null)
    }

    // ---- encoder lifecycle hooks (the sender stops encoding for PCM-only desktops) ----

    private fun GameAudioFormatNegotiator.runOutTheWindow() {
        onChannelOpen()
        while (pollAnnounce() != null) nowMs += GameAudioFormatNegotiator.ANNOUNCE_RETRY_MS
        nowMs += GameAudioFormatNegotiator.ANNOUNCE_WINDOW_MS
        pollAnnounce()
    }

    @Test
    fun `a window that closes without an ack settles pcm exactly once`() {
        val n = negotiator()
        var settled = 0
        n.onPcmSettled = { settled++ }
        n.runOutTheWindow()
        assertEquals(1, settled)
        repeat(3) { nowMs += 1_000; n.pollAnnounce() }
        assertEquals("later polls do not fire it again", 1, settled)
        assertFalse(n.sendsAac)
    }

    @Test
    fun `an ack inside the window never settles pcm`() {
        val n = negotiator()
        var settled = 0
        n.onPcmSettled = { settled++ }
        n.onChannelOpen()
        n.announceNow()
        n.onAck(GameAudioFormatNegotiator.FORMAT_AAC)
        nowMs += GameAudioFormatNegotiator.ANNOUNCE_WINDOW_MS * 2
        n.pollAnnounce()
        assertEquals(0, settled)
        assertTrue(n.sendsAac)
    }

    @Test
    fun `the encoder hook runs before the wire switches to aac`() {
        val n = negotiator()
        var modeSeenByHook: GameAudioFormatNegotiator.Mode? = null
        n.onAacAccepted = { modeSeenByHook = n.mode }
        n.onChannelOpen()
        n.onAck(GameAudioFormatNegotiator.FORMAT_AAC)
        assertEquals("ADTS must not be expected before the encoder runs", GameAudioFormatNegotiator.Mode.PCM, modeSeenByHook)
        assertTrue(n.sendsAac)
    }

    @Test
    fun `a late ack after pcm settled still switches, and a failed restart keeps pcm`() {
        val n = negotiator()
        n.runOutTheWindow()
        var restarts = 0
        n.onAacAccepted = { restarts++ }
        n.onAck(GameAudioFormatNegotiator.FORMAT_AAC)
        assertEquals(1, restarts)
        assertTrue("a desktop that acked understands AAC whenever it answered", n.sendsAac)

        val failing = negotiator()
        failing.onAacAccepted = { failing.onEncoderFailed() } // the restart blew up
        failing.onChannelOpen()
        failing.onAck(GameAudioFormatNegotiator.FORMAT_AAC)
        assertFalse(failing.sendsAac)
        assertEquals(GameAudioFormatNegotiator.Mode.PCM, failing.mode)
    }

    @Test
    fun `a pcm ack does not start the encoder`() {
        val n = negotiator()
        var restarts = 0
        n.onAacAccepted = { restarts++ }
        n.onChannelOpen()
        n.onAck(GameAudioFormatNegotiator.FORMAT_PCM)
        assertEquals(0, restarts)
    }
}
