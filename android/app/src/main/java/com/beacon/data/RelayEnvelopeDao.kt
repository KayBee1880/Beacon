package com.beacon.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface RelayEnvelopeDao {

    // OnConflictStrategy.IGNORE is the mesh-wide dedup (D-032): the same envelope can
    // legitimately arrive from several relay paths, messageId as primary key means only
    // the first copy is ever actually stored.
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(envelope: RelayEnvelope)

    @Query("SELECT messageId FROM relay_envelope")
    suspend fun getAllIds(): List<String>

    @Query("SELECT * FROM relay_envelope WHERE messageId IN (:ids)")
    suspend fun getByIds(ids: List<String>): List<RelayEnvelope>

    @Query("DELETE FROM relay_envelope WHERE createdAt < :cutoff")
    suspend fun deleteOlderThan(cutoff: Long)

    // D-033's storage cap, oldest receivedAt evicted first once exceeded.
    @Query(
        """
        DELETE FROM relay_envelope
        WHERE messageId NOT IN (SELECT messageId FROM relay_envelope ORDER BY receivedAt DESC LIMIT :cap)
        """
    )
    suspend fun evictExcess(cap: Int)
}
