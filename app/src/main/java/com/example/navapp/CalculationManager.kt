// CalculationManager.kt
package com.example.navapp

import com.google.android.gms.maps.model.LatLng
import kotlin.math.*

class CalculationManager {

    private var lastTimestamp: Long? = null

    /**
     * Calculates the new geographic position based on the previous position, speed, bearing, and elapsed time.
     *
     * @param latPrev Previous latitude in degrees.
     * @param lonPrev Previous longitude in degrees.
     * @param speed Speed in meters per second.
     * @param angle Bearing in degrees (0° = North, 90° = East).
     * @param timeElapsed Time elapsed in seconds.
     * @return Pair of new latitude and longitude in degrees.
     */
    fun calculateNewPosition(latPrev: Double, lonPrev: Double, speed: Double, angle: Float, timeElapsed: Double): Pair<Double, Double> {
        val R = 6371000.0  // Earth's radius in meters

        // Convert angle and coordinates to radians
        val angleRad = Math.toRadians(angle.toDouble())
        val latPrevRad = Math.toRadians(latPrev)
        val lonPrevRad = Math.toRadians(lonPrev)

        // Distance traveled
        val distance = speed * timeElapsed

        // Angular distance
        val delta = distance / R

        // Calculate new latitude
        val newLatRad = asin(
            sin(latPrevRad) * cos(delta) + cos(latPrevRad) * sin(delta) * cos(angleRad)
        )

        // Calculate new longitude
        val newLonRad = lonPrevRad + atan2(
            sin(angleRad) * sin(delta) * cos(latPrevRad),
            cos(delta) - sin(latPrevRad) * sin(newLatRad)
        )

        // Normalize longitude to be between -180 and +180 degrees
        val newLonNorm = (Math.toDegrees(newLonRad) + 540) % 360 - 180

        // Return the new coordinates in degrees
        val newLat = Math.toDegrees(newLatRad)
        val newLon = newLonNorm
        return Pair(newLat, newLon)
    }


    fun calculateDistance(point1: LatLng, point2: LatLng): Double {
        val R = 6371000.0 // Earth's radius in meters
        val lat1Rad = Math.toRadians(point1.latitude)
        val lat2Rad = Math.toRadians(point2.latitude)
        val deltaLat = Math.toRadians(point2.latitude - point1.latitude)
        val deltaLon = Math.toRadians(point2.longitude - point1.longitude)

        val a = sin(deltaLat / 2).pow(2.0) +
                cos(lat1Rad) * cos(lat2Rad) *
                sin(deltaLon / 2).pow(2.0)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))

        return R * c
    }

    /**
     * Updates the last timestamp to the current one.
     *
     * @param currentTimestamp Current timestamp in milliseconds.
     */
    fun updateTimestamp(currentTimestamp: Long) {
        lastTimestamp = currentTimestamp
    }

    /**
     * Calculates the time elapsed since the last update.
     * @param currentTimestamp Current timestamp in milliseconds.
     * @return Time elapsed in seconds.
     */
    fun timeElapsedSinceLastUpdate(currentTimestamp: Long): Double {
        return if (lastTimestamp == null) {
            0.0
        } else {
            (currentTimestamp - lastTimestamp!!) / 1000.0  // Time elapsed in seconds
        }
    }


    /**
     * Finds the index of the closest point on the route to the current position.
     * Searches only within the next 10 points after the currentIndex.
     *
     * @param currentPosition Current location as LatLng.
     * @param route List of LatLng points representing the route.
     * @param currentIndex Current index in the route.
     * @param simulationSpeed Current speed in meters per second.
     * @return The index of the closest point within thresholds, or null if none found.
     */
    fun findClosestPointIndex(
        currentPosition: LatLng,
        route: List<LatLng>,
        currentIndex: Int,
        simulationSpeed: Float // Speed in m/s
    ): Int? {
        val SOME_THRESHOLD = simulationSpeed * 0.9  // Dynamic threshold based on speed (meters)
        val MIN_THRESHOLD = 0.5f  // Minimum threshold in meters
        var closestIndex: Int? = null
        var smallestDistance = Double.MAX_VALUE

        // Define the range: next 30 points after currentIndex
        val start = currentIndex + 1
        val end = (currentIndex + 30).coerceAtMost(route.size - 1)

        for (i in start..end) {
            val distance = calculateDistance(currentPosition, route[i])
            if (distance < smallestDistance && distance <= SOME_THRESHOLD && distance > MIN_THRESHOLD) {
                smallestDistance = distance
                closestIndex = i
            }
        }

        return closestIndex
    }


    /**
     * Calculates the smallest difference between two angles in degrees.
     *
     * @param angle1 First angle in degrees.
     * @param angle2 Second angle in degrees.
     * @return The smallest difference in degrees, ranging from -180 to +180.
     */
    fun calculateAngleDifference(angle1: Float, angle2: Float): Float {
        var diff = angle1 - angle2
        // Normalize the difference to be within -180 to +180
        while (diff > 180f) diff -= 360f
        while (diff < -180f) diff += 360f
        return diff
    }


}
