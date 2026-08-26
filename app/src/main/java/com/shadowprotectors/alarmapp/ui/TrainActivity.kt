package com.shadowprotectors.alarmapp.ui

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
import com.shadowprotectors.alarmapp.ModeSelectionActivity
import com.shadowprotectors.alarmapp.R
import com.shadowprotectors.alarmapp.alert.AlertLevel
import com.shadowprotectors.alarmapp.data.DatabaseHelper
import com.shadowprotectors.alarmapp.data.Destination
import com.shadowprotectors.alarmapp.databinding.ActivityTrainBinding
import com.shadowprotectors.alarmapp.databinding.DialogImportLinkBinding
import com.shadowprotectors.alarmapp.engine.ApproachState
import com.shadowprotectors.alarmapp.engine.TripMode
import com.shadowprotectors.alarmapp.service.LocationTrackingService
import com.shadowprotectors.alarmapp.service.ServiceEventBus
import com.shadowprotectors.alarmapp.service.TrackingState
import com.shadowprotectors.alarmapp.util.GeocodingHelper
import com.shadowprotectors.alarmapp.util.LocationLinkParser
import com.shadowprotectors.alarmapp.util.ParsedLocation
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

class TrainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityTrainBinding
    private lateinit var databaseHelper: DatabaseHelper
    private lateinit var geocodingHelper: GeocodingHelper
    private var selectedLanguageCode = "en"
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
            Toast.makeText(this, "Location permission is required for train travel alarm", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTrainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        databaseHelper = DatabaseHelper(this)
        geocodingHelper = GeocodingHelper(this)

        ViewCompat.setOnApplyWindowInsetsListener(binding.coordinatorLayout) { _, insets ->
            val statusBarHeight = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            binding.appBarLayout.setPadding(0, statusBarHeight, 0, 0)
            insets
        }

        setupLanguageButtons()
        setupListeners()
        observeTrackingState()
        restoreSavedDestination()
    }

    private fun setDestination(name: String, lat: Double, lng: Double) {
        binding.etDestName.setText(name)
        binding.etLatitude.setText(String.format(Locale.US, "%.5f", lat))
        binding.etLongitude.setText(String.format(Locale.US, "%.5f", lng))

        val prefs = getSharedPreferences("travel_alarm_prefs", Context.MODE_PRIVATE)
        prefs.edit()
            .putString("PREF_TRAIN_DEST_NAME", name)
            .putFloat("PREF_TRAIN_DEST_LAT", lat.toFloat())
            .putFloat("PREF_TRAIN_DEST_LNG", lng.toFloat())
            .apply()

        databaseHelper.insertDestination(
            Destination(name = name, latitude = lat, longitude = lng, isPreset = false)
        )

        updateLandmarkConfirmation(lat, lng)
    }

    private fun restoreSavedDestination() {
        val prefs = getSharedPreferences("travel_alarm_prefs", Context.MODE_PRIVATE)
        val savedName = prefs.getString("PREF_TRAIN_DEST_NAME", null)
        val savedLat = prefs.getFloat("PREF_TRAIN_DEST_LAT", -999f)
        val savedLng = prefs.getFloat("PREF_TRAIN_DEST_LNG", -999f)

        if (!savedName.isNullOrBlank() && savedLat != -999f && savedLng != -999f) {
            val lat = savedLat.toDouble()
            val lng = savedLng.toDouble()
            binding.etDestName.setText(savedName)
            binding.etLatitude.setText(String.format(Locale.US, "%.5f", lat))
            binding.etLongitude.setText(String.format(Locale.US, "%.5f", lng))
            updateLandmarkConfirmation(lat, lng)
        } else {
            binding.etDestName.setText("")
            binding.etLatitude.setText("")
            binding.etLongitude.setText("")
            binding.cardLandmarkConfirmation.isVisible = false
        }
    }

    private fun updateLandmarkConfirmation(lat: Double, lng: Double) {
        lifecycleScope.launch {
            val landmarkText = geocodingHelper.getLandmarkConfirmation(lat, lng)
            binding.tvLandmarkConfirmation.text = "📍 Station Verified: $landmarkText"
            binding.cardLandmarkConfirmation.isVisible = true
        }
    }

    private fun setupLanguageButtons() {
        val primaryColor = Color.parseColor("#4338CA")
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
        binding.btnBackToLauncher.setOnClickListener {
            val intent = Intent(this, ModeSelectionActivity::class.java)
            intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            startActivity(intent)
            finish()
        }

        binding.btnOpenMapPicker.setOnClickListener {
            val mapPicker = MapPickerBottomSheet.newInstance()
            mapPicker.setOnDestinationSelectedListener(object : MapPickerBottomSheet.OnDestinationSelectedListener {
                override fun onDestinationSelected(name: String, latitude: Double, longitude: Double) {
                    setDestination(name, latitude, longitude)
                    Toast.makeText(this@TrainActivity, "Station set: $name", Toast.LENGTH_SHORT).show()
                }
            })
            mapPicker.show(supportFragmentManager, MapPickerBottomSheet.TAG)
        }

        binding.btnImportLink.setOnClickListener {
            showImportLinkDialog()
        }

        binding.btnToggleTracking.setOnClickListener {
            if (isCurrentlyTracking) {
                stopTrackingService()
            } else {
                checkPermissionsAndStart()
            }
        }

        binding.btnStopTracking.setOnClickListener {
            stopTrackingService()
        }

        binding.btnTestAlarm.setOnClickListener {
            if (com.shadowprotectors.alarmapp.alert.AudioAlarmHelper.isPlaying()) {
                com.shadowprotectors.alarmapp.alert.AudioAlarmHelper.stopFullAlarm(this)
                voiceAlertHelper?.stop()
                binding.btnTestAlarm.text = "🧪 Test Alarm Siren, Voice & Screen"
                binding.btnTestAlarm.setTextColor(Color.parseColor("#4338CA"))
                Toast.makeText(this, "Train Alarm Stopped", Toast.LENGTH_SHORT).show()
            } else {
                val destName = binding.etDestName.text.toString().trim().ifEmpty { "Madurai Junction Station" }

                if (voiceAlertHelper == null) {
                    voiceAlertHelper = com.shadowprotectors.alarmapp.alert.VoiceAlertHelper(this)
                }
                voiceAlertHelper?.currentLanguage = when (selectedLanguageCode) {
                    "ta" -> com.shadowprotectors.alarmapp.alert.SupportedLanguage.TAMIL
                    "hi" -> com.shadowprotectors.alarmapp.alert.SupportedLanguage.HINDI
                    else -> com.shadowprotectors.alarmapp.alert.SupportedLanguage.ENGLISH
                }
                voiceAlertHelper?.speakApproachAlert(destName, 0.5)

                com.shadowprotectors.alarmapp.alert.AudioAlarmHelper.startFullAlarm(this)
                binding.btnTestAlarm.text = "🛑 Stop Alarm Siren"
                binding.btnTestAlarm.setTextColor(ContextCompat.getColor(this, R.color.status_red))

                val triggerIntent = Intent(this, AlarmTriggerActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                    putExtra(AlarmTriggerActivity.EXTRA_DEST_NAME, destName)
                    putExtra(AlarmTriggerActivity.EXTRA_DISTANCE_KM, 0.5)
                }
                startActivity(triggerIntent)
            }
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

        fun triggerParse(input: String) {
            parseJob?.cancel()
            val text = input.trim()
            if (text.isEmpty()) {
                dialogBinding.layoutParsingStatus.isVisible = false
                dialogBinding.cardResolvedPreview.isVisible = false
                return
            }

            dialogBinding.layoutParsingStatus.isVisible = true
            dialogBinding.tvParsingStatus.text = "Resolving station coordinates..."
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

        dialogBinding.btnCancelImport.setOnClickListener { dialog.dismiss() }
        dialogBinding.btnConfirmImport.setOnClickListener {
            val location = resolvedLocation
            if (location != null) {
                val name = resolvedName ?: "Imported Station"
                setDestination(name, location.latitude, location.longitude)
                Toast.makeText(this, "Station set: $name", Toast.LENGTH_SHORT).show()
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
        val name = binding.etDestName.text.toString().trim().ifEmpty { "Train Station" }
        val latStr = binding.etLatitude.text.toString()
        val lngStr = binding.etLongitude.text.toString()

        val lat = latStr.toDoubleOrNull()
        val lng = lngStr.toDoubleOrNull()

        if (lat == null || lng == null) {
            Toast.makeText(this, "Please enter valid Station Latitude & Longitude", Toast.LENGTH_SHORT).show()
            return
        }

        LocationTrackingService.startService(this, name, lat, lng, selectedLanguageCode, TripMode.TRAIN.name)
        Toast.makeText(this, "🚆 Background Train Alarm Active", Toast.LENGTH_SHORT).show()
    }

    private fun stopTrackingService() {
        LocationTrackingService.stopService(this)
        com.shadowprotectors.alarmapp.alert.AudioAlarmHelper.stopFullAlarm(this)
        voiceAlertHelper?.stop()
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        notificationManager.cancelAll()
        ServiceEventBus.resetState()
        updateUiFromTrackingState(TrackingState())
        Toast.makeText(this, "Train tracking stopped & terminated", Toast.LENGTH_SHORT).show()
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

        if (state.isTracking && state.tripMode == TripMode.TRAIN) {
            binding.btnToggleTracking.isVisible = false
            binding.btnStopTracking.isVisible = true

            binding.tvStatus.text = "Tracking Train to ${state.destinationName}"
            binding.tvDistance.text = String.format(Locale.US, "Distance: %.2f km", state.distanceKm)
            binding.tvEta.text = formatTrainEta(state.etaMinutes, state.speedKmh)

            when (state.approachState) {
                ApproachState.APPROACHING -> {
                    binding.tvApproachBadge.text = "🟢 Train Approaching"
                    binding.tvApproachBadge.setTextColor(ContextCompat.getColor(this, R.color.status_green_text))
                    binding.tvApproachBadge.setBackgroundColor(ContextCompat.getColor(this, R.color.status_green_bg))
                }
                ApproachState.CIRCLING_LOOP -> {
                    binding.tvApproachBadge.text = "🟡 Junction Track Filtered"
                    binding.tvApproachBadge.setTextColor(ContextCompat.getColor(this, R.color.status_amber_text))
                    binding.tvApproachBadge.setBackgroundColor(ContextCompat.getColor(this, R.color.status_amber_bg))
                }
                ApproachState.RECEDING -> {
                    binding.tvApproachBadge.text = "🔴 Train Departed / Moving Away"
                    binding.tvApproachBadge.setTextColor(ContextCompat.getColor(this, R.color.status_red_text))
                    binding.tvApproachBadge.setBackgroundColor(ContextCompat.getColor(this, R.color.status_red_bg))
                }
                ApproachState.UNCERTAIN -> {
                    binding.tvApproachBadge.text = "Locating Train..."
                    binding.tvApproachBadge.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
                    binding.tvApproachBadge.setBackgroundColor(Color.parseColor("#E2E8F0"))
                }
            }

            when (state.alertLevel) {
                AlertLevel.LEVEL_4_FULL_ALARM -> {
                    binding.cardTrackingStatus.setCardBackgroundColor(ContextCompat.getColor(this, R.color.status_red_bg))
                    binding.tvStatus.setTextColor(ContextCompat.getColor(this, R.color.status_red_text))
                }
                AlertLevel.LEVEL_3_VOICE -> {
                    binding.cardTrackingStatus.setCardBackgroundColor(Color.parseColor("#EEF2FF"))
                    binding.tvStatus.setTextColor(Color.parseColor("#4338CA"))
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
            binding.btnToggleTracking.isVisible = true
            binding.btnToggleTracking.text = "Start Background Train Alarm"
            binding.btnToggleTracking.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#4338CA"))
            binding.btnStopTracking.isVisible = false

            binding.tvStatus.text = "Train Alarm Idle — Tap Start to track in background"
            binding.tvDistance.text = "Distance: -- km"
            binding.tvEta.text = "Waiting to start…"
            binding.tvApproachBadge.text = "Idle"
            binding.tvApproachBadge.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
            binding.tvApproachBadge.setBackgroundColor(Color.parseColor("#E2E8F0"))
            binding.cardTrackingStatus.setCardBackgroundColor(ContextCompat.getColor(this, R.color.surface_card))
            binding.tvStatus.setTextColor(ContextCompat.getColor(this, R.color.text_primary))
        }
    }

    private fun formatTrainEta(etaMinutes: Int?, speedKmh: Double): String {
        if (etaMinutes == null) {
            return if (speedKmh < 2.0) "Train at station…" else "Calculating ETA…"
        }
        return when {
            etaMinutes < 1    -> "Arriving at Station Now!"
            etaMinutes < 60   -> "~${etaMinutes} mins out"
            else -> {
                val hrs  = etaMinutes / 60
                val mins = etaMinutes % 60
                if (mins == 0) "~${hrs} hr${if (hrs > 1) "s" else ""}"
                else           "~${hrs} hr${if (hrs > 1) "s" else ""} ${mins} mins"
            }
        }
    }
}
