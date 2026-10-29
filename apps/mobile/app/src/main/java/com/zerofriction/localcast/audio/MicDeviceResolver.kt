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
}
