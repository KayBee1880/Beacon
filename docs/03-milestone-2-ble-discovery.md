# Milestone 2: BLE Peer Discovery

_2026-09-02_

Covers Journey 2 from [01-user-journeys.md](01-user-journeys.md): two nearby devices see each other, with no messaging yet. This is the first milestone that touches a real radio, and the first one where a naive reading of the requirements runs straight into a hard platform limit. That limit, and the fix, is most of this document.

## 1. The problem BLE's advertisement size limit creates

Milestone 1 settled `Peer.id` as the peer's full public key ([docs/02](02-milestone-1-domain-and-persistence.md) §3), deliberately, so nothing ever has to trust a display name as an identifier. Journey 2 then requires a `Peer` row to exist "the moment we've seen an advertisement, before any GATT connection is formed."

Put those two together and there's a real problem: a legacy BLE advertisement packet has **31 bytes** of payload, shared between flags, the service UUID, and any custom data. An EC public key, X.509-encoded, is around 91 bytes; Base64-encoded, over 120 characters. It does not fit. Not "fits if we're careful": it categorically does not fit alongside anything else, including a display name.

Two ways out, both real:

1. **BLE 5 extended advertising**, which supports payloads up to ~1650 bytes on capable hardware, large enough to carry the full public key directly.
2. **Advertise a short fingerprint, resolve the rest afterward**: broadcast something small and unique enough to identify the peer, then fetch the actual public key over a brief, automatic GATT read once an advertisement is seen.

## 2. Decision: fingerprint-first discovery, not extended advertising

**Chosen: option 2.** Advertise a public-key fingerprint, resolve the full identity via a background GATT read immediately on discovery.

Extended advertising was rejected specifically because of who Beacon is for ([00-foundations.md](00-foundations.md) §2): disaster response and remote-expedition use assumes a wide range of device ages, not everyone's newest flagship. `BluetoothAdapter.isLeExtendedAdvertisingSupported()` is not universally `true`. Older or budget chipsets fall back to legacy advertising regardless of what the app requests. That means supporting extended advertising wouldn't remove the need to also handle the legacy/small-payload case. It would mean building and maintaining *two* discovery paths instead of one. One path that works on every BLE 4.0+ device (the actual floor `minSdk = 26` implies) is simpler than two paths where only the fallback one is guaranteed to run everywhere.

**How it works, and a byte-budget correction made while writing this section:**

The first draft of this plan advertised the fingerprint as data attached to Beacon's own 128-bit service UUID. That doesn't actually fit: a legacy advertisement's 31 bytes have to hold the mandatory Flags structure (3 bytes) *and* whatever identifies the service. A "Complete List of 128-bit Service UUIDs" structure alone costs 18 bytes (2 bytes of header plus the 16-byte UUID), and attaching Service Data to a 128-bit UUID costs the UUID *again* (another 16 bytes) plus the payload. There's no way to fit both a 128-bit UUID reference and a 16-byte fingerprint in one 31-byte packet. This is exactly the "naive approach breaks" step this doc's other sections got to skip past, worth leaving visible rather than quietly fixing, since it's the kind of mistake that only shows up as a cryptic `ADVERTISE_FAILED_DATA_TOO_LARGE` at runtime if it ships.

**The actual fix: two tiers of UUID, doing two different jobs.**

- A **16-bit UUID** (`0xFDF0`; see caveat below) is used *only* in the advertisement itself, as the discriminator for the Service Data structure. A 16-bit UUID's AD structures are cheap: 4 bytes for the "Complete List of 16-bit Service UUIDs," and 4 bytes of header for Service Data, leaving `31 − 3 (flags) − 4 − 4 = 20` bytes free for the fingerprint payload. A 16-byte SHA-256-derived fingerprint fits comfortably.
- Beacon's real, unique **128-bit UUIDs** (§3) are used only for the actual GATT service/characteristics exposed *after* a connection is made. There's no advertisement-packet size pressure once connected, so full 128-bit identifiers are the right choice there (exactly as originally planned).

**Caveat on the 16-bit advertising UUID:** 16-bit Bluetooth UUIDs are meant to be centrally assigned by the Bluetooth SIG; `0xFDF0` here is arbitrarily chosen, not registered, and could theoretically collide with some other nearby BLE device/app using the same value for something unrelated. This is deliberately not a correctness problem: a collision just means `BleCentralRole` attempts a resolve connection against a device that turns out not to expose Beacon's actual 128-bit GATT service. That attempt fails harmlessly and the device is ignored, never mistaken for a real peer. A future version distributed at real scale should register a proper SIG identifier; not worth doing for a single-developer project at this stage.

**The resolve sequence itself:**

