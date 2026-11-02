package com.zerofriction.localcast.audio

import android.media.AudioDeviceInfo
import com.zerofriction.localcast.config.MicDeviceSource
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The mic device resolution rules (settings → capture device type): a kind
 * maps to the first of its accepted input types that is actually connected,
 * and anything unresolvable degrades to the built-in mic — a missing headset
 * never fails a mic session (audio.md).
 */
class MicDeviceResolverTest {

    @Test
    fun `the built-in mic always resolves to the built-in type`() {
        assertEquals(
            AudioDeviceInfo.TYPE_BUILTIN_MIC,
            MicDeviceResolver.resolveType(MicDeviceSource.BUILTIN, emptySet()),
        )
    }

    @Test
    fun `a wired headset resolves to the headset input`() {
        val connected = setOf(AudioDeviceInfo.TYPE_BUILTIN_MIC, AudioDeviceInfo.TYPE_WIRED_HEADSET)
        assertEquals(AudioDeviceInfo.TYPE_WIRED_HEADSET, MicDeviceResolver.resolveType(MicDeviceSource.WIRED_HEADSET, connected))
    }

    @Test
    fun `wired headphones are not a mic choice`() {
        // TYPE_WIRED_HEADPHONES has no microphone — it must not satisfy the
        // headset kind, and the resolution falls back to the built-in mic.
        val connected = setOf(AudioDeviceInfo.TYPE_BUILTIN_MIC, AudioDeviceInfo.TYPE_WIRED_HEADPHONES)
        assertEquals(
            MicDeviceResolver.FALLBACK_TYPE,
            MicDeviceResolver.resolveType(MicDeviceSource.WIRED_HEADSET, connected),
        )
    }

    @Test
    fun `usb prefers the headset type over the plain device type`() {
        val connected = setOf(AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_DEVICE)
        assertEquals(AudioDeviceInfo.TYPE_USB_HEADSET, MicDeviceResolver.resolveType(MicDeviceSource.USB, connected))
    }

    @Test
    fun `a plain usb device satisfies the usb kind`() {
        val connected = setOf(AudioDeviceInfo.TYPE_BUILTIN_MIC, AudioDeviceInfo.TYPE_USB_DEVICE)
        assertEquals(AudioDeviceInfo.TYPE_USB_DEVICE, MicDeviceResolver.resolveType(MicDeviceSource.USB, connected))
    }

    @Test
    fun `bluetooth resolves only through the sco input`() {
        val sco = setOf(AudioDeviceInfo.TYPE_BUILTIN_MIC, AudioDeviceInfo.TYPE_BLUETOOTH_SCO)
        assertEquals(AudioDeviceInfo.TYPE_BLUETOOTH_SCO, MicDeviceResolver.resolveType(MicDeviceSource.BLUETOOTH, sco))
    }

    @Test
    fun `a2dp audio is not a mic input`() {
        // A2DP is the output-only Bluetooth profile — it must not satisfy the
        // Bluetooth mic kind.
        val connected = setOf(AudioDeviceInfo.TYPE_BUILTIN_MIC, AudioDeviceInfo.TYPE_BLUETOOTH_A2DP)
        assertEquals(MicDeviceResolver.FALLBACK_TYPE, MicDeviceResolver.resolveType(MicDeviceSource.BLUETOOTH, connected))
    }

    @Test
    fun `an unconnected kind falls back to the built-in mic`() {
        val connected = setOf(AudioDeviceInfo.TYPE_BUILTIN_MIC, AudioDeviceInfo.TYPE_WIRED_HEADSET)
        assertEquals(MicDeviceResolver.FALLBACK_TYPE, MicDeviceResolver.resolveType(MicDeviceSource.USB, connected))
        assertEquals(MicDeviceResolver.FALLBACK_TYPE, MicDeviceResolver.resolveType(MicDeviceSource.BLUETOOTH, connected))
    }

    // ---- kindForType: the reverse half (the UI's "Recording from …" line) ----

    @Test
    fun `each accepted type maps back to its kind`() {
        for (source in MicDeviceSource.entries) {
            for (type in MicDeviceResolver.acceptedTypes(source)) {
                assertEquals(source, MicDeviceResolver.kindForType(type))
            }
        }
    }

