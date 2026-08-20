package com.shadowprotectors.alarmapp

import android.Manifest
import android.content.pm.PackageManager
import android.location.Location
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.shadowprotectors.alarmapp.databinding.ActivityMainBinding
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private var locationCallback: LocationCallback? = null
    private var isTracking = false

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val fineLocationGranted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] ?: false
        val coarseLocationGranted = permissions[Manifest.permission.ACCESS_COARSE_LOCATION] ?: false

        if (fineLocationGranted || coarseLocationGranted) {
            startLocationTracking()
        } else {
            Toast.makeText(this, "Location permission is required for travel alerts", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        binding.btnToggleTracking.setOnClickListener {
            if (isTracking) {
                stopLocationTracking()
            } else {
                checkPermissionsAndStart()
            }
        }
    }

    private fun checkPermissionsAndStart() {
        val permissions = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val missingPermissions = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missingPermissions.isNotEmpty()) {
            requestPermissionLauncher.launch(missingPermissions.toTypedArray())
        } else {
            startLocationTracking()
        }
    }

    private fun startLocationTracking() {
        val destLat = binding.etLatitude.text.toString().toDoubleOrNull()
        val destLng = binding.etLongitude.text.toString().toDoubleOrNull()

        if (destLat == null || destLng == null) {
            Toast.makeText(this, "Please enter valid coordinates", Toast.LENGTH_SHORT).show()
            return
        }

        val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 5000)
            .setMinUpdateIntervalMillis(3000)
            .build()

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                for (location in locationResult.locations) {
                    updateDistance(location, destLat, destLng)
                }
            }
        }

        try {
            fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback!!, mainLooper)
            isTracking = true
            binding.tvStatus.text = "GPS Active — Tracking in real-time"
            binding.btnToggleTracking.text = "Stop Tracking"
            binding.btnToggleTracking.setBackgroundColor(ContextCompat.getColor(this, R.color.status_red))
            Toast.makeText(this, "Tracking started!", Toast.LENGTH_SHORT).show()
        } catch (e: SecurityException) {
            Toast.makeText(this, "Permission error: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun stopLocationTracking() {
        locationCallback?.let { fusedLocationClient.removeLocationUpdates(it) }
        isTracking = false
        binding.tvStatus.text = "GPS Idle — Tap Start to track"
        binding.tvDistance.text = "Distance to stop: -- km"
        binding.btnToggleTracking.text = "Start Destination Alarm"
        binding.btnToggleTracking.setBackgroundColor(ContextCompat.getColor(this, R.color.primary))
        Toast.makeText(this, "Tracking stopped", Toast.LENGTH_SHORT).show()
    }

    private fun updateDistance(currentLocation: Location, destLat: Double, destLng: Double) {
        val distanceKm = calculateHaversineDistance(
            currentLocation.latitude, currentLocation.longitude,
            destLat, destLng
        )

        binding.tvDistance.text = String.format("Distance to stop: %.2f km", distanceKm)

        if (distanceKm <= 0.5) {
            binding.tvStatus.text = "🚨 Level 3 Alert: Stop is within 500 meters!"
        } else if (distanceKm <= 2.0) {
            binding.tvStatus.text = "⚠️ Level 2 Alert: Approaching stop (within 2 km)"
        } else if (distanceKm <= 5.0) {
            binding.tvStatus.text = "🔔 Level 1 Alert: Within 5 km"
        } else {
            binding.tvStatus.text = "Tracking active (On route)"
        }
    }

    private fun calculateHaversineDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371.0 // Earth radius in kilometers
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                sin(dLon / 2) * sin(dLon / 2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return r * c
    }

    override fun onDestroy() {
        super.onDestroy()
        stopLocationTracking()
    }
}