1. `BlePeripheralRole` advertises Service Data (the first 16 bytes of `SHA-256(Identity.publicKey)`), keyed to the placeholder 16-bit UUID, per the byte budget above.
2. The moment `BleCentralRole` sees that advertisement, it opens a **background GATT connection** purely to read two characteristics off Beacon's real 128-bit service (the full public key and the current display name, §3), then disconnects immediately. This connection is transport-layer housekeeping the user never sees: no "connecting..." UI, no chat implied.
3. Once both values are read back, the central role **upserts** a `Peer` row keyed by the real public key (not the fingerprint; the fingerprint never gets persisted anywhere; it only exists to trigger the resolve step).

This satisfies Journey 2's "a peer entry exists before any GATT connection" in the sense that matters to the user (no visible connect step, no chat action implied) while being honest that a brief, invisible connection is what actually resolves the identity. Worth being explicit about, since "before any GATT connection" read too literally would be impossible given BLE's own packet size limit. The fix keeps the *user-facing* guarantee intact rather than the literal implementation detail.

**Rejected alternative:** advertising the peer's display name instead of/alongside the fingerprint, to show something in the list before resolution completes. Rejected because Journey 2 already ruled out display name as meaningful identity ("two peers can share one"); showing an unresolved, unverified name in the UI before the real public key is confirmed would blur exactly the line that decision was drawn to keep sharp. The list shows nothing for a peer until its identity is actually resolved; the gap is milliseconds on a local BLE link, not a UX problem worth trading that guarantee for.

## 3. GATT contract

One custom service, two read-only characteristics, exposed only after the brief resolve connection from §2. This is the entire Milestone 2 wire surface, no writes, no notifications yet (those start mattering once Milestone 3 adds actual messaging). Not to be confused with the 16-bit `0xFDF0` UUID from §2, which exists only to make the *advertisement* fit. Everything below is 128-bit and only ever seen over an active GATT connection, where packet-size pressure doesn't apply.

| | UUID | Contents |
|---|---|---|
| **Beacon Identity Service** | `cbd0b8c8-bebc-4f7d-976d-089689ef90f5` | N/A |
| ↳ Public Key characteristic | `a9a065e1-ca6c-4bd4-8c3d-de81bf609348` | `Identity.publicKey`, UTF-8 bytes of the Base64 string (matches how it's already stored; see [docs/02](02-milestone-1-domain-and-persistence.md) §3's note on why the key is a `String`, not raw bytes) |
| ↳ Display Name characteristic | `9623d7d0-482a-4b06-b375-8db41e6b9868` | `Identity.displayName`, UTF-8 bytes |

