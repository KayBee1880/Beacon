package com.beacon.ble

import android.os.ParcelUuid
import java.security.MessageDigest
import java.util.UUID

// Two tiers of UUID doing two different jobs, see docs/03-milestone-2-ble-discovery.md §2.
object BeaconGattProfile {

    val ADVERTISING_UUID: ParcelUuid = ParcelUuid.fromString("0000fdf0-0000-1000-8000-00805f9b34fb")

    val IDENTITY_SERVICE_UUID: UUID = UUID.fromString("cbd0b8c8-bebc-4f7d-976d-089689ef90f5")
    val PUBLIC_KEY_CHARACTERISTIC_UUID: UUID = UUID.fromString("a9a065e1-ca6c-4bd4-8c3d-de81bf609348")
    val DISPLAY_NAME_CHARACTERISTIC_UUID: UUID = UUID.fromString("9623d7d0-482a-4b06-b375-8db41e6b9868")

    // Milestone 3 (docs/04): one service for the handshake and the encrypted chat itself,
    // exposed only over the long-lived per-chat connection docs/04 §5 describes, never
    // during the brief Milestone 2 resolve connection.
    val MESSAGING_SERVICE_UUID: UUID = UUID.fromString("e2d4a716-4bb2-4c1e-8f3a-7c9d5b6e1a02")

    // Central writes here: outgoing handshake frames and outgoing encrypted messages,
    // see docs/04 §5's write-plus-notify decision (D-016).
    val RX_CHARACTERISTIC_UUID: UUID = UUID.fromString("9b3f0a5c-2e91-4d7a-9c4b-6f1e8a2d5c31")

    // Peripheral notifies here: its own handshake frame and its own outgoing messages,
    // pushed to whichever central is connected and has enabled notifications.
    val TX_CHARACTERISTIC_UUID: UUID = UUID.fromString("51a7d6e4-8c3f-4b29-b6e5-2d9a4f7c1e08")

    // The Bluetooth SIG's own assigned UUID for the Client Characteristic Configuration
    // Descriptor, not one of ours: this is how a central enables notifications on
    // TX_CHARACTERISTIC_UUID. Every GATT stack recognizes this exact value.
    val CLIENT_CHARACTERISTIC_CONFIG_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    private const val FINGERPRINT_LENGTH_BYTES = 16

    /** First 16 bytes of SHA-256(publicKey), small enough to fit the advertisement, see §2. */
    fun fingerprint(publicKeyBase64: String): ByteArray =
        MessageDigest.getInstance("SHA-256")
            .digest(publicKeyBase64.toByteArray(Charsets.UTF_8))
            .copyOf(FINGERPRINT_LENGTH_BYTES)
}
