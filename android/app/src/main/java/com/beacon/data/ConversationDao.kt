package com.beacon.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ConversationDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(conversation: Conversation)

    @Query("SELECT * FROM conversation ORDER BY lastMessageAt DESC")
    fun observeAll(): Flow<List<Conversation>>

    @Query("SELECT * FROM conversation WHERE peerId = :peerId LIMIT 1")
    suspend fun findByPeer(peerId: String): Conversation?

    // Milestone 6: MessageRepository's relay fallback (D-035) only has a Message's
    // conversationId to start from, and needs that conversation's peerId to know who a
    // relay envelope should actually be addressed to.
    @Query("SELECT * FROM conversation WHERE id = :conversationId")
    suspend fun get(conversationId: String): Conversation?

    @Query("UPDATE conversation SET lastMessageAt = :timestamp WHERE id = :conversationId")
    suspend fun touch(conversationId: String, timestamp: Long)
}
