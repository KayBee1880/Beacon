# Milestone 3: Secure Direct Messaging

_2026-09-04_

Covers Journey 3 from [01-user-journeys.md](01-user-journeys.md): encrypted 1:1 messages over a single BLE connection, with a real delivery acknowledgement from the peer's device. Builds directly on Milestone 1's `Message` entity (already has the state machine's fields) and Milestone 2's identity resolution (already gives each side a verified public key for the peer it's talking to).

## 1. What already exists vs. what this milestone adds

Already in place: `Message` (id, conversationId, direction, content, status, createdAt, deliveredAt), the `SENDING → SENT → DELIVERED → FAILED` states as an enum, and (Milestone 2) a way for two devices to resolve each other's verified identity public key over a brief GATT connection.

Not yet built: any way to actually move message bytes between devices, any encryption at all, and a GATT connection that stays open for the length of a conversation rather than disconnecting immediately after resolve.

## 2. The problem: the identity key cannot do this job

The obvious first instinct is "we already have a keypair per device, use it to encrypt messages." That does not work, for two independent reasons:

- **Purpose mismatch.** `IdentityKeyProvider`/`IdentityKeyStore` generates the identity key with `KeyProperties.PURPOSE_SIGN` only (ADR-0003 / D-006). A Keystore key's declared purpose is enforced by the Keystore itself; a sign-only key cannot be used for key agreement (ECDH), full stop.
- **Platform floor.** Even if the key were regenerated with `PURPOSE_AGREE_KEY` added, Android Keystore only supports hardware-backed ECDH key agreement from API 31 onward. `minSdk = 26` means roughly a third of the supported API range (26 to 30) would have no working path at all.

There is also a crypto-hygiene reason to want a separate key even where the above two problems did not exist: reusing one long-term key for both signing and encryption key agreement is a well-known way to accidentally weaken both operations. A device's identity key should keep doing exactly one job: proving which device this is.

## 3. Decision: a software-generated ephemeral EC keypair per session, authenticated by the identity key

Each side generates a fresh EC keypair (secp256r1, matching the identity key's curve) in software, not the Keystore, once per chat session (see §8 for what "session" means here). This key never touches persistence; it lives in memory for the life of the connection and is discarded when it closes.

Software, not Keystore, specifically because this key does not need hardware protection the way the identity key does: if it is ever compromised, only that one session's messages are exposed, and a fresh one is generated the next time the chat is opened. Using the platform's plain `KeyPairGenerator.getInstance("EC")` (no `"AndroidKeyStore"` provider) works identically across the entire `minSdk = 26` floor, unlike Keystore-backed agreement.

**Curve choice:** secp256r1, not X25519. X25519 is the more modern, purpose-built choice for Diffie-Hellman and was explicitly flagged as a candidate in ADR-0003, but Android's platform crypto providers only gained reliable `XDH`/X25519 support around API 33, well above this project's floor. Adopting X25519 anyway would mean pulling in a third-party crypto library (Bouncy Castle or similar) for one function, which is not justified yet. Revisit if `minSdk` is ever raised past 33, or if a crypto library gets adopted for an unrelated reason first.

**Binding the ephemeral key to a verified identity:** an ephemeral ECDH exchange on its own is vulnerable to an active man-in-the-middle relaying and substituting keys between the two devices, since nothing ties the ephemeral public key to the identity Milestone 2 already resolved and verified. The fix reuses the identity key for exactly the one job it is good at: the sender signs its ephemeral public key with its identity private key (`Signature.getInstance("SHA256withECDSA")`, which a `PURPOSE_SIGN` Keystore key already supports natively), and the receiver verifies that signature against the peer's already-known identity public key before trusting the ephemeral key for anything. An attacker without that device's identity private key cannot produce a valid signature over a substituted ephemeral key.

## 4. Decision: ECDH + HKDF to derive an AES-256-GCM key, hand-rolled HKDF

Once both signed ephemeral public keys are exchanged and verified, each side computes the same shared secret via `KeyAgreement.getInstance("ECDH")`, then derives a symmetric key from it with HKDF-SHA256 (RFC 5869), then encrypts every message that session with `Cipher.getInstance("AES/GCM/NoPadding")` using a fresh random 12-byte nonce per message (`SecureRandom`, never a counter or anything reused; GCM's authentication guarantee breaks completely if a nonce is ever reused under the same key, a real "gotcha" worth flagging rather than an edge case).

Raw ECDH output is not used directly as an AES key. It is not uniformly random the way a key needs to be, and using it directly is a known footgun. HKDF fixes that, but Android's default crypto providers do not expose HKDF as a first-class JCE primitive the way they do `Cipher` or `Signature`. Two ways to get it:

- **Hand-roll HKDF using `Mac.getInstance("HmacSHA256")`.** RFC 5869's extract-then-expand construction is about 20 lines of code over a primitive Android already has everywhere.
- **Pull in a crypto library** (Bouncy Castle, Google Tink) that ships a tested `HKDFBytesGenerator` or equivalent.

**Chosen: hand-roll it.** A correctly-implemented HKDF is small, precisely specified, and easy to test against RFC 5869's published test vectors; adding a dependency for one well-defined function is the kind of premature dependency this project has avoided elsewhere (see D-005 on the `core/` module). Revisit if this milestone's `CryptoService` grows enough real cryptographic surface area that a vetted library starts paying for its own inclusion, rather than for this one function.

## 5. Decision: one long-lived connection per open chat, write + notify instead of two connections

GATT is asymmetric per connection: only the client (central) can write to the server's (peripheral's) characteristics, and only the server can push data via notifications to a client that has subscribed. Sending messages both directions therefore cannot be "both sides write," the naive first read of "it's just a chat, both people send messages."

**The fix:** one connection, two operations on the same characteristic pair. Whichever device opened the chat (Journey 3: "tapping a peer either reuses an existing GATT connection... or triggers a new connect") holds the central role for that connection. It sends messages by **writing** to a Message characteristic on the peer's GATT server. The peer sends messages back by updating that value on its own server and **notifying** the connected central, which subscribed to it when the connection opened. Both devices keep running their Milestone 2 central and peripheral roles concurrently in the background throughout, this is a third, longer-lived connection layered on top for one active chat, not a replacement for discovery.

This is also what makes docs/03 §7's flagged future concern ("multiple simultaneous GATT connections... becomes one once Milestone 3 holds connections open for active chats") arrive now rather than later: Android's low cap on concurrent GATT connections means having several chats open at once, plus ongoing discovery resolves, is a real constraint starting this milestone. Not solved here; noted for when it is actually hit.

## 6. Decision: binding an incoming chat connection to a resolved identity

§3's signature check only works if the receiving side already knows *which* identity public key to verify against. Milestone 2's resolve flow only establishes that mapping for the device acting as central during discovery, the one that connects out and reads the peer's public key. The device acting as peripheral when a chat connection arrives sees only a `BluetoothDevice` (a BLE address) in its GATT server callbacks; nothing ties that address to a `Peer.id` on its own. Naively verifying "whichever identity the connecting device claims" would be no verification at all, the same hollow check §3 already rejected once.

The saving fact: both devices run central and peripheral roles concurrently for ambient discovery (Milestone 2), so by the time either device opens a chat, it has almost certainly already resolved the other as a peer through its own central role, in the normal course of discovery, not anything new this milestone has to build from scratch.

**The fix:** extend `BleCentralRole`'s existing resolve step to also record, in memory only, which BLE device address resolved to which identity public key, alongside the RSSI map it already keeps (same reasoning as RSSI: a device's current BLE address is transient background data, not identity, and isn't persisted). Expose that mapping through `PeerDiscovery` so `BlePeripheralRole`'s GATT server can look up an incoming connection's device address at handshake time and know which identity public key to verify the signature against.

**Why:** this reuses discovery that is already running in the background instead of inventing a second identity-verification path. The alternative, having the peripheral open its own connection back to the incoming device just to resolve it first, would mean two connections and two round-trips to accomplish what ambient discovery already accomplishes for free.

**Alternatives considered:**
- **Have the connecting device include its own identity public key directly in the handshake frame, self-signed**: rejected outright, it provides no protection at all. An attacker can generate an arbitrary keypair and sign with it just as easily as a real device can. The signature check only means something when verified against a key obtained through an independent, already-trusted channel, never one bundled into the same message being authenticated.
- **Have the peripheral synchronously resolve the connecting device's identity on demand, before accepting a handshake**: correctness-equivalent, but adds a redundant connection and round-trip for the overwhelmingly common case where discovery already resolved it moments earlier.

**Revisit when:** a chat is opened with a peer whose identity ambient discovery hasn't resolved yet (e.g., a peer seen only milliseconds ago, before its resolve round-trip completed). This milestone's answer: fail the handshake cleanly with a "peer not yet fully discovered, try again shortly" state, never proceed without verification.

## 7. Decision: negotiate a larger ATT MTU before the handshake

The design so far has a fitting problem exactly like docs/03 §1's advertisement byte budget, just one layer up the stack. The default BLE ATT MTU is 23 bytes, leaving 20 usable bytes per write after the ATT header. §3's signed ephemeral key is an X.509-encoded EC public key (about 91 bytes) plus a DER-encoded ECDSA signature (about 70 to 72 bytes): well over 160 bytes combined. It categorically does not fit in a default-MTU write, the same "does not fit, not fits-if-we're-careful" situation as the advertisement problem, just with a different fix available this time because this is over an established connection, not a broadcast packet.

**The fix:** request a larger MTU immediately after the chat connection is established, before sending the handshake. `BluetoothGatt.requestMtu()` (central side) can ask for up to 517 bytes; the peripheral side receives the negotiated value via `BluetoothGattServerCallback.onMtuChanged()`. Both sides then know the real usable payload size for that connection. This API has existed since API 21, so it carries no `minSdk = 26` floor problem the way Keystore's ECDH or X25519 support did.

**Why this and not extended advertising's fix (fragmenting the payload manually):** unlike a broadcast advertisement, this is a live, already-negotiated connection between two known devices; there is no equivalent hardware-support gap to work around, MTU negotiation is a standard, universally-supported connection-level operation. Manually splitting the handshake payload across multiple writes would solve the same problem with meaningfully more code (reassembly, ordering, partial-write failure handling) for no benefit, since a larger MTU is available to just ask for.

**A device that refuses the requested MTU** (some report a negotiated value lower than requested, or reject the request outright) still needs handling: if the negotiated MTU can't fit the handshake payload, the connection should fail cleanly with a real error state the user can see, not hang silently. Not expected to be common on `minSdk = 26`-and-above hardware, but worth a defined failure path rather than an assumption.

## 8. Decision: message state machine and delivery acknowledgement

`content` stays plaintext at rest in Room, unchanged from Milestone 1's decision (docs/02 §5): encryption is purely a transit-layer concern, applied right before a GATT write and removed right after a GATT read/notification, matching the app-layers diagram's "Domain encrypts, Transport only ever moves ciphertext."

States, mapped to concrete events:

- **SENDING**: the `Message` row is inserted the moment the user taps send, before any network operation, so it survives an immediate app kill (same reasoning as Milestone 1's identity-creation atomicity).
- **SENT**: the GATT write completes successfully (`onCharacteristicWrite` reports success). This means "left this device," not "arrived."
- **DELIVERED**: the peer's device, after successfully decrypting and persisting the message as its own `INCOMING` row, writes/notifies back a small encrypted acknowledgement containing just the message's client-generated UUID. Receiving that ack is what flips the sender's row to `DELIVERED`, a real acknowledgement from the peer's device, not an assumption.
- **FAILED**: the GATT write itself errors, or the connection drops before a `SENT`/ack is ever received and no retry is in flight (Milestone 4's territory for what happens next; this milestone only needs the terminal state to exist).

A "session" for §3's ephemeral key purposes is one open chat connection: established when the chat screen opens (or reuses an existing connection, per Journey 3), torn down when the screen closes or the connection drops. A new ephemeral key is generated the next time a connection is established, whether that is seconds or days later.

## 9. Deliberately deferred

- **Forward secrecy within a session (message-level ratcheting).** One ephemeral key for the whole session gives forward secrecy *between* sessions (an exposed key from a past session cannot decrypt a future one) but not between individual messages inside the same session. A full Double Ratchet is real hardening, appropriately out of scope for the first working version of encrypted messaging. Revisit once single-hop messaging is solid, the same sequencing principle D-003 already applied to relay.
- **Multi-hop-aware encryption.** Nothing here considers a message passing through a relay device; that is Milestone 6's problem once store-and-forward exists, and it will need its own design pass (an intermediate relay should not be able to read message content it is only carrying).
- **Retry/backoff for `FAILED` messages.** Explicitly Milestone 4's concern, already flagged as deferred once in docs/02 §6; still deferred here for the same reason: designing a retry policy before the connection layer's real failure modes are known would mean guessing.
- **Read receipts.** Journey 3 already settled this: `DELIVERED` is enough for v1, read receipts are a UX nicety layered on later, not a resilience requirement.
- **Concurrent multi-chat GATT connection limits.** Flagged in §5 as arriving now, not solved now. Revisit once it is actually hit in testing rather than designed against speculatively.
- **Handling an MTU negotiation that lands below what the handshake needs.** §7 names this as a required failure path, not a solved one; the exact user-facing error state is an implementation detail for when the code is written.
- **A chat opened before ambient discovery has resolved the peer's identity.** §6 names the fix (fail cleanly, not silently), not the exact UI treatment.

## 10. Next step

Implement `CryptoService` (ephemeral EC keygen, signed key exchange, ECDH, hand-rolled HKDF, AES-GCM encrypt/decrypt), extend `BeaconGattProfile` with the ephemeral-key and message characteristics, build the chat screen and send/receive flow, and wire the acknowledgement path. Ready to start on that?
