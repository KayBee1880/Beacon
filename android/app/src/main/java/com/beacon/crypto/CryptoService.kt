package com.beacon.crypto

import android.util.Base64
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

private const val EC_ALGORITHM = "EC"
private const val CURVE_NAME = "secp256r1"
private const val KEY_AGREEMENT_ALGORITHM = "ECDH"
private const val CIPHER_TRANSFORMATION = "AES/GCM/NoPadding"
private const val AES_KEY_LENGTH_BYTES = 32
private const val GCM_NONCE_LENGTH_BYTES = 12
private const val GCM_TAG_LENGTH_BITS = 128
private const val SESSION_KEY_INFO = "beacon-session-key-v1"
// D-031: a distinct HKDF info string from SESSION_KEY_INFO, standard domain separation
// so a per-hop session key and an end-to-end envelope key can never coincide, even though
// both are derived by the same ECDH + HKDF steps.
private const val ENVELOPE_KEY_INFO = "beacon-envelope-key-v1"

/**
 * Everything Milestone 3's handshake and message encryption needs, per docs/04:
 * a software ephemeral keypair (D-013) authenticated by the identity key (D-014),
 * ECDH + HKDF to derive a session key (D-015), and AES-GCM for message content.
 */
class CryptoService {

    fun generateEphemeralKeyPair(): KeyPair {
        val generator = KeyPairGenerator.getInstance(EC_ALGORITHM)
        generator.initialize(ECGenParameterSpec(CURVE_NAME))
        return generator.generateKeyPair()
    }

    fun signEphemeralPublicKey(identityKeystoreAlias: String, ephemeralPublicKey: PublicKey): ByteArray =
        IdentityKeyStore.sign(identityKeystoreAlias, ephemeralPublicKey.encoded)

    // Null return means the signature didn't verify; the caller must fail the
    // handshake outright, never fall back to trusting an unverified key.
    fun decodeAndVerifyEphemeralPublicKey(
        peerIdentityPublicKeyBase64: String,
        ephemeralPublicKeyBytes: ByteArray,
        signature: ByteArray
    ): PublicKey? {
        if (!IdentityKeyStore.verify(peerIdentityPublicKeyBase64, ephemeralPublicKeyBytes, signature)) {
            return null
        }
        return KeyFactory.getInstance(EC_ALGORITHM)
            .generatePublic(X509EncodedKeySpec(ephemeralPublicKeyBytes))
    }

    fun deriveSessionKey(ourEphemeralPrivateKey: PrivateKey, peerEphemeralPublicKey: PublicKey): SecretKey {
        val sharedSecret = KeyAgreement.getInstance(KEY_AGREEMENT_ALGORITHM).apply {
            init(ourEphemeralPrivateKey)
            doPhase(peerEphemeralPublicKey, true)
        }.generateSecret()

        val keyBytes = Hkdf.deriveKey(
            inputKeyMaterial = sharedSecret,
            info = SESSION_KEY_INFO.toByteArray(Charsets.UTF_8),
            outputLengthBytes = AES_KEY_LENGTH_BYTES
        )
        return SecretKeySpec(keyBytes, "AES")
    }

    // Output layout: [12-byte nonce][ciphertext + 16-byte GCM tag]. The nonce travels in
    // the clear (standard for AES-GCM: it isn't secret, it only ever has to be unique
    // per key, which a fresh SecureRandom draw per message guarantees with overwhelming
    // probability). Reusing a nonce under the same key would break GCM's authentication
    // guarantee completely, never regress this to anything but a fresh random draw.
    fun encrypt(sessionKey: SecretKey, plaintext: ByteArray): ByteArray {
        val nonce = ByteArray(GCM_NONCE_LENGTH_BYTES).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION).apply {
            init(Cipher.ENCRYPT_MODE, sessionKey, GCMParameterSpec(GCM_TAG_LENGTH_BITS, nonce))
        }
        return nonce + cipher.doFinal(plaintext)
    }

    fun decrypt(sessionKey: SecretKey, payload: ByteArray): ByteArray {
        val nonce = payload.copyOfRange(0, GCM_NONCE_LENGTH_BYTES)
        val ciphertext = payload.copyOfRange(GCM_NONCE_LENGTH_BYTES, payload.size)
        val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, sessionKey, GCMParameterSpec(GCM_TAG_LENGTH_BITS, nonce))
        }
        return cipher.doFinal(ciphertext)
    }

    // D-029: the long-term encryption keypair is generated the exact same way as an
    // ephemeral one (same curve, same generator), the only difference is this one gets
    // persisted (Identity.encryptionPublicKey/encryptionPrivateKey) instead of discarded
    // after one connection. No separate generator function needed; callers creating an
    // identity just keep the KeyPair this same method already returns.
    fun encodePublicKey(publicKey: PublicKey): String = Base64.encodeToString(publicKey.encoded, Base64.NO_WRAP)

    fun encodePrivateKey(privateKey: PrivateKey): String = Base64.encodeToString(privateKey.encoded, Base64.NO_WRAP)

    fun decodeEncryptionPrivateKey(base64: String): PrivateKey {
        val bytes = Base64.decode(base64, Base64.NO_WRAP)
        return KeyFactory.getInstance(EC_ALGORITHM).generatePrivate(PKCS8EncodedKeySpec(bytes))
    }

    // For a peer's own encryptionPublicKey column: already verified once, against the
    // signature D-030 requires, at the moment discovery resolved and stored it, so no
    // second verification happens here, unlike decodeAndVerifyEphemeralPublicKey above.
    fun decodeEncryptionPublicKey(base64: String): PublicKey {
        val bytes = Base64.decode(base64, Base64.NO_WRAP)
        return KeyFactory.getInstance(EC_ALGORITHM).generatePublic(X509EncodedKeySpec(bytes))
    }

    // D-031: the envelope key is ECDH + HKDF exactly like deriveSessionKey above, ECDH
    // between a fresh sender ephemeral key and the recipient's long-term encryption
    // public key rather than two live ephemeral keys, with ENVELOPE_KEY_INFO instead of
    // SESSION_KEY_INFO so the two derivations can never collide.
    fun deriveEnvelopeKey(ourEphemeralPrivateKey: PrivateKey, recipientEncryptionPublicKey: PublicKey): SecretKey {
        val sharedSecret = KeyAgreement.getInstance(KEY_AGREEMENT_ALGORITHM).apply {
            init(ourEphemeralPrivateKey)
            doPhase(recipientEncryptionPublicKey, true)
        }.generateSecret()

        val keyBytes = Hkdf.deriveKey(
            inputKeyMaterial = sharedSecret,
            info = ENVELOPE_KEY_INFO.toByteArray(Charsets.UTF_8),
            outputLengthBytes = AES_KEY_LENGTH_BYTES
        )
        return SecretKeySpec(keyBytes, "AES")
    }
}
