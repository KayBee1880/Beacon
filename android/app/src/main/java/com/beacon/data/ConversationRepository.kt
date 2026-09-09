package com.beacon.data

import kotlinx.coroutines.flow.Flow
import java.util.UUID

class ConversationRepository(private val conversationDao: ConversationDao) {

    fun observeAll(): Flow<List<Conversation>> = conversationDao.observeAll()

    // Journey 3: "if this is the first time talking to them, the conversation starts
    // empty"; this is the get-or-create behind that. ConversationDao.insert is
    // onConflict = IGNORE, so a race between two concurrent calls for the same peer
    // can't produce two rows; the re-read after insert is what returns whichever row
    // actually won, not necessarily the one this call constructed.
    suspend fun getOrCreate(peerId: String): Conversation {
        conversationDao.findByPeer(peerId)?.let { return it }
        val now = System.currentTimeMillis()
        val conversation = Conversation(id = UUID.randomUUID().toString(), peerId = peerId, createdAt = now, lastMessageAt = now)
        conversationDao.insert(conversation)
        return conversationDao.findByPeer(peerId) ?: conversation
    }
}
