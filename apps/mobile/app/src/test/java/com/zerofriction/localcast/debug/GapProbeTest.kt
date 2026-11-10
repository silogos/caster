package com.zerofriction.localcast.debug

import org.junit.Assert.assertEquals
import org.junit.Test

class GapProbeTest {

    private var nowMs = 0L
    private val lines = mutableListOf<String>()
    private val probe = GapProbe("audio read", nowNs = { nowMs * 1_000_000 }, report = { lines += it })

    private fun eventAt(ms: Long) {
        nowMs = ms
        probe.onEvent()
    }

    @Test
    fun `a steady stream reports its period once a second`() {
        for (t in 1L..1_001L step 10) eventAt(t)
        assertEquals(listOf("audio read gap n=101 max=10.0 ms, long=0"), lines)
    }

    @Test
    fun `a late event shows as the longest pause and is counted`() {
        for (t in 1L..400L step 10) eventAt(t) // last at 391
        eventAt(446) // held up 55 ms
        for (t in 456L..1_006L step 10) eventAt(t)
        assertEquals(listOf("audio read gap n=97 max=55.0 ms, long=1"), lines)
    }

    @Test
    fun `each second starts over, and a pause across the boundary still counts`() {
        for (t in 1L..1_001L step 10) eventAt(t)
        eventAt(1_041) // 40 ms after the event that closed the first second
        for (t in 1_051L..2_001L step 10) eventAt(t)
        assertEquals("audio read gap n=97 max=40.0 ms, long=1", lines[1])
    }
}
