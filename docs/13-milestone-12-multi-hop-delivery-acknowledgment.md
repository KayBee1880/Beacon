# Milestone 12: Multi-Hop Delivery Acknowledgment

_2026-09-18_

Delivers the one piece of scope Milestone 6 named and explicitly deferred (docs/07 §10, §9's "revisit when"): a relayed message's original sender currently has no way to know it ever reached the final recipient, only that it left this device. This milestone closes that gap without inventing new machinery, the ack is itself just another relay envelope, riding the exact same gossip/storage/bounding infrastructure Milestone 6 already built.

## 1. What already exists vs. what this milestone adds

Already in place: a message whose direct retries are exhausted (D-025/D-035) is wrapped into a `RelayEnvelope`, end-to-end encrypted to the final recipient's long-term encryption key, and gossiped hop by hop until it reaches them (docs/07). `RelayGossipSession.deliverToSelf` decrypts it, verifies the origin's signature over the ephemeral key, and delivers it as a real `Message`. The `Message` row is marked `SENT` at the moment it enters the mesh and never changes again in this milestone's absence, `SENT` is an honest ceiling, not a false claim, but it's also the last word the sender ever gets.

Not yet built: any return path. The final recipient has no way to tell the original sender "I got it," because doing so means encrypting something back to someone the recipient may never have met directly, exactly the same problem the original message solved, just in the other direction.

## 2. The core problem: acking a stranger requires a return address

**Context:** `RelayGossipSession.deliverToSelf` already has everything it needs to *read* a relayed message: the origin's identity public key (`originSenderId`), display name, and a verified per-message ephemeral key. What it does not have is the origin's long-term *encryption* public key, the one thing needed to encrypt anything back to them. That key was never part of the envelope, there was never a reason to put it there before now, the recipient's own long-term encryption key already gets learned through ambient discovery (docs/07 §4) precisely because a *sender* needs it before the first send. A recipient acking a message has the opposite problem: they may be acking someone they've never discovered directly at all, that's the entire point of relay.

**Naive approach that breaks:** have the recipient look up the origin's encryption key the normal way, via `PeerRepository`, populated by discovery. This works only if the recipient has directly met the origin at some point, which is not guaranteed and often exactly the case that made relay necessary in the first place. Rejected, it would silently fail to ack most of the messages that actually needed relaying to begin with.

**Decision (D-059): every relay envelope carries its own creator's authenticated long-term encryption key.** `RelayEnvelope` gains `originEncryptionPublicKey` and `originEncryptionPublicKeySignature` (both Base64 `String`, matching `Identity`'s own encoding), populated from the envelope creator's own `Identity` row at build time, no extra signing operation needed, `Identity.encryptionPublicKeySignature` was already computed once at identity creation (docs/07 §3) and is just being carried along now, not recomputed. Whoever eventually needs to build a return-path envelope (a recipient acking a message, potentially other reply traffic later) already has an authenticated key to encrypt to, without ever needing a prior relationship with the addressee.

**Why authenticated, not trusted on sight:** the same rule as everywhere else in this codebase (D-014, D-030), never trust a key without verifying it against the identity that's supposed to own it. A malicious relay could otherwise substitute its own encryption key here and redirect where an eventual ack gets encrypted to, learning "message X was delivered" as metadata even though it still could never read the message content itself (that ciphertext is separately protected). Verification closes even that narrower leak.

**Alternatives considered:**
- **Look up the origin's key via `PeerRepository` at ack time, fall back to not acking if unknown**: rejected, this is a majority-case failure, not an edge case, since relay exists specifically for peers the recipient hasn't necessarily met.
- **A dedicated key-request round-trip before acking**: adds a whole new protocol exchange to learn one already-public value the sender was always going to send anyway; strictly worse than just including it.

**Revisit when:** never expected to change; this is the same trust-establishment pattern the rest of the relay system already relies on, not a provisional stand-in.

## 3. Decision: the ack is a `RelayEnvelope`, not a new concept

