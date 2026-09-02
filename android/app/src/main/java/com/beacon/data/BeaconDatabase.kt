package com.beacon.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

@Database(
    entities = [Identity::class, Peer::class, Conversation::class, Message::class],
    version = 1,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class BeaconDatabase : RoomDatabase() {

    abstract fun identityDao(): IdentityDao
    abstract fun peerDao(): PeerDao
    abstract fun conversationDao(): ConversationDao
    abstract fun messageDao(): MessageDao

    companion object {
        fun build(context: Context): BeaconDatabase =
            Room.databaseBuilder(
                context.applicationContext,
                BeaconDatabase::class.java,
                "beacon.db"
            ).build()
    }
}
