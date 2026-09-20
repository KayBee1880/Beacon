# Milestone 13: At-Rest Database Encryption

_2026-09-18_

Closes the gap docs/02 §5 named and deliberately deferred at the very start of this project: "re-encrypting message content with a separate at-rest key (e.g. via SQLCipher) is a real hardening option, deliberately deferred rather than silently skipped; noted for revisit once transit encryption exists to compare it against." Transit encryption has existed since Milestone 3, and every message, identity, and private key in `beacon.db` has sat in a plain SQLite file ever since. This milestone closes that.

## 1. What already exists vs. what this adds

Already in place: Android's own file-based encryption (FBE) and app sandboxing already protect `beacon.db` at rest, this was docs/02 §5's own stated reasoning for deferring further hardening in the first place, not a gap nobody noticed.

Not yet built: a second, independent layer that still protects the database's *content* even if something manages to read the raw file outside the app's own sandbox, a scenario docs/00 §1's threat model makes directly relevant, not hypothetical, for an app built for use during device seizure or forensic extraction risk (disaster response, censorship contexts).

## 2. The core problem: what SQLCipher adds beyond FBE, and what it doesn't

**Context:** Android FBE protects app data using a key tied to the device's own lock-screen credential and boot chain; it's real protection, but it's scoped to "is the device itself in a state where the OS will hand out the decrypted file to anyone/anything asking," which is not the same question as "can this specific app's data be read if a copy of the raw file escapes that boundary." `adb backup`-style extraction, an unlocked or rooted device, or forensic tooling used against a seized phone can all end up with a raw copy of `beacon.db`'s bytes without going through Android's own per-app access control at all. FBE alone does nothing at that point, the file is just SQLite's ordinary, fully-readable-once-you-have-the-bytes format.

**What SQLCipher actually changes:** `beacon.db`'s bytes become AES-256 encrypted at the SQLite page level, unreadable without the database's own passphrase, a genuinely separate secret from anything FBE manages. A copy of the raw file, on its own, is now just ciphertext.

**What this still doesn't protect against, named honestly rather than left implicit:** the passphrase itself is wrapped by an Android Keystore key scoped to this app's own UID (§3). Root access to a *running, unlocked* device, or anything that can execute code as this app's own process, can still ask the Keystore to unwrap it, the same way it could extract any other secret this app holds (the identity private key included, this is not a new category of exposure). This milestone's real, honest scope is: protects the raw file against extraction that does not also compromise code execution as this app, which covers the `adb backup`/lost-or-seized-but-locked-or-uncompromised-device cases docs/00 §1 actually cares about, not a defense against a fully rooted, actively-running attack.

**Alternatives considered:**
- **Do nothing further, rely on FBE alone**: this was docs/02 §5's status quo being deferred *from*, not a real option being newly rejected, already covered by the above.
- **`androidx.security-crypto`'s `EncryptedSharedPreferences` for message content only, leave the rest of the schema alone**: rejected, encrypts a subset of columns while leaving `Identity`'s private keys and every `Peer`/`RelayEnvelope` row in plain SQLite, a partial fix that would misrepresent the actual protection level. SQLCipher encrypts the whole database file at the page level, no per-column decisions to get wrong or forget.

**Revisit when:** never expected to change in kind; the residual root-level gap named above would need a fundamentally different approach (attestation, a hardware-backed unlock ceremony) that's out of scope for a local-only messenger and not something this milestone's threat model asks for.

## 3. Decision: a random passphrase, wrapped by a new, purpose-built Android Keystore AES key

