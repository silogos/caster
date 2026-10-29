package com.zerofriction.localcast.ui.home

import com.zerofriction.localcast.adaptive.QualityLevel
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The STATUS header's shorthand readout (designs/mobile-app.html): the
 * p-notation map for the shipped rungs, raw pixels for anything else, and
 * the "p+fps · min–max M" composition — behavior, not implementation.
 */
class ReadoutFormatTest {

    @Test
    fun `p-notation maps the shipped rungs`() {
        assertEquals("1080p", pNotation(1920))
        assertEquals("720p", pNotation(1280))
        assertEquals("540p", pNotation(960))
    }

    @Test
    fun `p-notation falls back to raw pixels off the rungs`() {
        assertEquals("1152px", pNotation(1152))
    }

    @Test
    fun `the readout composes notation, fps and the megabit window`() {
        assertEquals(
            "720p30 · 3–6M",
            formatLiveReadout(QualityLevel(1280, 30, 3_000_000, 6_000_000)),
        )
        assertEquals(
            "1080p60 · 4–8M",
            formatLiveReadout(QualityLevel(1920, 60, 4_000_000, 8_000_000)),
        )
    }

    @Test
    fun `half-megabit floors keep their decimal`() {
        assertEquals(
            "540p30 · 1.5–3M",
            formatLiveReadout(QualityLevel(960, 30, 1_500_000, 3_000_000)),
        )
    }
}
