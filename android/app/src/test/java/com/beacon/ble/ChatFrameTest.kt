package com.beacon.ble

import com.beacon.data.RelayEnvelope
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

private fun messageId(): String = UUID.randomUUID().toString()

/**
 * Milestone 11 (D-055): ChatFrame has no Android dependency at all, pure java.nio.ByteBuffer
 * framing, exactly the kind of protocol logic that's already testable in a plain JVM unit
 * test with no emulator, no move to a separate module required.
 */
class ChatFrameTest {

    @Test
    fun `handshake frame round trips through encode and decode`() {
        val frame = ChatFrame.Handshake(ephemeralPublicKey = byteArrayOf(1, 2, 3), signature = byteArrayOf(9, 8, 7, 6))

        val decoded = ChatFrame.decode(frame.encode())

        val handshake = decoded as? ChatFrame.Handshake ?: throw AssertionError("Expected a Handshake frame, got $decoded")
        assertArrayEquals(frame.ephemeralPublicKey, handshake.ephemeralPublicKey)
        assertArrayEquals(frame.signature, handshake.signature)
    }

    @Test
    fun `encrypted message ack relay and attachment frames all round trip`() {
        val payload = byteArrayOf(1, 2, 3, 4, 5)

        assertEquals(payload.toList(), (ChatFrame.decode(ChatFrame.EncryptedMessage(payload).encode()) as ChatFrame.EncryptedMessage).payload.toList())
        assertEquals(payload.toList(), (ChatFrame.decode(ChatFrame.EncryptedAck(payload).encode()) as ChatFrame.EncryptedAck).payload.toList())
        assertEquals(payload.toList(), (ChatFrame.decode(ChatFrame.EncryptedRelayInventory(payload).encode()) as ChatFrame.EncryptedRelayInventory).payload.toList())
        assertEquals(payload.toList(), (ChatFrame.decode(ChatFrame.EncryptedRelayRequest(payload).encode()) as ChatFrame.EncryptedRelayRequest).payload.toList())
        assertEquals(payload.toList(), (ChatFrame.decode(ChatFrame.EncryptedRelayPush(payload).encode()) as ChatFrame.EncryptedRelayPush).payload.toList())
        assertEquals(payload.toList(), (ChatFrame.decode(ChatFrame.EncryptedAttachmentOffer(payload).encode()) as ChatFrame.EncryptedAttachmentOffer).payload.toList())
        assertEquals(payload.toList(), (ChatFrame.decode(ChatFrame.EncryptedAttachmentResponse(payload).encode()) as ChatFrame.EncryptedAttachmentResponse).payload.toList())
    }

    @Test
    fun `decode returns null for empty or unrecognized bytes`() {
        assertNull(ChatFrame.decode(ByteArray(0)))
        assertNull(ChatFrame.decode(byteArrayOf(0x7f)))
    }

    @Test
    fun `decode returns null for a truncated handshake frame`() {
        val frame = ChatFrame.Handshake(ephemeralPublicKey = byteArrayOf(1, 2, 3, 4), signature = byteArrayOf(5, 6))
        val encoded = frame.encode()

        // Cut the frame off mid length-prefix, exactly the "malformed or partial write"
        // case decodeHandshake's own bounds checks exist to reject rather than crash on.
        val truncated = encoded.copyOfRange(0, encoded.size - 3)

        assertNull(ChatFrame.decode(truncated))
    }

    @Test
    fun `relay id list round trips through encode and decode`() {
        val ids = listOf(messageId(), messageId(), messageId())

        val decoded = RelayFramePlaintext.decodeIdList(RelayFramePlaintext.encodeIdList(ids))

        assertEquals(ids, decoded)
    }

    @Test
    fun `empty relay id list round trips to an empty list`() {
        val decoded = RelayFramePlaintext.decodeIdList(RelayFramePlaintext.encodeIdList(emptyList()))

        assertTrue(decoded.isEmpty())
    }

    @Test
    fun `relay envelope round trips through encode and decode, except receivedAt`() {
        val envelope = RelayEnvelope(
            messageId = messageId(),
            originSenderId = "origin-public-key",
            originDisplayName = "Alice",
            finalRecipientId = "recipient-public-key",
            senderEphemeralPublicKey = byteArrayOf(1, 2, 3),
            senderEphemeralPublicKeySignature = byteArrayOf(4, 5, 6),
            ciphertext = byteArrayOf(7, 8, 9, 10),
            hopCount = 2,
            createdAt = 1_700_000_000_000L,
            receivedAt = 1_700_000_000_000L
        )

        val decoded = RelayFramePlaintext.decodeEnvelope(RelayFramePlaintext.encodeEnvelope(envelope))

        requireNotNull(decoded)
        assertEquals(envelope.messageId, decoded.messageId)
        assertEquals(envelope.originSenderId, decoded.originSenderId)
        assertEquals(envelope.originDisplayName, decoded.originDisplayName)
        assertEquals(envelope.finalRecipientId, decoded.finalRecipientId)
        assertArrayEquals(envelope.senderEphemeralPublicKey, decoded.senderEphemeralPublicKey)
        assertArrayEquals(envelope.senderEphemeralPublicKeySignature, decoded.senderEphemeralPublicKeySignature)
        assertArrayEquals(envelope.ciphertext, decoded.ciphertext)
        assertEquals(envelope.hopCount, decoded.hopCount)
        assertEquals(envelope.createdAt, decoded.createdAt)
        // receivedAt is deliberately not part of the wire format (docs/07 §8's own note),
        // decodeEnvelope always fills it in fresh as "now", never the sender's own value.
    }

    @Test
    fun `decodeEnvelope returns null for truncated bytes`() {
        assertNull(RelayFramePlaintext.decodeEnvelope(ByteArray(10)))
    }

    @Test
    fun `attachment offer round trips through encode and decode`() {
        val offer = AttachmentOffer(
            messageId = messageId(),
            fileName = "photo.jpg",
            mimeType = "image/jpeg",
            sizeBytes = 123_456L,
            contentHash = ByteArray(32) { it.toByte() },
            wifiDeviceAddress = "02:00:00:00:00:01"
        )

        val decoded = AttachmentFramePlaintext.decodeOffer(AttachmentFramePlaintext.encodeOffer(offer))

        requireNotNull(decoded)
        assertEquals(offer.messageId, decoded.messageId)
        assertEquals(offer.fileName, decoded.fileName)
        assertEquals(offer.mimeType, decoded.mimeType)
        assertEquals(offer.sizeBytes, decoded.sizeBytes)
        assertArrayEquals(offer.contentHash, decoded.contentHash)
        assertEquals(offer.wifiDeviceAddress, decoded.wifiDeviceAddress)
    }

    @Test
    fun `attachment response round trips with and without a device address`() {
        val accepted = AttachmentResponse(messageId(), accepted = true, wifiDeviceAddress = "02:00:00:00:00:02")
        val rejected = AttachmentResponse(messageId(), accepted = false, wifiDeviceAddress = null)

        val decodedAccepted = AttachmentFramePlaintext.decodeResponse(AttachmentFramePlaintext.encodeResponse(accepted))
        val decodedRejected = AttachmentFramePlaintext.decodeResponse(AttachmentFramePlaintext.encodeResponse(rejected))

        assertEquals(accepted, decodedAccepted)
        assertEquals(rejected, decodedRejected)
    }
}
