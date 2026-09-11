package com.beacon

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.beacon.data.Conversation
import com.beacon.data.ConversationRepository
import com.beacon.data.Peer
import com.beacon.data.PeerRepository
import kotlinx.coroutines.flow.combine
import java.text.DateFormat
import java.util.Date

private data class ConversationUiState(val conversation: Conversation, val peer: Peer)

// Milestone 5: ConversationRepository.observeAll() (docs/02, written in Milestone 1) had
// no caller anywhere in the UI until this screen: the only way to reach a chat before
// this was tapping a currently-nearby peer, which made history for anyone out of range
// completely unreachable even though every message was already sitting in the database.
@Composable
fun ConversationsScreen(
    conversationRepository: ConversationRepository,
    peerRepository: PeerRepository,
    onConversationSelected: (Peer) -> Unit
) {
    val conversations by remember(conversationRepository, peerRepository) {
        combine(conversationRepository.observeAll(), peerRepository.observeAll()) { conversations, peers ->
            val peersById = peers.associateBy { it.id }
            // mapNotNull, not a forced lookup: the FK cascade (Conversation -> Peer,
            // ON DELETE CASCADE) means a Conversation should never outlive its Peer row,
            // but these are two independent Flows that don't emit in lockstep, so a brief
            // inconsistency between them costs nothing to guard against defensively.
            conversations.mapNotNull { conversation ->
                peersById[conversation.peerId]?.let { peer -> ConversationUiState(conversation, peer) }
            }
        }
    }.collectAsState(initial = emptyList())

    Column(modifier = Modifier.fillMaxSize().padding(24.dp)) {
        Text(stringResource(R.string.conversations_title), style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(16.dp))
        if (conversations.isEmpty()) {
            Text(stringResource(R.string.conversations_empty))
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(conversations, key = { it.conversation.id }) { state ->
                    ConversationRow(state, onClick = { onConversationSelected(state.peer) })
                }
            }
        }
    }
}

@Composable
private fun ConversationRow(state: ConversationUiState, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(state.peer.displayName)
        Text(formatTimestamp(state.conversation.lastMessageAt), style = MaterialTheme.typography.bodySmall)
    }
}

// D-028: name + timestamp only, deliberately no message preview text; see docs/06 §4.
private fun formatTimestamp(epochMillis: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(epochMillis))
