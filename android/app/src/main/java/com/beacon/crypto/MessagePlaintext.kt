package com.beacon.crypto

// A message's client-generated id (Message.id) is always a UUID.toString(), exactly
// 36 ASCII characters, so the layout below needs no length prefix for it.
private const val MESSAGE_ID_LENGTH_BYTES = 36

/**
 * The plaintext layout inside a decrypted chat message/ack, or a decrypted relay
 * envelope (docs/07 §5): the same id-plus-content shape either way, only the key and the
 * transport differ. Lives in `crypto`, not `ble`, specifically so `MessageRepository`
 * (the `data` layer, building a relay envelope in D-035's retry fallback) can use it
 * without depending on `ble`, which itself depends on `data`.
 */
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
