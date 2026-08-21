package com.shadowprotectors.alarmapp

import android.Manifest
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.shadowprotectors.alarmapp.alert.AlertLevel
import com.shadowprotectors.alarmapp.data.DatabaseHelper
import com.shadowprotectors.alarmapp.databinding.ActivityMainBinding
import com.shadowprotectors.alarmapp.engine.ApproachState
import com.shadowprotectors.alarmapp.service.LocationTrackingService
import com.shadowprotectors.alarmapp.service.ServiceEventBus
import com.shadowprotectors.alarmapp.service.TrackingState
import kotlinx.coroutines.launch
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var databaseHelper: DatabaseHelper
    private var selectedLanguageCode = "en"
    private var isCurrentlyTracking = false

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val fineGranted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true
        val coarseGranted = permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true

        if (fineGranted || coarseGranted) {
            startTrackingService()
        } else {
            Toast.makeText(this, "Location permission is required for travel alarm", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        databaseHelper = DatabaseHelper(this)

        // Handle edge-to-edge status bar insets properly
        ViewCompat.setOnApplyWindowInsetsListener(binding.coordinatorLayout) { _, insets ->
            val statusBarHeight = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            binding.appBarLayout.setPadding(0, statusBarHeight, 0, 0)
            insets
        }

        setupPresetChips()
        setupLanguageButtons()
        setupListeners()
        observeTrackingState()
    }

    private fun setupPresetChips() {
        binding.chipMadurai.setOnClickListener {
            binding.etDestName.setText("Madurai Junction")
            binding.etLatitude.setText("9.9196")
            binding.etLongitude.setText("78.1100")
        }

        binding.chipCentral.setOnClickListener {
            binding.etDestName.setText("Chennai Central")
            binding.etLatitude.setText("13.0827")
            binding.etLongitude.setText("80.2707")
        }

        binding.chipAirport.setOnClickListener {
            binding.etDestName.setText("Chennai Airport")
            binding.etLatitude.setText("12.9941")
            binding.etLongitude.setText("80.1709")
        }

        binding.chipCoimbatore.setOnClickListener {
            binding.etDestName.setText("Coimbatore Junction")
            binding.etLatitude.setText("10.9972")
            binding.etLongitude.setText("76.9634")
        }
    }

    private fun setupLanguageButtons() {
        val primaryColor = ContextCompat.getColor(this, R.color.primary)
        val mutedColor = Color.parseColor("#E2E8F0")
        val whiteColor = ContextCompat.getColor(this, R.color.white)
        val darkTextColor = ContextCompat.getColor(this, R.color.text_primary)

        fun updateUi(selected: String) {
            selectedLanguageCode = selected

            binding.btnLangEnglish.backgroundTintList = ColorStateList.valueOf(if (selected == "en") primaryColor else mutedColor)
            binding.btnLangEnglish.setTextColor(if (selected == "en") whiteColor else darkTextColor)

            binding.btnLangTamil.backgroundTintList = ColorStateList.valueOf(if (selected == "ta") primaryColor else mutedColor)
            binding.btnLangTamil.setTextColor(if (selected == "ta") whiteColor else darkTextColor)

            binding.btnLangHindi.backgroundTintList = ColorStateList.valueOf(if (selected == "hi") primaryColor else mutedColor)
            binding.btnLangHindi.setTextColor(if (selected == "hi") whiteColor else darkTextColor)
        }

        binding.btnLangEnglish.setOnClickListener { updateUi("en") }
        binding.btnLangTamil.setOnClickListener { updateUi("ta") }
        binding.btnLangHindi.setOnClickListener { updateUi("hi") }
    }

    private fun setupListeners() {
        binding.btnToggleTracking.setOnClickListener {
            if (isCurrentlyTracking) {
                stopTrackingService()
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

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            permissions.add(Manifest.permission.FOREGROUND_SERVICE_LOCATION)
        }

        val missing = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missing.isEmpty()) {
            startTrackingService()
        } else {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    private fun startTrackingService() {
        val name = binding.etDestName.text.toString().trim().ifEmpty { "Destination" }
        val latStr = binding.etLatitude.text.toString()
        val lngStr = binding.etLongitude.text.toString()

        val lat = latStr.toDoubleOrNull()
        val lng = lngStr.toDoubleOrNull()

        if (lat == null || lng == null) {
            Toast.makeText(this, "Please enter valid Latitude & Longitude", Toast.LENGTH_SHORT).show()
            return
        }

        LocationTrackingService.startService(this, name, lat, lng, selectedLanguageCode)
        Toast.makeText(this, "Background alarm active for $name", Toast.LENGTH_SHORT).show()
    }

    private fun stopTrackingService() {
        LocationTrackingService.stopService(this)
        Toast.makeText(this, "Destination alarm stopped", Toast.LENGTH_SHORT).show()
    }

    private fun observeTrackingState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                ServiceEventBus.trackingState.collect { state ->
                    updateUiFromTrackingState(state)
                }
            }
        }
    }

    private fun updateUiFromTrackingState(state: TrackingState) {
        isCurrentlyTracking = state.isTracking

        if (state.isTracking) {
            binding.btnToggleTracking.text = "Stop Background Alarm"
            binding.btnToggleTracking.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.status_red))

            binding.tvStatus.text = "Tracking to ${state.destinationName}"
            binding.tvDistance.text = String.format(Locale.US, "Distance to stop: %.2f km", state.distanceKm)
            binding.tvSpeed.text = String.format(Locale.US, "Speed: %.1f km/h", state.speedKmh)
            binding.tvEta.text = if (state.etaMinutes != null) "ETA: ~${state.etaMinutes} mins" else "ETA: Calculating..."

            // Approach state badge
            when (state.approachState) {
                ApproachState.APPROACHING -> {
                    binding.tvApproachBadge.text = "🟢 Approaching"
                    binding.tvApproachBadge.setTextColor(ContextCompat.getColor(this, R.color.status_green_text))
                    binding.tvApproachBadge.setBackgroundColor(ContextCompat.getColor(this, R.color.status_green_bg))
                }
                ApproachState.CIRCLING_LOOP -> {
                    binding.tvApproachBadge.text = "🟡 Detour / Loop Filtered"
                    binding.tvApproachBadge.setTextColor(ContextCompat.getColor(this, R.color.status_amber_text))
                    binding.tvApproachBadge.setBackgroundColor(ContextCompat.getColor(this, R.color.status_amber_bg))
                }
                ApproachState.RECEDING -> {
                    binding.tvApproachBadge.text = "🔴 Moving Away"
                    binding.tvApproachBadge.setTextColor(ContextCompat.getColor(this, R.color.status_red_text))
                    binding.tvApproachBadge.setBackgroundColor(ContextCompat.getColor(this, R.color.status_red_bg))
                }
                ApproachState.UNCERTAIN -> {
                    binding.tvApproachBadge.text = "Locating..."
                    binding.tvApproachBadge.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
                    binding.tvApproachBadge.setBackgroundColor(Color.parseColor("#E2E8F0"))
                }
            }

            // Alert level status card styling
            when (state.alertLevel) {
                AlertLevel.LEVEL_4_FULL_ALARM -> {
                    binding.cardTrackingStatus.setCardBackgroundColor(ContextCompat.getColor(this, R.color.status_red_bg))
                    binding.tvStatus.setTextColor(ContextCompat.getColor(this, R.color.status_red_text))
                }
                AlertLevel.LEVEL_3_VOICE -> {
                    binding.cardTrackingStatus.setCardBackgroundColor(Color.parseColor("#EEF2FF"))
                    binding.tvStatus.setTextColor(Color.parseColor("#3730A3"))
                }
                AlertLevel.LEVEL_2_VIBRATE -> {
                    binding.cardTrackingStatus.setCardBackgroundColor(ContextCompat.getColor(this, R.color.status_amber_bg))
                    binding.tvStatus.setTextColor(ContextCompat.getColor(this, R.color.status_amber_text))
                }
                AlertLevel.LEVEL_1_SILENT -> {
                    binding.cardTrackingStatus.setCardBackgroundColor(ContextCompat.getColor(this, R.color.status_green_bg))
                    binding.tvStatus.setTextColor(ContextCompat.getColor(this, R.color.status_green_text))
                }
                AlertLevel.NONE -> {
                    binding.cardTrackingStatus.setCardBackgroundColor(ContextCompat.getColor(this, R.color.surface_card))
                    binding.tvStatus.setTextColor(ContextCompat.getColor(this, R.color.text_primary))
                }
            }
        } else {
            binding.btnToggleTracking.text = "Start Background Destination Alarm"
            binding.btnToggleTracking.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.primary))
            binding.tvStatus.text = "GPS Idle — Tap Start to track in background"
            binding.tvDistance.text = "Distance to stop: -- km"
            binding.tvSpeed.text = "Speed: -- km/h"
            binding.tvEta.text = "ETA: --"
            binding.tvApproachBadge.text = "Idle"
            binding.tvApproachBadge.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
            binding.tvApproachBadge.setBackgroundColor(Color.parseColor("#E2E8F0"))
            binding.cardTrackingStatus.setCardBackgroundColor(ContextCompat.getColor(this, R.color.surface_card))
            binding.tvStatus.setTextColor(ContextCompat.getColor(this, R.color.text_primary))
        }
    }
}
