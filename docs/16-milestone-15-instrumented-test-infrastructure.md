# Milestone 15: Instrumented Test Infrastructure

_2026-09-24_

Closes the gap D-057 named at Milestone 11 and every milestone since has repeated verbatim: "`IdentityKeyStore`-dependent crypto... real-device-only." That was always true of the JVM unit test suite specifically, not of testing in general. This milestone builds the `androidTest` harness that actually reaches `AndroidKeyStore`, and uses it to close the four concrete gaps that reason has been cited for since Milestone 11.

## 1. What already exists vs. what this adds

Already in place: a plain JVM `src/test/` suite (Milestone 11) covering everything with zero Android dependency, and a precise, repeatedly-cited list of what it cannot reach: `IdentityKeyStore.sign`/`verify`, `CryptoService.decodeAndVerifyEphemeralPublicKey`/`decodeAndVerifyEncryptionPublicKey` (both call into `IdentityKeyStore.verify`), `DatabaseKeyStore` (Milestone 13), and, as of Milestone 14, four real `Migration`s that have never actually executed against any database, only ever inspected.

Not yet built: any test that runs on a real Android runtime at all. Every "run on real hardware" caveat in this README so far has meant *manual* verification (an emulator, tapped through by hand); nothing has ever been an automated, repeatable, on-device test.

## 2. Decision: `androidx.test` plus `room-testing`, versions chosen the way D-064 already taught this project to

**Decision (D-069):** `androidTestImplementation` gains `androidx.test:core:1.7.0`, `androidx.test:runner:1.7.0`, `androidx.test:rules:1.7.0`, `androidx.test.ext:junit:1.3.0`, and `androidx.room:room-testing:2.6.1` (matching this project's already-pinned Room version exactly, not the latest `room-testing` release, for the same reason every other paired dependency in this project stays version-locked to its counterpart). `defaultConfig.testInstrumentationRunner` is set to `androidx.test.runner.AndroidJUnitRunner`, the standard, required runner for any instrumented test to execute at all.

**Why versions matter enough to name explicitly, again:** D-064 already cost two real build failures by not checking a new dependency's toolchain assumptions against this project's pinned Kotlin 1.9.24/`compileSdk 34` first. These specific `androidx.test` versions were chosen because their own release notes describe being built against Kotlin 1.9.x, the same generation this project is pinned to, not because no newer version exists. This is a documented choice, not a verified one, the first real Gradle sync is still the actual test, exactly the honesty standard D-064's own walkthrough already sets for itself.

**Alternatives considered:**
- **Espresso (`androidx.test.espresso:espresso-core`)**: not added, this milestone has no UI-interaction tests to write, only Keystore/database round trips; adding a dependency for a category of test this milestone doesn't produce would be unused weight.

**Revisit when:** never expected to change in shape; individual version numbers will drift over time the normal way any pinned dependency does.

## 3. Decision: close the four already-named gaps, don't chase new coverage beyond them

**Decision:** the actual instrumented tests this milestone writes are scoped to exactly what's already been named, in this project's own words, as blocked on real `AndroidKeyStore` access:

- `IdentityKeyStoreTest`: a real key generated, signed, verified; a tampered signature or wrong key rejected. The two operations D-057 named from the start.
- `CryptoServiceKeystoreTest`: `signEphemeralPublicKey`/`decodeAndVerifyEphemeralPublicKey` round trip, and `decodeAndVerifyEncryptionPublicKey` round trip, both explicitly called out as excluded in `CryptoServiceTest.kt`'s own doc comment since Milestone 11.
- `DatabaseKeyStoreTest`: encrypt/decrypt round trip, and the same "two encryptions never collide" nonce check `CryptoServiceTest` already runs for the software-key path, now for the Keystore-backed one.
- `BeaconDatabaseTest`: the first time any of Milestone 13's or 14's code has actually executed anywhere. Builds a real `BeaconDatabase` via `BeaconDatabase.build`, writes a row, closes it, builds it again (simulating an app restart) against the *same* app-private storage, and confirms the data is still there, exercising the entire passphrase-generate-wrap-store-unwrap-reopen cycle for real, not by inspection.

**Why stop there, not also write migration-execution tests:** covered in §4, a real, separate limitation, not an oversight.

**Why not go further and write instrumented tests for BLE/Wi-Fi Direct code too:** those need two communicating devices or radios an emulator doesn't provide (docs/03 §8's own already-documented risk), a fundamentally different blocker than "the JVM has no Keystore," which this milestone's harness does nothing to remove. Real hardware is still the only path there; conflating the two would overstate what this milestone actually fixes.

**Revisit when:** a future milestone adds more `AndroidKeyStore`-touching code; the harness this milestone builds already supports it, no infrastructure work needed again, just new test files in the same shape as these four.

## 4. Decision: no migration-execution tests yet, an honest, already-predicted gap

**Decision:** `MigrationTestHelper` is wired up (the `androidTest` source set's `assets` directory points at `app/schemas/`, where `exportSchema` (D-068) writes each version's JSON), but no test actually exercises `MIGRATION_1_2` through `MIGRATION_4_5` against it.

