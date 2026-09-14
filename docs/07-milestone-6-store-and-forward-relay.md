# Milestone 6: Store and Forward Relay

_2026-09-12_

Delivers what docs/00's roadmap names for this milestone: multi-hop delivery via intermediate peers, the mesh part of "mesh messenger." Phase 3 of docs/00 §4's own incremental plan, positioned there deliberately, "once single-hop is solid, add the ability for a device to carry a message meant for someone else and hand it off later," which Milestones 3 and 4 have now made true.

## 1. What already exists vs. what this milestone adds

Already in place: a working direct, single-hop, encrypted, retried connection between two devices that can currently reach each other over BLE (Milestones 3 and 4). `MessageRetryCoordinator` already reacts to a peer coming back into range, but only for messages already addressed to that exact peer; it never does anything on behalf of a peer it has no direct conversation with. Nothing today lets a third device carry a message it isn't a party to.

Not yet built: any notion of a message surviving past the one device pair it was created for, any encryption that doesn't depend on a live connection to the final recipient, and any behavior that treats "I met a peer" as a reason to do something even when I have nothing of my own to say to them.

## 2. The core problem: hop by hop encryption cannot be handed to a stranger

**Context:** Milestone 3's only encryption key (D-015) is a session key derived live, per connection, between the two devices actually holding that GATT session. A relay is by definition a third device, neither the original sender nor the final recipient, so if a relayed message used that same session key, the relay itself would be able to decrypt it. That contradicts the entire premise of an encrypted messenger: a message should be unreadable to everyone except its intended recipient, including every device that happens to carry it partway there.

**Naive approach that breaks:** decrypt and re-encrypt at every hop, using each hop's own live session key. This works mechanically, every relay already has a session key to whichever peer it's currently connected to, but it means every single relay reads the plaintext. Rejected outright, not a tradeoff worth taking.

**Decision: a second, long-term, software-generated EC keypair per identity, used only for end-to-end ECDH to a final recipient (D-029).** The existing identity key (`IdentityKeyStore`) cannot be reused for this: it is generated with `KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY` only, and Android Keystore does not allow an EC key to mix signing and key agreement purposes (a real platform restriction, and reasonable practice besides, key separation avoids a family of cross-protocol attacks that come from using one asymmetric key for two different cryptographic jobs). A genuinely new key is required regardless of where it lives.

Where it lives was the real fork, decided directly with the project owner: Android Keystore's hardware-backed `PURPOSE_AGREE_KEY` exists, but only from API 31 onward, and this project's `minSdk` is 26; raising it would drop Android 8 through 11 for a crypto implementation detail, a real reach cost, not just a security one. The chosen path is a software-generated keypair (same `secp256r1` curve, same `KeyPairGenerator` call `CryptoService` already makes for ephemeral keys), persisted, private key held in Room. This is not a new risk category for the project: `docs/02` already accepted that the local database has no at-rest encryption (SQLCipher explicitly deferred), so a private key stored there is consistent with the risk already carried by every message and every `Peer` row already sitting in the same unencrypted database, not a new hole opened specifically for this milestone.

**Alternatives considered:**
- **Hardware Keystore key, raise `minSdk` to 31**: stronger key protection, rejected for now on reach grounds; revisit if the project ever needs to raise `minSdk` for an unrelated reason, at which point migrating this key onto `PURPOSE_AGREE_KEY` becomes close to free.
- **No end-to-end encryption this milestone, ship routing/storage mechanics only**: considered and explicitly rejected, a relay that can read what it relays is a regression the project's own security framing (ADR-0003, the Keystore-backed identity key) would not survive quietly.

**Revisit when:** `minSdk` is ever raised past 31 for any reason, or if Android's software `KeyPairGenerator` behavior ever changes in a way that makes an app-managed key meaningfully weaker than it is today.

## 3. Decision: the new key is authenticated the same way D-014 already authenticates ephemeral keys

