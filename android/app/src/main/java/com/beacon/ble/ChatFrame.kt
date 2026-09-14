package com.beacon.ble

import com.beacon.data.RelayEnvelope
import java.nio.ByteBuffer

private const val FRAME_TYPE_HANDSHAKE: Byte = 0x01
private const val FRAME_TYPE_MESSAGE: Byte = 0x02
private const val FRAME_TYPE_ACK: Byte = 0x03
private const val FRAME_TYPE_RELAY_INVENTORY: Byte = 0x04
private const val FRAME_TYPE_RELAY_REQUEST: Byte = 0x05
private const val FRAME_TYPE_RELAY_PUSH: Byte = 0x06

// A message id (Message.id, also RelayEnvelope.messageId) is always a UUID.toString(),
// exactly 36 ASCII characters, so the id lists below need no length prefix per entry.
private const val MESSAGE_ID_LENGTH_BYTES = 36

/**
 * Everything sent over BeaconGattProfile's RX/TX characteristics (docs/04 §5, docs/07 §8)
 * is one of these six frames. The handshake frame travels unencrypted (it's what
 * establishes the key); every other frame carries only CryptoService's already-encrypted
 * bytes, message/ack with the per-hop session key, relay frames the same way (docs/07 §8,
 * relay's own end-to-end encryption is a separate layer inside a push frame's payload).
 */
sealed class ChatFrame {

