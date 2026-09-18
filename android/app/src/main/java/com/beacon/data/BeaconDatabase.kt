package com.beacon.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

@Database(
    entities = [Identity::class, Peer::class, Conversation::class, Message::class, RelayEnvelope::class],
    version = 5,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class BeaconDatabase : RoomDatabase() {

    abstract fun identityDao(): IdentityDao
    abstract fun peerDao(): PeerDao
    abstract fun conversationDao(): ConversationDao
    abstract fun messageDao(): MessageDao
    abstract fun relayEnvelopeDao(): RelayEnvelopeDao

    companion object {
        fun build(context: Context): BeaconDatabase =
            Room.databaseBuilder(
                context.applicationContext,
                BeaconDatabase::class.java,
                "beacon.db"
            )
                // D-023: no real release has ever shipped, so there's no real data to
                // preserve across this version bump yet. Must become a real Migration
                // before that stops being true.
                .fallbackToDestructiveMigration()
                .build()
    }
}
