package com.beacon.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

// Milestone 11 (D-056): hand-written in-memory fakes, not Room, not a mocking framework,
// shared across this package's test classes so each one isn't rewriting the same handful
// of methods. Each fake implements its real DAO/repository-dependency interface exactly,
// so a MessageRepository (or any other class) built against one behaves identically to
// how it would against the real Room-backed implementation, for the methods each test
// actually exercises.

class FakeMessageDao : MessageDao {
    val stored = mutableListOf<Message>()

    override suspend fun insert(message: Message) {
        check(stored.none { it.id == message.id }) { "Duplicate id inserted via insert(), createOutgoing's own contract" }
        stored.add(message)
    }

    override suspend fun insertIgnoreDuplicate(message: Message): Long {
        if (stored.any { it.id == message.id }) return -1L
        stored.add(message)
        return stored.size.toLong()
    }

    override suspend fun update(message: Message) {
        val index = stored.indexOfFirst { it.id == message.id }
        if (index >= 0) stored[index] = message
    }

    override fun observeForConversation(conversationId: String): Flow<List<Message>> =
        MutableStateFlow(stored.filter { it.conversationId == conversationId }.sortedBy { it.createdAt })

    override suspend fun get(messageId: String): Message? = stored.firstOrNull { it.id == messageId }

    override suspend fun getExistingIds(ids: List<String>): List<String> =
        stored.filter { it.id in ids }.map { it.id }

    override suspend fun getStatusCounts(): List<MessageStatusCount> =
        stored.groupingBy { it.status }.eachCount().map { (status, count) -> MessageStatusCount(status, count) }

    override suspend fun getUndelivered(
        outgoing: MessageDirection,
        sending: MessageStatus,
        sent: MessageStatus
    ): List<Message> = stored.filter { it.direction == outgoing && (it.status == sending || it.status == sent) }

    override suspend fun getRetryableForConversation(
        conversationId: String,
        now: Long,
        outgoing: MessageDirection,
        sending: MessageStatus,
        sent: MessageStatus
    ): List<Message> = stored.filter { message ->
        val nextRetryAt = message.nextRetryAt
        message.conversationId == conversationId &&
            message.direction == outgoing &&
            (message.status == sending || message.status == sent) &&
            (nextRetryAt == null || nextRetryAt <= now)
    }
}

class FakeConversationDao : ConversationDao {
    val stored = mutableListOf<Conversation>()

    override suspend fun insert(conversation: Conversation) {
        if (stored.none { it.peerId == conversation.peerId }) stored.add(conversation)
    }

    override fun observeAll(): Flow<List<Conversation>> =
        MutableStateFlow(stored.sortedByDescending { it.lastMessageAt })

    override suspend fun findByPeer(peerId: String): Conversation? = stored.firstOrNull { it.peerId == peerId }

    override suspend fun get(conversationId: String): Conversation? = stored.firstOrNull { it.id == conversationId }

    override suspend fun touch(conversationId: String, timestamp: Long) {
        val index = stored.indexOfFirst { it.id == conversationId }
        if (index >= 0) stored[index] = stored[index].copy(lastMessageAt = timestamp)
    }
}

class FakePeerDao : PeerDao {
    val stored = mutableListOf<Peer>()

    override suspend fun upsert(peer: Peer) {
        stored.removeAll { it.id == peer.id }
        stored.add(peer)
    }

    override fun observeAll(): Flow<List<Peer>> = MutableStateFlow(stored.sortedByDescending { it.lastSeenAt })

    override suspend fun get(peerId: String): Peer? = stored.firstOrNull { it.id == peerId }
}

class FakeIdentityDao : IdentityDao {
    var stored: Identity? = null

    override suspend fun insert(identity: Identity) {
        stored = identity
    }

    override fun observe(): Flow<Identity?> = MutableStateFlow(stored)

    override suspend fun get(): Identity? = stored
}

class FakeRelayEnvelopeDao : RelayEnvelopeDao {
    val stored = mutableListOf<RelayEnvelope>()

    override suspend fun insert(envelope: RelayEnvelope) {
        if (stored.none { it.messageId == envelope.messageId }) stored.add(envelope)
    }

    override suspend fun getAllIds(): List<String> = stored.map { it.messageId }

    override suspend fun getByIds(ids: List<String>): List<RelayEnvelope> = stored.filter { it.messageId in ids }

    // Milestone 10 added RelayEnvelopeDao.count() (DiagnosticsScreen); this fake was
    // written before that landed here and missed it, caught only by the compiler
    // refusing to treat an incomplete interface implementation as abstract.
    override suspend fun count(): Int = stored.size

    override suspend fun deleteOlderThan(cutoff: Long) {
        stored.removeAll { it.createdAt < cutoff }
    }

    override suspend fun evictExcess(cap: Int) {
        if (stored.size <= cap) return
        val keep = stored.sortedByDescending { it.receivedAt }.take(cap).map { it.messageId }.toSet()
        stored.removeAll { it.messageId !in keep }
    }
}
