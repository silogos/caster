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
}
