package com.beacon.ble

import android.bluetooth.BluetoothManager
import android.content.Context
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
import java.util.concurrent.ConcurrentHashMap

private const val HANDSHAKE_TIMEOUT_MS = 30_000L
private const val GOSSIP_SETTLE_DELAY_MS = 5_000L
// D-034: a coarse starting mitigation for a re-resolving peer (Milestone 2's own grace
// period) triggering a fresh connection every time; unvalidated against real usage.
private const val GOSSIP_THROTTLE_MS = 5 * 60_000L

/**
 * D-034: the actual mesh behavior, distinct from MessageRetryCoordinator. Where that
 * class only ever acts when this specific peer has a message waiting for them, this one
 * connects to *every* newly resolved peer, purely so RelayGossipSession's inventory
 * exchange (docs/07 §8) gets a chance to run, since a device carrying a message for a
 * third party has no other reason to ever talk to whoever it just met.
 */
class RelayGossipCoordinator(
    private val context: Context,
    private val identityRepository: IdentityRepository,
    private val conversationRepository: ConversationRepository,
    private val messageRepository: MessageRepository,
    private val peerRepository: PeerRepository,
    private val relayEnvelopeRepository: RelayEnvelopeRepository,
    private val activeConnections: ActiveChatConnections,
    private val scope: CoroutineScope
) {
    private val lastGossipAtByDeviceAddress = ConcurrentHashMap<String, Long>()

    fun onPeerResolved(peerId: String, deviceAddress: String) {
        val now = System.currentTimeMillis()
        val last = lastGossipAtByDeviceAddress[deviceAddress]
        if (last != null && now - last < GOSSIP_THROTTLE_MS) return
        lastGossipAtByDeviceAddress[deviceAddress] = now
        scope.launch { attemptGossip(peerId, deviceAddress) }
    }

    private suspend fun attemptGossip(peerId: String, deviceAddress: String) {
        // Someone else (an open ChatScreen, or MessageRetryCoordinator) already holds a
        // connection to this peer; its own RelayGossipSession already ran or is running
        // gossip as a side effect of reaching READY (docs/07 §8), a second connection
        // here would be pure waste, not additional coverage.
        if (activeConnections.isActive(peerId)) return

        val conversation = conversationRepository.getOrCreate(peerId)
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
                connection.state.first { it == ChatConnectionState.READY || it == ChatConnectionState.FAILED }
            }
            if (reachedState == ChatConnectionState.READY) {
                // Brief grace period for the gossip round's own frames to actually be
                // exchanged before tearing the connection back down, same reasoning
                // MessageRetryCoordinator's own settle delay already documents.
                delay(GOSSIP_SETTLE_DELAY_MS)
            }
        } finally {
            connection.disconnect()
            activeConnections.unregister(peerId, connection)
        }
    }
}
