// MapsActivity.kt
package com.example.navapp

import android.Manifest
import android.annotation.SuppressLint
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
import android.util.Log
import android.view.animation.LinearInterpolator
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
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
import android.location.LocationListener
import android.location.LocationManager
import android.content.Context
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import java.util.concurrent.atomic.AtomicReference
import android.animation.ValueAnimator



class MapsActivity : AppCompatActivity(), OnMapReadyCallback, SensorEventListener, ObdDataListener {

    private var currentLatLng: LatLng? = null
    private lateinit var mMap: GoogleMap
    private lateinit var calculationManager: CalculationManager
    private lateinit var binding: ActivityMapsBinding
    private lateinit var navigationInfoTextView: TextView
    private lateinit var distanceTextView: TextView
    private lateinit var speedTextView: TextView
    private var route: List<LatLng> = mutableListOf()
    private var steps: List<Step> = mutableListOf()
    private var currentStepIndex = 0
    private var currentStep: Step? = null
    private var marker: Marker? = null
    private val handler = Handler(Looper.getMainLooper())

    private lateinit var sensorManager: SensorManager
    private var accelerometer: Sensor? = null
    private var magnetometer: Sensor? = null
    private var rotationVectorSensor: Sensor? = null
    private val LOCATION_PERMISSION_REQUEST_CODE = 1

    companion object {
        private const val REQUEST_CODE_LOCATION_PERMISSION = 100
        private val client = OkHttpClient()
    }

    private val currentAzimuth = AtomicReference(0f)
    private var simulatedAzimuth: Float = 0f
    private var initialPhoneAzimuth: Float = 0f
    private var currentTilt = 0f  // Initial tilt
    private var currentBearing = 0f  // Initial bearing
    private var angle = 0f  // Initial angle

    private val ALPHA = 0.25f

    // OBD Manager and current speed
    private lateinit var obdManager: OBDManager
    private val currentSpeed = AtomicReference(0f)
    private lateinit var locationManager: LocationManager
    private var gpsSpeed: Float = 0f
    private var lastLocation: Location? = null
    private var lastLocationTime: Long = 0L
    private lateinit var azimuthKalmanFilter: KalmanFilter1D
    var lastFindClosestPointTime = System.currentTimeMillis()

    private var currentMarkerAnimator: ValueAnimator? = null


    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            val currentTime = System.currentTimeMillis()

            // Copy lastLocation to a local variable
            val lastLoc = lastLocation
            if (lastLoc != null) {
                val timeDelta = (currentTime - lastLocationTime) / 1000.0 // Time in seconds
                val distance = location.distanceTo(lastLoc) // Distance in meters

                if (timeDelta > 0) { // Avoid division by zero
                    val speed = (distance / timeDelta) * 3.6 // Speed in km/h
                    gpsSpeed = speed.toFloat()

                    // Log the calculated GPS speed
                    Log.d("MapsActivity", "Calculated GPS Speed: $gpsSpeed km/h")
                }
            } else {
                // For the first location update, use location.speed if available
                gpsSpeed = location.speed * 3.6f
                Log.d("MapsActivity", "Initial GPS Speed from Location: $gpsSpeed km/h")
            }

