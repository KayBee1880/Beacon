# Milestone 1: Domain Model & Local Persistence

_2026-07-20_

Covers Journeys 1 and 5 from [01-user-journeys.md](01-user-journeys.md): identity creation and "everything survives a restart, with no server to be the source of truth." No networking yet: this milestone is entirely about what lives on one device, at rest.

## 1. Entities

### Identity: this device's own identity

Exists because Journey 1 requires a durable, self-issued identity created without any server. There is exactly one `Identity` per install: it's a singleton, not a collection.

Deliberately kept **separate** from `Peer` even though they're conceptually similar ("a public key plus a display name"), for a security reason, not a modeling-purity one: `Identity` is the one entity that has a private key associated with it. Mixing "peers I know about" and "the one identity I *am*" into a single polymorphic table creates a real risk: a bug that queries "all identities" and treats them uniformly could leak the boundary between "has private key access" and "does not." Keeping them as distinct types makes that boundary a compiler-checked fact, not a runtime convention.

### Peer: a known remote identity

Exists because Journey 2 needs somewhere to record "devices we've seen," independent of whether we're currently connected to them. A `Peer` row is created the moment we see a BLE advertisement, before any GATT connection. Discovery and connection are separate steps (Journey 2), so the entity that represents discovery has to be able to exist without a live connection.

The **id is the peer's public key**, not their display name. Journey 2 explicitly ruled out display name as an identifier (two peers can share one); the public key is the only thing guaranteed unique and stable across reconnects.

### Conversation: a thread with one peer

Exists because Journey 3 needs somewhere for messages to accumulate that isn't tied to a single connection session. Phase 1 scope: **one conversation per peer, 1:1 only**, no group chat. A `Conversation` is really just "the peer, plus denormalized info for showing a sorted list of chats" (last message time). It's a thin entity on purpose; nothing about it is Phase-1-specific in a way that would make it painful to extend later for group conversations, but we're not designing for that now (no group requirement exists yet).

### Message: a single message within a conversation

Exists because Journey 3 and Journey 4 both need a message to be a persisted thing with a lifecycle (`sending → sent → delivered / failed`), not just a value passed to a network call and forgotten. Journey 4 specifically requires a message to survive the app being backgrounded before it's acknowledged. That requirement *is* this entity's reason to exist as a row in a database rather than an in-memory object.

The **id is a client-generated UUID, assigned at creation time**, before the message is ever sent. This is what Journey 3 flagged as necessary to detect duplicate sends (the button-mash case) without depending on network-layer behavior to dedupe for us.

## 2. Relationships

```
Identity            (singleton: this device)
   │  (keys live in Android Keystore, not this table; see §4)

Peer  ──1───1──  Conversation  ──1───*──  Message
(known           (one thread     (individual
 remote           per peer,       messages,
 device)          Phase 1)        each owned by
                                  exactly one
                                  conversation)
```

See [`private/diagrams/domain-model.svg`](../private/diagrams/domain-model.svg) for the visual version with full field lists.

## 3. Schema

| Entity | Field | Type | Notes |
|---|---|---|---|
| **Identity** | id | Int | Always `0`: enforced singleton, single-row table |
| | publicKey | String (Base64) | Shared with peers as our durable identifier |
| | keystoreAlias | String | Points to the Keystore-held private key; the key itself never appears here |
| | displayName | String | Mutable; peers see whatever we most recently advertised |
| | createdAt | Long (epoch ms) | |
| **Peer** | id | String | The peer's public key (durable identifier, not display name) |
| | displayName | String | As advertised by them: a label, not trusted for identity |
| | firstSeenAt | Long | |
| | lastSeenAt | Long | Drives the "still nearby" grace period from Journey 2 |
| **Conversation** | id | String (UUID) | |
| | peerId | String | FK → `Peer.id` |
| | createdAt | Long | |
| | lastMessageAt | Long | Denormalized for sorting the conversation list without a join on every render |
| **Message** | id | String (UUID) | Client-generated at creation: enables duplicate detection (Journey 3) |
| | conversationId | String | FK → `Conversation.id` |
| | direction | Enum: `INCOMING` \| `OUTGOING` | |
| | content | String | Plaintext at rest; see §5 security note |
| | status | Enum: `SENDING` \| `SENT` \| `DELIVERED` \| `FAILED` | The state machine Journey 3/4 requires |
| | createdAt | Long | |
| | deliveredAt | Long? | Null until a real ack arrives |

