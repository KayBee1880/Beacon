package com.beacon.crypto

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec

/**
 * Generates and reads the identity keypair held in the Android Keystore.
 * The private key never leaves the Keystore; only the public key is ever read out.
 */
object IdentityKeyStore {
    private const val PROVIDER = "AndroidKeyStore"
    private const val SIGNATURE_ALGORITHM = "SHA256withECDSA"

    fun getOrCreatePublicKey(alias: String): String {
        val keyStore = KeyStore.getInstance(PROVIDER).apply { load(null) }
        val publicKey = keyStore.getCertificate(alias)?.publicKey ?: generateKeyPair(alias)
        return Base64.encodeToString(publicKey.encoded, Base64.NO_WRAP)
    }

    // Used to sign an ephemeral session key (D-013/D-014) so a peer can verify it actually
    // came from the device holding this alias's private key. The private key itself is
    // never read out; the Keystore performs the sign operation on our behalf.
    fun sign(alias: String, data: ByteArray): ByteArray {
        val keyStore = KeyStore.getInstance(PROVIDER).apply { load(null) }
        val privateKey = keyStore.getKey(alias, null) as PrivateKey
        return Signature.getInstance(SIGNATURE_ALGORITHM).apply {
            initSign(privateKey)
            update(data)
        }.sign()
    }

    // Verifies a signature against a peer's identity public key (already resolved and
    // trusted via Milestone 2's discovery, not this device's own Keystore).
    fun verify(publicKeyBase64: String, data: ByteArray, signature: ByteArray): Boolean {
        val publicKeyBytes = Base64.decode(publicKeyBase64, Base64.NO_WRAP)
        val publicKey = KeyFactory.getInstance(KeyProperties.KEY_ALGORITHM_EC)
            .generatePublic(X509EncodedKeySpec(publicKeyBytes))
        return Signature.getInstance(SIGNATURE_ALGORITHM).apply {
            initVerify(publicKey)
            update(data)
        }.verify(signature)
    }

    private fun generateKeyPair(alias: String): PublicKey {
        val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, PROVIDER)
        val spec = KeyGenParameterSpec.Builder(
            alias,
            KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
        )
            .setDigests(KeyProperties.DIGEST_SHA256)
            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
            .build()
        generator.initialize(spec)
        return generator.generateKeyPair().public
    }
}
