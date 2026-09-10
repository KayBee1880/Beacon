package com.beacon.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

enum class MessageDirection { INCOMING, OUTGOING }

enum class MessageStatus { SENDING, SENT, DELIVERED, FAILED }

@Entity(
    tableName = "message",
    foreignKeys = [
        ForeignKey(
            entity = Conversation::class,
            parentColumns = ["id"],
            childColumns = ["conversationId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("conversationId")]
)
data class Message(
    @PrimaryKey val id: String,
    val conversationId: String,
    val direction: MessageDirection,
    val content: String,
    val status: MessageStatus,
    val createdAt: Long,
    val deliveredAt: Long? = null,
    val retryCount: Int = 0,
    val nextRetryAt: Long? = null
)
