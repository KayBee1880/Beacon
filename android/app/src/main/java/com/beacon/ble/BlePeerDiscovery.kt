package com.beacon.ble

import android.content.Context
import com.beacon.data.ConversationRepository
import com.beacon.data.Identity
import com.beacon.data.IdentityRepository
import com.beacon.data.MessageRepository
import com.beacon.data.PeerRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow

interface PeerDiscovery {
    val rssiByPeerId: StateFlow<Map<String, Int>>
    val identityPublicKeyByDeviceAddress: StateFlow<Map<String, String>>
    fun start(identity: Identity)
    fun stop()
}

class BlePeerDiscovery(
    context: Context,
    identityRepository: IdentityRepository,
    peerRepository: PeerRepository,
    conversationRepository: ConversationRepository,
    messageRepository: MessageRepository,
    activeChatConnections: ActiveChatConnections,
    scope: CoroutineScope
) : PeerDiscovery {

    // D-021: reacts to central's resolves alongside PeerRepository, driving background
    // retries independently of any open ChatScreen.
    private val retryCoordinator = MessageRetryCoordinator(
        context = context,
        identityRepository = identityRepository,
        conversationRepository = conversationRepository,
        messageRepository = messageRepository,
        activeConnections = activeChatConnections,
        scope = scope
    )

    private val central = BleCentralRole(
        context = context,
        scope = scope,
        onPeerResolved = { publicKey, displayName, deviceAddress ->
            peerRepository.recordSeen(publicKey, displayName, System.currentTimeMillis())
            retryCoordinator.onPeerResolved(publicKey, deviceAddress)
        }
    )

    // Declared after central, deliberately: it needs central's resolved-identity map to
    // hand to ChatGattServer (D-018), so central has to exist first.
    private val peripheral = BlePeripheralRole(
        context = context,
        scope = scope,
        conversationRepository = conversationRepository,
        messageRepository = messageRepository,
        resolvedIdentityByDeviceAddress = { central.identityPublicKeyByDeviceAddress.value }
    )

    override val rssiByPeerId: StateFlow<Map<String, Int>> = central.rssiByPeerId
    override val identityPublicKeyByDeviceAddress: StateFlow<Map<String, String>> =
        central.identityPublicKeyByDeviceAddress

    override fun start(identity: Identity) {
        peripheral.start(identity)
        central.start()
    }

    override fun stop() {
        central.stop()
        peripheral.stop()
    }
}
