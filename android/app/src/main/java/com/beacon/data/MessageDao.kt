package com.beacon.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface MessageDao {

    @Insert
    suspend fun insert(message: Message)

    @Update
    suspend fun update(message: Message)

    @Query("SELECT * FROM message WHERE conversationId = :conversationId ORDER BY createdAt ASC")
    fun observeForConversation(conversationId: String): Flow<List<Message>>

    @Query("SELECT * FROM message WHERE id = :messageId")
    suspend fun get(messageId: String): Message?

    // Milestone 4: explicitly SENDING/SENT, not "!= DELIVERED"; the earlier version of
    // this query also matched FAILED, the one status that must never be retried.
    @Query("SELECT * FROM message WHERE direction = :outgoing AND (status = :sending OR status = :sent)")
    suspend fun getUndelivered(
        outgoing: MessageDirection = MessageDirection.OUTGOING,
        sending: MessageStatus = MessageStatus.SENDING,
        sent: MessageStatus = MessageStatus.SENT
    ): List<Message>

    // The retry coordinator's query (docs/05 §5): scoped to one conversation, and gated
    // by nextRetryAt so a peer seen repeatedly doesn't trigger a resend on every sighting.
    @Query(
        """
        SELECT * FROM message
        WHERE conversationId = :conversationId
        AND direction = :outgoing
        AND (status = :sending OR status = :sent)
        AND (nextRetryAt IS NULL OR nextRetryAt <= :now)
        """
    )
    suspend fun getRetryableForConversation(
        conversationId: String,
        now: Long,
        outgoing: MessageDirection = MessageDirection.OUTGOING,
        sending: MessageStatus = MessageStatus.SENDING,
        sent: MessageStatus = MessageStatus.SENT
    ): List<Message>
}
