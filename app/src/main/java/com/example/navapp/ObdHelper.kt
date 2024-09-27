// ObdHelper.kt
package com.example.navapp

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.util.Log
import java.io.IOException
import java.util.*
import kotlin.concurrent.thread

class ObdHelper(
    private val listener: ObdDataListener
) {

    private val TAG = "ObdHelper"
    private var bluetoothSocket: BluetoothSocket? = null
    private var isConnected = false

    // UUID for SPP (Serial Port Profile)
    private val OBD_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

    // Connect to the OBD-II device
    @SuppressLint("MissingPermission")
    fun connect() {
        val bluetoothAdapter = BluetoothAdapter.getDefaultAdapter()
        if (bluetoothAdapter == null) {
            listener.onObdError("Bluetooth not supported on this device.")
            return
        }

        if (!bluetoothAdapter.isEnabled) {
            listener.onObdError("Bluetooth is not enabled.")
            return
        }

        val pairedDevices: Set<BluetoothDevice> = bluetoothAdapter.bondedDevices

        if (pairedDevices.isEmpty()) {
            listener.onObdError("No paired Bluetooth devices found.")
            return
        }

        thread(start = true) {
            var connected = false
            for (device in pairedDevices) {
                try {
                    bluetoothSocket = device.createRfcommSocketToServiceRecord(OBD_UUID)
                    bluetoothSocket?.connect()
                    isConnected = true
                    connected = true
                    Log.d(TAG, "Connected to OBD device: ${device.name}")
                    startSpeedMonitoring()
                    break
                } catch (e: IOException) {
                    Log.e(TAG, "Failed to connect to device: ${device.name}", e)
                    // Try the next device
                }
            }
            if (!connected) {
                listener.onObdError("Failed to connect to any OBD device.")
            }
        }
    }

    // Start monitoring vehicle speed
    private fun startSpeedMonitoring() {
        thread(start = true) {
            while (isConnected) {
                getVehicleSpeed(
                    onResult = { speed ->
                        listener.onSpeedUpdated(speed.toFloat())
                    },
                    onError = { errorMessage ->
                        listener.onObdError(errorMessage)
                    }
                )
                Thread.sleep(1000) // Adjust the frequency as needed
            }
        }
    }

    // Retrieve the vehicle speed
    private fun getVehicleSpeed(onResult: (Int) -> Unit, onError: ((String) -> Unit)? = null) {
        if (!isConnected || bluetoothSocket == null) {
            onError?.invoke("Not connected to OBD device.")
            return
        }

        try {
            val outputStream = bluetoothSocket!!.outputStream
            val inputStream = bluetoothSocket!!.inputStream

            // Send the PID for vehicle speed (010D)
            val command = "010D\r"
            outputStream.write(command.toByteArray())

            // Read the response
            val buffer = ByteArray(1024)
            var bytesRead: Int

            // Read until '>' character is found indicating the end of the response
            val responseBuilder = StringBuilder()
            while (true) {
                bytesRead = inputStream.read(buffer)
                val responsePart = String(buffer, 0, bytesRead)
                responseBuilder.append(responsePart)
                if (responsePart.contains('>')) {
                    break
                }
            }

            val response = responseBuilder.toString()
            Log.d(TAG, "Raw OBD Response: $response")

            val speed = parseSpeed(response)
            onResult(speed)
        } catch (e: IOException) {
            onError?.invoke("Failed to read vehicle speed: ${e.message}")
            Log.e(TAG, "Error reading vehicle speed", e)
        }
    }

    // Disconnect from the OBD-II device
    fun disconnect() {
        try {
            isConnected = false
            bluetoothSocket?.close()
            Log.d(TAG, "Disconnected from OBD device.")
        } catch (e: IOException) {
            Log.e(TAG, "Error disconnecting from OBD device", e)
        }
    }

    // Parse the speed from the OBD response
    private fun parseSpeed(response: String): Int {
        // OBD response format: "41 0D XX"
        // Where XX is the speed in km/h in hexadecimal

        val regex = Regex("41 0D ([0-9A-Fa-f]{2})")
        val matchResult = regex.find(response)

        return if (matchResult != null) {
            val speedHex = matchResult.groupValues[1]
            Integer.parseInt(speedHex, 16)
        } else {
            0
        }
    }
}
