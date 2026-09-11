<div align="center">

# Beacon

**Offline-first, encrypted mesh messaging over Bluetooth LE, for when the internet isn't there.**

[![Kotlin](https://img.shields.io/badge/Kotlin-1.9.24-7F52FF?logo=kotlin&logoColor=white)](android/app/build.gradle.kts)
[![Jetpack Compose](https://img.shields.io/badge/UI-Jetpack%20Compose-4285F4?logo=jetpackcompose&logoColor=white)](android/app/build.gradle.kts)
[![Room / SQLite](https://img.shields.io/badge/DB-Room%20%2F%20SQLite-003B57?logo=sqlite&logoColor=white)](android/app/src/main/java/com/beacon/data/BeaconDatabase.kt)
[![Status](https://img.shields.io/badge/status-early%20development-yellow)](#roadmap)

</div>

Beacon is a peer-to-peer messenger for places the internet doesn't reach: disaster response, remote expeditions, dense events where cell towers exist but are saturated. Two nearby phones discover each other and exchange messages directly over Bluetooth LE, with no server, no account, and no cellular or Wi-Fi infrastructure required. The offline-first architecture (local-first persistence, a real message delivery state machine, hardware-backed identity keys) exists to serve that product requirement, not because distributed systems are interesting in the abstract: every engineering decision here traces back to a concrete situation where connectivity fails and communication still has to work.

## Why this project exists

Modern messaging assumes a live path to a server. That assumption breaks exactly when communication matters most: towers down after a disaster, no coverage on an expedition, towers up but overloaded at a packed event. Beacon starts from the opposite assumption: two devices near each other should be able to talk regardless of what infrastructure exists between them.

Full problem statement, target users, and the reasoning behind the transport/topology choices: **[docs/00-foundations.md](docs/00-foundations.md)**.

## Engineering approach

Principles this build has actually practiced so far, each checkable against the repo:

- **Every architecture and technology decision is logged before code is written for it**: what was chosen, what else was considered, and why the alternative lost. See [docs/architecture/](docs/architecture/) (ADRs 0001 to 0004).
- **Domain rules are enforced at the database layer, not just in application code.** "One conversation per peer" isn't a convention application code has to remember: it's a `UNIQUE` index in the schema ([`Conversation.kt`](android/app/src/main/java/com/beacon/data/Conversation.kt)) that rejects violations outright.
- **Deferred and rejected scope is written down explicitly, not silently absent.** See the "Deliberately deferred" section of [docs/02-milestone-1-domain-and-persistence.md](docs/02-milestone-1-domain-and-persistence.md) and the non-goals in [docs/00-foundations.md](docs/00-foundations.md).
- **No invented metrics.** There are no performance or reliability numbers anywhere in this repo. Any that appear later will be backed by an actual benchmark, not a plausible-sounding guess.
- **Documented reasoning follows problem → naive approach → why it breaks → the actual fix**, not just "we chose X." Visible throughout the milestone docs and ADRs, for example why `ByteArray` was rejected for stored public keys in favor of Base64 `String`, documented in the [`Identity.kt` design note](docs/02-milestone-1-domain-and-persistence.md#3-schema).

## What's actually working right now

- **The full Milestone 1 domain model is implemented as Room entities and DAOs**: `Identity`, `Peer`, `Conversation`, `Message`, with foreign keys, cascading deletes, and the unique-per-peer conversation constraint described above. Code: [`android/app/src/main/java/com/beacon/data/`](android/app/src/main/java/com/beacon/data/).
- **Journey 1 (first launch & identity creation) is implemented and verified end-to-end on a running emulator (Pixel 8, API 34), 2026-09-01**: `MainActivity` observes the `Identity` table and shows a display-name setup screen when none exists; submitting it generates a hardware-backed EC keypair in the Android Keystore ([`IdentityKeyStore`](android/app/src/main/java/com/beacon/crypto/IdentityKeyStore.kt)) and persists the resulting `Identity` row via [`IdentityRepository`](android/app/src/main/java/com/beacon/data/IdentityRepository.kt). The private key never leaves the Keystore. Once an identity exists, the reactive UI switches to an empty "Nearby" screen (Milestone 2's job to fill in) with no manual navigation code involved, confirmed by actually running it, not just reading the code.
- **The Android project scaffold is written**: Gradle (Kotlin DSL), Jetpack Compose, Room + KSP.
- **Journey 5 (returning to the app later) is verified too**: stopping and relaunching the app goes straight to the "Signed in as [name] / No peers nearby yet" screen (no re-setup prompt), confirming the `Identity` row is actually persisted to disk via Room/SQLite, not just held in memory for the session.
- **The build compiles and runs.** `BUILD SUCCESSFUL` via Android Studio, app installs and launches on an emulator, both journeys above were exercised by hand and behaved as designed.
- **Milestone 2 (BLE peer discovery, Journey 2) is written, partially verified.** [`BlePeripheralRole`](android/app/src/main/java/com/beacon/ble/BlePeripheralRole.kt) advertises a public-key fingerprint and serves the real identity over GATT; [`BleCentralRole`](android/app/src/main/java/com/beacon/ble/BleCentralRole.kt) scans, resolves it, and upserts a `Peer` row via the new [`PeerRepository`](android/app/src/main/java/com/beacon/data/PeerRepository.kt); `PeerDiscoveryScreen` shows the result with a permission-gate state and coarse signal strength. Full design and the byte-budget problem it solves: [docs/03-milestone-2-ble-discovery.md](docs/03-milestone-2-ble-discovery.md). **Tried on two Android 14 emulators, 2026-09-09**: every individual BLE API call succeeded cleanly on both sides (GATT server, both services, and the scanner all registered with `status=0`; advertising reported no failure), confirming the app's own code exercises the platform correctly. Neither device discovered the other, isolating the gap to the emulator-to-emulator virtual Bluetooth bridge itself, not Beacon's code, exactly the risk docs/03 §8 already named. **Actual discovery between two devices still needs real hardware to confirm.**
- **Milestone 3 (secure direct messaging, Journey 3) is written, not yet verified.** [`CryptoService`](android/app/src/main/java/com/beacon/crypto/CryptoService.kt) generates a per-session ephemeral keypair, signs it with the identity key, derives an AES-256-GCM session key via ECDH and a hand-rolled HKDF ([`Hkdf`](android/app/src/main/java/com/beacon/crypto/Hkdf.kt)); [`ChatGattServer`](android/app/src/main/java/com/beacon/ble/ChatGattServer.kt) and [`ChatConnection`](android/app/src/main/java/com/beacon/ble/ChatConnection.kt) implement both sides of the handshake and the encrypted message/ack protocol over `BeaconGattProfile`'s messaging service; [`ChatScreen`](android/app/src/main/java/com/beacon/ChatScreen.kt) wires it into the UI, reachable by tapping a peer on the Nearby screen. `BUILD SUCCESSFUL` via Android Studio for all of it. Full design, including the ATT MTU fitting problem and the identity-binding gap the peripheral side needed solved: [docs/04-milestone-3-secure-messaging.md](docs/04-milestone-3-secure-messaging.md). **Unverified**, same reason as Milestone 2: the actual handshake/message exchange needs two devices that can actually discover each other, which real hardware is still the only confirmed path to.
- **Milestone 4 (delivery resilience, Journey 4) is written, not yet verified.** `Message` gained `retryCount`/`nextRetryAt`; [`MessageRepository.scheduleRetry`](android/app/src/main/java/com/beacon/data/MessageRepository.kt) is now the one place a send failure is handled, either scheduling an exponentially-backed-off retry or giving up to `FAILED` after 5 attempts; [`MessageRetryCoordinator`](android/app/src/main/java/com/beacon/ble/MessageRetryCoordinator.kt) reacts to ambient discovery re-resolving a peer and opens a fresh `ChatConnection` in the background to resend, independent of whether `ChatScreen` is open; a small registry ([`ActiveChatConnections`](android/app/src/main/java/com/beacon/ble/ActiveChatConnections.kt)) stops a screen-initiated chat and a background retry from double-connecting to the same peer. `BUILD SUCCESSFUL` via Android Studio for all of it. This milestone was explicitly designed without real BLE failure-mode data (still blocked, see above); every retry/backoff constant is a labeled provisional guess, not a validated number: [docs/05-milestone-4-delivery-resilience.md](docs/05-milestone-4-delivery-resilience.md) §1.
- **Milestone 5 (conversations & history) is written, not yet verified.** [`ConversationsScreen`](android/app/src/main/java/com/beacon/ConversationsScreen.kt) combines `ConversationRepository.observeAll()` (persisted since Milestone 1, unused until now) with `PeerRepository.observeAll()` into a live, name-and-timestamp conversation list, making a conversation with a currently out-of-range peer reachable for the first time; `MainActivity`'s `BeaconApp` gained a `TopLevelTab` state and a Material 3 `NavigationBar` switching between Nearby and Conversations, with tapping either a nearby peer or a conversation row opening the same `ChatScreen`. `BUILD SUCCESSFUL` via Android Studio. Full design, including re-checking (and rejecting) an earlier prediction that this milestone would need Navigation Compose: [docs/06-milestone-5-conversations-and-history.md](docs/06-milestone-5-conversations-and-history.md).

**What this deliberately does not claim:** there are zero automated tests: everything verified so far was verified manually, by hand, on one emulator, and only covers Milestones 0 and 1. `Conversation` and `Message` (the rest of the Milestone 1 domain model) have no UI exercising them beyond what Milestones 3 and 4 added. Milestones 2 through 4's BLE/crypto/retry code compile cleanly but have never actually run a discovery, a chat, or a retry. Real device testing is expected to surface real bugs, per the same honesty standard Milestone 1's JDK/build issues were tracked under. All of the above are real, tracked roadmap items, not silent gaps.

## Architecture

**Current state:** one device, no networking, Milestone 1 verified running on-device.

![Current architecture](docs/architecture/diagrams/current-state.svg)

**Target state:** the full mesh vision this is built toward. Most of this does not exist yet.

![Target architecture](docs/architecture/diagrams/target-state.svg)

## Tech stack

### In use today

| Layer | Technology | Why |
|---|---|---|
| Language | Kotlin 1.9.24 | Only mobile-first language with full BLE central + peripheral API access on Android (see [ADR-0001](docs/architecture/0001-android-first-client.md)) |
| UI | Jetpack Compose | Reactive UI that observes Room `Flow`s directly; a message status change re-renders with no manual refresh plumbing ([ADR-0004](docs/architecture/0004-project-scaffold-tooling.md)) |
| Local persistence | Room 2.6.1 (SQLite) | Compile-time-checked queries, `Flow`-based reactivity, standard Android tooling ([ADR-0002](docs/architecture/0002-room-for-local-persistence.md)) |
| Annotation processing | KSP | Faster than kapt, first-class Room support ([ADR-0004](docs/architecture/0004-project-scaffold-tooling.md)) |
| Identity keys | Android Keystore (EC / secp256r1) | Hardware-backed identity private key storage; private key never leaves the Keystore ([ADR-0003](docs/architecture/0003-android-keystore-for-identity-keys.md)) |
| Android BLE APIs (central + peripheral) | Peer discovery: advertise/scan, GATT client + server | Milestone 2, code written, **not yet run on real hardware**, see [docs/03](docs/03-milestone-2-ble-discovery.md) |
| Message encryption | Ephemeral EC/secp256r1 + ECDH + HKDF-SHA256 + AES-256-GCM | Milestone 3, code written, **not yet run on real hardware**, see [docs/04](docs/04-milestone-3-secure-messaging.md) |
| Build | Gradle (Kotlin DSL), AGP 8.5.0 | Type-checked build scripts, IDE autocomplete on config itself |

### Planned

| Technology | Purpose | Milestone |
|---|---|---|
| Wi-Fi Direct | Bulk transfer for attachments once BLE throughput is the bottleneck | Milestone 7 |
| Store-and-forward relay logic | Multi-hop delivery when sender and recipient are never simultaneously in range | Milestone 6 |
| SQLCipher (candidate) | At-rest database encryption, evaluated once transit encryption exists to compare against | Deferred (see [docs/02](docs/02-milestone-1-domain-and-persistence.md#5-decision-room-for-local-persistence)) |

## Roadmap

- [x] **Milestone 0: Foundations & repo scaffold**
- [x] **Milestone 1: Identity & local persistence** (Journeys 1 and 5 verified running end-to-end on an emulator, 2026-09-01)
- [ ] **Milestone 2: BLE peer discovery** (design + code written; individual BLE API calls confirmed working on emulators, 2026-09-09; actual discovery needs two physical devices to verify)
- [ ] **Milestone 3: Secure direct messaging** (design + code written; needs two physical devices to verify)
- [ ] **Milestone 4: Delivery resilience** (design + code written; needs two physical devices to verify; designed without real BLE failure data, see docs/05 §1)
- [ ] **Milestone 5: Conversations & history** ← current (design + code written; needs two physical devices to verify chat itself, conversation list needs no BLE and is not blocked)
- [ ] Milestone 6: Store-and-forward relay
- [ ] Milestone 7: Wi-Fi Direct bulk transport
- [ ] Milestone 8: Synchronization & conflict resolution
- [ ] Milestone 9: Visualization & connection quality UX
- [ ] Milestone 10: Observability
- [ ] Milestone 11: Adverse-network testing & benchmarking

Full roadmap with what each milestone delivers: [docs/00-foundations.md](docs/00-foundations.md#7-milestone-roadmap).

## Repository structure

```
Beacon/
├── docs/                                 # Product, architecture, and design documentation
│   ├── 00-foundations.md                 # Product definition, architecture comparison, roadmap
│   ├── 01-user-journeys.md               # Concrete user flows that drove the domain model
│   ├── 02-milestone-1-domain-and-persistence.md
│   ├── 03-milestone-2-ble-discovery.md
│   ├── 04-milestone-3-secure-messaging.md
│   ├── 05-milestone-4-delivery-resilience.md
│   ├── 06-milestone-5-conversations-and-history.md
│   ├── architecture/                     # ADRs (0001 to 0004) + current/target diagrams
│   ├── protocol/                         # (not yet populated) wire format, sequence diagrams
│   ├── security/                         # (not yet populated) threat model, crypto rationale
│   └── testing/                          # (not yet populated) network simulation, benchmarks
├── android/                              # Kotlin / Jetpack Compose client (Gradle project)
│   └── app/src/main/
│       ├── java/com/beacon/              # BeaconApplication, MainActivity, ChatScreen, ConversationsScreen
│       │   ├── data/                     # Room entities, DAOs, database, repositories
│       │   ├── crypto/                   # Identity Keystore key, ephemeral session crypto (CryptoService, Hkdf)
│       │   └── ble/                      # Discovery (central/peripheral roles) + chat (ChatConnection, ChatGattServer, ChatFrame)
│       └── res/                          # Strings, theme
├── tools/                                # (not yet populated) dev scripts, network condition simulators
├── LICENSE
└── README.md
```

## Local setup

This is currently a local-only project: there's no remote yet, so there's no `git clone` step. Once one exists, that'll be step one here.

**Prerequisites:** [Android Studio](https://developer.android.com/studio) (bundles the JDK, Gradle, and Android SDK; nothing else needs installing separately).

1. Open Android Studio → **File → Open** → select the `android/` folder (the one containing `settings.gradle.kts`).
2. There's no Gradle wrapper committed yet. Android Studio will detect this and offer to generate one using its bundled Gradle. Accept it.
3. **Set the Gradle JDK to a real JDK 17, not "Embedded JDK."** Settings → Build, Execution, Deployment → Build Tools → Gradle → Gradle JDK → Download JDK... → version 17 (any vendor). This isn't optional on newer Android Studio releases: the bundled "Embedded JDK" has moved to JDK 25, and the Kotlin 1.9.24 compiler this project pins ([ADR-0004](docs/architecture/0004-project-scaffold-tooling.md)) throws `IllegalArgumentException: 25.0.2` from KSP when run on it. This is an internal `JavaVersion.parse` failure, not anything wrong with this project's own code. If you hit that error after already changing this setting, a stale Gradle daemon from before the change is almost always why: stop it (`.\gradlew --stop`, or Android Studio's Terminal with `$env:JAVA_HOME` pointed at its own `jbr` folder if a bare shell can't find `java`) and resync.
4. Let the initial sync run (downloads AGP, Kotlin, Compose, Room, KSP; a few minutes on the first run).
5. Run on an emulator or a physical device. Note: **emulators don't support real Bluetooth radios**, so this is fine for Milestone 1 but won't be sufficient once BLE discovery (Milestone 2) exists.

**Honesty note:** all five steps are verified as of 2026-09-01: Gradle sync succeeds, the app installs and launches on a Pixel 8 (API 34) emulator, and both Journey 1 (identity creation) and Journey 5 (identity survives a restart) were exercised by hand and behaved as designed.

## Documentation

- [docs/00-foundations.md](docs/00-foundations.md): product definition, networking architecture comparison, milestone roadmap
- [docs/01-user-journeys.md](docs/01-user-journeys.md): concrete user flows
- [docs/02-milestone-1-domain-and-persistence.md](docs/02-milestone-1-domain-and-persistence.md): entity design and local persistence
- [docs/03-milestone-2-ble-discovery.md](docs/03-milestone-2-ble-discovery.md): BLE advertising byte budget, GATT contract, permission model
- [docs/04-milestone-3-secure-messaging.md](docs/04-milestone-3-secure-messaging.md): ephemeral session keys, ECDH/HKDF/AES-GCM, GATT message framing
- [docs/05-milestone-4-delivery-resilience.md](docs/05-milestone-4-delivery-resilience.md): retry/backoff design, built without real BLE failure data by necessity
- [docs/06-milestone-5-conversations-and-history.md](docs/06-milestone-5-conversations-and-history.md): conversation list, and checking the Navigation Compose prediction against reality
- [docs/architecture/](docs/architecture/): Architecture Decision Records

## License

[MIT](LICENSE)
