package com.beacon.data

import kotlinx.coroutines.flow.Flow

class PeerRepository(private val peerDao: PeerDao) {

    fun observeAll(): Flow<List<Peer>> = peerDao.observeAll()

    // Milestone 6: MessageRepository's relay fallback (D-035) needs a one-shot read of a
    // specific peer's encryptionPublicKey, not an ongoing subscription.
    suspend fun get(peerId: String): Peer? = peerDao.get(peerId)

    // A relay-delivered message (docs/07 §6) can come from someone this device has never
    // directly discovered; without a Peer row at all, creating a Conversation for them
    // would violate its foreign key to Peer. firstSeenAt/lastSeenAt of 0 deliberately
    // never satisfies PeerDiscoveryScreen's nearby grace-period check (docs/03 §6): being
    // known through the mesh must never make someone show up as physically nearby. If
    // this peer is already on file (met directly, or via an earlier relay), this is a
    // no-op, recordSeen's own upsert is what keeps a genuinely live row current.
    suspend fun recordKnownFromRelay(peerId: String, displayName: String) {
        if (peerDao.get(peerId) != null) return
        peerDao.upsert(Peer(id = peerId, displayName = displayName, firstSeenAt = 0L, lastSeenAt = 0L))
    }

    // encryptionPublicKey is already verified by the time this is called (D-030,
    // BleCentralRole.verifyEncryptionPublicKey); a resolve without a usable key (peer not
    // yet on Milestone 6 code, or a failed verification) passes null, which must not
    // overwrite a key already on file from an earlier, successful resolve.
    suspend fun recordSeen(
        publicKey: String,
        displayName: String,
        seenAt: Long,
        encryptionPublicKey: String? = null
    ) {
        val existing = peerDao.get(publicKey)
        peerDao.upsert(
            Peer(
                id = publicKey,
                displayName = displayName,
                firstSeenAt = existing?.firstSeenAt ?: seenAt,
                lastSeenAt = seenAt,
                encryptionPublicKey = encryptionPublicKey ?: existing?.encryptionPublicKey
            )
        )
    }
}
