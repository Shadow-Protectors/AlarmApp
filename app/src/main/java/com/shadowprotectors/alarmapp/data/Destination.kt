package com.shadowprotectors.alarmapp.data

data class Destination(
    val id: Long = 0,
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val alertRadiusKm: Double = 0.5,
    val isPreset: Boolean = false,
    val createdAt: Long = System.currentTimeMillis()
)
