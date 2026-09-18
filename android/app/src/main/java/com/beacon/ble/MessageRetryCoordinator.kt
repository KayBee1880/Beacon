package com.beacon.ble

import android.bluetooth.BluetoothManager
import android.content.Context
import com.beacon.diagnostics.BeaconLog
import com.beacon.data.ConversationRepository
import com.beacon.data.IdentityRepository
import com.beacon.data.MessageRepository
import com.beacon.data.PeerRepository
import com.beacon.data.RelayEnvelopeRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

private const val HANDSHAKE_TIMEOUT_MS = 30_000L
private const val MESSAGE_SETTLE_DELAY_MS = 5_000L

/**
 * D-021: reacts to ambient discovery resolving a peer by checking whether that peer's
 * conversation has any retryable messages (docs/05 §5) and, if so, opening a fresh
 * ChatConnection (D-024) to resend them, entirely independent of whether any ChatScreen
 * is currently open. This is what makes Journey 4's "pending messages resume sending
 * automatically" true in the background, not just when a chat happens to be reopened.
 */
class MessageRetryCoordinator(
    private val context: Context,
    private val identityRepository: IdentityRepository,
    private val conversationRepository: ConversationRepository,
    private val messageRepository: MessageRepository,
    private val peerRepository: PeerRepository,
    private val relayEnvelopeRepository: RelayEnvelopeRepository,
    private val activeConnections: ActiveChatConnections,
    private val scope: CoroutineScope
) {
    fun onPeerResolved(peerId: String, deviceAddress: String) {
        scope.launch { attemptRetry(peerId, deviceAddress) }
    }

    private suspend fun attemptRetry(peerId: String, deviceAddress: String) {
        if (activeConnections.isActive(peerId)) return

        val conversation = conversationRepository.getOrCreate(peerId)
        val retryable = messageRepository.getRetryableForConversation(conversation.id)
        if (retryable.isEmpty()) return

        val identity = identityRepository.get() ?: return

        val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val device = bluetoothManager.adapter.getRemoteDevice(deviceAddress)

        val connection = ChatConnection(
            context = context,
            identity = identity,
            peerPublicKey = peerId,
            conversationId = conversation.id,
            conversationRepository = conversationRepository,
            messageRepository = messageRepository,
            peerRepository = peerRepository,
            relayEnvelopeRepository = relayEnvelopeRepository,
            scope = scope
        )
        if (!activeConnections.tryRegister(peerId, connection)) return

        try {
            connection.connect(device)
            val reachedState = withTimeoutOrNull(HANDSHAKE_TIMEOUT_MS) {
                connection.state.first {
                    it == ChatConnectionState.READY || it == ChatConnectionState.FAILED
                }
            }
            if (reachedState == ChatConnectionState.READY) {
                retryable.forEach { connection.sendMessage(it) }
                // Brief grace period for acks to arrive before tearing the connection back
                // down; sendMessage returning only means the write was queued, not acked.
                delay(MESSAGE_SETTLE_DELAY_MS)
            } else {
                BeaconLog.w(TAG, "Retry connection to $peerId did not become ready in time")
            }
        } finally {
            connection.disconnect()
            activeConnections.unregister(peerId, connection)
        }
    }

    private companion object {
        const val TAG = "MessageRetryCoordinator"
    }
}