**Decision:** on identity creation, alongside the existing Keystore signing key, generate the new long-term encryption keypair via `CryptoService`, then sign the encryption public key with the identity's Keystore-backed signing key (`IdentityKeyStore.sign`), exactly the same "authenticate an untrusted-until-verified key with the one key nobody can forge" pattern D-014 already established for ephemeral keys. `Identity` gains `encryptionPublicKey`, `encryptionPrivateKey` (Base64, software key material, not Keystore-backed), and `encryptionPublicKeySignature`.

**Why:** without this signature, a malicious relay (or anyone impersonating a peer during discovery) could hand out a fabricated encryption public key and have messages meant for the real recipient encrypted to an attacker instead. Signing it with the same identity key every peer already verifies handshake signatures against means no new trust mechanism is needed, only a new signed value flowing through machinery Milestone 3 already built.

**Alternatives considered:**
- **Trust the encryption key on first sight, no signature**: rejected, this is exactly the same "never fall back to trusting an unverified key" rule D-014's own docs/04 comment already states, applied here for the same reason.

**Revisit when:** never, barring a wholesale identity-key redesign; this is load-bearing for the rest of this milestone's trust model.

## 4. Decision: the encryption public key travels the same way the rest of identity does

**Decision:** `BeaconGattProfile.IDENTITY_SERVICE_UUID` gains an `ENCRYPTION_PUBLIC_KEY_CHARACTERISTIC_UUID` and an `ENCRYPTION_PUBLIC_KEY_SIGNATURE_CHARACTERISTIC_UUID`, read by `BleCentralRole` in the same resolve sequence that already reads the identity public key and display name (docs/03 §5), verified against the peer's already-known signing public key (`Peer.id`) via `IdentityKeyStore.verify` the moment they're read, the same "verify before trusting" discipline the handshake already follows. `Peer` gains only `encryptionPublicKey`, not the signature: the signature's one job is proving the key at this verification moment, nothing later ever re-checks it, so persisting it would be a column with no reader. A peer resolved before this milestone's code exists simply has no encryption key yet, no message can be end-to-end encrypted to them until they're resolved again, a real but self-healing gap (ambient discovery re-resolves peers continuously already).

**Why:** this is the only way a sender can encrypt a message to a recipient who is not currently reachable, the entire point of store and forward. It has to be learned from ambient discovery, not from a live chat connection, since a live connection to the final recipient is precisely what won't exist when relay is needed.

**Alternatives considered:**
- **Exchange encryption keys only during the Milestone 3 handshake**: useless for this milestone's purpose, the handshake only happens between two devices already directly connected, exactly the case that needs no relay.

**Revisit when:** the advertising byte budget (docs/03 §2) ever needs to grow to carry more identity data directly in the advertisement itself; not needed now since this is read over a GATT connection, not the advertisement packet, so the existing byte-budget constraint doesn't apply here at all.

## 5. Decision: the envelope format, and how integrity survives untrusted hops

**Decision:** a relay envelope is: `messageId` (the original sender's client-generated UUID, doubling as the mesh-wide dedup key), `originSenderId` (the original author's identity public key), `originDisplayName` (D-036, found necessary during implementation: a relay-delivered message can come from someone this device has never directly met, so there may be no `Peer` row to supply a name from, this is what lets the recipient create one), `finalRecipientId` (the intended recipient's identity public key), `senderEphemeralPublicKey` (fresh per message, not reused, same reasoning as D-013), `senderEphemeralPublicKeySignature` (signed by `originSenderId`'s identity key, verifiable by anyone who already trusts that identity, not just the final recipient), `ciphertext` (AES-256-GCM, same `CryptoService.encrypt` output shape as direct messages), `hopCount`, `createdAt`.

The encryption key itself is derived exactly the way D-015 already derives a session key, ECDH between the sender's fresh ephemeral private key and the recipient's long-term encryption public key, then HKDF, except with a distinct info string (`beacon-envelope-key-v1` vs. the existing `beacon-session-key-v1`) for domain separation, standard HKDF practice for deriving two unrelated keys from what could otherwise coincidentally overlap. No new cryptographic primitive exists anywhere in this design, `CryptoService`'s existing ECDH/HKDF/AES-GCM functions are reused verbatim, only the input key (long-term encryption key instead of a live ephemeral one) and the info string differ.

