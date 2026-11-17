package io.github.silogos.caster.usb

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The phone's half of the USB link's mutual authentication (usb-link.md).
 * The macs are the receiver's own vectors (usb_protocol.rs): both apps
 * compute the proofs alike.
 */
@RunWith(RobolectricTestRunner::class)
class UsbHandshakeTest {

    private val key = ByteArray(32) { 7 }
    private val saved = UsbHandshake.Saved("desk-1", key)

    private fun hello(desktopId: String = "desk-1", link: Int = 1) =
        JSONObject().put("type", "hello").put("link", link).put("desktopId", desktopId).put("name", "Gaming PC").put("n", "dn")

    @Test
    fun `the paired desktop gets the phone's proof, then proves itself`() {
        val handshake = UsbHandshake("phone-1", saved, nonce = { "pn" })
        val auth = handshake.onControl(hello()) as UsbHandshake.Outcome.Send
        assertEquals("auth", auth.message.getString("type"))
        assertEquals("phone-1", auth.message.getString("deviceId"))
        assertEquals("pn", auth.message.getString("n"))
        assertEquals("VIO7LRtb_4ZGrSSviktpCWrQe2mS2ssQvKvfjCLdGGo", auth.message.getString("mac"))

        val ok = JSONObject().put("type", "auth-ok").put("mac", "bCa575Km4Dj4Q8UcJQwpyM1dkQHy4uaHI2Di-LX4CDI")
        assertEquals(UsbHandshake.Outcome.Trusted("Gaming PC"), handshake.onControl(ok))
        assertTrue(handshake.trusted)
    }

    @Test
    fun `a desktop that cannot prove the key is refused`() {
        val handshake = UsbHandshake("phone-1", saved, nonce = { "pn" })
        handshake.onControl(hello())
        val forged = JSONObject().put("type", "auth-ok").put("mac", "AAAA")
        assertTrue(handshake.onControl(forged) is UsbHandshake.Outcome.Refused)
        assertFalse(handshake.trusted)
    }

    @Test
    fun `another desktop, or no pairing at all, reads not paired and gets no proof`() {
        assertEquals(UsbHandshake.Outcome.NotPaired, UsbHandshake("phone-1", saved).onControl(hello(desktopId = "desk-2")))
        assertEquals(UsbHandshake.Outcome.NotPaired, UsbHandshake("phone-1", null).onControl(hello()))
    }

    @Test
    fun `a repeated hello is answered once, an unknown link version is refused`() {
        val handshake = UsbHandshake("phone-1", saved, nonce = { "pn" })
        assertTrue(handshake.onControl(hello()) is UsbHandshake.Outcome.Send)
        assertEquals(UsbHandshake.Outcome.Ignore, handshake.onControl(hello()))
        assertTrue(UsbHandshake("phone-1", saved).onControl(hello(link = 2)) is UsbHandshake.Outcome.Refused)
    }

    @Test
    fun `the desktop's refusal ends the handshake`() {
        val handshake = UsbHandshake("phone-1", saved, nonce = { "pn" })
        handshake.onControl(hello())
        val error = JSONObject().put("type", "error").put("code", "unknown-device")
        assertTrue(handshake.onControl(error) is UsbHandshake.Outcome.Refused)
    }
}
