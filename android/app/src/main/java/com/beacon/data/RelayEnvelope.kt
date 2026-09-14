package com.beacon.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

// Milestone 6 (D-032): deliberately not a Message, no foreign key to Conversation. Most
// of the time this device is carrying it purely on behalf of two other people; it only
// becomes a real Message (via MessageRepository.receiveIncoming) once finalRecipientId
// turns out to be this device's own identity and decryption succeeds, at which point this
// row is deleted, its job done.
@Suppress("ArrayInDataClass")
@Entity(tableName = "relay_envelope", indices = [Index("finalRecipientId")])
data class RelayEnvelope(
    @PrimaryKey val messageId: String,
    val originSenderId: String,
    // A relay-delivered message may come from someone this device has never directly
    // discovered, so there may be no Peer row for originSenderId yet; carrying the
    // display name in the envelope itself is what lets the recipient create one (see
    // RelayGossipSession.deliverToSelf) instead of the delivery failing on Conversation's
    // foreign key to Peer, or silently showing up in ConversationsScreen with no name.
    val originDisplayName: String,
    val finalRecipientId: String,
    val senderEphemeralPublicKey: ByteArray,
    val senderEphemeralPublicKeySignature: ByteArray,
    val ciphertext: ByteArray,
    val hopCount: Int,
    val createdAt: Long,
    val receivedAt: Long
)
