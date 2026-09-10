# Milestone 4: Delivery Resilience

_2026-09-10_

Covers Journey 4 from [01-user-journeys.md](01-user-journeys.md): a connection drops mid-conversation, and a message sent right before or during that drop has to survive it, retry automatically once the peer is back in range, and eventually reach a real terminal failure state if it never does.

## 1. The constraint this milestone was explicitly built to wait for

Both docs/02 §6 and docs/04 §9 deferred retry/backoff design specifically because "designing a retry policy before the connection layer's real failure modes are known would mean guessing." That constraint has not actually been lifted: docs/03 §8's 2026-09-09 update confirms every individual BLE API call works correctly, but real device-to-device discovery and chat are still unverified, so the *actual* failure modes this milestone is supposed to be resilient against (how often does a real connection drop mid-message, how long does a real reconnect take, what does a real flaky link look like) remain unknown.

This milestone proceeds anyway, by necessity, but on those terms explicitly: every timing constant here (backoff intervals, retry limits) is a labeled starting guess, the same treatment `NEARBY_GRACE_PERIOD_MS` already got in Milestone 2, not a validated number. The mechanism is architected so those constants are the only thing expected to change once real testing happens; the mechanism's shape itself is derived from Journey 4's actual requirements, not from guessed timing data.

## 2. What already exists vs. what this milestone adds

