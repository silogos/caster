package com.zerofriction.localcast.webrtc

import com.zerofriction.localcast.config.CastConfig

/**
 * How low the video sender may go (ADR-023).
 *
 * A bitrate window's floor was a hard floor: libwebrtc may not send less,
 * whatever the uplink can carry. Measured live (2026-11-11, latency-measurement.md):
 * with the floor at 8 Mbps and the uplink carrying 3–8, the video queued up in
 * the sender (up to 590 ms per packet), the frame rate fell to 16–37 fps, and
 * the game audio, which shares the transport, starved on the desktop.
 *
 * "Keep frame rate" promises a cast that stays smooth and drops detail. It can
 * only drop detail if the bitrate may fall, so under that strategy the floor
 * is [KEEP_FRAME_RATE_FLOOR_BPS] and the window's own floor is where the cast
 * *starts* ([startBitrateBps]). The other strategies keep the window's floor:
 * "Keep resolution" is the user's choice of detail over smoothness.
 */
fun effectiveBitrateFloorBps(windowFloorBps: Int, degradationPreference: String): Int =
    if (followsTheNetwork(degradationPreference)) minOf(windowFloorBps, KEEP_FRAME_RATE_FLOOR_BPS) else windowFloorBps

/**
 * Where the bandwidth estimate starts when the floor no longer holds it up:
 * at the window's floor, so a healthy link looks the same from the first
 * second. Null when the floor itself does that job.
 */
fun startBitrateBps(windowFloorBps: Int, degradationPreference: String): Int? =
    windowFloorBps.takeIf { effectiveBitrateFloorBps(windowFloorBps, degradationPreference) < windowFloorBps }

private fun followsTheNetwork(degradationPreference: String): Boolean =
    degradationPreference == CastConfig.DEGRADATION_FRAMERATE

/**
 * Low enough to follow an uplink that carried 1.6–3.4 Mbps at its worst in the
 * measured cast; still a recognisable picture once libwebrtc has scaled the
 * frame down, which is what "Keep frame rate" asks it to do.
 */
const val KEEP_FRAME_RATE_FLOOR_BPS = 1_000_000
