package io.github.silogos.caster.usb

import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.view.Surface

/**
 * The cable cast's one VirtualDisplay (ADR-036): created once per cast and
 * kept while the cable is out, because Android 14+ allows one
 * `createVirtualDisplay` per projection. Each encoder shows its input
 * surface on it; between encoders it has none, so nothing is captured.
 * Thread-safe: an encoder's drain thread detaches its own surface.
 */
class UsbScreen(projection: MediaProjection, width: Int, height: Int, densityDpi: Int) {
    private val display: VirtualDisplay = projection.createVirtualDisplay(
        "caster-usb", width, height, densityDpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, null, null, null,
    ) ?: throw IllegalStateException("the projection refused a virtual display")
    private var shown: Surface? = null
    private var released = false

    /** The screen goes to [surface] from now on. */
    @Synchronized
    fun show(surface: Surface) {
        check(!released) { "the cast's screen is released" }
        display.surface = surface
        shown = surface
    }

    /** Stops feeding [surface] — only if it is still the one shown (a newer encoder may own the display). */
    @Synchronized
    fun detach(surface: Surface) {
        if (released || shown !== surface) return
        display.surface = null
        shown = null
    }

    @Synchronized
    fun release() {
        if (released) return
        released = true
        display.surface = null
        shown = null
        display.release()
    }
}
