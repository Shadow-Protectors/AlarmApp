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

    fun reset() {
        distanceHistory.clear()
        bearingHistory.clear()
    }

    /**
     * Records a new GPS fix and determines whether the vehicle is genuinely approaching the destination.
     * @param currentDistanceKm Current distance to stop
     * @param userBearing Degrees (0..360) from device GPS (or -1 if unavailable)
     * @param targetBearing Degrees (0..360) directly toward destination
     */
    fun evaluateApproach(
        currentDistanceKm: Double,
        userBearing: Float,
        targetBearing: Float
    ): ApproachState {
        distanceHistory.add(currentDistanceKm)
        if (distanceHistory.size > historyCapacity) {
            distanceHistory.removeAt(0)
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

        // 2. Heading alignment (if GPS bearing is available)
        var isHeadingToward = true
        if (userBearing >= 0) {
            var diff = abs(userBearing - targetBearing)
            if (diff > 180f) diff = 360f - diff
            isHeadingToward = diff <= 85f // Within forward cone
        }

        return when {
            isDistanceDecreasing && isHeadingToward -> ApproachState.APPROACHING
            netDistanceDelta > 0.1 && !isHeadingToward -> ApproachState.RECEDING
            !isDistanceDecreasing && isHeadingToward -> ApproachState.CIRCLING_LOOP
            else -> ApproachState.UNCERTAIN
        }
    }
}
