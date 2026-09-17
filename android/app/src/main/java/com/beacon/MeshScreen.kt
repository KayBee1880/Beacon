package com.beacon

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.beacon.ble.PeerDiscovery
import com.beacon.data.Peer
import com.beacon.data.PeerRepository
import kotlinx.coroutines.flow.combine
import kotlin.math.cos
import kotlin.math.sin

private val DIAGRAM_HEIGHT = 240.dp
private val DIAGRAM_RADIUS = 90.dp
private val NODE_RADIUS = 6.dp

private data class MeshPeerUiState(val peer: Peer, val reachability: PeerReachability, val signal: SignalStrength?)

// docs/10 §5 (D-050): a logical topology, not a geographic one, see that decision's own
// "why" for the privacy reasoning that ruled out anything location-based entirely.
@Composable
fun MeshScreen(
    peerRepository: PeerRepository,
    peerDiscovery: PeerDiscovery,
    onPeerSelected: (Peer) -> Unit
) {
    val peers by remember(peerRepository, peerDiscovery) {
        combine(peerRepository.observeAll(), peerDiscovery.rssiByPeerId) { allPeers, rssiByPeerId ->
            val now = System.currentTimeMillis()
            allPeers.map { peer ->
                val reachability = classifyReachability(peer, now)
                val signal = rssiByPeerId[peer.id]?.let { bucketRssi(it) }
                MeshPeerUiState(peer, reachability, signal)
            }
        }
    }.collectAsState(initial = emptyList())

    val direct = peers.filter { it.reachability == PeerReachability.DIRECT }
    val recentlyDirect = peers.filter { it.reachability == PeerReachability.RECENTLY_DIRECT }
    val meshOnly = peers.filter { it.reachability == PeerReachability.MESH_ONLY }

    Column(modifier = Modifier.fillMaxSize().padding(24.dp)) {
        Text(stringResource(R.string.mesh_title), style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))

        if (peers.isEmpty()) {
            Box(modifier = Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.mesh_empty))
            }
            return@Column
        }

        if (direct.isNotEmpty()) {
            MeshDiagram(direct = direct, onPeerSelected = onPeerSelected)
        }

        LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (recentlyDirect.isNotEmpty()) {
                item { Text(stringResource(R.string.mesh_section_recently_direct), style = MaterialTheme.typography.titleSmall) }
                items(recentlyDirect, key = { it.peer.id }) { state -> MeshPeerRow(state, onPeerSelected) }
            }
            if (meshOnly.isNotEmpty()) {
                item { Text(stringResource(R.string.mesh_section_mesh_only), style = MaterialTheme.typography.titleSmall) }
                items(meshOnly, key = { it.peer.id }) { state -> MeshPeerRow(state, onPeerSelected) }
            }
        }
    }
}

// D-050: only DIRECT peers (a real, live RSSI) get a spatial position; RECENTLY_DIRECT and
// MESH_ONLY peers have no signal to place spatially and are listed as plain rows instead,
// see MeshScreen's own caller.
@Composable
private fun MeshDiagram(direct: List<MeshPeerUiState>, onPeerSelected: (Peer) -> Unit) {
    BoxWithConstraints(modifier = Modifier.fillMaxWidth().height(DIAGRAM_HEIGHT)) {
        val centerX = maxWidth / 2
        val centerY = DIAGRAM_HEIGHT / 2

        Canvas(modifier = Modifier.fillMaxSize()) {
            val center = Offset(centerX.toPx(), centerY.toPx())
            val radiusPx = DIAGRAM_RADIUS.toPx()
            direct.forEachIndexed { index, state ->
                val angle = angleForIndex(index, direct.size)
                val nodeCenter = Offset(
                    center.x + radiusPx * cos(angle).toFloat(),
                    center.y + radiusPx * sin(angle).toFloat()
                )
                drawLine(
                    color = lineColorFor(state.signal),
                    start = center,
                    end = nodeCenter,
                    strokeWidth = lineWidthFor(state.signal)
                )
            }
            drawCircle(color = Color.Gray, radius = NODE_RADIUS.toPx(), center = center)
        }

        direct.forEachIndexed { index, state ->
            val angle = angleForIndex(index, direct.size)
            val offsetX = centerX + DIAGRAM_RADIUS * cos(angle).toFloat()
            val offsetY = centerY + DIAGRAM_RADIUS * sin(angle).toFloat()
            Box(
                modifier = Modifier
                    .offset(x = offsetX - NODE_RADIUS * 3, y = offsetY - NODE_RADIUS * 3)
                    .clickable { onPeerSelected(state.peer) }
            ) {
                Text(state.peer.displayName, style = MaterialTheme.typography.bodySmall)
            }
        }

        Box(modifier = Modifier.offset(x = centerX - NODE_RADIUS * 4, y = centerY - NODE_RADIUS * 4)) {
            Text(stringResource(R.string.mesh_you_label), style = MaterialTheme.typography.bodySmall)
        }
    }
}

private fun angleForIndex(index: Int, count: Int): Double =
    // Starts at the top (-90 degrees) rather than 0/east, purely so a single peer lands
    // directly above "you" instead of to the right, a more natural default orientation.
    -Math.PI / 2 + (2 * Math.PI * index / count)

private fun lineColorFor(signal: SignalStrength?): Color = when (signal) {
    SignalStrength.STRONG -> Color(0xFF2E7D32)
    SignalStrength.MEDIUM -> Color(0xFFF9A825)
    SignalStrength.WEAK -> Color(0xFFC62828)
    null -> Color.Gray
}

private fun lineWidthFor(signal: SignalStrength?): Float = when (signal) {
    SignalStrength.STRONG -> 6f
    SignalStrength.MEDIUM -> 4f
    SignalStrength.WEAK -> 2f
    null -> 2f
}

@Composable
private fun MeshPeerRow(state: MeshPeerUiState, onPeerSelected: (Peer) -> Unit) {
    Text(
        state.peer.displayName,
        modifier = Modifier.fillMaxWidth().clickable { onPeerSelected(state.peer) }.padding(vertical = 8.dp)
    )
}
