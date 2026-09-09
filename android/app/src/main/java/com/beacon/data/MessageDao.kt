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

    @Query("SELECT * FROM message WHERE status != :deliveredStatus AND direction = :outgoing")
    suspend fun getUndelivered(
        deliveredStatus: MessageStatus = MessageStatus.DELIVERED,
        outgoing: MessageDirection = MessageDirection.OUTGOING
    ): List<Message>
}