**Why the signature matters more here than in the direct case:** in Milestone 3, the two devices signing and verifying are the same two devices that will use the resulting key immediately, over a connection they both know is live. Here, the envelope may pass through several relays with no relationship to either the sender or recipient; the signature is what lets the *eventual* recipient (the only device that will ever actually decrypt it) verify the message really came from who it claims, without needing to trust a single hop it passed through along the way. Any relay could tamper with `originSenderId` or the ciphertext, but only the final recipient's verification step matters, and it will simply fail (reject the envelope, never surface it as a message) if anything was altered.

**Alternatives considered:**
- **Trust whichever device handed you the envelope**: rejected, this is exactly the "MITM via an untrusted intermediary" scenario the identity-binding work in docs/04 §6 already went out of its way to close for the direct case; leaving it open here for relay would undo that.

**Revisit when:** if a future milestone needs forward secrecy for relayed messages specifically (a compromised long-term encryption key would expose every message ever sent to that identity, unlike the ephemeral direct-message keys, which are used once and discarded); out of scope for this milestone, flagged in §9.

## 6. Decision: relay envelopes get their own table, not a `Message` row

**Decision:** a new Room entity, `RelayEnvelope`, holds everything from §5 plus `receivedAt` (when this device first stored it, distinct from `createdAt`). It has no foreign key to `Conversation`, a relay envelope usually is not for this device's own conversations at all, most of the time it is being carried purely on behalf of two other people. `messageId` is the primary key, giving dedup for free via `INSERT OR IGNORE`, the same pattern `ConversationRepository.getOrCreate` already uses for its own race safety.

