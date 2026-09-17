package com.beacon

import com.beacon.data.Peer

// Milestone 2's original bucketing (docs/03 §6), extracted here in Milestone 9 (D-049)
// once a second screen (MeshScreen) needed the exact same definition PeerDiscoveryScreen
// already had: two screens agreeing on where "strong" ends and "medium" begins has to be
// one shared definition, not two copies that could silently drift apart.
enum class SignalStrength { STRONG, MEDIUM, WEAK }

const val NEARBY_GRACE_PERIOD_MS = 30_000L

fun bucketRssi(rssi: Int): SignalStrength = when {
    rssi >= -60 -> SignalStrength.STRONG
    rssi >= -80 -> SignalStrength.MEDIUM
    else -> SignalStrength.WEAK
}

// D-049: a three-way split that was already implicit in Peer's own data, never surfaced
// before MeshScreen needed it. DIRECT and RECENTLY_DIRECT both mean "met this peer over
// BLE directly at least once" (a real, non-zero lastSeenAt), split by the same grace
// period PeerDiscoveryScreen's nearby list already uses; MESH_ONLY means this Peer row
// only exists because of PeerRepository.recordKnownFromRelay (D-036), lastSeenAt is the
// 0L sentinel that can never satisfy the grace-period check.
enum class PeerReachability { DIRECT, RECENTLY_DIRECT, MESH_ONLY }

fun classifyReachability(peer: Peer, now: Long): PeerReachability = when {
    peer.lastSeenAt == 0L -> PeerReachability.MESH_ONLY
    now - peer.lastSeenAt <= NEARBY_GRACE_PERIOD_MS -> PeerReachability.DIRECT
    else -> PeerReachability.RECENTLY_DIRECT
}
