package com.beacon

import android.bluetooth.BluetoothManager
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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
import com.beacon.ble.ChatConnection
import com.beacon.ble.ChatConnectionState
import com.beacon.ble.PeerDiscovery
import com.beacon.data.ConversationRepository
import com.beacon.data.Identity
import com.beacon.data.Message
import com.beacon.data.MessageDirection
import com.beacon.data.MessageRepository
import com.beacon.data.MessageStatus
import com.beacon.data.Peer
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch

// Journey 3's chat screen, opened by tapping a peer on PeerDiscoveryScreen. Owns exactly
// one ChatConnection (docs/04) for the peer it was opened with, a fresh instance every
// time this composable enters composition for a given peer.id, torn down on exit.
@Composable
fun ChatScreen(
    identity: Identity,
    peer: Peer,
    peerDiscovery: PeerDiscovery,
    conversationRepository: ConversationRepository,
    messageRepository: MessageRepository,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var conversationId by remember(peer.id) { mutableStateOf<String?>(null) }
    var connectionState by remember(peer.id) { mutableStateOf(ChatConnectionState.CONNECTING) }
    var connection by remember(peer.id) { mutableStateOf<ChatConnection?>(null) }

    LaunchedEffect(peer.id) {
        val conversation = conversationRepository.getOrCreate(peer.id)
        conversationId = conversation.id

        // D-018's mapping, reversed: find the device address currently associated with
        // this peer's identity. A miss here means ambient discovery hasn't (yet, or
        // anymore) resolved this peer; docs/04 §6's "fail cleanly" case.
        val deviceAddress = peerDiscovery.identityPublicKeyByDeviceAddress.value
            .entries.firstOrNull { it.value == peer.id }?.key
        if (deviceAddress == null) {
            connectionState = ChatConnectionState.FAILED
            return@LaunchedEffect
        }

        val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val device = bluetoothManager.adapter.getRemoteDevice(deviceAddress)

        val chatConnection = ChatConnection(
            context = context,
            identity = identity,
            peerPublicKey = peer.id,
            conversationId = conversation.id,
            messageRepository = messageRepository,
            scope = this
        )
        connection = chatConnection
        launch { chatConnection.state.collect { connectionState = it } }
        chatConnection.connect(device)
    }

    DisposableEffect(peer.id) {
        onDispose { connection?.disconnect() }
    }

    val messages by remember(conversationId) {
        conversationId?.let { messageRepository.observeForConversation(it) } ?: emptyFlow<List<Message>>()
    }.collectAsState(initial = emptyList())

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text(stringResource(R.string.chat_back_button)) }
            Spacer(Modifier.width(8.dp))
            Column {
                Text(peer.displayName, style = MaterialTheme.typography.titleMedium)
                Text(connectionStatusText(connectionState), style = MaterialTheme.typography.bodySmall)
            }
        }
        Spacer(Modifier.height(8.dp))

        if (messages.isEmpty()) {
            Box(modifier = Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.chat_empty))
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxWidth().weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                items(messages, key = { it.id }) { message -> MessageRow(message) }
            }
        }

        Spacer(Modifier.height(8.dp))
        MessageInput(
            enabled = connectionState == ChatConnectionState.READY,
            onSend = { text ->
                val activeConversationId = conversationId ?: return@MessageInput
                val activeConnection = connection ?: return@MessageInput
                scope.launch {
                    val message = messageRepository.createOutgoing(activeConversationId, text)
                    activeConnection.sendMessage(message)
                }
            }
        )
    }
}

@Composable
private fun connectionStatusText(state: ChatConnectionState): String = stringResource(
    when (state) {
        ChatConnectionState.CONNECTING -> R.string.chat_status_connecting
        ChatConnectionState.HANDSHAKING -> R.string.chat_status_handshaking
        ChatConnectionState.READY -> R.string.chat_status_ready
        ChatConnectionState.FAILED -> R.string.chat_status_failed
        ChatConnectionState.DISCONNECTED -> R.string.chat_status_disconnected
    }
)

@Composable
private fun MessageRow(message: Message) {
    val isOutgoing = message.direction == MessageDirection.OUTGOING
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isOutgoing) Arrangement.End else Arrangement.Start
    ) {
        Column(horizontalAlignment = if (isOutgoing) Alignment.End else Alignment.Start) {
            Text(message.content)
            if (isOutgoing) {
                Text(messageStatusText(message.status), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun messageStatusText(status: MessageStatus): String = stringResource(
    when (status) {
        MessageStatus.SENDING -> R.string.message_status_sending
        MessageStatus.SENT -> R.string.message_status_sent
        MessageStatus.DELIVERED -> R.string.message_status_delivered
        MessageStatus.FAILED -> R.string.message_status_failed
    }
)

@Composable
private fun MessageInput(enabled: Boolean, onSend: (String) -> Unit) {
    var text by remember { mutableStateOf("") }

    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            label = { Text(stringResource(R.string.chat_message_hint)) },
            enabled = enabled,
            modifier = Modifier.weight(1f)
        )
        Spacer(Modifier.width(8.dp))
        Button(
            onClick = {
                val trimmed = text.trim()
                if (trimmed.isNotEmpty()) {
                    onSend(trimmed)
                    text = ""
                }
            },
            enabled = enabled && text.isNotBlank()
        ) {
            Text(stringResource(R.string.chat_send_button))
        }
    }
}
