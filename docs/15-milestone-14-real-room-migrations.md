# Milestone 14: Real Room Migrations

_2026-09-20_

Closes the other half of the punch list named alongside Milestone 13: `fallbackToDestructiveMigration()` has silently absorbed every one of this project's four schema bumps so far (versions 1 through 5), explicitly flagged each time (D-023) as fine only because "no real release has ever shipped." This milestone replaces that with real `Migration` objects for every version transition that has ever existed, and changes what happens when a future one is missing.

## 1. What already exists vs. what this adds

Already in place: five real schema versions, each one's actual column-level shape fully known from the entity files and the decisions log entries that introduced them (D-022's `Message.retryCount`/`nextRetryAt`, D-029/D-030's `Identity`/`Peer` encryption-key columns and the new `RelayEnvelope` table, D-037's `Message` attachment columns, D-060's `RelayEnvelope.kind`/`ackedMessageId`/origin-key columns).

Not yet built: any of the four `Migration` objects a real upgrade between adjacent versions would actually need, and any schema export to verify them against.

## 2. Decision: four real migrations, backfilling what can honestly be backfilled

**Decision (D-065):** `DatabaseMigrations.kt` defines `MIGRATION_1_2` through `MIGRATION_4_5`, each writing the exact `ALTER TABLE`/`CREATE TABLE` SQL that reproduces the real, known shape change at that version, cross-checked against the entity files and decisions log, not reconstructed from memory. Three of the four are simple: new nullable columns, or `NOT NULL` columns with a genuinely correct default (`Message.retryCount INTEGER NOT NULL DEFAULT 0`, matching the entity's own `= 0` default), need nothing beyond the `ALTER TABLE` itself. `MIGRATION_2_3` is not one of those three.

**The one migration that needed real logic, not just SQL:** `Identity` gained `encryptionPublicKey`/`encryptionPrivateKey`/`encryptionPublicKeySignature` at version 3, all three genuinely `NOT NULL`, with no default that would leave an existing identity in a valid state, an identity row with an empty-string "encryption key" is not a key at all. `MIGRATION_2_3` adds the columns with a placeholder default (satisfying SQLite's own requirement that a `NOT NULL` column added via `ALTER TABLE` needs *some* default), then, in the same migration, generates a real encryption keypair and self-signs it exactly the way `IdentityRepository.createIdentity` already does for a brand-new identity, then `UPDATE`s the existing row with the real values. This is not a workaround, it's the literal same first-time key-generation event Milestone 6 already defined, simply happening later, for an identity that existed before that event was invented.

**Why this is correct, not just convenient:** nothing was ever relying on this identity having an encryption key before Milestone 6 existed; generating one now, at upgrade time, is exactly as valid as if it had been generated the moment Milestone 6's code first ran. There is no security downgrade and no data that "should have" existed but doesn't, this is the honest, first real instance of that keypair coming into being.

**Alternatives considered:**
- **A nullable `Identity.encryptionPublicKey` with lazy first-use generation**: would avoid a migration-time backfill, rejected as a bigger, unrelated change to `Identity`'s own contract (every current reader of these three fields, `MessageRepository.buildRelayEnvelope` included, assumes they're always present) purely to make a migration simpler.

**Revisit when:** never expected to change; this is the correct, permanent shape for backfilling a required secret that has a well-defined "first ever value" event.

## 3. Decision: `MIGRATION_4_5` wipes `relay_envelope` rather than backfilling garbage

**Decision:** `RelayEnvelope.originEncryptionPublicKey`/`originEncryptionPublicKeySignature` (D-059) are also `NOT NULL` with no honest default, but unlike `Identity`'s columns, there is no correct backfill value to compute: this data is the *original envelope creator's* authenticated key, never captured on receipt by a device that was only ever carrying the envelope for someone else, and not derivable from anything else already in the row. `MIGRATION_4_5` runs `DELETE FROM relay_envelope` before adding the new columns, rather than filling them with placeholder data a future reader might mistake for real.

**Why this is an acceptable loss, not a broken promise:** `RelayEnvelope` rows were already explicitly bounded, best-effort, and self-healing by design (docs/07 §7's hop/age/storage caps, §10's "a phone that never encounters another device simply never spreads or receives anything, which is correct, expected behavior"). Whatever a device happens to be carrying for other people at the exact moment it upgrades is already exactly the kind of transient state that design never promised to survive indefinitely; losing it on an upgrade is a narrower version of the same property, not a new one.

**Alternatives considered:**
- **Backfill with empty-string placeholders, let `decodeAndVerifyEncryptionPublicKey` fail closed on read (docs/13 §6's existing safe-no-op path)**: technically survives without crashing, but leaves genuinely meaningless data sitting in the table under real-looking non-null columns, worse than not having the rows at all.

**Revisit when:** never expected to change; this is the correct answer for data that was never capturable to begin with, not a temporary shortcut.

## 4. Decision: `fallbackToDestructiveMigrationOnDowngrade()` stays; the general fallback goes

**Decision:** `BeaconDatabase.build` drops `.fallbackToDestructiveMigration()` entirely and adds `.addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)` plus `.fallbackToDestructiveMigrationOnDowngrade()`. A missing *upgrade* path now throws loudly (`IllegalStateException`) instead of silently deleting everything; a *downgrade* (installing an older build over newer local data, a real, ordinary occurrence during active development, checking out an older branch, reinstalling debug builds out of order) still falls back destructively, since Room has no forward migration to reverse a schema change with and no real end user's app ever legitimately downgrades in production anyway.

**Why not keep the general fallback as a safety net:** the entire point of this milestone is that a missing migration should be a loud, caught-immediately failure, not a silent data-loss event discovered later. Keeping the blanket fallback "just in case" would mean a forgotten future migration behaves exactly as badly as it does today, defeating the milestone. A crash during development is a bug report; silent deletion is a bug nobody notices until a user asks where their messages went.

**Alternatives considered:**
- **Keep the general fallback for both directions**: rejected, exactly what this milestone exists to stop doing.
- **Write real downgrade `Migration`s too**: rejected, pointless for a schema evolving forward in one direction, no real scenario ever needs to reverse a column addition, and Room's own downgrade fallback already handles the one real (development-only) case cleanly.

**Revisit when:** never expected to change in shape.

## 5. Decision: turn on `exportSchema`, starting now, not retroactively

**Decision:** `exportSchema = false` (a Milestone 1 decision, docs/02 §5, made because the schema was still actively churning) becomes `exportSchema = true`, with `room.schemaLocation` pointed at `app/schemas/`, committed to the repo from version 5 forward.

**Why not backfill schema exports for versions 1 through 4:** they were never captured, and reconstructing them by hand now would be re-deriving the same information already fully captured in the entity files and decisions log, real effort for an artifact whose only consumer (`androidx.room:room-testing`'s `MigrationTestHelper`, an instrumented, on-device test) doesn't exist in this project yet either (a separate, already-identified punch-list item). Capturing real schema snapshots starting now means the moment instrumented test infrastructure does get built, migrations from version 5 onward already have real ground truth to test against, no retroactive reconstruction needed for anything written after this point.

**Alternatives considered:**
- **Hand-reconstruct schema JSON for versions 1 through 4 anyway**: real, avoidable busywork for artifacts nothing can consume yet; the entity files and decisions log already serve as the record of what those versions looked like.

**Revisit when:** instrumented test infrastructure is eventually built (its own future milestone); at that point `MigrationTestHelper` tests become possible for every migration written from this point forward, immediately, no additional setup.

## 6. Deliberately deferred

- **Instrumented (`MigrationTestHelper`) tests actually exercising these four migrations.** No `androidTest` infrastructure exists yet (a separate punch-list item); these migrations are logically correct by inspection, cross-checked against the real historical entity shapes, not verified by running them against a real prior-version database file.
- **Any real device ever having actually exercised any of these four migrations.** Every install of this app to date has been a fresh one; the destructive-fallback path was always taken instead, never a real migration. This milestone prepares for the first time that stops being true, it doesn't claim it already happened.
- **Schema export JSON for versions 1 through 4.** Per §5, real but unrecoverable-without-reconstruction history; not worth reconstructing for a consumer that doesn't exist yet.

## 7. Next step

`DatabaseMigrations.kt` (the four `Migration` objects), then `BeaconDatabase.build`'s `.addMigrations(...)`/`.fallbackToDestructiveMigrationOnDowngrade()` wiring, then `build.gradle.kts`'s `room.schemaLocation` KSP argument and `exportSchema = true`. Ready to start on that?
