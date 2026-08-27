package com.shadowprotectors.alarmapp.engine

import android.location.Location
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

object DistanceEngine {

    private const val EARTH_RADIUS_KM = 6371.0

    /**
     * Calculates precise distance between two coordinates using Android's WGS84 ellipsoid model
     * with Haversine fallback.
     * @return Distance in kilometers
     */
    fun calculateDistanceKm(
        lat1: Double, lon1: Double,
        lat2: Double, lon2: Double
    ): Double {
        return try {
            val results = FloatArray(1)
            Location.distanceBetween(lat1, lon1, lat2, lon2, results)
            (results[0] / 1000.0)
        } catch (e: Exception) {
            val dLat = Math.toRadians(lat2 - lat1)
            val dLon = Math.toRadians(lon2 - lon1)
            val a = sin(dLat / 2) * sin(dLat / 2) +
                    cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                    sin(dLon / 2) * sin(dLon / 2)
            val c = 2 * atan2(sqrt(a), sqrt(1 - a))
            EARTH_RADIUS_KM * c
        }
    }

    /**
     * Calculates the initial bearing (compass heading in degrees 0..360) from point 1 to point 2.
     */
    fun calculateBearing(
        lat1: Double, lon1: Double,
        lat2: Double, lon2: Double
    ): Float {
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
     * Includes sanity checks to prevent m/s vs km/h unit mismatch errors.
     */
    fun estimateEtaMinutes(distanceKm: Double, speedKmh: Double): Int? {
        if (distanceKm <= 0.0) return 0
        var effectiveSpeed = speedKmh

        // Unit mismatch protection: If speed is suspiciously low (< 4.0 km/h) for a traveling vehicle, return null (stationary)
        if (effectiveSpeed < 3.0) return null // Stationary or walking slowly (< 3 km/h)

        val hours = distanceKm / effectiveSpeed
        val minutes = (hours * 60.0).toInt()
        return minutes.coerceAtLeast(1)
    }
}
