package com.beacon.data

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

private const val MAX_HOP_COUNT = 6
private const val MAX_ENVELOPE_AGE_MS = 72 * 60 * 60 * 1000L
private const val MAX_HELD_ENVELOPES = 200

// FakeRelayEnvelopeDao lives in TestFakes.kt, shared with MessageRepositoryTest.

private fun envelope(hopCount: Int = 0, createdAt: Long = System.currentTimeMillis(), receivedAt: Long = System.currentTimeMillis()) =
    RelayEnvelope(
        messageId = UUID.randomUUID().toString(),
        originSenderId = "origin",
        originDisplayName = "Alice",
        finalRecipientId = "recipient",
        senderEphemeralPublicKey = byteArrayOf(1, 2, 3),
        senderEphemeralPublicKeySignature = byteArrayOf(4, 5, 6),
        originEncryptionPublicKey = "origin-encryption-key",
        originEncryptionPublicKeySignature = "origin-encryption-key-signature",
        ciphertext = byteArrayOf(7, 8, 9),
        kind = RelayEnvelopeKind.MESSAGE,
        ackedMessageId = null,
        hopCount = hopCount,
        createdAt = createdAt,
        receivedAt = receivedAt
    )

/**
 * Milestone 11 (D-058): D-033's hop/age/storage bounds are the "churn" this app's own
 * relay design was actually built to survive; these tests inject envelopes right at and
 * past each bound and check the measured outcome against what docs/07 §7 documents,
 * rather than trusting the doc comment alone.
 */
class RelayEnvelopeRepositoryTest {

    @Test
    fun `an envelope within bounds is stored`() = runTest {
        val dao = FakeRelayEnvelopeDao()
        val repository = RelayEnvelopeRepository(dao)

        repository.store(envelope(hopCount = 1))

        assertEquals(1, dao.stored.size)
    }

    @Test
    fun `an envelope past the hop count bound is dropped, not stored`() = runTest {
        val dao = FakeRelayEnvelopeDao()
        val repository = RelayEnvelopeRepository(dao)

        repository.store(envelope(hopCount = MAX_HOP_COUNT + 1))

        assertTrue(dao.stored.isEmpty())
    }

    @Test
    fun `an envelope exactly at the hop count bound is still stored`() = runTest {
        val dao = FakeRelayEnvelopeDao()
        val repository = RelayEnvelopeRepository(dao)

        repository.store(envelope(hopCount = MAX_HOP_COUNT))

        assertEquals(1, dao.stored.size)
    }

    @Test
    fun `an envelope older than the age bound is dropped, not stored`() = runTest {
        val dao = FakeRelayEnvelopeDao()
        val repository = RelayEnvelopeRepository(dao)
        val tooOld = System.currentTimeMillis() - MAX_ENVELOPE_AGE_MS - 1_000L

        repository.store(envelope(createdAt = tooOld))

        assertTrue(dao.stored.isEmpty())
    }

    @Test
    fun `storing past the held-envelope cap evicts the oldest by receivedAt`() = runTest {
        val dao = FakeRelayEnvelopeDao()
        val repository = RelayEnvelopeRepository(dao)
        val now = System.currentTimeMillis()

        // One envelope older (by receivedAt) than everything about to fill the cap.
        val oldest = envelope(receivedAt = now - 1_000_000L)
        repository.store(oldest)
        repeat(MAX_HELD_ENVELOPES) { index ->
            repository.store(envelope(receivedAt = now - index))
        }

        assertEquals(MAX_HELD_ENVELOPES, dao.stored.size)
        assertTrue(dao.stored.none { it.messageId == oldest.messageId })
    }
}