**Why this isn't just left undone by accident:** `MigrationTestHelper` needs a real exported schema for a migration's *starting* version to construct a database at that version to migrate from. `exportSchema` was `false` until Milestone 14 (docs/02 §5); the only schema this project has ever captured is version 5, the *ending* version of the last migration in the chain, not the starting version of any of them. None of the four existing migrations have a testable starting point; this was already named as the expected outcome in docs/15 §5's own "Revisit when": "every migration from version 5 onward already has real ground truth waiting." That trigger hasn't happened yet, version 6 doesn't exist.

**What this means concretely:** the moment this project's schema bumps to version 6 for any future reason, that migration (`MIGRATION_5_6`) becomes the first one with both a real starting schema (5) and ending schema (6) captured, and a real `MigrationTestHelper` test becomes possible immediately, using infrastructure this milestone already finishes building. Nothing about the four pre-existing migrations changes; they stay exactly as verified as they are today (logically correct by inspection, never executed).

**Alternatives considered:**
- **Hand-reconstruct schema JSON for versions 1 through 4 to unlock testing the existing migrations**: the same real, avoidable-busywork tradeoff docs/15 §5 already rejected for the identical reason, still true here.

**Revisit when:** version 6 (or any future version) exists; a `MigrationTestHelper` test for that transition should be written in the same commit that adds its migration, not deferred again.

## 5. Real finding: `androidTest` method names cannot use backtick-with-spaces

**What happened:** the first real `connectedAndroidTest` run failed at `dexBuilderDebugAndroidTest`, not compilation: `D8: ... Space characters in SimpleName '...' are not allowed prior to DEX version 040`, for every one of the four test files' backtick-named `@Test fun`s, plus a synthetic continuation class D8 generates from a suspend test function's own name.

**Why this hadn't already been caught:** `./gradlew testDebugUnitTest` and `./gradlew assembleDebug` had both already succeeded for this milestone's code, neither one ever DEXes anything under `androidTest`; `connectedAndroidTest` was the first task this project had ever run that actually does. The `src/test` (JVM) suite's identical-looking backtick-with-spaces convention was never at risk, those files compile to plain `.class` files and never get DEXed at all.

**Decision (D-070):** every `androidTest` method name became a plain camelCase identifier, no backticks, no spaces. `src/test`'s existing convention is untouched, this is a real, hard platform constraint specific to code that actually gets packaged into an installable APK, not a style preference to apply project-wide.

## 6. Real finding: a real bug in the wrong-passphrase test itself, caught by actually running it

**What happened:** after D-070's fix, the first real `connectedAndroidTest` run passed 11 of 12 tests, real, on-device confirmation of `IdentityKeyStore`, `CryptoService`'s two Keystore-dependent functions, `DatabaseKeyStore`, and `BeaconDatabase`'s restart-persistence case. The twelfth, `aDifferentPassphraseCannotOpenAnAlreadyEncryptedDatabaseFile`, failed: `expected opening with the wrong passphrase to fail`.

**Why it failed:** the test's first version opened the same database file a second time through `BeaconDatabase.build(context, databaseName, wrongPrefsName)`, a *different* `SharedPreferences` name than the one that had actually encrypted the file, expecting the freshly-generated, different passphrase under that name to fail against SQLCipher. It never got the chance to: a `prefsName` with no wrapped passphrase yet is `getOrCreatePassphrase`'s own signal for "first run, wipe anything already on disk" (D-063), and `BeaconDatabase.kt`'s own walkthrough had already named this exact scenario, a wrapped-passphrase entry and the file it belongs to falling out of sync, as a real, unguarded edge case. The test's own wrong-prefs-name open silently deleted the already-encrypted file and created a fresh, empty one before SQLCipher's real passphrase check was ever reached.

**Decision (D-071):** the test now bypasses `BeaconDatabase.build`/`getOrCreatePassphrase` entirely for this one case, opening the file directly (`Room.databaseBuilder(...).openHelperFactory(SupportFactory(wrongPassphrase))`) with an arbitrary, deliberately-wrong passphrase against the same `databaseName`. This is a test-design fix, not a production-code one: `getOrCreatePassphrase`'s "first run" heuristic is correct for its actual job, the test was simply asking its question through the wrong door.

## 7. Deliberately deferred

- **Any BLE/Wi-Fi Direct instrumented test.** Different blocker entirely (needs two real radios, docs/03 §8), unaffected by this milestone.
- **Espresso/UI instrumented tests.** No UI-interaction test exists yet to justify the dependency; add it if one is ever actually written.
- **Retroactive migration-execution tests for versions 1 through 4.** Per §4, would require reconstructing schema data that was never captured, real busywork already rejected once for the same reason in docs/15.

## 8. Next step

`build.gradle.kts`'s `androidTestImplementation` dependencies, `testInstrumentationRunner`, and the `androidTest` source set's `assets.srcDirs` pointing at `app/schemas/`, then the four test files themselves (`IdentityKeyStoreTest`, `CryptoServiceKeystoreTest`, `DatabaseKeyStoreTest`, `BeaconDatabaseTest`). Ready to start on that?
