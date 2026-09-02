package com.beacon.data

import com.beacon.crypto.IdentityKeyStore
import kotlinx.coroutines.flow.Flow

class IdentityRepository(private val identityDao: IdentityDao) {

    private val keystoreAlias = "beacon_identity_key"

    fun observe(): Flow<Identity?> = identityDao.observe()

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
