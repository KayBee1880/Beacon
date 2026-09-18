package com.beacon.data

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

private const val BASE_RETRY_DELAY_MS = 5_000L
private const val MAX_RETRY_DELAY_MS = 5 * 60_000L
private const val MAX_RETRY_COUNT = 5

private fun newRepository(): Triple<MessageRepository, FakeMessageDao, FakeConversationDao> {
    val messageDao = FakeMessageDao()
    val conversationDao = FakeConversationDao()
    val peerRepository = PeerRepository(FakePeerDao())
    val relayEnvelopeRepository = RelayEnvelopeRepository(FakeRelayEnvelopeDao())
    val identityRepository = IdentityRepository(FakeIdentityDao())
    val repository = MessageRepository(messageDao, conversationDao, peerRepository, relayEnvelopeRepository, identityRepository)
    return Triple(repository, messageDao, conversationDao)
}

private fun outgoingMessage(retryCount: Int = 0, conversationId: String = UUID.randomUUID().toString()) = Message(
    id = UUID.randomUUID().toString(),
    conversationId = conversationId,
    direction = MessageDirection.OUTGOING,
    content = "hello",
    status = MessageStatus.SENDING,
    createdAt = System.currentTimeMillis(),
    retryCount = retryCount
)

/**
 * Milestone 11 (D-058): "simulated packet loss" here means repeated injected send
 * failures, exactly what MessageRetryCoordinator would be reacting to on a real device;
 * these tests check the measured backoff schedule and retry-exhaustion outcome against
 * what docs/05 §1 documents, not just trust the doc comment.
 *
 * D-057's scope limit applies here too, not only to CryptoService directly:
 * scheduleRetry's relay fallback (D-035), once a peer's encryption key IS on file, calls
 * CryptoService.signEphemeralPublicKey, which reaches IdentityKeyStore, real-device-only.
 * Only the two fallback paths that never reach that call are tested here: the ordinary
 * backoff schedule, and exhaustion with no peer encryption key on file at all (fails at
 * an earlier guard, before ever touching AndroidKeyStore).
 */
class MessageRepositoryTest {

    @Test
    fun `scheduleRetry increments retryCount and schedules exponential backoff`() = runTest {
        val (repository, messageDao, _) = newRepository()
        var message = outgoingMessage()
        messageDao.stored.add(message)

        val expectedDelaysMs = listOf(1L, 2L, 4L, 8L, 16L).map { it * BASE_RETRY_DELAY_MS }

        expectedDelaysMs.forEachIndexed { index, expectedDelay ->
            val before = System.currentTimeMillis()
            message = repository.scheduleRetry(message)
            val after = System.currentTimeMillis()

            assertEquals(index + 1, message.retryCount)
            assertEquals(MessageStatus.SENDING, message.status)
            val nextRetryAt = requireNotNull(message.nextRetryAt)
            assertTrue(
                "attempt ${index + 1}: expected nextRetryAt within [$before+$expectedDelay, $after+$expectedDelay], was $nextRetryAt",
                nextRetryAt in (before + expectedDelay)..(after + expectedDelay)
            )
        }
    }

    @Test
    fun `backoff delay is capped once the exponential term would exceed it`() {
        // Not reachable through scheduleRetry itself: MAX_RETRY_COUNT (5) means the
        // exponential term (5s * 2^(n-1)) never actually climbs past MAX_RETRY_DELAY_MS
        // within the attempts the public API allows, a real, currently-dead safety net,
        // not vacuous, exactly why backoffDelayMillis is exercised directly here instead.
        val (repository, _, _) = newRepository()

        val uncappedIfNaive = BASE_RETRY_DELAY_MS * (1L shl (10 - 1)) // retryCount 10, far past MAX_RETRY_COUNT
        assertTrue("test fixture assumption: this retryCount should exceed the cap", uncappedIfNaive > MAX_RETRY_DELAY_MS)

        val actual = repository.backoffDelayMillis(10)

        assertEquals(MAX_RETRY_DELAY_MS, actual)
    }

    @Test
    fun `exhausting retries with no known peer falls to FAILED`() = runTest {
        val (repository, messageDao, conversationDao) = newRepository()
        val conversationId = UUID.randomUUID().toString()
        conversationDao.stored.add(Conversation(conversationId, peerId = "unknown-peer", createdAt = 0L, lastMessageAt = 0L))
        var message = outgoingMessage(retryCount = MAX_RETRY_COUNT, conversationId = conversationId)
        messageDao.stored.add(message)

        // The next attempt is the one that pushes retryCount past MAX_RETRY_COUNT.
        message = repository.scheduleRetry(message)

        assertEquals(MessageStatus.FAILED, message.status)
    }

    // Milestone 12 (D-061): receiveIncoming's null-on-duplicate return is what lets
    // RelayGossipSession.deliverMessage decide whether to build an ack at all, these two
    // tests are the real decision boundary that logic depends on, no crypto involved.
    @Test
    fun `receiveIncoming returns the new Message and touches the conversation`() = runTest {
        val (repository, _, conversationDao) = newRepository()
        val conversationId = UUID.randomUUID().toString()
        conversationDao.stored.add(Conversation(conversationId, peerId = "peer", createdAt = 0L, lastMessageAt = 0L))
        val messageId = UUID.randomUUID().toString()

        val delivered = repository.receiveIncoming(conversationId, messageId, "hello")

        assertEquals(messageId, delivered?.id)
        assertEquals(MessageStatus.DELIVERED, delivered?.status)
        val conversation = requireNotNull(conversationDao.get(conversationId))
        assertTrue(conversation.lastMessageAt > 0L)
    }

    @Test
    fun `receiveIncoming returns null for a duplicate delivery and does not re-touch the conversation`() = runTest {
        val (repository, _, conversationDao) = newRepository()
        val conversationId = UUID.randomUUID().toString()
        conversationDao.stored.add(Conversation(conversationId, peerId = "peer", createdAt = 0L, lastMessageAt = 0L))
        val messageId = UUID.randomUUID().toString()
        repository.receiveIncoming(conversationId, messageId, "hello")
        val touchedAtFirstDelivery = requireNotNull(conversationDao.get(conversationId)).lastMessageAt

        val duplicate = repository.receiveIncoming(conversationId, messageId, "hello")

        assertEquals(null, duplicate)
        assertEquals(touchedAtFirstDelivery, requireNotNull(conversationDao.get(conversationId)).lastMessageAt)
    }
}
