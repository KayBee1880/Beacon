package com.beacon.ble

import java.nio.ByteBuffer

private const val FRAME_TYPE_HANDSHAKE: Byte = 0x01
private const val FRAME_TYPE_MESSAGE: Byte = 0x02
private const val FRAME_TYPE_ACK: Byte = 0x03

// A message's client-generated id (Message.id) is always a UUID.toString(), exactly
// 36 ASCII characters, so the plaintext layout below needs no length prefix for it.
private const val MESSAGE_ID_LENGTH_BYTES = 36

/**
 * Everything sent over BeaconGattProfile's RX/TX characteristics (docs/04 §5) is one of
 * these three frames. The handshake frame travels unencrypted (it's what establishes the
 * key); message and ack frames carry only CryptoService's already-encrypted bytes.
 */
sealed class ChatFrame {

    data class Handshake(val ephemeralPublicKey: ByteArray, val signature: ByteArray) : ChatFrame()
    data class EncryptedMessage(val payload: ByteArray) : ChatFrame()
    data class EncryptedAck(val payload: ByteArray) : ChatFrame()

    fun encode(): ByteArray = when (this) {
        is Handshake -> ByteBuffer.allocate(1 + 4 + ephemeralPublicKey.size + 4 + signature.size)
            .put(FRAME_TYPE_HANDSHAKE)
            .putInt(ephemeralPublicKey.size)
            .put(ephemeralPublicKey)
            .putInt(signature.size)
            .put(signature)
            .array()
        is EncryptedMessage -> byteArrayOf(FRAME_TYPE_MESSAGE) + payload
        is EncryptedAck -> byteArrayOf(FRAME_TYPE_ACK) + payload
    }

    companion object {
        fun decode(bytes: ByteArray): ChatFrame? {
            if (bytes.isEmpty()) return null
            return when (bytes[0]) {
                FRAME_TYPE_HANDSHAKE -> decodeHandshake(bytes)
                FRAME_TYPE_MESSAGE -> EncryptedMessage(bytes.copyOfRange(1, bytes.size))
                FRAME_TYPE_ACK -> EncryptedAck(bytes.copyOfRange(1, bytes.size))
                else -> null
            }
        }

        private fun decodeHandshake(bytes: ByteArray): Handshake? {
            val buffer = ByteBuffer.wrap(bytes, 1, bytes.size - 1)
            if (buffer.remaining() < 4) return null
            val keyLength = buffer.int
            if (keyLength < 0 || buffer.remaining() < keyLength) return null
            val ephemeralPublicKey = ByteArray(keyLength).also { buffer.get(it) }

            if (buffer.remaining() < 4) return null
            val signatureLength = buffer.int
            if (signatureLength < 0 || buffer.remaining() < signatureLength) return null
            val signature = ByteArray(signatureLength).also { buffer.get(it) }

            return Handshake(ephemeralPublicKey, signature)
        }
    }
}

/** The plaintext layout inside a decrypted [ChatFrame.EncryptedMessage]/[ChatFrame.EncryptedAck]. */
object ChatMessagePlaintext {

    fun encodeMessage(messageId: String, content: String): ByteArray =
        messageId.toByteArray(Charsets.US_ASCII) + content.toByteArray(Charsets.UTF_8)

    /** Returns (messageId, content). */
    fun decodeMessage(plaintext: ByteArray): Pair<String, String> {
        val id = String(plaintext.copyOfRange(0, MESSAGE_ID_LENGTH_BYTES), Charsets.US_ASCII)
        val content = String(plaintext.copyOfRange(MESSAGE_ID_LENGTH_BYTES, plaintext.size), Charsets.UTF_8)
        return id to content
    }

    fun encodeAck(messageId: String): ByteArray = messageId.toByteArray(Charsets.US_ASCII)

    fun decodeAck(plaintext: ByteArray): String = String(plaintext, Charsets.US_ASCII)
}
