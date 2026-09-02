# ADR-0003: Android Keystore for identity private key material

**Status:** Accepted — 2026-07-20

## Context

`Identity` (ADR/Milestone 1 domain model) needs a private key that authenticates this device to peers. Beacon's threat model assumes wireless interception; a private key stored in plaintext alongside chat history in the app database would mean a single storage compromise breaks confidentiality retroactively and going forward.

## Decision

Generate and hold the identity private key inside the Android Keystore. The Room `Identity` table stores only the public key and a `keystoreAlias` reference — never the private key itself. Signing/decryption operations are performed *by* the Keystore using the key, without the key ever being exposed to application code.

## Consequences

- On devices with hardware-backed Keystore support, the private key is not extractable even if the device is rooted or app storage is dumped.
- Key usage goes through the Keystore's API surface rather than a general-purpose crypto library for this specific key — acceptable since this key's only job is proving device identity, not general message encryption (that's Milestone 3's `CryptoService`, a separate decision).

Alternatives considered and why they lost (raw key storage in the DB, EncryptedSharedPreferences, a software-only keystore): `private/decisions/tech-decisions.md`, D-006.