`Conversation` itself still has a foreign key to `Peer`, though, which surfaced a real gap during implementation (D-036): delivering a message from someone never directly met would otherwise violate that constraint outright, since no `Peer` row would exist for them. `RelayGossipSession.deliverToSelf` now calls a new `PeerRepository.recordKnownFromRelay(originSenderId, originDisplayName)` first, creating a minimal `Peer` row (using §5's `originDisplayName`) if one doesn't already exist, before ever calling `ConversationRepository.getOrCreate`.

**Why:** `Message` and `Conversation` model this device's own conversations; conflating "a blob I'm carrying for two strangers" with that would mean either a nullable `conversationId` (weakening a foreign key that's currently non-null and meaningful) or `Message` rows appearing for conversations the user never had, corrupting `ConversationsScreen`'s own list. A relay envelope becomes a real `Message`, via the existing `MessageRepository.receiveIncoming`, at the moment its `finalRecipientId` is discovered to be this device's own identity and it successfully decrypts; an envelope addressed to this device is never actually inserted into `RelayEnvelope` at all in that case, it's decrypted and delivered directly from the just-decoded push frame, so there's nothing to clean up afterward. It behaves exactly like a Milestone 3 incoming message from that point on, indistinguishable in `ChatScreen` from a message that arrived directly.

**Alternatives considered:**
- **A nullable `conversationId` on `Message` for in-transit relay envelopes**: rejected, weakens an existing non-null foreign key and blurs a distinction (mine vs. something I'm just carrying) that matters for every other screen already built.

**Revisit when:** never expected to change; this is a clean, permanent separation of concerns, not a provisional stand-in.

## 7. Decision: bounded by both hop count and age, evicted oldest-first past a storage cap

**Decision:** an envelope is no longer relayed once `hopCount` reaches `MAX_HOP_COUNT` (provisional: 6) or `createdAt` is older than `MAX_ENVELOPE_AGE_MS` (provisional: 72 hours), whichever comes first; both are checked before a device relays an envelope onward, and swept opportunistically (checked whenever a new envelope is stored, no separate scheduled background job). Total envelopes held for other people, not addressed to this device, are additionally capped at `MAX_HELD_ENVELOPES` (provisional: 200), oldest `receivedAt` evicted first once exceeded.

**Why:** an unbounded flood has an unbounded blast radius and an unbounded storage/battery cost on every device in the mesh, not just the sender's. A hop limit bounds how far a single message can spread; an age limit bounds how long a phone keeps carrying dead weight for a recipient who may never come back into the mesh at all; a storage cap bounds the worst case where a device sits at the center of a lot of mesh traffic. All three numbers are explicitly provisional starting guesses, the same honesty standard docs/05 §1 already set for retry/backoff constants, not validated against any real multi-device mesh traffic, because none exists yet.

**Alternatives considered:**
- **No cap, rely on age alone**: rejected, a pathological case (many peers, many relayed messages, none expiring soon) could still exhaust storage well before 72 hours passes.
- **TTL by wall-clock deadline chosen by the sender instead of a fixed constant**: more flexible, deferred, adds a field and a UI decision ("how long should this matter") this milestone doesn't need to ship a working mesh.

**Revisit when:** real multi-device testing (still blocked on hardware, same as every milestone since 2) shows actual mesh traffic volume and actual how-long-do-people-stay-out-of-range patterns.

## 8. Decision: relay is gossiped on every connection, not only when there's a direct message to send

**Decision:** `BlePeerDiscovery`'s existing `onPeerResolved` hook (already used by `MessageRetryCoordinator`, D-021) gains a second listener, a new `RelayGossipCoordinator`, which opens a `ChatConnection` to *every* newly resolved peer, not just ones this device has a pending message for, purely to run a relay gossip round once the connection reaches `READY`. The round: each side sends the `finalRecipientId`-matching envelopes it holds for the *other* side directly (an immediate real delivery, decrypted into a genuine `Message` via `MessageRepository.receiveIncoming`, then removed from `RelayEnvelope`); then each side exchanges a compact list of the `messageId`s of everything else it holds, and pushes only the envelopes the other side doesn't already have (a standard gossip/anti-entropy exchange, not a full resend every time, keeping traffic bounded to actual gaps between what the two sides already hold).

Three new `ChatFrame` variants carry this, `EncryptedRelayInventory`, `EncryptedRelayRequest`, `EncryptedRelayPush`, each an encrypted payload exactly like `EncryptedMessage`/`EncryptedAck` already are, using the *existing per-hop session key* (D-015), not the new end-to-end key. This is deliberate layering, not an oversight: the session key already protects this specific hop's traffic from a passive listener (the same protection `EncryptedMessage` already gets), while the envelope's own end-to-end key is what actually protects the message content from the two devices carrying it. Routing metadata (who's talking to whom, which message IDs exist) is only ever as exposed as it already is for direct chat traffic today; no new exposure is introduced.

**Why gossip on every connection, not just chat connections:** the entire value of relay is a device carrying a message for someone it has no relationship with; if gossip only happened when there was already a reason to connect (an active chat, a pending retry), the mesh would only ever move messages between people who were already going to talk directly anyway, defeating the purpose.

**Known cost, honestly flagged, not hidden:** opening a `ChatConnection` to every resolved peer is a real battery and airtime cost beyond what Milestones 2 through 4 needed, since discovery previously only ever *observed* peers, it never had to connect to all of them. A coarse per-peer throttle (provisional: gossip with a given device address at most once per `GOSSIP_THROTTLE_MS`, five minutes) caps the worst case of a peer repeatedly re-resolving inside the Milestone 2 grace period from triggering a new connection every time. This is a starting mitigation, not a tuned answer, real usage data on how often peers actually re-resolve and how much battery this costs is still blocked on hardware.

**Alternatives considered:**
- **Gossip only opportunistically, piggybacked on connections opened for other reasons**: simpler, no new coordinator, but this is not really store-and-forward, it's "retry, but sometimes a stranger's message happens to ride along," a materially weaker guarantee than docs/00 §4's Phase 3 description promises.
- **A scheduled background sync (WorkManager) instead of connection-time gossip**: adds real Android background-execution machinery (job scheduling, battery-optimization interactions) this milestone doesn't otherwise need; connection-time gossip reuses infrastructure that already exists (`ChatConnection`, `ActiveChatConnections`) and only adds a new listener + three frame types.

**Revisit when:** real device testing shows the throttle is either too aggressive (messages sit too long before spreading) or too loose (visibly hurts battery); this is exactly the kind of constant docs/05 already established the project would rather ship provisionally and correct with real data than block on guessing right the first time.

## 9. Decision: a message falls back to relay only once direct retry is exhausted, and its status ceiling changes

**Decision:** `MessageRepository.scheduleRetry` (D-025), instead of calling `markFailed` once `MAX_RETRY_COUNT` is exceeded, first checks whether the peer's `encryptionPublicKey` is known (resolved at least once, ever). If it is, the message is wrapped into a `RelayEnvelope` addressed to that peer and inserted for this device to carry and gossip onward, and the `Message` row is marked `SENT` (meaning "left this device, no longer this device's problem to retry") rather than `FAILED`. If the peer has never been resolved at all (no encryption key on file, nothing to encrypt to), it still falls all the way to `FAILED`, unchanged from Milestone 4, since there is nothing that could be relayed either.

A relayed message never reaches `DELIVERED` in this milestone, and the original sender has no way to know it actually arrived: there is no return path yet for a delivery acknowledgment to travel back across multiple hops to the original sender the way the direct-connection ack (D-016) already does for a single hop. `SENT` is the honest ceiling: it tells the sender their message left the device and entered the mesh, not that anyone received it.

**Why fall back only after direct retry exhausts, not immediately:** a currently-reachable peer should always get the message the fast, simple, already-working way (D-024's direct `ChatConnection`); relay exists for when that path has already been tried and failed, not as a replacement for it.

**Alternatives considered:**
- **Always relay immediately, skip the direct attempt**: rejected, this would abandon a connection that might succeed on retry, in favor of a slower, no-delivery-guarantee path, for no benefit.
- **Give relayed messages a new status value distinct from `SENT`** (e.g. `RELAYED`): would communicate more precisely to the user, deferred, `ChatScreen`'s `messageStatusText` mapping only needs one new string resource if this is picked up later; `SENT` is truthful enough to ship this milestone without a UI-facing wording decision blocking the mesh mechanics themselves.

**Revisit when:** end-to-end delivery acknowledgment across multiple hops is designed, likely alongside Milestone 8's synchronization work, since both need some form of state that survives being carried by intermediate, possibly-offline devices.

## 10. Deliberately deferred

- **Multi-hop delivery acknowledgment back to the original sender.** Per §9, a real, separate problem: an ack now has to survive the same carry-and-forward journey a message does, not just travel back over one already-open connection.
- **Smarter routing.** This milestone floods (gossips with every resolved peer), the same "epidemic, store-and-carry" positioning docs/00 §3 already places Beacon closest to (Bridgefy). Anything smarter (shortest-path estimates, peer reliability scoring) is real, separable work, and docs/00 §7 already puts connection-quality visualization at Milestone 9, after this one.
- **Abuse/spam protection beyond the storage cap.** Nothing stops a malicious peer from generating many envelopes to exhaust a relay's `MAX_HELD_ENVELOPES` slots; the cap bounds the damage to one device's own storage, it doesn't prevent the attempt. A real mitigation (proof-of-work, rate limiting per origin identity) is out of scope for a first working mesh.
- **Forward secrecy for relayed messages**, per §5's revisit note: the long-term encryption key, unlike a Milestone 3 ephemeral key, is reused across every relayed message to that identity, so its compromise would expose all of them, not just one session.
- **A dedicated background sync schedule.** Per §8, relay only happens opportunistically when devices already connect for discovery/gossip; a phone that never encounters another device in range simply never spreads or receives anything, which is the correct, expected behavior for a BLE-only mesh with no other transport, not a bug.

## 11. Next step

Schema first (`Identity`/`Peer` encryption-key columns, the new `RelayEnvelope` entity, another destructive migration per D-023's precedent), then `CryptoService`'s envelope key derivation and encrypt/decrypt helpers, then the three new `ChatFrame` variants and `ChatGattServer`/`ChatConnection` handling for them, then `RelayGossipCoordinator`, then wiring `MessageRepository.scheduleRetry`'s fallback path. Ready to start on that?
