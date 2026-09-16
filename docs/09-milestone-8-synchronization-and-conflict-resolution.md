# Milestone 8: Synchronization & Conflict Resolution

_2026-09-16_

Delivers what docs/00's roadmap names for this milestone: "reconciling state after independent offline edits." Phase 5 of docs/00 §4's incremental plan named this as needing its own evaluation before picking an approach: "timestamps vs. vector clocks vs. CRDTs; to be evaluated on its own before picking one." This document is that evaluation, done now that there's a real domain model and four milestones of actual delivery machinery (retry, relay, gossip) to evaluate it against, rather than guessed at from Milestone 0.

## 1. The actual finding: this milestone's premise doesn't hold, the way Milestone 5's Navigation Compose prediction didn't

**Context:** Phase 5 was written before any of the domain model or delivery machinery existed, on the reasonable assumption that "two devices independently mutating shared conversation state" would eventually need a real reconciliation strategy, the way any distributed system with multiple writers to the same data typically does.

**What the domain model actually looks like, checked now instead of assumed:** every piece of state this project has ever built has exactly one legitimate writer, by construction, not by convention:

- **A `Message`'s `content` is written once, by its author, and never mutated by anyone else.** There is no edit feature, no delete feature. The receiving device's own copy of that message is a separate local row it creates once (`receiveIncoming`/`receiveIncomingAttachmentOffer`) and never mutates its content either.
- **A `Message`'s `status`/`attachmentState`/`retryCount` are written only by the device tracking its own send or receive lifecycle.** The sender's device is the only writer of its own outgoing message's status; the receiver's device is the only writer of its own incoming message's row. Neither ever writes to the other's copy, because there is no shared row at all, each device's `message` table is entirely local.
- **`Conversation` is not a shared entity in the first place.** `Conversation.id` is a fresh random UUID generated independently by each device (`ConversationRepository.getOrCreate`); device A's row representing "my conversation with B" and device B's row representing "my conversation with A" are two unrelated local rows with different ids that are never compared, merged, or synced against each other directly. There is nothing to reconcile because there is no shared object.
- **`Peer.displayName` looks like it could conflict (two devices both "know" a name for the same identity) but doesn't**, because there is exactly one legitimate source for a given identity's display name: that identity's own device, broadcasting its own name over discovery. Every other device's `Peer` row for that identity is a read-only cache of the latest broadcast (`PeerRepository.recordSeen`, unconditionally overwritten on every resolve), not a second writer contending for the same field.

**Why this actually happened, not by luck:** three specific decisions made for other reasons already close off the classic distributed-systems conflict shapes Phase 5 was anticipating: client-generated message ids (Journey 3, originally for duplicate-send detection, D-032/D-037 later reused it for mesh-wide dedup for the same structural reason), sorting by each message's own origin `createdAt` rather than local arrival order (Milestone 1, originally just "the obvious way to order a chat log," turns out to also make delivery order irrelevant to display order), and `ActiveChatConnections` (D-024, originally built to stop a screen and a background retry from double-connecting, also happens to make "two concurrent writers touching the same message row" structurally impossible on a single device).

