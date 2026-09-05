# Beacon: Foundations

_Created 2026-07-20 21:45 CDT_

This document is the starting point for Beacon: what it is, who it's for, and the networking architecture decisions that shape everything downstream. Nothing here is implemented yet: this is the design conversation that precedes code, per the project's engineering process.

## 1. Problem Statement

Internet and cellular connectivity is an assumption baked into almost all modern messaging apps. That assumption breaks in predictable, recurring situations:

- **Disaster response**: cell towers are damaged, overloaded, or power-starved after earthquakes, hurricanes, wildfires.
- **Remote areas**: hiking, expeditions, rural field work with no cellular coverage at all.
- **Dense crowds**: festivals, stadiums, campuses where towers exist but are saturated (too many devices, not too little infrastructure).
- **Network shutdowns / censorship**: infrastructure is deliberately disabled or monitored.

In all four cases, the failure mode is the same: **devices are physically close to each other but cannot reach a server.** The internet, as a communication substrate, requires a chain of infrastructure (towers, ISPs, backbone) that is often the least reliable part of the system exactly when communication matters most.

**Offline-first** is a design philosophy where the absence of connectivity is the default assumption, not an edge case. A system built offline-first works standalone and treats synchronization with the wider world as an opportunistic bonus, not a requirement for basic function. This is different from "works offline as a degraded mode" (e.g., a note-taking app that queues writes). In Beacon, two phones with no infrastructure in sight need to be able to complete a full conversation.

**Mesh networking** extends this: if device A can't reach device C directly, but B can reach both, B can relay. Range becomes a property of the *network* (how many peers are willing to relay), not of any single radio. This is what lets offline-first survive at more than "two people standing next to each other" scale.

## 2. Primary Users

| Persona | Context | Core need |
|---|---|---|
| Emergency responder / disaster volunteer | Infrastructure down, coordinating a search or relief effort | Reliable delivery to a small team, works for hours/days without power grid |
| Remote expedition member | No cellular coverage by design (backcountry, research station) | Peer-to-peer chat + location awareness, extreme battery efficiency |
| Event/campus attendee | Towers up but congested | Fast local discovery, doesn't compete with cellular at all |
| Field operations team | Semi-fixed area, need situational awareness of who's nearby | Visualize peers on a map, understand link quality, not just "connected/not" |

**Non-goals for v1** (explicitly out of scope, revisit later): internet-scale mesh, anonymity/traffic-analysis resistance (Tor-grade), multi-hop internet gatewaying, group calls/voice.

## 3. Networking Architecture Options

Two independent axes matter: **transport** (which radio moves the bytes) and **topology** (how peers are logically connected).

### 3.1 Transport comparison

| | Bluetooth LE | Wi-Fi Direct | Hybrid (BLE + Wi-Fi) |
|---|---|---|---|
| Throughput | Very low (~1–2 Mbps theoretical, far less in practice with GATT overhead) | High (tens of Mbps) | Best of both |
| Range | ~10–30m typical, better with BLE 5 extended advertising | ~50–100m, but requires an active connection | Same as components |
| Discovery cost | Cheap: advertising/scanning is designed for always-on background use | Expensive: active scanning, slower to form groups | BLE for discovery, Wi-Fi only when needed |
| Battery | Very low draw | Meaningfully higher draw | Low draw most of the time |
| Concurrent connections | Platform-limited (Android/iOS typically cap simultaneous GATT connections at a handful) | Fewer, heavier connections (group owner model) | N/A |
| Platform API support | Both iOS and Android expose central + peripheral roles | **Android only**: iOS has no public Wi-Fi Direct API (Apple pushes Multipeer Connectivity instead, which internally picks BLE/Wi-Fi/AWDL for you and hides the choice) | Depends on platform |

**Key implication:** BLE is the only transport with full, symmetric API access on both major mobile platforms. This makes the platform choice a *networking* decision, not just a tooling one, covered in §5.

### 3.2 Topology comparison

| Model | Description | Pros | Cons |
|---|---|---|---|
| Star | One hub device, others connect only to it | Simple, easy to reason about | Hub is a single point of failure; doesn't scale range |
| Full mesh, real-time multi-hop | Every device can relay live traffic for others immediately | Extends effective range dramatically | Broadcast storms, routing complexity, battery cost of always relaying |
| Store-and-carry (delay-tolerant / epidemic routing) | Devices carry messages and hand them off when they meet a peer, no live route required | Works with intermittent, never-simultaneous connectivity (classic DTN case) | Higher latency, needs strong duplicate suppression |
| Client-server with mesh fallback | Normal server-based app that falls back to local peer exchange when offline | Reuses familiar architecture, syncs cleanly when internet returns | Not truly offline-first; server dependency creeps back into every design decision |

