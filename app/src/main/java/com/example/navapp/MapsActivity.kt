package com.example.navapp

import android.Manifest
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.text.Html
import android.view.animation.LinearInterpolator
import androidx.core.text.HtmlCompat



import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.navapp.databinding.ActivityMapsBinding
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.Marker
import com.google.android.gms.maps.model.MarkerOptions
import com.google.android.gms.maps.model.PolylineOptions
//import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.CameraPosition
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
    private val gravity = FloatArray(3)
    private val geomagnetic = FloatArray(3)
    private val orientation = FloatArray(3)
    private val rotationMatrix = FloatArray(9)


    companion object {
        private const val REQUEST_CODE_LOCATION_PERMISSION = 100
        private val client = OkHttpClient()
    }

    private var currentTilt = 0f  // Initial tilt (can adjust if needed)
    private var currentBearing = 0f  // Initial bearing

    // Adjust tilt (vertical rotation)
    private fun adjustTilt(tiltChange: Float) {
        currentTilt = (currentTilt + tiltChange).coerceIn(0f, 60f)  // Limit tilt between 0 and 90 degrees
        Log.d("MapTilt", "Adjusting tilt to: $currentTilt")
        val cameraPosition = CameraPosition.Builder()
            .target(mMap.cameraPosition.target)
            .zoom(mMap.cameraPosition.zoom)
            .tilt(currentTilt)  // Apply new tilt
            .bearing(currentBearing)  // Keep the same bearing
            .build()

        mMap.animateCamera(CameraUpdateFactory.newCameraPosition(cameraPosition))
    }

    // Adjust bearing (horizontal rotation)
    private fun adjustBearing(bearingChange: Float) {
        currentBearing = (currentBearing + bearingChange) % 360  // Keep bearing between 0 and 360 degrees

        val cameraPosition = CameraPosition.Builder()
            .target(mMap.cameraPosition.target)
            .zoom(mMap.cameraPosition.zoom)
            .tilt(currentTilt)  // Keep the same tilt
            .bearing(currentBearing)  // Apply new bearing
            .build()

        mMap.animateCamera(CameraUpdateFactory.newCameraPosition(cameraPosition))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMapsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Initialize the SensorManager
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        magnetometer = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)

        // Register listeners only if the sensors are available
        if (accelerometer != null) {
            sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_UI)
        } else {
            Log.e("MapsActivity", "Accelerometer not available")
        }

        if (magnetometer != null) {
            sensorManager.registerListener(this, magnetometer, SensorManager.SENSOR_DELAY_UI)
        } else {
            Log.e("MapsActivity", "Magnetometer not available")
        }

        val buttonLeft: Button = findViewById(R.id.button_left)
        val buttonRight: Button = findViewById(R.id.button_right)

        buttonLeft.setOnClickListener { adjustBearing(-10f) }
        buttonRight.setOnClickListener { adjustBearing(10f) }

        // Initialize UI elements
        navigationInfoTextView = findViewById(R.id.navigation_info)
        val mapFragment = supportFragmentManager.findFragmentById(R.id.map) as SupportMapFragment
        mapFragment.getMapAsync(this)

        // Set up button click listener
        val navigateButton: Button = findViewById(R.id.navigation_button)
        navigateButton.setOnClickListener {
            if (route.isNotEmpty()) simulateNavigation()
        }
    }




    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> {
                System.arraycopy(event.values, 0, gravity, 0, event.values.size)
            }
            Sensor.TYPE_MAGNETIC_FIELD -> {
                System.arraycopy(event.values, 0, geomagnetic, 0, event.values.size)
            }
        }

        val success = SensorManager.getRotationMatrix(rotationMatrix, null, gravity, geomagnetic)
        if (success) {
            SensorManager.getOrientation(rotationMatrix, orientation)
            val azimuth = Math.toDegrees(orientation[0].toDouble()).toFloat() // Azimuth in degrees

            // You can log the azimuth or update your UI with it
//            Log.d("MapsActivity", "Azimuth: $azimuth°")
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // You can handle accuracy changes here if needed
    }

    override fun onPause() {
        super.onPause()
        sensorManager.unregisterListener(this)
    }

    override fun onResume() {
        super.onResume()
        sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_UI)
        sensorManager.registerListener(this, magnetometer, SensorManager.SENSOR_DELAY_UI)
    }



    override fun onMapReady(googleMap: GoogleMap) {
        mMap = googleMap
        val startLocation = LatLng(32.794044, 34.989571) // Example starting point in Haifa
        mMap.addMarker(MarkerOptions().position(startLocation).title("Start Point"))
        mMap.moveCamera(CameraUpdateFactory.newLatLngZoom(startLocation, 10f))
        mMap.uiSettings.isZoomControlsEnabled = true
        mMap.isTrafficEnabled = true
        mMap.uiSettings.isTiltGesturesEnabled = true
        mMap.uiSettings.isRotateGesturesEnabled = true

        val destination = LatLng(32.0853, 34.7818) // Simulated destination example in Tel Aviv
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
        val simulationDirection = 270f
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
                        simulationDirection,
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

        val requestBody = json.toString().toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull())
        val request = Request.Builder()
            .url("http://10.0.2.2:5000/update")
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
                                                direction,
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
