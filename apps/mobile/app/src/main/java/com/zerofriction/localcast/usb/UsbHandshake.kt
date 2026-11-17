package com.zerofriction.localcast.usb

import com.zerofriction.localcast.signaling.Handshake
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * The phone's half of the USB link's mutual authentication
 * (docs/architecture/usb-link.md, ADR-034): both sides prove they hold the
 * trust key of the Wi-Fi pairing (ADR-006). Pure — no I/O; the link feeds it
 * control messages and sends what it returns.
 */
class UsbHandshake(
    private val deviceId: String,
    /** The saved desktop's id and trust key, or null when this phone never paired. */
    private val saved: Saved?,
    private val nonce: () -> String = ::freshNonce,
) {
    data class Saved(val desktopId: String, val trustKey: ByteArray)

    sealed interface Outcome {
        /** Send this control message and wait. */
        data class Send(val message: JSONObject) : Outcome
        /** Both proofs checked: media may flow. */
        data class Trusted(val desktopName: String) : Outcome
        /** This phone has no pairing with the desktop on the cable. */
        data object NotPaired : Outcome
        /** The desktop refused us, or failed to prove itself. */
        data class Refused(val why: String) : Outcome
        /** Nothing to do (a repeated hello while waiting). */
        data object Ignore : Outcome
    }

    private var phoneNonce: String? = null
    private var desktopId: String? = null
    private var desktopName: String = ""
    var trusted = false
        private set

    fun onControl(message: JSONObject): Outcome = when (message.optString("type")) {
        "hello" -> onHello(message)
        "auth-ok" -> onAuthOk(message)
        "error" -> Outcome.Refused("desktop error ${message.optString("code")}")
        else -> Outcome.Ignore
    }

    private fun onHello(hello: JSONObject): Outcome {
        if (trusted || phoneNonce != null) return Outcome.Ignore // the desktop resends until answered
        val link = hello.optInt("link", -1)
        if (link != UsbFrame.LINK_VERSION) return Outcome.Refused("desktop speaks link $link")
        val id = hello.optString("desktopId")
        val desktopNonce = hello.optString("n")
        if (id.isEmpty() || desktopNonce.isEmpty()) return Outcome.Refused("hello without desktopId or nonce")
        val pairing = saved
        if (pairing == null || pairing.desktopId != id) return Outcome.NotPaired
        desktopId = id
        desktopName = hello.optString("name")
        val mine = nonce()
        phoneNonce = mine
        return Outcome.Send(
            JSONObject()
                .put("type", "auth")
                .put("deviceId", deviceId)
                .put("n", mine)
                .put("mac", Handshake.computeMac(pairing.trustKey, deviceId, desktopNonce)),
        )
    }

    private fun onAuthOk(ok: JSONObject): Outcome {
        val mine = phoneNonce ?: return Outcome.Refused("auth-ok before auth")
        val id = desktopId ?: return Outcome.Refused("auth-ok before hello")
        val key = saved?.trustKey ?: return Outcome.NotPaired
        val expected = Handshake.computeMac(key, id, mine)
        val received = ok.optString("mac")
        // Constant-time: the comparison must not leak how much of a forged mac matched.
        if (!MessageDigest.isEqual(expected.toByteArray(), received.toByteArray())) {
            return Outcome.Refused("desktop failed to prove the trust key")
        }
        trusted = true
        return Outcome.Trusted(desktopName)
    }

    companion object {
        private const val NONCE_BYTES = 16
        private val random = SecureRandom()

        fun freshNonce(): String {
            val bytes = ByteArray(NONCE_BYTES).also(random::nextBytes)
            return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        }
    }
}
