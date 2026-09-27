package com.zerofriction.localcast.capture

/**
 * The adaptive-step format rule (Phase17). Quality steps must not touch the
 * capture pipeline: Phase16 measured every rung resizing the VirtualDisplay
 * (`changeCaptureFormat`) as a capture-thread stall + surface-buffer
 * reallocation, with freeze bursts and a jitter-buffer spike around the
 * steps. Instead the VirtualDisplay stays at the cast-start **ceiling** size
 * and a step downscales inside libwebrtc (`VideoSource.adaptOutputFormat` —
 * a GL scale before the encoder). Only rotation still resizes the display,
 * because an aspect flip cannot be produced by downscaling.
 *
 * Pure decision logic so [MediaCastSession]'s format handling is
 * JVM-testable — the session applies exactly what [resolve] returns.
 */
object StepFormat {

    /**
     * What to do with the live pipeline for the current display bounds and
     * quality targets. [rungWidth]/[rungHeight] are the adapter's target —
     * what the encoder receives and what `currentCaptureFormat` reports.
     */
    sealed interface Change {
        /** Nothing moved — unrelated display events cost nothing (Phase14's no-op rule). */
        data object None : Change

        /**
         * Rotation (or any real size change): resize the VirtualDisplay to
         * the ceiling size for the new orientation, then re-apply the rung
         * adaptation (the rung follows the new aspect).
         */
        data class ResizeCapture(
            val captureWidth: Int,
            val captureHeight: Int,
            val rungWidth: Int,
            val rungHeight: Int,
        ) : Change

        /** A quality step: adapt the source only — the display is untouched. */
        data class AdaptRung(val rungWidth: Int, val rungHeight: Int) : Change
    }

    /**
     * The one decision the session makes on a display event or a quality
     * step: compare the *targets* (capture = ceiling, rung = step target,
     * both recomputed from the display's live bounds — the cast-start
     * snapshot goes stale on rotation) against what is in force, and classify.
     */
    fun resolve(
        displayWidth: Int,
        displayHeight: Int,
        ceilingLongEdgePx: Int,
        rungLongEdgePx: Int,
        currentCaptureWidth: Int,
        currentCaptureHeight: Int,
        currentRungWidth: Int,
        currentRungHeight: Int,
    ): Change {
        val (captureWidth, captureHeight) = CaptureSize.scaleTo(ceilingLongEdgePx, displayWidth, displayHeight)
        val (rungWidth, rungHeight) = CaptureSize.scaleTo(rungLongEdgePx, displayWidth, displayHeight)
        val captureChanged =
            captureWidth != currentCaptureWidth || captureHeight != currentCaptureHeight
        val rungChanged = rungWidth != currentRungWidth || rungHeight != currentRungHeight
        return when {
            captureChanged -> Change.ResizeCapture(captureWidth, captureHeight, rungWidth, rungHeight)
            rungChanged -> Change.AdaptRung(rungWidth, rungHeight)
            else -> Change.None
        }
    }
}
