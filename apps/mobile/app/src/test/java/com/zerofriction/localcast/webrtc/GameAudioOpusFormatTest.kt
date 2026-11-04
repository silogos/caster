package com.zerofriction.localcast.webrtc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The game-audio channel payload v1 wire contract (ADR-013, webrtc.md): the
 * announce JSON the desktop parses, and the csd-0 OpusHead pre-skip read.
 * Both ends must agree exactly — the desktop implements the same validation
 * in game-audio-channel.ts, and these tests pin that agreement on this side.
 */
class GameAudioOpusFormatTest {

    @Test
    fun `announce is the exact v1 opus 48k stereo JSON`() {
        assertEquals(
            """{"v":1,"codec":"opus","sampleRate":48000,"channels":2,"preskip":312}""",
            GameAudioOpusFormat.announce(GameAudioOpusFormat.DEFAULT_PRESKIP_SAMPLES),
        )
    }

    @Test
    fun `announce carries a csd-derived preskip unchanged`() {
        assertEquals(
            """{"v":1,"codec":"opus","sampleRate":48000,"channels":2,"preskip":356}""",
            GameAudioOpusFormat.announce(356),
        )
    }

    @Test
    fun `announce parses back to the preskip`() {
        val json = GameAudioOpusFormat.announce(312)
        assertEquals(312, GameAudioOpusFormat.parseAnnounce(json))
    }

    @Test
    fun `parseAnnounce rejects a wrong version, codec, rate, channel count or preskip`() {
        assertNull(GameAudioOpusFormat.parseAnnounce("""{"v":2,"codec":"opus","sampleRate":48000,"channels":2,"preskip":312}"""))
        assertNull(GameAudioOpusFormat.parseAnnounce("""{"v":1,"codec":"aac","sampleRate":48000,"channels":2,"preskip":312}"""))
        assertNull(GameAudioOpusFormat.parseAnnounce("""{"v":1,"codec":"opus","sampleRate":44100,"channels":2,"preskip":312}"""))
        assertNull(GameAudioOpusFormat.parseAnnounce("""{"v":1,"codec":"opus","sampleRate":48000,"channels":1,"preskip":312}"""))
        assertNull(GameAudioOpusFormat.parseAnnounce("""{"v":1,"codec":"opus","sampleRate":48000,"channels":2,"preskip":-1}"""))
        assertNull(GameAudioOpusFormat.parseAnnounce("""{"v":1,"codec":"opus","sampleRate":48000,"channels":2}"""))
        assertNull(GameAudioOpusFormat.parseAnnounce("not json at all"))
    }

    /** A real OpusHead: preskip 312 (little-endian — RFC 7845 §3.1), 48 kHz, stereo, gain 0, mapping family 0. */
    private fun opusHead(preskip: Int, channels: Int = 2, rate: Int = 48_000): ByteArray {
        val head = ByteArray(GameAudioOpusFormat.OPUS_HEAD_SIZE)
        "OpusHead".toByteArray(Charsets.US_ASCII).copyInto(head)
        head[8] = 1 // version
        head[9] = channels.toByte()
        head[10] = (preskip and 0xFF).toByte() // preskip, little-endian
        head[11] = ((preskip shr 8) and 0xFF).toByte()
        head[12] = (rate and 0xFF).toByte() // sample rate, little-endian
        head[13] = ((rate shr 8) and 0xFF).toByte()
        head[14] = ((rate shr 16) and 0xFF).toByte()
        head[15] = ((rate shr 24) and 0xFF).toByte()
        head[16] = 0 // output gain
        head[17] = 0
        head[18] = 0 // mapping family
        return head
    }

    @Test
    fun `preskip reads the little-endian field from a well-formed OpusHead`() {
        assertEquals(312, GameAudioOpusFormat.preskipFromOpusHead(opusHead(312)))
        assertEquals(648, GameAudioOpusFormat.preskipFromOpusHead(opusHead(648)))
        // ffmpeg's own head for a 312-preskip stream carries `38 01` at [10..11].
        assertEquals(312, GameAudioOpusFormat.preskipFromOpusHead(
            byteArrayOf(*"OpusHead".toByteArray(Charsets.US_ASCII), 1, 2, 0x38, 0x01, 0x80.toByte(), 0xBB.toByte(), 0, 0, 0, 0, 0),
        ))
    }

    @Test
    fun `preskip rejects non-OpusHead csd`() {
        assertNull(GameAudioOpusFormat.preskipFromOpusHead(ByteArray(0)))
        assertNull(GameAudioOpusFormat.preskipFromOpusHead(ByteArray(10)))
        assertNull(GameAudioOpusFormat.preskipFromOpusHead("NotOpusHead".toByteArray() + ByteArray(9)))
        assertNull(GameAudioOpusFormat.preskipFromOpusHead(opusHead(312, channels = 1)))
        assertNull(GameAudioOpusFormat.preskipFromOpusHead(opusHead(312, rate = 44_100)))
    }
}
