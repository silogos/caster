package com.zerofriction.localcast.capture

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 720p capture sizing (webrtc.md): long edge capped, aspect kept, small
 * screens native, everything even for the hardware encoder.
 */
class CaptureSizeTest {

    @Test
    fun `landscape screen scales the long edge to the limit`() {
        assertEquals(1280 to 720, CaptureSize.scaleTo(1280, 2560, 1440))
    }

    @Test
    fun `portrait screen keeps orientation`() {
        assertEquals(720 to 1280, CaptureSize.scaleTo(1280, 1440, 2560))
    }

    @Test
    fun `odd physical dimensions come out even before scaling`() {
        assertEquals(1280 to 720, CaptureSize.scaleTo(1280, 2561, 1441))
    }

    @Test
    fun `screens at the target capture native`() {
        assertEquals(1280 to 720, CaptureSize.scaleTo(1280, 1280, 720))
    }

    @Test
    fun `screens smaller than the target are not upscaled`() {
        assertEquals(600 to 400, CaptureSize.scaleTo(1280, 600, 400))
    }

    // ---- following the display live (Phase14 rotation fix) ----

    @Test
    fun `portrait capture follows the display rotating to landscape`() {
        val target = CaptureSize.followDisplay(
            longEdgePxLimit = 1280, currentWidth = 720, currentHeight = 1280,
            displayWidth = 2560, displayHeight = 1440,
        )
        assertEquals(1280 to 720, target)
    }

    @Test
    fun `landscape capture follows the display rotating to portrait`() {
        val target = CaptureSize.followDisplay(
            longEdgePxLimit = 1280, currentWidth = 1280, currentHeight = 720,
            displayWidth = 1440, displayHeight = 2560,
        )
        assertEquals(720 to 1280, target)
    }

    @Test
    fun `unrelated display changes are a no-op when the size would not change`() {
        // Same orientation, same bounds — brightness/state changes arrive the same way.
        val target = CaptureSize.followDisplay(
            longEdgePxLimit = 1280, currentWidth = 1280, currentHeight = 720,
            displayWidth = 2560, displayHeight = 1440,
        )
        assertEquals(null, target)
    }

    @Test
    fun `a 180 degree rotation keeps the capture size`() {
        // The display bounds do not change for a half-turn — nothing to re-apply.
        val target = CaptureSize.followDisplay(
            longEdgePxLimit = 1280, currentWidth = 1280, currentHeight = 720,
            displayWidth = 2560, displayHeight = 1440,
        )
        assertEquals(null, target)
    }

    @Test
    fun `follows the live quality target, not the cast-start one`() {
        // Auto quality stepped down to a 720px long edge; the display then rotates.
        val target = CaptureSize.followDisplay(
            longEdgePxLimit = 720, currentWidth = 720, currentHeight = 1280,
            displayWidth = 2560, displayHeight = 1440,
        )
        assertEquals(720 to 404, target)
    }

    @Test
    fun `small native screens keep their size through rotation`() {
        val target = CaptureSize.followDisplay(
            longEdgePxLimit = 1280, currentWidth = 600, currentHeight = 400,
            displayWidth = 400, displayHeight = 600,
        )
        assertEquals(400 to 600, target)
    }
}
