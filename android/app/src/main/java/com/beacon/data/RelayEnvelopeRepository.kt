package com.beacon.data

class RelayEnvelopeRepository(private val relayEnvelopeDao: RelayEnvelopeDao) {

    suspend fun getAllIds(): List<String> = relayEnvelopeDao.getAllIds()

    suspend fun getByIds(ids: List<String>): List<RelayEnvelope> = relayEnvelopeDao.getByIds(ids)

    // D-033: an envelope past either bound is worth dropping outright rather than storing
    // and immediately being ineligible to relay further; both are provisional starting
    // guesses, unvalidated against real mesh traffic (docs/07 §7).
    suspend fun store(envelope: RelayEnvelope) {
        if (envelope.hopCount > MAX_HOP_COUNT) return
        if (System.currentTimeMillis() - envelope.createdAt > MAX_ENVELOPE_AGE_MS) return
        relayEnvelopeDao.insert(envelope)
        relayEnvelopeDao.deleteOlderThan(System.currentTimeMillis() - MAX_ENVELOPE_AGE_MS)
        relayEnvelopeDao.evictExcess(MAX_HELD_ENVELOPES)
    }

    private companion object {
        const val MAX_HOP_COUNT = 6
        const val MAX_ENVELOPE_AGE_MS = 72 * 60 * 60 * 1000L
        const val MAX_HELD_ENVELOPES = 200
    }
}
