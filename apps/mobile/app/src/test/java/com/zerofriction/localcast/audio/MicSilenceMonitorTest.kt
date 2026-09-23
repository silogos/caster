package com.zerofriction.localcast.audio

import com.zerofriction.localcast.audio.MicSilenceMonitor.Recording
import com.zerofriction.localcast.audio.MicSilenceMonitor.Signal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The arbitration state machine behind the mic UI (ADR-004, audio.md): the
 * platform's recording configs decide whether *our* capture was silenced by
 * a higher-priority recorder; "ours" is matched by audio source, the
 * playback-capture record never counts, and recovery is automatic.
 */
class MicSilenceMonitorTest {

    private val ourSource = 7 // VOICE_COMMUNICATION, the factory-B source

    private fun monitor(): Triple<MicSilenceMonitor, MutableList<Signal>, MutableList<String>> {
        val signals = mutableListOf<Signal>()
        val logs = mutableListOf<String>()
        val m = MicSilenceMonitor(
            ourSource = ourSource,
            log = { logs.add(it) },
            onSignal = { signals.add(it) },
        )
        return Triple(m, signals, logs)
    }

    private fun source(src: Int) = Recording(source = src, silenced = false)

    @Test
    fun `starts active and publishes nothing until a transition happens`() {
        val (m, signals, _) = monitor()
        assertEquals(MicState.Active, m.state)
        assertTrue(signals.isEmpty())
    }

    @Test
    fun `another app silenced on a different source does not touch us`() {
        val (m, signals, _) = monitor()
        // A recorder that lost the arbitration to our capture.
        m.onRecordings(listOf(Recording(source = 1, silenced = true), source(ourSource)))
        assertEquals(MicState.Active, m.state)
        assertTrue(signals.isEmpty())
    }

    @Test
    fun `our source silenced becomes silenced with the diagnostics line`() {
        val (m, signals, logs) = monitor()
        m.onRecordings(listOf(source(ourSource)))
        m.onRecordings(listOf(Recording(source = ourSource, silenced = true)))
        assertEquals(MicState.Silenced, m.state)
        assertEquals(listOf(Signal.OwnSourceSilenced), signals)
        // The log must carry the metadata needed to diagnose on device.
        assertTrue(logs.last().contains("sources=[7]"))
        assertTrue(logs.last().contains("silenced=[7]"))
    }

    @Test
    fun `the winner stopping recording recovers us automatically`() {
        val (m, signals, _) = monitor()
        m.onRecordings(listOf(Recording(source = ourSource, silenced = true)))
        assertEquals(MicState.Silenced, m.state)
        m.onRecordings(listOf(source(ourSource)))
        assertEquals(MicState.Active, m.state)
        assertEquals(listOf(Signal.OwnSourceSilenced, Signal.OwnSourceCleared), signals)
    }

    @Test
    fun `the playback-capture record is never part of the decision`() {
        val (m, signals, _) = monitor()
        // Factory A's substituted record reports as REMOTE_SUBMIX; even a
        // "silenced" entry there (it is not arbitrated like the mic) must be
        // ignored, and it must not mask the mic-source list in the log.
        m.onRecordings(listOf(Recording(source = MicSilenceMonitor.REMOTE_SUBMIX, silenced = true)))
        assertEquals(MicState.Active, m.state)
        assertTrue(signals.isEmpty())
        m.onRecordings(
            listOf(
                Recording(source = MicSilenceMonitor.REMOTE_SUBMIX, silenced = true),
                Recording(source = ourSource, silenced = true),
            ),
        )
        assertEquals(MicState.Silenced, m.state)
    }

    @Test
    fun `repeating the same config publishes nothing`() {
        val (m, signals, _) = monitor()
        val silenced = listOf(Recording(source = ourSource, silenced = true))
        m.onRecordings(silenced)
        m.onRecordings(silenced)
        assertEquals(listOf(Signal.OwnSourceSilenced), signals)
    }
}
