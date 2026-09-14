package com.beacon.ble

import android.content.Context
import com.beacon.data.ConversationRepository
import com.beacon.data.Identity
import com.beacon.data.IdentityRepository
import com.beacon.data.MessageRepository
import com.beacon.data.PeerRepository
import com.beacon.data.RelayEnvelopeRepository
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
    relayEnvelopeRepository: RelayEnvelopeRepository,
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
        peerRepository = peerRepository,
        relayEnvelopeRepository = relayEnvelopeRepository,
        activeConnections = activeChatConnections,
        scope = scope
    )

    // D-034: reacts to every resolve, not just ones with a message waiting, the behavior
    // that actually makes this device part of a mesh rather than just a retrying sender.
    private val relayGossipCoordinator = RelayGossipCoordinator(
        context = context,
        identityRepository = identityRepository,
        conversationRepository = conversationRepository,
        messageRepository = messageRepository,
        peerRepository = peerRepository,
        relayEnvelopeRepository = relayEnvelopeRepository,
        activeConnections = activeChatConnections,
        scope = scope
    )

    private val central = BleCentralRole(
        context = context,
        scope = scope,
        onPeerResolved = { publicKey, displayName, deviceAddress, encryptionPublicKey ->
            peerRepository.recordSeen(publicKey, displayName, System.currentTimeMillis(), encryptionPublicKey)
            retryCoordinator.onPeerResolved(publicKey, deviceAddress)
            relayGossipCoordinator.onPeerResolved(publicKey, deviceAddress)
        }
    )

    // Declared after central, deliberately: it needs central's resolved-identity map to
    // hand to ChatGattServer (D-018), so central has to exist first.
    private val peripheral = BlePeripheralRole(
        context = context,
        scope = scope,
        conversationRepository = conversationRepository,
        messageRepository = messageRepository,
        peerRepository = peerRepository,
        relayEnvelopeRepository = relayEnvelopeRepository,
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