            lastLocation = location
            lastLocationTime = currentTime
        }

        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
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
        distanceTextView = findViewById(R.id.distance_text_view)

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

        // Initialize LocationManager
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager

        // Check permissions and request location updates
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            == PackageManager.PERMISSION_GRANTED) {
            locationManager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                1000L, // Minimum time interval between updates (milliseconds)
                0f,    // Minimum distance between updates (meters)
                locationListener
            )
        } else {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
                LOCATION_PERMISSION_REQUEST_CODE
            )
        }
        // Initialize the Kalman Filter with initial parameters
        azimuthKalmanFilter = KalmanFilter1D(
            q = 1.0f,   // Process noise covariance
            r = 0.6f,   // Measurement noise covariance
            initialEstimate = 0f,  // Initial state estimate
            initialErrorCovariance = 1f  // Initial estimation error covariance
        )
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

                // Apply Kalman Filter
                val filteredAzimuth = azimuthKalmanFilter.update(azimuthInDegrees)
                Log.d("MapsActivity4", "azimuthInDegrees Azimuth: $azimuthInDegrees")
                Log.d("MapsActivity4", "filteredAzimuth Azimuth: $filteredAzimuth")
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
                var trueNorthAzimuth = (filteredAzimuth + declination + 360) % 360
                Log.d("MapsActivity4", "trueNorthAzimuth Azimuth: $trueNorthAzimuth")

                // Handle angle wrap-around
                var deltaAzimuth = trueNorthAzimuth - currentAzimuth.get()
                Log.d("MapsActivity2", "deltaAzimuth Azimuth: $deltaAzimuth")
                if (deltaAzimuth > 180) deltaAzimuth -= 360
                else if (deltaAzimuth < -180) deltaAzimuth += 360

                // Apply low-pass filter using class-level ALPHA
                //trueNorthAzimuth = (currentAzimuth + ALPHA * deltaAzimuth + 360) % 360
                //Log.d("MapsActivity4", "trueNorthAzimuth2 Azimuth: $trueNorthAzimuth")

                // Update the current azimuth
                currentAzimuth.set(trueNorthAzimuth)
                //simulatedAzimuth = currentAzimuth + deltaAzimuth
                // Update the camera bearing
                currentBearing = angle
                val cameraPosition = CameraPosition.Builder()
                    .target(mMap.cameraPosition.target)
                    .zoom(mMap.cameraPosition.zoom)
                    .tilt(currentTilt)
                    .bearing(currentBearing)
                    .build()

                // Only update camera if the change is significant (e.g., more than 1°)
                if (kotlin.math.abs(deltaAzimuth) > 3) {
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
        locationManager.removeUpdates(locationListener) // Stop location updates
        Log.d("MapsActivity", "onPause: Unregistered sensor listeners, removed handler callbacks, and stopped location updates.")
    }


    override fun onResume() {
        super.onResume()
        // Re-register sensor listeners
        accelerometer?.also {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }
        magnetometer?.also {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }
        rotationVectorSensor?.also {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }

        // Request location updates if permission is granted
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            == PackageManager.PERMISSION_GRANTED) {
            locationManager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                1000L,
                0f,
                locationListener
            )
        }

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
        // Check if location permission is granted
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            == PackageManager.PERMISSION_GRANTED) {
            // Enable the My Location layer on the map
            mMap.isMyLocationEnabled = true
        } else {
            // Request permission if not already granted
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
                LOCATION_PERMISSION_REQUEST_CODE
            )
        }
        // Customize the UI settings of the map
        mMap.uiSettings.isMyLocationButtonEnabled = true

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


    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        // Handle location permission result
        if (requestCode == LOCATION_PERMISSION_REQUEST_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                    == PackageManager.PERMISSION_GRANTED) {
                    mMap.isMyLocationEnabled = true
                    // Request location updates after permission is granted
                    locationManager.requestLocationUpdates(
                        LocationManager.GPS_PROVIDER,
                        1000L,
                        0f,
                        locationListener
                    )
                }
            } else {
                Toast.makeText(this, "Location permission is required.", Toast.LENGTH_LONG).show()
            }
        }
        // Pass onRequestPermissionsResult to OBDManager
        obdManager.onRequestPermissionsResult(requestCode, permissions, grantResults)
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
                        // Log the route and steps for debugging purposes
                        Log.d("MapsActivity", "Route: $route")
                        Log.d("MapsActivity", "steps: $steps")
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
        val stepsList = mutableListOf<Step>()
        val routes = jsonObject.getJSONArray("routes")
        if (routes.length() > 0) {
            val legs = routes.getJSONObject(0).getJSONArray("legs")
            val stepsArray = legs.getJSONObject(0).getJSONArray("steps")
            for (i in 0 until stepsArray.length()) {
                val stepObject = stepsArray.getJSONObject(i)
                val distance = stepObject.getJSONObject("distance").getString("text")
                val instruction = stepObject.getString("html_instructions")
                val startLocation = stepObject.getJSONObject("start_location")
                val endLocation = stepObject.getJSONObject("end_location")
                val startLatLng = LatLng(
                    startLocation.getDouble("lat"),
                    startLocation.getDouble("lng")
                )
                val endLatLng = LatLng(
                    endLocation.getDouble("lat"),
                    endLocation.getDouble("lng")
                )
                stepsList.add(Step(instruction, distance, startLatLng, endLatLng))
            }
        }
        return stepsList
    }


    private fun updateCurrentStep(currentLatLng: LatLng) {
        if (currentStepIndex >= steps.size) return  // No more steps

        currentStep?.let { step ->
            val stepEndLocation = step.endLocation

            // Calculate the distance to the end point of the current step
            val distanceToEnd = calculationManager.calculateDistance(currentLatLng, stepEndLocation)

            // Update distance in UI
            updateDistanceToStep(distanceToEnd)

            // If the distance is less than a certain threshold, proceed to the next step
            val THRESHOLD_DISTANCE = 50.0  // Threshold in meters
            if (distanceToEnd < THRESHOLD_DISTANCE) {
                currentStepIndex++
                if (currentStepIndex < steps.size) {
                    currentStep = steps[currentStepIndex]
                    updateNavigationInfo()
                } else {
                    runOnUiThread {
                        navigationInfoTextView.text = "הגעת ליעד"
                    }
                }
            }
        }
    }


    private fun updateNavigationInfo() {
        runOnUiThread {
            currentStep?.let { step ->
                navigationInfoTextView.text = android.text.Html.fromHtml(step.description)
            }
        }
    }


    @SuppressLint("DefaultLocale", "SetTextI18n")
    private fun updateDistanceToStep(distance: Double) {
        runOnUiThread {
            val formattedDistance = if (distance >= 1000) {
                String.format("%.1f km", distance / 1000)
            } else {
                String.format("%.0f m", distance)
            }
            distanceTextView.text = "מרחק ליעד הבא: $formattedDistance"
        }
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

    private fun calculateBearing(start: LatLng, end: LatLng): Float {
        val startLat = Math.toRadians(start.latitude)
        val startLng = Math.toRadians(start.longitude)
        val endLat = Math.toRadians(end.latitude)
        val endLng = Math.toRadians(end.longitude)

        val deltaLng = endLng - startLng
        val x = sin(deltaLng) * cos(endLat)
        val y = cos(startLat) * sin(endLat) - sin(startLat) * cos(endLat) * cos(deltaLng)

        var bearing = Math.toDegrees(atan2(x, y)).toFloat()
        bearing = (bearing + 360) % 360
        return bearing
    }

    private fun startNavigation() {
        if (route.isEmpty()) {
            Toast.makeText(this, "No route available. Please select start and destination points.", Toast.LENGTH_SHORT).show()
            return
        }
        // Initialize simulation variables
        if (route.size > 2) {
            simulatedAzimuth = calculateBearing(route[0], route[2])
            Log.d("Simulation2", "Initial Route Bearing: $simulatedAzimuth")
        } else {
            Log.e("MapsActivity", "Route does not have enough points to calculate initial bearing.")
        }

        initialPhoneAzimuth = currentAzimuth.get()
        Log.d("Simulation2", "Initial initialPhoneAzimuth: $initialPhoneAzimuth")

        // Initialize simulation variables
        var routeIndex = 0
        var currentLatLng: LatLng? = route.firstOrNull()
        currentStepIndex = 0
        if (steps.isNotEmpty()) {
            currentStep = steps[currentStepIndex]
            updateNavigationInfo()

            // Update the initial distance
            val initialDistance = calculationManager.calculateDistance(currentLatLng!!, currentStep!!.endLocation)
            updateDistanceToStep(initialDistance)
        } else {
            Toast.makeText(this, "No navigation steps available.", Toast.LENGTH_SHORT).show()
            Log.e("MapsActivity", "Steps are empty in startNavigation.")
            return
        }
        // Set initial marker position
        currentLatLng.let {
            marker = mMap.addMarker(MarkerOptions().position(it).title("Simulated Location"))
        } ?: run {
            Toast.makeText(this, "Invalid start location.", Toast.LENGTH_SHORT).show()
            return
        }

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
                val currentPoint = route[routeIndex]
                // val nextPoint = route.getOrNull(routeIndex + 1)

                // Recalculate effective speed inside the runnable
                //val effectiveSpeed = if (currentSpeed.get() > 0f) currentSpeed.get() else gpsSpeed
                val effectiveSpeed = if (gpsSpeed < 1) 0f else gpsSpeed
                //val effectiveSpeed =  22f
                val simulationSpeed = if (effectiveSpeed > 0.35f) effectiveSpeed / 3.6f else 0f  // Convert km/h to m/s

                Log.d("Simulation2", "CurrentAzimuth in navigationRunnable: $currentAzimuth")
                Log.d("Simulation", "Simulation speed: $simulationSpeed m/s")

                // Use currentAzimuth from sensors
                val angleDifference = calculationManager.calculateAngleDifference(simulatedAzimuth, currentAzimuth.get())
                if (angleDifference in -45f..45f) {
                    angle = (simulatedAzimuth + (currentAzimuth.get() - initialPhoneAzimuth) + 360) % 360
                    Log.d("Simulation3", "Initial angle updated to: $angle")
                } else {
                    angle = currentAzimuth.get()
                    Log.d("Simulation3", "angle azimuth difference ($angle°) exceeds ±45°. Update skipped.")
                    //Toast.makeText(this, "Initial azimuth difference exceeds ±45°. Navigation may not align correctly.", Toast.LENGTH_SHORT).show()
                }
                //val angle = simulatedAzimuth +(currentAzimuth.toDouble() - initialPhoneAzimuth.toDouble())
                //angle = (simulatedAzimuth + (currentAzimuth - initialPhoneAzimuth) + 360) % 360

                Log.d("Simulation2", "simulatedAzimuth in navigationRunnable: $simulatedAzimuth")
                Log.d("Simulation2", "initialPhoneAzimuth in navigationRunnable: $initialPhoneAzimuth")
                Log.d("Simulation2", "angle in navigationRunnable: $angle")
                // Calculate time elapsed since last update
                val currentTimestamp = System.currentTimeMillis()
                val timeElapsed = calculationManager.timeElapsedSinceLastUpdate(currentTimestamp)

                // Update last timestamp
                calculationManager.updateTimestamp(currentTimestamp)

                // Update speed display
                onSpeedUpdated(simulationSpeed * 3.6f)
                var zoom = adjustZoomBasedOnSpeed(simulationSpeed)
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
                // Update current step
                updateCurrentStep(currentLatLng!!)

                val currentTime = System.currentTimeMillis()
                val diffFindClosestPointTime = currentTime - lastFindClosestPointTime
                var closestIndex: Int? = null
                var simulatedAzimuth2 = simulatedAzimuth

                if (diffFindClosestPointTime >= 1000) {  // Check if 1 seconds have passed
                    lastFindClosestPointTime = currentTime

                    // Find the index of the closest point within threshold, excluding current index
                    closestIndex = calculationManager.findClosestPointIndex(currentLatLng!!, route, routeIndex, simulationSpeed)

                    // Update routeIndex only if closestIndex is not null
                    if (closestIndex != null) {
                        // Calculate the angular difference between the new simulated azimuth and the current azimuth
                        simulatedAzimuth2 = calculateBearing(route[closestIndex], route.getOrNull(closestIndex + 2) ?: route[closestIndex])
                        val angleDifference = calculationManager.calculateAngleDifference(simulatedAzimuth2, currentAzimuth.get())
                        routeIndex = closestIndex

                        // Proceed with the update only if the angular difference is within ±45 degrees
                        if (angleDifference in -45f..45f) {
                            currentLatLng = route[closestIndex]

                            // Update initialPhoneAzimuth and simulatedAzimuth only if within threshold
                            initialPhoneAzimuth = currentAzimuth.get()
                            simulatedAzimuth = simulatedAzimuth2
                        } else {
                            initialPhoneAzimuth = currentAzimuth.get()
                            simulatedAzimuth = currentAzimuth.get()
                            Log.d("Simulation3", "Azimuth difference ($angleDifference°) exceeds ±45°. Update skipped.")
                        }
                    } else {
                        Log.d("Simulation3", "No closest index found within threshold.")
                    }

                    // Logging for debugging
                    Log.d("Simulation3", "simulatedAzimuth in navigationRunnable: $simulatedAzimuth")
                    Log.d("Simulation3", "simulatedAzimuth2 in navigationRunnable: $simulatedAzimuth2")
                    Log.d("Simulation3", "initialPhoneAzimuth in navigationRunnable: $initialPhoneAzimuth")
                    Log.d("Simulation3", "currentAzimuth in navigationRunnable: $currentAzimuth")
                    Log.d("Simulation3", "angle in navigationRunnable: $angle")
                    // Uncomment the following lines for more detailed logging if needed
                    // Log.d("Simulation3", "currentTime in navigationRunnable: $currentTime")
                    // Log.d("Simulation3", "lastFindClosestPointTime in navigationRunnable: $lastFindClosestPointTime")
                    // Log.d("Simulation3", "diffFindClosestPointTime in navigationRunnable: $diffFindClosestPointTime")
                }


                // Update the next point based on the new routeIndex
                val nextPoint = route.getOrNull(routeIndex + 1)

                // Animate marker movement for smooth transition
                currentLatLng?.let { newLatLng ->
                    marker?.let { animateMarker(it, newLatLng) }
                }

                // Move camera to follow the marker smoothly
                currentLatLng?.let { latLng ->
                    val newCameraPosition = CameraPosition.Builder()
                        .target(latLng)
                        .zoom(zoom)
                        .tilt(currentTilt)
                        .bearing(angle)
                        .build()
                    mMap.animateCamera(CameraUpdateFactory.newCameraPosition(newCameraPosition), 500, null)  // Animate over 500ms
                } ?: run {
                    Log.e("Simulation", "currentLatLng is null. Cannot update camera.")
                }

                // Schedule next update
                handler.postDelayed(this, 200)  // Update every 200 milliseconds for real-time updates
            }
        }
        // Start navigation simulation
        handler.post(navigationRunnable)
    }


    /**
     * Animates the marker from its current position to the specified position smoothly.
     *
     * @param marker The marker to animate.
     * @param toPosition The target position to move the marker to.
     */
    private fun animateMarker(marker: Marker, toPosition: LatLng) {
        // Cancel any existing animator to prevent overlapping animations
        currentMarkerAnimator?.cancel()

        val startPosition = marker.position
        val endPosition = toPosition

        // Create a ValueAnimator that goes from 0f to 1f
        currentMarkerAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 750L // Duration of the animation in milliseconds (0.75 seconds)
            interpolator = LinearInterpolator() // Ensures a smooth linear animation

            // Listener to update the marker's position on each animation frame
            addUpdateListener { animation ->
                val t = animation.animatedValue as Float

                // Calculate the new latitude and longitude using linear interpolation
                val lat = (endPosition.latitude - startPosition.latitude) * t + startPosition.latitude
                val lng = (endPosition.longitude - startPosition.longitude) * t + startPosition.longitude

                // Update the marker's position
                marker.position = LatLng(lat, lng)
            }

            // Start the animation
            start()
        }
    }


    private fun adjustZoomBasedOnSpeed(speedKmh: Float): Float {
        val desiredZoom = when {
            speedKmh < 20 -> 20f
            speedKmh < 40 -> 18f
            speedKmh < 60 -> 16f
            else -> 14f
        }
        return desiredZoom
    }


    // ObdDataListener implementation
    @SuppressLint("SetTextI18n", "DefaultLocale")
    override fun onSpeedUpdated(speed: Float) {
        currentSpeed.set(speed)
        // Format the speed to one decimal place
        val formattedSpeed = String.format("%.1f", speed)
        runOnUiThread {
            speedTextView.text = "$formattedSpeed\nkm/h"
        }
    }


    override fun onObdError(errorMessage: String) {
        runOnUiThread {
            Toast.makeText(this, "OBD Error: $errorMessage", Toast.LENGTH_LONG).show()
        }
    }

}

data class Step(
    val description: String,
    val distance: String,
    val startLocation: LatLng,
    val endLocation: LatLng
)

