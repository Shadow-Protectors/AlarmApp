package com.shadowprotectors.alarmapp

import android.Manifest
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.shadowprotectors.alarmapp.alert.AlertLevel
import com.shadowprotectors.alarmapp.data.DatabaseHelper
import com.shadowprotectors.alarmapp.data.Destination
import com.shadowprotectors.alarmapp.databinding.ActivityMainBinding
import com.shadowprotectors.alarmapp.databinding.DialogImportLinkBinding
import com.shadowprotectors.alarmapp.engine.ApproachState
import com.shadowprotectors.alarmapp.service.LocationTrackingService
import com.shadowprotectors.alarmapp.service.ServiceEventBus
import com.shadowprotectors.alarmapp.service.TrackingState
import com.shadowprotectors.alarmapp.ui.MapPickerBottomSheet
import com.shadowprotectors.alarmapp.util.GeocodingHelper
import com.shadowprotectors.alarmapp.util.LocationLinkParser
import com.shadowprotectors.alarmapp.util.ParsedLocation
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var databaseHelper: DatabaseHelper
    private lateinit var geocodingHelper: GeocodingHelper
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
        geocodingHelper = GeocodingHelper(this)

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

        // Handle shared location links from WhatsApp or Maps
        handleIncomingIntent(intent)

        // Initial landmark confirmation for default location
        updateLandmarkConfirmation(9.9196, 78.1100)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    private fun handleIncomingIntent(intent: Intent?) {
        if (intent == null) return

        when (intent.action) {
            Intent.ACTION_SEND -> {
                val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT)
                if (!sharedText.isNullOrBlank()) {
                    importSharedLocation(sharedText)
                }
            }
            Intent.ACTION_VIEW -> {
                val dataUri = intent.dataString
                if (!dataUri.isNullOrBlank()) {
                    importSharedLocation(dataUri)
                }
            }
        }
    }

    private fun importSharedLocation(rawInput: String) {
        lifecycleScope.launch {
            Toast.makeText(this@MainActivity, "Parsing shared destination...", Toast.LENGTH_SHORT).show()
            val parsed = LocationLinkParser.parse(rawInput)
            if (parsed != null) {
                val resolvedName = geocodingHelper.reverseGeocode(parsed.latitude, parsed.longitude)
                setDestination(resolvedName, parsed.latitude, parsed.longitude)
                Toast.makeText(this@MainActivity, "Destination set to: $resolvedName", Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(this@MainActivity, "Could not extract coordinates from link", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun setDestination(name: String, lat: Double, lng: Double) {
        binding.etDestName.setText(name)
        binding.etLatitude.setText(String.format(Locale.US, "%.5f", lat))
        binding.etLongitude.setText(String.format(Locale.US, "%.5f", lng))

        // Save to SQLite database
        databaseHelper.insertDestination(
            Destination(name = name, latitude = lat, longitude = lng, isPreset = false)
        )

        // Update landmark confirmation
        updateLandmarkConfirmation(lat, lng)
    }

    private fun updateLandmarkConfirmation(lat: Double, lng: Double) {
        lifecycleScope.launch {
            val landmarkText = geocodingHelper.getLandmarkConfirmation(lat, lng)
            binding.tvLandmarkConfirmation.text = "📍 Verified: $landmarkText"
            binding.cardLandmarkConfirmation.isVisible = true
        }
    }

    private fun setupPresetChips() {
        binding.chipMadurai.setOnClickListener {
            setDestination("Madurai Junction", 9.9196, 78.1100)
        }

        binding.chipCentral.setOnClickListener {
            setDestination("Chennai Central", 13.0827, 80.2707)
        }

        binding.chipAirport.setOnClickListener {
            setDestination("Chennai Airport", 12.9941, 80.1709)
        }

        binding.chipCoimbatore.setOnClickListener {
            setDestination("Coimbatore Junction", 10.9972, 76.9634)
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
        // 1. In-App Map Picker
        binding.btnOpenMapPicker.setOnClickListener {
            val mapPicker = MapPickerBottomSheet.newInstance()
            mapPicker.setOnDestinationSelectedListener(object : MapPickerBottomSheet.OnDestinationSelectedListener {
                override fun onDestinationSelected(name: String, latitude: Double, longitude: Double) {
                    setDestination(name, latitude, longitude)
                    Toast.makeText(this@MainActivity, "Destination set: $name", Toast.LENGTH_SHORT).show()
                }
            })
            mapPicker.show(supportFragmentManager, MapPickerBottomSheet.TAG)
        }

        // 2. Paste / Import Maps Link Dialog
        binding.btnImportLink.setOnClickListener {
            showImportLinkDialog()
        }

        // 3. Toggle Tracking Button
        binding.btnToggleTracking.setOnClickListener {
            if (isCurrentlyTracking) {
                stopTrackingService()
            } else {
                checkPermissionsAndStart()
            }
        }

        // 4. Test / Preview Alarm Button
        binding.btnTestAlarm.setOnClickListener {
            val destName = binding.etDestName.text.toString().trim().ifEmpty { "Madurai Junction" }
            
            // 1. Play Voice TTS in chosen language
            val voiceAlertHelper = com.shadowprotectors.alarmapp.alert.VoiceAlertHelper(this)
            voiceAlertHelper.currentLanguage = when (selectedLanguageCode) {
                "ta" -> com.shadowprotectors.alarmapp.alert.SupportedLanguage.TAMIL
                "hi" -> com.shadowprotectors.alarmapp.alert.SupportedLanguage.HINDI
                else -> com.shadowprotectors.alarmapp.alert.SupportedLanguage.ENGLISH
            }
            voiceAlertHelper.speakApproachAlert(destName, 0.5)

            // 2. Play Alarm Sound + Vibration
            val audioHelper = com.shadowprotectors.alarmapp.alert.AudioAlarmHelper(this)
            audioHelper.startFullAlarm()

            // 3. Launch Full-Screen Wake Activity
            val triggerIntent = Intent(this, com.shadowprotectors.alarmapp.ui.AlarmTriggerActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(com.shadowprotectors.alarmapp.ui.AlarmTriggerActivity.EXTRA_DEST_NAME, destName)
                putExtra(com.shadowprotectors.alarmapp.ui.AlarmTriggerActivity.EXTRA_DISTANCE_KM, 0.5)
            }
            startActivity(triggerIntent)
        }
    }

    private fun showImportLinkDialog() {
        val dialogBinding = DialogImportLinkBinding.inflate(LayoutInflater.from(this))
        val dialog = AlertDialog.Builder(this)
            .setView(dialogBinding.root)
            .create()

        var parseJob: Job? = null
        var resolvedLocation: ParsedLocation? = null
        var resolvedName: String? = null

        // Auto-check clipboard
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clipData = clipboard.primaryClip
        if (clipData != null && clipData.itemCount > 0) {
            val clipText = clipData.getItemAt(0).text?.toString() ?: ""
            if (clipText.contains("http") || clipText.contains("geo:") || clipText.matches(Regex(".*\\d+\\.\\d+.*"))) {
                dialogBinding.etLinkInput.setText(clipText)
            }
        }

        fun triggerParse(input: String) {
            parseJob?.cancel()
            val text = input.trim()
            if (text.isEmpty()) {
                dialogBinding.layoutParsingStatus.isVisible = false
                dialogBinding.cardResolvedPreview.isVisible = false
                return
            }

            dialogBinding.layoutParsingStatus.isVisible = true
            dialogBinding.tvParsingStatus.text = "Resolving link coordinates..."
            dialogBinding.cardResolvedPreview.isVisible = false

            parseJob = lifecycleScope.launch {
                delay(300)
                val parsed = LocationLinkParser.parse(text)
                if (parsed != null) {
                    resolvedLocation = parsed
                    val name = geocodingHelper.reverseGeocode(parsed.latitude, parsed.longitude)
                    resolvedName = name

                    dialogBinding.layoutParsingStatus.isVisible = false
                    dialogBinding.tvResolvedTitle.text = name
                    dialogBinding.tvResolvedCoords.text = String.format(Locale.US, "Lat: %.5f, Lng: %.5f", parsed.latitude, parsed.longitude)
                    dialogBinding.cardResolvedPreview.isVisible = true
                } else {
                    dialogBinding.layoutParsingStatus.isVisible = true
                    dialogBinding.tvParsingStatus.text = "No coordinates found in link"
                }
            }
        }

        dialogBinding.btnPasteClipboard.setOnClickListener {
            val clip = clipboard.primaryClip
            if (clip != null && clip.itemCount > 0) {
                val clipText = clip.getItemAt(0).text?.toString() ?: ""
                dialogBinding.etLinkInput.setText(clipText)
            }
        }

        dialogBinding.etLinkInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                triggerParse(s?.toString() ?: "")
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        // Initial parse if clipboard auto-filled
        val currentInput = dialogBinding.etLinkInput.text?.toString() ?: ""
        if (currentInput.isNotEmpty()) {
            triggerParse(currentInput)
        }

        dialogBinding.btnCancelImport.setOnClickListener {
            dialog.dismiss()
        }

        dialogBinding.btnConfirmImport.setOnClickListener {
            val location = resolvedLocation
            if (location != null) {
                val name = resolvedName ?: "Imported Stop"
                setDestination(name, location.latitude, location.longitude)
                Toast.makeText(this, "Destination set: $name", Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            } else {
                Toast.makeText(this, "Please paste a valid Google Maps link or coordinates", Toast.LENGTH_SHORT).show()
            }
        }

        dialog.show()
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
