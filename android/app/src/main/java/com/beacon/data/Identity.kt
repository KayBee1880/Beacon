package com.beacon.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "identity")
data class Identity(
    @PrimaryKey val id: Int = SINGLETON_ID,
    val publicKey: String,
    val keystoreAlias: String,
    val displayName: String,
    val createdAt: Long
) {
    companion object {
        const val SINGLETON_ID = 0
    }
}
