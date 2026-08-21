package com.shadowprotectors.alarmapp.service

import com.shadowprotectors.alarmapp.alert.AlertLevel
import com.shadowprotectors.alarmapp.engine.ApproachState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class TrackingState(
    val isTracking: Boolean = false,
    val destinationName: String = "",
    val destLat: Double = 0.0,
    val destLng: Double = 0.0,
    val currentLat: Double = 0.0,
    val currentLng: Double = 0.0,
    val distanceKm: Double = 0.0,
    val speedKmh: Double = 0.0,
    val etaMinutes: Int? = null,
    val approachState: ApproachState = ApproachState.UNCERTAIN,
    val alertLevel: AlertLevel = AlertLevel.NONE
)

object ServiceEventBus {
    private val _trackingState = MutableStateFlow(TrackingState())
    val trackingState: StateFlow<TrackingState> = _trackingState.asStateFlow()

    fun updateState(newState: TrackingState) {
        _trackingState.value = newState
    }

    fun resetState() {
        _trackingState.value = TrackingState()
    }
}
