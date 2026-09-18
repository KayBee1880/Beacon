package com.beacon.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import javax.crypto.AEADBadTagException

/**
 * Milestone 11 (D-057): everything here is reachable without AndroidKeyStore, ECDH key
 * agreement, HKDF derivation, and AES-GCM encrypt/decrypt are all plain javax.crypto/
 * java.security. signEphemeralPublicKey/decodeAndVerifyEphemeralPublicKey are deliberately
 * not tested here, both call into IdentityKeyStore, real-device-only (see docs/12 §3).
 */
class CryptoServiceTest {

    private val cryptoService = CryptoService()

    @Test
    fun `encrypt then decrypt returns the original plaintext`() {
        val ourKeyPair = cryptoService.generateEphemeralKeyPair()
        val theirKeyPair = cryptoService.generateEphemeralKeyPair()
        val key = cryptoService.deriveSessionKey(ourKeyPair.private, theirKeyPair.public)
        val plaintext = "hello beacon".toByteArray()

        val ciphertext = cryptoService.encrypt(key, plaintext)
        val decrypted = cryptoService.decrypt(key, ciphertext)

        assertArrayEquals(plaintext, decrypted)
    }

    @Test
    fun `encrypting the same plaintext twice produces different ciphertext`() {
        val keyPair = cryptoService.generateEphemeralKeyPair()
        val peerKeyPair = cryptoService.generateEphemeralKeyPair()
        val key = cryptoService.deriveSessionKey(keyPair.private, peerKeyPair.public)
        val plaintext = "same message".toByteArray()

        val first = cryptoService.encrypt(key, plaintext)
        val second = cryptoService.encrypt(key, plaintext)

        // D-013/D-015's nonce discipline: a fresh SecureRandom draw every call, so two
        // encryptions of the same plaintext under the same key must never collide.
        assertNotEquals(first.toList(), second.toList())
    }

    @Test
    fun `deriveSessionKey produces the same key from both sides of an ECDH exchange`() {
        val deviceA = cryptoService.generateEphemeralKeyPair()
        val deviceB = cryptoService.generateEphemeralKeyPair()

        val keyFromA = cryptoService.deriveSessionKey(deviceA.private, deviceB.public)
        val keyFromB = cryptoService.deriveSessionKey(deviceB.private, deviceA.public)

        // SecretKeySpec's own equals() compares algorithm + encoded bytes; two
        // independently derived keys are only equal if the underlying ECDH shared
        // secret (and everything derived from it) actually matched on both sides.
        assertEquals(keyFromA, keyFromB)
    }

    @Test
    fun `deriveEnvelopeKey produces the same key from both sides`() {
        val sender = cryptoService.generateEphemeralKeyPair()
        val recipient = cryptoService.generateEphemeralKeyPair()

        val keyFromSender = cryptoService.deriveEnvelopeKey(sender.private, recipient.public)
        val keyFromRecipient = cryptoService.deriveEnvelopeKey(recipient.private, sender.public)

        assertEquals(keyFromSender, keyFromRecipient)
    }

    @Test
    fun `deriveSessionKey and deriveEnvelopeKey never collide for the same key pair`() {
        val deviceA = cryptoService.generateEphemeralKeyPair()
        val deviceB = cryptoService.generateEphemeralKeyPair()

        val sessionKey = cryptoService.deriveSessionKey(deviceA.private, deviceB.public)
        val envelopeKey = cryptoService.deriveEnvelopeKey(deviceA.private, deviceB.public)

        // D-031's domain separation (distinct HKDF info strings): the same ECDH inputs
        // must still produce two unrelated keys depending on which derivation is used.
        assertNotEquals(sessionKey, envelopeKey)
    }

    @Test
    fun `decrypt with the wrong key fails loudly instead of returning garbage`() {
        val realKeyPair = cryptoService.generateEphemeralKeyPair()
        val peerKeyPair = cryptoService.generateEphemeralKeyPair()
        val wrongKeyPair = cryptoService.generateEphemeralKeyPair()

        val key = cryptoService.deriveSessionKey(realKeyPair.private, peerKeyPair.public)
        val wrongKey = cryptoService.deriveSessionKey(wrongKeyPair.private, peerKeyPair.public)
        val ciphertext = cryptoService.encrypt(key, "secret".toByteArray())

        // The exact failure mode CryptoService.decrypt's own walkthrough already
        // documents: AES-GCM's authentication tag check throws rather than decrypting
        // to silent nonsense under the wrong key.
        assertThrows(AEADBadTagException::class.java) {
            cryptoService.decrypt(wrongKey, ciphertext)
        }
    }
}