**Decision (D-060): `RelayEnvelope` gains a `kind` column (`RelayEnvelopeKind.MESSAGE` / `RelayEnvelopeKind.ACK`) and a nullable `ackedMessageId`.** An ack envelope is built and stored by `RelayGossipSession` the moment `deliverToSelf` completes a genuinely new delivery of a `MESSAGE`-kind envelope: a fresh ephemeral keypair, ECDH against the origin's now-verified encryption key from §2, `CryptoService.encrypt`, signed the same way as any other envelope, addressed back with `finalRecipientId = originalEnvelope.originSenderId`. It is then handed to the exact same `relayEnvelopeRepository.store(...)` every other envelope goes through, subject to the same hop-count, age, and storage-cap bounds (docs/07 §7), gossiped by the exact same `RelayGossipSession.start`/`handleInventory`/`handleRequest`/`handlePush` machinery, with zero changes to any of those four functions. The only place `kind` is actually branched on is the last step of `deliverToSelf`: a `MESSAGE`-kind decrypted payload becomes a real `Message` (unchanged from Milestone 6); an `ACK`-kind decrypted payload (`ChatMessagePlaintext.decodeAck`, already exists, built for the single-hop ack in D-016) looks up the original outgoing `Message` by `ackedMessageId` and marks it `DELIVERED`.

**Why not a new table or a new protocol:** the entire value of Milestone 6's design was that a relay envelope is opaque, self-contained, and carried by devices with no stake in its content. An ack has exactly the same shape and exactly the same needs (survive being carried by intermediate, possibly-offline devices, docs/07 §9's own "revisit when" note), reusing the table and the gossip protocol means every existing bound, every existing test, and every existing piece of infrastructure just works, unchanged, for the return trip too.

**Why a fresh id for the ack row, not the original `messageId`:** the forward envelope for a message and its eventual ack can genuinely coexist in flight at the same time, carried by overlapping sets of devices, in opposite directions. `RelayEnvelope.messageId` is the table's primary key and the gossip protocol's dedup identity (`getAllIds`, `getExistingIds`); reusing the original message's id for the ack row would collide with (or be indistinguishable from) the still-circulating forward envelope. Minting a fresh UUID for the ack's own row, with `ackedMessageId` as a separate field naming what it's actually about, keeps the two fully independent without touching the gossip protocol's existing identity semantics at all.

**Alternatives considered:**
- **A composite primary key (`messageId`, `kind`)**: works, but forces a schema and DAO signature change (`getByIds`, `getExistingIds`, everything keyed on a bare `String` today) throughout code that has no other reason to change this milestone. A fresh id is a smaller, fully backward-compatible diff.
- **Send the ack back immediately over whatever connection the message just arrived on**: only works if that connection happens to lead toward the origin, which is usually false, that's the entire premise of needing relay in the first place. Storing it as a normal envelope and letting it gossip is the only approach that doesn't assume a lucky topology.

**Revisit when:** never expected to change; this is intended as the permanent shape for any future return-path traffic (see §6), not a provisional stand-in.

## 4. Decision: only ack a genuinely new delivery, not every redundant push

**Decision (D-061): `MessageRepository.receiveIncoming` returns `Message?`, null meaning "already existed, this was a duplicate."** `RelayGossipSession.deliverToSelf` only builds and stores an ack when the return value is non-null. The three existing call sites (`ChatConnection.handleMessage`, `ChatGattServer`, the pre-existing part of `deliverToSelf` itself) never used the return value, so this is a behavior-preserving signature change everywhere except the one new call site that needed the signal.

**Why this matters:** flood-based gossip (docs/07 §8) can genuinely deliver the same envelope to a device twice, from two different relay paths, before the first delivery's dedup write has settled. Without this check, `deliverToSelf` would mint and store a fresh ack envelope on every redundant delivery, not just the first, adding avoidable mesh traffic for something `markDelivered`'s own idempotency means the sender didn't need duplicated. This isn't a correctness bug either way (a duplicate ack is harmless, `markDelivered` just re-sets the same status), it's purely about not being wasteful with a resource (mesh bandwidth, storage slots) docs/07 §7 already treats as scarce enough to bound.

**Alternatives considered:**
- **Let duplicate acks happen, rely on the existing storage caps to bound the damage**: technically safe, but needlessly wasteful for a check that costs one already-in-hand return value.

**Revisit when:** never expected to change.

## 5. Decision: `DELIVERED` is now the honest, reachable ceiling for a relayed message too; no new status value needed

**Decision:** once an ack envelope is decrypted and its `ackedMessageId` matched to a local outgoing `Message`, that message is marked `DELIVERED` via the exact same `MessageRepository.markDelivered` the single-hop direct ack (D-016) already calls, no new `MessageStatus` value, no new string resource, `ChatScreen`'s existing `messageStatusText` mapping is unchanged. `SENT` keeps meaning exactly what it always has, "left this device, not yet confirmed", now honestly true for however long the mesh takes to carry the ack back, rather than forever.

