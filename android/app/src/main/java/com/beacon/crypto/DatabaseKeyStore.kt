package com.beacon.crypto

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

private const val GCM_NONCE_LENGTH_BYTES = 12
private const val GCM_TAG_LENGTH_BITS = 128

/**
 * Milestone 13 (D-062): wraps/unwraps the random passphrase SQLCipher actually opens
 * `beacon.db` with. A separate Keystore key from IdentityKeyStore's, not a reused one:
 * an AndroidKeyStore EC key generated with PURPOSE_SIGN/PURPOSE_VERIFY (IdentityKeyStore's
 * key) cannot also do encryption, the same platform restriction D-029 already ran into for
 * the relay encryption key. Unlike D-029, this needs no minSdk tradeoff at all,
 * PURPOSE_ENCRYPT/PURPOSE_DECRYPT AES/GCM has been supported since API 23, well under this
 * project's minSdk 26, so hardware backing is simply available here, no software fallback
 * fork to reason about.
 */
object DatabaseKeyStore {
    private const val PROVIDER = "AndroidKeyStore"
    private const val ALIAS = "beacon-database-key"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"

    // Output layout matches CryptoService.encrypt's own convention: [12-byte nonce]
    // [ciphertext + 16-byte GCM tag], one blob to store rather than two separate values.
    // AndroidKeyStore's own Cipher generates the nonce itself on ENCRYPT_MODE init (unlike
    // CryptoService's software keys, where the caller draws it), read back via cipher.iv
    // afterward.
    fun encrypt(plaintext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        }
        val nonce = cipher.iv
        return nonce + cipher.doFinal(plaintext)
    }

    fun decrypt(payload: ByteArray): ByteArray {
        val nonce = payload.copyOfRange(0, GCM_NONCE_LENGTH_BYTES)
        val ciphertext = payload.copyOfRange(GCM_NONCE_LENGTH_BYTES, payload.size)
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(GCM_TAG_LENGTH_BITS, nonce))
        }
        return cipher.doFinal(ciphertext)
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(PROVIDER).apply { load(null) }
        (keyStore.getKey(ALIAS, null) as? SecretKey)?.let { return it }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
        val spec = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
        generator.init(spec)
        return generator.generateKey()
    }
}
