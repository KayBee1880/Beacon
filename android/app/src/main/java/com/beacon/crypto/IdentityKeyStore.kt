package com.beacon.crypto

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PublicKey
import java.security.spec.ECGenParameterSpec

/**
 * Generates and reads the identity keypair held in the Android Keystore.
 * The private key never leaves the Keystore — only the public key is ever read out.
 */
object IdentityKeyStore {
    private const val PROVIDER = "AndroidKeyStore"

    fun getOrCreatePublicKey(alias: String): String {
        val keyStore = KeyStore.getInstance(PROVIDER).apply { load(null) }
        val publicKey = keyStore.getCertificate(alias)?.publicKey ?: generateKeyPair(alias)
        return Base64.encodeToString(publicKey.encoded, Base64.NO_WRAP)
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