    data class Handshake(val ephemeralPublicKey: ByteArray, val signature: ByteArray) : ChatFrame()
    data class EncryptedMessage(val payload: ByteArray) : ChatFrame()
    data class EncryptedAck(val payload: ByteArray) : ChatFrame()
    data class EncryptedRelayInventory(val payload: ByteArray) : ChatFrame()
    data class EncryptedRelayRequest(val payload: ByteArray) : ChatFrame()
    data class EncryptedRelayPush(val payload: ByteArray) : ChatFrame()

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
        is EncryptedRelayInventory -> byteArrayOf(FRAME_TYPE_RELAY_INVENTORY) + payload
        is EncryptedRelayRequest -> byteArrayOf(FRAME_TYPE_RELAY_REQUEST) + payload
        is EncryptedRelayPush -> byteArrayOf(FRAME_TYPE_RELAY_PUSH) + payload
    }

    companion object {
        fun decode(bytes: ByteArray): ChatFrame? {
            if (bytes.isEmpty()) return null
            return when (bytes[0]) {
                FRAME_TYPE_HANDSHAKE -> decodeHandshake(bytes)
                FRAME_TYPE_MESSAGE -> EncryptedMessage(bytes.copyOfRange(1, bytes.size))
                FRAME_TYPE_ACK -> EncryptedAck(bytes.copyOfRange(1, bytes.size))
                FRAME_TYPE_RELAY_INVENTORY -> EncryptedRelayInventory(bytes.copyOfRange(1, bytes.size))
                FRAME_TYPE_RELAY_REQUEST -> EncryptedRelayRequest(bytes.copyOfRange(1, bytes.size))
                FRAME_TYPE_RELAY_PUSH -> EncryptedRelayPush(bytes.copyOfRange(1, bytes.size))
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

/**
 * The plaintext layout inside a decrypted relay gossip frame (docs/07 §8): a list of
 * message ids for inventory/request, or one full envelope for a push. Each is encrypted
 * with the per-hop session key exactly like ChatMessagePlaintext above, this is only the
 * shape of what's inside, not a second layer of encryption, an envelope's own ciphertext
 * field (already end-to-end encrypted, see CryptoService.deriveEnvelopeKey) rides inside
 * a push frame's plaintext unchanged.
 */
object RelayFramePlaintext {

    fun encodeIdList(ids: List<String>): ByteArray {
        val buffer = ByteBuffer.allocate(4 + ids.size * MESSAGE_ID_LENGTH_BYTES)
        buffer.putInt(ids.size)
        ids.forEach { buffer.put(it.toByteArray(Charsets.US_ASCII)) }
        return buffer.array()
    }

    fun decodeIdList(plaintext: ByteArray): List<String> {
        val buffer = ByteBuffer.wrap(plaintext)
        val count = buffer.int
        return (0 until count).map {
            val idBytes = ByteArray(MESSAGE_ID_LENGTH_BYTES).also { dest -> buffer.get(dest) }
            String(idBytes, Charsets.US_ASCII)
        }
    }

    fun encodeEnvelope(envelope: RelayEnvelope): ByteArray {
        val originSenderIdBytes = envelope.originSenderId.toByteArray(Charsets.UTF_8)
        val originDisplayNameBytes = envelope.originDisplayName.toByteArray(Charsets.UTF_8)
        val finalRecipientIdBytes = envelope.finalRecipientId.toByteArray(Charsets.UTF_8)
        val buffer = ByteBuffer.allocate(
            MESSAGE_ID_LENGTH_BYTES +
                4 + originSenderIdBytes.size +
                4 + originDisplayNameBytes.size +
                4 + finalRecipientIdBytes.size +
                4 + envelope.senderEphemeralPublicKey.size +
                4 + envelope.senderEphemeralPublicKeySignature.size +
                4 + envelope.ciphertext.size +
                4 + 8
        )
        buffer.put(envelope.messageId.toByteArray(Charsets.US_ASCII))
        buffer.putInt(originSenderIdBytes.size).put(originSenderIdBytes)
        buffer.putInt(originDisplayNameBytes.size).put(originDisplayNameBytes)
        buffer.putInt(finalRecipientIdBytes.size).put(finalRecipientIdBytes)
        buffer.putInt(envelope.senderEphemeralPublicKey.size).put(envelope.senderEphemeralPublicKey)
        buffer.putInt(envelope.senderEphemeralPublicKeySignature.size).put(envelope.senderEphemeralPublicKeySignature)
        buffer.putInt(envelope.ciphertext.size).put(envelope.ciphertext)
        buffer.putInt(envelope.hopCount)
        buffer.putLong(envelope.createdAt)
        return buffer.array()
    }

    // receivedAt isn't part of the wire format, it's a local bookkeeping timestamp; the
    // caller storing this envelope fills it in as System.currentTimeMillis().
    fun decodeEnvelope(plaintext: ByteArray): RelayEnvelope? {
        val buffer = ByteBuffer.wrap(plaintext)
        if (buffer.remaining() < MESSAGE_ID_LENGTH_BYTES) return null
        val messageId = ByteArray(MESSAGE_ID_LENGTH_BYTES).also { buffer.get(it) }

        fun readLengthPrefixed(): ByteArray? {
            if (buffer.remaining() < 4) return null
            val length = buffer.int
            if (length < 0 || buffer.remaining() < length) return null
            return ByteArray(length).also { buffer.get(it) }
        }

        val originSenderId = readLengthPrefixed() ?: return null
        val originDisplayName = readLengthPrefixed() ?: return null
        val finalRecipientId = readLengthPrefixed() ?: return null
        val senderEphemeralPublicKey = readLengthPrefixed() ?: return null
        val senderEphemeralPublicKeySignature = readLengthPrefixed() ?: return null
        val ciphertext = readLengthPrefixed() ?: return null
        if (buffer.remaining() < 12) return null
        val hopCount = buffer.int
        val createdAt = buffer.long

        return RelayEnvelope(
            messageId = String(messageId, Charsets.US_ASCII),
            originSenderId = String(originSenderId, Charsets.UTF_8),
            originDisplayName = String(originDisplayName, Charsets.UTF_8),
            finalRecipientId = String(finalRecipientId, Charsets.UTF_8),
            senderEphemeralPublicKey = senderEphemeralPublicKey,
            senderEphemeralPublicKeySignature = senderEphemeralPublicKeySignature,
            ciphertext = ciphertext,
            hopCount = hopCount,
            createdAt = createdAt,
            receivedAt = System.currentTimeMillis()
        )
    }
}
