package com.beacon.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface MessageDao {

    @Insert
    suspend fun insert(message: Message)

    // Milestone 6: receiveIncoming's own insert, distinct from the one above. A relayed
    // message can now legitimately arrive twice (direct plus relay, or two relay paths),
    // where createOutgoing's fresh UUID never collides and a collision there would be a
    // real bug worth ABORT catching; here a duplicate id is expected mesh behavior, not one.
    // Returns the new rowId, or -1 if ignored as a duplicate (Room's documented convention
    // for a suspend @Insert with a Long return type and an IGNORE conflict strategy).
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnoreDuplicate(message: Message): Long

    @Update
    suspend fun update(message: Message)

    @Query("SELECT * FROM message WHERE conversationId = :conversationId ORDER BY createdAt ASC")
    fun observeForConversation(conversationId: String): Flow<List<Message>>

    @Query("SELECT * FROM message WHERE id = :messageId")
    suspend fun get(messageId: String): Message?

    // Milestone 6: which of these ids (from a peer's relay gossip inventory) this device
    // has already fully received or sent as a real Message, so gossip doesn't re-request
    // something already delivered through a different mesh path.
    @Query("SELECT id FROM message WHERE id IN (:ids)")
    suspend fun getExistingIds(ids: List<String>): List<String>

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
