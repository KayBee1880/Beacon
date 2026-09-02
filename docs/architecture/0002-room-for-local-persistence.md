# ADR-0002: Room for local persistence

**Status:** Accepted — 2026-07-20

## Context

Milestone 1 needs a local, on-device store for `Identity`, `Peer`, `Conversation`, and `Message` that the UI can observe reactively (message status updates should reflect on screen without manual refresh logic) and that supports compile-time-checked queries.

## Decision

Use Room (Android Jetpack's SQLite ORM) for all local persistence in the Android client.

## Consequences

- Query results are exposed as Kotlin `Flow`s, so UI state can observe the database directly.
- SQL queries are verified at compile time, not at first runtime execution.
- Room is Android-only. If a platform-agnostic `core/` module is ever extracted (see ADR-0001), Room does not travel with it — SQLDelight would be the natural replacement at that point, since it generates typesafe Kotlin from SQL across Kotlin Multiplatform targets.

Alternatives considered and why they lost (raw SQLite, SQLDelight now, Realm/ObjectBox): `private/decisions/tech-decisions.md`, D-007.
