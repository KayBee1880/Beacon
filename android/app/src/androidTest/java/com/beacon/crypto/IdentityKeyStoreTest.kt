package com.beacon.crypto

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.security.KeyStore
import java.util.UUID

/**
 * Milestone 15 (D-069): the exact gap D-057 named at Milestone 11 and every milestone
 * since has repeated, `IdentityKeyStore` reaches real `AndroidKeyStore`, unavailable in a
 * plain JVM unit test. Runs on a real device or emulator, both of which support software
 *-backed `AndroidKeyStore` keys even without dedicated secure hardware.
 */
@RunWith(AndroidJUnit4::class)
@SmallTest
class IdentityKeyStoreTest {

    // A fresh alias per test, not a shared constant: AndroidKeyStore state persists
    // across test runs on the same device, a shared alias would let one test's leftover
    // key quietly make a later test pass (or fail) for the wrong reason.
    private val alias = "test-identity-key-${UUID.randomUUID()}"

    @After
    fun tearDown() {
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(alias)
    }

    @Test
    fun signThenVerifySucceedsWithTheRealSigningKey() {
        val publicKey = IdentityKeyStore.getOrCreatePublicKey(alias)
        val data = "hello beacon".toByteArray()

        val signature = IdentityKeyStore.sign(alias, data)

        assertTrue(IdentityKeyStore.verify(publicKey, data, signature))
    }

    @Test
    fun getOrCreatePublicKeyReturnsTheSameKeyOnASecondCall() {
        val first = IdentityKeyStore.getOrCreatePublicKey(alias)
        val second = IdentityKeyStore.getOrCreatePublicKey(alias)

        assertTrue(first == second)
    }

    @Test
    fun verifyFailsWhenTheSignedDataIsTamperedWith() {
        val publicKey = IdentityKeyStore.getOrCreatePublicKey(alias)
        val signature = IdentityKeyStore.sign(alias, "original".toByteArray())

        assertFalse(IdentityKeyStore.verify(publicKey, "tampered".toByteArray(), signature))
    }

    @Test
    fun verifyFailsAgainstADifferentIdentitysKey() {
        val otherAlias = "test-identity-key-${UUID.randomUUID()}"
        try {
            val publicKey = IdentityKeyStore.getOrCreatePublicKey(alias)
            val otherPublicKey = IdentityKeyStore.getOrCreatePublicKey(otherAlias)
            assertNotEquals(publicKey, otherPublicKey)
            val data = "hello beacon".toByteArray()

            // Signed by the *other* identity's key, verified against this one's.
            val signature = IdentityKeyStore.sign(otherAlias, data)

            assertFalse(IdentityKeyStore.verify(publicKey, data, signature))
        } finally {
            KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(otherAlias)
        }
    }
}
