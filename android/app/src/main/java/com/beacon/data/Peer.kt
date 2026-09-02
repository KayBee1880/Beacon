package com.beacon.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "peer")
data class Peer(
    @PrimaryKey val id: String,
    val displayName: String,
    val firstSeenAt: Long,
    val lastSeenAt: Long
)
