package com.zerofriction.localcast.capture

/**
 * Capture-size math (mobile.md module `capture`), separated from the
 * MediaProjection plumbing so it is plain-Kotlin unit-testable.
 *
 * The cast target is a maximum long edge (720p Phase5): a landscape screen
 * captures at 1280×720, a portrait one at 720×1280. Screens smaller than the
 * target capture at their native size (upscaling would waste encoder bitrate).
 * Both edges are forced even — hardware H.264 encoders reject odd dimensions.
 */
object CaptureSize {

    fun scaleTo(longEdgePxLimit: Int, physicalWidth: Int, physicalHeight: Int): Pair<Int, Int> {
        require(longEdgePxLimit > 0) { "long-edge limit must be positive" }
        val width = even(physicalWidth)
        val height = even(physicalHeight)
        val longEdge = maxOf(width, height)
        if (longEdge <= longEdgePxLimit) return width to height
        val scale = longEdgePxLimit.toDouble() / longEdge
        val scaledLong = longEdgePxLimit
        val scaledShort = even((minOf(width, height) * scale).toInt())
        return if (width >= height) scaledLong to scaledShort else scaledShort to scaledLong
    }

    /** Hardware H.264 encoders reject odd dimensions. */
    private fun even(value: Int): Int = if (value % 2 == 0) value else value - 1

    /**
     * The capture size the cast should use NOW, given the display's live
     * bounds (rotation-aware) and the current quality target — or null when
     * it is unchanged (so listeners can no-op on unrelated display changes).
     *
     * Found live in the Phase14 session (docs/features/obs-streaming.md): the
     * stock `ScreenCapturerAndroid` does NOT follow device rotation — a cast
     * that starts portrait keeps its portrait virtual display forever, and
     * Android squeezes the rotated screen into it. The session re-applies
     * this on every display change; the desktop letterboxes the flip
     * automatically because its layout re-fits on the intrinsic-size change.
     */
    fun followDisplay(
        longEdgePxLimit: Int,
        currentWidth: Int,
        currentHeight: Int,
        displayWidth: Int,
        displayHeight: Int,
    ): Pair<Int, Int>? {
        val target = scaleTo(longEdgePxLimit, displayWidth, displayHeight)
        if (target.first == currentWidth && target.second == currentHeight) return null
        return target
    }
}
