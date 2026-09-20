package com.beacon.data

import android.content.Context
import android.util.Base64
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.beacon.crypto.DatabaseKeyStore
import net.sqlcipher.database.SQLiteDatabase
import net.sqlcipher.database.SupportFactory
import java.io.File
import java.security.SecureRandom

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
        private const val DATABASE_NAME = "beacon.db"
        private const val PREFS_NAME = "beacon_database_key"
        private const val PREFS_KEY_WRAPPED_PASSPHRASE = "wrapped_passphrase"
        private const val PASSPHRASE_LENGTH_BYTES = 32

        fun build(context: Context): BeaconDatabase {
            SQLiteDatabase.loadLibs(context)
            val passphrase = getOrCreatePassphrase(context)

            return Room.databaseBuilder(
                context.applicationContext,
                BeaconDatabase::class.java,
                DATABASE_NAME
            )
                // Milestone 13 (D-062): every previous version bump's destructive-migration
                // reasoning (D-023) still applies unchanged, SQLCipher only changes how the
                // file's bytes are stored, not the schema Room migrates.
                .openHelperFactory(SupportFactory(passphrase))
                .fallbackToDestructiveMigration()
                .build()
        }

        // Milestone 13 (D-062/D-063): a wrapped passphrase already present means this
        // install has opened an encrypted beacon.db before, just unwrap and reuse it.
        // Its absence is this function's only signal that this is the very first run of
        // this milestone's code, which doubles as D-063's migration trigger: any beacon.db
        // already on disk at that point necessarily predates encryption entirely, and gets
        // deleted before SQLCipher ever tries to open it with a freshly generated passphrase.
        private fun getOrCreatePassphrase(context: Context): ByteArray {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val wrapped = prefs.getString(PREFS_KEY_WRAPPED_PASSPHRASE, null)
            if (wrapped != null) {
                return DatabaseKeyStore.decrypt(Base64.decode(wrapped, Base64.NO_WRAP))
            }

            deleteExistingDatabaseFiles(context)
            val passphrase = ByteArray(PASSPHRASE_LENGTH_BYTES).also { SecureRandom().nextBytes(it) }
            val wrappedPassphrase = DatabaseKeyStore.encrypt(passphrase)
            prefs.edit()
                .putString(PREFS_KEY_WRAPPED_PASSPHRASE, Base64.encodeToString(wrappedPassphrase, Base64.NO_WRAP))
                .apply()
            return passphrase
        }

        // WAL/SHM/rollback-journal siblings too, not just beacon.db itself: leaving a
        // stale journal file next to a freshly created, differently-keyed database file
        // risks SQLite trying to recover from it against content that no longer matches.
        private fun deleteExistingDatabaseFiles(context: Context) {
            val databaseFile = context.getDatabasePath(DATABASE_NAME)
            databaseFile.delete()
            File(databaseFile.path + "-wal").delete()
            File(databaseFile.path + "-shm").delete()
            File(databaseFile.path + "-journal").delete()
        }
    }
}
