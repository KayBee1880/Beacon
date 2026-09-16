# Milestone 7: Wi-Fi Direct Bulk Transport

_2026-09-15_

Delivers what docs/00's roadmap names for this milestone: attachments and larger payloads. Phase 4 of docs/00 §4's incremental plan: "introduced specifically for attachments/large payloads where BLE's throughput becomes the bottleneck, layered in as a second transport under the same message abstraction, not a rewrite."

## 1. What already exists vs. what this milestone adds

Already in place: an authenticated, encrypted, direct BLE connection between two devices (`ChatConnection`/`ChatGattServer`), with a per-connection session key (D-015) neither side has to re-derive or re-verify once the handshake completes. `Message` models a text message; nothing in the domain model has any concept of a file.

Not yet built: any way to pick, send, receive, or store a file; any second transport; any negotiation protocol for two BLE-authenticated devices to find and connect to each other over Wi-Fi Direct, an entirely separate Android API surface (`android.net.wifi.p2p.*`) this project has never touched.

## 2. Decision: an attachment is a property of a `Message`, not a new first-class entity

**Decision:** `Message` gains nullable attachment columns: `attachmentFileName`, `attachmentMimeType`, `attachmentSizeBytes`, `attachmentContentHash`, `attachmentLocalPath`, and `attachmentState` (`AttachmentState`: `LOCAL`, `OFFERED`, `TRANSFERRING`, `RECEIVED`, `FAILED`). A message either has an attachment or it doesn't; this milestone doesn't build multiple attachments per message.

**Why:** every existing screen (`ChatScreen`, `ConversationsScreen`) already reasons about "a list of `Message` rows"; a message with an optional file is a smaller, more natural extension of that model than a new entity requiring a join everywhere a message already renders. `attachmentState` is deliberately its own enum, not a reuse of `MessageStatus`: `MessageStatus` describes the *message's* delivery lifecycle (already meaningful with no attachment at all), while `attachmentState` describes whether the *file's bytes* actually exist on this device yet, a genuinely different, independently-timed thing, the sender's own copy is `LOCAL` the instant they pick it, long before the receiver's copy exists at all.

**Alternatives considered:**
- **A separate `Attachment` entity, foreign-keyed to `Message`**: more normalized, deferred, this milestone only ever needs at most one attachment per message, a separate table buys nothing yet and would need its own DAO/repository for no current benefit.

**Revisit when:** a message ever needs more than one attachment, or an attachment ever needs to exist independent of a message (neither is in scope now).

## 3. The core negotiation problem: two BLE-authenticated devices have no idea how to find each other over Wi-Fi Direct

