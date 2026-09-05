package com.beacon.data

import kotlinx.coroutines.flow.Flow

class PeerRepository(private val peerDao: PeerDao) {

    fun observeAll(): Flow<List<Peer>> = peerDao.observeAll()

    suspend fun recordSeen(publicKey: String, displayName: String, seenAt: Long) {
        val existing = peerDao.get(publicKey)
        peerDao.upsert(
            Peer(
                id = publicKey,
                displayName = displayName,
                firstSeenAt = existing?.firstSeenAt ?: seenAt,
                lastSeenAt = seenAt
            )
        )
    }
}
