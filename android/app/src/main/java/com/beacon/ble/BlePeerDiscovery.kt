package com.beacon.ble

import android.content.Context
import com.beacon.data.ConversationRepository
import com.beacon.data.Identity
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
    peerRepository: PeerRepository,
    conversationRepository: ConversationRepository,
    messageRepository: MessageRepository,
    scope: CoroutineScope
) : PeerDiscovery {

    private val central = BleCentralRole(
        context = context,
        scope = scope,
        onPeerResolved = { publicKey, displayName ->
            peerRepository.recordSeen(publicKey, displayName, System.currentTimeMillis())
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
