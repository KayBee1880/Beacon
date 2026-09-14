package com.beacon.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.Context
import android.util.Log
import com.beacon.data.ConversationRepository
import com.beacon.data.Identity
import com.beacon.data.MessageRepository
import com.beacon.data.PeerRepository
import com.beacon.data.RelayEnvelopeRepository
import kotlinx.coroutines.CoroutineScope
import java.util.concurrent.ConcurrentHashMap

// Permission checks happen upstream, gating whether start() is ever called; see BlePermissions.kt.
@SuppressLint("MissingPermission")
class BlePeripheralRole(
    private val context: Context,
    private val scope: CoroutineScope,
    private val conversationRepository: ConversationRepository,
    private val messageRepository: MessageRepository,
    private val peerRepository: PeerRepository,
    private val relayEnvelopeRepository: RelayEnvelopeRepository,
    private val resolvedIdentityByDeviceAddress: () -> Map<String, String>
) {
    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager

    private var advertiser: BluetoothLeAdvertiser? = null
    private var gattServer: BluetoothGattServer? = null
    private var chatGattServer: ChatGattServer? = null
    private var txCharacteristic: BluetoothGattCharacteristic? = null

    private var publicKeyBytes: ByteArray = ByteArray(0)
    private var displayNameBytes: ByteArray = ByteArray(0)
    // Milestone 6 (D-030): served the same read-only way as the two fields above.
    private var encryptionPublicKeyBytes: ByteArray = ByteArray(0)
    private var encryptionPublicKeySignatureBytes: ByteArray = ByteArray(0)

    // Devices that have written ENABLE_NOTIFICATION_VALUE to TX's CCCD: notifying a
    // device that hasn't subscribed yet would silently go nowhere, so this is checked
    // before every notify attempt (see sendToDevice below).
    private val subscribedDeviceAddresses = ConcurrentHashMap.newKeySet<String>()

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartFailure(errorCode: Int) {
            Log.w(TAG, "Advertising failed to start, error code $errorCode")
        }
    }

    private val gattServerCallback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                subscribedDeviceAddresses.remove(device.address)
                chatGattServer?.onDeviceDisconnected(device)
            }
        }

        override fun onCharacteristicReadRequest(
            device: BluetoothDevice,
            requestId: Int,
            offset: Int,
            characteristic: BluetoothGattCharacteristic
        ) {
            val value = when (characteristic.uuid) {
                BeaconGattProfile.PUBLIC_KEY_CHARACTERISTIC_UUID -> publicKeyBytes
                BeaconGattProfile.DISPLAY_NAME_CHARACTERISTIC_UUID -> displayNameBytes
                BeaconGattProfile.ENCRYPTION_PUBLIC_KEY_CHARACTERISTIC_UUID -> encryptionPublicKeyBytes
                BeaconGattProfile.ENCRYPTION_PUBLIC_KEY_SIGNATURE_CHARACTERISTIC_UUID -> encryptionPublicKeySignatureBytes
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

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray
        ) {
            if (characteristic.uuid == BeaconGattProfile.RX_CHARACTERISTIC_UUID) {
                chatGattServer?.onWrite(device, value)
            }
            if (responseNeeded) {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, null)
            }
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray
        ) {
            if (descriptor.uuid == BeaconGattProfile.CLIENT_CHARACTERISTIC_CONFIG_UUID) {
                if (value.contentEquals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)) {
                    subscribedDeviceAddresses.add(device.address)
                } else {
                    subscribedDeviceAddresses.remove(device.address)
                }
            }
            if (responseNeeded) {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, null)
            }
        }
    }

    fun start(identity: Identity) {
        publicKeyBytes = identity.publicKey.toByteArray(Charsets.UTF_8)
        displayNameBytes = identity.displayName.toByteArray(Charsets.UTF_8)
        encryptionPublicKeyBytes = identity.encryptionPublicKey.toByteArray(Charsets.UTF_8)
        encryptionPublicKeySignatureBytes = identity.encryptionPublicKeySignature.toByteArray(Charsets.UTF_8)

        chatGattServer = ChatGattServer(
            identity = identity,
            conversationRepository = conversationRepository,
            messageRepository = messageRepository,
            peerRepository = peerRepository,
            relayEnvelopeRepository = relayEnvelopeRepository,
            resolvedIdentityByDeviceAddress = resolvedIdentityByDeviceAddress,
            scope = scope,
            sendToDevice = ::sendToDevice
        )

        gattServer = bluetoothManager.openGattServer(context, gattServerCallback)?.apply {
            addService(buildIdentityService())
            val messagingService = buildMessagingService()
            addService(messagingService)
            txCharacteristic = messagingService.getCharacteristic(BeaconGattProfile.TX_CHARACTERISTIC_UUID)
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
        chatGattServer = null
        txCharacteristic = null
        subscribedDeviceAddresses.clear()
    }

    // This 3-arg overload alone covers minSdk 26-34, same reasoning as BleCentralRole's
    // onCharacteristicRead: the platform's API 33+ overload delegates to it when unused.
    @Suppress("DEPRECATION")
    private fun sendToDevice(device: BluetoothDevice, bytes: ByteArray) {
        val characteristic = txCharacteristic ?: return
        if (device.address !in subscribedDeviceAddresses) {
            Log.w(TAG, "Not sending to ${device.address}: not subscribed to TX notifications")
            return
        }
        characteristic.setValue(bytes)
        gattServer?.notifyCharacteristicChanged(device, characteristic, false)
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
        service.addCharacteristic(
            BluetoothGattCharacteristic(
                BeaconGattProfile.ENCRYPTION_PUBLIC_KEY_CHARACTERISTIC_UUID,
                BluetoothGattCharacteristic.PROPERTY_READ,
                BluetoothGattCharacteristic.PERMISSION_READ
            )
        )
        service.addCharacteristic(
            BluetoothGattCharacteristic(
                BeaconGattProfile.ENCRYPTION_PUBLIC_KEY_SIGNATURE_CHARACTERISTIC_UUID,
                BluetoothGattCharacteristic.PROPERTY_READ,
                BluetoothGattCharacteristic.PERMISSION_READ
            )
        )
        return service
    }

    // Milestone 3 (docs/04 §5): RX is write-only (central → peripheral), TX is
    // notify-only (peripheral → central); see ChatGattServer for the actual protocol
    // this service's bytes carry.
    private fun buildMessagingService(): BluetoothGattService {
        val service = BluetoothGattService(
            BeaconGattProfile.MESSAGING_SERVICE_UUID,
            BluetoothGattService.SERVICE_TYPE_PRIMARY
        )
        service.addCharacteristic(
            BluetoothGattCharacteristic(
                BeaconGattProfile.RX_CHARACTERISTIC_UUID,
                BluetoothGattCharacteristic.PROPERTY_WRITE,
                BluetoothGattCharacteristic.PERMISSION_WRITE
            )
        )
        val tx = BluetoothGattCharacteristic(
            BeaconGattProfile.TX_CHARACTERISTIC_UUID,
            BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ
        )
        tx.addDescriptor(
            BluetoothGattDescriptor(
                BeaconGattProfile.CLIENT_CHARACTERISTIC_CONFIG_UUID,
                BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE
            )
        )
        service.addCharacteristic(tx)
        return service
    }

    private companion object {
        const val TAG = "BlePeripheralRole"
    }
}
