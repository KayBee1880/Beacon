# ADR-0001: Android (Kotlin) as the first and only client platform

**Status:** Accepted, 2026-07-20

## Context

Beacon's core value depends on real BLE (and later Wi-Fi Direct) behavior. The client platform determines which radio APIs are actually available to build on.

## Decision

Build a native Android client in Kotlin. No other platform is targeted on the current roadmap.

## Consequences

- Full public API access to BLE central *and* peripheral roles, plus Wi-Fi Direct, both needed for the architecture described in `docs/00-foundations.md`.
- No iOS client possible without a substantial redesign around Multipeer Connectivity's abstraction (see alternatives below).
- A second platform, if ever pursued, would likely motivate extracting a platform-agnostic `core/` module (see ADR-0002's note on SQLDelight as the Room alternative for that scenario).

Alternatives considered and why they lost (iOS native, cross-platform frameworks, desktop-first simulation): `private/decisions/tech-decisions.md`, D-004.
