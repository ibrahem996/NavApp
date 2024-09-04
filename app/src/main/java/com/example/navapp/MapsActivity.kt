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
import android.os.Bundle
import android.util.Log


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
            Log.d("MapsActivity", "Azimuth: $azimuth°")
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
        mMap.moveCamera(CameraUpdateFactory.newLatLngZoom(startLocation, 15f))
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

    private fun simulateNavigation() {
        var index = 0
        currentLatLng =
            route.firstOrNull()  // Initialize with the first point in the route or null if the route is empty
        val delayMillis: Long = 1000  // Delay between movements (1 second)

        // Initialize marker at the first route point
        currentLatLng?.let {
            marker = mMap.addMarker(MarkerOptions().position(it).title("Simulated Location"))
        }

        val simulationSpeed = 30f  // Simulation speed in meters/second
        val simulationDirection = 180f  // Direction in degrees

        val runnable = object : Runnable {
            override fun run() {
                if (index < route.size) {
                    val currentPoint = route[index]
                    Log.d(
                        "Navigation Simulate",
                        "Current index: $index, Point: ${currentPoint.latitude}, ${currentPoint.longitude}"
                    )
                    updateLocationToServer(
                        currentPoint.latitude,
                        currentPoint.longitude,
                        simulationSpeed,
                        simulationDirection,
                        route,
                        index,
                        this
                    )
                    index++
                } else {
                    Log.d("Navigation", "End of route reached.")
                    handler.removeCallbacks(this)
                }
            }
        }
        handler.post(runnable)
    }

    fun updateLocationToServer(
        lat: Double,
        lng: Double,
        speed: Float,
        direction: Float,
        route: List<LatLng>,  // Include the route in the parameters
        index: Int,
        runnable: Runnable
    ) {
        // Convert route list to JSON array
        val routeJsonArray = org.json.JSONArray().apply {
            route.forEach { point ->
                put(JSONObject().apply {
                    put("x", point.latitude)
                    put("y", point.longitude)
                })
            }
        }

        // Create the JSON payload
        val json = JSONObject().apply {
            put("location", JSONObject().apply {
                put("x", lat)
                put("y", lng)
            })
            put("speed", speed)
            put("direction", direction)
            put("time", System.currentTimeMillis() / 1000)  // Timestamp in seconds
            put("expected_path_points", routeJsonArray)  // Add the route as expected path points
        }

        // Create the request
        val requestBody =
            json.toString().toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull())
        val request = Request.Builder()
            .url("http://10.0.2.2:5000/update")  // Adjusted for local testing with the Android emulator
            .post(requestBody)
            .build()

        // Execute the request
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e("Navigation Update", "Failed to send location: ${e.message}")
                handler.post {
                    Toast.makeText(
                        applicationContext,
                        "Failed to update location",
                        Toast.LENGTH_SHORT
                    ).show()
                    handler.postDelayed(runnable, 1000)  // Retry or continue simulation
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
                            val updatedLatLng = newX?.let { it1 -> newY?.let { it2 ->
                                LatLng(it1,
                                    it2
                                )
                            } }
                            currentLatLng = updatedLatLng  // Save the new location
                            marker?.position = updatedLatLng!!
                            mMap.moveCamera(CameraUpdateFactory.newLatLng(updatedLatLng))
                            handler.postDelayed(runnable, 1000)  // Schedule the next iteration
                        }
                    } else {
                        Log.e("Navigation Update", "Server error: ${response.code}")
                        handler.post {
                            Toast.makeText(
                                applicationContext,
                                "Error updating location",
                                Toast.LENGTH_SHORT
                            ).show()
                            handler.postDelayed(runnable, 1000)  // Retry or continue simulation
                        }
                    }
                }
            }
        })
    }
}

    data class Step(val description: String, val distance: String)
