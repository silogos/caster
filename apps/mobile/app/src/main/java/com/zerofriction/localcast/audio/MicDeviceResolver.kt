package com.zerofriction.localcast.audio

import android.media.AudioDeviceInfo
import com.zerofriction.localcast.config.MicDeviceSource

/**
 * Resolves a stored [MicDeviceSource] (config module) against the input
 * devices the phone currently reports, to the concrete AudioManager device
 * type the capture should route to.
 *
 * Pure on device types (ints) so the resolution rules are unit-testable;
 * `AudioDeviceInfo.TYPE_*` are compile-time constants. The mapping is a kind
 * → candidate types in preference order, because a kind can span several
 * platform types (USB headsets are `USB_HEADSET`, plain USB mics are
 * `USB_DEVICE`, older accessories `USB_ACCESSORY`).
 *
 * The fallback is always the built-in mic — the device the ADR-004 story was
 * validated on — so a selected kind that isn't plugged in degrades to the
 * default capture instead of failing the mic session.
 */
object MicDeviceResolver {

    /** The default and fallback capture device (ADR-004). */
    const val FALLBACK_TYPE = AudioDeviceInfo.TYPE_BUILTIN_MIC

    /**
     * ADR-004 round 2: the rate the **built-in** capture shares with on-device
     * voice chat — PUBG captures 16 kHz on the builtin mic (dumpsys evidence,
     * 2026-09-23), and running at the same rate keeps the cast mic aligned
     * with the shared HAL path when both capture concurrently. This contract
     * is builtin-specific: it does **not** extend to external kinds (round 4).
     */
    const val SHARED_VOICE_INPUT_RATE_HZ = 16_000

    /**
     * ADR-004 round 4: the native rate of wired and USB input paths (USB
     * Audio Class and every wired-headset HAL profile run 48 kHz). Forcing
     * the builtin contract's 16 kHz onto these paths while a game captures
     * concurrently made the audio policy reconfigure the shared input
     * profile and mis-feed *both* captures (chipmunk on the cast mic and the
     * game's voice chat, found live 2026-11-02) — the record asks for the
     * path's native rate instead, so each input keeps its own profile.
     */
    const val USB_ANALOG_INPUT_RATE_HZ = 48_000

    /**
     * ADR-004 round 4: the HFP wideband ceiling. Bluetooth SCO is a
     * phone-call link (8 kHz narrowband / 16 kHz wideband), so 16 kHz is the
     * honest client rate for it — the framework resamples narrowband up.
     * Unverified on device (the SCO caveat in the UI stands).
     */
    const val HFP_VOICE_INPUT_RATE_HZ = 16_000

    /**
     * The input device types each kind accepts, in preference order.
     * Output-only twins are deliberately absent (wired headphones, Bluetooth
     * A2DP have no microphone).
     */
    private val TYPES_BY_SOURCE: Map<MicDeviceSource, List<Int>> = mapOf(
        MicDeviceSource.BUILTIN to listOf(AudioDeviceInfo.TYPE_BUILTIN_MIC),
        MicDeviceSource.WIRED_HEADSET to listOf(AudioDeviceInfo.TYPE_WIRED_HEADSET),
        MicDeviceSource.USB to listOf(
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_USB_DEVICE,
            AudioDeviceInfo.TYPE_USB_ACCESSORY,
        ),
        MicDeviceSource.BLUETOOTH to listOf(AudioDeviceInfo.TYPE_BLUETOOTH_SCO),
    )

    /** The platform input types a kind maps to, in preference order. */
    fun acceptedTypes(source: MicDeviceSource): List<Int> = TYPES_BY_SOURCE.getValue(source)

    /**
     * The device type to route to for a selected kind, given the types of
     * the currently connected input devices. A kind that isn't connected
     * (or BUILTIN) resolves to [FALLBACK_TYPE].
     */
    fun resolveType(selected: MicDeviceSource, connectedTypes: Collection<Int>): Int {
        if (selected == MicDeviceSource.BUILTIN) return FALLBACK_TYPE
        return acceptedTypes(selected).firstOrNull { it in connectedTypes } ?: FALLBACK_TYPE
    }

    /**
     * The kind a routed device type belongs to — the reverse half the UI's
     * "Recording from …" line needs (a wired type renders as the wired kind).
     * Unknown types are the fallback kind, never a crash.
     */
    fun kindForType(type: Int): MicDeviceSource =
        TYPES_BY_SOURCE.entries.firstOrNull { (_, types) -> type in types }?.key ?: MicDeviceSource.BUILTIN

    /**
     * The capture contract rate for a kind (ADR-004 round 4 — per-device,
     * not one-size): builtin keeps the game-shared 16 kHz; wired/USB record
     * at their native 48 kHz; Bluetooth SCO at the HFP wideband ceiling.
     * Callers pass the **resolved** kind (a selected-but-unconnected kind
     * falls back to builtin and must keep the builtin contract).
     */
    fun captureRateHz(kind: MicDeviceSource): Int = when (kind) {
        MicDeviceSource.BUILTIN -> SHARED_VOICE_INPUT_RATE_HZ
        MicDeviceSource.WIRED_HEADSET, MicDeviceSource.USB -> USB_ANALOG_INPUT_RATE_HZ
        MicDeviceSource.BLUETOOTH -> HFP_VOICE_INPUT_RATE_HZ
    }

    /**
     * Whether a running record at [recordRateHz] can take [source] with a
     * plain `setPreferredDevice` (live reroute): only when the resolved kind
     * of the new selection captures at the same rate the record already
     * runs — an `AudioRecord`'s rate is fixed at creation, so a cross-rate
     * switch (e.g. builtin ↔ USB) needs a mic-session rebuild instead.
     */
    fun isLiveReroutable(recordRateHz: Int, source: MicDeviceSource, connectedTypes: Collection<Int>): Boolean =
        captureRateHz(kindForType(resolveType(source, connectedTypes))) == recordRateHz
}
