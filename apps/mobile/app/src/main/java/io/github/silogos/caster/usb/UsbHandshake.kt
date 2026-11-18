package io.github.silogos.caster.usb

import io.github.silogos.caster.signaling.Handshake
import org.json.JSONObject
import java.security.KeyPair
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.interfaces.ECPublicKey
import java.util.Base64

/**
 * The phone's half of the USB link's handshake (usb-link.md v2, ADR-037):
 * the cable makes its own pairing. A phone that holds a cable key for the
 * desktop on the cable authenticates with it; one that doesn't enrols — an
 * ECDH key exchange, its own `auth` with the new key, then the computer's
 * user taps Allow. Pure — no I/O; the link feeds it control messages and
 * sends what it returns.
 */
class UsbHandshake(
    private val deviceId: String,
    /** The `ua` the desktop takes the phone's name from (the Wi-Fi hello's). */
    private val userAgent: String,
    /** The cable pairing, or null while this phone has none (the cable guide). */
    private val saved: Saved?,
    private val nonce: () -> String = ::freshNonce,
    private val keys: () -> KeyPair = CableKey::generate,
) {
    data class Saved(val desktopId: String, val key: ByteArray)

    enum class Refusal {
        /** The computer's user said no, closed the prompt, or let it run out. */
        NotAllowed,
        /** The computer is paired with another phone. */
        Busy,
        /** This phone is paired with another computer (switching needs Terminate). */
        OtherComputer,
        /** The computer runs an older Caster. */
        OldComputer,
        /** Anything else: a wrong proof, a failed enrolment. */
        Failed,
    }

    sealed interface Outcome {
        /** Send this control message and wait. */
        data class Send(val message: JSONObject) : Outcome
        /** Our key is made and our proof sent: the computer is asking its user about us. */
        data class Asking(val message: JSONObject, val desktopName: String) : Outcome
        /** The stored key is stale (the computer forgot us): forget it, and send this enrolment. */
        data class StaleKey(val enrol: JSONObject) : Outcome
        /** Both proofs checked: media may flow. [enrolledKey] is set when this was the first time — store it. */
        class Trusted(val desktopName: String, val desktopId: String, val enrolledKey: ByteArray?) : Outcome
        data class Refused(val refusal: Refusal, val why: String) : Outcome
        /** Nothing to do (a repeated hello while waiting). */
        data object Ignore : Outcome
    }

    private var desktopId: String? = null
    private var desktopName: String = ""
    private var desktopNonce: String? = null
    private var phoneNonce: String? = null
    private var ephemeral: KeyPair? = null
    /** The key our `auth` used: the stored one, or the enrolment's. */
    private var key: ByteArray? = null
    private var enrolling = false
    var trusted = false
        private set

    fun onControl(message: JSONObject): Outcome = when (message.optString("type")) {
        "hello" -> onHello(message)
        "enrol-key" -> onEnrolKey(message)
        "auth-ok" -> onAuthOk(message)
        "error" -> onError(message.optString("code"))
        else -> Outcome.Ignore
    }

    private fun onHello(hello: JSONObject): Outcome {
        if (trusted || desktopNonce != null) return Outcome.Ignore // the desktop resends until answered
        val link = hello.optInt("link", -1)
        if (link != UsbFrame.LINK_VERSION) return Outcome.Refused(Refusal.OldComputer, "desktop speaks link $link")
        val id = hello.optString("desktopId")
        val n = hello.optString("n")
        if (id.isEmpty() || n.isEmpty()) return Outcome.Refused(Refusal.Failed, "hello without desktopId or nonce")
        val pairing = saved
        if (pairing != null && pairing.desktopId != id) return Outcome.Refused(Refusal.OtherComputer, "paired with another computer")
        desktopId = id
        desktopName = hello.optString("name")
        desktopNonce = n
        if (pairing == null) return Outcome.Send(enrol())
        key = pairing.key
        return Outcome.Send(auth(pairing.key))
    }

    private fun enrol(): JSONObject {
        val pair = keys()
        ephemeral = pair
        enrolling = true
        val mine = nonce()
        phoneNonce = mine
        return JSONObject()
            .put("type", "enrol")
            .put("deviceId", deviceId)
            .put("ua", userAgent)
            .put("pub", b64(CableKey.encode(pair.public as ECPublicKey)))
            .put("n", mine)
    }

    private fun auth(key: ByteArray): JSONObject {
        val n = desktopNonce ?: error("auth before hello")
        // During enrolment the auth carries the enrol's nonce: it is in the transcript.
        val mine = if (enrolling) phoneNonce!! else nonce().also { phoneNonce = it }
        return JSONObject()
            .put("type", "auth")
            .put("deviceId", deviceId)
            .put("n", mine)
            .put("mac", Handshake.computeMac(key, deviceId, n))
    }

    private fun onEnrolKey(message: JSONObject): Outcome {
        val pair = ephemeral ?: return Outcome.Ignore
        if (key != null) return Outcome.Ignore
        val desktopPub = runCatching { unb64(message.optString("pub")) }.getOrNull()
        val peer = desktopPub?.let(CableKey::decode)
            ?: return Outcome.Refused(Refusal.Failed, "enrol-key is not a P-256 point")
        val phonePub = CableKey.encode(pair.public as ECPublicKey)
        val t = CableKey.transcript(phonePub, desktopPub, deviceId, desktopId!!, desktopNonce!!, phoneNonce!!)
        val derived = CableKey.derive(CableKey.agree(pair.private, peer), t)
        key = derived
        ephemeral = null // used once
        return Outcome.Asking(auth(derived), desktopName)
    }

    private fun onAuthOk(ok: JSONObject): Outcome {
        val mine = phoneNonce ?: return Outcome.Refused(Refusal.Failed, "auth-ok before auth")
        val id = desktopId ?: return Outcome.Refused(Refusal.Failed, "auth-ok before hello")
        val k = key ?: return Outcome.Refused(Refusal.Failed, "auth-ok before a key")
        val expected = Handshake.computeMac(k, id, mine)
        // Constant-time: the comparison must not leak how much of a forged mac matched.
        if (!MessageDigest.isEqual(expected.toByteArray(), ok.optString("mac").toByteArray())) {
            return Outcome.Refused(Refusal.Failed, "desktop failed to prove the cable key")
        }
        trusted = true
        return Outcome.Trusted(desktopName, id, if (enrolling) k else null)
    }

    private fun onError(code: String): Outcome {
        if (code == "unknown-device" && !enrolling && key != null) {
            // The computer forgot us (its Terminate while we were unplugged): enrol on the same hello.
            key = null
            return Outcome.StaleKey(enrol())
        }
        return when (code) {
            "not-allowed" -> Outcome.Refused(Refusal.NotAllowed, "the computer didn't allow this phone")
            "busy" -> Outcome.Refused(Refusal.Busy, "the computer is paired with another phone")
            else -> Outcome.Refused(Refusal.Failed, "desktop error $code")
        }
    }

    private fun b64(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    private fun unb64(s: String): ByteArray = Base64.getUrlDecoder().decode(s)

    companion object {
        private const val NONCE_BYTES = 16
        private val random = SecureRandom()

        fun freshNonce(): String {
            val bytes = ByteArray(NONCE_BYTES).also(random::nextBytes)
            return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        }
    }
}