    @Test
    fun `usb device types all render as the usb kind`() {
        assertEquals(MicDeviceSource.USB, MicDeviceResolver.kindForType(AudioDeviceInfo.TYPE_USB_HEADSET))
        assertEquals(MicDeviceSource.USB, MicDeviceResolver.kindForType(AudioDeviceInfo.TYPE_USB_DEVICE))
    }

    @Test
    fun `the builtin type is the builtin kind`() {
        assertEquals(MicDeviceSource.BUILTIN, MicDeviceResolver.kindForType(MicDeviceResolver.FALLBACK_TYPE))
    }

    @Test
    fun `an unknown type degrades to the builtin kind`() {
        // e.g. TYPE_TELEPHONY — an input type no kind accepts; never a crash.
        assertEquals(MicDeviceSource.BUILTIN, MicDeviceResolver.kindForType(AudioDeviceInfo.TYPE_TELEPHONY))
    }

    // ---- captureRateHz / isLiveReroutable: the round-4 per-device contract ----

    @Test
    fun `the capture rate follows the kind - builtin shares the game path, wired and usb run native, sco runs hfp`() {
        // The round-2 contract (16 kHz, the game's voice-chat path) is
        // builtin-specific; forcing it onto wired/USB mis-fed both captures
        // (round 4). Each kind captures at its own path's rate.
        assertEquals(MicDeviceResolver.SHARED_VOICE_INPUT_RATE_HZ, MicDeviceResolver.captureRateHz(MicDeviceSource.BUILTIN))
        assertEquals(MicDeviceResolver.USB_ANALOG_INPUT_RATE_HZ, MicDeviceResolver.captureRateHz(MicDeviceSource.WIRED_HEADSET))
        assertEquals(MicDeviceResolver.USB_ANALOG_INPUT_RATE_HZ, MicDeviceResolver.captureRateHz(MicDeviceSource.USB))
        assertEquals(MicDeviceResolver.HFP_VOICE_INPUT_RATE_HZ, MicDeviceResolver.captureRateHz(MicDeviceSource.BLUETOOTH))
    }

    @Test
    fun `a cross-rate switch is not live-reroutable`() {
        // An AudioRecord's rate is fixed at creation — builtin (16 kHz) to
        // USB (48 kHz) in either direction must rebuild the mic session,
        // never reroute the running record onto a mis-matched path.
        val connected = setOf(AudioDeviceInfo.TYPE_BUILTIN_MIC, AudioDeviceInfo.TYPE_USB_DEVICE)
        assertEquals(false, MicDeviceResolver.isLiveReroutable(MicDeviceResolver.SHARED_VOICE_INPUT_RATE_HZ, MicDeviceSource.USB, connected))
        assertEquals(false, MicDeviceResolver.isLiveReroutable(MicDeviceResolver.USB_ANALOG_INPUT_RATE_HZ, MicDeviceSource.BUILTIN, connected))
    }

    @Test
    fun `a same-rate switch stays live`() {
        // Wired ↔ USB are both 48 kHz native; builtin ↔ SCO are both 16 kHz.
        val analog = setOf(AudioDeviceInfo.TYPE_BUILTIN_MIC, AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_USB_DEVICE)
        val sco = setOf(AudioDeviceInfo.TYPE_BUILTIN_MIC, AudioDeviceInfo.TYPE_BLUETOOTH_SCO)
        assertEquals(true, MicDeviceResolver.isLiveReroutable(MicDeviceResolver.USB_ANALOG_INPUT_RATE_HZ, MicDeviceSource.USB, analog))
        assertEquals(true, MicDeviceResolver.isLiveReroutable(MicDeviceResolver.USB_ANALOG_INPUT_RATE_HZ, MicDeviceSource.WIRED_HEADSET, analog))
        assertEquals(true, MicDeviceResolver.isLiveReroutable(MicDeviceResolver.SHARED_VOICE_INPUT_RATE_HZ, MicDeviceSource.BLUETOOTH, sco))
    }

    @Test
    fun `an unconnected pick keeps the builtin contract and stays live`() {
        // Selecting an external kind with nothing connected resolves to the
        // builtin mic (16 kHz) — a running builtin record takes it in place,
        // and the reroute reports the fallback via onRouted.
        val connected = setOf(AudioDeviceInfo.TYPE_BUILTIN_MIC)
        assertEquals(true, MicDeviceResolver.isLiveReroutable(MicDeviceResolver.SHARED_VOICE_INPUT_RATE_HZ, MicDeviceSource.USB, connected))
    }
}
