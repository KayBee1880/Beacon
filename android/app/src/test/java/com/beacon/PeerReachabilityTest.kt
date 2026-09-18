package com.beacon

import com.beacon.data.Peer
import org.junit.Assert.assertEquals
import org.junit.Test

private fun peer(lastSeenAt: Long) = Peer(id = "peer", displayName = "Alice", firstSeenAt = lastSeenAt, lastSeenAt = lastSeenAt)

/**
 * Milestone 11 (D-058): the "partition" half of this milestone's simulated scenarios,
 * a peer known but not currently reachable. No Android dependency here at all, this is
 * plain Kotlin over a Peer's own timestamp fields.
 */
class PeerReachabilityTest {

    @Test
    fun `a peer with the 0L sentinel is mesh-only, regardless of current time`() {
        val now = System.currentTimeMillis()

        assertEquals(PeerReachability.MESH_ONLY, classifyReachability(peer(lastSeenAt = 0L), now))
    }

    @Test
    fun `a peer seen within the grace period is direct`() {
        val now = System.currentTimeMillis()

        assertEquals(PeerReachability.DIRECT, classifyReachability(peer(lastSeenAt = now - 1_000L), now))
    }

    @Test
    fun `a peer seen exactly at the grace period boundary is still direct`() {
        val now = System.currentTimeMillis()

        assertEquals(PeerReachability.DIRECT, classifyReachability(peer(lastSeenAt = now - NEARBY_GRACE_PERIOD_MS), now))
    }

    @Test
    fun `a peer seen just past the grace period is recently direct, not mesh-only`() {
        val now = System.currentTimeMillis()

        assertEquals(
            PeerReachability.RECENTLY_DIRECT,
            classifyReachability(peer(lastSeenAt = now - NEARBY_GRACE_PERIOD_MS - 1L), now)
        )
    }

    @Test
    fun `a peer seen a long time ago is recently direct, not mesh-only`() {
        val now = System.currentTimeMillis()

        assertEquals(
            PeerReachability.RECENTLY_DIRECT,
            classifyReachability(peer(lastSeenAt = now - 30L * 24 * 60 * 60 * 1000), now)
        )
    }
}