**Context:** `ChatConnection` already knows, with cryptographic certainty (D-013/D-014), which identity is on the other end of a BLE connection. Wi-Fi Direct is a completely separate radio and a completely separate Android subsystem (`WifiP2pManager`), with its own peer discovery (`discoverPeers()`) that has no idea a BLE conversation is even happening, and its own addressing (a `WifiP2pDevice.deviceAddress`, the Wi-Fi radio's MAC, unrelated to the BLE device address `BleCentralRole` already resolved).

**Decision:** two new frames, `EncryptedAttachmentOffer` and `EncryptedAttachmentResponse`, both encrypted with the existing BLE connection's session key (D-015), riding the exact same RX/TX characteristics every other `ChatFrame` already uses. The offer carries the file's metadata (`messageId`, file name, mime type, size, a SHA-256 content hash, see §5) plus the sender's own `WifiP2pDevice.deviceAddress`; the response carries accept/reject plus, if accepting, the receiver's `deviceAddress`.

**Revised during implementation (D-044):** each side calls `discoverPeers()` for its side effect of activating the local Wi-Fi P2P stack, but the "filter the resulting peer list for the handed-off address" step originally planned here was simplified away: `WifiP2pManager.connect()` accepts a `WifiP2pConfig` built directly from a known `deviceAddress` and does not require that address to already be enumerated in a prior `requestPeers()` result. Waiting for and filtering a peer list would have been real, additional asynchronous complexity (a `PeerListListener`, a wait loop) for a guarantee `connect()` doesn't actually need.

**Why this is the only sound order:** if a device connected to a Wi-Fi Direct peer *first* and only authenticated identity afterward, it would have no way to know whether the file it's about to receive is really from the person it thinks it's talking to, exactly the MITM shape docs/04 §6 already closed for the direct BLE case. Authenticating over BLE first, then handing off only enough information (a MAC address) to find the same already-verified device on a second radio, keeps the trust boundary exactly where it already is.

**Alternatives considered:**
- **Android's Wi-Fi Direct service discovery (`WifiP2pManager.DnsSdServiceInfo`) instead of a BLE-carried address**: would let devices find each other without the BLE round trip, rejected because it has no authentication of its own either, it would need the same BLE handshake to verify identity afterward anyway, at the cost of a second, parallel discovery mechanism to reason about.

**Revisit when:** never expected to change; this is the same "authenticate first, only then hand off to a less-trusted channel" shape the project has now used twice (docs/04 §6, and this).

## 4. Decision: sender initiates the Wi-Fi Direct connection; group-owner role is left to Android's own negotiation

**Decision:** once `EncryptedAttachmentResponse` arrives accepting the offer, the *sender* calls `WifiP2pManager.connect()` targeting the receiver's now-known `deviceAddress`. Whichever side Android's own group-owner negotiation assigns as GO opens a `ServerSocket` and accepts one connection; the other side reads `WifiP2pInfo.groupOwnerAddress` from the connection-changed broadcast and opens a plain `Socket` to it. Neither side sets an explicit `groupOwnerIntent`, Android's default negotiation decides.

**Why:** the sender is the side with something to push, giving them the initiating role keeps "who does what" easy to reason about, matching the same "central/peripheral roles are fixed by who's doing what" shape `BleCentralRole`/`BlePeripheralRole` already established. Not choosing a group-owner intent is a deliberate non-decision: which side becomes GO has no bearing on correctness here (the socket-role code path already has to handle either outcome, since Android's negotiation result isn't guaranteed), and tuning intent is a real optimization with no data yet to justify a specific value.

**Alternatives considered:**
- **Force a specific side to always be GO** (via `groupOwnerIntent = 15`/`0`): would simplify the socket code slightly (only one path instead of two), rejected, Android does not guarantee honoring an intent value against a peer's own preference, so the "only one path" code would still need the other path as a fallback, buying nothing.

**Revisit when:** real transfers show a meaningful, consistent performance or reliability difference tied to which side ends up group owner.

## 5. Decision: the bulk transfer is still application-layer encrypted, chunked, with a whole-file integrity check

**Decision:** the Wi-Fi Direct socket carries `[4-byte chunk length][encrypted chunk]` frames, each chunk (provisional: 64 KB of plaintext) encrypted with `CryptoService.encrypt` using the *same* BLE connection's session key, the identical nonce-prepended AES-256-GCM shape every other encrypted frame in this project already uses. The receiver reads chunks until the cumulative decrypted byte count matches the offer's declared size, then verifies the whole file's SHA-256 against the offer's `attachmentContentHash` before marking it `RECEIVED`; a mismatch, a short read, or the socket closing early all produce `FAILED`, and any partial file on disk is deleted.

**Why encrypt at the application layer at all, when Wi-Fi Direct groups are already WPA2-secured:** the project's own established discipline (docs/03 §2, docs/04) has never trusted a transport's own link security as the reason a message is safe, only the application-layer crypto is ever treated as the actual guarantee. A Wi-Fi Direct group's pairing is negotiated entirely by the two Android Wi-Fi stacks, with no Beacon-controlled secret; reusing the already-authenticated session key costs nothing new to implement (no new key exchange, no new authentication step) and keeps the guarantee consistent with everything else in the app. The whole-file hash exists because GCM's per-chunk authentication only proves each chunk individually wasn't tampered with, not that every chunk actually arrived, in order, exactly once, a cheap, independent check worth having for a transport this new and this untested.

**Alternatives considered:**
- **Trust Wi-Fi Direct's own WPA2 security, send the file in the clear over the socket**: rejected outright for the reason above, this would be the first place in the whole app where the transport's own security was the only guarantee, a real regression from everything built so far.
- **Skip the whole-file hash, rely on GCM alone**: simpler, rejected, the hash is cheap insurance specifically because nothing about this transport has ever run once, unlike BLE's per-hop encryption which has at least been reasoned through symmetrically on both sides for three milestones now.

**Revisit when:** real transfers show 64 KB is a poor chunk size (too small for throughput, too large for memory pressure on a low-end device); this is exactly the kind of provisional constant docs/05 §1 already established the project would rather ship and correct than block on guessing right.

## 6. Decision: attachments are a direct-connection feature, never relayed through the mesh

**Decision:** an attachment can only be sent to a peer this device can form a live BLE connection with, right now, for the offer/response exchange. If the peer isn't reachable, sending an attachment simply isn't offered as an option (no attach button interaction succeeds without an active `ChatConnection` at `READY`); there is no relay fallback for attachments the way D-035 built one for text messages.

**Why:** Milestone 6's own storage cap (D-033, 200 envelopes, sized for small text-message ciphertexts) and hop-based flooding model have no sane answer for a multi-megabyte file, carrying even a handful of photos through several hops would either blow through that cap immediately or require an entirely separate, much larger storage and bandwidth budget the mesh's own design was never sized for. Restricting attachments to a live, direct connection sidesteps that problem entirely rather than trying to solve it half-heartedly.

**Alternatives considered:**
- **A separate, larger storage/relay path just for attachments**: real, coherent future work, explicitly out of scope, docs/00 itself frames Wi-Fi Direct as solving *direct* throughput, not mesh-wide attachment delivery.

**Revisit when:** relaying large payloads through the mesh is ever actually needed; would deserve its own design pass, not a bolt-on to this milestone's direct-only transfer.

## 7. Decision: no resumability; a dropped connection fails the whole transfer

**Decision:** if the BLE connection or the Wi-Fi Direct socket closes before a transfer completes, the attempt is marked `FAILED` and any partial file is deleted. There is no checkpoint, no partial-byte-range re-request, and no automatic retry (unlike D-021's message retry, which reacts to a peer resolving again).

**Why:** resumability is real, separable complexity (tracking how much was received, verifying a partial file's own partial hash, re-negotiating a second Wi-Fi Direct connection for the remainder) that a first working version of bulk transfer doesn't need to ship. A failed attachment can always be sent again from scratch, the same "just try again" experience every chat app had before resumable transfers existed.

**Alternatives considered:**
- **Build resumability now, since large files are more likely to be interrupted than a short text message**: a real concern, deferred anyway, on the same "ship the smallest real slice first" reasoning every prior milestone in this project has followed.

**Revisit when:** real usage shows attachments failing mid-transfer often enough that always restarting from zero is genuinely painful, not preemptively.

## 8. Permissions

**Decision:** `ACCESS_WIFI_STATE`/`CHANGE_WIFI_STATE` (needed for `WifiP2pManager` at any API level), `NEARBY_WIFI_DEVICES` with `usesPermissionFlags="neverForLocation"` on API 33+ (Beacon never derives physical location from any radio, so the same flag `BLUETOOTH_SCAN` already uses applies here for the same reason), and `ACCESS_FINE_LOCATION` extended to cover API 31 and 32 specifically, the gap between BLE no longer requiring it (API 31+, already reflected in the existing `maxSdkVersion="30"` cap) and `NEARBY_WIFI_DEVICES` existing at all (API 33+). Wi-Fi Direct discovery genuinely still requires location permission on exactly those two API levels; this is a real, separate requirement from BLE's own, not an oversight in the existing manifest.

**Why:** this is a real, well-documented Android platform gap, not a design choice, `WifiP2pManager.discoverPeers()` has required `ACCESS_FINE_LOCATION` since Wi-Fi Direct discovery could theoretically infer nearby SSID/location data, and Android didn't introduce a narrower, location-free permission for it until `NEARBY_WIFI_DEVICES` in API 33.

**Alternatives considered:** none; this is dictated by the platform, not a real design fork.

**Revisit when:** never expected to change, short of Android itself changing this requirement.

**Found during implementation:** discovering *this device's own* Wi-Fi Direct address (needed for the offer, §3) uses `WifiP2pManager.requestDeviceInfo`, added in API 29. Supporting API 26-28 would need the older `WIFI_P2P_THIS_DEVICE_CHANGED_ACTION` broadcast instead, a second code path for an ever-shrinking slice of real devices; judged not worth building for this milestone. Attachments are effectively unavailable below API 29, a real, named scope limit, not an oversight, tracked the same honest way as every other gap in this document.

**A real, unverified platform risk worth naming plainly:** Android's Wi-Fi Direct stack can, on some OEM builds and Android versions, surface a system-level "Invitation to connect" prompt to the *receiving* device when an unfamiliar peer calls `connect()` against it, even though this project's own protocol has already authenticated the two devices to each other over BLE. If that prompt appears, an attachment transfer would silently stall waiting for a system UI interaction neither `ChatScreen` nor this design anticipated or renders anything for. This can't be resolved without real hardware, and is exactly the kind of gap the project has consistently surfaced honestly rather than assumed away (docs/03 §8, docs/07 §1); flagged here rather than glossed over.

## 9. Storage

**Decision:** attachment files live under `context.filesDir/attachments/<messageId>`, app-private storage, not `MediaStore` or a user-visible location. No eviction policy or storage cap exists in this milestone.

**Why:** app-private storage needs no additional runtime permission and is automatically cleaned up if the app is uninstalled, consistent with the rest of the app's data living in Beacon's own Room database with no external visibility. No eviction policy is a real, honestly-named gap, not an oversight: sizing one correctly (by count, by total bytes, by age) needs real usage data the same way Milestone 6's own storage cap (D-033) was an admitted guess even with data-adjacent reasoning available; here there isn't even that much to go on yet.

**Alternatives considered:**
- **Save to `MediaStore`/a user-visible Downloads-style folder**: more discoverable to the user, deferred, adds a real permission/scoped-storage question (`WRITE_EXTERNAL_STORAGE` semantics vary significantly across API levels) this milestone doesn't need to answer to ship a working transfer.

**Revisit when:** real usage shows attachment storage actually growing large enough to matter, or a user-facing "save to Downloads" feature is explicitly requested.

## 10. UI

**Decision:** `ChatScreen` gains an attach button next to the existing text input, opening Android's system document picker (`ActivityResultContracts.OpenDocument`), enabled only when `connectionState == READY` (mirroring the existing send-button gating), requesting Wi-Fi Direct permissions on demand at that point (§8) rather than folding them into `BeaconApp`'s app-wide gate, since most users may never send a file. A message with an attachment renders as a distinct row shape (file name plus a state-dependent line: "Sent", "Waiting", "Receiving…", "Received", or "Failed"), reusing `MessageRow`'s existing outgoing/incoming alignment logic, not a wholesale new screen.

**Simplified during implementation:** no tap-to-open affordance for a `RECEIVED`/`LOCAL` attachment exists yet; opening a received file would need a `FileProvider` (a manifest `<provider>` entry plus a file-paths resource) to hand another app a content URI for this app's private storage, real additional Android plumbing judged separable from "can a file actually arrive at all," this milestone's core question. A received file exists on disk and is named in the row, just not yet reachable from a tap.

**Why:** reuses the chat screen's existing connection-state gating and message-list rendering rather than inventing a parallel attachment-specific UI; a chat with attachments should still read as one continuous thread, the same principle that kept `ConversationsScreen` a plain list in Milestone 5 rather than something more elaborate.

**Alternatives considered:**
- **A percentage progress bar during `TRANSFERRING`**: better UX, deferred, needs a live byte-count callback threaded up from the socket-reading loop into Compose state, real additional plumbing this milestone's core "can a file actually arrive at all" question doesn't need answered first.

**Revisit when:** `TRANSFERRING` with no progress feedback turns out to feel broken in practice, not preemptively.

## 11. Deliberately deferred

- **Resumable transfers**, per §7.
- **Relayed attachments through the mesh**, per §6.
- **Multiple attachments per message, or multiple simultaneous transfers**, per §2 and the single-file-at-a-time scope this milestone assumes throughout.
- **Transfer progress reporting and cancellation UI**, per §10.
- **An eviction/storage-cap policy for saved attachments**, per §9.
- **Thumbnails or in-line image previews.** A received attachment is a file-chip row, not a rendered image, regardless of mime type; real, separable UI work.
- **Tuning `groupOwnerIntent`**, per §4.
- **Tap-to-open for a received attachment**, per §10; needs a `FileProvider`, real separable Android plumbing.
- **Support for API 26-28**, per §8's implementation note; `getLocalDeviceAddress()` requires API 29's `requestDeviceInfo`.

## 12. Implementation status

Implemented in full per the decisions above: schema (`Message`'s attachment columns, `AttachmentState`), the two new `ChatFrame` types and their offer/response handling in `ChatConnection`/`ChatGattServer`, the Wi-Fi Direct connection and socket transfer code (`com.beacon.wifidirect.WifiDirectFileTransfer`), the manifest/permission changes, and `ChatScreen`'s attach button and attachment row rendering. `BUILD SUCCESSFUL`; entirely unverified against real hardware or a real Wi-Fi Direct connection, same as every BLE-dependent milestone since 2, compounded here by an entire second radio this project has never exercised even once.
