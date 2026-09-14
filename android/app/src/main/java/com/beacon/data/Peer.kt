package com.beacon.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "peer")
data class Peer(
    @PrimaryKey val id: String,
    val displayName: String,
    val firstSeenAt: Long,
    val lastSeenAt: Long,
    // Milestone 6 (D-030): learned during discovery resolution, same as the rest of this
    // row, but only ever stored after BleCentralRole has already verified its signature
    // against this peer's known signing key; the signature itself isn't persisted, its
    // one job (proving this key really came from this identity) is done once verification
    // passes. Null until a resolve completes both new characteristic reads and that
    // verification, which a message can't be relay-encrypted to this peer without.
    val encryptionPublicKey: String? = null
)
