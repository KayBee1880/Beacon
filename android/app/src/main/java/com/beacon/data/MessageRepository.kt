package com.beacon.data

import com.beacon.crypto.ChatMessagePlaintext
import com.beacon.crypto.CryptoService
import kotlinx.coroutines.flow.Flow
import java.util.UUID

class MessageRepository(
    private val messageDao: MessageDao,
    private val conversationDao: ConversationDao,
    private val peerRepository: PeerRepository,
    private val relayEnvelopeRepository: RelayEnvelopeRepository,
    private val identityRepository: IdentityRepository
) {
    private val cryptoService = CryptoService()

    fun observeForConversation(conversationId: String): Flow<List<Message>> =
        messageDao.observeForConversation(conversationId)

    suspend fun getById(messageId: String): Message? = messageDao.get(messageId)

    // The row is inserted before any GATT write is attempted (docs/04 §8's SENDING
    // state); the id is generated here, client-side, so the same id travels with the
    // encrypted message and comes back on the ack (Journey 3's duplicate-detection id).
    suspend fun createOutgoing(conversationId: String, content: String): Message {
        val message = Message(
            id = UUID.randomUUID().toString(),
            conversationId = conversationId,
            direction = MessageDirection.OUTGOING,
            content = content,
            status = MessageStatus.SENDING,
            createdAt = System.currentTimeMillis()
        )
        messageDao.insert(message)
        conversationDao.touch(conversationId, message.createdAt)
        return message
    }

    // messageId here is the sender's original client-generated id, decrypted off the
    // wire, never a fresh one, so both devices' databases agree on which message this
    // is, which is what makes the ack path (and future retry dedup) actually work.
    suspend fun receiveIncoming(conversationId: String, messageId: String, content: String): Message {
        val now = System.currentTimeMillis()
        val message = Message(
            id = messageId,
            conversationId = conversationId,
            direction = MessageDirection.INCOMING,
            content = content,
            status = MessageStatus.DELIVERED,
            createdAt = now,
            deliveredAt = now
        )
        // A duplicate delivery (direct plus relay, or two relay paths for the same
        // message) must not re-touch the conversation's lastMessageAt to "now": that
        // would bump a conversation to the top of the list for a message it already had.
        if (messageDao.insertIgnoreDuplicate(message) != -1L) {
            conversationDao.touch(conversationId, now)
        }
        return message
    }

    suspend fun getExistingIds(ids: List<String>): List<String> = messageDao.getExistingIds(ids)

    // Milestone 7 (D-037): content is blank, there's no caption feature this milestone,
    // the file itself is the message. attachmentState starts LOCAL, a sender's own copy
    // already exists in full, unlike the receiver's, which starts OFFERED (below).
    // messageId is caller-supplied, not generated here, unlike createOutgoing: ChatScreen
    // has to name the copied-in local file *before* this row exists, and needs that same
    // id to be the one that ends up on the wire in the offer, so it generates the id first.
    suspend fun createOutgoingAttachment(
        messageId: String,
        conversationId: String,
        fileName: String,
        mimeType: String,
        sizeBytes: Long,
        contentHash: ByteArray,
        localPath: String
    ): Message {
        val message = Message(
            id = messageId,
            conversationId = conversationId,
            direction = MessageDirection.OUTGOING,
            content = "",
            status = MessageStatus.SENDING,
            createdAt = System.currentTimeMillis(),
            attachmentFileName = fileName,
            attachmentMimeType = mimeType,
            attachmentSizeBytes = sizeBytes,
            attachmentContentHash = contentHash,
            attachmentLocalPath = localPath,
            attachmentState = AttachmentState.LOCAL
        )
        messageDao.insert(message)
        conversationDao.touch(conversationId, message.createdAt)
        return message
    }

    // status = DELIVERED immediately, the same convention receiveIncoming already
    // established for text: the message construct (here, its metadata) has arrived the
    // moment this runs. localPath already points to where ChatGattServer.handleAttachmentOffer
    // will write the file, even though it doesn't exist there yet, attachmentState = OFFERED
    // is what actually reflects that the bytes haven't arrived.
    suspend fun receiveIncomingAttachmentOffer(
        conversationId: String,
        messageId: String,
        fileName: String,
        mimeType: String,
        sizeBytes: Long,
        contentHash: ByteArray,
        localPath: String
    ): Message {
        val now = System.currentTimeMillis()
        val message = Message(
            id = messageId,
            conversationId = conversationId,
            direction = MessageDirection.INCOMING,
            content = "",
            status = MessageStatus.DELIVERED,
            createdAt = now,
            deliveredAt = now,
            attachmentFileName = fileName,
            attachmentMimeType = mimeType,
            attachmentSizeBytes = sizeBytes,
            attachmentContentHash = contentHash,
            attachmentLocalPath = localPath,
            attachmentState = AttachmentState.OFFERED
        )
        if (messageDao.insertIgnoreDuplicate(message) != -1L) {
            conversationDao.touch(conversationId, now)
        }
        return message
    }

    suspend fun markAttachmentState(message: Message, state: AttachmentState): Message {
        val updated = message.copy(attachmentState = state)
        messageDao.update(updated)
        return updated
    }

    suspend fun markSent(message: Message): Message {
        val updated = message.copy(status = MessageStatus.SENT)
        messageDao.update(updated)
        return updated
    }

    suspend fun markDelivered(message: Message): Message {
        val updated = message.copy(status = MessageStatus.DELIVERED, deliveredAt = System.currentTimeMillis())
        messageDao.update(updated)
        return updated
    }

    suspend fun markFailed(message: Message): Message {
        val updated = message.copy(status = MessageStatus.FAILED)
        messageDao.update(updated)
        return updated
    }

    suspend fun getRetryableForConversation(conversationId: String): List<Message> =
        messageDao.getRetryableForConversation(conversationId, System.currentTimeMillis())

    // D-025: the one place a send failure is actually handled now, whether the failing
    // attempt was the user's original send or a later background retry (docs/05 §8).
    // Schedules the next attempt with exponential backoff (D-022), or gives up and calls
    // markFailed once the retry budget (provisional, see docs/05 §1) is exhausted.
    suspend fun scheduleRetry(message: Message): Message {
        val newRetryCount = message.retryCount + 1
        if (newRetryCount > MAX_RETRY_COUNT) {
            return fallbackToRelayOrFail(message)
        }
        val updated = message.copy(
            retryCount = newRetryCount,
            nextRetryAt = System.currentTimeMillis() + backoffDelayMillis(newRetryCount)
        )
        messageDao.update(updated)
        return updated
    }

    // D-035: once direct retry is exhausted, hand off to the mesh instead of giving up
    // outright, but only if this peer's encryption key is actually on file (they've been
    // resolved at least once, ever); with nothing to encrypt to, there's nothing relay
    // can do that direct retry hasn't already tried, so it falls to FAILED exactly as it
    // did before this milestone.
    private suspend fun fallbackToRelayOrFail(message: Message): Message {
        val conversation = conversationDao.get(message.conversationId) ?: return markFailed(message)
        val recipientEncryptionPublicKey = peerRepository.get(conversation.peerId)?.encryptionPublicKey
            ?: return markFailed(message)
        val identity = identityRepository.get() ?: return markFailed(message)

        relayEnvelopeRepository.store(buildRelayEnvelope(identity, conversation.peerId, recipientEncryptionPublicKey, message))
        // SENT, not DELIVERED: this device's job is done, but there is no return path
        // yet for a delivery acknowledgment to travel back across the mesh (docs/07 §9).
        return markSent(message)
    }

    private fun buildRelayEnvelope(
        identity: Identity,
        finalRecipientId: String,
        recipientEncryptionPublicKeyBase64: String,
        message: Message
    ): RelayEnvelope {
        val ephemeralKeyPair = cryptoService.generateEphemeralKeyPair()
        val recipientEncryptionPublicKey = cryptoService.decodeEncryptionPublicKey(recipientEncryptionPublicKeyBase64)
        val envelopeKey = cryptoService.deriveEnvelopeKey(ephemeralKeyPair.private, recipientEncryptionPublicKey)
        val ciphertext = cryptoService.encrypt(envelopeKey, ChatMessagePlaintext.encodeMessage(message.id, message.content))
        val signature = cryptoService.signEphemeralPublicKey(identity.keystoreAlias, ephemeralKeyPair.public)

        return RelayEnvelope(
            messageId = message.id,
            originSenderId = identity.publicKey,
            originDisplayName = identity.displayName,
            finalRecipientId = finalRecipientId,
            senderEphemeralPublicKey = ephemeralKeyPair.public.encoded,
            senderEphemeralPublicKeySignature = signature,
            ciphertext = ciphertext,
            hopCount = 0,
            createdAt = message.createdAt,
            receivedAt = System.currentTimeMillis()
        )
    }

    private fun backoffDelayMillis(retryCount: Int): Long {
        val exponential = BASE_RETRY_DELAY_MS * (1L shl (retryCount - 1))
        return minOf(exponential, MAX_RETRY_DELAY_MS)
    }

    private companion object {
        // Provisional starting guesses (docs/05 §1), expected to change once real
        // device testing shows actual reconnection timing, not validated numbers.
        const val MAX_RETRY_COUNT = 5
        const val BASE_RETRY_DELAY_MS = 5_000L
        const val MAX_RETRY_DELAY_MS = 5 * 60_000L
    }
}
