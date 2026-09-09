package com.beacon.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.util.Log
import com.beacon.crypto.CryptoService
import com.beacon.data.Identity
import com.beacon.data.Message
import com.beacon.data.MessageRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.security.KeyPair
import javax.crypto.SecretKey

private const val REQUESTED_MTU = 517

enum class ChatConnectionState { CONNECTING, HANDSHAKING, READY, FAILED, DISCONNECTED }

private sealed class PendingWrite {
    data class Handshake(val bytes: ByteArray) : PendingWrite()
    data class OutgoingMessage(val message: Message, val bytes: ByteArray) : PendingWrite()
    data class Ack(val bytes: ByteArray) : PendingWrite()
}

/**
 * The central side of one open chat (docs/04 §5): connects to a peer's peripheral,
 * negotiates a larger MTU before anything else (D-017), performs the signed handshake
 * (D-013/D-014), then sends/receives encrypted messages and acks over that one
 * connection until it's closed. One instance per open chat screen, a "session" in
 * docs/04 §8's sense.
 */
@SuppressLint("MissingPermission")
class ChatConnection(
    private val context: Context,
    private val identity: Identity,
    private val peerPublicKey: String,
    private val conversationId: String,
    private val messageRepository: MessageRepository,
    private val scope: CoroutineScope
) {
    private val cryptoService = CryptoService()

    private val _state = MutableStateFlow(ChatConnectionState.CONNECTING)
    val state: StateFlow<ChatConnectionState> = _state

    private var gatt: BluetoothGatt? = null
    private var rxCharacteristic: BluetoothGattCharacteristic? = null
    private var ourEphemeralKeyPair: KeyPair? = null
    private var sessionKey: SecretKey? = null

    // A GATT connection only allows one write in flight at a time; RX carries three
    // different frame kinds (handshake, messages, acks), so they're queued and sent one
    // at a time rather than risking overlapping writeCharacteristic calls.
    private val writeQueue = ArrayDeque<PendingWrite>()
    private var writeInFlight: PendingWrite? = null

    fun connect(device: BluetoothDevice) {
        _state.value = ChatConnectionState.CONNECTING
        gatt = device.connectGatt(context, false, gattCallback)
    }

    fun disconnect() {
        gatt?.disconnect()
        gatt?.close()
        gatt = null
        rxCharacteristic = null
        sessionKey = null
        ourEphemeralKeyPair = null
        writeQueue.clear()
        writeInFlight = null
    }

    suspend fun sendMessage(message: Message) {
        val key = sessionKey
        if (key == null) {
            // Deliberately no queue-until-ready here: a send attempted before the
            // handshake completes fails outright, per docs/04 §8/§9; retrying sends
            // is Milestone 4's job, not this one's.
            messageRepository.markFailed(message)
            return
        }
        val payload = cryptoService.encrypt(key, ChatMessagePlaintext.encodeMessage(message.id, message.content))
        enqueueWrite(PendingWrite.OutgoingMessage(message, ChatFrame.EncryptedMessage(payload).encode()))
    }

    private fun enqueueWrite(write: PendingWrite) {
        writeQueue.addLast(write)
        if (writeInFlight == null) processNextWrite()
    }

    @Suppress("DEPRECATION")
    private fun processNextWrite() {
        val next = writeQueue.removeFirstOrNull() ?: return
        val characteristic = rxCharacteristic
        if (characteristic == null) {
            failPendingWrite(next)
            processNextWrite()
            return
        }
        writeInFlight = next
        val bytes = when (next) {
            is PendingWrite.Handshake -> next.bytes
            is PendingWrite.OutgoingMessage -> next.bytes
            is PendingWrite.Ack -> next.bytes
        }
        characteristic.setValue(bytes)
        gatt?.writeCharacteristic(characteristic)
    }

    private fun failPendingWrite(write: PendingWrite) {
        if (write is PendingWrite.OutgoingMessage) {
            scope.launch { messageRepository.markFailed(write.message) }
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> gatt.requestMtu(REQUESTED_MTU)
                BluetoothProfile.STATE_DISCONNECTED -> {
                    sessionKey = null
                    _state.value = ChatConnectionState.DISCONNECTED
                    gatt.close()
                }
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            // D-017's required failure path: a device that won't negotiate a usable MTU
            // fails cleanly here rather than attempting a handshake write that would
            // silently be truncated.
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.w(TAG, "MTU negotiation failed, status $status")
                _state.value = ChatConnectionState.FAILED
                gatt.disconnect()
                return
            }
            gatt.discoverServices()
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                _state.value = ChatConnectionState.FAILED
                gatt.disconnect()
                return
            }
            val service = gatt.getService(BeaconGattProfile.MESSAGING_SERVICE_UUID)
            val rx = service?.getCharacteristic(BeaconGattProfile.RX_CHARACTERISTIC_UUID)
            val tx = service?.getCharacteristic(BeaconGattProfile.TX_CHARACTERISTIC_UUID)
            val cccd = tx?.getDescriptor(BeaconGattProfile.CLIENT_CHARACTERISTIC_CONFIG_UUID)
            if (rx == null || tx == null || cccd == null) {
                Log.w(TAG, "Peer is missing the messaging service or its notification descriptor")
                _state.value = ChatConnectionState.FAILED
                gatt.disconnect()
                return
            }
            rxCharacteristic = rx

            // Subscribing before sending anything is required, not optional: a notify
            // to a device that hasn't enabled this yet silently goes nowhere (see
            // BlePeripheralRole's sendToDevice), so the handshake can't be sent until
            // this completes (onDescriptorWrite, below).
            gatt.setCharacteristicNotification(tx, true)
            @Suppress("DEPRECATION")
            cccd.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
            @Suppress("DEPRECATION")
            gatt.writeDescriptor(cccd)
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (descriptor.uuid != BeaconGattProfile.CLIENT_CHARACTERISTIC_CONFIG_UUID) return
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.w(TAG, "Failed to subscribe to TX notifications, status $status")
                _state.value = ChatConnectionState.FAILED
                gatt.disconnect()
                return
            }
            sendHandshake()
        }

        // This 3-arg overload alone covers minSdk 26-34, same reasoning as
        // BleCentralRole's onCharacteristicRead.
        @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            val completed = writeInFlight
            writeInFlight = null
            if (completed is PendingWrite.OutgoingMessage) {
                scope.launch {
                    if (status == BluetoothGatt.GATT_SUCCESS) {
                        messageRepository.markSent(completed.message)
                    } else {
                        messageRepository.markFailed(completed.message)
                    }
                }
            }
            processNextWrite()
        }

        @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (characteristic.uuid != BeaconGattProfile.TX_CHARACTERISTIC_UUID) return
            val value = characteristic.value ?: return
            when (val frame = ChatFrame.decode(value)) {
                is ChatFrame.Handshake -> handleHandshakeResponse(frame)
                is ChatFrame.EncryptedMessage -> handleMessage(frame)
                is ChatFrame.EncryptedAck -> handleAck(frame)
                null -> Log.w(TAG, "Unrecognized or malformed frame from peer")
            }
        }
    }

    private fun sendHandshake() {
        _state.value = ChatConnectionState.HANDSHAKING
        val keyPair = cryptoService.generateEphemeralKeyPair()
        ourEphemeralKeyPair = keyPair
        val signature = cryptoService.signEphemeralPublicKey(identity.keystoreAlias, keyPair.public)
        enqueueWrite(PendingWrite.Handshake(ChatFrame.Handshake(keyPair.public.encoded, signature).encode()))
    }

    private fun handleHandshakeResponse(frame: ChatFrame.Handshake) {
        val ourKeyPair = ourEphemeralKeyPair ?: return
        val peerEphemeralPublicKey = try {
            cryptoService.decodeAndVerifyEphemeralPublicKey(peerPublicKey, frame.ephemeralPublicKey, frame.signature)
        } catch (e: Exception) {
            Log.w(TAG, "Malformed handshake response", e)
            null
        }
        if (peerEphemeralPublicKey == null) {
            Log.w(TAG, "Handshake signature verification failed")
            _state.value = ChatConnectionState.FAILED
            gatt?.disconnect()
            return
        }
        sessionKey = cryptoService.deriveSessionKey(ourKeyPair.private, peerEphemeralPublicKey)
        _state.value = ChatConnectionState.READY
    }

    private fun handleMessage(frame: ChatFrame.EncryptedMessage) {
        val key = sessionKey ?: return
        val plaintext = decryptOrNull(key, frame.payload) ?: return
        val (messageId, content) = ChatMessagePlaintext.decodeMessage(plaintext)
        scope.launch {
            messageRepository.receiveIncoming(conversationId, messageId, content)
            val ackPayload = cryptoService.encrypt(key, ChatMessagePlaintext.encodeAck(messageId))
            enqueueWrite(PendingWrite.Ack(ChatFrame.EncryptedAck(ackPayload).encode()))
        }
    }

    private fun handleAck(frame: ChatFrame.EncryptedAck) {
        val key = sessionKey ?: return
        val plaintext = decryptOrNull(key, frame.payload) ?: return
        val messageId = ChatMessagePlaintext.decodeAck(plaintext)
        scope.launch {
            messageRepository.getById(messageId)?.let { messageRepository.markDelivered(it) }
        }
    }

    private fun decryptOrNull(key: SecretKey, payload: ByteArray): ByteArray? =
        try {
            cryptoService.decrypt(key, payload)
        } catch (e: Exception) {
            Log.w(TAG, "Decryption failed", e)
            null
        }

    private companion object {
        const val TAG = "ChatConnection"
    }
}
