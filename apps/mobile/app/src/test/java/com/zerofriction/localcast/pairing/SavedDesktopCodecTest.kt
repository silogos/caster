package com.zerofriction.localcast.pairing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The saved-desktop document codec (ADR-006): one versioned JSON object,
 * strict shape — anything wrong-shaped degrades to null so the store falls
 * back to "nothing saved" and the user re-scans (the CastSettingsCodec
 * convention: never a session on guessed values).
 */
class SavedDesktopCodecTest {

    private val entry = SavedDesktop(
        desktopId = "desktop-uuid-1",
        name = "Gaming PC",
        hosts = listOf("192.168.1.42", "192.168.137.1"),
        port = 52341,
        sealedTrustKey = "v1.AAAA.BBBB",
        savedAtUnixSeconds = 1_800_000_000,
        lastConnectedAtUnixSeconds = 1_800_050_000,
    )

    @Test
    fun `round-trips one saved desktop`() {
        val decoded = decodeSavedDesktop(encodeSavedDesktop(entry))
        assertEquals(entry, decoded)
    }

    @Test
    fun `degrades to null on corrupt or wrong-shaped documents`() {
        assertNull(decodeSavedDesktop("not json"))
        assertNull(decodeSavedDesktop("{}"))
        assertNull(decodeSavedDesktop(encodeSavedDesktop(entry).replace("\"version\":1", "\"version\":2")))
        // No hosts / out-of-range port / empty key are all unusable.
        assertNull(decodeSavedDesktop(encodeSavedDesktop(entry.copy(hosts = emptyList()))))
        assertNull(decodeSavedDesktop(encodeSavedDesktop(entry.copy(port = 0))))
        assertNull(decodeSavedDesktop(encodeSavedDesktop(entry.copy(sealedTrustKey = ""))))
        assertNull(decodeSavedDesktop(encodeSavedDesktop(entry.copy(desktopId = ""))))
    }
}
