# Milestone 11: Adverse-Network Testing & Benchmarking

_2026-09-17_

Delivers what docs/00's roadmap names for this milestone: "simulated packet loss/partition/churn, measured (not claimed) reliability numbers." The last milestone on the current roadmap, and the first one in this whole project to introduce any automated test at all, zero have existed until now, everything through Milestone 10 was verified, when verified at all, by hand.

## 1. Checking docs/00 §6's own prediction: does this milestone actually need a `core` module?

**Context:** docs/00 §6 named this milestone specifically as the trigger for finally extracting a platform-agnostic `core` module: "a platform-agnostic module only earns its keep once there's a second consumer of the protocol logic (e.g., a desktop simulator for adverse-network testing in Milestone 11)." That was a prediction made at Milestone 0, before any of the actual protocol code existed to evaluate it against.

**What the codebase actually looks like, checked now instead of assumed:** a meaningful slice of the protocol logic already has no Android dependency at all. `ChatFrame`/`RelayFramePlaintext`/`AttachmentFramePlaintext` (wire framing) use only `java.nio.ByteBuffer`. `CryptoService`'s ECDH/HKDF/AES-GCM math uses only `javax.crypto`/`java.security`, standard JVM APIs available in a plain unit test with no Android runtime. `RelayEnvelopeRepository`'s hop/age bound checks and `MessageRepository`'s backoff calculation are plain Kotlin control flow over data their DAOs hand them, testable against a hand-written fake DAO instead of a real Room database. The one real Android-only dependency inside the crypto layer is `IdentityKeyStore` (`AndroidKeyStore`, only available on a real or emulated device), and everything that touches Android's Bluetooth/Wi-Fi P2P APIs directly (`BleCentralRole`, `BlePeripheralRole`, `ChatConnection`, `ChatGattServer`, `WifiDirectFileTransfer`) obviously stays Android-only regardless of any module boundary.

**Decision: no `core` module extraction. Plain JVM unit tests (`android/app/src/test/`), written against the existing classes in place.** The prediction's premise, that testing this logic requires a second consumer to justify separating it out, turns out not to hold: Gradle's own `src/test/` source set already runs on the local JVM with no emulator or device, and everything listed above already compiles and runs there today, in its current location, without moving a single file.

**Why this is the same kind of finding as D-026/D-048, not a coincidence:** three separate predictions made early in this project (Navigation Compose for Milestone 5, a fourth `NavigationBar` tab, and now a `core` module for Milestone 11) were all reasonable guesses made before the actual shape of the problem existed to check them against, and all three, checked when their moment actually arrived, needed less machinery than predicted. This isn't this project getting lucky three times, it's what actually happens when architecture decisions get revisited against real requirements instead of executed on faith the moment a milestone number comes up.

**Alternatives considered:**
- **Extract a `core` Kotlin/JVM module now, as originally planned**: would work, and would be the more "correct" long-term shape if a second real consumer (a desktop simulator, a future iOS port) ever actually appears, rejected for now because nothing in this milestone's actual testing need requires it, `src/test/` already reaches everything worth testing without it.

**Revisit when:** a second real consumer of this protocol logic actually appears (a desktop simulator, a non-Android client), not preemptively for test-running convenience alone.

## 2. Decision: hand-written fakes for DAOs and repositories, not a mocking framework

**Decision:** tests that need a `MessageDao`/`ConversationDao`/`RelayEnvelopeDao`/`PeerRepository`/`IdentityRepository` use small, hand-written in-memory implementations of those interfaces/classes, not Mockito, MockK, or any other mocking library.

**Why:** every interface involved is small (a handful of methods), and a hand-written fake reads as plain, ordinary Kotlin, an in-memory list standing in for a table, exactly the same "no new dependency for something this small" reasoning that has governed every other tooling choice in this project (`Canvas` over a charting library in Milestone 9, `BeaconLog` over Timber in Milestone 10). A mocking framework's stub-configuration syntax would be genuinely less readable than the fakes for interfaces this size.

**Alternatives considered:**
- **Mockito/MockK**: standard, well-understood tools, rejected for the size/readability reasons above; worth reconsidering if a future test needs to verify complex interaction sequences a simple fake can't express cleanly.

**Revisit when:** a test needs to assert something a hand-written fake can't express reasonably (call ordering across many methods, argument capture across dozens of invocations); none of this milestone's tests need that.

## 3. Decision: `IdentityKeyStore`-dependent crypto (the handshake/envelope signature) is not unit tested this milestone

