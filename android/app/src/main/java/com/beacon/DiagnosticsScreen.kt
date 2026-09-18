package com.beacon

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.beacon.data.ConversationRepository
import com.beacon.data.Identity
import com.beacon.data.MessageRepository
import com.beacon.data.MessageStatus
import com.beacon.data.MessageStatusCount
import com.beacon.data.PeerRepository
import com.beacon.data.RelayEnvelopeRepository
import com.beacon.diagnostics.BeaconLog
import com.beacon.diagnostics.LogEntry
import com.beacon.diagnostics.LogLevel
import java.text.DateFormat
import java.util.Date

// docs/11 §3 (D-053): a flat overlay reachable from PeerDiscoveryScreen, not a fourth
// top-level tab, the same one-level-deep navigation the whole app has kept since D-026.
// Every number here is read locally, once (a snapshot, not a live dashboard, refreshed
// on demand) or from BeaconLog's own in-memory buffer; nothing here ever leaves the
// device (D-054).
@Composable
fun DiagnosticsScreen(
    identity: Identity,
    peerRepository: PeerRepository,
    conversationRepository: ConversationRepository,
    messageRepository: MessageRepository,
    relayEnvelopeRepository: RelayEnvelopeRepository,
    onBack: () -> Unit
) {
    val peers by peerRepository.observeAll().collectAsState(initial = emptyList())
    val conversations by conversationRepository.observeAll().collectAsState(initial = emptyList())
    val logEntries by BeaconLog.entries.collectAsState()

    var statusCounts by remember { mutableStateOf<List<MessageStatusCount>>(emptyList()) }
    var relayEnvelopeCount by remember { mutableStateOf(0) }
    var refreshTrigger by remember { mutableStateOf(0) }

    LaunchedEffect(refreshTrigger) {
        statusCounts = messageRepository.getStatusCounts()
        relayEnvelopeCount = relayEnvelopeRepository.count()
    }

    val now = System.currentTimeMillis()
    val direct = peers.count { classifyReachability(it, now) == PeerReachability.DIRECT }
    val recentlyDirect = peers.count { classifyReachability(it, now) == PeerReachability.RECENTLY_DIRECT }
    val meshOnly = peers.count { classifyReachability(it, now) == PeerReachability.MESH_ONLY }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text(stringResource(R.string.chat_back_button)) }
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.diagnostics_title), style = MaterialTheme.typography.headlineSmall)
        }
        Spacer(Modifier.height(16.dp))

        LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            item {
                DiagnosticsSection(stringResource(R.string.diagnostics_section_identity)) {
                    Text(identity.displayName)
                    Text(stringResource(R.string.diagnostics_fingerprint, identity.publicKey.take(16)))
                }
            }
            item {
                DiagnosticsSection(stringResource(R.string.diagnostics_section_peers)) {
                    Text(stringResource(R.string.diagnostics_peers_total, peers.size))
                    Text(stringResource(R.string.diagnostics_peers_direct, direct))
                    Text(stringResource(R.string.diagnostics_peers_recently_direct, recentlyDirect))
                    Text(stringResource(R.string.diagnostics_peers_mesh_only, meshOnly))
                }
            }
            item {
                DiagnosticsSection(stringResource(R.string.diagnostics_section_conversations)) {
                    Text(stringResource(R.string.diagnostics_conversations_total, conversations.size))
                }
            }
            item {
                DiagnosticsSection(stringResource(R.string.diagnostics_section_messages)) {
                    if (statusCounts.isEmpty()) {
                        Text(stringResource(R.string.diagnostics_messages_none))
                    } else {
                        statusCounts.forEach { Text(messageStatusCountText(it)) }
                    }
                }
            }
            item {
                DiagnosticsSection(stringResource(R.string.diagnostics_section_relay)) {
                    Text(stringResource(R.string.diagnostics_relay_held, relayEnvelopeCount))
                }
            }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.diagnostics_section_log), style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.width(8.dp))
                    TextButton(onClick = { refreshTrigger++ }) { Text(stringResource(R.string.diagnostics_refresh_button)) }
                }
            }
            if (logEntries.isEmpty()) {
                item { Text(stringResource(R.string.diagnostics_log_empty)) }
            } else {
                items(logEntries.asReversed()) { entry -> LogEntryRow(entry) }
            }
        }
    }
}

@Composable
private fun DiagnosticsSection(title: String, content: @Composable () -> Unit) {
    Column {
        Text(title, style = MaterialTheme.typography.titleSmall)
        content()
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun messageStatusCountText(statusCount: MessageStatusCount): String {
    val label = stringResource(
        when (statusCount.status) {
            MessageStatus.SENDING -> R.string.message_status_sending
            MessageStatus.SENT -> R.string.message_status_sent
            MessageStatus.DELIVERED -> R.string.message_status_delivered
            MessageStatus.FAILED -> R.string.message_status_failed
        }
    )
    return "$label: ${statusCount.count}"
}

@Composable
private fun LogEntryRow(entry: LogEntry) {
    val time = DateFormat.getTimeInstance(DateFormat.MEDIUM).format(Date(entry.timestamp))
    Column {
        Text("$time  ${levelLabel(entry.level)}  ${entry.tag}", style = MaterialTheme.typography.bodySmall)
        Text(entry.message, style = MaterialTheme.typography.bodySmall)
        if (entry.throwableMessage != null) {
            Text(entry.throwableMessage, style = MaterialTheme.typography.bodySmall)
        }
    }
}

private fun levelLabel(level: LogLevel): String = when (level) {
    LogLevel.DEBUG -> "D"
    LogLevel.WARN -> "W"
    LogLevel.ERROR -> "E"
}
