# Milestone 10: Observability

_2026-09-17_

Delivers what docs/00's roadmap names for this milestone: "structured logs, metrics, dev diagnostics vs. user-facing status." Unlike Milestone 8's finding (a real question that turned out to need no new machinery), this one has real, concrete gaps: every log line in this codebase today is a raw `android.util.Log.w` call, visible only over `adb logcat`, and there is no way, anywhere in the app, to see what's actually happening without a connected computer.

## 1. What already exists vs. what this milestone adds

Already in place: user-facing status, spread across every milestone that needed it, `ChatConnectionState` text, `MessageStatus` text, `AttachmentState` text, `SignalStrength` text (Milestone 9's `ChatScreen`/`MeshScreen`/`PeerDiscoveryScreen`). This is the "user-facing status" half of this milestone's roadmap phrase, and it's already honestly satisfied by work done for other reasons; this milestone doesn't need to add more of it, only keep it cleanly separate from what it does add.

Not yet built: any structure to the 33 existing `Log.w` calls across seven files (`BleCentralRole`, `BlePeripheralRole`, `ChatConnection`, `ChatGattServer`, `MessageRetryCoordinator`, `RelayGossipSession`, `WifiDirectFileTransfer`), all warn-level, all free text, none captured anywhere `adb` isn't; and any way at all to see local operational state (how many peers, how many messages, how much relay traffic is being carried) without reading source code and inferring it.

## 2. Decision: a small structured logging facade, replacing every existing `Log.w` call

**Decision:** a new `BeaconLog` object (`com.beacon.diagnostics`) wraps `android.util.Log` with `d`/`w`/`e` functions matching the existing call sites' shapes almost exactly (`tag`, `message`, optional `throwable`), so migrating is close to a pure find-and-replace. Every call still reaches `adb logcat` exactly as before (nothing about existing debugging workflows regresses), but is now also captured into a bounded in-memory ring buffer (`MAX_ENTRIES = 200`, a starting guess) exposed as a `StateFlow<List<LogEntry>>`, the same private-mutable/public-read-only shape `BleCentralRole.rssiByPeerId` and every other live app state already uses.

**Why:** the roadmap's "structured logs" is satisfied by giving every log entry a consistent, typed shape (timestamp, level, tag, message) instead of an opaque `Log.w(TAG, "free text")` call whose only structure lives in a human's head. Capturing entries in-process, not just to logcat, is what makes the diagnostics screen (§3) possible at all, `adb` access is exactly the thing docs/00 §1's disaster-response and remote-expedition personas can't assume they have.

**Alternatives considered:**
- **A third-party logging library** (Timber, or a real structured-logging framework): rejected, this project has never added a dependency for something this small (Canvas instead of a charting library in Milestone 9, hand-rolled HKDF instead of a crypto library in Milestone 3); a 200-line object does everything actually needed here.
- **Leave existing `Log.w` calls as-is, only use `BeaconLog` for new code going forward:** rejected, two parallel logging conventions in the same codebase is a real, avoidable inconsistency, and the whole value of the in-app diagnostics screen depends on capturing everything, not just what's written after this milestone.

**Revisit when:** not expected to change; this is a small, stable, load-bearing piece of infrastructure now, not a provisional stand-in.

## 3. Decision: a Diagnostics screen, reachable from Nearby, not a fourth top-level tab

**Decision:** a new `DiagnosticsScreen`, reachable via a small text button in `PeerDiscoveryScreen`'s header, opened as a flat overlay (the same `selectedPeer != null` pattern `ChatScreen` already uses, one level deep, never nested). It shows: identity (display name, a short public-key fingerprint), peer counts broken down by `PeerReachability` (Milestone 9's own `DIRECT`/`RECENTLY_DIRECT`/`MESH_ONLY` split, reused unchanged), message counts by `MessageStatus`, the number of relay envelopes currently held, and the most recent `BeaconLog` entries, newest first.

**Why a button, not a fourth tab:** D-027/D-048's own reasoning already flagged three tabs as roughly where icons start being worth considering; a diagnostics view is a secondary, occasional-use tool, not a primary destination a user reaches for the way Nearby/Conversations/Mesh are, putting it in the main tab row would overstate its importance and crowd a navigation bar that's already at its planned limit of plain text labels.

**Alternatives considered:**
- **A fourth top-level tab:** rejected on the crowding grounds above.
- **A settings-style overflow menu:** would need introducing an overflow-menu pattern this app has never had anywhere, real new UI machinery for one entry point; a single button on the one screen every user already sees is simpler and costs nothing new.

**Revisit when:** a second secondary-tool screen is ever needed (e.g., a future settings screen), at which point a shared, real "more" destination for both might be worth the machinery this decision currently avoids.

## 4. Decision: no telemetry leaves the device, no crash-reporting SDK, ever

**Decision:** every count and log entry this milestone surfaces is computed locally, read once or observed reactively from this device's own Room database and in-memory state, and rendered only inside the app itself. No analytics SDK, no crash-reporting service (Firebase Crashlytics, Sentry, or equivalent), no network call of any kind is introduced anywhere by this milestone.

**Why:** this isn't a new precaution invented for this milestone, it's the same line this project has never crossed since Milestone 0: docs/00 §1 names censorship and disaster-response contexts as core scenarios this app exists for, populations for whom a background service silently phoning usage or crash data to a third party would be a real, material risk, not a hypothetical one. "Observability" for Beacon has to mean "an operator in the field can see what's happening," not "a developer's dashboard sees what's happening," the two are easy to conflate in a typical app and deliberately kept apart here.

**Alternatives considered:**
- **A crash-reporting SDK, since real device bugs are expected and currently unfindable without a connected debugger:** genuinely tempting given how much of this codebase has never run once, rejected anyway, the privacy cost is exactly wrong for this project's own stated threat model, and this milestone's in-app diagnostics screen (§3) already gives a real, if more manual, path to the same information without it.

**Revisit when:** never expected to change; this is a permanent boundary, not a provisional one.

## 5. Deliberately deferred

- **Log filtering or search in the diagnostics screen.** A first version shows everything captured, newest first; filtering by level or subsystem is real, separable UI work once there's enough real log volume to make it worth doing.
- **Persisting log entries across app restarts.** The ring buffer is in-memory only; closing the app loses it. A developer actively investigating a live issue would keep the app open; persisting to Room is real, separable work deferred until that's shown to actually matter.
- **A manual "export logs" action** (share sheet, save to a file). Would help a field operator hand diagnostic output to someone else without reading it aloud; genuinely useful, deferred, not needed for a first version that at least makes the information visible at all.
- **Structured metrics beyond simple counts** (rates, histograms, time-series). Nothing in this app has needed anything beyond "how many right now" yet; real time-series tracking is closer to Milestone 11's adverse-network benchmarking territory than this milestone's job.

## 6. Next step

`BeaconLog` first, then migrate all 33 existing `Log.w` call sites across the seven files that have any, then the small aggregate-count additions to `MessageDao`/`MessageRepository` and `RelayEnvelopeDao`/`RelayEnvelopeRepository`, then `DiagnosticsScreen` itself and its entry point in `PeerDiscoveryScreen`/`BeaconApp`. Ready to start on that?
