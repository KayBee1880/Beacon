package com.beacon.data

import android.util.Base64
import com.beacon.crypto.CryptoService
import com.beacon.crypto.IdentityKeyStore
import kotlinx.coroutines.flow.Flow

class IdentityRepository(private val identityDao: IdentityDao) {

    private val keystoreAlias = "beacon_identity_key"
    private val cryptoService = CryptoService()

    fun observe(): Flow<Identity?> = identityDao.observe()

    // One-shot read for callers that aren't Composables (e.g. MessageRetryCoordinator),
    // which need the current identity at one moment rather than an ongoing subscription.
    suspend fun get(): Identity? = identityDao.get()

    suspend fun createIdentity(displayName: String) {
        val publicKey = IdentityKeyStore.getOrCreatePublicKey(keystoreAlias)

        // D-029/D-030: the long-term encryption keypair, generated once here and signed
        // by the Keystore key above, the same "authenticate an untrusted-until-verified
        // key with the one key nobody can forge" pattern D-014 already established.
        val encryptionKeyPair = cryptoService.generateEphemeralKeyPair()
        val encryptionPublicKey = cryptoService.encodePublicKey(encryptionKeyPair.public)
        val encryptionPrivateKey = cryptoService.encodePrivateKey(encryptionKeyPair.private)
        val encryptionPublicKeySignature = Base64.encodeToString(
            IdentityKeyStore.sign(keystoreAlias, encryptionKeyPair.public.encoded),
            Base64.NO_WRAP
        )

        identityDao.insert(
            Identity(
                publicKey = publicKey,
                keystoreAlias = keystoreAlias,
                displayName = displayName,
                createdAt = System.currentTimeMillis(),
                encryptionPublicKey = encryptionPublicKey,
                encryptionPrivateKey = encryptionPrivateKey,
                encryptionPublicKeySignature = encryptionPublicKeySignature
            )
        )
    }
}
