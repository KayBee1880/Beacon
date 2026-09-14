package com.beacon

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.beacon.ble.ActiveChatConnections
import com.beacon.ble.BlePermissions
import com.beacon.ble.PeerDiscovery
import com.beacon.data.ConversationRepository
import com.beacon.data.Identity
import com.beacon.data.IdentityRepository
import com.beacon.data.MessageRepository
import com.beacon.data.Peer
import com.beacon.data.PeerRepository
import com.beacon.data.RelayEnvelopeRepository
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as BeaconApplication
        setContent {
            BeaconTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    BeaconApp(
                        identityRepository = app.identityRepository,
                        peerRepository = app.peerRepository,
                        conversationRepository = app.conversationRepository,
                        messageRepository = app.messageRepository,
                        relayEnvelopeRepository = app.relayEnvelopeRepository,
                        activeChatConnections = app.activeChatConnections,
                        peerDiscovery = app.peerDiscovery
                    )
                }
            }
        }
    }
}

@Composable
private fun BeaconApp(
    identityRepository: IdentityRepository,
    peerRepository: PeerRepository,
    conversationRepository: ConversationRepository,
    messageRepository: MessageRepository,
    relayEnvelopeRepository: RelayEnvelopeRepository,
    activeChatConnections: ActiveChatConnections,
    peerDiscovery: PeerDiscovery
) {
    val identity by identityRepository.observe().collectAsState(initial = null)
    val currentIdentity = identity
    if (currentIdentity == null) {
        IdentitySetupScreen(identityRepository = identityRepository)
        return
    }

    val context = LocalContext.current
    var hasPermissions by remember { mutableStateOf(BlePermissions.allGranted(context)) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results -> hasPermissions = results.values.all { it } }

    if (!hasPermissions) {
        PermissionNeededContent(onRequestPermissions = { permissionLauncher.launch(BlePermissions.required()) })
        return
    }

    // Discovery's lifecycle is tied to having an identity at all, not to which screen is
    // currently visible: it has to keep running in the background while a chat is open
    // too, both so this device stays connectable (BlePeripheralRole) and so other peers
    // keep resolving. Tying this to PeerDiscoveryScreen's own composition instead (as
    // Milestone 2 originally did, before there was a second screen to navigate to) would
    // tear discovery down the moment either side of a chat navigates away from Nearby,
    // closing the very GATT server the chat connection depends on.
    DisposableEffect(currentIdentity) {
        peerDiscovery.start(currentIdentity)
        onDispose { peerDiscovery.stop() }
    }

    var selectedPeer by remember { mutableStateOf<Peer?>(null) }
    var topLevelTab by remember { mutableStateOf(TopLevelTab.NEARBY) }
    val peer = selectedPeer
    if (peer != null) {
        ChatScreen(
            identity = currentIdentity,
            peer = peer,
            peerDiscovery = peerDiscovery,
            conversationRepository = conversationRepository,
            messageRepository = messageRepository,
            peerRepository = peerRepository,
            relayEnvelopeRepository = relayEnvelopeRepository,
            activeChatConnections = activeChatConnections,
            onBack = { selectedPeer = null }
        )
        return
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Box(modifier = Modifier.weight(1f)) {
            when (topLevelTab) {
                TopLevelTab.NEARBY -> PeerDiscoveryScreen(
                    identity = currentIdentity,
                    peerRepository = peerRepository,
                    peerDiscovery = peerDiscovery,
                    onPeerSelected = { selectedPeer = it }
                )
                TopLevelTab.CONVERSATIONS -> ConversationsScreen(
                    conversationRepository = conversationRepository,
                    peerRepository = peerRepository,
                    onConversationSelected = { selectedPeer = it }
                )
            }
        }
        NavigationBar {
            NavigationBarItem(
                selected = topLevelTab == TopLevelTab.NEARBY,
                onClick = { topLevelTab = TopLevelTab.NEARBY },
                icon = {},
                label = { Text(stringResource(R.string.nav_nearby)) }
            )
            NavigationBarItem(
                selected = topLevelTab == TopLevelTab.CONVERSATIONS,
                onClick = { topLevelTab = TopLevelTab.CONVERSATIONS },
                icon = {},
                label = { Text(stringResource(R.string.nav_conversations)) }
            )
        }
    }
}

