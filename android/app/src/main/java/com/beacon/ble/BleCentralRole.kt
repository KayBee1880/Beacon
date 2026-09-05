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
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap

// Permission checks happen upstream, gating whether start() is ever called — see BlePermissions.kt.
@SuppressLint("MissingPermission")
class BleCentralRole(
    private val context: Context,
    private val scope: CoroutineScope,
    private val onPeerResolved: suspend (publicKey: String, displayName: String) -> Unit
) {
    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager

    // Guards against opening a second resolve connection while one's already in flight for a device.
    private val resolvingDevices = ConcurrentHashMap<String, Boolean>()

    private val _rssiByPeerId = MutableStateFlow<Map<String, Int>>(emptyMap())
    val rssiByPeerId: StateFlow<Map<String, Int>> = _rssiByPeerId

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device ?: return
            if (resolvingDevices.putIfAbsent(device.address, true) != null) return
            resolveIdentity(device, result.rssi)
        }

        override fun onScanFailed(errorCode: Int) {
            Log.w(TAG, "Scan failed to start, error code $errorCode")
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
    }

    private fun resolveIdentity(device: BluetoothDevice, rssi: Int) {
        var resolvedPublicKey: String? = null

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
            @Suppress("DEPRECATION")
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
                        val displayNameCharacteristic = gatt
                            .getService(BeaconGattProfile.IDENTITY_SERVICE_UUID)
                            ?.getCharacteristic(BeaconGattProfile.DISPLAY_NAME_CHARACTERISTIC_UUID)
                        if (displayNameCharacteristic == null) {
                            gatt.disconnect()
                        } else {
                            gatt.readCharacteristic(displayNameCharacteristic)
                        }
                    }
                    BeaconGattProfile.DISPLAY_NAME_CHARACTERISTIC_UUID -> {
                        val publicKey = resolvedPublicKey
                        val displayName = String(value, StandardCharsets.UTF_8)
                        if (publicKey != null) {
                            _rssiByPeerId.update { it + (publicKey to rssi) }
                            scope.launch { onPeerResolved(publicKey, displayName) }
                        }
                        gatt.disconnect()
                    }
                }
            }
        }

        device.connectGatt(context, false, gattCallback)
    }

    private companion object {
        const val TAG = "BleCentralRole"
    }
}
