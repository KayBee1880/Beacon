package com.beacon.ble

import android.util.Log
import com.beacon.crypto.ChatMessagePlaintext
import com.beacon.crypto.CryptoService
import com.beacon.data.ConversationRepository
import com.beacon.data.Identity
import com.beacon.data.MessageRepository
import com.beacon.data.PeerRepository
import com.beacon.data.RelayEnvelope
import com.beacon.data.RelayEnvelopeRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
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
            Log.w(TAG, "Malformed relay envelope")
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
            Log.w(TAG, "Malformed relay envelope sender key", e)
            null
        }
        if (senderEphemeralPublicKey == null) {
            Log.w(TAG, "Relay envelope signature verification failed, dropping")
            return
        }

        val ourEncryptionPrivateKey = cryptoService.decodeEncryptionPrivateKey(identity.encryptionPrivateKey)
        val envelopeKey = cryptoService.deriveEnvelopeKey(ourEncryptionPrivateKey, senderEphemeralPublicKey)
        val plaintext = try {
            cryptoService.decrypt(envelopeKey, envelope.ciphertext)
        } catch (e: Exception) {
            Log.w(TAG, "Relay envelope decryption failed, dropping", e)
            return
        }

        peerRepository.recordKnownFromRelay(envelope.originSenderId, envelope.originDisplayName)
        val (messageId, content) = ChatMessagePlaintext.decodeMessage(plaintext)
        val conversation = conversationRepository.getOrCreate(envelope.originSenderId)
        messageRepository.receiveIncoming(conversation.id, messageId, content)
    }

    private fun sendEncrypted(plaintext: ByteArray, wrap: (ByteArray) -> ChatFrame) {
        sendFrame(wrap(cryptoService.encrypt(sessionKey, plaintext)))
    }

    private fun decryptOrNull(payload: ByteArray): ByteArray? =
        try {
            cryptoService.decrypt(sessionKey, payload)
        } catch (e: Exception) {
            Log.w(TAG, "Relay frame decryption failed", e)
            null
        }

    private companion object {
        const val TAG = "RelayGossipSession"
    }
}
