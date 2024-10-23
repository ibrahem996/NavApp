// MapsActivity.kt
package com.example.navapp

import android.annotation.SuppressLint
import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.animation.LinearInterpolator
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.navapp.databinding.ActivityMapsBinding
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.*
import okhttp3.*
import org.json.JSONObject
import java.io.IOException

class MapsActivity : AppCompatActivity(), OnMapReadyCallback, SensorEventListener, ObdDataListener {

    private var currentLatLng: LatLng? = null
    private lateinit var mMap: GoogleMap
    private lateinit var calculationManager: CalculationManager
    private lateinit var binding: ActivityMapsBinding
    private lateinit var navigationInfoTextView: TextView
    private lateinit var speedTextView: TextView
    private var route: List<LatLng> = mutableListOf()
    private var steps: List<Step> = mutableListOf()
    private var marker: Marker? = null
    private val handler = Handler(Looper.getMainLooper())

    private lateinit var sensorManager: SensorManager
    private var accelerometer: Sensor? = null
    private var magnetometer: Sensor? = null
    private var gravity: FloatArray? = null
    private var geomagnetic: FloatArray? = null
    private var rotationVectorSensor: Sensor? = null


    companion object {
        private const val REQUEST_CODE_LOCATION_PERMISSION = 100
        private val client = OkHttpClient()
    }

    private var currentAzimuth: Float = 0f
    private var currentTilt = 0f  // Initial tilt
    private var currentBearing = 0f  // Initial bearing

    private val ALPHA = 0.25f

    // OBD Manager and current speed
    private lateinit var obdManager: OBDManager
    private var currentSpeed: Float = 0f

    // Low-Pass Filter function to smooth sensor data
    private fun lowPass(input: FloatArray, output: FloatArray?): FloatArray {
        if (output == null) return input.clone()
        for (i in input.indices) {
            output[i] = output[i] + ALPHA * (input[i] - output[i])
        }
        return output
    }


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMapsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Initialize the CalculationManager
        calculationManager = CalculationManager()

        // Initialize SensorManager
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        magnetometer = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
        rotationVectorSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

        // Register listeners for available sensors
        accelerometer?.also {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        } ?: Log.e("MapsActivity", "Accelerometer not available")

        magnetometer?.also {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        } ?: Log.e("MapsActivity", "Magnetometer not available")

        rotationVectorSensor?.also {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        } ?: Log.e("MapsActivity", "Rotation Vector Sensor not available")

        // Initialize UI components
        speedTextView = findViewById(R.id.speed_text_view)
        navigationInfoTextView = findViewById(R.id.navigation_info)

        // Initialize map fragment
        val mapFragment = supportFragmentManager.findFragmentById(R.id.map) as? SupportMapFragment
        mapFragment?.getMapAsync(this) ?: run {
            Toast.makeText(this, "Map fragment not found.", Toast.LENGTH_SHORT).show()
            Log.e("MapsActivity", "Map fragment not found.")
        }