Already in place: the full `SENDING → SENT → DELIVERED → FAILED` state machine, `MessageDao.getUndelivered()` (Milestone 1, with a known gotcha already flagged: it doesn't yet exclude `FAILED`), and `ChatConnectionState.DISCONNECTED` already surfacing a "peer disconnected" state to the UI, not silence. Journey 4's first bullet ("sender notices the connection dropped") is, in fact, already satisfied by Milestone 3's work.

Not yet built: anything that actually retries. A message that fails to send today either fails immediately (`ChatConnection.sendMessage`'s "not `READY`" guard) or, if the connection drops mid-flight, simply sits in `SENDING`/`SENT` forever with nothing ever picking it back up.

## 3. The problem: `ChatConnection` is screen-scoped, but retry has to be automatic

The naive fix, "add a retry loop inside `ChatConnection`," breaks immediately: a `ChatConnection` only exists for as long as `ChatScreen` is composed (docs/04's whole session model). Journey 4 is explicit that pending messages "resume sending automatically" when the peer comes back into range, not "once the user happens to reopen that chat." The retry has to work even if nobody is looking at the screen.

**The fix:** a new application-level component, alongside `peerDiscovery` in `BeaconApplication` rather than inside any screen, that reacts to peer resolution events and drives retries independently of UI state.

## 4. Decision: retry triggered by peer re-resolution, not a polling timer

**Decision:** the retry mechanism piggybacks on `PeerRepository`'s existing "a peer was just resolved" signal (the same event `BleCentralRole`'s `onPeerResolved` already produces) rather than running its own periodic check.

**Why:** Journey 4 names the failure mode a naive timer-based retry would recreate directly: "retry forever with no backoff → battery drain scanning/reconnecting for a peer that may never come back." Milestone 2's ambient discovery is already continuously scanning in the background for exactly the purpose of noticing when a peer is nearby; a separate retry-specific polling loop would duplicate that battery cost for information the app already has the moment it becomes true.

**Alternatives considered:**
- **A periodic timer that attempts reconnection regardless of whether the peer has actually been seen**: this is the specific battery-drain failure mode Journey 4 calls out, not a hypothetical one.
- **Retry only when the user manually reopens the chat**: simpler, but directly contradicts Journey 4's "resume sending automatically... the user does not have to manually resend."

**Revisit when:** never, structurally: reusing discovery's own signal is correct regardless of what real timing data eventually shows; only the backoff constants in §5 are expected to change.

## 5. Decision: bounded retry count with exponential backoff, `Message` gets two new columns

**Decision:** `Message` gains `retryCount: Int` (default 0) and `nextRetryAt: Long?` (default null). Each failed send attempt increments `retryCount` and sets `nextRetryAt` to an exponentially increasing delay from now, capped at a maximum interval. Once `retryCount` exceeds a maximum, the message transitions to `FAILED`, the real terminal state Journey 4 requires ("a real 'failed, won't retry automatically' terminal state the user can act on").

`nextRetryAt` matters even though the *trigger* is opportunistic (peer resolved), not time-based: a peer that stays in range produces repeated resolve events, and without a backoff gate, every single one would trigger an immediate resend attempt for a message that just failed moments ago. `nextRetryAt` is what keeps "retry when the peer is seen again" from degenerating into "retry constantly while the peer is in range."

**Why exponential backoff specifically:** it's the standard, well-understood answer to "don't hammer something that just failed, but don't wait forever either." This is appropriate here even without real timing data, since the *shape* of the policy (back off more after repeated failures) is justified independent of the exact numbers, which are labeled provisional per §1.

**Alternatives considered:**
- **A fixed retry interval**: simpler, but doesn't distinguish "failed once, probably transient" from "failed five times, probably not coming back soon," which is exactly the distinction a real terminal-failure state needs to be meaningful.
- **Unlimited retries, no terminal state**: directly rejected by Journey 4's own explicit requirement for a real failed state the user can act on.

**Revisit when:** real device testing (still blocked, per §1) shows actual reconnection timing, at which point the specific base delay, cap, and max retry count are the values to tune, not the mechanism itself.

## 6. Decision: a destructive Room migration for now, a real one before any actual release

**Context:** `retryCount`/`nextRetryAt` are new columns, which means bumping `BeaconDatabase`'s `@Database(version = 1)`. Room refuses to open a database at a newer version than it has a migration path for; naively bumping the version with no migration defined throws `IllegalStateException` at runtime for any existing install, exactly the kind of thing that only shows up as a crash report, not a compile error.

**Decision:** use `Room.databaseBuilder(...).fallbackToDestructiveMigration()` for this version bump, which drops and recreates all tables on a version mismatch rather than requiring a hand-written `Migration`.

**Why:** Beacon has never actually shipped a release with real user data to preserve, everything so far has been Android Studio–installed debug builds during development. Losing local data on a schema change costs nothing real right now. Writing a proper `Migration` object for data that doesn't exist yet would be solving a problem this project doesn't have.

**Alternatives considered:**
- **Write a real `Migration(1, 2)` now**: the technically more complete answer, and the one this project will actually need eventually, but premature. There's no real installed data anywhere to migrate, so there's nothing to verify the migration against.

**Revisit when:** before any real release/distribution of the app happens. At that point `fallbackToDestructiveMigration()` becomes actively harmful (it would silently delete real users' message history on every future schema change) and must be replaced with real `Migration` objects.

## 7. Decision: a retry opens a fresh `ChatConnection`, the same session model as any other chat, with a shared registry to prevent double-connecting

**Decision:** the retry coordinator, on a qualifying peer resolution, constructs a plain `ChatConnection` exactly the way `ChatScreen` does (same handshake, same per-session ephemeral key), sends whatever messages currently qualify (§5's backoff gate), then disconnects once they're all resolved (delivered or re-failed) or a bounded timeout passes. A new small registry (peer id → active `ChatConnection`), checked by both `ChatScreen` and the retry coordinator before opening a connection, prevents the two from independently opening two simultaneous connections to the same peer.

**Why:** No new connection type or protocol path is needed, a retry is not conceptually different from any other chat session, it just happens to be triggered by discovery instead of a screen opening. The registry exists because Android's low concurrent-GATT-connection cap (already flagged in docs/03 §7 and docs/04 §5) makes two independent connections to the same peer a real resource-contention risk, not a theoretical one, and it's the same "one connection per peer at a time" invariant `ChatScreen` already implicitly assumes.

**Alternatives considered:**
- **A dedicated background-only connection type, separate from `ChatConnection`**: would duplicate the handshake/session logic for no real benefit; a retry connection and a user-initiated chat connection do exactly the same thing.
- **No registry, accept the rare double-connect race**: rejected given the concurrent-connection ceiling is already a named, tracked constraint elsewhere in the project, not a new risk being introduced here.

**Revisit when:** Milestone 6 (relay) likely needs a more general connection-management story than one registry keyed by peer id; not solved more generally now since only two call sites exist today.

## 8. Decision: `FAILED` is reached only after retries are exhausted, not on the first failure

**Decision:** `ChatConnection.sendMessage`'s failure paths (no session key yet, a GATT write error) no longer call `MessageRepository.markFailed` directly. They call a new `MessageRepository.scheduleRetry`, which increments `retryCount`/sets `nextRetryAt`, or calls `markFailed` itself once the max retry count is exceeded. This is the same function whether the failing attempt was the user's original send from `ChatScreen` or a later background retry attempt, there is exactly one failure-handling path, not two.

**Why:** This is the change that actually makes Journey 4's "not yet delivered, not failed outright" distinction real. Milestone 3 treated any send failure as immediately terminal; that was correct for a milestone with no retry mechanism at all, and wrong now that one exists.

**Revisit when:** N/A, this is the mechanism itself, not a tuning constant.

## 9. Deliberately deferred

- **Retrying while a `ChatScreen` is simultaneously open for the same peer, mid-retry-connection.** §7's registry prevents a double-connect, but the exact UX (does the screen's own connection take priority, does the retry silently no-op) isn't fully specified. Revisit if this turns out to matter in practice; it's a narrow timing window.
- **Any retry policy smarter than exponential backoff** (e.g. adapting to observed reconnection patterns per peer). Not justified without real data, per §1.
- **Multi-hop-aware retry.** Nothing here considers a message that might reach its destination via a relay instead of direct reconnection; that's Milestone 6's problem once store-and-forward exists.
- **Surfacing retry state in the UI** (e.g. "retrying in 30s"). `MessageRow` already shows `SENDING`/`SENT`/`DELIVERED`/`FAILED`; whether a pending retry needs its own visible state is a UI decision, not a resilience-mechanism one, deferred to whenever the mechanism is actually implemented and there's something concrete to design a UI against.

## 10. Next step

Implement the `Message` schema change (`retryCount`, `nextRetryAt`, version bump, `fallbackToDestructiveMigration`), `MessageRepository.scheduleRetry` and a conversation-scoped, backoff-aware retryable-messages query, the small active-connection registry, and the retry coordinator itself wired into `BeaconApplication` alongside `peerDiscovery`. Ready to start on that?
