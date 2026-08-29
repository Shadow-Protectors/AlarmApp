package com.shadowprotectors.alarmapp.engine

import android.location.Location
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

object DistanceEngine {

    private const val EARTH_RADIUS_KM = 6371.0

    private var isUsingHaversineFallback = false

    /**
     * Calculates precise distance between two coordinates using Android's WGS84 ellipsoid model
     * with Haversine fallback.
     * @return Distance in kilometers
     */
    fun calculateDistanceKm(
        lat1: Double, lon1: Double,
        lat2: Double, lon2: Double
    ): Double {
        if (!isUsingHaversineFallback) {
            try {
                val results = FloatArray(1)
                Location.distanceBetween(lat1, lon1, lat2, lon2, results)
                return (results[0] / 1000.0)
            } catch (e: Exception) {
                isUsingHaversineFallback = true
            }
        }
        
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                sin(dLon / 2) * sin(dLon / 2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return EARTH_RADIUS_KM * c
    }

    /**
     * Calculates the initial bearing (compass heading in degrees 0..360) from point 1 to point 2.
     * Returns -1f if the distance is too small to calculate a meaningful bearing.
     */
    fun calculateBearing(
        lat1: Double, lon1: Double,
        lat2: Double, lon2: Double
    ): Float {
        val distance = calculateDistanceKm(lat1, lon1, lat2, lon2)
        if (distance < 0.02) return -1f

        val phi1 = Math.toRadians(lat1)
        val phi2 = Math.toRadians(lat2)
        val deltaLambda = Math.toRadians(lon2 - lon1)

        val y = sin(deltaLambda) * cos(phi2)
        val x = cos(phi1) * sin(phi2) - sin(phi1) * cos(phi2) * cos(deltaLambda)
        val theta = atan2(y, x)
        val bearingDeg = Math.toDegrees(theta)
        return ((bearingDeg + 360.0) % 360.0).toFloat()
    }

    /**
     * Estimates remaining time in minutes given current distance (km) and speed (km/h).
     * Includes fallback minimum speeds based on trip mode to prevent absurdly large ETAs 
     * when stationary or in heavy traffic.
     */
    fun estimateEtaMinutes(distanceKm: Double, speedKmh: Double, tripMode: com.shadowprotectors.alarmapp.engine.TripMode): Int? {
        if (distanceKm <= 0.0) return 0

        val minRealisticSpeed = when (tripMode) {
            com.shadowprotectors.alarmapp.engine.TripMode.TRAIN -> 40.0
            com.shadowprotectors.alarmapp.engine.TripMode.BUS_CAR -> 25.0
            else -> 10.0
        }

        // Use the actual speed if it's realistic, otherwise fallback to the minimum realistic speed for that mode.
        // This prevents 4-hour ETAs for a 20km trip when the user is just sitting at home testing or stuck at a red light.
        val effectiveSpeed = if (speedKmh < minRealisticSpeed) minRealisticSpeed else speedKmh

        val hours = distanceKm / effectiveSpeed
        val minutes = (hours * 60.0).toInt()
        return minutes.coerceAtLeast(1)
    }

    /**
     * Computes baseline estimated transit time based on typical road speed (30 km/h for bus/car).
     */
    fun estimateInitialEtaMinutes(distanceKm: Double, defaultSpeedKmh: Double = 30.0): Int {
        if (distanceKm <= 0.0) return 0
        val hours = distanceKm / defaultSpeedKmh.coerceAtLeast(10.0)
        return (hours * 60.0).toInt().coerceAtLeast(1)
    }
}