**Decision (D-062):** on first run, `BeaconDatabase.build` generates a random 256-bit passphrase (`SecureRandom`), encrypts it with a brand-new Android Keystore AES-256-GCM key (alias `beacon-database-key`, `PURPOSE_ENCRYPT`/`PURPOSE_DECRYPT`, a new small object, `DatabaseKeyStore`, mirroring `IdentityKeyStore`'s existing shape), and stores the wrapped result (nonce-plus-ciphertext, Base64, the same bundling convention `CryptoService.encrypt` already established) in a dedicated `SharedPreferences` file. Every later launch reads that same wrapped value back, unwraps it via the Keystore key, and hands the recovered passphrase to SQLCipher.

**Why a fresh Keystore key, not the existing identity signing key:** the same purpose-separation constraint D-029 already ran into for the relay encryption key, an `AndroidKeyStore` EC key generated with `PURPOSE_SIGN`/`PURPOSE_VERIFY` cannot also do encryption, a real platform restriction. Unlike D-029, though, this key needs no `minSdk` tradeoff at all: `PURPOSE_ENCRYPT`/`PURPOSE_DECRYPT` with `BLOCK_MODE_GCM` for a plain AES key has been supported since API 23, well under this project's `minSdk = 26`, so there's no hardware-vs-software-key fork to make here, hardware backing is simply available.

**Why the passphrase can't just *be* the Keystore key:** SQLCipher's native code needs literal passphrase bytes to open the database file; an `AndroidKeyStore` key's whole point is that its key material never leaves the Keystore. The Keystore key's only job is wrapping/unwrapping a separate, ordinary random value that SQLCipher actually uses.

**Alternatives considered:**
- **`androidx.security-crypto`'s `EncryptedSharedPreferences`/`MasterKey`**: would do the same wrapping job with a Google-maintained library rather than hand-rolled Keystore calls. Rejected in favor of the hand-rolled approach specifically because `IdentityKeyStore` already establishes exactly this pattern (a small object owning one Keystore-backed key) in this codebase; adding a second, differently-shaped dependency for the same kind of operation is more surface area, not less, for a project that already has the primitives it needs.
- **A user-supplied app passcode/PIN as the passphrase**: rejected, Beacon has no login/passcode concept anywhere in its design (docs/00's own user journeys), inventing one purely to gate database access would be new, unrelated product scope, not a security refinement.

**Revisit when:** never expected to change in shape; if `minSdk` is ever raised for an unrelated reason, there's no forced re-evaluation here the way D-029 has one, this key was never blocked from hardware backing to begin with.

## 4. Decision: first-run wipe of any pre-existing plaintext database, no in-place conversion

**Decision (D-063):** `BeaconDatabase.build` treats "no wrapped passphrase exists yet in `SharedPreferences`" as the signal that this is the first time this milestone's code has run on this install, and deletes any `beacon.db`/`-wal`/`-shm`/`-journal` files already on disk before generating a fresh passphrase and opening SQLCipher for the first time. No attempt is made to decrypt-then-reencrypt an existing plaintext database in place.

**Why:** the same reasoning D-023 already established for every schema-version bump so far, no real release has ever shipped, so there is no real installed data anywhere this would actually cost. SQLCipher's own ecosystem has tooling for converting a live plaintext database to encrypted in place (attach-and-export style migration), genuinely useful once real user data exists to preserve, but building and testing that now would be real, unnecessary complexity for data that, as of this milestone, has never once represented an actual user's real conversation history anywhere.

**Why the signal is "does a wrapped passphrase exist," not "does `beacon.db` look encrypted":** sniffing a SQLite file's header to guess whether it's already SQLCipher-encrypted is exactly the kind of fragile, easy-to-get-subtly-wrong check this project's own engineering discipline avoids elsewhere (see the "one clear signal, not an inferred one" reasoning behind D-061's `receiveIncoming` return value). Whether a passphrase has ever been generated on this install is already the one fact that has to be tracked anyway; reusing it as the migration trigger adds no new state.

**Alternatives considered:**
- **SQLCipher's built-in plaintext-to-encrypted migration utility**: real, legitimate future work, deferred to whenever this project actually has a real release with real user data to preserve across an upgrade, the same trigger condition D-023 already names for replacing `fallbackToDestructiveMigration()` itself.

**Revisit when:** the same moment `fallbackToDestructiveMigration()` needs to be replaced with a real `Migration`, a real release exists with real data. The two are naturally linked, both are "no real data yet" shortcuts that expire at the same milestone.

## 5. Decision: the deprecated `android-database-sqlcipher`, not the current `sqlcipher-android`, chosen on real build evidence

**Decision (D-064):** the current, actively maintained `net.zetetic:sqlcipher-android` was tried first and rejected after two real, sequential build failures, not preference. `4.19.0` fails outright, its AAR metadata requires `compileSdk 37`; this project is on `compileSdk 34` with AGP 8.5.0, whose own maximum recommended `compileSdk` is 34. `4.17.0` (the newest release before that requirement was introduced in `4.18.0`) compiles, but transitively pulls an `androidx.sqlite` build compiled with Kotlin 2.1, a hard metadata-version error against this project's pinned Kotlin 1.9.24 compiler, not merely a warning. `net.zetetic:android-database-sqlcipher:4.5.3` with `androidx.sqlite:sqlite:2.1.0`, old enough to predate both problems entirely, is what's actually integrated (`net.sqlcipher.database.SupportFactory` and `SQLiteDatabase.loadLibs(context)`, not the newer library's `net.zetetic.database.sqlcipher` namespace).

**Why not chase a working version further on the current library instead:** two independent version-specific failures in a row on the actively-developed line is a real signal, not bad luck, that it now tracks a materially newer toolchain baseline (Kotlin 2.x, `compileSdk 37`) than this project is pinned to. The older artifact's entire value here is that it stopped moving years ago, at a toolchain generation this project's own pinned versions (Kotlin 1.9.24, Room 2.6.1, `compileSdk 34`) already match without any other change.

**Real, named tradeoff, not a free choice:** `android-database-sqlcipher` is officially deprecated, and per Zetetic's own documentation, lacks the 16KB native-library page-size alignment Google Play now requires for new and updated app submissions. Acceptable for a project with no release anywhere on the horizon, not acceptable indefinitely, see the revisit condition below.

**Alternatives considered:**
- **Force an older `androidx.sqlite` transitive version against `sqlcipher-android:4.17.0`** (Gradle dependency constraints): possible, rejected as fighting a library's own declared minimum rather than using a version actually designed to work with it, more fragile, not less.
- **Upgrade Kotlin/`compileSdk`/AGP to satisfy the current library**: a much larger, unrelated toolchain upgrade with its own compatibility surface (the Compose-compiler/Kotlin version coupling this project already has a documented gotcha about, `app/build.gradle.kts`'s own walkthrough), entirely out of scope for adding at-rest encryption.

**Revisit when:** this project ever approaches a real Play Store submission, the exact trigger Zetetic's own migration guidance names; by then this project will likely have caught up to a newer Kotlin/AGP baseline for other reasons too, making the newer library's requirements far less likely to still be a fresh obstacle.

## 6. Decision: no Room schema version bump

**Decision:** `BeaconDatabase`'s `version`, `entities` list, and every DAO are untouched. This milestone changes how the database file's bytes are stored on disk (SQLCipher's page-level encryption via `SupportFactory`), not the shape of any table.

**Why:** Room's version number tracks schema shape, not storage encoding; conflating the two would mean bumping a version for a change no `Migration` (real or destructive-fallback) actually needs to reconcile anything about. The one-time file wipe in §4 is gated on the passphrase's own existence, entirely independent of Room's own migration machinery.

## 7. Deliberately deferred

- **In-place migration of a real, pre-existing encrypted-format-less database.** Per §4, real work for once real user data exists to preserve.
- **Passphrase rotation.** Nothing currently rotates the random passphrase or the Keystore key wrapping it; a compromise of the wrapped value plus Keystore access would expose the same passphrase indefinitely. Revisit if this project ever needs a "wipe and re-key" security response feature.
- **Any change to `exportSchema = false`.** Unrelated to this milestone; `BeaconDatabase.kt`'s own existing gotcha about schema export already covers when that should change.
- **Defending against a rooted or actively-compromised device.** Named explicitly in §2 as this milestone's real boundary, not silently assumed away.
- **Migrating to `sqlcipher-android`.** Per §5, real, necessary future work, not optional hardening, once a real Play Store submission is actually on the horizon.

## 8. Next step

`net.zetetic:android-database-sqlcipher` plus its `androidx.sqlite` companion dependency in `build.gradle.kts`, then `DatabaseKeyStore` (the new Keystore-backed wrap/unwrap object, `crypto` package), then `BeaconDatabase.build`'s passphrase bootstrap and `SupportFactory` wiring. Ready to start on that?
