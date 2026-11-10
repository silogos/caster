package com.zerofriction.localcast.webrtc

import android.media.MediaFormat

/**
 * The MediaCodec keys that ask a hardware video encoder for low latency
 * (ADR-018) — what scrcpy sets on the same encoders:
 *  - [MediaFormat.KEY_LATENCY] = 1: emit each frame after one queued input
 *    frame, instead of holding several for rate-control look-ahead
 *    (API 26; the codec ignores it if unsupported).
 *  - [MediaFormat.KEY_PRIORITY] = 0: realtime — the codec should keep up with
 *    the frame rate rather than run as a best-effort background job (API 23).
 *
 * Deliberately not set: KEY_OPERATING_RATE (it can raise hardware clocks —
 * more heat for no measured gain yet) and intra-refresh (changes the bitstream;
 * a separate decision).
 */
object EncoderLatencyKeys {
    const val ONE_FRAME = 1
    const val REALTIME = 0

    fun hardware(): Map<String, Int> = mapOf(
        MediaFormat.KEY_LATENCY to ONE_FRAME,
        MediaFormat.KEY_PRIORITY to REALTIME,
    )
}
