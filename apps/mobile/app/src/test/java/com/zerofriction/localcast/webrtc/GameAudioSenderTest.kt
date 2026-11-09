package com.zerofriction.localcast.webrtc

import com.zerofriction.localcast.audio.PcmEncoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ADR-013's send pipeline: what reaches the wire before/after the desktop's
 * ack, when the ack never comes, and when the encoder dies mid-cast.
 */
class GameAudioSenderTest {

    private class FakeEncoder(val onFrame: (ByteArray) -> Unit, val onError: () -> Unit) : PcmEncoder {
        var started = false
        var released = false
        val fed = mutableListOf<ByteArray>()
        override fun start() { started = true }
        override fun queuePcm(chunk: ByteArray) { fed.add(chunk) }
        override fun release() { released = true }
    }

    private class Rig {
        var nowMs = 0L
        val negotiator = GameAudioFormatNegotiator(nowMs = { nowMs })
        val wire = mutableListOf<String>()
        val encoders = mutableListOf<FakeEncoder>()
        val sender = GameAudioSender(
            negotiator = negotiator,
            wire = { wire.add(String(it)) },
            encoderFactory = { onFrame, onError -> FakeEncoder(onFrame, onError).also { encoders.add(it) } },
        )
        val encoder get() = encoders.last()

        fun pcm(tag: String) = sender.onSamples(tag.toByteArray())

        /** The channel opened and the announce window ran [ms] long with no ack. */
        fun announceFor(ms: Long) {
            negotiator.onChannelOpen()
            negotiator.pollAnnounce()
            nowMs += ms
            negotiator.pollAnnounce()
        }
    }

    @Test
    fun `before any ack every desktop hears raw pcm while the encoder warms up`() {
        val rig = Rig()
        rig.sender.start()
        rig.pcm("p1")

        assertEquals(listOf("p1"), rig.wire)
        assertTrue(rig.encoder.started)
        assertEquals(1, rig.encoder.fed.size) // warm: no priming needed at the switch
    }

    @Test
    fun `after an aac ack only adts frames take the wire`() {
        val rig = Rig()
        rig.sender.start()
        rig.negotiator.onChannelOpen()
        rig.negotiator.onAck(GameAudioFormatNegotiator.FORMAT_AAC)

        rig.pcm("p1")
        rig.encoder.onFrame("adts1".toByteArray())

        assertEquals(listOf("adts1"), rig.wire)
    }

    @Test
    fun `no ack within the window releases the encoder and pcm continues`() {
        val rig = Rig()
        rig.sender.start()
        rig.announceFor(GameAudioFormatNegotiator.ANNOUNCE_WINDOW_MS)

        assertTrue(rig.encoder.released) // no encoding for nobody (native receiver)
        rig.pcm("p1")
        assertEquals(listOf("p1"), rig.wire)
        assertTrue(rig.encoder.fed.isEmpty())
    }

    @Test
    fun `a late aac ack restarts the encoder before adts takes the wire`() {
        val rig = Rig()
        rig.sender.start()
        rig.announceFor(GameAudioFormatNegotiator.ANNOUNCE_WINDOW_MS)

        rig.negotiator.onAck(GameAudioFormatNegotiator.FORMAT_AAC)

        assertEquals(2, rig.encoders.size)
        assertTrue(rig.encoder.started)
        rig.encoder.onFrame("adts1".toByteArray())
        assertEquals(listOf("adts1"), rig.wire)
    }

    @Test
    fun `an encoder failure mid-cast flips the wire straight back to pcm`() {
        val rig = Rig()
        rig.sender.start()
        rig.negotiator.onChannelOpen()
        rig.negotiator.onAck(GameAudioFormatNegotiator.FORMAT_AAC)

        rig.encoder.onError()
        rig.encoder.onFrame("late-adts".toByteArray()) // a frame racing the failure
        rig.pcm("p1")

        assertEquals(listOf("p1"), rig.wire)
        assertFalse(rig.negotiator.sendsAac)
        // The desktop is told at once (re-announce pcm16), not left to its quiet timer.
        assertTrue(rig.negotiator.pollAnnounce()!!.contains(GameAudioFormatNegotiator.FORMAT_PCM))
    }

    @Test
    fun `release stops the encoder and detaches from the negotiator`() {
        val rig = Rig()
        rig.sender.start()
        rig.sender.release()

        assertTrue(rig.encoder.released)
        rig.negotiator.onChannelOpen()
        rig.negotiator.onAck(GameAudioFormatNegotiator.FORMAT_AAC) // no restart after teardown
        assertEquals(1, rig.encoders.size)
    }

    @Test
    fun `without start - low-latency forced pcm - chunks still flow and nothing is encoded`() {
        val rig = Rig()
        rig.pcm("p1")
        assertEquals(listOf("p1"), rig.wire)
        assertTrue(rig.encoders.isEmpty())
    }
}