        // Navigation button listener
        val navigateButton: Button? = findViewById(R.id.navigation_button)
        navigateButton?.setOnClickListener {
            if (route.isNotEmpty()) {
                startNavigation()
            } else {
                Toast.makeText(this, "No route available. Please select start and destination points.", Toast.LENGTH_SHORT).show()
            }
        }
        // Initialize OBDManager
        obdManager = OBDManager(this, this)
        obdManager.checkAndRequestPermissions()
    }


    override fun onSensorChanged(event: SensorEvent) {
        if (!::mMap.isInitialized) {
            Log.d("MapsActivity", "mMap is not initialized yet in onSensorChanged.")
            return
        }

        when (event.sensor.type) {
            Sensor.TYPE_ROTATION_VECTOR -> {
                val rotationMatrix = FloatArray(9)
                SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
                val orientation = FloatArray(3)
                SensorManager.getOrientation(rotationMatrix, orientation)
                val azimuthInRadians = orientation[0]

                // Convert to degrees and normalize
                var azimuthInDegrees = Math.toDegrees(azimuthInRadians.toDouble()).toFloat()
                azimuthInDegrees = (azimuthInDegrees + 360) % 360  // Ensure value is between 0° and 360°

                // Account for magnetic declination
                val latitude = currentLatLng?.latitude?.toFloat() ?: 0f
                val longitude = currentLatLng?.longitude?.toFloat() ?: 0f
                val geoField = GeomagneticField(
                    latitude,
                    longitude,
                    0f,
                    System.currentTimeMillis()
                )
                val declination = geoField.declination
                var trueNorthAzimuth = (azimuthInDegrees + declination + 360) % 360

                // Handle angle wrap-around
                var deltaAzimuth = trueNorthAzimuth - currentAzimuth
                if (deltaAzimuth > 180) deltaAzimuth -= 360
                else if (deltaAzimuth < -180) deltaAzimuth += 360

                // Apply low-pass filter using class-level ALPHA
                trueNorthAzimuth = (currentAzimuth + ALPHA * deltaAzimuth + 360) % 360

                // Update the current azimuth
                currentAzimuth = trueNorthAzimuth

                // Update the camera bearing
                currentBearing = trueNorthAzimuth
                val cameraPosition = CameraPosition.Builder()
                    .target(mMap.cameraPosition.target)
                    .zoom(mMap.cameraPosition.zoom)
                    .tilt(currentTilt)
                    .bearing(currentBearing)
                    .build()

                // Only update camera if the change is significant (e.g., more than 1°)
                if (kotlin.math.abs(deltaAzimuth) > 1) {
                    mMap.animateCamera(CameraUpdateFactory.newCameraPosition(cameraPosition))
                }

                // Log the azimuth
                Log.d("MapsActivity", "Azimuth (Rotation Vector): $trueNorthAzimuth°")
            }
            else -> {
                // Optionally handle other sensor types or ignore them
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // Handle sensor accuracy changes if necessary
    }


    override fun onPause() {
        super.onPause()
        sensorManager.unregisterListener(this)
        obdManager.disconnect()
        handler.removeCallbacksAndMessages(null)  // Prevent memory leaks by removing all callbacks and messages
        Log.d("MapsActivity", "onPause: Unregistered sensor listeners and removed handler callbacks.")
    }


    override fun onResume() {
        super.onResume()
        // Sensors are already registered in onCreate
        obdManager.checkAndRequestPermissions()
        Log.d("MapsActivity", "onResume: Checked and requested OBD permissions.")
    }

    private var startLocation: LatLng? = null
    private var destination: LatLng? = null

    override fun onMapReady(googleMap: GoogleMap) {
        mMap = googleMap

        // Map settings
        mMap.uiSettings.isZoomControlsEnabled = true
        mMap.isTrafficEnabled = true
        mMap.uiSettings.isTiltGesturesEnabled = true
        mMap.uiSettings.isRotateGesturesEnabled = true

        // Adding map click listeners
        mMap.setOnMapClickListener { latLng ->
            handleMapClick(latLng)
        }

        // Optional: Long click to reset selections
        mMap.setOnMapLongClickListener {
            clearSelections()
        }

        // Set initial camera position (e.g., Haifa)
        val initialPosition = LatLng(32.794044, 34.989571) // Haifa
        val cameraPosition = CameraPosition.Builder()
            .target(initialPosition)
            .zoom(10f)
            .tilt(0f)
            .bearing(0f)
            .build()

        mMap.moveCamera(CameraUpdateFactory.newCameraPosition(cameraPosition))
    }


    private fun handleMapClick(latLng: LatLng) {
        if (startLocation == null) {
            // startLocation
            startLocation = latLng
            mMap.addMarker(MarkerOptions().position(latLng).title("Start Point"))
            Toast.makeText(this, "נקודת התחלה נבחרה. לחץ כדי לבחור נקודת סיום.", Toast.LENGTH_SHORT).show()
        } else if (destination == null) {
            // destination
            destination = latLng
            mMap.addMarker(MarkerOptions().position(latLng).title("Destination"))
            Toast.makeText(this, "נקודת סיום נבחרה.", Toast.LENGTH_SHORT).show()

            // the two points chose
            startLocation?.let { start ->
                destination?.let { dest ->
                    getDirections(start, dest)
                }
            }
        } else {
            Toast.makeText(this, "כבר בחרת נקודת התחלה וסיום. לחץ לחיצה ארוכה כדי לאפס.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun clearSelections() {
        startLocation = null
        destination = null
        mMap.clear()
        route = mutableListOf()
        steps = mutableListOf()
        marker = null
        handler.removeCallbacksAndMessages(null)  // Stop any ongoing simulations
        Toast.makeText(this, "הבחירות אופסו. לחץ כדי לבחור נקודת התחלה.", Toast.LENGTH_SHORT).show()
        Log.d("MapsActivity", "clearSelections: All selections cleared and handlers reset.")
    }


    private fun getDirections(origin: LatLng, destination: LatLng) {
        val apiKey = BuildConfig.MAPS_API_KEY
        val url =
            "https://maps.googleapis.com/maps/api/directions/json?origin=${origin.latitude},${origin.longitude}&destination=${destination.latitude},${destination.longitude}&key=$apiKey"
        val request = Request.Builder().url(url).build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e("MapsActivity", "Failed to fetch directions: ${e.message}")
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (it.isSuccessful) {
                        val jsonResponse = it.body?.string()
                        val jsonObject = JSONObject(jsonResponse)
                        route = parseDirections(jsonObject)
                        steps = parseSteps(jsonObject)
                        runOnUiThread {
                            drawPolyline(route)
                        }
                    } else {
                        Log.e("MapsActivity", "Error fetching directions: HTTP ${response.code}")
                    }
                }
            }
        })
    }

    private fun parseDirections(jsonObject: JSONObject): List<LatLng> {
        val route = mutableListOf<LatLng>()
        val routes = jsonObject.getJSONArray("routes")
        if (routes.length() > 0) {
            val legs = routes.getJSONObject(0).getJSONArray("legs")
            for (i in 0 until legs.getJSONObject(0).getJSONArray("steps").length()) {
                val step = legs.getJSONObject(0).getJSONArray("steps").getJSONObject(i)
                val polyline = step.getJSONObject("polyline").getString("points")
                route.addAll(decodePolyline(polyline))
            }
        }
        return route
    }

    private fun parseSteps(jsonObject: JSONObject): List<Step> {
        val steps = mutableListOf<Step>()
        val routes = jsonObject.getJSONArray("routes")
        if (routes.length() > 0) {
            val legs = routes.getJSONObject(0).getJSONArray("legs")
            for (i in 0 until legs.getJSONObject(0).getJSONArray("steps").length()) {
                val step = legs.getJSONObject(0).getJSONArray("steps").getJSONObject(i)
                val distance = step.getJSONObject("distance").getString("text")
                val instruction = step.getString("html_instructions")
                steps.add(Step(instruction, distance))
            }
        }
        return steps
    }

    private fun drawPolyline(route: List<LatLng>) {
        val polylineOptions = PolylineOptions().addAll(route).width(10f)
            .color(ContextCompat.getColor(this, R.color.purple_500))
        mMap.addPolyline(polylineOptions)
    }

    private fun decodePolyline(encoded: String): List<LatLng> {
        val poly = ArrayList<LatLng>()
        var index = 0
        var lat = 0
        var lng = 0
        val len = encoded.length
        while (index < len) {
            var b: Int
            var shift = 0
            var result = 0
            do {
                b = encoded[index++].code - 63
                result = result or (b and 0x1f shl shift)
                shift += 5
            } while (b >= 0x20)
            val dlat = if (result and 1 != 0) (result shr 1).inv() else result shr 1
            lat += dlat

            shift = 0
            result = 0
            do {
                b = encoded[index++].code - 63
                result = result or (b and 0x1f shl shift)
                shift += 5
            } while (b >= 0x20)
            val dlng = if (result and 1 != 0) (result shr 1).inv() else result shr 1
            lng += dlng

            poly.add(LatLng(lat / 1E5, lng / 1E5))
        }
        return poly
    }

    // Function to calculate the distance between two LatLng points
    private fun calculateDistance(point1: LatLng, point2: LatLng): Float {
        val results = FloatArray(1)
        Location.distanceBetween(
            point1.latitude,
            point1.longitude,
            point2.latitude,
            point2.longitude,
            results
        )
        return results[0]  // Return distance in meters
    }

    private fun startNavigation() {
        if (route.isEmpty()) {
            Toast.makeText(this, "No route available. Please select start and destination points.", Toast.LENGTH_SHORT).show()
            return
        }
        // Initialize simulation variables
        var routeIndex = 0
        var currentLatLng: LatLng? = route.firstOrNull()

        // Set initial marker position
        currentLatLng?.let {
            marker = mMap.addMarker(MarkerOptions().position(it).title("Simulated Location"))
        } ?: run {
            Toast.makeText(this, "Invalid start location.", Toast.LENGTH_SHORT).show()
            return
        }
        // Convert speed from km/h to m/s (assuming currentSpeed is in km/h)
        val simulationSpeed = (currentSpeed.takeIf { it > 0 } ?: 70f) / 3.6f  // Default speed 10 km/h if speed is 0
        Log.d("Simulation", "CurrentAzimuth in start Navigation: $currentAzimuth")
        Log.d("Simulation", "Simulation speed: $simulationSpeed m/s")

        // Apply initial camera tilt and bearing
        currentTilt = 60f
        val cameraPosition = CameraPosition.Builder()
            .target(currentLatLng)
            .zoom(mMap.cameraPosition.zoom)
            .tilt(currentTilt)  // Apply tilt
            .bearing(currentBearing)  // Apply bearing
            .build()
        mMap.animateCamera(CameraUpdateFactory.newCameraPosition(cameraPosition))

        // Initialize time
        val startTimestamp = System.currentTimeMillis()
        calculationManager.updateTimestamp(startTimestamp)

        // Create a runnable for continuous location update
        val navigationRunnable = object : Runnable {
            override fun run() {
                if (routeIndex < route.size - 1) {  // Ensure there's a next point
                    val currentPoint = route[routeIndex]
                    val nextPoint = route[routeIndex + 1]
                    // Log.d("Simulation", "Current tilt: $currentTilt")

                    // Use currentAzimuth from sensors
                    val angle = currentAzimuth.toDouble()

                    // Calculate time elapsed since last update
                    val currentTimestamp = System.currentTimeMillis()
                    val timeElapsed = calculationManager.timeElapsedSinceLastUpdate(currentTimestamp)

                    // Update last timestamp
                    calculationManager.updateTimestamp(currentTimestamp)
                    Log.d("Simulation", "CurrentAzimuth in startNavigation: $currentAzimuth")
                    Log.d("Simulation", "Simulation speed: $simulationSpeed m/s")
                    onSpeedUpdated(simulationSpeed * 3.6f)

                    // Calculate new position using CalculationManager
                    val newPositionPair = calculationManager.calculateNewPosition(
                        latPrev = currentLatLng?.latitude ?: currentPoint.latitude,
                        lonPrev = currentLatLng?.longitude ?: currentPoint.longitude,
                        speed = simulationSpeed.toDouble(),  // Speed in m/s
                        angle = angle,   // Using device's azimuth
                        timeElapsed = timeElapsed
                    )

                    // Update current position
                    currentLatLng = LatLng(newPositionPair.first, newPositionPair.second)

                    // Animate marker movement for smooth transition
                    currentLatLng?.let { newLatLng ->
                        marker?.let { animateMarker(it, newLatLng) }
                    }

                    // Move camera to follow the marker smoothly
                    currentLatLng?.let { latLng ->
                        val newCameraPosition = CameraPosition.Builder()
                            .target(latLng)
                            .zoom(mMap.cameraPosition.zoom)
                            .tilt(currentTilt)
                            .bearing(currentBearing)
                            .build()
                        mMap.animateCamera(CameraUpdateFactory.newCameraPosition(newCameraPosition), 500, null)  // Animate over 500ms
                    } ?: run {
                        Log.e("Simulation", "currentLatLng is null. Cannot update camera.")
                    }

                    // Check if the marker is close to the next point
                    val distanceToNextPoint = calculateDistance(currentLatLng!!, nextPoint)
                    if (distanceToNextPoint < 10) {  // Threshold in meters
                        routeIndex++  // Move to the next segment
                    }

                    // Schedule next update
                    handler.postDelayed(this, 100)  // Update every 100 milliseconds for real-time updates
                } else {
                    // Final segment
                    Log.d("Simulation", "Final point reached.")

                    // Optionally, remove the marker or perform other cleanup
                    marker?.remove()
                    Toast.makeText(this@MapsActivity, "Navigation simulation completed.", Toast.LENGTH_SHORT).show()

                    // Stop the simulation
                    handler.removeCallbacks(this)
                }
            }
        }

        // Start navigation simulation
        handler.post(navigationRunnable)
    }


    private fun animateMarker(marker: Marker, toPosition: LatLng) {
        val startPosition = marker.position
        val duration = 500L  // Duration in milliseconds (e.g., 0.5 seconds)
        val interpolator = LinearInterpolator()
        val startTime = SystemClock.uptimeMillis()

        handler.post(object : Runnable {
            override fun run() {
                val elapsed = SystemClock.uptimeMillis() - startTime
                val t = (elapsed / duration.toFloat()).coerceIn(0f, 1f)
                val factor = interpolator.getInterpolation(t)

                val lat = factor * toPosition.latitude + (1 - factor) * startPosition.latitude
                val lng = factor * toPosition.longitude + (1 - factor) * startPosition.longitude
                marker.position = LatLng(lat, lng)

                if (t < 1.0f) {
                    handler.postDelayed(this, 16)  // Approximately 60 FPS
                }
            }
        })
    }


    // ObdDataListener implementation
    @SuppressLint("SetTextI18n")
    override fun onSpeedUpdated(speed: Float) {
        currentSpeed = speed
        runOnUiThread {
            speedTextView.text = "$speed\nkm/h"        }
    }

    override fun onObdError(errorMessage: String) {
        runOnUiThread {
            Toast.makeText(this, "OBD Error: $errorMessage", Toast.LENGTH_LONG).show()
        }
    }

    // Pass onRequestPermissionsResult to OBDManager
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        obdManager.onRequestPermissionsResult(requestCode, permissions, grantResults)
    }

}

data class Step(val description: String, val distance: String)
