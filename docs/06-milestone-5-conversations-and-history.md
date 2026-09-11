# Milestone 5: Conversations & History

_2026-09-11_

Delivers what docs/00's roadmap names for this milestone: multi-message conversations, persisted, viewable. Most of the persistence side of this already exists from Milestone 1; what's actually missing is a way to *see* it.

## 1. What already exists vs. what this milestone adds

Already in place, and already doing real work: `Conversation`/`Message` persistence, `ConversationRepository.getOrCreate`, and `ChatScreen`'s reactive message list (`MessageRepository.observeForConversation`). `ConversationRepository.observeAll()`/`ConversationDao.observeAll()` also already exist, ordered by `lastMessageAt DESC`, exactly what a conversation list needs, but nothing in the UI has ever called either. The only way to reach `ChatScreen` today is by tapping a peer who happens to be currently nearby (`PeerDiscoveryScreen`), which means a conversation with someone who has stepped out of range is completely unreachable, even though every message in it is sitting in the database the whole time.

Not yet built: any screen that lists conversations at all, and a way to navigate to one independent of whether that peer is currently resolved.

## 2. The problem this surfaces: is hand-rolled navigation still enough?

`MainActivity.kt`'s own walkthrough already named this milestone as the likely point to revisit: "Milestone 5 (conversations & history, which needs its own 'list of conversations → tap into one' flow) is a more likely point to actually revisit [Navigation Compose]." That prediction is worth actually checking now that the real shape of the requirement exists, rather than assumed.

**What the requirement actually needs:** two top-level destinations (Nearby, Conversations) that a user switches between, plus one detail screen (`ChatScreen`) reachable from either. Critically, `ChatScreen` is never reached *through* one top-level destination and then further nested, it's a flat overlay on top of whichever top-level destination is currently showing. "Back" from a chat always means "return to whichever top-level tab was already selected," never "pop through a multi-level stack."

**Decision: keep hand-rolling, do not adopt Navigation Compose.** The predicted trigger arrived, but the actual back-stack depth this milestone needs is still exactly what `BeaconApp`'s existing `selectedPeer: Peer?` pattern already handles: a `TopLevelTab` (`NEARBY`/`CONVERSATIONS`) tracked independently of `selectedPeer`, with `selectedPeer != null` meaning "show chat regardless of which tab is underneath." Because the tab selection is never disturbed by opening a chat, "back" is simply `selectedPeer = null`, no separate "which tab did I come from" bookkeeping needed at all.

**Why this is worth writing down even though it changes nothing:** the earlier walkthrough note was a prediction, not a decision, and predictions should get checked against reality when the moment they were about arrives, not silently forgotten if they turn out not to hold. Adopting a navigation library here would mean a new Gradle dependency and real migration work to solve a back-stack problem that, on inspection, isn't actually deep enough to need one.

**Alternatives considered:**
- **Adopt Navigation Compose now, as predicted**: would work, but for a two-tab-plus-one-overlay structure this is meaningfully more machinery (a `NavHost`, route definitions, a new dependency) than three `remember`ed variables.

**Revisit when:** `ChatScreen` (or any future screen) ever needs to open *another* screen on top of itself (real nested navigation, not a flat overlay). That's the point hand-rolled state actually starts costing more than a real navigation solution.

## 3. Decision: a bottom navigation bar between Nearby and Conversations, using Material 3's `NavigationBar` directly

**Decision:** `BeaconApp` gains a `TopLevelTab` enum and a `NavigationBar`/`NavigationBarItem` pair (both already part of the `material3` dependency already in use, no new library) switching between `PeerDiscoveryScreen` and the new `ConversationsScreen`, shown whenever `selectedPeer` is `null`.

**Why:** `androidx.compose.material3.NavigationBar` is the standard, already-available Material 3 component for exactly this pattern (a small, fixed set of top-level destinations); no reason to reach for anything else. Icons are rendered as plain `Text` labels rather than `Icon` glyphs, since the Material Icons set beyond a small default core requires its own separate dependency (`material-icons-extended`); two short text labels ("Nearby", "Conversations") are perfectly legible and avoid that dependency question entirely for a two-item bar.

**Alternatives considered:**
- **Material icons for the tab bar**: would look more like a typical app, but pulls in a dependency purely for two icons; deferred as a cosmetic improvement, not a functional need.

**Revisit when:** a third top-level tab is ever needed and two text labels start feeling cramped, or if icons become worth the dependency for other reasons first.

## 4. Decision: `ConversationsScreen` shows peer name and last-activity time, not a message preview

**Decision:** Each row shows the peer's display name and a formatted `lastMessageAt` timestamp, built by combining `ConversationRepository.observeAll()` with `PeerRepository.observeAll()` (the same "combine two flows, map to a UI state list" shape `PeerDiscoveryScreen` already uses for the nearby list). No snippet of the actual last message is shown.

**Why:** `Conversation` has no column that stores the last message's text, only `lastMessageAt`, a timestamp. Showing a real preview would mean either adding a denormalized `lastMessagePreview` column (another schema migration, another destructive one per D-023 since there's still no real release) or a separate per-conversation query to fetch the newest `Message` row, both real additions this milestone doesn't strictly need to satisfy "conversations, persisted, viewable." A conversation list with names and timestamps is a complete, honest answer to the roadmap's requirement; a preview snippet is a real, separable enhancement.

**Alternatives considered:**
- **Add a `lastMessagePreview` column to `Conversation`, updated alongside `touch()`**: the more complete UX, deferred because it's an additive enhancement, not required to make history "viewable" at all, and this milestone already has one schema-adjacent question (whether to touch the database at all) with a clean "no" answer worth keeping.
- **Query the newest message per conversation at render time**: would work without a schema change, but is real added query complexity for a cosmetic improvement; same reasoning as above for deferring it.

**Revisit when:** the plain name-and-timestamp list feels genuinely insufficient in practice, not preemptively.

## 5. Deliberately deferred

- **Message preview text in the conversation list**, per §4.
- **Unread-message indicators.** Nothing in the domain model tracks "has the user seen this message yet"; that's a real, separate feature with its own persistence question, out of scope for making history viewable at all.
- **Deleting or archiving conversations.** No user-facing way to remove a conversation exists yet; not required by the roadmap's "persisted, viewable" framing for this milestone.
- **Search across conversations/messages.** A real feature, not needed for the base "can you see your conversation history" requirement this milestone exists to satisfy.

## 6. Next step

Implement `ConversationsScreen` (combine `ConversationRepository`/`PeerRepository`, render a `LazyColumn` of name + timestamp rows), add `TopLevelTab` and the `NavigationBar` to `BeaconApp`, and wire tapping a conversation row to the same `selectedPeer` state `PeerDiscoveryScreen` already uses to open `ChatScreen`. Ready to start on that?
