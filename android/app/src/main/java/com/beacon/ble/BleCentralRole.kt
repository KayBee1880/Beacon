package com.beacon.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.util.Base64
import com.beacon.crypto.IdentityKeyStore
import com.beacon.diagnostics.BeaconLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

// Permission checks happen upstream, gating whether start() is ever called; see BlePermissions.kt.
@SuppressLint("MissingPermission")
class BleCentralRole(
    private val context: Context,
    private val scope: CoroutineScope,
    // encryptionPublicKey is null if the peer doesn't yet expose one (pre-Milestone-6
    // build) or its signature failed verification (docs/07 §4); a resolve is not failed
    // outright for this, the peer's core identity already verified, only relaying to
    // them stays unavailable until a resolve succeeds with a verified key.
    private val onPeerResolved: suspend (
        publicKey: String,
        displayName: String,
        deviceAddress: String,
        encryptionPublicKey: String?
    ) -> Unit
) {
    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager

    // Guards against opening a second resolve connection while one's already in flight for a device.
    private val resolvingDevices = ConcurrentHashMap<String, Boolean>()

    private val _rssiByPeerId = MutableStateFlow<Map<String, Int>>(emptyMap())
    val rssiByPeerId: StateFlow<Map<String, Int>> = _rssiByPeerId

    // D-018: which identity public key a given BLE device address resolved to, kept in
    // memory only (never persisted, a device's current BLE address is transient
    // background data, same treatment as RSSI above, not identity). This is what lets
    // BlePeripheralRole verify a chat handshake's signature against the right peer
    // without a second, redundant resolve connection.
    private val _identityPublicKeyByDeviceAddress = MutableStateFlow<Map<String, String>>(emptyMap())
    val identityPublicKeyByDeviceAddress: StateFlow<Map<String, String>> = _identityPublicKeyByDeviceAddress

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device ?: return
            if (resolvingDevices.putIfAbsent(device.address, true) != null) return
            resolveIdentity(device, result.rssi)
        }

        override fun onScanFailed(errorCode: Int) {
            BeaconLog.w(TAG, "Scan failed to start, error code $errorCode")
        }
    }

    fun start() {
        val filter = ScanFilter.Builder()
            .setServiceUuid(BeaconGattProfile.ADVERTISING_UUID)
            .build()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_BALANCED)
            .build()
        bluetoothManager.adapter?.bluetoothLeScanner?.startScan(listOf(filter), settings, scanCallback)
    }

    fun stop() {
        bluetoothManager.adapter?.bluetoothLeScanner?.stopScan(scanCallback)
        resolvingDevices.clear()
        _rssiByPeerId.value = emptyMap()
        _identityPublicKeyByDeviceAddress.value = emptyMap()
    }

    private fun resolveIdentity(device: BluetoothDevice, rssi: Int) {
        var resolvedPublicKey: String? = null
        var resolvedDisplayName: String? = null
        var resolvedEncryptionPublicKey: String? = null

        val gattCallback = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                when (newState) {
                    BluetoothProfile.STATE_CONNECTED -> gatt.discoverServices()
                    BluetoothProfile.STATE_DISCONNECTED -> {
                        resolvingDevices.remove(device.address)
                        gatt.close()
                    }
                }
            }

            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                val characteristic = gatt
                    .getService(BeaconGattProfile.IDENTITY_SERVICE_UUID)
                    ?.getCharacteristic(BeaconGattProfile.PUBLIC_KEY_CHARACTERISTIC_UUID)
                if (characteristic == null) {
                    gatt.disconnect()
                } else {
                    gatt.readCharacteristic(characteristic)
                }
            }

            // This 3-arg overload alone covers minSdk 26-34: the platform's API 33+ 4-arg override delegates to it.
            @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
            override fun onCharacteristicRead(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                status: Int
            ) {
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    gatt.disconnect()
                    return
                }
                val value = characteristic.value ?: run {
                    gatt.disconnect()
                    return
                }
                when (characteristic.uuid) {
                    BeaconGattProfile.PUBLIC_KEY_CHARACTERISTIC_UUID -> {
                        resolvedPublicKey = String(value, StandardCharsets.UTF_8)
                        readNext(gatt, BeaconGattProfile.DISPLAY_NAME_CHARACTERISTIC_UUID)
                    }
                    BeaconGattProfile.DISPLAY_NAME_CHARACTERISTIC_UUID -> {
                        resolvedDisplayName = String(value, StandardCharsets.UTF_8)
                        readNext(gatt, BeaconGattProfile.ENCRYPTION_PUBLIC_KEY_CHARACTERISTIC_UUID)
                    }
                    BeaconGattProfile.ENCRYPTION_PUBLIC_KEY_CHARACTERISTIC_UUID -> {
                        resolvedEncryptionPublicKey = String(value, StandardCharsets.UTF_8)
                        readNext(gatt, BeaconGattProfile.ENCRYPTION_PUBLIC_KEY_SIGNATURE_CHARACTERISTIC_UUID)
                    }
                    BeaconGattProfile.ENCRYPTION_PUBLIC_KEY_SIGNATURE_CHARACTERISTIC_UUID -> {
                        val publicKey = resolvedPublicKey
                        val displayName = resolvedDisplayName
                        val encryptionPublicKey = resolvedEncryptionPublicKey
                        val signatureBase64 = String(value, StandardCharsets.UTF_8)
                        if (publicKey != null && displayName != null) {
                            // D-030: verified here, once, at resolve time, not re-verified
                            // by every later caller that reads it back off the Peer row.
                            val verifiedEncryptionPublicKey = encryptionPublicKey?.takeIf {
                                verifyEncryptionPublicKey(publicKey, it, signatureBase64)
                            }
                            _rssiByPeerId.update { it + (publicKey to rssi) }
                            _identityPublicKeyByDeviceAddress.update { it + (device.address to publicKey) }
                            scope.launch {
                                onPeerResolved(publicKey, displayName, device.address, verifiedEncryptionPublicKey)
                            }
                        }
                        gatt.disconnect()
                    }
                }
            }
        }

        device.connectGatt(context, false, gattCallback)
    }

    private fun readNext(gatt: BluetoothGatt, characteristicUuid: UUID) {
        val characteristic = gatt
            .getService(BeaconGattProfile.IDENTITY_SERVICE_UUID)
            ?.getCharacteristic(characteristicUuid)
        if (characteristic == null) {
            gatt.disconnect()
        } else {
            gatt.readCharacteristic(characteristic)
        }
    }

    private fun verifyEncryptionPublicKey(peerPublicKey: String, encryptionPublicKeyBase64: String, signatureBase64: String): Boolean =
        try {
            val encryptionPublicKeyBytes = Base64.decode(encryptionPublicKeyBase64, Base64.NO_WRAP)
            val signatureBytes = Base64.decode(signatureBase64, Base64.NO_WRAP)
            IdentityKeyStore.verify(peerPublicKey, encryptionPublicKeyBytes, signatureBytes)
        } catch (e: Exception) {
            BeaconLog.w(TAG, "Malformed encryption key or signature", e)
            false
        }

    private companion object {
        const val TAG = "BleCentralRole"
    }
}