**Why this settles docs/07 §9's deferred idea instead of building it:** that section named "give relayed messages a new status value distinct from `SENT`" as a possible future addition specifically *because* there was no way to distinguish "confirmed delivered" from "confirmed sent but unknown beyond that." Now that a real confirmation path exists, `DELIVERED` already means the right thing without a new label; the deferred idea solved a problem this milestone removes rather than one it needs to also solve.

**Alternatives considered:**
- **A distinct `RELAYED_DELIVERED` status, to let the UI show *how* a message was delivered**: rejected as a UI nicety with no behavioral need behind it yet; nothing in any current screen distinguishes delivery path today (a direct `DELIVERED` and a relay `DELIVERED` already look identical to the user, correctly, since the distinction has never mattered to what "delivered" promises).

**Revisit when:** a future feature actually wants to show delivery path to the user (unlikely, and not asked for); until then this is settled, not provisional.

## 6. Evaluated and rejected: authenticating envelope metadata beyond the ciphertext and the ephemeral-key signature

Checked directly, in the style of Milestone 8's own evaluation, rather than assumed: could a malicious relay tamper with `kind` or `ackedMessageId` (neither is covered by the ephemeral-key signature, which only ever covered the ephemeral key itself, or by AES-GCM's tag, which only covers the ciphertext) to cause harm?

- **Flipping a real ack's `kind` to `MESSAGE`:** the decrypted ack plaintext is 36 raw bytes (`ChatMessagePlaintext.encodeAck`'s exact format), which `decodeMessage` would parse as `(id=those 36 bytes, content="")`. `receiveIncoming` would then attempt to insert a `Message` row whose primary key already exists, this is the sender's own original outgoing message id, so `insertIgnoreDuplicate` silently no-ops on the primary-key collision. No corruption, no new row, already handled by dedup logic that exists for an unrelated reason.
- **Flipping a real message's `kind` to `ACK`:** `decodeAck` takes the whole plaintext as a string with no length check; the result is a garbage id, looked up via `messageRepository.getById(...)`, which returns null for anything that isn't a real local message id (a UUID collision is cryptographically infeasible). No-op.
- **Forging `ackedMessageId` outright, or any other envelope field, without a valid signature over a real identity's ephemeral key:** already caught by the existing signature verification step every envelope goes through in `deliverToSelf`, unrelated to acks specifically.

**Conclusion:** no additional signature covering `kind`/`ackedMessageId` is needed this milestone. Every tampering path already resolves to a safe no-op through mechanisms (primary-key dedup, UUID-collision infeasibility, existing signature verification) that exist for other reasons. This is a genuine finding, not an assumption, worth writing down so a future reader doesn't have to re-derive it, exactly the standard docs/08 and docs/09 already set.

## 7. Deliberately deferred

- **Read receipts.** This milestone answers "did it arrive," not "did anyone read it." A read receipt would need its own trigger point (opening `ChatScreen`) and arguably its own privacy consideration (do users want senders to know they've read something); a separate, later decision, not implied by this one.
- **Acking a message that fell all the way to `FAILED`.** Per docs/07 §9, `FAILED` only happens when the peer was never resolved at all, no encryption key on file, nothing could have been relayed either. There is nothing to ack because nothing was ever sent into the mesh.
- **Retrying a lost ack specifically.** An ack envelope is bound by the exact same hop/age/storage caps as everything else (docs/07 §7); if it's evicted or expires before reaching the origin, the sender simply never learns delivery happened, the same honest, already-accepted "epidemic, best-effort" property flood gossip already carries for messages themselves (docs/07 §10). No special-cased ack reliability beyond what the mesh already offers everything.
- **Any use of the return-path machinery beyond acks** (read receipts, typing indicators, anything else that would want to reply to someone not yet met directly). D-060 deliberately built the `kind` column and the origin-key carrying as a reusable shape, but nothing beyond `ACK` is being built now; this is future work earning its own decision if it's ever actually needed, not something to speculatively build out today.

## 8. Next step

Schema first (`RelayEnvelope.kind`/`ackedMessageId`/`originEncryptionPublicKey`/`originEncryptionPublicKeySignature`, a `RelayEnvelopeKind` enum + `Converters` entry, a destructive migration to version 5 per D-023's precedent), then the two small `CryptoService` additions (`decodeAndVerifyEncryptionPublicKey`, mirroring the existing ephemeral-key verifier), then `ChatFrame.kt`'s wire format extension for the four new fields, then `MessageRepository.receiveIncoming`'s `Message?` signature change and its three call sites, then `RelayGossipSession`'s `deliverToSelf` split into `deliverMessage`/`deliverAck`/`buildAckEnvelope`. Ready to start on that?
