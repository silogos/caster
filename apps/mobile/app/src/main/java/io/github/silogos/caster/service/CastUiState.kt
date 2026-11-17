package io.github.silogos.caster.service

import io.github.silogos.caster.adaptive.AdaptiveQualityController
import io.github.silogos.caster.adaptive.QualityLevel
import io.github.silogos.caster.config.MicDeviceSource
import io.github.silogos.caster.signaling.AudioRoutingStatus
import io.github.silogos.caster.signaling.Envelope
import io.github.silogos.caster.signaling.VcStatus

/**
 * The cast pipeline's user-facing state, owned by [CastService] — the home and
 * scan screens render it 1:1. Errors carry the simple user-facing message only
 * (AGENTS.md); technical detail lives in the service/session logs. The
 * desktop's name rides along so any screen (and the notification) can say who
 * is being cast to without asking the pairing machine, which handed its
 * connection over when the cast started.
 */
/** Which link a cast runs over: the waiting state words it differently (ADR-036). */
enum class CastLink { WIFI, CABLE }

sealed interface CastState {
    data object Idle : CastState
    data class Starting(val desktopName: String) : CastState

    /** [startedAtMillis] feeds the Home hero's live elapsed readout (wall
     * clock at the streaming transition; 0 = unknown, the UI falls back). */
    data class Casting(val desktopName: String, val startedAtMillis: Long = 0L) : CastState

    /**
     * ADR-036: the link dropped mid-cast and the cast waits for it, the
     * projection kept, until [endsAtMillis] (the drop window after
     * [sinceMillis]). Back to [Casting] (same [startedAtMillis]) when the link
     * returns; [Failed] when the window runs out.
     */
    data class Interrupted(
        val desktopName: String,
        val link: CastLink,
        val sinceMillis: Long,
        val endsAtMillis: Long,
        val startedAtMillis: Long = 0L,
    ) : CastState
    data class Failed(val message: String) : CastState
}

/** A cast exists: streaming, or waiting for its link (ADR-036). */
fun CastState.isLive(): Boolean = this is CastState.Casting || this is CastState.Interrupted

fun CastState.liveDesktopName(): String? = when (this) {
    is CastState.Casting -> desktopName
    is CastState.Interrupted -> desktopName
    else -> null
}

/** The running cast's start, kept across a wait (the STATUS timecode). */
fun CastState.liveStartedAtMillis(): Long? = when (this) {
    is CastState.Casting -> startedAtMillis
    is CastState.Interrupted -> startedAtMillis
    else -> null
}

/**
 * The audio output routing's UI half (proto 2): the phone's mirror of the
 * desktop's receiver setting (speakers / virtual cable / both). Null until a
 * status arrives — old desktops (proto 1) never send one, so the row stays
 * hidden there.
 */
data class AudioRoutingUiState(
    val mode: String = Envelope.AUDIO_ROUTING_SPEAKERS,
    val cableAvailable: Boolean = false,
    val known: Boolean = false,
)

/** `audio-routing-status` → the UI state — pure, JVM-tested. */
internal fun AudioRoutingStatus.toUiState(): AudioRoutingUiState =
    AudioRoutingUiState(mode = mode, cableAvailable = cableAvailable, known = true)

/**
 * The live mic routing while a mic session records (the Home picker's
 * display state): which kind is actually capturing and, when the selected
 * kind wasn't connected, which kind fell back. Null while the mic is off.
 */
data class MicRoutingUiState(val effective: MicDeviceSource, val fallbackFrom: MicDeviceSource? = null)

/**
 * The virtual camera's UI half (ADR-007, proto 2): what the home screen shows
 * while casting. [VcCapability.Hidden] — the default — hides the card
 * entirely: no vc-status yet (old desktop, proto 1) or the desktop reported
 * itself unavailable (non-Windows-x64, DLL missing).
 */
enum class VcCapability { Hidden, NeedsSetup, Ready }

data class VirtualCameraUiState(
    val capability: VcCapability = VcCapability.Hidden,
    val active: Boolean = false,
)

/** `vc-status` (webrtc.md proto 2) → the toggle's UI state — pure, JVM-tested. */
internal fun VcStatus.toUiState(): VirtualCameraUiState = when {
    !available -> VirtualCameraUiState(VcCapability.Hidden)
    needsRegistration -> VirtualCameraUiState(VcCapability.NeedsSetup)
    else -> VirtualCameraUiState(VcCapability.Ready, active = active)
}

/**
 * The auto-quality half of the cast's state (Phase12, thermal.md): what the
 * running cast's quality is right now, what it may go back up to (the user's
 * settings at cast start — the ceiling), and the last transition (the
 * "what happened" line the home/scan screens render). Blank state = no cast,
 * or the user's switch turned auto quality off.
 */
data class AdaptiveUiState(
    val current: QualityLevel? = null,
    val ceiling: QualityLevel? = null,
    val lastChange: AdaptiveQualityController.QualityChange? = null,
)
