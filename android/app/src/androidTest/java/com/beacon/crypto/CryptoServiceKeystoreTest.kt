package com.beacon.crypto

import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import java.security.KeyStore
import java.util.UUID

/**
 * Milestone 15 (D-069): `CryptoServiceTest.kt`'s own doc comment has named these two
 * functions excluded since Milestone 11, "both call into IdentityKeyStore, real-device-
 * only". This is that real device.
 */
@RunWith(AndroidJUnit4::class)
@SmallTest
class CryptoServiceKeystoreTest {

    private val alias = "test-crypto-keystore-key-${UUID.randomUUID()}"
    private val cryptoService = CryptoService()

    @After
    fun tearDown() {
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(alias)
    }

    @Test
    fun signEphemeralPublicKeyThenDecodeAndVerifyEphemeralPublicKeyRoundTrips() {
        val identityPublicKey = IdentityKeyStore.getOrCreatePublicKey(alias)
        val ephemeralKeyPair = cryptoService.generateEphemeralKeyPair()

        val signature = cryptoService.signEphemeralPublicKey(alias, ephemeralKeyPair.public)
        val decoded = cryptoService.decodeAndVerifyEphemeralPublicKey(
            identityPublicKey,
            ephemeralKeyPair.public.encoded,
            signature
        )

        requireNotNull(decoded)
        assertArrayEquals(ephemeralKeyPair.public.encoded, decoded.encoded)
    }

    @Test
    fun decodeAndVerifyEphemeralPublicKeyReturnsNullForATamperedKey() {
        val identityPublicKey = IdentityKeyStore.getOrCreatePublicKey(alias)
        val ephemeralKeyPair = cryptoService.generateEphemeralKeyPair()
        val otherKeyPair = cryptoService.generateEphemeralKeyPair()
        val signature = cryptoService.signEphemeralPublicKey(alias, ephemeralKeyPair.public)

        // The signature really was produced over ephemeralKeyPair's key; presenting a
        // different key's bytes alongside that same signature must fail verification,
        // not silently decode into a PublicKey nobody actually authenticated.
        val decoded = cryptoService.decodeAndVerifyEphemeralPublicKey(
            identityPublicKey,
            otherKeyPair.public.encoded,
            signature
        )

        assertNull(decoded)
    }

    @Test
    fun decodeAndVerifyEncryptionPublicKeyRoundTrips() {
        val identityPublicKey = IdentityKeyStore.getOrCreatePublicKey(alias)
        val encryptionKeyPair = cryptoService.generateEphemeralKeyPair()
        val signature = IdentityKeyStore.sign(alias, encryptionKeyPair.public.encoded)
        val encryptionPublicKeyBase64 = cryptoService.encodePublicKey(encryptionKeyPair.public)
        val signatureBase64 = Base64.encodeToString(signature, Base64.NO_WRAP)

        val decoded = cryptoService.decodeAndVerifyEncryptionPublicKey(
            identityPublicKey,
            encryptionPublicKeyBase64,
            signatureBase64
        )

        requireNotNull(decoded)
        assertArrayEquals(encryptionKeyPair.public.encoded, decoded.encoded)
    }

    @Test
    fun decodeAndVerifyEncryptionPublicKeyReturnsNullForASignatureFromADifferentIdentity() {
        val identityPublicKey = IdentityKeyStore.getOrCreatePublicKey(alias)
        val otherAlias = "test-crypto-keystore-key-${UUID.randomUUID()}"
        try {
            IdentityKeyStore.getOrCreatePublicKey(otherAlias)
            val encryptionKeyPair = cryptoService.generateEphemeralKeyPair()
            // Signed by a different identity than the one verification is checked against.
            val signature = IdentityKeyStore.sign(otherAlias, encryptionKeyPair.public.encoded)
            val encryptionPublicKeyBase64 = cryptoService.encodePublicKey(encryptionKeyPair.public)
            val signatureBase64 = Base64.encodeToString(signature, Base64.NO_WRAP)

            val decoded = cryptoService.decodeAndVerifyEncryptionPublicKey(
                identityPublicKey,
                encryptionPublicKeyBase64,
                signatureBase64
            )

            assertNull(decoded)
        } finally {
            KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(otherAlias)
        }
    }
}
