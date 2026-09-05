package com.beacon.ble

import android.content.Context
import com.beacon.data.Identity
import com.beacon.data.PeerRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow

interface PeerDiscovery {
    val rssiByPeerId: StateFlow<Map<String, Int>>
    fun start(identity: Identity)
    fun stop()
}

class BlePeerDiscovery(
    context: Context,
    peerRepository: PeerRepository,
    scope: CoroutineScope
) : PeerDiscovery {

    private val peripheral = BlePeripheralRole(context)
    private val central = BleCentralRole(
        context = context,
        scope = scope,
        onPeerResolved = { publicKey, displayName ->
            peerRepository.recordSeen(publicKey, displayName, System.currentTimeMillis())
        }
    )

    override val rssiByPeerId: StateFlow<Map<String, Int>> = central.rssiByPeerId

    override fun start(identity: Identity) {
        peripheral.start(identity)
        central.start()
    }

    override fun stop() {
        central.stop()
        peripheral.stop()
    }
}
