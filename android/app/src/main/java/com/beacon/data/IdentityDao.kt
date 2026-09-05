package com.beacon.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface IdentityDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(identity: Identity)

    // 0 matches Identity.SINGLETON_ID, Room @Query strings must be compile-time literals,
    // so the constant can't be interpolated directly here.
    @Query("SELECT * FROM identity WHERE id = 0 LIMIT 1")
    fun observe(): Flow<Identity?>

    @Query("SELECT * FROM identity WHERE id = 0 LIMIT 1")
    suspend fun get(): Identity?
}
