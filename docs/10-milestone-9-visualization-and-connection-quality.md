# Milestone 9: Visualization & Connection Quality UX

_2026-09-16_

Delivers what docs/00's roadmap names for this milestone: "peer map, signal/link quality indicators," the need docs/00 §2 names directly for the field-operations persona: "visualize peers on a map, understand link quality, not just 'connected/not.'"

## 1. What already exists vs. what this milestone adds

Already in place: coarse signal-strength bucketing (`SignalStrength`, `bucketRssi`, `NearbyPeerUiState`, all currently `private` inside `MainActivity.kt`), a 30-second nearby grace period, and a three-way reachability distinction that already exists in the data even though nothing surfaces it: `Peer.lastSeenAt` is either a real recent timestamp (met directly, still in range), a real old timestamp (met directly, out of range now), or the `0L` sentinel `PeerRepository.recordKnownFromRelay` (D-036) writes for a peer only ever learned about through a relayed message, never met directly at all.

Not yet built: anything that surfaces that three-way distinction to the user, any visual (as opposed to list-row) representation of the mesh, and any connection-quality indicator inside an open chat itself.

## 2. Decision: no GPS map; a logical mesh topology instead

**Context:** "Peer map" could mean a literal geographic map (pins on a map view, requiring real device location) or a logical diagram of who this device can reach and how well.

**Decision:** A logical topology, not a geographic one. No location permission is requested anywhere in this milestone.

**Why:** Every location-adjacent permission this project has ever requested (`BLUETOOTH_SCAN`'s `neverForLocation` flag, `NEARBY_WIFI_DEVICES`'s same flag, docs/07 §8, docs/08 §8) was deliberately scoped to avoid deriving physical location, specifically because Beacon has no legitimate use for it and no design anywhere has ever assumed it exists. Introducing real GPS tracking now, for a visualization feature, would be a meaningfully larger privacy footprint than anything this project has taken on to reach this point, for a feature the field-ops persona's own stated need ("understand link quality, not just connected/not") doesn't actually require: knowing *how well* you can reach someone and *whether* you can reach them at all doesn't require knowing *where* either of you physically is.

**Alternatives considered:**
- **A real GPS map with peer pins**: would satisfy "map" most literally, rejected on the privacy-footprint grounds above; also technically heavier (a maps library or tile provider, a new dependency this project has avoided everywhere else per its own minimal-dependency stance).

