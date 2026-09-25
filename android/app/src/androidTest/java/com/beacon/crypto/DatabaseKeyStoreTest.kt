package com.beacon.crypto

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Milestone 15 (D-069): `DatabaseKeyStore` (Milestone 13) reaches real `AndroidKeyStore`,
 * excluded from the JVM suite for the same D-057 reason as `IdentityKeyStore`. Uses the
 * real, shared `beacon-database-key` alias, `DatabaseKeyStore` exposes no way to choose a
 * different one, the same alias `BeaconDatabase.build` itself would use; nothing here
 * deletes it afterward, a real key existing under that alias on a test device is exactly
 * the production condition this object is meant to handle.
 */
@RunWith(AndroidJUnit4::class)
@SmallTest
class DatabaseKeyStoreTest {

    @Test
    fun encryptThenDecryptReturnsTheOriginalPlaintext() {
        val plaintext = "a real sqlcipher passphrase, 32 bytes long!!".toByteArray()

        val ciphertext = DatabaseKeyStore.encrypt(plaintext)
        val decrypted = DatabaseKeyStore.decrypt(ciphertext)

        assertArrayEquals(plaintext, decrypted)
    }

    @Test
    fun encryptingTheSamePlaintextTwiceProducesDifferentCiphertext() {
        val plaintext = "same passphrase".toByteArray()

        val first = DatabaseKeyStore.encrypt(plaintext)
        val second = DatabaseKeyStore.encrypt(plaintext)

        // AndroidKeyStore's own Cipher generates a fresh nonce internally on every
        // ENCRYPT_MODE init (DatabaseKeyStore.kt's own walkthrough); two encryptions of
        // the same plaintext under the same key must never collide, the same discipline
        // CryptoServiceTest already checks for the software-key path.
        assertNotEquals(first.toList(), second.toList())
    }
}
