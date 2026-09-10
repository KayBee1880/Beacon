package com.beacon.data

import com.beacon.crypto.IdentityKeyStore
import kotlinx.coroutines.flow.Flow

class IdentityRepository(private val identityDao: IdentityDao) {

    private val keystoreAlias = "beacon_identity_key"

    fun observe(): Flow<Identity?> = identityDao.observe()

    // One-shot read for callers that aren't Composables (e.g. MessageRetryCoordinator),
    // which need the current identity at one moment rather than an ongoing subscription.
    suspend fun get(): Identity? = identityDao.get()

    suspend fun createIdentity(displayName: String) {
        val publicKey = IdentityKeyStore.getOrCreatePublicKey(keystoreAlias)
        identityDao.insert(
            Identity(
                publicKey = publicKey,
                keystoreAlias = keystoreAlias,
                displayName = displayName,
                createdAt = System.currentTimeMillis()
            )
        )
    }
}
