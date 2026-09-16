package com.beacon.ble

import com.beacon.data.RelayEnvelope
import java.nio.ByteBuffer

private const val FRAME_TYPE_HANDSHAKE: Byte = 0x01
private const val FRAME_TYPE_MESSAGE: Byte = 0x02
private const val FRAME_TYPE_ACK: Byte = 0x03
private const val FRAME_TYPE_RELAY_INVENTORY: Byte = 0x04
private const val FRAME_TYPE_RELAY_REQUEST: Byte = 0x05
private const val FRAME_TYPE_RELAY_PUSH: Byte = 0x06
private const val FRAME_TYPE_ATTACHMENT_OFFER: Byte = 0x07
private const val FRAME_TYPE_ATTACHMENT_RESPONSE: Byte = 0x08

// A message id (Message.id, also RelayEnvelope.messageId) is always a UUID.toString(),
// exactly 36 ASCII characters, so the id lists below need no length prefix per entry.
private const val MESSAGE_ID_LENGTH_BYTES = 36

// SHA-256 digest length (docs/08 §5's whole-file content hash), always exactly 32 bytes,
// so it needs no length prefix either.
private const val CONTENT_HASH_LENGTH_BYTES = 32

/**
 * Everything sent over BeaconGattProfile's RX/TX characteristics (docs/04 §5, docs/07 §8,
 * docs/08 §3) is one of these eight frames. The handshake frame travels unencrypted (it's
 * what establishes the key); every other frame carries only CryptoService's already-
 * encrypted bytes, message/ack/attachment-offer/attachment-response with the per-hop
 * session key, relay frames the same way (docs/07 §8, relay's own end-to-end encryption
 * is a separate layer inside a push frame's payload).
 */
sealed class ChatFrame {

    data class Handshake(val ephemeralPublicKey: ByteArray, val signature: ByteArray) : ChatFrame()
    data class EncryptedMessage(val payload: ByteArray) : ChatFrame()
    data class EncryptedAck(val payload: ByteArray) : ChatFrame()
    data class EncryptedRelayInventory(val payload: ByteArray) : ChatFrame()
    data class EncryptedRelayRequest(val payload: ByteArray) : ChatFrame()
    data class EncryptedRelayPush(val payload: ByteArray) : ChatFrame()
    data class EncryptedAttachmentOffer(val payload: ByteArray) : ChatFrame()
    data class EncryptedAttachmentResponse(val payload: ByteArray) : ChatFrame()

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
        is EncryptedAttachmentOffer -> byteArrayOf(FRAME_TYPE_ATTACHMENT_OFFER) + payload
        is EncryptedAttachmentResponse -> byteArrayOf(FRAME_TYPE_ATTACHMENT_RESPONSE) + payload
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
                FRAME_TYPE_ATTACHMENT_OFFER -> EncryptedAttachmentOffer(bytes.copyOfRange(1, bytes.size))
                FRAME_TYPE_ATTACHMENT_RESPONSE -> EncryptedAttachmentResponse(bytes.copyOfRange(1, bytes.size))
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

/** The plaintext layout inside a decrypted attachment offer/response frame (docs/08 §3). */
@Suppress("ArrayInDataClass")
data class AttachmentOffer(
    val messageId: String,
    val fileName: String,
    val mimeType: String,
    val sizeBytes: Long,
    val contentHash: ByteArray,
    val wifiDeviceAddress: String
)

data class AttachmentResponse(
    val messageId: String,
    val accepted: Boolean,
    val wifiDeviceAddress: String?
)

object AttachmentFramePlaintext {

    fun encodeOffer(offer: AttachmentOffer): ByteArray {
        val fileNameBytes = offer.fileName.toByteArray(Charsets.UTF_8)
        val mimeTypeBytes = offer.mimeType.toByteArray(Charsets.UTF_8)
        val addressBytes = offer.wifiDeviceAddress.toByteArray(Charsets.US_ASCII)
        val buffer = ByteBuffer.allocate(
            MESSAGE_ID_LENGTH_BYTES +
                4 + fileNameBytes.size +
                4 + mimeTypeBytes.size +
                8 +
                CONTENT_HASH_LENGTH_BYTES +
                4 + addressBytes.size
        )
        buffer.put(offer.messageId.toByteArray(Charsets.US_ASCII))
        buffer.putInt(fileNameBytes.size).put(fileNameBytes)
        buffer.putInt(mimeTypeBytes.size).put(mimeTypeBytes)
        buffer.putLong(offer.sizeBytes)
        buffer.put(offer.contentHash)
        buffer.putInt(addressBytes.size).put(addressBytes)
        return buffer.array()
    }

    fun decodeOffer(plaintext: ByteArray): AttachmentOffer? {
        val buffer = ByteBuffer.wrap(plaintext)
        if (buffer.remaining() < MESSAGE_ID_LENGTH_BYTES) return null
        val messageId = ByteArray(MESSAGE_ID_LENGTH_BYTES).also { buffer.get(it) }

        fun readLengthPrefixed(): ByteArray? {
            if (buffer.remaining() < 4) return null
            val length = buffer.int
            if (length < 0 || buffer.remaining() < length) return null
            return ByteArray(length).also { buffer.get(it) }
        }

        val fileName = readLengthPrefixed() ?: return null
        val mimeType = readLengthPrefixed() ?: return null
        if (buffer.remaining() < 8 + CONTENT_HASH_LENGTH_BYTES) return null
        val sizeBytes = buffer.long
        val contentHash = ByteArray(CONTENT_HASH_LENGTH_BYTES).also { buffer.get(it) }
        val address = readLengthPrefixed() ?: return null

        return AttachmentOffer(
            messageId = String(messageId, Charsets.US_ASCII),
            fileName = String(fileName, Charsets.UTF_8),
            mimeType = String(mimeType, Charsets.UTF_8),
            sizeBytes = sizeBytes,
            contentHash = contentHash,
            wifiDeviceAddress = String(address, Charsets.US_ASCII)
        )
    }

    fun encodeResponse(response: AttachmentResponse): ByteArray {
        val addressBytes = response.wifiDeviceAddress?.toByteArray(Charsets.US_ASCII)
        val buffer = ByteBuffer.allocate(
            MESSAGE_ID_LENGTH_BYTES + 1 + 1 + 4 + (addressBytes?.size ?: 0)
        )
        buffer.put(response.messageId.toByteArray(Charsets.US_ASCII))
        buffer.put(if (response.accepted) 1.toByte() else 0.toByte())
        if (addressBytes == null) {
            buffer.put(0.toByte())
        } else {
            buffer.put(1.toByte())
            buffer.putInt(addressBytes.size).put(addressBytes)
        }
        return buffer.array()
    }

    fun decodeResponse(plaintext: ByteArray): AttachmentResponse? {
        val buffer = ByteBuffer.wrap(plaintext)
        if (buffer.remaining() < MESSAGE_ID_LENGTH_BYTES + 2) return null
        val messageId = ByteArray(MESSAGE_ID_LENGTH_BYTES).also { buffer.get(it) }
        val accepted = buffer.get() != 0.toByte()
        val hasAddress = buffer.get() != 0.toByte()
        val address = if (hasAddress) {
            if (buffer.remaining() < 4) return null
            val length = buffer.int
            if (length < 0 || buffer.remaining() < length) return null
            String(ByteArray(length).also { buffer.get(it) }, Charsets.US_ASCII)
        } else {
            null
        }

        return AttachmentResponse(
            messageId = String(messageId, Charsets.US_ASCII),
            accepted = accepted,
            wifiDeviceAddress = address
        )
    }
}
