package com.example.navapp

import android.Manifest
import android.content.pm.PackageManager
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
import android.text.Html
import android.util.Log
import android.view.animation.LinearInterpolator
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.text.HtmlCompat
import com.example.navapp.databinding.ActivityMapsBinding
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException

class MapsActivity : AppCompatActivity(), OnMapReadyCallback, SensorEventListener {

    private var currentLatLng: LatLng? = null
    private lateinit var mMap: GoogleMap
    private lateinit var binding: ActivityMapsBinding
    private lateinit var navigationInfoTextView: TextView
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
    private val orientation = FloatArray(3)
    private val rotationMatrix = FloatArray(9)

    companion object {
        private const val REQUEST_CODE_LOCATION_PERMISSION = 100
        private val client = OkHttpClient()
    }

    private var currentAzimuth: Float = 0f
    private var currentTilt = 0f  // Initial tilt
    private var currentBearing = 0f  // Initial bearing

    private val ALPHA = 0.25f

    // Low-Pass Filter function to smooth sensor data
    private fun lowPass(input: FloatArray, output: FloatArray?): FloatArray {
        if (output == null) return input.clone()
        for (i in input.indices) {
            output[i] = output[i] + ALPHA * (input[i] - output[i])
        }
        return output
    }

    // Adjust the tilt of the camera (vertical rotation)
    private fun adjustTilt(tiltChange: Float) {
        currentTilt = (currentTilt + tiltChange).coerceIn(0f, 60f)  // Limit tilt between 0 and 60 degrees
        Log.d("MapTilt", "Adjusting tilt to: $currentTilt")
        val cameraPosition = CameraPosition.Builder()
            .target(mMap.cameraPosition.target)
            .zoom(mMap.cameraPosition.zoom)
            .tilt(currentTilt)
            .bearing(currentBearing)
            .build()

        mMap.animateCamera(CameraUpdateFactory.newCameraPosition(cameraPosition))
    }

    // Adjust the bearing of the camera (horizontal rotation)
    private fun adjustBearing(bearingChange: Float) {
        currentBearing = (currentBearing + bearingChange) % 360  // Bearing between 0 and 360 degrees

        val cameraPosition = CameraPosition.Builder()
            .target(mMap.cameraPosition.target)
            .zoom(mMap.cameraPosition.zoom)
            .tilt(currentTilt)
            .bearing(currentBearing)
            .build()

        mMap.animateCamera(CameraUpdateFactory.newCameraPosition(cameraPosition))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMapsBinding.inflate(layoutInflater)
        setContentView(binding.root)

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

        val buttonLeft: Button = findViewById(R.id.button_left)
        val buttonRight: Button = findViewById(R.id.button_right)

        buttonLeft.setOnClickListener { adjustBearing(-10f) }
        buttonRight.setOnClickListener { adjustBearing(10f) }

        // Initialize UI components
        navigationInfoTextView = findViewById(R.id.navigation_info)
        val mapFragment = supportFragmentManager.findFragmentById(R.id.map) as SupportMapFragment
        mapFragment.getMapAsync(this)

        // Navigation button listener
        val navigateButton: Button = findViewById(R.id.navigation_button)
        navigateButton.setOnClickListener {
            if (route.isNotEmpty()) simulateNavigation()
        }
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> {
                gravity = lowPass(event.values, gravity)
            }
            Sensor.TYPE_MAGNETIC_FIELD -> {
                geomagnetic = lowPass(event.values, geomagnetic)
            }
            Sensor.TYPE_ROTATION_VECTOR -> {
                val rotationMatrix = FloatArray(9)
                SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
                val orientation = FloatArray(3)
                SensorManager.getOrientation(rotationMatrix, orientation)
                val azimuthInRadians = orientation[0]
                var azimuthInDegrees = Math.toDegrees(azimuthInRadians.toDouble()).toFloat()
                azimuthInDegrees = (azimuthInDegrees + 360) % 360  // Ensure the value is between 0 and 360

                // Account for magnetic declination
                val geoField = GeomagneticField(
                    currentLatLng?.latitude?.toFloat() ?: 0f,
                    currentLatLng?.longitude?.toFloat() ?: 0f,
                    0f,
                    System.currentTimeMillis()
                )
                val declination = geoField.declination
                val trueNorthAzimuth = (azimuthInDegrees + declination) % 360
                currentAzimuth = trueNorthAzimuth
                // Update the camera bearing
                currentBearing = trueNorthAzimuth
                val cameraPosition = CameraPosition.Builder()
                    .target(mMap.cameraPosition.target)
                    .zoom(mMap.cameraPosition.zoom)
                    .tilt(currentTilt)
                    .bearing(currentBearing)
                    .build()
                mMap.animateCamera(CameraUpdateFactory.newCameraPosition(cameraPosition))

                // Log the azimuth
                Log.d("MapsActivity", "Azimuth (Rotation Vector): $trueNorthAzimuth°")
            }
        }

