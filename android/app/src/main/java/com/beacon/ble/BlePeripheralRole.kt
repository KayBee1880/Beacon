package com.beacon.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.Context
import android.util.Log
import com.beacon.data.Identity

// Permission checks happen upstream, gating whether start() is ever called; see BlePermissions.kt.
@SuppressLint("MissingPermission")
class BlePeripheralRole(private val context: Context) {

    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager

    private var advertiser: BluetoothLeAdvertiser? = null
    private var gattServer: BluetoothGattServer? = null

    private var publicKeyBytes: ByteArray = ByteArray(0)
    private var displayNameBytes: ByteArray = ByteArray(0)

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartFailure(errorCode: Int) {
            Log.w(TAG, "Advertising failed to start, error code $errorCode")
        }
    }

    private val gattServerCallback = object : BluetoothGattServerCallback() {
        override fun onCharacteristicReadRequest(
            device: BluetoothDevice,
            requestId: Int,
            offset: Int,
            characteristic: BluetoothGattCharacteristic
        ) {
            val value = when (characteristic.uuid) {
                BeaconGattProfile.PUBLIC_KEY_CHARACTERISTIC_UUID -> publicKeyBytes
                BeaconGattProfile.DISPLAY_NAME_CHARACTERISTIC_UUID -> displayNameBytes
                else -> null
            }
            if (value == null) {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_FAILURE, offset, null)
                return
            }
            if (offset > value.size) {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_INVALID_OFFSET, offset, null)
                return
            }
            gattServer?.sendResponse(
                device,
                requestId,
                BluetoothGatt.GATT_SUCCESS,
                offset,
                value.copyOfRange(offset, value.size)
            )
        }
    }

    fun start(identity: Identity) {
        publicKeyBytes = identity.publicKey.toByteArray(Charsets.UTF_8)
        displayNameBytes = identity.displayName.toByteArray(Charsets.UTF_8)

        gattServer = bluetoothManager.openGattServer(context, gattServerCallback)?.apply {
            addService(buildIdentityService())
        }

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
            .setConnectable(true)
            .setTimeout(0)
            .build()
        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addServiceUuid(BeaconGattProfile.ADVERTISING_UUID)
            .addServiceData(BeaconGattProfile.ADVERTISING_UUID, BeaconGattProfile.fingerprint(identity.publicKey))
            .build()

        advertiser = bluetoothManager.adapter?.bluetoothLeAdvertiser
        advertiser?.startAdvertising(settings, data, advertiseCallback)
    }

    fun stop() {
        advertiser?.stopAdvertising(advertiseCallback)
        advertiser = null
        gattServer?.close()
        gattServer = null
    }

    private fun buildIdentityService(): BluetoothGattService {
        val service = BluetoothGattService(
            BeaconGattProfile.IDENTITY_SERVICE_UUID,
            BluetoothGattService.SERVICE_TYPE_PRIMARY
        )
        service.addCharacteristic(
            BluetoothGattCharacteristic(
                BeaconGattProfile.PUBLIC_KEY_CHARACTERISTIC_UUID,
                BluetoothGattCharacteristic.PROPERTY_READ,
                BluetoothGattCharacteristic.PERMISSION_READ
            )
        )
        service.addCharacteristic(
            BluetoothGattCharacteristic(
                BeaconGattProfile.DISPLAY_NAME_CHARACTERISTIC_UUID,
                BluetoothGattCharacteristic.PROPERTY_READ,
                BluetoothGattCharacteristic.PERMISSION_READ
            )
        )
        return service
    }

    private companion object {
        const val TAG = "BlePeripheralRole"
    }
}
