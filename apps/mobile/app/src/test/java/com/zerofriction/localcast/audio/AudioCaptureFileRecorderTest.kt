package com.zerofriction.localcast.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The bounded encoder-input dump (issue #101 diagnostics): a valid
 * 16-bit-PCM WAV whose sizes match the bytes actually written, hard-stopped
 * at its cap, and safe to keep feeding after it stopped.
 */
class AudioCaptureFileRecorderTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun le32(bytes: ByteArray, offset: Int) =
        (bytes[offset].toInt() and 0xFF) or
        ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
        ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
        ((bytes[offset + 3].toInt() and 0xFF) shl 24)

    private fun le16(bytes: ByteArray, offset: Int) =
        (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)

    @Test
    fun `start writes a 48k stereo pcm16 wav header with placeholder sizes`() {
        val recorder = AudioCaptureFileRecorder.start(tmp.newFolder(), 48_000, 2)!!
        recorder.close()

        val bytes = recorder.listOfWrittenBytes(tmp.root)
        assertEquals(44, bytes.size) // header only — nothing was fed
        assertEquals("RIFF", String(bytes, 0, 4))
        assertEquals("WAVE", String(bytes, 8, 4))
        assertEquals("fmt ", String(bytes, 12, 4))
        assertEquals(1, le16(bytes, 20)) // PCM
        assertEquals(2, le16(bytes, 22)) // channels
        assertEquals(48_000, le32(bytes, 24)) // sample rate
        assertEquals(48_000 * 2 * 2, le32(bytes, 28)) // byte rate
        assertEquals(4, le16(bytes, 32)) // block align
        assertEquals(16, le16(bytes, 34)) // bits per sample
        assertEquals("data", String(bytes, 36, 4))
        assertEquals(0, le32(bytes, 40)) // no data written
        assertEquals(36, le32(bytes, 4)) // riff size = header only
    }

    @Test
    fun `closed file's data size matches the bytes fed`() {
        val recorder = AudioCaptureFileRecorder.start(tmp.newFolder(), 48_000, 2)!!
        val frame = byteArrayOf(1, 0, 2, 0) // one stereo PCM16 frame
        repeat(1_000) { recorder.onSamples(frame) }
        recorder.close()

        val bytes = recorder.listOfWrittenBytes(tmp.root)
        assertEquals(44 + 4_000, bytes.size)
        assertEquals(4_000, le32(bytes, 40)) // data chunk size
        assertEquals(36 + 4_000, le32(bytes, 4)) // riff size
    }

    @Test
    fun `feeding past the cap stops writing`() {
        val recorder = AudioCaptureFileRecorder.start(tmp.newFolder(), 48_000, 2)!!
        val big = ByteArray(48_000 * 2 * 2) // exactly one second
        // 121 seconds of feeds — the recorder caps at 120 s.
        repeat(AudioCaptureFileRecorder.MAX_SECONDS + 1) { recorder.onSamples(big) }
        recorder.close()

        val bytes = recorder.listOfWrittenBytes(tmp.root)
        assertEquals(44L + AudioCaptureFileRecorder.MAX_SECONDS * 48_000L * 2 * 2, bytes.size.toLong())
    }

    @Test
    fun `feeding a closed recorder is a no-op`() {
        val recorder = AudioCaptureFileRecorder.start(tmp.newFolder(), 48_000, 2)!!
        recorder.close()
        recorder.onSamples(ByteArray(4_000))
        recorder.close() // idempotent
        val bytes = recorder.listOfWrittenBytes(tmp.root)
        assertEquals(44, bytes.size)
    }

    @Test
    fun `a blocked directory starts nothing`() {
        // A regular file occupies the would-be dump path — start() must give
        // up cleanly (dumping is optional diagnostics).
        val blocker = tmp.newFile("blocker")
        val started = AudioCaptureFileRecorder.start(blocker, 48_000, 2)
        started?.close()
        assertNull(started)
    }

    @Test
    fun `a new dump keeps the total under the cap, oldest first, and touches nothing else`() {
        val dir = tmp.newFolder("dumps")
        // 12 older dumps (creation millis in the name) + an unrelated file.
        val olds = (1..12).map { i -> java.io.File(dir, "capture-dump-${1_000L + i}-48000Hz-2ch.wav").apply { writeBytes(ByteArray(4)) } }
        val unrelated = java.io.File(dir, "notes.txt").apply { writeText("keep me") }
        val recorder = AudioCaptureFileRecorder.start(dir, 48_000, 2)!!
        recorder.close()
        val dumps = dir.listFiles { f -> f.name.startsWith("capture-dump-") }!!.map { it.name }.toSet()
        assertEquals(AudioCaptureFileRecorder.MAX_KEPT_DUMPS, dumps.size)
        assertTrue("the newest old dumps survive", olds.takeLast(AudioCaptureFileRecorder.MAX_KEPT_DUMPS - 1).all { it.name in dumps })
        assertTrue("the oldest are gone", olds.take(3).none { it.name in dumps })
        assertTrue(unrelated.exists())
    }
}

/** Reads the recorder's single dump file back out of [dir]. */
private fun AudioCaptureFileRecorder.listOfWrittenBytes(dir: java.io.File): ByteArray {
    val files = dir.walkTopDown().filter { it.isFile && it.name.startsWith("capture-dump-") }.toList()
    assertTrue("expected exactly one dump file, found ${files.size}", files.size == 1)
    return files.single().readBytes()
}
