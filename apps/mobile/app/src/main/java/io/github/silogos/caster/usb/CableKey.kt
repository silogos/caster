package io.github.silogos.caster.usb

import java.math.BigInteger
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECFieldFp
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * The cable key of USB link v2 (usb-link.md §The cable key, ADR-037): an
 * ephemeral ECDH on P-256 per enrolment, then
 * `cableKey = HKDF-SHA256(ikm = Z, salt = transcript, info = "caster-cable-v2", L = 32)`.
 * Platform crypto only (KeyPairGenerator "EC", KeyAgreement "ECDH"); the
 * receiver computes the same with the p256 crate, pinned by shared vectors.
 */
object CableKey {
    private const val CURVE = "secp256r1"
    private const val INFO = "caster-cable-v2"
    private const val KEY_BYTES = 32
    private const val COORD_BYTES = 32
    /** An uncompressed SEC1 point: 0x04 ‖ X ‖ Y. */
    const val POINT_BYTES = 1 + 2 * COORD_BYTES
    private const val UNCOMPRESSED: Byte = 0x04

    private val params: ECParameterSpec by lazy {
        AlgorithmParameters.getInstance("EC").apply { init(ECGenParameterSpec(CURVE)) }
            .getParameterSpec(ECParameterSpec::class.java)
    }

    /** A fresh ephemeral key pair for one enrolment. */
    fun generate(): KeyPair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec(CURVE)) }.generateKeyPair()

    /** The public point, uncompressed SEC1 (65 bytes). */
    fun encode(public: ECPublicKey): ByteArray =
        byteArrayOf(UNCOMPRESSED) + fixed(public.w.affineX) + fixed(public.w.affineY)

    /** The peer's point, or null when it is malformed or not on the curve. */
    fun decode(bytes: ByteArray): ECPublicKey? {
        if (bytes.size != POINT_BYTES || bytes[0] != UNCOMPRESSED) return null
        val x = BigInteger(1, bytes.copyOfRange(1, 1 + COORD_BYTES))
        val y = BigInteger(1, bytes.copyOfRange(1 + COORD_BYTES, POINT_BYTES))
        if (!onCurve(x, y)) return null
        return runCatching {
            KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(ECPoint(x, y), params)) as ECPublicKey
        }.getOrNull()
    }

    /** y² = x³ + ax + b (mod p): a provider is not required to check this, so we do. */
    private fun onCurve(x: BigInteger, y: BigInteger): Boolean {
        val p = (params.curve.field as ECFieldFp).p
        if (x.signum() < 0 || y.signum() < 0 || x >= p || y >= p) return false
        val left = y.modPow(BigInteger.valueOf(2), p)
        val right = x.modPow(BigInteger.valueOf(3), p).add(params.curve.a.multiply(x)).add(params.curve.b).mod(p)
        return left == right
    }

    /** Z, the shared point's X (32 bytes). */
    fun agree(own: PrivateKey, peer: ECPublicKey): ByteArray =
        KeyAgreement.getInstance("ECDH").run {
            init(own)
            doPhase(peer, true)
            generateSecret()
        }

    /** SHA-256(phone pub ‖ desktop pub ‖ deviceId ‖ 0 ‖ desktopId ‖ 0 ‖ desktop n ‖ 0 ‖ phone n). */
    fun transcript(phonePub: ByteArray, desktopPub: ByteArray, deviceId: String, desktopId: String, desktopNonce: String, phoneNonce: String): ByteArray =
        MessageDigest.getInstance("SHA-256").run {
            update(phonePub)
            update(desktopPub)
            listOf(deviceId, desktopId, desktopNonce, phoneNonce).forEachIndexed { i, part ->
                if (i > 0) update(0)
                update(part.toByteArray(Charsets.US_ASCII))
            }
            digest()
        }

    /** HKDF-SHA256 (RFC 5869) with one expand block: L = 32 = the hash length. */
    fun derive(z: ByteArray, transcript: ByteArray): ByteArray {
        val prk = Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(transcript, "HmacSHA256"))
            doFinal(z)
        }
        return Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(prk, "HmacSHA256"))
            update(INFO.toByteArray(Charsets.US_ASCII))
            update(1)
            doFinal()
        }.copyOf(KEY_BYTES)
    }

    /** A coordinate as exactly 32 big-endian bytes (BigInteger adds a sign byte or drops leading zeros). */
    private fun fixed(v: BigInteger): ByteArray {
        val raw = v.toByteArray()
        return when {
            raw.size == COORD_BYTES -> raw
            raw.size > COORD_BYTES -> raw.copyOfRange(raw.size - COORD_BYTES, raw.size)
            else -> ByteArray(COORD_BYTES - raw.size) + raw
        }
    }
}
