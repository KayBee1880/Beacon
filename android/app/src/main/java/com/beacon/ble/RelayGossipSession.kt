package com.beacon.ble

import com.beacon.diagnostics.BeaconLog
import com.beacon.crypto.ChatMessagePlaintext
import com.beacon.crypto.CryptoService
import com.beacon.data.ConversationRepository
import com.beacon.data.Identity
import com.beacon.data.MessageRepository
import com.beacon.data.PeerRepository
import com.beacon.data.RelayEnvelope
import com.beacon.data.RelayEnvelopeKind
import com.beacon.data.RelayEnvelopeRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.util.UUID
import javax.crypto.SecretKey

/**
 * The actual relay gossip protocol (docs/07 §8), deliberately transport ignorant, the
 * same "domain stays ignorant of the transport" split ChatGattServer already follows.
 * One instance per open connection (either role), constructed once that connection's
 * per-hop session key exists, since every frame here is encrypted with it. Owns none of
 * the GATT plumbing, only decides what to send back given a decrypted incoming frame.
 */
class RelayGossipSession(
    private val identity: Identity,
    private val conversationRepository: ConversationRepository,
    private val messageRepository: MessageRepository,
    private val peerRepository: PeerRepository,
    private val relayEnvelopeRepository: RelayEnvelopeRepository,
    private val sessionKey: SecretKey,
    private val scope: CoroutineScope,
    private val sendFrame: (ChatFrame) -> Unit
) {
    private val cryptoService = CryptoService()

    // Kicked off once, right after the handshake completes on either side (docs/07 §8):
    // announce everything currently held so the other side can ask for whatever it's
    // missing. No response is expected to this specific frame, the other side's own
    // handleInventory drives what happens next.
    fun start() {
        scope.launch {
            val ids = relayEnvelopeRepository.getAllIds()
            if (ids.isEmpty()) return@launch
            sendEncrypted(RelayFramePlaintext.encodeIdList(ids)) { ChatFrame.EncryptedRelayInventory(it) }
        }
    }

    fun handleInventory(frame: ChatFrame.EncryptedRelayInventory) {
        val plaintext = decryptOrNull(frame.payload) ?: return
        val theirIds = RelayFramePlaintext.decodeIdList(plaintext)
        if (theirIds.isEmpty()) return
        scope.launch {
            val alreadyHeld = relayEnvelopeRepository.getAllIds().toSet()
            val alreadyDelivered = messageRepository.getExistingIds(theirIds).toSet()
            val missing = theirIds.filter { it !in alreadyHeld && it !in alreadyDelivered }
            if (missing.isNotEmpty()) {
                sendEncrypted(RelayFramePlaintext.encodeIdList(missing)) { ChatFrame.EncryptedRelayRequest(it) }
            }
        }
    }

    fun handleRequest(frame: ChatFrame.EncryptedRelayRequest) {
        val plaintext = decryptOrNull(frame.payload) ?: return
        val requestedIds = RelayFramePlaintext.decodeIdList(plaintext)
        if (requestedIds.isEmpty()) return
        scope.launch {
            relayEnvelopeRepository.getByIds(requestedIds).forEach { envelope ->
                sendEncrypted(RelayFramePlaintext.encodeEnvelope(envelope)) { ChatFrame.EncryptedRelayPush(it) }
            }
        }
    }

    fun handlePush(frame: ChatFrame.EncryptedRelayPush) {
        val plaintext = decryptOrNull(frame.payload) ?: return
        val envelope = RelayFramePlaintext.decodeEnvelope(plaintext) ?: run {
            BeaconLog.w(TAG, "Malformed relay envelope")
            return
        }
        scope.launch {
            if (envelope.finalRecipientId == identity.publicKey) {
                deliverToSelf(envelope)
            } else {
                // Received from this hop, one further from origin than whatever count
                // arrived on the wire; storage's own hop/age caps (D-033) decide from
                // here whether it's still worth carrying onward at all.
                relayEnvelopeRepository.store(envelope.copy(hopCount = envelope.hopCount + 1))
            }
        }
    }

    // D-031: verify against the *original* sender's identity key, not whoever handed us
    // this envelope, exactly what makes the signature meaningful across untrusted hops.
    // A failed verification here is expected, recoverable input handling (a malformed or
    // tampered envelope), not a bug, same posture ChatConnection takes on a bad handshake.
    private suspend fun deliverToSelf(envelope: RelayEnvelope) {
        val senderEphemeralPublicKey = try {
            cryptoService.decodeAndVerifyEphemeralPublicKey(
                envelope.originSenderId,
                envelope.senderEphemeralPublicKey,
                envelope.senderEphemeralPublicKeySignature
            )
        } catch (e: Exception) {
            BeaconLog.w(TAG, "Malformed relay envelope sender key", e)
            null
        }
        if (senderEphemeralPublicKey == null) {
            BeaconLog.w(TAG, "Relay envelope signature verification failed, dropping")
            return
        }

        val ourEncryptionPrivateKey = cryptoService.decodeEncryptionPrivateKey(identity.encryptionPrivateKey)
        val envelopeKey = cryptoService.deriveEnvelopeKey(ourEncryptionPrivateKey, senderEphemeralPublicKey)
        val plaintext = try {
            cryptoService.decrypt(envelopeKey, envelope.ciphertext)
        } catch (e: Exception) {
            BeaconLog.w(TAG, "Relay envelope decryption failed, dropping", e)
            return
        }

        // Milestone 12 (D-060): kind is the only place a MESSAGE and an ACK envelope are
        // actually treated differently, everything above this point (signature
        // verification, envelope key derivation, decryption) is identical either way.
        when (envelope.kind) {
            RelayEnvelopeKind.MESSAGE -> deliverMessage(envelope, plaintext)
            RelayEnvelopeKind.ACK -> deliverAck(plaintext)
        }
    }

    private suspend fun deliverMessage(envelope: RelayEnvelope, plaintext: ByteArray) {
        peerRepository.recordKnownFromRelay(envelope.originSenderId, envelope.originDisplayName)
        val (messageId, content) = ChatMessagePlaintext.decodeMessage(plaintext)
        val conversation = conversationRepository.getOrCreate(envelope.originSenderId)
        // Milestone 12 (D-061): null means this was a duplicate delivery (an earlier
        // arrival already acked it), only a genuinely new delivery gets a fresh ack.
        val delivered = messageRepository.receiveIncoming(conversation.id, messageId, content) ?: return
        val ackEnvelope = buildAckEnvelope(envelope, delivered.id) ?: return
        relayEnvelopeRepository.store(ackEnvelope)
    }

    private suspend fun deliverAck(plaintext: ByteArray) {
        val ackedMessageId = ChatMessagePlaintext.decodeAck(plaintext)
        messageRepository.getById(ackedMessageId)?.let { messageRepository.markDelivered(it) }
    }

    // Milestone 12 (D-059/D-060): the return trip uses the exact same ECDH/HKDF/AES-GCM
    // pipeline as the original message, just addressed the other way, encrypted to the
    // origin's own authenticated encryption key (carried in the envelope being acked, for
    // exactly this purpose) rather than one learned through discovery. Null means the
    // origin's embedded key didn't verify, an ack simply isn't sent, the message itself
    // was already delivered successfully regardless of whether this ack can go out.
    private fun buildAckEnvelope(originalEnvelope: RelayEnvelope, messageId: String): RelayEnvelope? {
        val originEncryptionPublicKey = try {
            cryptoService.decodeAndVerifyEncryptionPublicKey(
                originalEnvelope.originSenderId,
                originalEnvelope.originEncryptionPublicKey,
                originalEnvelope.originEncryptionPublicKeySignature
            )
        } catch (e: Exception) {
            BeaconLog.w(TAG, "Malformed origin encryption key, cannot ack", e)
            null
        }
        if (originEncryptionPublicKey == null) {
            BeaconLog.w(TAG, "Origin encryption key signature verification failed, dropping ack")
            return null
        }

        val ephemeralKeyPair = cryptoService.generateEphemeralKeyPair()
        val envelopeKey = cryptoService.deriveEnvelopeKey(ephemeralKeyPair.private, originEncryptionPublicKey)
        val ciphertext = cryptoService.encrypt(envelopeKey, ChatMessagePlaintext.encodeAck(messageId))
        val signature = cryptoService.signEphemeralPublicKey(identity.keystoreAlias, ephemeralKeyPair.public)

        return RelayEnvelope(
            // A fresh id, not messageId: this ack's own row can be in flight, in the
            // opposite direction, at the same time as the envelope it's acknowledging
            // (D-060), the two must never share a gossip/storage identity.
            messageId = UUID.randomUUID().toString(),
            originSenderId = identity.publicKey,
            originDisplayName = identity.displayName,
            finalRecipientId = originalEnvelope.originSenderId,
            senderEphemeralPublicKey = ephemeralKeyPair.public.encoded,
            senderEphemeralPublicKeySignature = signature,
            originEncryptionPublicKey = identity.encryptionPublicKey,
            originEncryptionPublicKeySignature = identity.encryptionPublicKeySignature,
            ciphertext = ciphertext,
            kind = RelayEnvelopeKind.ACK,
            ackedMessageId = messageId,
            hopCount = 0,
            createdAt = System.currentTimeMillis(),
            receivedAt = System.currentTimeMillis()
        )
    }

    private fun sendEncrypted(plaintext: ByteArray, wrap: (ByteArray) -> ChatFrame) {
        sendFrame(wrap(cryptoService.encrypt(sessionKey, plaintext)))
    }

    private fun decryptOrNull(payload: ByteArray): ByteArray? =
        try {
            cryptoService.decrypt(sessionKey, payload)
        } catch (e: Exception) {
            BeaconLog.w(TAG, "Relay frame decryption failed", e)
            null
        }

    private companion object {
        const val TAG = "RelayGossipSession"
    }
}
