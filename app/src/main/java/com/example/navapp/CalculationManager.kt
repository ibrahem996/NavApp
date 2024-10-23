// CalculationManager.kt
package com.example.navapp

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
    fun calculateNewPosition(latPrev: Double, lonPrev: Double, speed: Double, angle: Double, timeElapsed: Double): Pair<Double, Double> {
        val R = 6371000.0  // Earth's radius in meters

        // Convert angle and coordinates to radians
        val angleRad = Math.toRadians(angle)
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

    /**
     * Calculates the distance between two geographic points using the Haversine formula.
     *
     * @param pos1 First position as Pair of latitude and longitude in degrees.
     * @param pos2 Second position as Pair of latitude and longitude in degrees.
     * @return Distance in meters.
     */
    fun calculateDistance(pos1: Pair<Double, Double>, pos2: Pair<Double, Double>): Double {
        val R = 6371000.0 // Earth's radius in meters
        val lat1Rad = Math.toRadians(pos1.first)
        val lat2Rad = Math.toRadians(pos2.first)
        val deltaLat = Math.toRadians(pos2.first - pos1.first)
        val deltaLon = Math.toRadians(pos2.second - pos1.second)

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
     *
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
}