// D-027: text labels only, no Icon glyphs; see docs/06 §3.
private enum class TopLevelTab { NEARBY, CONVERSATIONS }

@Composable
private fun IdentitySetupScreen(identityRepository: IdentityRepository) {
    var displayName by remember { mutableStateOf("") }
    var isCreating by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(stringResource(R.string.setup_title), style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.setup_description))
        Spacer(Modifier.height(24.dp))
        OutlinedTextField(
            value = displayName,
            onValueChange = { displayName = it },
            label = { Text(stringResource(R.string.setup_display_name_label)) },
            singleLine = true,
            enabled = !isCreating
        )
        Spacer(Modifier.height(16.dp))
        Button(
            onClick = {
                isCreating = true
                scope.launch {
                    identityRepository.createIdentity(displayName.trim())
                }
            },
            enabled = displayName.isNotBlank() && !isCreating
        ) {
            Text(
                stringResource(
                    if (isCreating) R.string.setup_create_button_creating
                    else R.string.setup_create_button
                )
            )
        }
    }
}

private enum class SignalStrength { STRONG, MEDIUM, WEAK }

private data class NearbyPeerUiState(val peer: Peer, val signal: SignalStrength)

private const val NEARBY_GRACE_PERIOD_MS = 30_000L

private fun bucketRssi(rssi: Int): SignalStrength = when {
    rssi >= -60 -> SignalStrength.STRONG
    rssi >= -80 -> SignalStrength.MEDIUM
    else -> SignalStrength.WEAK
}

@Composable
private fun PeerDiscoveryScreen(
    identity: Identity,
    peerRepository: PeerRepository,
    peerDiscovery: PeerDiscovery,
    onPeerSelected: (Peer) -> Unit
) {
    val nearbyPeers by remember(peerRepository, peerDiscovery) {
        combine(peerRepository.observeAll(), peerDiscovery.rssiByPeerId) { peers, rssiByPeerId ->
            val now = System.currentTimeMillis()
            peers
                .filter { now - it.lastSeenAt <= NEARBY_GRACE_PERIOD_MS }
                .map { peer -> NearbyPeerUiState(peer, bucketRssi(rssiByPeerId[peer.id] ?: Int.MIN_VALUE)) }
        }
    }.collectAsState(initial = emptyList())

    Column(modifier = Modifier.fillMaxSize().padding(24.dp)) {
        Text(stringResource(R.string.nearby_title), style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(4.dp))
        Text(stringResource(R.string.nearby_signed_in_as, identity.displayName))
        Spacer(Modifier.height(16.dp))
        if (nearbyPeers.isEmpty()) {
            Text(stringResource(R.string.nearby_empty))
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(nearbyPeers, key = { it.peer.id }) { nearbyPeer ->
                    PeerRow(nearbyPeer, onClick = { onPeerSelected(nearbyPeer.peer) })
                }
            }
        }
    }
}

@Composable
private fun PermissionNeededContent(onRequestPermissions: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(stringResource(R.string.permission_title), style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.permission_description))
        Spacer(Modifier.height(16.dp))
        Button(onClick = onRequestPermissions) {
            Text(stringResource(R.string.permission_grant_button))
        }
    }
}

@Composable
private fun PeerRow(nearbyPeer: NearbyPeerUiState, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(nearbyPeer.peer.displayName)
        Text(
            stringResource(
                when (nearbyPeer.signal) {
                    SignalStrength.STRONG -> R.string.signal_strong
                    SignalStrength.MEDIUM -> R.string.signal_medium
                    SignalStrength.WEAK -> R.string.signal_weak
                }
            )
        )
    }
}

@Composable
private fun BeaconTheme(content: @Composable () -> Unit) {
    MaterialTheme(content = content)
}
