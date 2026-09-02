# Beacon — User Journeys (Phase 1)

_2026-07-20_

Concrete walk-throughs of what a user actually does, screen by screen, for the slice of Beacon we're building first (Milestones 1–5: identity, discovery, direct messaging, resilience, history). Each journey exists to surface the entities and decisions we'll need before we design the domain model — if a journey needs something, that something goes in the schema; if it doesn't, it doesn't, no matter how "complete" it would feel to add.

Each journey has: the steps a user experiences, what's happening underneath at each step, failure modes worth naming now, and open questions it raises for later milestones.

---

## Journey 1 — First Launch & Identity Creation

**Trigger:** User installs Beacon and opens it for the first time.

1. App opens to a one-time setup screen — no account, no server, no email/phone number. User picks a display name.
2. App generates a cryptographic identity locally (a keypair — the public key *is* the durable identifier peers will see; the private key never leaves the device). This happens with no network call, because none is possible or needed.
3. User lands on an empty "Nearby" screen — no peers yet, no conversations yet.

**Underneath:** A local identity record is created and persisted. There is no signup/login flow to design because there is no server to authenticate against — identity is self-issued, which is standard for peer-to-peer systems (this is the same shape Signal's/Briar's identity model takes, minus Signal's server-mediated phone-number verification).

**Failure modes to name now:**
- App is killed mid-setup, before the keypair is persisted → must not leave a half-created identity; setup either completes atomically or restarts cleanly.
- User reinstalls the app → they get a *new* identity by default. Peers who had the old one no longer recognize them. (Backup/restore of identity is explicitly out of scope for Phase 1 — noted so it doesn't get silently assumed away.)

**Open questions for Milestone 1 (entities/persistence):** What exactly is in a local identity record beyond the keypair (display name, created-at)? Is display name mutable later, and if so, do peers who already saw the old name get confused? (Flagging, not answering — this is a Milestone 1 design conversation.)

---

## Journey 2 — Discovering Nearby Peers

**Trigger:** User is on the "Nearby" screen with BLE/location permissions granted.

1. App starts advertising (so others can see *this* device) and scanning (so this device can see others), automatically, no separate "go online" button — discovery is meant to feel ambient.
2. As peers are found, they appear in a list in real time: display name, and some indicator of signal/connection quality (not just "found," since range and stability vary a lot with BLE).
3. Peers that move out of range disappear from the list after some grace period (not instantly on first missed advertisement — BLE advertising isn't perfectly reliable packet-to-packet).

**Underneath:** Two independent BLE roles running concurrently on the same device (central: scanning/connecting out; peripheral: advertising/accepting in) — this is why the architecture diagram splits `BleCentralRole` and `BlePeripheralRole` rather than treating BLE as one component. A peer entry exists the moment we've seen an advertisement, before any GATT connection is formed — discovery and connection are separate steps.

**Failure modes to name now:**
- Permissions denied (BLE scan requires location permission on Android) → app must degrade to "can't discover peers" clearly, not silently do nothing.
- Two peers with the same display name nearby → display name cannot be the identifier anything relies on; the public key must be, with display name as a label only.
- Radio disabled mid-session → peers already in the list shouldn't just vanish with no explanation.

**Open questions for Milestone 2:** What counts as "signal quality" for BLE in a way that's meaningful to show a user (RSSI is noisy) — this is exactly the "connection quality" UX the product brief calls out, deferred to its own milestone (9) for the full treatment, but the discovery list needs *something* minimal now.

---

## Journey 3 — Starting a Conversation & Sending a Message

**Trigger:** User taps a nearby peer from the list.

1. App opens a chat screen for that peer. If this is the first time talking to them, the conversation starts empty.
2. User types a message and sends it.
3. Message appears in the thread immediately with a "sending" state, then updates to "delivered" once the peer's device confirms receipt.
4. On the peer's device, the message arrives, decrypts, and appears in their chat screen with this user (their own "Nearby" or existing conversation entry).

**Underneath:** Tapping a peer either reuses an existing GATT connection (if discovery already connected) or triggers a new connect. The message is encrypted before it ever reaches the transport layer (per the app-layers diagram — Domain encrypts, Transport only ever moves ciphertext). "Delivered" is a real acknowledgement from the peer's device, not just "left this device" — that distinction is the seed of the delivery-guarantees discussion in Milestone 4.

**Failure modes to name now:**
- Connection drops after send but before the ack arrives → message must not be silently lost, and must not *look* delivered when it wasn't. This is the concrete case that makes "sending → delivered" a real state machine rather than a boolean.
- User sends the same message twice by mashing the button → needs a stable message ID assigned at creation time, not relying on network behavior, so duplicates can be detected.

**Open questions for Milestone 3:** Exact message state machine (sending → sent → delivered → failed, at minimum). What "read" receipts (if any) add on top of "delivered," and whether that's Phase 1 scope at all (leaning: not yet — delivered is enough for v1, read receipts are a UX nicety, not a resilience requirement).

---

## Journey 4 — Losing Connection Mid-Conversation

**Trigger:** Two peers are mid-chat and one walks out of BLE range (or turns off Bluetooth, or the app is backgrounded and the OS kills the connection).

1. The sender notices the connection dropped — chat screen shows a visible "peer disconnected" state, not silence.
2. Any message sent right before or during the drop shows as "not yet delivered," not "failed" outright — it may still succeed once the peer is back in range.
3. When the peer comes back into range, discovery picks them up again, and pending messages resume sending automatically — the user does not have to manually resend.
4. Once delivered, the message's status updates retroactively.

**Underneath:** This is the first journey that actually requires local persistence of outbound messages independent of the connection — a message that hasn't been acknowledged has to survive the app being backgrounded or the device sleeping, and be retried without user action. This is exactly the retry/resilience layer called out for Milestone 4, and it's why Milestone 4 has to come before "conversations & history" is really *done* — history that can silently lose in-flight messages isn't trustworthy history.

**Failure modes to name now:**
- Retry forever with no backoff → battery drain scanning/reconnecting for a peer that may never come back. Needs a bounded retry/backoff policy, and a real "failed, won't retry automatically" terminal state the user can act on (Milestone 4 territory, not answered here).
- Peer reconnects but with a fresh app install (new identity) → this "looks like" the same peer only if we let display name stand in for identity, which Journey 2 already ruled out. The old pending messages correctly fail to deliver to the new identity, and that's correct behavior, not a bug — worth being explicit about so it isn't accidentally "fixed" into matching on display name later.

---

## Journey 5 — Returning to the App Later

**Trigger:** User closes Beacon (or reboots their phone) and reopens it hours or days later, possibly with no peers nearby at all.

1. App opens straight to existing conversations — no re-setup, no re-authentication (there's nothing to authenticate against).
2. All prior message history is there, in the state it was last known (including any messages still marked "not yet delivered" from Journey 4).
3. "Nearby" list is empty until discovery finds someone again — the app doesn't pretend to know who's around from a stale cache.

**Underneath:** Reinforces that local persistence (Milestone 1's SQLite/Room layer) is the actual source of truth for the whole app, not an afterthought cache — the UI layer always reads from it, never from "whatever the last BLE session said." This is a case for a design choice, not a bespoke feature: nothing new to build here if Milestones 1–4 are built correctly, which is itself a useful sanity check on those milestones.

**Open questions:** None new — this journey is a *test* of earlier milestones more than a source of new requirements, which is a good sign.

---

## What these journeys settled

- **Identity is self-issued and durable per-install**, keyed by public key, never by display name. (Feeds Milestone 1 entity design.)
- **Discovery and connection are separate steps** — seeing a peer is not the same as being connected to them. (Feeds Milestone 2.)
- **Message delivery is a state machine (sending → delivered, at minimum), not a boolean**, and it's driven by real peer acknowledgement. (Feeds Milestone 3.)
- **Outbound messages must persist and auto-retry independent of the live connection.** (Feeds Milestone 4 — and confirms Milestone 4 has to land before "history" is trustworthy.)
- **The local database is the single source of truth the UI reads from — never the live BLE session state directly.** (Cross-cutting; reinforces the app-layers diagram's persistence layer already being separate from transport.)

Next natural step: Milestone 1 architecture — designing the actual entities (User/Identity, Peer, Conversation, Message at minimum) and the local persistence layer these journeys imply, before writing any Kotlin.
