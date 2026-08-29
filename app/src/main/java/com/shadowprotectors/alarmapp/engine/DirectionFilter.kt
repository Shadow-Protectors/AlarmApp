package com.shadowprotectors.alarmapp.engine

import kotlin.math.abs

enum class ApproachState {
    APPROACHING,     // Clearly moving towards the destination
    RECEDING,        // Moving away (e.g. on opposite track or departed)
    CIRCLING_LOOP,   // Passing nearby on an outer loop/detour
    UNCERTAIN        // Insufficient motion history
}

class DirectionFilter(private val historyCapacity: Int = 6) {

    private val distanceHistory = mutableListOf<Double>()
    private val bearingHistory = mutableListOf<Float>()
    private val timeHistory = mutableListOf<Long>()

    fun reset() {
        distanceHistory.clear()
        bearingHistory.clear()
        timeHistory.clear()
    }

    /**
     * Records a new GPS fix and determines whether the vehicle is genuinely approaching the destination.
     * @param currentDistanceKm Current distance to stop
     * @param userBearing Degrees (0..360) from device GPS (or -1 if unavailable)
     * @param targetBearing Degrees (0..360) directly toward destination
     * @param currentTimeMs Timestamp of the location fix
     */
    fun evaluateApproach(
        currentDistanceKm: Double,
        userBearing: Float,
        targetBearing: Float,
        bearingToleranceDeg: Float = 85f,
        currentTimeMs: Long = System.currentTimeMillis()
    ): ApproachState {
        // If there's a huge time gap (e.g. > 90 seconds) since the last fix,
        // it means we lost GPS (tunnel, elevator, background throttle).
        // Comparing distance across a huge gap can falsely trigger a massive "decreasing" delta.
        // We must reset the history to prevent this "teleportation" ratchet bug.
        if (timeHistory.isNotEmpty() && (currentTimeMs - timeHistory.last()) > 90_000L) {
            reset()
        }

        distanceHistory.add(currentDistanceKm)
        timeHistory.add(currentTimeMs)
        if (distanceHistory.size > historyCapacity) {
            distanceHistory.removeAt(0)
            timeHistory.removeAt(0)
        }

        if (userBearing >= 0) {
            bearingHistory.add(userBearing)
            if (bearingHistory.size > historyCapacity) {
                bearingHistory.removeAt(0)
            }
        }

        // Need at least 3 points for reliable trend analysis
        if (distanceHistory.size < 3) {
            return ApproachState.APPROACHING // Default to permissive until history accumulates
        }

        // 1. Distance trend analysis: delta = last - first in window
        val netDistanceDelta = distanceHistory.last() - distanceHistory.first()
        val isDistanceDecreasing = netDistanceDelta < -0.05 // At least 50m closer
        val isStationary = abs(netDistanceDelta) <= 0.05 // Within 50m GPS jitter/stationary
        val isSteadyApproach = netDistanceDelta in -0.05..0.08 // Slowly moving towards or just crawling in traffic

        // 2. Heading alignment (if GPS bearing is available)
        var isHeadingToward = true
        if (userBearing >= 0) {
            var diff = abs(userBearing - targetBearing)
            if (diff > 180f) diff = 360f - diff
            isHeadingToward = diff <= bearingToleranceDeg // Within forward cone specified by mode
        }

        return when {
            isDistanceDecreasing && isHeadingToward -> ApproachState.APPROACHING
            netDistanceDelta > 0.08 && !isHeadingToward -> ApproachState.RECEDING
            isStationary -> ApproachState.APPROACHING // Stationary at traffic lights / bus stops: keep approaching status
            isSteadyApproach && isHeadingToward -> ApproachState.APPROACHING // Fix unclassified dead zone: slow traffic approach
            !isDistanceDecreasing && isHeadingToward -> {
                // If within 1.0 km of destination, never treat as detour loop
                if (currentDistanceKm <= 1.0) ApproachState.APPROACHING else ApproachState.CIRCLING_LOOP
            }
            else -> ApproachState.UNCERTAIN
        }
    }
}

