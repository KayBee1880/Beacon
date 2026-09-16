package com.beacon.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

enum class MessageDirection { INCOMING, OUTGOING }

enum class MessageStatus { SENDING, SENT, DELIVERED, FAILED }

// Milestone 7 (D-037): whether the attachment's actual bytes exist on this device yet,
// deliberately distinct from MessageStatus, which describes the message's own delivery.
// A sender's own file is LOCAL immediately, long before a receiver's copy exists at all.
enum class AttachmentState { LOCAL, OFFERED, TRANSFERRING, RECEIVED, FAILED }

@Suppress("ArrayInDataClass")
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
    val nextRetryAt: Long? = null,
    // Milestone 7 (D-037): null for a plain text message. contentHash is a SHA-256 digest
    // of the whole plaintext file, checked on the receiving end independent of AES-GCM's
    // own per-chunk authentication (docs/08 §5). localPath is null until the file's bytes
    // actually exist on this device (immediately for the sender, only after a successful
    // transfer for the receiver).
    val attachmentFileName: String? = null,
    val attachmentMimeType: String? = null,
    val attachmentSizeBytes: Long? = null,
    val attachmentContentHash: ByteArray? = null,
    val attachmentLocalPath: String? = null,
    val attachmentState: AttachmentState? = null
)
