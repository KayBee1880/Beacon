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

    private const val FINGERPRINT_LENGTH_BYTES = 16

    /** First 16 bytes of SHA-256(publicKey), small enough to fit the advertisement, see §2. */
    fun fingerprint(publicKeyBase64: String): ByteArray =
        MessageDigest.getInstance("SHA-256")
            .digest(publicKeyBase64.toByteArray(Charsets.UTF_8))
            .copyOf(FINGERPRINT_LENGTH_BYTES)
}