**Note on `Identity.publicKey` being `String`, not `ByteArray`:** the naive choice for a raw cryptographic key is `ByteArray`, since that's what key-generation APIs actually hand back. Rejected for the *stored* representation because Room persists `ByteArray` as a `BLOB` column, which prints as unreadable binary in any DB inspector, comparison/logging tooling, and equality checks (Kotlin's `ByteArray` doesn't override `equals`, so two otherwise-identical instances compare unequal by reference; a real footgun for a value used as a lookup key). The key is Base64-encoded to a `String` once, at rest, at the Identity/Peer boundary. `equals`, logging, and inspection all behave normally, and it's still the same durable identifier `Peer.id` uses. Encoding/decoding cost is negligible for a value read/written this rarely.

## 4. Decision: Android Keystore for identity key material

The `Identity.keystoreAlias` field above is a pointer, not the key. The actual private key is generated inside and never leaves the Android Keystore, a hardware-backed store that can perform signing/decryption operations *using* the key without ever exposing the raw key material to app code, even to us. If the device is compromised or the app's storage is dumped, the private key still isn't extractable (on devices with hardware-backed Keystore support).

This matters specifically because Beacon's threat model already assumes wireless interception (product brief, security section): a private key sitting in plaintext in the same SQLite file as chat history would mean a single storage compromise breaks confidentiality for every past and future conversation, not just this session's traffic.

Full reasoning and alternatives (raw key storage, EncryptedSharedPreferences, a software keystore) logged in `private/decisions/tech-decisions.md` (D-006) and formalized as [ADR-0003](architecture/0003-android-keystore-for-identity-keys.md).

## 5. Decision: Room for local persistence

`Identity`, `Peer`, `Conversation`, and `Message` above are Room entities, Android Jetpack's SQLite ORM. Chosen primarily because it exposes query results as Kotlin `Flow`s, which lets the UI layer observe the database directly and update reactively (a message flipping from `SENDING` to `DELIVERED` should just update on screen, no manual refresh plumbing), and because it verifies SQL queries at compile time instead of failing at runtime.

Full reasoning and alternatives (raw SQLite, SQLDelight, Realm/ObjectBox) logged in `private/decisions/tech-decisions.md` (D-007) and formalized as [ADR-0002](architecture/0002-room-for-local-persistence.md).

**Note on `content` being plaintext at rest:** this is the local on-device database, protected by Android's own file-based encryption and app sandboxing. It is a *different* concern from the peer-to-peer transit encryption `CryptoService` will handle starting Milestone 3. Re-encrypting message content with a separate at-rest key (e.g. via SQLCipher) is a real hardening option, deliberately deferred rather than silently skipped; noted for revisit once transit encryption exists to compare it against.

## 6. Deliberately deferred

- **`DeliveryReceipt` as its own entity.** For Phase 1's single-hop case, a `status` + `deliveredAt` field on `Message` fully captures the lifecycle. A separate table only earns its keep once a message can have *multiple* receipts (e.g. per-hop acknowledgements once relay exists). Revisit at Milestone 6.
- **Retry bookkeeping (`retryCount`, `nextRetryAt`, backoff state).** Journey 4 needs this, but it's Milestone 4's concern (delivery resilience), not Milestone 1's (does the data survive at all). Adding it now would mean designing a retry policy prematurely, before we've built the connection layer whose failure modes should actually inform it.
- **SQLCipher / at-rest DB encryption**: see §5 note above.

## 7. Next step

This schema is enough to build Milestone 1 for real: the Identity setup screen (Journey 1) and an empty Nearby/Conversations shell with nothing to discover yet. That's the actual implementation step: Gradle project scaffold, Room entities/DAOs, the identity creation flow, and it's the first real code in the repo. Ready to scaffold the Android project?