**Decision: no vector clocks, no CRDTs, no new reconciliation machinery.** There is no real class of conflict in this domain model for that machinery to resolve. Building it anyway would be solving a problem this codebase doesn't have, exactly the premature-abstraction failure mode this project's own engineering discipline (and Milestone 5's D-026) already argues against.

**Alternatives considered:**
- **Adopt vector clocks or a CRDT library defensively, in case a future feature needs them:** rejected, this is the same reasoning D-026 already rejected Navigation Compose on, adopting real infrastructure for a need that doesn't exist yet, rather than when it actually arrives.
- **Do nothing at all, skip this milestone's roadmap slot entirely:** rejected, the roadmap item deserved a real evaluation, not a skip; this document is that evaluation, and it surfaces one real, small decision worth making explicitly (§2) even though the big question resolves to "not needed yet."

**Revisit when:** Beacon ever adds a feature with a genuine second writer to the same piece of state, message editing or deletion (the same content, potentially modified independently on two devices before they reconnect), read receipts that both parties acknowledge, or group conversations (shared membership/metadata multiple participants can change). Any of those would be the actual trigger Phase 5 was written for; none of them exist yet.

## 2. The one real, small decision this evaluation surfaced: `Conversation.lastMessageAt` uses arrival time, deliberately

**Context:** `ConversationDao.touch(conversationId, timestamp)` has always been called with `System.currentTimeMillis()` (arrival time on this device), not the triggering message's own `createdAt` (origin time), since Milestone 1, before delayed/relayed delivery was possible at all. Milestone 6 added a guard against re-touching on a duplicate delivery (D-032's dedup fix) but never revisited *which* timestamp is the right one to use for a genuinely new arrival. Now that a message can legitimately arrive hours or days after it was composed (D-035's relay fallback, docs/07 §7's whole reason no resumability or urgency guarantee exists), this choice actually matters in a way it didn't before relay existed.

**Decision: keep arrival time.** `ConversationsScreen`'s list (Milestone 5, D-028) exists to answer "where did something happen recently," not "what is the chronologically newest message across all conversations." A message that finally arrives today, after being relayed for two days, is genuinely new *information* to this device today, surfacing that conversation back near the top is the correct signal even though the message's own content is chronologically old. The message's own position *within* its conversation thread is still correctly ordered by origin `createdAt` (`MessageDao.observeForConversation`), only the cross-conversation ordering in the list uses arrival time.

**Why this needed to be an explicit decision, not just left as-is:** the original choice was made before there was any path for a message to arrive long after it was sent; leaving it unexamined would mean the current behavior is an accident of Milestone 1's code, not a decision anyone actually made once delayed delivery became real. Checking a load-bearing assumption when the conditions around it change is the same discipline D-026 and docs/07 §8's revision both already modeled.

**Alternatives considered:**
- **Use the message's own `createdAt`**: would make the conversation list reflect "chronologically newest content," but a conversation where the only recent activity was a two-day-late relayed message would silently sort *below* a conversation with no new activity at all but a recent `createdAt` from before either device went offline, actively hiding the thing that just became newly relevant.

**Revisit when:** user feedback (once real usage exists at all) suggests seeing an old message jump a conversation to the top of the list is actually confusing rather than useful; a per-row "this arrived late" indicator would be the more likely fix at that point, not a sort-order change.

## 3. Deliberately not addressed here

- **Message editing or deletion.** Doesn't exist as a feature; would be the actual trigger for revisiting §1, not something to design against speculatively.
- **Read receipts.** Deferred since Milestone 6 (docs/07's own deferred list touches the adjacent "unread indicator" question); would introduce the first genuinely mutual piece of state (both parties care about and could race on "has this been seen") this domain model has had.
- **Group conversations.** Out of scope for the entire roadmap so far; `Conversation` is modeled 1:1 throughout (docs/02's unique-per-peer constraint). Shared group membership/metadata is the other classic trigger for needing real conflict resolution, and doesn't exist here either.
- **A known, minor, pre-existing inefficiency, not a correctness bug:** a message that falls back to relay (D-035) is marked `SENT`, which keeps it eligible for `getRetryableForConversation`'s direct-retry query; if the original peer is later resolved directly, a fresh direct attempt can fire *in addition to* whatever relay path eventually delivers it. This can produce a redundant duplicate delivery, which `receiveIncoming`'s dedup (D-032) already makes harmless on arrival. Not fixed here: suppressing the direct-retry path after a relay fallback would need a way to know a relay attempt is already "in flight" versus already delivered, real added bookkeeping for an outcome that's already correct, just occasionally sends one redundant copy.

## 4. Next step

No new production code is required by this milestone's actual finding; the one concrete artifact is this document plus a short code comment at `ConversationDao.touch`'s call site recording §2's decision, so a future reader doesn't mistake arrival-time touch for an unexamined leftover. Ready to move on to Milestone 9 (visualization & connection quality UX) once that comment is in place?
