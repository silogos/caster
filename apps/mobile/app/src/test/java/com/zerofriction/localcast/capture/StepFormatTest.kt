package com.zerofriction.localcast.capture

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The Phase17 adaptive-step decision rule: a quality step adapts the source
 * only (the VirtualDisplay stays at the ceiling), rotation resizes it, and
 * anything that would change nothing is a no-op.
 */
class StepFormatTest {

    /** A 2560×1440 tablet at a sharp-ceiling cast that stepped down to cool. */
    @Test
    fun `quality step down adapts the source and leaves the capture alone`() {
        val change = StepFormat.resolve(
            displayWidth = 2560, displayHeight = 1440,
            ceilingLongEdgePx = 1920, rungLongEdgePx = 960,
            currentCaptureWidth = 1920, currentCaptureHeight = 1080,
            currentRungWidth = 1920, currentRungHeight = 1080,
        )
        assertEquals(StepFormat.Change.AdaptRung(960, 540), change)
    }

    @Test
    fun `stepping back up to the ceiling returns to no adaptation`() {
        val change = StepFormat.resolve(
            displayWidth = 2560, displayHeight = 1440,
            ceilingLongEdgePx = 1920, rungLongEdgePx = 1920,
            currentCaptureWidth = 1920, currentCaptureHeight = 1080,
            currentRungWidth = 960, currentRungHeight = 540,
        )
        assertEquals(StepFormat.Change.AdaptRung(1920, 1080), change)
    }

    @Test
    fun `a resolution-only restore to the already-captured size is a no-op`() {
        val change = StepFormat.resolve(
            displayWidth = 2560, displayHeight = 1440,
            ceilingLongEdgePx = 1920, rungLongEdgePx = 1920,
            currentCaptureWidth = 1920, currentCaptureHeight = 1080,
            currentRungWidth = 1920, currentRungHeight = 1080,
        )
        assertEquals(StepFormat.Change.None, change)
    }

    @Test
    fun `an fps-only rung change touches nothing`() {
        // Custom 1920/60 → sharp 1920/30: same dimensions, fps is sender-side.
        val change = StepFormat.resolve(
            displayWidth = 2560, displayHeight = 1440,
            ceilingLongEdgePx = 1920, rungLongEdgePx = 1920,
            currentCaptureWidth = 1920, currentCaptureHeight = 1080,
            currentRungWidth = 1920, currentRungHeight = 1080,
        )
        assertEquals(StepFormat.Change.None, change)
    }

    @Test
    fun `rotation at the ceiling resizes the capture and re-applies the rung`() {
        val change = StepFormat.resolve(
            displayWidth = 1440, displayHeight = 2560,
            ceilingLongEdgePx = 1920, rungLongEdgePx = 1920,
            currentCaptureWidth = 1920, currentCaptureHeight = 1080,
            currentRungWidth = 1920, currentRungHeight = 1080,
        )
        assertEquals(StepFormat.Change.ResizeCapture(1080, 1920, 1080, 1920), change)
    }

    @Test
    fun `rotation while stepped down keeps the capture at the ceiling and re-adapts the rung`() {
        val change = StepFormat.resolve(
            displayWidth = 1440, displayHeight = 2560,
            ceilingLongEdgePx = 1920, rungLongEdgePx = 960,
            currentCaptureWidth = 1920, currentCaptureHeight = 1080,
            currentRungWidth = 960, currentRungHeight = 540,
        )
        assertEquals(StepFormat.Change.ResizeCapture(1080, 1920, 540, 960), change)
    }

    @Test
    fun `unrelated display events are a no-op`() {
        // Same orientation, same bounds — brightness/state changes arrive the same way.
        val change = StepFormat.resolve(
            displayWidth = 2560, displayHeight = 1440,
            ceilingLongEdgePx = 1920, rungLongEdgePx = 960,
            currentCaptureWidth = 1920, currentCaptureHeight = 1080,
            currentRungWidth = 960, currentRungHeight = 540,
        )
        assertEquals(StepFormat.Change.None, change)
    }

    @Test
    fun `a 180 degree rotation is a no-op`() {
        // The display bounds do not change for a half-turn — nothing to re-apply.
        val change = StepFormat.resolve(
            displayWidth = 2560, displayHeight = 1440,
            ceilingLongEdgePx = 1920, rungLongEdgePx = 960,
            currentCaptureWidth = 1920, currentCaptureHeight = 1080,
            currentRungWidth = 960, currentRungHeight = 540,
        )
        assertEquals(StepFormat.Change.None, change)
    }

    @Test
    fun `native screens never upscale and stay at both sizes`() {
        val change = StepFormat.resolve(
            displayWidth = 600, displayHeight = 400,
            ceilingLongEdgePx = 1920, rungLongEdgePx = 960,
            currentCaptureWidth = 600, currentCaptureHeight = 400,
            currentRungWidth = 600, currentRungHeight = 400,
        )
        assertEquals(StepFormat.Change.None, change)
    }

    @Test
    fun `odd display dimensions come out even in both targets`() {
        val change = StepFormat.resolve(
            displayWidth = 2561, displayHeight = 1441,
            ceilingLongEdgePx = 1920, rungLongEdgePx = 960,
            currentCaptureWidth = 600, currentCaptureHeight = 400,
            currentRungWidth = 600, currentRungHeight = 400,
        )
        assertEquals(StepFormat.Change.ResizeCapture(1920, 1080, 960, 540), change)
    }
}
