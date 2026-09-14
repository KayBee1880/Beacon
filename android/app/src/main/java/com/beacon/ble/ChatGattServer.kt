package com.beacon.ble

import android.bluetooth.BluetoothDevice
import android.util.Log
import com.beacon.crypto.ChatMessagePlaintext
import com.beacon.crypto.CryptoService
import com.beacon.data.ConversationRepository
import com.beacon.data.Identity
import com.beacon.data.MessageRepository
import com.beacon.data.PeerRepository
import com.beacon.data.RelayEnvelopeRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.SecretKey

/**
 * The actual chat protocol (docs/04): verifying a handshake, decrypting/persisting an
 * incoming message and acking it, and marking an outgoing message delivered on ack.
 * Deliberately knows nothing about GATT plumbing (no BluetoothGattServer/characteristic
 * references). BlePeripheralRole owns that and hands this class raw bytes in, taking a
 * plain callback for bytes going back out, the same "transport stays ignorant of what's
 * above it, domain stays ignorant of the transport" split BleCentralRole already uses.
 */
class ChatGattServer(
    private val identity: Identity,
    private val conversationRepository: ConversationRepository,
    private val messageRepository: MessageRepository,
    private val peerRepository: PeerRepository,
    private val relayEnvelopeRepository: RelayEnvelopeRepository,
    private val resolvedIdentityByDeviceAddress: () -> Map<String, String>,
    private val scope: CoroutineScope,
    private val sendToDevice: (device: BluetoothDevice, bytes: ByteArray) -> Unit
) {
    private val cryptoService = CryptoService()

    private data class Session(
        val sessionKey: SecretKey,
        val peerPublicKey: String,
        val relayGossipSession: RelayGossipSession
    )

    // Keyed by device address: a connection's whole point, per docs/04 §8, is that a
    // session key lives only as long as the connection does; nothing here is persisted.
    private val sessionsByDeviceAddress = ConcurrentHashMap<String, Session>()

    fun onWrite(device: BluetoothDevice, value: ByteArray) {
        when (val frame = ChatFrame.decode(value)) {
            is ChatFrame.Handshake -> handleHandshake(device, frame)
            is ChatFrame.EncryptedMessage -> handleMessage(device, frame)
            is ChatFrame.EncryptedAck -> handleAck(device, frame)
            is ChatFrame.EncryptedRelayInventory -> sessionsByDeviceAddress[device.address]?.relayGossipSession?.handleInventory(frame)
            is ChatFrame.EncryptedRelayRequest -> sessionsByDeviceAddress[device.address]?.relayGossipSession?.handleRequest(frame)
            is ChatFrame.EncryptedRelayPush -> sessionsByDeviceAddress[device.address]?.relayGossipSession?.handlePush(frame)
            null -> Log.w(TAG, "Unrecognized or malformed frame from ${device.address}")
        }
    }

    fun onDeviceDisconnected(device: BluetoothDevice) {
        sessionsByDeviceAddress.remove(device.address)
    }

    // D-018: the only source of truth for "who is this" is ambient discovery's already
    // resolved mapping, never anything the connecting device claims in the frame itself.
    private fun handleHandshake(device: BluetoothDevice, frame: ChatFrame.Handshake) {
        val peerPublicKey = resolvedIdentityByDeviceAddress()[device.address]
        if (peerPublicKey == null) {
            Log.w(TAG, "Handshake from ${device.address}, but no resolved identity yet, failing per docs/04 §6")
            return
        }

        val peerEphemeralPublicKey = try {
            cryptoService.decodeAndVerifyEphemeralPublicKey(peerPublicKey, frame.ephemeralPublicKey, frame.signature)
        } catch (e: Exception) {
            Log.w(TAG, "Malformed handshake key from ${device.address}", e)
            null
        }
        if (peerEphemeralPublicKey == null) {
            Log.w(TAG, "Handshake signature verification failed for ${device.address}")
            return
        }

        val ourEphemeralKeyPair = cryptoService.generateEphemeralKeyPair()
        val sessionKey = cryptoService.deriveSessionKey(ourEphemeralKeyPair.private, peerEphemeralPublicKey)
        val relayGossipSession = RelayGossipSession(
            identity = identity,
            conversationRepository = conversationRepository,
            messageRepository = messageRepository,
            peerRepository = peerRepository,
            relayEnvelopeRepository = relayEnvelopeRepository,
            sessionKey = sessionKey,
            scope = scope,
            sendFrame = { frame -> sendToDevice(device, frame.encode()) }
        )
        sessionsByDeviceAddress[device.address] = Session(sessionKey, peerPublicKey, relayGossipSession)

        val ourSignature = cryptoService.signEphemeralPublicKey(identity.keystoreAlias, ourEphemeralKeyPair.public)
        val response = ChatFrame.Handshake(ourEphemeralKeyPair.public.encoded, ourSignature)
        sendToDevice(device, response.encode())

        // docs/07 §8: symmetric with ChatConnection's own post-READY gossip kickoff.
        relayGossipSession.start()
    }

    private fun handleMessage(device: BluetoothDevice, frame: ChatFrame.EncryptedMessage) {
        val session = sessionsByDeviceAddress[device.address] ?: run {
            Log.w(TAG, "Message from ${device.address} with no completed handshake, dropping")
            return
        }
        val plaintext = decryptOrNull(session.sessionKey, frame.payload) ?: return
        val (messageId, content) = ChatMessagePlaintext.decodeMessage(plaintext)

        scope.launch {
            val conversation = conversationRepository.getOrCreate(session.peerPublicKey)
            messageRepository.receiveIncoming(conversation.id, messageId, content)

            val ackPayload = cryptoService.encrypt(session.sessionKey, ChatMessagePlaintext.encodeAck(messageId))
            sendToDevice(device, ChatFrame.EncryptedAck(ackPayload).encode())
        }
    }

    private fun handleAck(device: BluetoothDevice, frame: ChatFrame.EncryptedAck) {
        val session = sessionsByDeviceAddress[device.address] ?: run {
            Log.w(TAG, "Ack from ${device.address} with no completed handshake, dropping")
            return
        }
        val plaintext = decryptOrNull(session.sessionKey, frame.payload) ?: return
        val messageId = ChatMessagePlaintext.decodeAck(plaintext)

        scope.launch {
            messageRepository.getById(messageId)?.let { messageRepository.markDelivered(it) }
        }
    }

    // Decryption failing (wrong/no session key, tampered payload) is expected, recoverable
    // input handling here, not a bug; see CryptoService.decrypt's walkthrough.
    private fun decryptOrNull(sessionKey: SecretKey, payload: ByteArray): ByteArray? =
        try {
            cryptoService.decrypt(sessionKey, payload)
        } catch (e: Exception) {
            Log.w(TAG, "Decryption failed", e)
            null
        }

    private companion object {
        const val TAG = "ChatGattServer"
    }
}
