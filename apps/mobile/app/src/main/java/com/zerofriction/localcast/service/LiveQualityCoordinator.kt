package com.zerofriction.localcast.service

import android.util.Log
import com.zerofriction.localcast.adaptive.AdaptiveQualityController
import com.zerofriction.localcast.adaptive.QualityLevel
import com.zerofriction.localcast.config.StrugglePolicy
import com.zerofriction.localcast.config.matchingProfile
import com.zerofriction.localcast.thermal.ThermalStatus
import com.zerofriction.localcast.webrtc.SenderSample
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * The running cast's quality (Phase12, thermal.md): what is sent right now
 * ([liveQuality] — the STATUS readout, populated even with auto quality off),
 * the auto-quality policy when the user left it on ([adaptiveState]), and
 * the encoder libwebrtc picked ([encoderImplementation]).
 *
 * Two writers change the level, both through [applyLevel] (the service
 * applies it on the media session and keeps the desktop's display-only
 * `session-info` in step):
 *  - the policy: every ~1 Hz sender sample plus the latest thermal reading
 *    may produce a change, announced through [onAdapted] (notification text);
 *  - the user's live pick (the quality console): applied the same way, and
 *    auto quality — when on — is **reset** onto it (a fresh controller with
 *    the pick as its ceiling: clean slate, hysteresis restarted).
 * The user's persisted settings are never touched — this lasts until the cast ends.
 */
internal class LiveQualityCoordinator(
    private val liveQuality: MutableStateFlow<QualityLevel?>,
    private val adaptiveState: MutableStateFlow<AdaptiveUiState>,
    private val encoderImplementation: MutableStateFlow<String?>,
    private val thermalStatus: () -> ThermalStatus,
    private val applyLevel: (QualityLevel) -> Unit,
    private val onAdapted: (AdaptiveQualityController.QualityChange) -> Unit,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    /** Null without a cast. With "Don't adjust" it runs too, with no step to take. */
    private var controller: AdaptiveQualityController? = null

    /** The running cast's choice of what to give up. */
    private var policy: StrugglePolicy = StrugglePolicy.FIXED

    /** The ceiling auto quality may climb back to — the user's settings at cast start (or their live pick). */
    private var ceiling: QualityLevel? = null

    /** Cast start: the readout's baseline is the profile's values; the rule steps from them the way [policy] says. */
    fun start(startLevel: QualityLevel, policy: StrugglePolicy) {
        this.policy = policy
        liveQuality.value = startLevel
        startPolicy(startLevel)
    }

    /** The policy's stats input — called from the media session thread. */
    fun onSample(sample: SenderSample) {
        encoderImplementation.value = sample.encoderImplementation
        val policy = controller ?: return
        policy.onTick(
            nowMs(),
            // Cumulative counters; the (pure, tested) controller turns their
            // deltas into the symptoms it acts on.
            AdaptiveQualityController.StreamSample(
                framesEncoded = sample.framesEncoded,
                framesDropped = sample.framesDropped,
                totalEncodeTimeSeconds = sample.totalEncodeTimeSeconds,
                totalPacketSendDelaySeconds = sample.totalPacketSendDelaySeconds,
                packetsSent = sample.packetsSent,
                targetBitrateBps = sample.targetBitrateBps,
            ),
            thermalStatus(),
        )
    }

    /** The user puts the cast back at their own settings (auto quality is reversible). */
    fun restore() {
        controller?.restore(nowMs())
    }

    fun applyUserLevel(level: QualityLevel) {
        applyLevel(level)
        liveQuality.value = level
        startPolicy(level)
        Log.i(TAG, "user live quality: ${describe(level)}")
    }

    /** Cast teardown: the policy and every published readout. */
    fun stop() {
        controller = null
        ceiling = null
        adaptiveState.value = AdaptiveUiState()
        liveQuality.value = null
        encoderImplementation.value = null
    }

    private fun startPolicy(top: QualityLevel) {
        // The ceiling: auto quality never goes above what the user chose (thermal.md principle 2).
        ceiling = top
        controller = AdaptiveQualityController(ceiling = top, policy = policy, onLevelChanged = { onPolicyChange(it) })
        adaptiveState.value = AdaptiveUiState(current = top, ceiling = top)
    }

    private fun onPolicyChange(change: AdaptiveQualityController.QualityChange) {
        // One INFO line per change is the policy's announcement duty (thermal.md).
        Log.i(TAG, "adaptive quality: ${change.direction} (${change.reason}) — ${describe(change.from)} → ${describe(change.to)}")
        applyLevel(change.to)
        liveQuality.value = change.to
        adaptiveState.value = AdaptiveUiState(current = change.to, ceiling = ceiling, lastChange = change)
        onAdapted(change)
    }

    private fun describe(level: QualityLevel): String =
        matchingProfile(level.longEdgePx, level.fps, level.bitrateMinBps, level.bitrateMaxBps)?.label
            ?: "custom ${level.longEdgePx}px/${level.fps}fps"

    private companion object {
        const val TAG = "CastService"
    }
}
