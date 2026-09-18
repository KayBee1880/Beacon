package com.beacon.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

// Milestone 12 (D-060): MESSAGE is Milestone 6's original envelope shape; ACK is the new
// return-path envelope RelayGossipSession.buildAckEnvelope builds once a MESSAGE-kind
// envelope is genuinely, newly delivered. Both ride the exact same gossip/storage/bound
// machinery, this is the only place the two kinds are actually treated differently.
enum class RelayEnvelopeKind { MESSAGE, ACK }

// Milestone 6 (D-032): deliberately not a Message, no foreign key to Conversation. Most
// of the time this device is carrying it purely on behalf of two other people; it only
// becomes a real Message (via MessageRepository.receiveIncoming) once finalRecipientId
// turns out to be this device's own identity and decryption succeeds, at which point this
// row is deleted, its job done.
@Suppress("ArrayInDataClass")
@Entity(tableName = "relay_envelope", indices = [Index("finalRecipientId")])
data class RelayEnvelope(
    // Milestone 12: this row's own gossip/storage identity, not necessarily the id of the
    // message it's about. For a MESSAGE-kind envelope this is still the actual message's
    // own id, unchanged from Milestone 6. For an ACK-kind envelope this is a freshly
    // minted id (see ackedMessageId below for what it's actually acknowledging); the two
    // can genuinely be in flight, in opposite directions, at the same time, so acks must
    // never collide with the still-circulating envelope they're acknowledging (D-060).
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
    // Milestone 12 (D-059): this envelope creator's own long-term encryption key, already
    // signed once at identity creation (Identity.encryptionPublicKeySignature), carried
    // here so whoever eventually needs to build a return-path envelope back to this
    // sender (an ack, so far) can do so without ever having discovered them directly.
    val originEncryptionPublicKey: String,
    val originEncryptionPublicKeySignature: String,
    val ciphertext: ByteArray,
    val kind: RelayEnvelopeKind,
    // Milestone 12 (D-060): null for MESSAGE-kind envelopes. For ACK-kind, the id of the
    // original outgoing Message this ack confirms, looked up via MessageRepository.getById
    // and marked DELIVERED once this envelope is decrypted by the original sender.
    val ackedMessageId: String?,
    val hopCount: Int,
    val createdAt: Long,
    val receivedAt: Long
)
