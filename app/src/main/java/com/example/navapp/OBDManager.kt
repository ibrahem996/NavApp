// OBDManager.kt
package com.example.navapp

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class OBDManager(
    private val context: Context,
    private val listener: ObdDataListener
) {

    companion object {
        private const val REQUEST_BLUETOOTH_PERMISSIONS = 1001
    }

    private val obdHelper = ObdHelper(listener)

    // Check and request Bluetooth permissions
    fun checkAndRequestPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // For Android 12 and above
            val permissionsToRequest = mutableListOf<String>()
            if (ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.BLUETOOTH_CONNECT
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                permissionsToRequest.add(Manifest.permission.BLUETOOTH_CONNECT)
            }
            if (ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.BLUETOOTH_SCAN
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                permissionsToRequest.add(Manifest.permission.BLUETOOTH_SCAN)
            }
            if (permissionsToRequest.isNotEmpty()) {
                ActivityCompat.requestPermissions(
                    context as Activity,
                    permissionsToRequest.toTypedArray(),
                    REQUEST_BLUETOOTH_PERMISSIONS
                )
            } else {
                connect()
            }
        } else {
            // For Android versions below 12
            connect()
        }
    }

    // Handle permission results
    fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        if (requestCode == REQUEST_BLUETOOTH_PERMISSIONS) {
            if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                connect()
            } else {
                listener.onObdError("Bluetooth permissions are required to connect to the OBD-II adapter")
            }
        }
    }

    // Connect to the OBD-II device
    private fun connect() {
        obdHelper.connect()
    }

    // Disconnect from the OBD-II device
    fun disconnect() {
        obdHelper.disconnect()
    }
}
