package com.beacon.wifidirect

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.util.Log
import com.beacon.crypto.CryptoService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import javax.crypto.SecretKey
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private const val TRANSFER_PORT = 8988
private const val SOCKET_TIMEOUT_MS = 15_000
private const val CONNECTION_TIMEOUT_MS = 30_000L
// docs/08 §5: provisional, unvalidated against any real transfer.
private const val CHUNK_SIZE_BYTES = 64 * 1024

/**
 * One-shot bulk transfer over Wi-Fi Direct (docs/08 §4/§5): construct fresh per attempt,
 * exactly like ChatConnection is fresh per chat, never reused across transfers. Which side
 * calls send() vs receive() is decided by the application-level offer/response exchange
 * (docs/08 §3, handled in ChatConnection/ChatGattServer); which side ends up Wi-Fi
 * Direct's group owner is a separate, Android-decided outcome neither function assumes
 * ahead of time, both branch on WifiP2pInfo.isGroupOwner identically.
 */
@SuppressLint("MissingPermission")
class WifiDirectFileTransfer(private val context: Context) {

    private val manager = context.getSystemService(Context.WIFI_P2P_SERVICE) as WifiP2pManager
    private val channel: WifiP2pManager.Channel = manager.initialize(context, context.mainLooper, null)
    private val cryptoService = CryptoService()

    // Everything past connection setup here is blocking socket/file I/O (ServerSocket.accept,
    // Socket.connect, stream reads/writes): withContext(Dispatchers.IO) is what keeps this
    // off whatever dispatcher the caller happens to be on, which for a UI-triggered send can
    // be Compose's own Main-dispatched scope; blocking that would freeze the chat screen.
    suspend fun send(peerWifiDeviceAddress: String, sessionKey: SecretKey, file: File): Boolean {
        return try {
            discoverPeers()
            connect(peerWifiDeviceAddress)
            val info = awaitConnectionInfo() ?: return false
            withContext(Dispatchers.IO) {
                openDataSocket(info).use { socket -> sendChunks(socket, sessionKey, file) }
            }
            true
        } catch (e: Exception) {
            Log.w(TAG, "Attachment send failed", e)
            false
        } finally {
            removeGroup()
        }
    }

    suspend fun receive(sessionKey: SecretKey, destinationFile: File, expectedSizeBytes: Long, expectedHash: ByteArray): Boolean {
        return try {
            discoverPeers()
            val info = awaitConnectionInfo() ?: return false
            val received = withContext(Dispatchers.IO) {
                openDataSocket(info).use { socket ->
                    receiveChunks(socket, sessionKey, destinationFile, expectedSizeBytes, expectedHash)
                }
            }
            if (!received) destinationFile.delete()
            received
        } catch (e: Exception) {
            Log.w(TAG, "Attachment receive failed", e)
            destinationFile.delete()
            false
        } finally {
            removeGroup()
        }
    }

