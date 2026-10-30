package com.zerofriction.localcast.pairing

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Seals the saved session's trustKey at rest (ADR-006). The app allows OS
 * backups (`allowBackup="true"`), so a plaintext key in SharedPreferences
 * could ride a backup off the device; wrapping it with a key that lives in
 * the Android Keystore makes the extracted ciphertext useless. The vault is
 * deliberately narrow: seal/open of one small secret, nothing else.
 *
 * [TrustVault] is the injectable interface — JVM tests run with [PlainTrustVault];
 * production wiring passes [KeystoreTrustVault]. An unopenable seal (key
 * invalidated, e.g. after a lock-screen credential change) returns null and
 * the caller degrades honestly: the saved session is dropped and the user
 * re-scans — never a cast on guessed key material.
 */
interface TrustVault {
    /** Seal the plain bytes into an opaque, persistable string. */
    fun seal(plain: ByteArray): String

    /** Open a seal produced by [seal]; null when it cannot be opened. */
    fun open(sealed: String): ByteArray?
}

/** Base64 pass-through — tests and JVM-only contexts (no key material at stake). */
object PlainTrustVault : TrustVault {
    override fun seal(plain: ByteArray): String = Base64.getEncoder().encodeToString(plain)

    override fun open(sealed: String): ByteArray? = try {
        Base64.getDecoder().decode(sealed)
    } catch (_: IllegalArgumentException) {
        null
    }
}

/** AES-256-GCM under an AndroidKeyStore key — the production vault. */
class KeystoreTrustVault : TrustVault {

    private val key: SecretKey? by lazy { loadOrCreateKey() }

    override fun seal(plain: ByteArray): String {
        val key = key ?: throw IllegalStateException("trust vault key unavailable")
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val iv = cipher.iv
        val sealed = cipher.doFinal(plain)
        // "v1" + iv + ciphertext, all base64 — one persistable, versioned blob.
        return VERSION_PREFIX +
            Base64.getEncoder().encodeToString(iv) + PART_SEPARATOR +
            Base64.getEncoder().encodeToString(sealed)
    }

    override fun open(sealed: String): ByteArray? {
        if (!sealed.startsWith(VERSION_PREFIX)) return null
        val parts = sealed.removePrefix(VERSION_PREFIX).split(PART_SEPARATOR)
        if (parts.size != 2) return null
        val key = key ?: run {
            Log.w(TAG, "trust vault key unavailable — saved session cannot resume")
            return null
        }
        return try {
            val iv = Base64.getDecoder().decode(parts[0])
            val sealedBytes = Base64.getDecoder().decode(parts[1])
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
            cipher.doFinal(sealedBytes)
        } catch (e: Exception) {
            Log.w(TAG, "saved trustKey failed to unseal (${e.javaClass.simpleName}) — degrading to re-scan")
            null
        }
    }

    private fun loadOrCreateKey(): SecretKey? = try {
        val store = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        generator.generateKey()
    } catch (e: Exception) {
        Log.e(TAG, "creating the trust vault key failed (${e.javaClass.simpleName})")
        null
    }

    private companion object {
        const val TAG = "TrustVault"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "zfc-trust-key-v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_TAG_BITS = 128
        const val VERSION_PREFIX = "v1."
        const val PART_SEPARATOR = "."
    }
}
