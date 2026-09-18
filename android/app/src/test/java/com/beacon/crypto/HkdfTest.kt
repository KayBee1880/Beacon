package com.beacon.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

private fun hex(s: String): ByteArray =
    ByteArray(s.length / 2) { i -> ((Character.digit(s[i * 2], 16) shl 4) + Character.digit(s[i * 2 + 1], 16)).toByte() }

/**
 * Milestone 11 (D-055): Hkdf is hand-rolled (D-015), never delegated to a crypto library,
 * so it needs a real correctness check independent of anything else in this codebase.
 * These are RFC 5869's own published test vectors (https://www.rfc-editor.org/rfc/rfc5869
 * Appendix A.1 and A.3), not values this project invented, if this test ever fails, the
 * RFC text itself is the place to cross-check the expected output against, not this file.
 */
class HkdfTest {

    // RFC 5869 Appendix A.1, Test Case 1: basic case, SHA-256, salt and info both present.
    @Test
    fun `deriveKey matches RFC 5869 test case 1`() {
        val ikm = hex("0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b")
        val salt = hex("000102030405060708090a0b0c")
        val info = hex("f0f1f2f3f4f5f6f7f8f9")
        val expected = hex(
            "3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865"
        )

        val actual = Hkdf.deriveKey(inputKeyMaterial = ikm, salt = salt, info = info, outputLengthBytes = 42)

        assertArrayEquals(expected, actual)
    }

    // RFC 5869 Appendix A.3, Test Case 3: no salt, no info, exactly CryptoService's own
    // shape when it calls Hkdf.deriveKey with only inputKeyMaterial/info supplied and
    // salt left at its default null (see CryptoService.deriveSessionKey/deriveEnvelopeKey).
    @Test
    fun `deriveKey matches RFC 5869 test case 3 (no salt, no info)`() {
        val ikm = hex("0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b")
        val expected = hex(
            "8da4e775a563c18f715f802a063c5a31b8a11f5c5ee1879ec3454e5f3c738d2d9d201395faa4b61a96c8"
        )

        val actual = Hkdf.deriveKey(inputKeyMaterial = ikm, salt = null, info = ByteArray(0), outputLengthBytes = 42)

        assertArrayEquals(expected, actual)
    }

    @Test
    fun `deriveKey output length is exactly what was requested`() {
        val ikm = ByteArray(32) { it.toByte() }

        val actual = Hkdf.deriveKey(inputKeyMaterial = ikm, info = "test".toByteArray(), outputLengthBytes = 16)

        assertEquals(16, actual.size)
    }
}
