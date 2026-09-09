package com.beacon.crypto

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

private const val HMAC_ALGORITHM = "HmacSHA256"
private const val HASH_LENGTH_BYTES = 32

// RFC 5869 HKDF-SHA256, hand-rolled rather than pulling in a crypto library for one
// function (D-015). Extract, then expand: standard two-step key derivation so a raw
// ECDH shared secret (not uniformly random) becomes a usable AES key.
object Hkdf {

    fun deriveKey(inputKeyMaterial: ByteArray, salt: ByteArray? = null, info: ByteArray = ByteArray(0), outputLengthBytes: Int): ByteArray {
        val pseudoRandomKey = extract(salt ?: ByteArray(HASH_LENGTH_BYTES), inputKeyMaterial)
        return expand(pseudoRandomKey, info, outputLengthBytes)
    }

    private fun extract(salt: ByteArray, inputKeyMaterial: ByteArray): ByteArray =
        hmac(salt, inputKeyMaterial)

    private fun expand(pseudoRandomKey: ByteArray, info: ByteArray, outputLengthBytes: Int): ByteArray {
        val blocksNeeded = (outputLengthBytes + HASH_LENGTH_BYTES - 1) / HASH_LENGTH_BYTES
        var previousBlock = ByteArray(0)
        val output = ByteArray(blocksNeeded * HASH_LENGTH_BYTES)

        for (blockIndex in 1..blocksNeeded) {
            val mac = Mac.getInstance(HMAC_ALGORITHM).apply {
                init(SecretKeySpec(pseudoRandomKey, HMAC_ALGORITHM))
                update(previousBlock)
                update(info)
                update(blockIndex.toByte())
            }
            previousBlock = mac.doFinal()
            previousBlock.copyInto(output, (blockIndex - 1) * HASH_LENGTH_BYTES)
        }

        return output.copyOf(outputLengthBytes)
    }

    private fun hmac(key: ByteArray, data: ByteArray): ByteArray =
        Mac.getInstance(HMAC_ALGORITHM).apply {
            init(SecretKeySpec(key, HMAC_ALGORITHM))
        }.doFinal(data)
}