        if (gravity != null && geomagnetic != null) {
            val rotationMatrix = FloatArray(9)
            val inclinationMatrix = FloatArray(9)
            val success = SensorManager.getRotationMatrix(rotationMatrix, inclinationMatrix, gravity, geomagnetic)
            if (success) {
                val orientation = FloatArray(3)
                SensorManager.getOrientation(rotationMatrix, orientation)
                val azimuthInRadians = orientation[0]
                var azimuthInDegrees = Math.toDegrees(azimuthInRadians.toDouble()).toFloat()
                azimuthInDegrees = (azimuthInDegrees + 360) % 360  // Ensure the value is between 0 and 360

                // Account for magnetic declination
                val geoField = GeomagneticField(
                    currentLatLng?.latitude?.toFloat() ?: 0f,
                    currentLatLng?.longitude?.toFloat() ?: 0f,
                    0f,
                    System.currentTimeMillis()
                )
                val declination = geoField.declination
                val trueNorthAzimuth = (azimuthInDegrees + declination) % 360
                currentAzimuth = trueNorthAzimuth
                // Check if mMap is initialized before using it
                if (::mMap.isInitialized) {
                    // Update the camera bearing
                    currentBearing = trueNorthAzimuth
                    val cameraPosition = CameraPosition.Builder()
                        .target(mMap.cameraPosition.target)
                        .zoom(mMap.cameraPosition.zoom)
                        .tilt(currentTilt)
                        .bearing(currentBearing)
                        .build()
                    mMap.animateCamera(CameraUpdateFactory.newCameraPosition(cameraPosition))
                } else {
                    Log.d("MapsActivity", "mMap is not initialized yet.")
                }

                // Log the azimuth
                Log.d("MapsActivity", "Azimuth: $trueNorthAzimuth°")
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // Handle sensor accuracy changes if necessary
    }

    override fun onPause() {
        super.onPause()
        sensorManager.unregisterListener(this)
    }

    override fun onResume() {
        super.onResume()
        sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_UI)
        sensorManager.registerListener(this, magnetometer, SensorManager.SENSOR_DELAY_UI)
        sensorManager.registerListener(this, rotationVectorSensor, SensorManager.SENSOR_DELAY_UI)
    }

