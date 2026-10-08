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
}
