package com.beacon.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "conversation",
    foreignKeys = [
        ForeignKey(
            entity = Peer::class,
            parentColumns = ["id"],
            childColumns = ["peerId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("peerId", unique = true)]
)
data class Conversation(
    @PrimaryKey val id: String,
    val peerId: String,
    val createdAt: Long,
    val lastMessageAt: Long
)
