package com.beacon.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface PeerDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(peer: Peer)

    @Query("SELECT * FROM peer ORDER BY lastSeenAt DESC")
    fun observeAll(): Flow<List<Peer>>

    @Query("SELECT * FROM peer WHERE id = :peerId")
    suspend fun get(peerId: String): Peer?
}