    override fun onMapReady(googleMap: GoogleMap) {
        mMap = googleMap
        val startLocation = LatLng(32.794044, 34.989571) // Starting point in Haifa
        mMap.addMarker(MarkerOptions().position(startLocation).title("Start Point"))
        mMap.moveCamera(CameraUpdateFactory.newLatLngZoom(startLocation, 10f))
        mMap.uiSettings.isZoomControlsEnabled = true
        mMap.isTrafficEnabled = true
        mMap.uiSettings.isTiltGesturesEnabled = true
        mMap.uiSettings.isRotateGesturesEnabled = true

        val destination = LatLng(32.0853, 34.7818) // Destination example in Tel Aviv
        getDirections(startLocation, destination)
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


    private fun simulateNavigation() {
        var routeIndex = 0
        var currentLatLng: LatLng? = route.firstOrNull()

        currentLatLng?.let {
            marker = mMap.addMarker(MarkerOptions().position(it).title("Simulated Location"))
        }

        val simulationSpeed = 20f
        // val simulationDirection = currentAzimuth
        Log.d("Simulation", "CurrentAzimuth in simulateNavigation: $currentAzimuth")

        currentTilt = 60f
        val cameraPosition = CameraPosition.Builder()
            .target(mMap.cameraPosition.target)
            .zoom(mMap.cameraPosition.zoom)
            .tilt(currentTilt)  // Apply new tilt
            .bearing(currentBearing)  // Keep the same bearing
            .build()
        mMap.animateCamera(CameraUpdateFactory.newCameraPosition(cameraPosition))

        val initialRunnable = object : Runnable {
            override fun run() {
                if (routeIndex < route.size) {
                    val currentPoint = route[routeIndex]
                    Log.d("Simulation", "Current tilt: $currentTilt")

                    // Use currentLatLng if available, otherwise use the original point from the route
                    val lat = currentLatLng?.latitude ?: currentPoint.latitude
                    val lng = currentLatLng?.longitude ?: currentPoint.longitude

                    updateLocationToServer(
                        lat,
                        lng,
                        simulationSpeed,
                        currentAzimuth,
                        route,
                        routeIndex
                    )
                } else {
                    Log.d("Navigation", "End of route reached.")
                    handler.removeCallbacks(this)
                }
            }
        }

        handler.post(initialRunnable)
    }

    fun updateLocationToServer(
        lat: Double,
        lng: Double,
        speed: Float,
        direction: Float,
        route: List<LatLng>,
        index: Int
    ) {
        val json = JSONObject().apply {
            put("location", JSONObject().apply {
                put("x", lat)
                put("y", lng)
            })
            put("speed", speed)
            put("direction", direction)
            put("time", System.currentTimeMillis() / 1000)
        }
        Log.d("UpdateLocation", "Sending direction: $direction")

        val requestBody = json.toString().toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull())
        val request = Request.Builder()
            .url("http://10.0.0.16:5000/update")
            .post(requestBody)
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e("Navigation Update", "Failed to send location: ${e.message}")
                handler.post {
                    Toast.makeText(
                        applicationContext,
                        "Failed to update location",
                        Toast.LENGTH_SHORT
                    ).show()
                    // Retry after a delay on failure
                    handler.postDelayed({
                        updateLocationToServer(lat, lng, speed, direction, route, index)
                    }, 1000)
                }
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (it.isSuccessful) {
                        val respString = it.body?.string()
                        val respJSON = respString?.let { it1 -> JSONObject(it1) }
                        val newLocation = respJSON?.getJSONObject("new_location")
                        val newX = newLocation?.getDouble("x")
                        val newY = newLocation?.getDouble("y")

                        Log.d(
                            "Navigation Update",
                            "Successful response: New Location - Latitude: $newX, Longitude: $newY"
                        )

                        handler.post {
                            if (newX != null && newY != null) {
                                currentLatLng = LatLng(newX, newY)

                                val currentLocation = currentLatLng

                                animateMarker(marker!!, currentLocation!!)

                                // Build a new CameraPosition with the desired tilt
                                val cameraPosition = CameraPosition.Builder()
                                    .target(currentLocation)
                                    .zoom(mMap.cameraPosition.zoom)
                                    .tilt(currentTilt)
                                    .build()

                                mMap.animateCamera(CameraUpdateFactory.newCameraPosition(cameraPosition))

                                val cameraPositionAfterUpdate = mMap.cameraPosition
                                Log.d("CameraPosition", "Target: ${cameraPositionAfterUpdate.target}, Zoom: ${cameraPositionAfterUpdate.zoom}, Tilt: ${cameraPositionAfterUpdate.tilt}, Bearing: ${cameraPositionAfterUpdate.bearing}")

                                val updatedRunnable = object : Runnable {
                                    override fun run() {
                                        if (index < route.size) {
                                            val currentPoint = route[index]

                                            val lat = currentLatLng?.latitude ?: currentPoint.latitude
                                            val lng = currentLatLng?.longitude ?: currentPoint.longitude

                                            updateLocationToServer(
                                                lat,
                                                lng,
                                                speed,
                                                currentAzimuth,
                                                route,
                                                index + 1
                                            )
                                        } else {
                                            Log.d("Navigation", "End of route reached.")
                                            handler.removeCallbacks(this)
                                        }
                                    }
                                }

                                handler.postDelayed(updatedRunnable, 1000)

                            } else {
                                Log.e("Navigation Update", "Received null for new coordinates")
                            }
                        }
                    } else {
                        Log.e("Navigation Update", "Server error: ${response.code}")
                        handler.post {
                            Toast.makeText(
                                applicationContext,
                                "Error updating location",
                                Toast.LENGTH_SHORT
                            ).show()
                            // Retry after a delay on server error
                            handler.postDelayed({
                                updateLocationToServer(lat, lng, speed, direction, route, index)
                            }, 1000)
                        }
                    }
                }
            }
        })
    }


    private fun animateMarker(marker: Marker, toPosition: LatLng) {
        val startPosition = marker.position
        val handler = Handler(Looper.getMainLooper())
        val start = SystemClock.uptimeMillis()
        val duration = 300L  // 0.3 second

        val interpolator = LinearInterpolator()

        handler.post(object : Runnable {
            override fun run() {
                val elapsed = SystemClock.uptimeMillis() - start
                val t = elapsed / duration.toFloat()
                val lat = t * toPosition.latitude + (1 - t) * startPosition.latitude
                val lng = t * toPosition.longitude + (1 - t) * startPosition.longitude
                marker.position = LatLng(lat, lng)

                if (t < 1.0) {
                    handler.postDelayed(this, 16)
                }
            }
        })
    }

}

data class Step(val description: String, val distance: String)
