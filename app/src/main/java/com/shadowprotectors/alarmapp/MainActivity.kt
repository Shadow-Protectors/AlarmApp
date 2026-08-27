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
    private var selectedTripMode: com.shadowprotectors.alarmapp.engine.TripMode = com.shadowprotectors.alarmapp.engine.TripMode.BUS_CAR
    private var isCurrentlyTracking = false
    private var voiceAlertHelper: com.shadowprotectors.alarmapp.alert.VoiceAlertHelper? = null

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

        setupLanguageButtons()
        setupListeners()
        observeTrackingState()

        // Handle shared location links from WhatsApp or Maps
        handleIncomingIntent(intent)

        // Restore last selected destination if available (no hardcoded placeholders)
        restoreSavedDestination()
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

        // Save to SharedPreferences for app restart persistence
        val prefs = getSharedPreferences("travel_alarm_prefs", Context.MODE_PRIVATE)
        prefs.edit()
            .putString("PREF_DEST_NAME", name)
            .putFloat("PREF_DEST_LAT", lat.toFloat())
            .putFloat("PREF_DEST_LNG", lng.toFloat())
            .apply()

        // Save to SQLite database
        databaseHelper.insertDestination(
            Destination(name = name, latitude = lat, longitude = lng, isPreset = false)
        )

        // Update landmark confirmation
        updateLandmarkConfirmation(lat, lng)

        // Calculate and show distance from current GPS if available
        try {
            val fusedClient = com.google.android.gms.location.LocationServices.getFusedLocationProviderClient(this)
            fusedClient.lastLocation.addOnSuccessListener { loc ->
                if (loc != null && !isCurrentlyTracking) {
                    val dist = com.shadowprotectors.alarmapp.engine.DistanceEngine.calculateDistanceKm(loc.latitude, loc.longitude, lat, lng)
                    binding.tvDistance.text = String.format(Locale.US, "Distance to stop: %.2f km", dist)
                }
            }
        } catch (e: SecurityException) {}
    }

    private fun restoreSavedDestination() {
        val prefs = getSharedPreferences("travel_alarm_prefs", Context.MODE_PRIVATE)
        val savedName = prefs.getString("PREF_DEST_NAME", null)
        val savedLat = prefs.getFloat("PREF_DEST_LAT", -999f)
        val savedLng = prefs.getFloat("PREF_DEST_LNG", -999f)

        if (!savedName.isNullOrBlank() && savedLat != -999f && savedLng != -999f) {
            val lat = savedLat.toDouble()
            val lng = savedLng.toDouble()
            binding.etDestName.setText(savedName)
            binding.etLatitude.setText(String.format(Locale.US, "%.5f", lat))
            binding.etLongitude.setText(String.format(Locale.US, "%.5f", lng))
            updateLandmarkConfirmation(lat, lng)
        } else {
            // No destination saved yet — keep fields clean without dummy placeholders
            binding.etDestName.setText("")
            binding.etLatitude.setText("")
            binding.etLongitude.setText("")
            binding.cardLandmarkConfirmation.isVisible = false
        }
    }

    private fun updateLandmarkConfirmation(lat: Double, lng: Double) {
        lifecycleScope.launch {
            val landmarkText = geocodingHelper.getLandmarkConfirmation(lat, lng)
            binding.tvLandmarkConfirmation.text = "📍 Verified: $landmarkText"
            binding.cardLandmarkConfirmation.isVisible = true
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

        // 3. Toggle Tracking Button (Start)
        binding.btnToggleTracking.setOnClickListener {
            if (isCurrentlyTracking) {
                stopTrackingService()
            } else {
                checkPermissionsAndStart()
            }
        }

        // 4. Explicit Stop Tracking Button
        binding.btnStopTracking.setOnClickListener {
            stopTrackingService()
        }

        // 4. Test / Preview Alarm Button
        binding.btnTestAlarm.setOnClickListener {
            if (com.shadowprotectors.alarmapp.alert.AudioAlarmHelper.isPlaying()) {
                // If alarm is currently active, stop it immediately
                com.shadowprotectors.alarmapp.alert.AudioAlarmHelper.stopFullAlarm(this)
                voiceAlertHelper?.stop()
                binding.btnTestAlarm.text = "🧪 Test Alarm Siren, Voice & Screen"
                binding.btnTestAlarm.setTextColor(ContextCompat.getColor(this, R.color.primary))
                Toast.makeText(this, "Alarm Stopped", Toast.LENGTH_SHORT).show()
            } else {
                val destName = binding.etDestName.text.toString().trim().ifEmpty { "Madurai Junction" }

                // 1. Play Voice TTS in chosen language
                if (voiceAlertHelper == null) {
                    voiceAlertHelper = com.shadowprotectors.alarmapp.alert.VoiceAlertHelper(this)
                }
                voiceAlertHelper?.currentLanguage = when (selectedLanguageCode) {
                    "ta" -> com.shadowprotectors.alarmapp.alert.SupportedLanguage.TAMIL
                    "hi" -> com.shadowprotectors.alarmapp.alert.SupportedLanguage.HINDI
                    else -> com.shadowprotectors.alarmapp.alert.SupportedLanguage.ENGLISH
                }
                voiceAlertHelper?.speakApproachAlert(destName, 0.5)

                // 2. Play Alarm Sound + Vibration using Singleton
                com.shadowprotectors.alarmapp.alert.AudioAlarmHelper.startFullAlarm(this)
                binding.btnTestAlarm.text = "🛑 Stop Alarm Siren"
                binding.btnTestAlarm.setTextColor(ContextCompat.getColor(this, R.color.status_red))

                // 3. Launch Full-Screen Wake Activity
                val triggerIntent = Intent(this, com.shadowprotectors.alarmapp.ui.AlarmTriggerActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                    putExtra(com.shadowprotectors.alarmapp.ui.AlarmTriggerActivity.EXTRA_DEST_NAME, destName)
                    putExtra(com.shadowprotectors.alarmapp.ui.AlarmTriggerActivity.EXTRA_DISTANCE_KM, 0.5)
                }
                startActivity(triggerIntent)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (com.shadowprotectors.alarmapp.alert.AudioAlarmHelper.isPlaying()) {
            binding.btnTestAlarm.text = "🛑 Stop Alarm Siren"
            binding.btnTestAlarm.setTextColor(ContextCompat.getColor(this, R.color.status_red))
        } else {
            binding.btnTestAlarm.text = "🧪 Test Alarm Siren, Voice & Screen"
            binding.btnTestAlarm.setTextColor(ContextCompat.getColor(this, R.color.primary))
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        voiceAlertHelper?.shutdown()
    }

    private fun showImportLinkDialog() {
        val dialogBinding = DialogImportLinkBinding.inflate(LayoutInflater.from(this))
        val dialog = AlertDialog.Builder(this)
            .setView(dialogBinding.root)
            .create()

        var parseJob: Job? = null
        var resolvedLocation: ParsedLocation? = null
        var resolvedName: String? = null

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

        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
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

        LocationTrackingService.startService(this, name, lat, lng, selectedLanguageCode, selectedTripMode.name)
        Toast.makeText(this, "${selectedTripMode.icon} Background alarm active (${selectedTripMode.displayName})", Toast.LENGTH_SHORT).show()
    }

    private fun stopTrackingService() {
        LocationTrackingService.stopService(this)
        com.shadowprotectors.alarmapp.alert.AudioAlarmHelper.stopFullAlarm(this)
        voiceAlertHelper?.stop()
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        notificationManager.cancelAll()
        ServiceEventBus.resetState()
        updateUiFromTrackingState(TrackingState())
        Toast.makeText(this, "Background tracking stopped & terminated", Toast.LENGTH_SHORT).show()
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
        val uiState = resolveBusUiState(state)
        renderBusUiState(uiState)
    }

    private fun resolveBusUiState(trackingState: TrackingState): com.shadowprotectors.alarmapp.engine.BusUiState {
        val destName = binding.etDestName.text.toString().trim()
        val latStr = binding.etLatitude.text.toString().trim()
        val lngStr = binding.etLongitude.text.toString().trim()

        val locationManager = getSystemService(Context.LOCATION_SERVICE) as android.location.LocationManager
        val isGpsEnabled = locationManager.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER) ||
                locationManager.isProviderEnabled(android.location.LocationManager.NETWORK_PROVIDER)

        if (!isGpsEnabled) {
            return com.shadowprotectors.alarmapp.engine.BusUiState.Disabled
        }

        if (trackingState.isTracking) {
            return if (trackingState.alertLevel == AlertLevel.LEVEL_4_FULL_ALARM) {
                com.shadowprotectors.alarmapp.engine.BusUiState.LimitReached(trackingState.destinationName)
            } else if (trackingState.distanceKm == Double.MAX_VALUE || trackingState.distanceKm <= 0.0) {
                com.shadowprotectors.alarmapp.engine.BusUiState.Partial("Acquiring GPS location fix…")
            } else {
                com.shadowprotectors.alarmapp.engine.BusUiState.Offline(
                    destinationName = trackingState.destinationName,
                    distanceKm = trackingState.distanceKm,
                    alertLevel = trackingState.alertLevel,
                    approachState = trackingState.approachState
                )
            }
        }

        if (destName.isEmpty() && latStr.isEmpty() && lngStr.isEmpty()) {
            return com.shadowprotectors.alarmapp.engine.BusUiState.Empty
        }

        val lat = latStr.toDoubleOrNull()
        val lng = lngStr.toDoubleOrNull()
        if (latStr.isNotEmpty() && (lat == null || lat < -90.0 || lat > 90.0)) {
            return com.shadowprotectors.alarmapp.engine.BusUiState.Error("Invalid Latitude (-90 to +90)")
        }
        if (lngStr.isNotEmpty() && (lng == null || lng < -180.0 || lng > 180.0)) {
            return com.shadowprotectors.alarmapp.engine.BusUiState.Error("Invalid Longitude (-180 to +180)")
        }

        return com.shadowprotectors.alarmapp.engine.BusUiState.Idle
    }

    private fun renderBusUiState(state: com.shadowprotectors.alarmapp.engine.BusUiState) {
        when (state) {
            is com.shadowprotectors.alarmapp.engine.BusUiState.Idle -> {
                binding.btnToggleTracking.isVisible = true
                binding.btnToggleTracking.text = "Start Background Destination Alarm"
                binding.btnToggleTracking.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.primary))
                binding.btnStopTracking.isVisible = false

                binding.tvStatus.text = "🚌 Bus Alarm Idle — Ready to start"
                binding.tvDistance.text = "Distance to stop: -- km"
                binding.tvEta.text = "Waiting to start…"
                binding.tvApproachBadge.text = "Idle"
                binding.tvApproachBadge.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
                binding.tvApproachBadge.setBackgroundColor(Color.parseColor("#E2E8F0"))
                binding.cardTrackingStatus.setCardBackgroundColor(ContextCompat.getColor(this, R.color.surface_card))
                binding.tvStatus.setTextColor(ContextCompat.getColor(this, R.color.text_primary))
            }

            is com.shadowprotectors.alarmapp.engine.BusUiState.Empty -> {
                renderBusUiState(com.shadowprotectors.alarmapp.engine.BusUiState.Idle)
                binding.tvStatus.text = "No Destination Selected — Pick location below"
                binding.tvApproachBadge.text = "Empty"
            }

            is com.shadowprotectors.alarmapp.engine.BusUiState.Loading -> {
                binding.tvStatus.text = "⏳ ${state.message}"
                binding.tvApproachBadge.text = "Loading"
                binding.tvApproachBadge.setTextColor(ContextCompat.getColor(this, R.color.primary))
                binding.tvApproachBadge.setBackgroundColor(Color.parseColor("#EFF6FF"))
            }

            is com.shadowprotectors.alarmapp.engine.BusUiState.Error -> {
                binding.tvStatus.text = "⚠️ ${state.message}"
                binding.tvApproachBadge.text = "Error"
                binding.tvApproachBadge.setTextColor(ContextCompat.getColor(this, R.color.status_red_text))
                binding.tvApproachBadge.setBackgroundColor(ContextCompat.getColor(this, R.color.status_red_bg))
                binding.cardTrackingStatus.setCardBackgroundColor(ContextCompat.getColor(this, R.color.status_red_bg))
            }

            is com.shadowprotectors.alarmapp.engine.BusUiState.Partial -> {
                binding.btnToggleTracking.isVisible = false
                binding.btnStopTracking.isVisible = true
                binding.tvStatus.text = "🟡 ${state.message}"
                binding.tvDistance.text = "Distance: Locating…"
                binding.tvEta.text = "Acquiring GPS fix…"
                binding.tvApproachBadge.text = "Locating GPS"
                binding.tvApproachBadge.setTextColor(ContextCompat.getColor(this, R.color.status_amber_text))
                binding.tvApproachBadge.setBackgroundColor(ContextCompat.getColor(this, R.color.status_amber_bg))
                binding.cardTrackingStatus.setCardBackgroundColor(ContextCompat.getColor(this, R.color.status_amber_bg))
            }

            is com.shadowprotectors.alarmapp.engine.BusUiState.Offline -> {
                binding.btnToggleTracking.isVisible = false
                binding.btnStopTracking.isVisible = true
                binding.tvStatus.text = "Tracking to ${state.destinationName}"
                binding.tvDistance.text = String.format(Locale.US, "Distance to stop: %.2f km", state.distanceKm)

                val currentState = ServiceEventBus.trackingState.value
                binding.tvEta.text = formatEta(currentState.etaMinutes, currentState.speedKmh)

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

                when (state.alertLevel) {
                    AlertLevel.LEVEL_4_FULL_ALARM -> {
                        binding.cardTrackingStatus.setCardBackgroundColor(ContextCompat.getColor(this, R.color.status_red_bg))
                        binding.tvStatus.setTextColor(ContextCompat.getColor(this, R.color.status_red_text))
                        binding.tvAlertLevelBadge.text = "🚨 Level 4: Full Emergency Siren Active (0.5 km)"
                        binding.tvAlertLevelBadge.setTextColor(ContextCompat.getColor(this, R.color.status_red_text))
                        binding.tvAlertLevelBadge.setBackgroundColor(ContextCompat.getColor(this, R.color.status_red_bg))
                    }
                    AlertLevel.LEVEL_3_VOICE -> {
                        binding.cardTrackingStatus.setCardBackgroundColor(Color.parseColor("#EEF2FF"))
                        binding.tvStatus.setTextColor(Color.parseColor("#3730A3"))
                        binding.tvAlertLevelBadge.text = "🔊 Level 3: Regional Voice Announcement (1.0 km)"
                        binding.tvAlertLevelBadge.setTextColor(Color.parseColor("#3730A3"))
                        binding.tvAlertLevelBadge.setBackgroundColor(Color.parseColor("#EEF2FF"))
                    }
                    AlertLevel.LEVEL_2_VIBRATE -> {
                        binding.cardTrackingStatus.setCardBackgroundColor(ContextCompat.getColor(this, R.color.status_amber_bg))
                        binding.tvStatus.setTextColor(ContextCompat.getColor(this, R.color.status_amber_text))
                        binding.tvAlertLevelBadge.text = "📳 Level 2: Vibration Pulse Alert (2.0 km)"
                        binding.tvAlertLevelBadge.setTextColor(ContextCompat.getColor(this, R.color.status_amber_text))
                        binding.tvAlertLevelBadge.setBackgroundColor(ContextCompat.getColor(this, R.color.status_amber_bg))
                    }
                    AlertLevel.LEVEL_1_SILENT -> {
                        binding.cardTrackingStatus.setCardBackgroundColor(ContextCompat.getColor(this, R.color.status_green_bg))
                        binding.tvStatus.setTextColor(ContextCompat.getColor(this, R.color.status_green_text))
                        binding.tvAlertLevelBadge.text = "🔵 Level 1: Silent Heads-Up Banner (5.0 km)"
                        binding.tvAlertLevelBadge.setTextColor(ContextCompat.getColor(this, R.color.status_green_text))
                        binding.tvAlertLevelBadge.setBackgroundColor(ContextCompat.getColor(this, R.color.status_green_bg))
                    }
                    AlertLevel.NONE -> {
                        binding.cardTrackingStatus.setCardBackgroundColor(ContextCompat.getColor(this, R.color.surface_card))
                        binding.tvStatus.setTextColor(ContextCompat.getColor(this, R.color.text_primary))
                        binding.tvAlertLevelBadge.text = "📡 Monitoring Distance En Route"
                        binding.tvAlertLevelBadge.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
                        binding.tvAlertLevelBadge.setBackgroundColor(Color.parseColor("#1F94A3B8"))
                    }
                }
            }

            is com.shadowprotectors.alarmapp.engine.BusUiState.Disabled -> {
                binding.tvStatus.text = "🚫 Location (GPS) Disabled in Settings"
                binding.tvDistance.text = "Enable GPS to start alarm"
                binding.tvApproachBadge.text = "GPS Off"
                binding.tvApproachBadge.setTextColor(ContextCompat.getColor(this, R.color.status_red_text))
                binding.tvApproachBadge.setBackgroundColor(ContextCompat.getColor(this, R.color.status_red_bg))
                binding.cardTrackingStatus.setCardBackgroundColor(ContextCompat.getColor(this, R.color.status_red_bg))
            }

            is com.shadowprotectors.alarmapp.engine.BusUiState.LimitReached -> {
                binding.btnToggleTracking.isVisible = false
                binding.btnStopTracking.isVisible = true
                binding.tvStatus.text = "🛑 Destination Reached! (${state.destinationName})"
                binding.tvDistance.text = "Level 4 Wake Alarm Triggered"
                binding.tvApproachBadge.text = "ALARM ACTIVE"
                binding.tvApproachBadge.setTextColor(ContextCompat.getColor(this, R.color.status_red_text))
                binding.tvApproachBadge.setBackgroundColor(ContextCompat.getColor(this, R.color.status_red_bg))
                binding.cardTrackingStatus.setCardBackgroundColor(ContextCompat.getColor(this, R.color.status_red_bg))
            }
        }
    }

    /**
     * Returns a clean, human-readable ETA string for passengers.
     * Raw speed is never shown on UI — it is used only internally here to detect
     * if the vehicle is stationary (GPS jitter at < 2 km/h).
     */
    private fun formatEta(etaMinutes: Int?, speedKmh: Double): String {
        if (etaMinutes == null) {
            return if (speedKmh < 2.0) "Waiting to depart…" else "Calculating…"
        }
        return when {
            etaMinutes < 1    -> "Arriving now!"
            etaMinutes < 60   -> "~${etaMinutes} mins away"
            else -> {
                val hrs  = etaMinutes / 60
                val mins = etaMinutes % 60
                if (mins == 0) "~${hrs} hr${if (hrs > 1) "s" else ""}"
                else           "~${hrs} hr${if (hrs > 1) "s" else ""} ${mins} mins"
            }
        }
    }
}
