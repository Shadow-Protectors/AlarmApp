package com.shadowprotectors.alarmapp.engine

import com.shadowprotectors.alarmapp.alert.AlertLevel

/**
 * Explicit 8-State UI Machine for Bus / Car Travel Alarm
 */
sealed class BusUiState {
    /** 1. IDLE: Ready for user interaction, no active background tracking */
    object Idle : BusUiState()

    /** 2. EMPTY: Destination inputs cleared / no destination selected */
    object Empty : BusUiState()

    /** 3. LOADING: Geocoding address lookup or location resolution in progress */
    data class Loading(val message: String = "Resolving location…") : BusUiState()

    /** 4. ERROR: Invalid coordinates, search failure, or permission missing */
    data class Error(val message: String) : BusUiState()

    /** 5. PARTIAL: Destination configured, waiting for first GPS fix or weak GPS signal */
    data class Partial(val message: String = "Acquiring GPS location fix…") : BusUiState()

    /** 6. OFFLINE: Offline GPS tracking actively monitoring en route */
    data class Offline(
        val destinationName: String,
        val distanceKm: Double,
        val alertLevel: AlertLevel,
        val approachState: ApproachState
    ) : BusUiState()

    /** 7. DISABLED: Phone Location / GPS service turned off in system settings */
    object Disabled : BusUiState()

    /** 8. LIMIT_REACHED: Destination reached / Level 4 Alarm triggered */
    data class LimitReached(val destinationName: String) : BusUiState()
}
