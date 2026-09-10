package com.beacon.data

import kotlinx.coroutines.flow.Flow
import java.util.UUID

class MessageRepository(
    private val messageDao: MessageDao,
    private val conversationDao: ConversationDao
) {

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
        messageDao.insert(message)
        conversationDao.touch(conversationId, now)
        return message
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
            return markFailed(message)
        }
        val updated = message.copy(
            retryCount = newRetryCount,
            nextRetryAt = System.currentTimeMillis() + backoffDelayMillis(newRetryCount)
        )
        messageDao.update(updated)
        return updated
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
