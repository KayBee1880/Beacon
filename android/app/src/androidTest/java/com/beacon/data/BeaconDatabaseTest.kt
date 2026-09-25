package com.beacon.data

import android.content.Context
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.MediumTest
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import net.sqlcipher.database.SupportFactory
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/**
 * Milestone 15 (D-069): the first time any of Milestone 13's (SQLCipher passphrase
 * bootstrap) or Milestone 14's (real migrations) code has actually executed anywhere,
 * not just built cleanly. Uses BeaconDatabase.build's internal test overload
 * (databaseName/prefsName) so this never touches the real beacon.db an actual install, or
 * a developer's own manually tested app on the same emulator, already has real data in.
 */
@RunWith(AndroidJUnit4::class)
@MediumTest
class BeaconDatabaseTest {

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val databaseName = "test-beacon-${UUID.randomUUID()}.db"
    private val prefsName = "test-beacon-database-key-${UUID.randomUUID()}"

    @After
    fun tearDown() {
        context.getDatabasePath(databaseName).delete()
        context.getSharedPreferences(prefsName, Context.MODE_PRIVATE).edit().clear().apply()
    }

    @Test
    fun identityWrittenBeforeARestartIsStillThereAfterReopeningTheDatabase() = runBlocking {
        val identity = Identity(
            publicKey = "test-public-key",
            keystoreAlias = "test-keystore-alias",
            displayName = "Test Identity",
            createdAt = System.currentTimeMillis(),
            encryptionPublicKey = "test-encryption-public-key",
            encryptionPrivateKey = "test-encryption-private-key",
            encryptionPublicKeySignature = "test-encryption-public-key-signature"
        )

        val firstOpen = BeaconDatabase.build(context, databaseName, prefsName)
        firstOpen.identityDao().insert(identity)
        // Simulates an app restart: close this instance, build an entirely new one
        // against the same on-disk file. If the passphrase weren't durably wrapped and
        // recovered correctly (DatabaseKeyStore's whole job, Milestone 13), this second
        // open would either fail outright or silently see a different, empty database.
        firstOpen.close()

        val secondOpen = BeaconDatabase.build(context, databaseName, prefsName)
        try {
            val reloaded = secondOpen.identityDao().get()

            assertEquals(identity, reloaded)
        } finally {
            secondOpen.close()
        }
    }

    // The actual crux of Milestone 13's whole promise, checked for the first time ever:
    // beacon.db's bytes are supposed to be "unreadable without the database's own
    // passphrase" (docs/14 §2), not just wrapped in extra bookkeeping that happens not to
    // matter. Opens Room directly with an arbitrary wrong passphrase, deliberately
    // bypassing BeaconDatabase.build/getOrCreatePassphrase entirely: an earlier version of
    // this test went through build(context, databaseName, wrongPrefsName) instead, which
    // doesn't test this at all, a prefs name with no wrapped passphrase yet is exactly
    // getOrCreatePassphrase's own "first run" signal, so it deleted the already-encrypted
    // file and created a fresh one before SQLCipher ever got a chance to reject anything,
    // a real bug caught on this suite's first actual run, not a hypothetical.
    @Test
    fun aDifferentPassphraseCannotOpenAnAlreadyEncryptedDatabaseFile() = runBlocking {
        val opened = BeaconDatabase.build(context, databaseName, prefsName)
        opened.identityDao().insert(
            Identity(
                publicKey = "a",
                keystoreAlias = "a",
                displayName = "a",
                createdAt = 0L,
                encryptionPublicKey = "a",
                encryptionPrivateKey = "a",
                encryptionPublicKeySignature = "a"
            )
        )
        opened.close()

        val wrongPassphrase = ByteArray(32) { 0x42 }
        val reopenedWithWrongPassphrase = Room.databaseBuilder(context.applicationContext, BeaconDatabase::class.java, databaseName)
            .openHelperFactory(SupportFactory(wrongPassphrase))
            .build()
        try {
            var threw = false
            try {
                reopenedWithWrongPassphrase.identityDao().get()
            } catch (e: Exception) {
                threw = true
            }
            assertTrue("expected opening with the wrong passphrase to fail", threw)
        } finally {
            reopenedWithWrongPassphrase.close()
        }
    }
}
