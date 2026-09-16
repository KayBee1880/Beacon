package com.beacon.wifidirect

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

// docs/08 §8: a real, separate platform gap from BlePermissions', not an oversight.
// Wi-Fi Direct discovery has required location permission since before NEARBY_WIFI_DEVICES
// existed at all (API 33); BLE's own move off location permission (API 31, see
// BlePermissions) doesn't cover this. Requested on demand when the user first taps the
// attach button, not folded into the app-wide gate BeaconApp already shows, since most
// users may never send an attachment at all.
object WifiDirectPermissions {

    fun required(): Array<String> =
        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU ->
                arrayOf(Manifest.permission.NEARBY_WIFI_DEVICES)
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
            else -> emptyArray()
        }

    fun allGranted(context: Context): Boolean =
        required().all { permission ->
            ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
        }
}