**Decision:** `CryptoService.signEphemeralPublicKey`/`decodeAndVerifyEphemeralPublicKey`, and anything that transitively calls into `IdentityKeyStore`, are excluded from this milestone's test suite. Everything else in `CryptoService` (ECDH key agreement, HKDF derivation, AES-GCM encrypt/decrypt) is tested directly, since none of it touches `AndroidKeyStore`.

**Why:** `IdentityKeyStore` calls `android.security.keystore.KeyGenParameterSpec` and the `"AndroidKeyStore"` `KeyPairGenerator`/`KeyStore` provider, which exists only on a real or emulated Android device, never in a plain JVM unit test. Faking it out would mean introducing an abstraction (an interface `IdentityKeyStore` could implement, injected everywhere it's currently called as a bare `object`) purely to make one milestone's test suite reach further, real, non-trivial surgery on working code for a narrow testing win.

**Alternatives considered:**
- **Introduce an `IdentityKeyStore` interface so it can be faked in tests**: real, legitimate refactor, deferred, a bigger change than this milestone's actual testing goal justifies on its own; worth reconsidering if a future milestone needs to fake identity signing for an unrelated reason too.
- **Instrumented tests (`androidTest`) that run on a real device/emulator, reaching real `AndroidKeyStore`**: would work, deferred, this project still has no reliable emulator or device to run them on (the same hardware gap named in every milestone since 2), and everything this milestone can already test doesn't need one.

**Revisit when:** instrumented test infrastructure becomes practical (real hardware, or a working emulator setup), or `IdentityKeyStore` is refactored behind an interface for an unrelated reason.

## 4. Decision: "simulated packet loss/partition/churn" means targeted failure-injection unit tests, not a network simulator

**Decision:** the roadmap's "simulated" scenarios are satisfied by unit tests that inject the specific failure conditions each resilience mechanism was actually built to handle, against fakes, not a general-purpose network simulator: repeated send failures driving `MessageRepository.scheduleRetry`'s exponential backoff schedule (docs/05 §1's "packet loss," repeated attempts failing), an envelope arriving past its hop or age bound (docs/07 §7's "churn," a device carrying something too long or too far), and the three-way peer reachability split (Milestone 9's `PeerReachability`, a stand-in for "partition," a peer known but currently unreachable).

**Why:** a real network simulator (packet-level loss/latency/partition injection across an actual multi-node BLE/relay simulation) is real, substantial infrastructure this project has never had a reason to build, and would mostly be exercising the *transport* layer (`BleCentralRole`, `ChatConnection`), which is exactly the Android-only code §1 already ruled out of this milestone's JVM test reach. Testing each resilience mechanism's own decision logic directly, at its actual boundary conditions, gives real, measured answers ("after 5 failed attempts, does this actually fall back to relay, and does the backoff schedule actually match what docs/05 documented") without needing to build a simulator to get them.

**Alternatives considered:**
- **A real desktop network simulator** (docs/00 §6's own long-term vision): genuinely valuable future work, rejected for this milestone, real infrastructure, and the `core`-module question in §1 already found no immediate need for the module it would run against.

**Revisit when:** a `core` module extraction ever happens for its own real reason (§1's revisit condition); a simulator would be natural follow-on work at that point, not before.

## 5. Deliberately deferred

- **A `core` platform-agnostic module**, per §1.
- **Instrumented (`androidTest`) tests requiring a real device or working emulator**, per §3, for `IdentityKeyStore`-dependent crypto and everything touching real Bluetooth/Wi-Fi Direct APIs.
- **A desktop mesh network simulator**, per §4.
- **Throughput/latency benchmarking.** Needs real timing data from real radios; nothing about simulated failure injection in a unit test can produce a meaningful throughput number, this is real work blocked on the same hardware gap as everything else, not something this milestone's testing approach can substitute for.
- **Mutation testing or coverage tooling.** Real, separable investment in test-suite quality itself; a first test suite existing at all is this milestone's job, not yet measuring how good it is.

## 6. Next step

Add `testImplementation` dependencies (JUnit, `kotlinx-coroutines-test`) to `app/build.gradle.kts`, then write the test suite itself: `HkdfTest` (RFC 5869 vectors), `ChatFrameTest` (round-trip and malformed-input handling across all eight frame types), `CryptoServiceTest` (ECDH/HKDF/AES-GCM correctness, excluding `IdentityKeyStore`-dependent functions per §3), `RelayEnvelopeRepositoryTest` (hop/age bound enforcement against a fake DAO), `MessageRepositoryTest` (backoff schedule and retry-exhaustion fallback against fakes), and `PeerReachabilityTest` (the three-way classification, including the `0L` sentinel edge case). Ready to start on that?
