package com.zerofriction.localcast.ui.pairing

/**
 * Decides which camera detections reach the pairing flow. The camera sees
 * the same code ~30 times a second; delivering every frame would restart a
 * failed attempt at frame rate. The first version delivered ONCE per screen,
 * so after a wrong QR ("not a Caster code") the right one was ignored
 * until the user left the screen (review 2026-11-09).
 *
 * Rule: a different code is delivered at once; the same code again only
 * after [sameCodeRetryMs] — a retry of a code that failed (network blip)
 * still works without leaving the screen.
 */
class ScanDeliveryGate(
    private val clock: () -> Long = System::currentTimeMillis,
    private val sameCodeRetryMs: Long = SAME_CODE_RETRY_MS,
) {
    private var lastText: String? = null
    private var lastAtMs = 0L

    fun shouldDeliver(text: String): Boolean {
        val now = clock()
        if (text == lastText && now - lastAtMs < sameCodeRetryMs) return false
        lastText = text
        lastAtMs = now
        return true
    }

    companion object {
        const val SAME_CODE_RETRY_MS = 2_000L
    }
}
