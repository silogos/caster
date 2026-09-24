package com.zerofriction.localcast.webrtc

/**
 * The sender-side framerate cap that makes a profile's (or an adaptive step's)
 * fps *real* — Phase16's live-found fix.
 *
 * Screen capture cannot cap fps: `MediaProjection` produces display-paced
 * frames and the stock screencast capturer (our pinned-class port included)
 * legitimately ignores the fps argument of `startCapture`/`changeCaptureFormat`.
 * Measured live (2026-09-24, Phase16 baseline session): a cast stepped down to
 * a 30 fps target kept encoding 61 fps — the thermal staircase's fps rung was
 * a placebo, the encoder doing double the intended per-second work. The one
 * mechanism that reaches the actual encode rate is
 * [org.webrtc.RtpParameters.Encoding.maxFramerate] on the video sender.
 *
 * The user's own Advanced "Encoder fps limit" (a receiver of last resort,
 * default off) still wins when it is the tighter of the two — it can only ever
 * lower the rate, never raise it past the profile's intent.
 */
fun effectiveEncoderFpsCap(liveFps: Int, userFpsLimit: Int?): Int =
    userFpsLimit?.let { it.coerceAtMost(liveFps) } ?: liveFps