**Precedent systems**, briefly:
- **Apple Multipeer Connectivity**: hybrid transport chosen automatically per-link, mesh-capable but limited to Apple devices.
- **Bridgefy**: BLE mesh, epidemic-style store-and-carry, designed for protest/disaster use.
- **FireChat**: one of the first mainstream mesh chat apps, BLE + Wi-Fi Direct hybrid, popularized during Hong Kong protests.
- **Briar**: strongest security posture (Tor-integrated when online, BLE/Wi-Fi mesh offline), sync via a well-defined transport-agnostic protocol layer.
- **Serval Mesh / military mesh systems**: treat mesh as an alternate transport for standard IP/telephony semantics, heavier infrastructure assumptions than a phone app can make.

Beacon's positioning is closest to Bridgefy/FireChat's transport model (BLE-first, hybrid later) with Briar's discipline around separating "protocol" from "transport."

## 4. Recommended Architecture (incremental)

Don't build mesh routing, Wi-Fi Direct, or conflict resolution on day one: each is justified by a specific need that single-hop BLE messaging will surface naturally.

**Phase 1: Direct, single-hop, BLE only.** Two nearby devices discover each other via BLE advertising/scanning, form one GATT connection, exchange encrypted messages directly. No relay, no Wi-Fi Direct. Text messages are small; BLE throughput is not a bottleneck here. This is the smallest slice that is a complete, useful product (two people, one room, no infrastructure).

**Phase 2: Local persistence + resilience.** Messages survive disconnects, retry, and app restarts. This is where delivery guarantees, acknowledgements, and idempotency get introduced, because Phase 1 will surface the need for them the moment a connection drops mid-message.

**Phase 3: Store-and-forward relay (mesh, still BLE).** Once single-hop is solid, add the ability for a device to carry a message meant for someone else and hand it off later. This is where duplicate suppression, TTL/hop-limits, and routing metadata get introduced, again, only once we've felt the pain single-hop doesn't relay past line-of-sight.

**Phase 4: Wi-Fi Direct for bulk transfer.** Introduced specifically for attachments/large payloads where BLE's throughput becomes the bottleneck, layered in as a second transport under the same message abstraction, not a rewrite.

**Phase 5: Synchronization across reconnects, conflict resolution.** Once two devices can be offline from each other for real durations and both mutate shared conversation state, we need a reconciliation strategy (timestamps vs. vector clocks vs. CRDTs; to be evaluated on its own before picking one).

This mirrors the milestone ordering in §7: networking capability is added exactly when the product need for it exists, not preemptively.

## 5. Client Platform Decision

**Decision: Android (Kotlin) native**, first and only client for the foreseeable roadmap.

Rationale (recap of §3.1): Android is the only major mobile OS exposing public APIs for both BLE central *and* peripheral roles, plus Wi-Fi Direct. iOS's Multipeer Connectivity hides transport selection behind a single abstraction, which would force Beacon's architecture to match Apple's choices instead of making its own, undermining the whole point of studying BLE vs. Wi-Fi Direct as distinct, deliberate decisions. This is captured as [ADR-0001](architecture/0001-android-first-client.md) (to be written when we start Milestone 0 scaffolding).

## 6. Repository Structure

```
Beacon/
├── docs/
│   ├── 00-foundations.md          (this file)
│   ├── architecture/              (ADRs, one file per major decision)
│   ├── protocol/                  (wire format, sequence diagrams)
│   ├── security/                  (threat model, crypto rationale)
│   └── testing/                   (network simulation, benchmark reports)
├── android/                       (Kotlin client app, Gradle project root)
└── tools/                         (dev scripts, network condition simulators)
```

No `core/` module yet: a platform-agnostic module only earns its keep once there's a second consumer of the protocol logic (e.g., a desktop simulator for adverse-network testing in Milestone 11). Introducing it now would be premature abstraction for a single-client project.

## 7. Milestone Roadmap

| # | Milestone | Delivers |
|---|---|---|
| 0 | Foundations (this doc) + repo scaffold | Product definition, architecture decision, empty repo structure |
| 1 | Identity & local persistence | User creates an identity, app has a local data store, no networking yet |
| 2 | BLE peer discovery | Devices see each other nearby, no messaging yet |
| 3 | Secure direct messaging | Encrypted 1:1 messages over a single BLE connection |
| 4 | Delivery resilience | Retries, acks, idempotency, reconnect handling |
| 5 | Conversations & history | Multi-message conversations, persisted, viewable |
| 6 | Store-and-forward relay | Multi-hop delivery via intermediate peers |
| 7 | Wi-Fi Direct bulk transport | Attachments, larger payloads |
| 8 | Synchronization & conflict resolution | Reconciling state after independent offline edits |
| 9 | Visualization & connection quality UX | Peer map, signal/link quality indicators |
| 10 | Observability | Structured logs, metrics, dev diagnostics vs. user-facing status |
| 11 | Adverse-network testing & benchmarking | Simulated packet loss/partition/churn, measured (not claimed) reliability numbers |

Each milestone will get its own architecture discussion before implementation, and close out with tradeoffs, risks, and interview-relevant talking points, per the project's process.