**Revisit when:** a real product requirement for geographic awareness emerges independent of this milestone (none exists today; docs/00 §2's own persona need is fully satisfiable without it, as this document goes on to show).

## 3. Decision: a third top-level tab, fulfilling D-027's own predicted trigger

**Decision:** `MainActivity`'s `TopLevelTab` enum gains `MESH`, alongside `NEARBY`/`CONVERSATIONS`, with a third `NavigationBarItem`.

**Why:** D-027 already named this exact moment as its own revisit condition: "a third top-level tab is ever needed and two text labels start feeling cramped, or if icons become worth the dependency for other reasons first." It has arrived; text labels for three short words ("Nearby," "Conversations," "Mesh") are still legible without icons, so the icon-dependency question stays deferred, only the tab count changes.

**Alternatives considered:**
- **Fold the mesh view into the existing Nearby screen** (a toggle or secondary view within it): considered, rejected, `PeerDiscoveryScreen` already has a clear, single job (the currently-in-range list); mixing in relay-known and previously-seen peers there would blur a distinction Journey 2 never asked for and complicate that screen's existing empty/populated logic for no benefit a separate tab doesn't already give more cleanly.

**Revisit when:** a fourth top-level destination is ever needed; three text labels is likely close to where icons start being worth it, per D-027's own note.

## 4. Decision: shared signal/reachability logic extracted out of `MainActivity.kt`

**Decision:** `SignalStrength`, `bucketRssi`, and `NEARBY_GRACE_PERIOD_MS` move out of `MainActivity.kt` (where they were `private`) into a new shared file, alongside a new `PeerReachability` enum (`DIRECT`, `RECENTLY_DIRECT`, `MESH_ONLY`) and a `classifyReachability(peer, now)` function implementing the three-way split described in §1.

**Why:** The new Mesh screen needs the exact same signal bucketing `PeerDiscoveryScreen` already has, and both need the exact same grace-period constant to agree on what "currently nearby" means; duplicating either would let the two screens drift out of sync on what should be one shared definition. This is the same "a second consumer justifies extracting what used to be fine as a private implementation detail" reasoning that already moved `ChatMessagePlaintext` into `crypto` in Milestone 6.

**Alternatives considered:**
- **Duplicate the bucketing logic in the new screen**: rejected, the two screens disagreeing on where "strong" ends and "medium" begins, or on how long the grace period is, would be a real, confusing inconsistency for no benefit.

**Revisit when:** not expected to change further; this is a permanent, minor refactor, not a provisional one.

## 5. Decision: a simple radial Canvas diagram for directly-reachable peers, a plain list for everyone else

**Decision:** The Mesh screen draws this device as a centered node, with every `DIRECT` peer (currently in range, within the grace period) placed evenly around it on a fixed-radius circle, connected by a line whose color/weight reflects that peer's `SignalStrength` bucket. `RECENTLY_DIRECT` and `MESH_ONLY` peers, which have no live signal to visualize, are listed as plain rows below the diagram, grouped under their own headings, not placed spatially. Tapping any peer, in the diagram or in a list, opens their chat, the same `selectedPeer` mechanism `PeerDiscoveryScreen` and `ConversationsScreen` both already use.

**Why:** Built with Compose's own `Canvas`/`DrawScope`, no new dependency, matching this project's consistent avoidance of pulling in a graph-layout or charting library for a need this small. Only `DIRECT` peers have a real quality signal (RSSI) to show at all, spatial placement for peers with no signal to represent would be inventing a visual meaning (position, distance) the underlying data doesn't actually have; an honest plain list for those two categories says exactly as much as is actually known, no more.

**Alternatives considered:**
- **A force-directed or multi-hop graph showing relay paths between peers**: real mesh visualization, rejected for this milestone, the data to support it doesn't exist yet either (docs/07 never built anything resembling a routing table, only an in-flight envelope store, see §7).
- **Static icons instead of a drawn diagram** (e.g., three fixed signal-bar icons per row, like a phone's own signal indicator): simpler, avoids `Canvas` entirely, rejected, this is closer to what `PeerDiscoveryScreen`'s existing text label already gives, it wouldn't add the spatial "map" quality the roadmap and the field-ops persona actually ask for.

**Revisit when:** relay path/hop data ever becomes rich enough to support a real multi-hop graph (see §7's revisit note).

## 6. Decision: a live signal indicator in `ChatScreen`'s header

**Decision:** `ChatScreen`'s header, which already shows `connectionStatusText`, gains a second line showing the peer's current `SignalStrength` (via the same shared `bucketRssi`/`peerDiscovery.rssiByPeerId`), when available, blank when not (the peer resolved once to open this chat but has since gone out of range, or was never resolved directly at all, e.g. a conversation opened from `ConversationsScreen` with a mesh-only peer).

**Why:** This is the other half of "understand link quality" the roadmap names, applied to the one screen where a user is actively relying on that link right now, not just browsing who's nearby. Reuses data (`rssiByPeerId`) and logic (`bucketRssi`) this milestone already centralized in §4, no new signal source needed.

**Alternatives considered:**
- **A numeric dBm readout instead of the bucketed word**: more precise, rejected, a raw dBm number means nothing to a non-technical user and every other signal display in the app (the Nearby list, the new Mesh diagram) already uses the same three-word bucket; a different treatment here would be an inconsistency, not an improvement.

**Revisit when:** not expected to change.

## 7. Deliberately deferred

- **Any geographic/GPS mapping**, per §2.
- **Multi-hop relay path visualization.** No routing-table-shaped data exists to visualize (docs/07's relay design is deliberately flood-based, not path-based, D-034); would need real new data collection, not just a new view over existing data, out of scope here.
- **Historical connection-quality trends** (a graph of signal strength or delivery success over time). Real, separable work; this milestone visualizes current state, not history.
- **Latency/throughput measurement.** No such measurement exists anywhere in the app yet; docs/00 §7 puts adverse-network testing and benchmarking at Milestone 11, deliberately after this one.
- **Tuning the radial diagram's layout** (peer ordering around the circle, handling a large number of simultaneously-nearby peers gracefully). A first, honestly basic layout; real crowding behavior can't be evaluated without real multi-peer scenarios, still blocked on hardware like everything since Milestone 2.

## 8. Next step

Extract the shared signal/reachability logic (§4) first, since both the existing `PeerDiscoveryScreen` and the new screen depend on it, then add `TopLevelTab.MESH` and its `NavigationBarItem` (§3), then build the new Mesh screen itself (§5), then `ChatScreen`'s header addition (§6). Ready to start on that?
