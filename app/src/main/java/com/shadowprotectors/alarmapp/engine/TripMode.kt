package com.shadowprotectors.alarmapp.engine

enum class TripMode(val displayName: String, val icon: String) {
    BUS_CAR("Bus / Car", "🚌"),
    TRAIN("Train Express", "🚆")
}

enum class ThresholdType {
    DISTANCE,
    ETA
}

data class AlertProfile(
    val mode: TripMode,
    val thresholdType: ThresholdType,
    val level1Value: Double, // 5.0 km or 15 mins
    val level2Value: Double, // 2.0 km or 7 mins
    val level3Value: Double, // 1.0 km or 3 mins
    val level4Value: Double, // 0.5 km or 1 min
    val pollingIntervalMs: Long, // e.g. 5000ms for Bus, 3000ms for Train
    val minUpdateIntervalMs: Long, // e.g. 2000ms for Bus, 1000ms for Train
    val bearingToleranceDeg: Float // e.g. 85f for Bus, 60f for Train
)

object AlertProfileResolver {
    fun resolve(mode: TripMode): AlertProfile {
        return when (mode) {
            TripMode.BUS_CAR -> AlertProfile(
                mode = TripMode.BUS_CAR,
                thresholdType = ThresholdType.DISTANCE,
                level1Value = 5.0,  // 5.0 km
                level2Value = 2.0,  // 2.0 km
                level3Value = 1.0,  // 1.0 km
                level4Value = 0.5,  // 0.5 km
                pollingIntervalMs = 5000L,
                minUpdateIntervalMs = 2000L,
                bearingToleranceDeg = 85f
            )
            TripMode.TRAIN -> AlertProfile(
                mode = TripMode.TRAIN,
                thresholdType = ThresholdType.ETA,
                level1Value = 15.0, // 15 mins ETA
                level2Value = 7.0,  // 7 mins ETA
                level3Value = 3.0,  // 3 mins ETA
                level4Value = 1.0,  // 1 min ETA
                pollingIntervalMs = 3000L,
                minUpdateIntervalMs = 1000L,
                bearingToleranceDeg = 60f
            )
        }
    }
}
