package com.beacon

import android.app.Application
import com.beacon.ble.ActiveChatConnections
import com.beacon.ble.BlePeerDiscovery
import com.beacon.ble.PeerDiscovery
import com.beacon.data.BeaconDatabase
import com.beacon.data.ConversationRepository
import com.beacon.data.IdentityRepository
import com.beacon.data.MessageRepository
import com.beacon.data.PeerRepository
import com.beacon.data.RelayEnvelopeRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class BeaconApplication : Application() {

    lateinit var database: BeaconDatabase
        private set

    lateinit var identityRepository: IdentityRepository
        private set

    lateinit var peerRepository: PeerRepository
        private set

    lateinit var conversationRepository: ConversationRepository
        private set

    lateinit var messageRepository: MessageRepository
        private set

    lateinit var relayEnvelopeRepository: RelayEnvelopeRepository
        private set

    // D-024: one shared registry so ChatScreen, MessageRetryCoordinator, and (Milestone 6)
    // RelayGossipCoordinator never more than one of them hold an open connection to the
    // same peer at once.
    lateinit var activeChatConnections: ActiveChatConnections
        private set

    lateinit var peerDiscovery: PeerDiscovery
        private set

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        database = BeaconDatabase.build(this)
        identityRepository = IdentityRepository(database.identityDao())
        peerRepository = PeerRepository(database.peerDao())
        conversationRepository = ConversationRepository(database.conversationDao())
        relayEnvelopeRepository = RelayEnvelopeRepository(database.relayEnvelopeDao())
        messageRepository = MessageRepository(
            database.messageDao(),
            database.conversationDao(),
            peerRepository,
            relayEnvelopeRepository,
            identityRepository
        )
        activeChatConnections = ActiveChatConnections()
        peerDiscovery = BlePeerDiscovery(
            this,
            identityRepository,
            peerRepository,
            conversationRepository,
            messageRepository,
            relayEnvelopeRepository,
            activeChatConnections,
            applicationScope
        )
    }
}
