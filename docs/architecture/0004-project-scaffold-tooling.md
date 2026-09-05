# ADR-0004: Project scaffold tooling (Compose, KSP, minSdk 26, Kotlin DSL)

**Status:** Accepted, 2026-07-21

## Context

Scaffolding the Android project required choosing a UI toolkit, an annotation-processing backend for Room, a minimum supported OS version, and a Gradle script language.

## Decision

- UI: Jetpack Compose.
- Room annotation processing: KSP.
- `minSdk`: 26 (Android 8.0).
- Gradle scripts: Kotlin DSL (`.gradle.kts`).

## Consequences

- UI observes Room `Flow`s reactively with no manual refresh/binding code.
- Faster incremental builds than kapt would give; Room's KSP support is first-class, so no processor compatibility gap.
- Excludes devices older than Android 8.0 (2017). Accepted given more consistent BLE peripheral-mode behavior from that version onward.
- Gradle scripts get IDE type-checking and autocomplete; less copy-pasteable from older Groovy-based tutorials.

Full reasoning and alternatives (XML Views, kapt, minSdk 21, Groovy DSL): `private/decisions/tech-decisions.md`, D-008.