    // docs/08 §3: this device's own Wi-Fi Direct address, sent in an offer so the peer can
    // find this device via their own discovery. requestDeviceInfo was added in API 29;
    // supporting the older broadcast-based fallback (WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)
    // for API 26-28 was judged not worth the added complexity for an ever-shrinking sliver
    // of real devices, so attachments are effectively unavailable below API 29, a real,
    // named scope limit, not an oversight.
    suspend fun getLocalDeviceAddress(): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        return suspendCancellableCoroutine { cont ->
            manager.requestDeviceInfo(channel) { device ->
                if (cont.isActive) cont.resume(device?.deviceAddress)
            }
        }
    }

    // Fire-and-forget: this only needs to bring the local Wi-Fi P2P stack into an active
    // state, not build a peer list to filter (docs/08 §3's revised approach, see docs/08's
    // own note on this simplification). A failure here doesn't abort the transfer outright,
    // connect()/the connection broadcast below are what actually determine success.
    private suspend fun discoverPeers() {
        suspendCancellableCoroutine<Unit> { cont ->
            manager.discoverPeers(channel, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    if (cont.isActive) cont.resume(Unit)
                }
                override fun onFailure(reason: Int) {
                    Log.w(TAG, "discoverPeers failed, reason $reason")
                    if (cont.isActive) cont.resume(Unit)
                }
            })
        }
    }

    private suspend fun connect(peerWifiDeviceAddress: String) {
        suspendCancellableCoroutine<Unit> { cont ->
            val config = WifiP2pConfig().apply { deviceAddress = peerWifiDeviceAddress }
            manager.connect(channel, config, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    if (cont.isActive) cont.resume(Unit)
                }
                override fun onFailure(reason: Int) {
                    if (cont.isActive) cont.resumeWithException(IllegalStateException("connect() failed, reason $reason"))
                }
            })
        }
    }

    // Registered by both roles: the sender ends up here right after connect() succeeds,
    // the receiver ends up here without ever having called connect() at all, Android
    // fires this same broadcast on both ends once its own group-owner negotiation
    // finishes (docs/08 §4). A bounded wait, not indefinite, same reasoning
    // MessageRetryCoordinator's own handshake timeout already established.
    private suspend fun awaitConnectionInfo(): WifiP2pInfo? = withTimeoutOrNull(CONNECTION_TIMEOUT_MS) {
        suspendCancellableCoroutine { cont ->
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(receiverContext: Context, intent: Intent) {
                    if (intent.action != WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION) return
                    @Suppress("DEPRECATION")
                    val info = intent.getParcelableExtra<WifiP2pInfo>(WifiP2pManager.EXTRA_WIFI_P2P_INFO)
                    if (info != null && info.groupFormed && cont.isActive) {
                        context.unregisterReceiver(this)
                        cont.resume(info)
                    }
                }
            }
            context.registerReceiver(receiver, IntentFilter(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION))
            cont.invokeOnCancellation {
                try {
                    context.unregisterReceiver(receiver)
                } catch (e: IllegalArgumentException) {
                    // Already unregistered by a successful resume racing this cancellation; harmless.
                }
            }
        }
    }

    // D-039: neither send() nor receive() assumes which role it ended up with, both branch
    // on isGroupOwner identically. One fixed port, agreed by both sides simply by both
    // being this same class: no negotiation needed for a value neither side chooses per
    // transfer.
    private fun openDataSocket(info: WifiP2pInfo): Socket =
        if (info.isGroupOwner) {
            ServerSocket(TRANSFER_PORT).use { serverSocket ->
                serverSocket.soTimeout = SOCKET_TIMEOUT_MS
                serverSocket.accept()
            }
        } else {
            Socket().apply {
                connect(java.net.InetSocketAddress(info.groupOwnerAddress, TRANSFER_PORT), SOCKET_TIMEOUT_MS)
            }
        }

    private fun sendChunks(socket: Socket, sessionKey: SecretKey, file: File) {
        val out = DataOutputStream(socket.getOutputStream())
        FileInputStream(file).use { input ->
            val buffer = ByteArray(CHUNK_SIZE_BYTES)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                val plaintext = if (read == buffer.size) buffer else buffer.copyOf(read)
                val ciphertext = cryptoService.encrypt(sessionKey, plaintext)
                out.writeInt(ciphertext.size)
                out.write(ciphertext)
            }
        }
        out.flush()
    }

    // Stops once expectedSizeBytes worth of plaintext has been read, per docs/08 §5;
    // returns false (a "failed", not thrown, outcome, docs/08's own posture on untrusted
    // input) on a short read, a decrypt failure, or a final hash mismatch.
    private fun receiveChunks(
        socket: Socket,
        sessionKey: SecretKey,
        destinationFile: File,
        expectedSizeBytes: Long,
        expectedHash: ByteArray
    ): Boolean {
        val input = DataInputStream(socket.getInputStream())
        val digest = MessageDigest.getInstance("SHA-256")
        var totalRead = 0L

        FileOutputStream(destinationFile).use { output ->
            while (totalRead < expectedSizeBytes) {
                val ciphertextLength = try {
                    input.readInt()
                } catch (e: Exception) {
                    Log.w(TAG, "Attachment transfer ended early", e)
                    return false
                }
                if (ciphertextLength <= 0) return false
                val ciphertext = ByteArray(ciphertextLength)
                input.readFully(ciphertext)
                val plaintext = try {
                    cryptoService.decrypt(sessionKey, ciphertext)
                } catch (e: Exception) {
                    Log.w(TAG, "Attachment chunk decryption failed", e)
                    return false
                }
                output.write(plaintext)
                digest.update(plaintext)
                totalRead += plaintext.size
            }
        }

        if (totalRead != expectedSizeBytes) return false
        return digest.digest().contentEquals(expectedHash)
    }

    private fun removeGroup() {
        manager.removeGroup(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {}
            override fun onFailure(reason: Int) {
                Log.w(TAG, "removeGroup failed, reason $reason")
            }
        })
    }

    private companion object {
        const val TAG = "WifiDirectFileTransfer"
    }
}
