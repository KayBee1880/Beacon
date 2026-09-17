package com.beacon

import android.bluetooth.BluetoothManager
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import com.beacon.ble.ActiveChatConnections
import com.beacon.ble.ChatConnection
import com.beacon.ble.ChatConnectionState
import com.beacon.ble.PeerDiscovery
import com.beacon.data.AttachmentState
import com.beacon.data.ConversationRepository
import com.beacon.data.Identity
import com.beacon.data.Message
import com.beacon.data.MessageDirection
import com.beacon.data.MessageRepository
import com.beacon.data.MessageStatus
import com.beacon.data.Peer
import com.beacon.data.PeerRepository
import com.beacon.data.RelayEnvelopeRepository
import com.beacon.wifidirect.WifiDirectPermissions
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.UUID

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
    peerRepository: PeerRepository,
    relayEnvelopeRepository: RelayEnvelopeRepository,
    activeChatConnections: ActiveChatConnections,
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
            conversationRepository = conversationRepository,
            messageRepository = messageRepository,
            peerRepository = peerRepository,
            relayEnvelopeRepository = relayEnvelopeRepository,
            scope = this
        )
        // D-024: if a background retry already holds a connection to this peer, don't
        // open a second one, this screen just shows whatever state that leaves it in.
        if (!activeChatConnections.tryRegister(peer.id, chatConnection)) {
            connectionState = ChatConnectionState.FAILED
            return@LaunchedEffect
        }
        connection = chatConnection
        launch { chatConnection.state.collect { connectionState = it } }
        chatConnection.connect(device)
    }

    DisposableEffect(peer.id) {
        onDispose {
            connection?.let { activeChatConnections.unregister(peer.id, it) }
            connection?.disconnect()
        }
    }

    val messages by remember(conversationId) {
        conversationId?.let { messageRepository.observeForConversation(it) } ?: emptyFlow<List<Message>>()
    }.collectAsState(initial = emptyList())

    // D-051: blank whenever this peer has no live RSSI, either they've never been
    // resolved directly (a conversation opened from ConversationsScreen with a mesh-only
    // peer) or they were resolved once to open this chat but have since gone out of range;
    // reuses the exact same bucketing MeshScreen and PeerDiscoveryScreen already share.
    val rssiByPeerId by peerDiscovery.rssiByPeerId.collectAsState()
    val signal = rssiByPeerId[peer.id]?.let { bucketRssi(it) }

    // docs/08 §10: attachment permissions are requested here, on demand, the first time
    // the attach button is actually tapped, not folded into BeaconApp's app-wide gate,
    // most users may never send a file at all.
    val filePickerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val activeConversationId = conversationId ?: return@rememberLauncherForActivityResult
        val activeConnection = connection ?: return@rememberLauncherForActivityResult
        scope.launch {
            val message = copyPickedFileAsAttachment(context, uri, activeConversationId, messageRepository)
            if (message != null) activeConnection.sendAttachment(message)
        }
    }
    val wifiPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results.values.all { it }) filePickerLauncher.launch(arrayOf("*/*"))
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text(stringResource(R.string.chat_back_button)) }
            Spacer(Modifier.width(8.dp))
            Column {
                Text(peer.displayName, style = MaterialTheme.typography.titleMedium)
                Text(connectionStatusText(connectionState), style = MaterialTheme.typography.bodySmall)
                if (signal != null) {
                    Text(signalStrengthText(signal), style = MaterialTheme.typography.bodySmall)
                }
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
            },
            onAttach = {
                if (WifiDirectPermissions.allGranted(context)) {
                    filePickerLauncher.launch(arrayOf("*/*"))
                } else {
                    wifiPermissionLauncher.launch(WifiDirectPermissions.required())
                }
            }
        )
    }
}

// docs/08 §9: app-private storage, named by the same messageId that will travel in the
// offer, so the sending side's own local file and the eventual Message row always agree
// on where the bytes are. Hashing while copying (a running MessageDigest.update per
// chunk) avoids a second full read of the file just to compute what CryptoService.encrypt
// and the receiver's own verification (docs/08 §5) both need.
private suspend fun copyPickedFileAsAttachment(
    context: Context,
    uri: Uri,
    conversationId: String,
    messageRepository: MessageRepository
): Message? {
    val contentResolver = context.contentResolver
    val fileName = queryDisplayName(context, uri) ?: uri.lastPathSegment ?: "file"
    val mimeType = contentResolver.getType(uri) ?: "application/octet-stream"
    val messageId = UUID.randomUUID().toString()
    val destinationFile = File(context.filesDir, "attachments/$messageId")
    destinationFile.parentFile?.mkdirs()

    val digest = MessageDigest.getInstance("SHA-256")
    var sizeBytes = 0L
    val input = contentResolver.openInputStream(uri) ?: return null
    input.use { stream ->
        FileOutputStream(destinationFile).use { output ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = stream.read(buffer)
                if (read <= 0) break
                output.write(buffer, 0, read)
                digest.update(buffer, 0, read)
                sizeBytes += read
            }
        }
    }

    return messageRepository.createOutgoingAttachment(
        messageId = messageId,
        conversationId = conversationId,
        fileName = fileName,
        mimeType = mimeType,
        sizeBytes = sizeBytes,
        contentHash = digest.digest(),
        localPath = destinationFile.absolutePath
    )
}

private fun queryDisplayName(context: Context, uri: Uri): String? =
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        val nameColumn = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (nameColumn >= 0 && cursor.moveToFirst()) cursor.getString(nameColumn) else null
    }

// Same three string resources PeerDiscoveryScreen's PeerRow already uses for the exact
// same buckets (D-051): a live signal reading means the same thing wherever it's shown.
@Composable
private fun signalStrengthText(signal: SignalStrength): String = stringResource(
    when (signal) {
        SignalStrength.STRONG -> R.string.signal_strong
        SignalStrength.MEDIUM -> R.string.signal_medium
        SignalStrength.WEAK -> R.string.signal_weak
    }
)

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
            // docs/08 §10: an attachment message reuses this row's existing
            // outgoing/incoming alignment, only the content line itself branches, a chat
            // with attachments still reads as one continuous thread, not a separate screen.
            val attachmentState = message.attachmentState
            if (attachmentState != null) {
                Text(message.attachmentFileName ?: "")
                Text(attachmentStateText(attachmentState), style = MaterialTheme.typography.bodySmall)
            } else {
                Text(message.content)
                if (isOutgoing) {
                    Text(messageStatusText(message.status), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun attachmentStateText(state: AttachmentState): String = stringResource(
    when (state) {
        AttachmentState.LOCAL -> R.string.attachment_state_local
        AttachmentState.OFFERED -> R.string.attachment_state_offered
        AttachmentState.TRANSFERRING -> R.string.attachment_state_transferring
        AttachmentState.RECEIVED -> R.string.attachment_state_received
        AttachmentState.FAILED -> R.string.attachment_state_failed
    }
)

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
private fun MessageInput(enabled: Boolean, onSend: (String) -> Unit, onAttach: () -> Unit) {
    var text by remember { mutableStateOf("") }

    Row(verticalAlignment = Alignment.CenterVertically) {
        // Gated on the same `enabled` (connectionState == READY) as the send button:
        // docs/08 §10 requires a live connection for the offer/response exchange, the
        // same reason a plain text send is disabled before READY.
        TextButton(onClick = onAttach, enabled = enabled) {
            Text(stringResource(R.string.chat_attach_button))
        }
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
