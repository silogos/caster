package io.github.silogos.caster.usb

import io.github.silogos.caster.signaling.Handshake
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.math.BigInteger
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.KeyPair
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPrivateKeySpec
import java.util.Base64

/**
 * The phone's half of USB link v2 (usb-link.md, ADR-037). The cable key's
 * vectors are the receiver's own (usb_protocol.rs `the_cable_key_matches_the_phones_vectors`):
 * the receiver computes them with the p256 crate, the phone with the
 * platform's EC provider, so equal values prove both apps derive the same key.
 */
@RunWith(RobolectricTestRunner::class)
class UsbHandshakeTest {

    private companion object {
        val PHONE_SCALAR = ByteArray(32) { 0x11 }
        const val PHONE_PUB = "BAIX5hfwtkQ5KCePlpmeaaI6TywVK99tbN9m5bgCgtTtGUp968uXcS0t2jyoWqh2Wlb0X8dYWZZS8ol8ZTBuV5Q"
        const val DESKTOP_PUB = "BNZak5d8qj0bCBhS_1ennkZfFmBXcwS66tUF3TpIWJzzUBheiVNy32Ih6joTdVfkc_3bZ1XwW9UHw8Uz_OnJEoU"
        const val Z_HEX = "ccfc261f58193c98ca4ad4a53bbac6f0ee29bc4d48438090446908622ca79af6"
        const val TRANSCRIPT_HEX = "3589c3627c23d56f37d297ba8996a06830b64d1df90bcfdf311e69fc30f0d11c"
        const val KEY_HEX = "53e857ae5d1b056b3158cba7b2d8891499cd5ead1b619af37495aa35544386f3"
        const val PHONE_MAC = "Nn_7TRD77Kya3nITRbptE0VGAc0fKsn2dPEkJnkl7Rc"
        const val DESKTOP_MAC = "i9A5anRUqmWDuD8v3MF4X2BKgrAI179ysvo8e83LhS0"
        const val UA = "Caster/0.3 (Android 15; Lenovo TB321FU)"
    }

    private fun unb64(s: String) = Base64.getUrlDecoder().decode(s)
    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    /** The phone's fixed test key pair: the private scalar 0x11…, its public point from the receiver's vector. */
    private fun phoneKeys(): KeyPair {
        val params = AlgorithmParameters.getInstance("EC").apply { init(ECGenParameterSpec("secp256r1")) }
            .getParameterSpec(ECParameterSpec::class.java)
        val private = KeyFactory.getInstance("EC").generatePrivate(ECPrivateKeySpec(BigInteger(1, PHONE_SCALAR), params))
        return KeyPair(CableKey.decode(unb64(PHONE_PUB))!!, private)
    }

    @Test
    fun `the cable key matches the receiver's vectors`() {
        val keys = phoneKeys()
        val z = CableKey.agree(keys.private, CableKey.decode(unb64(DESKTOP_PUB))!!)
        assertEquals(Z_HEX, hex(z))
        val t = CableKey.transcript(unb64(PHONE_PUB), unb64(DESKTOP_PUB), "phone-1", "desk-1", "dn", "pn")
        assertEquals(TRANSCRIPT_HEX, hex(t))
        val key = CableKey.derive(z, t)
        assertEquals(KEY_HEX, hex(key))
        assertEquals(PHONE_MAC, Handshake.computeMac(key, "phone-1", "dn"))
        assertEquals(DESKTOP_MAC, Handshake.computeMac(key, "desk-1", "pn"))
    }

    @Test
    fun `a point off the curve or of the wrong shape is refused`() {
        assertNull(CableKey.decode(byteArrayOf(0x04) + ByteArray(64) { 0xFF.toByte() }))
        assertNull(CableKey.decode(unb64(PHONE_PUB).copyOf(64)))
        assertNull(CableKey.decode(unb64(PHONE_PUB).also { it[0] = 0x02 }))
    }

    private fun hello(desktopId: String = "desk-1", link: Int = UsbFrame.LINK_VERSION) =
        JSONObject().put("type", "hello").put("link", link).put("desktopId", desktopId).put("name", "MacBook Pro").put("n", "dn")

    private fun newPhone() = UsbHandshake("phone-1", UA, saved = null, nonce = { "pn" }, keys = ::phoneKeys)

