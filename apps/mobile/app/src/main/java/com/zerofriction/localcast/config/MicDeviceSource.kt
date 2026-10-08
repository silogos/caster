package com.zerofriction.localcast.config

/**
 * Which microphone the cast records from (settings screen, audio.md):
 * the phone's built-in mic, a wired headset's mic, a USB microphone, or a
 * connected Bluetooth headset's mic. The default stays the built-in mic —
 * the device the ADR-004 routing story was validated on.
 *
 * Stored as a label (not the enum name) via [CastSettingsCodec] — same
 * convention as the other settings enums: renaming a constant can't silently
 * change what users stored. The stored value is a *kind*, not a concrete
 * device: it is resolved at mic-session start against the currently
 * connected input devices, so a saved choice survives replugs and reboots.
 * A kind that isn't connected falls back to the built-in mic at capture
 * time (MicDeviceResolver) — a missing headset never fails a cast.
 */
enum class MicDeviceSource(val label: String) {
    /** The phone's own microphone — the default and the fallback. */
    BUILTIN("builtin"),

    /** The mic of a wired headset (3.5 mm or USB-C headset with a mic). */
    WIRED_HEADSET("wired_headset"),

    /** A USB audio device's input (USB microphone or USB audio interface). */
    USB("usb"),

    /** The mic of a connected Bluetooth headset (the SCO/HFP path). */
    BLUETOOTH("bluetooth");

    companion object {
        fun fromLabel(label: String): MicDeviceSource? = entries.firstOrNull { it.label == label }
    }
}
