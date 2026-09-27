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
}