All three 128-bit UUIDs are freshly generated, random v4 UUIDs, the correct way to mint a custom BLE UUID (never hand-write something that merely *looks* plausible; collision avoidance is the whole point of the 128-bit space; unlike the 16-bit advertising UUID above, there's no meaningful collision risk here at all). They're constants from this point on. Changing them later would break discovery between an updated and non-updated install, so they belong in one object (`BeaconGattProfile`) referenced by both roles, never inlined at each call site.

## 4. Decision: central + peripheral as separate, interface-abstracted components

`BleCentralRole` (scans, connects out to resolve identities) and `BlePeripheralRole` (advertises, accepts the resolve connection) are two independent classes, not one "BLE manager." Journey 2 already calls this out as necessary, since a device runs both roles *concurrently*: it's advertising itself to others while simultaneously scanning for others, and those are genuinely different Android APIs (`BluetoothLeAdvertiser` vs. `BluetoothLeScanner`) with different lifecycles and different failure modes (advertising can fail because another app already has 4+ advertisers running; scanning can fail because location services are off; conflating them into one class would mean one component with two unrelated sets of error handling).

Both roles sit behind a single interface the Domain/UI layer depends on (a `PeerDiscovery` or similar abstraction; exact shape decided when this gets implemented) rather than `MainActivity`/`PeerDiscoveryScreen` importing `android.bluetooth.*` directly. This is what keeps Wi-Fi Direct (Milestone 7) an *additive* transport later instead of a rewrite. The day a second transport exists, it implements the same interface, and nothing above the Transport layer changes.

## 5. Decision: runtime permissions, handled per API level, not skipped

Android's BLE permission model changed at API 31 (Android 12), and `minSdk = 26` means Beacon has to handle both eras. This can't be waved away as "just request permissions," because the *which* permissions genuinely differs:

- **API 26–30:** `ACCESS_FINE_LOCATION` (BLE scanning is bundled with location access on these versions; a long-standing Android quirk, not a Beacon design choice) plus the install-time `BLUETOOTH`/`BLUETOOTH_ADMIN` permissions.
- **API 31+:** `BLUETOOTH_SCAN`, `BLUETOOTH_ADVERTISE`, `BLUETOOTH_CONNECT`: runtime permissions, but location-independent (Android 12 separated "can use Bluetooth" from "can access location" as a privacy improvement, provided the app declares `neverForLocation` on the scan permission, which Beacon can do honestly since it doesn't derive physical location from scan results).

Journey 2's failure mode ("permissions denied → app must degrade to 'can't discover peers' clearly, not silently do nothing") is a real requirement, not a nicety: a user on a disaster-response deployment who thinks discovery is running when it silently isn't is worse off than one who's told plainly that it's off. The Nearby/`PeerDiscoveryScreen` needs an explicit "permission needed" state, not just an empty list indistinguishable from "no peers around right now."

## 6. Decision: peer list lifecycle (upsert on resolve, grace period on drop)

- **On identity resolve** (§2 step 3): Milestone 1 already gave `PeerDao` an `@Insert(onConflict = REPLACE) upsert(peer: Peer)`, but a blind `REPLACE` is the wrong tool used directly here, because it replaces the *whole row*: constructing a fresh `Peer(id, displayName, firstSeenAt = now, lastSeenAt = now)` on every resolve would silently reset `firstSeenAt` back to "now" on a peer we've actually seen many times before. A new `PeerRepository` (mirroring [`IdentityRepository`](../android/app/src/main/java/com/beacon/data/IdentityRepository.kt)) wraps this: read the existing row first, preserve its `firstSeenAt` if present, then call the DAO's `upsert`. `PeerDao` itself needed no new method. The merge logic belongs one layer up, same as Milestone 1's identity-creation ordering.
- **On advertisement gone**: nothing removes a `Peer` row immediately on a single missed advertisement. BLE advertising isn't reliably packet-to-packet (Journey 2's own failure-mode note). A peer drops off the *visible* Nearby list once `lastSeenAt` is older than a grace period (exact duration is a tuning constant, not an architecture decision; start conservative, e.g. 30s, revisit once real device testing shows how bursty advertisement intervals actually are). The `Peer` row itself is never deleted on going out of range. It's still a real "peer we've seen before," just not currently nearby; only relevant for now since Milestone 2 has no conversation history yet, but this is the same reasoning Journey 5 already established for messages: local data is the source of truth, not "whatever the last BLE session said."
- **Signal quality**: Milestone 2 shows raw RSSI only (three coarse buckets: strong/medium/weak by threshold), not a polished indicator. RSSI is not persisted in `Peer`. It's live radio data that's stale the instant it's read, unlike `displayName`/`lastSeenAt`, which describe real state worth remembering. It lives in memory only, in `BleCentralRole`, keyed by peer id, and gets combined with the persisted `Peer` list at the UI layer. Journey 2 explicitly defers the real treatment to Milestone 9 ("connection quality UX"); building more than a minimal signal here would be solving a problem that milestone hasn't been designed yet.

## 7. Deliberately deferred

- **Extended advertising support**, even as an opportunistic fast-path on capable hardware; §2 covers why. Revisit only if the fingerprint-resolve round-trip proves too slow in real testing, which is an empirical question this milestone hasn't answered yet.
- **Connection quality UX beyond raw RSSI buckets**: Milestone 9, per Journey 2's own open question.
- **Any GATT writes or notifications**: nothing in Milestone 2 sends data peer-to-peer beyond the two read-only identity characteristics. Messaging's actual wire format is Milestone 3.
- **Multiple simultaneous GATT connections**: Android caps concurrent GATT connections low (platform-limited, a handful at most). Milestone 2's connections are all brief resolve-then-disconnect, so this isn't a real constraint yet; it becomes one once Milestone 3 holds connections open for active chats.

## 8. A testing constraint worth flagging now, not at debug time

**Android emulators do not support real Bluetooth radios**: this was already noted in the README's setup instructions. Milestone 1 didn't care; Milestone 2 is the first milestone where it matters, since there is nothing to discover with only one virtual device. Verifying this milestone needs **two physical Android devices** running the app side by side. (Recent Android Studio emulator releases have experimental virtual-Bluetooth passthrough between two AVDs on the same host; untested here, not something to rely on; two real phones is the dependable path.)

## 9. Next step

Design is settled enough to implement: the `PeerDao` upsert method, the `BeaconGattProfile` constants, `BlePeripheralRole` (advertise + serve the two characteristics), `BleCentralRole` (scan + resolve + upsert), the permission-request flow, and wiring `PeerDiscoveryScreen` in as what `NearbyScreen` becomes. Ready to start on that?
