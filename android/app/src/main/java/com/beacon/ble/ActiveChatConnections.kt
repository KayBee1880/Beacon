package com.beacon.ble

import java.util.concurrent.ConcurrentHashMap

/**
 * D-024: prevents a screen-initiated chat and a background retry from independently
 * opening two GATT connections to the same peer at once. Android's concurrent-connection
 * cap is low (docs/03 §7, docs/04 §5), so this is a real resource-contention guard, not a
 * tidiness measure. Callers (ChatScreen, MessageRetryCoordinator) are responsible for
 * registering before connecting and unregistering when they're done; this class holds no
 * opinion about connection lifecycles itself.
 */
class ActiveChatConnections {

    private val connectionsByPeerId = ConcurrentHashMap<String, ChatConnection>()

    /** Returns true if registered (no connection was already active for this peer). */
    fun tryRegister(peerId: String, connection: ChatConnection): Boolean =
        connectionsByPeerId.putIfAbsent(peerId, connection) == null

    /** No-ops if [connection] isn't the currently-registered one for [peerId]. */
    fun unregister(peerId: String, connection: ChatConnection) {
        connectionsByPeerId.remove(peerId, connection)
    }

    fun isActive(peerId: String): Boolean = connectionsByPeerId.containsKey(peerId)
}
