package io.github.silogos.caster.usb

import io.github.silogos.caster.config.CastDropWindow

/**
 * When a cable cast waits and when it resumes (ADR-036). Pure: the service
 * feeds it the link's events and its own clock, and acts on the decision.
 * A cast resumes only to the desktop it started with, within the window.
 */
class UsbCastDrop(private val desktopId: String, private val windowMs: Long = CastDropWindow.MS) {

    sealed interface Decision {
        /** Pause the media and wait until [endsAtMs]. */
        data class Wait(val sinceMs: Long, val endsAtMs: Long) : Decision
        data object Resume : Decision
        data class End(val reason: String) : Decision
        /** Nothing to do (already waiting, or not waiting). */
        data object Ignore : Decision
    }

    /** When the link dropped; null while the cast runs. */
    var waitingSinceMs: Long? = null
        private set

    fun onLinkLost(nowMs: Long): Decision {
        if (waitingSinceMs != null) return Decision.Ignore
        waitingSinceMs = nowMs
        return Decision.Wait(nowMs, nowMs + windowMs)
    }

    fun onLinkReady(readyDesktopId: String?, nowMs: Long): Decision {
        val since = waitingSinceMs ?: return Decision.Ignore
        return when {
            nowMs - since >= windowMs -> Decision.End("the cable stayed unplugged for the whole window")
            readyDesktopId != desktopId -> Decision.End("another desktop on the cable")
            else -> {
                waitingSinceMs = null
                Decision.Resume
            }
        }
    }

    fun onTick(nowMs: Long): Decision {
        val since = waitingSinceMs ?: return Decision.Ignore
        return if (nowMs - since >= windowMs) Decision.End("the cable stayed unplugged for the whole window") else Decision.Ignore
    }
}
