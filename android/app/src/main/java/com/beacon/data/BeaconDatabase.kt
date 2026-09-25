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
    // Milestone 14 (D-068): on starting now, not reconstructed for versions 1-4, which
    // were never captured. room.schemaLocation (build.gradle.kts) is where KSP writes
    // the exported JSON.
    exportSchema = true
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

        fun build(context: Context): BeaconDatabase = build(context, DATABASE_NAME, PREFS_NAME)

        // Milestone 15 (D-069): databaseName/prefsName are parameters, not hardcoded to
        // this class's own constants, specifically so BeaconDatabaseTest can exercise
        // this exact passphrase-bootstrap code path against an isolated file and
        // SharedPreferences entry, never the real beacon.db (or its wrapped passphrase)
        // an actual install, or a developer's own manually tested app, already has real
        // data in. The no-argument build(context) above is the only production call site,
        // always using the real names; nothing about it changed.
        internal fun build(context: Context, databaseName: String, prefsName: String): BeaconDatabase {
            SQLiteDatabase.loadLibs(context)
            val passphrase = getOrCreatePassphrase(context, databaseName, prefsName)

            return Room.databaseBuilder(
                context.applicationContext,
                BeaconDatabase::class.java,
                databaseName
            )
                .openHelperFactory(SupportFactory(passphrase))
                // Milestone 14 (D-067): a real Migration for every version bump this
                // project has ever had, replacing D-023's destructive fallback. A missing
                // *upgrade* path now throws loudly instead of silently deleting
                // everything; only a downgrade (a real, ordinary development-time
                // occurrence, never a legitimate production path) still falls back
                // destructively.
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
                .fallbackToDestructiveMigrationOnDowngrade()
                .build()
        }

        // Milestone 13 (D-062/D-063): a wrapped passphrase already present means this
        // install has opened an encrypted database under this name before, just unwrap
        // and reuse it. Its absence is this function's only signal that this is the very
        // first run against this particular database, which doubles as D-063's migration
        // trigger: any file already on disk under this name at that point necessarily
        // predates encryption entirely, and gets deleted before SQLCipher ever tries to
        // open it with a freshly generated passphrase.
        private fun getOrCreatePassphrase(context: Context, databaseName: String, prefsName: String): ByteArray {
            val prefs = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
            val wrapped = prefs.getString(PREFS_KEY_WRAPPED_PASSPHRASE, null)
            if (wrapped != null) {
                return DatabaseKeyStore.decrypt(Base64.decode(wrapped, Base64.NO_WRAP))
            }

            deleteExistingDatabaseFiles(context, databaseName)
            val passphrase = ByteArray(PASSPHRASE_LENGTH_BYTES).also { SecureRandom().nextBytes(it) }
            val wrappedPassphrase = DatabaseKeyStore.encrypt(passphrase)
            prefs.edit()
                .putString(PREFS_KEY_WRAPPED_PASSPHRASE, Base64.encodeToString(wrappedPassphrase, Base64.NO_WRAP))
                .apply()
            return passphrase
        }

        // WAL/SHM/rollback-journal siblings too, not just the main file itself: leaving a
        // stale journal file next to a freshly created, differently-keyed database file
        // risks SQLite trying to recover from it against content that no longer matches.
        private fun deleteExistingDatabaseFiles(context: Context, databaseName: String) {
            val databaseFile = context.getDatabasePath(databaseName)
            databaseFile.delete()
            File(databaseFile.path + "-wal").delete()
            File(databaseFile.path + "-shm").delete()
            File(databaseFile.path + "-journal").delete()
        }
    }
}