    @Test
    fun `a phone without a cable key enrols, proves the new key, and stores it once the Mac allows`() {
        val handshake = newPhone()
        val enrol = (handshake.onControl(hello()) as UsbHandshake.Outcome.Send).message
        assertEquals("enrol", enrol.getString("type"))
        assertEquals(PHONE_PUB, enrol.getString("pub"))
        assertEquals(UA, enrol.getString("ua"))
        assertEquals("pn", enrol.getString("n"))

        val asking = handshake.onControl(JSONObject().put("type", "enrol-key").put("pub", DESKTOP_PUB)) as UsbHandshake.Outcome.Asking
        assertEquals("MacBook Pro", asking.desktopName)
        assertEquals("auth", asking.message.getString("type"))
        assertEquals("pn", asking.message.getString("n"))
        assertEquals(PHONE_MAC, asking.message.getString("mac"))

        val trusted = handshake.onControl(JSONObject().put("type", "auth-ok").put("mac", DESKTOP_MAC)) as UsbHandshake.Outcome.Trusted
        assertEquals("desk-1", trusted.desktopId)
        assertEquals(KEY_HEX, hex(trusted.enrolledKey!!))
        assertTrue(handshake.trusted)
    }

    @Test
    fun `a paired phone authenticates with its stored key and stores nothing new`() {
        val key = CableKey.derive(ByteArray(32) { 1 }, ByteArray(32) { 2 })
        val handshake = UsbHandshake("phone-1", UA, UsbHandshake.Saved("desk-1", key), nonce = { "pn" })
        val auth = (handshake.onControl(hello()) as UsbHandshake.Outcome.Send).message
        assertEquals("auth", auth.getString("type"))
        assertEquals(Handshake.computeMac(key, "phone-1", "dn"), auth.getString("mac"))
        val ok = JSONObject().put("type", "auth-ok").put("mac", Handshake.computeMac(key, "desk-1", "pn"))
        val trusted = handshake.onControl(ok) as UsbHandshake.Outcome.Trusted
        assertNull(trusted.enrolledKey)
    }

    @Test
    fun `a stale key is dropped and the phone enrols on the same hello`() {
        val handshake = UsbHandshake("phone-1", UA, UsbHandshake.Saved("desk-1", ByteArray(32) { 7 }), nonce = { "pn" }, keys = ::phoneKeys)
        handshake.onControl(hello())
        val stale = handshake.onControl(JSONObject().put("type", "error").put("code", "unknown-device")) as UsbHandshake.Outcome.StaleKey
        assertEquals("enrol", stale.enrol.getString("type"))
        assertTrue(handshake.onControl(JSONObject().put("type", "enrol-key").put("pub", DESKTOP_PUB)) is UsbHandshake.Outcome.Asking)
    }

    @Test
    fun `a Mac that can't prove the key is refused`() {
        val handshake = newPhone()
        handshake.onControl(hello())
        handshake.onControl(JSONObject().put("type", "enrol-key").put("pub", DESKTOP_PUB))
        val forged = handshake.onControl(JSONObject().put("type", "auth-ok").put("mac", "AAAA")) as UsbHandshake.Outcome.Refused
        assertEquals(UsbHandshake.Refusal.Failed, forged.refusal)
        assertFalse(handshake.trusted)
    }

    @Test
    fun `the Mac's refusals say why`() {
        for ((code, refusal) in listOf("not-allowed" to UsbHandshake.Refusal.NotAllowed, "busy" to UsbHandshake.Refusal.Busy)) {
            val handshake = newPhone()
            handshake.onControl(hello())
            val out = handshake.onControl(JSONObject().put("type", "error").put("code", code)) as UsbHandshake.Outcome.Refused
            assertEquals(refusal, out.refusal)
        }
    }

    @Test
    fun `paired with one Mac, another Mac on the cable is refused, not enrolled`() {
        val handshake = UsbHandshake("phone-1", UA, UsbHandshake.Saved("desk-1", ByteArray(32)))
        val out = handshake.onControl(hello(desktopId = "desk-2")) as UsbHandshake.Outcome.Refused
        assertEquals(UsbHandshake.Refusal.OtherComputer, out.refusal)
    }

    @Test
    fun `a repeated hello is answered once, an older Mac is refused`() {
        val handshake = newPhone()
        assertTrue(handshake.onControl(hello()) is UsbHandshake.Outcome.Send)
        assertEquals(UsbHandshake.Outcome.Ignore, handshake.onControl(hello()))
        val old = newPhone().onControl(hello(link = 1)) as UsbHandshake.Outcome.Refused
        assertEquals(UsbHandshake.Refusal.OldComputer, old.refusal)
    }

    @Test
    fun `the derived key is 32 bytes and both macs use it`() {
        assertArrayEquals(CableKey.derive(ByteArray(32), ByteArray(32)), CableKey.derive(ByteArray(32), ByteArray(32)))
        assertEquals(32, CableKey.derive(ByteArray(32), ByteArray(32)).size)
    }
}
