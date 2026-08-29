package com.shadowprotectors.alarmapp.service

import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Location
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.shadowprotectors.alarmapp.alert.AlertLevel
import com.shadowprotectors.alarmapp.alert.AlertManager
import com.shadowprotectors.alarmapp.alert.AudioAlarmHelper
import com.shadowprotectors.alarmapp.alert.SupportedLanguage
import com.shadowprotectors.alarmapp.alert.VoiceAlertHelper
import com.shadowprotectors.alarmapp.engine.ApproachState
import com.shadowprotectors.alarmapp.engine.DirectionFilter
import com.shadowprotectors.alarmapp.engine.DistanceEngine
import com.shadowprotectors.alarmapp.ui.AlarmTriggerActivity
import com.shadowprotectors.alarmapp.util.NotificationHelper

class LocationTrackingService : Service() {

    companion object {
        const val ACTION_START = "com.shadowprotectors.alarmapp.ACTION_START"
        const val ACTION_STOP = "com.shadowprotectors.alarmapp.ACTION_STOP"
        const val ACTION_STOP_ALARM = "com.shadowprotectors.alarmapp.ACTION_STOP_ALARM"
        const val ACTION_UPDATE_LANGUAGE = "com.shadowprotectors.alarmapp.ACTION_UPDATE_LANGUAGE"

        const val EXTRA_DEST_NAME = "EXTRA_DEST_NAME"
        const val EXTRA_DEST_LAT = "EXTRA_DEST_LAT"
        const val EXTRA_DEST_LNG = "EXTRA_DEST_LNG"
        const val EXTRA_LANG_CODE = "EXTRA_LANG_CODE"
        const val EXTRA_TRIP_MODE = "EXTRA_TRIP_MODE"

        fun startService(context: Context, destName: String, lat: Double, lng: Double, langCode: String = "en", modeName: String = "BUS_CAR") {
            val intent = Intent(context, LocationTrackingService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_DEST_NAME, destName)
                putExtra(EXTRA_DEST_LAT, lat)
                putExtra(EXTRA_DEST_LNG, lng)
                putExtra(EXTRA_LANG_CODE, langCode)
                putExtra(EXTRA_TRIP_MODE, modeName)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun updateLanguage(context: Context, langCode: String) {
            val intent = Intent(context, LocationTrackingService::class.java).apply {
                action = ACTION_UPDATE_LANGUAGE
                putExtra(EXTRA_LANG_CODE, langCode)
            }
            context.startService(intent)
        }

        fun stopService(context: Context) {
            val intent = Intent(context, LocationTrackingService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }

        fun stopAlarm(context: Context) {
            val intent = Intent(context, LocationTrackingService::class.java).apply {
                action = ACTION_STOP_ALARM
            }
            context.startService(intent)
        }
    }

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private var locationCallback: LocationCallback? = null
    private var wakeLock: PowerManager.WakeLock? = null

    private lateinit var voiceAlertHelper: VoiceAlertHelper
    private lateinit var alertManager: AlertManager
    private val directionFilter = DirectionFilter()

    private var destinationName = ""
    private var destLat = 0.0
    private var destLng = 0.0

    private var activeProfile = com.shadowprotectors.alarmapp.engine.AlertProfileResolver.resolve(com.shadowprotectors.alarmapp.engine.TripMode.BUS_CAR)

    // Cached once at start — never changes during a tracking session
    private var stopPendingIntent: PendingIntent? = null

    // Guards: skip notification/UI emit if nothing meaningful changed
    private var lastNotifiedDistanceKm = Double.MAX_VALUE
    private var lastEmittedState: TrackingState? = null
    private var hasLaunchedLevel4Activity = false
    private var smoothedSpeedKmh = 0.0

    override fun onCreate() {
        super.onCreate()
        NotificationHelper.createNotificationChannels(this)

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        voiceAlertHelper = VoiceAlertHelper(this)
        alertManager = AlertManager(this, voiceAlertHelper)

        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "TravelAlarm::TrackingWakeLock").apply {
            setReferenceCounted(false)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                destinationName = intent.getStringExtra(EXTRA_DEST_NAME) ?: "Destination"
                destLat = intent.getDoubleExtra(EXTRA_DEST_LAT, 0.0)
                destLng = intent.getDoubleExtra(EXTRA_DEST_LNG, 0.0)
                val langCode = intent.getStringExtra(EXTRA_LANG_CODE) ?: "en"
                val modeName = intent.getStringExtra(EXTRA_TRIP_MODE) ?: "BUS_CAR"

                val tripMode = try { com.shadowprotectors.alarmapp.engine.TripMode.valueOf(modeName) } catch(e: Exception) { com.shadowprotectors.alarmapp.engine.TripMode.BUS_CAR }
                activeProfile = com.shadowprotectors.alarmapp.engine.AlertProfileResolver.resolve(tripMode)

                voiceAlertHelper.currentLanguage = when (langCode) {
                    "ta" -> SupportedLanguage.TAMIL
                    "hi" -> SupportedLanguage.HINDI
                    else -> SupportedLanguage.ENGLISH
                }

                startForegroundTracking()
            }
            ACTION_UPDATE_LANGUAGE -> {
                val langCode = intent.getStringExtra(EXTRA_LANG_CODE) ?: "en"
                voiceAlertHelper.currentLanguage = when (langCode) {
                    "ta" -> SupportedLanguage.TAMIL
                    "hi" -> SupportedLanguage.HINDI
                    else -> SupportedLanguage.ENGLISH
                }
            }
            ACTION_STOP_ALARM -> {
                alertManager.stopAlarm()
                AudioAlarmHelper.stopFullAlarm(this)
                val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                notificationManager.cancel(NotificationHelper.NOTIFICATION_ALARM_ID)
            }
            ACTION_STOP -> {
                stopForegroundTracking()
            }
        }
        return START_NOT_STICKY
    }

    private fun startForegroundTracking() {
        wakeLock?.acquire(10 * 60 * 60 * 1000L /* 10 hours max */)

        val stopIntent = Intent(this, LocationTrackingService::class.java).apply { action = ACTION_STOP }
        // Assign to class-level property so handleLocationUpdate() can reuse it without per-tick allocation
        stopPendingIntent = PendingIntent.getService(this, 2, stopIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        val initialNotification = NotificationHelper.buildTrackingNotification(
            this, destinationName, 0.0, null, stopPendingIntent!!
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceCompat.startForeground(
                this,
                NotificationHelper.NOTIFICATION_TRACKING_ID,
                initialNotification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            )
        } else {
            startForeground(NotificationHelper.NOTIFICATION_TRACKING_ID, initialNotification)
        }

        alertManager.reset()
        directionFilter.reset()
        lastNotifiedDistanceKm = Double.MAX_VALUE
        lastEmittedState = null
        hasLaunchedLevel4Activity = false
        smoothedSpeedKmh = 0.0

        val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, activeProfile.pollingIntervalMs)
            .setMinUpdateIntervalMillis(activeProfile.minUpdateIntervalMs)
            .build()

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                for (location in result.locations) {
                    handleLocationUpdate(location)
                }
            }
        }

        try {
            fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback!!, mainLooper)
        } catch (e: SecurityException) {
            Log.e("TrackingService", "Location permission missing: ${e.message}")
            stopSelf()
        }
    }

    private var lastLocation: Location? = null

    private fun handleLocationUpdate(location: Location) {
        val distanceKm = DistanceEngine.calculateDistanceKm(location.latitude, location.longitude, destLat, destLng)
        val targetBearing = DistanceEngine.calculateBearing(location.latitude, location.longitude, destLat, destLng)
        // Only trust bearing if the user is moving at a measurable speed (> 0.5 m/s or 1.8 km/h)
        val userBearing = if (location.hasBearing() && location.hasSpeed() && location.speed > 0.5f) location.bearing else -1f

        // Calculate accurate real-time speed in km/h (* 3.6 from m/s)
        val rawSpeedKmh = if (location.hasSpeed() && location.speed > 0f) location.speed * 3.6 else 0.0
        val prevLoc = lastLocation
        val calcDeltaSpeedKmh = if (prevLoc != null) {
            val distMeters = location.distanceTo(prevLoc)
            val timeDiffSec = (location.time - prevLoc.time) / 1000.0
            if (timeDiffSec in 1.5..60.0 && location.hasAccuracy() && location.accuracy < 50f && prevLoc.hasAccuracy() && prevLoc.accuracy < 50f) {
                (distMeters / timeDiffSec) * 3.6
            } else 0.0
        } else 0.0

        val instantSpeedKmh = if (rawSpeedKmh in 0.1..180.0) {
            rawSpeedKmh
        } else if (calcDeltaSpeedKmh in 0.1..180.0) {
            calcDeltaSpeedKmh
        } else {
            0.0
        }

        // Exponential moving average for smooth ETA without jitter (alpha = 0.35)
        smoothedSpeedKmh = if (smoothedSpeedKmh <= 0.0) {
            instantSpeedKmh
        } else {
            (0.35 * instantSpeedKmh) + (0.65 * smoothedSpeedKmh)
        }

        lastLocation = location

        val finalSpeedKmh = smoothedSpeedKmh
        val etaMinutes = DistanceEngine.estimateEtaMinutes(distanceKm, finalSpeedKmh, activeProfile.mode)

        val approachState = directionFilter.evaluateApproach(distanceKm, userBearing, targetBearing, activeProfile.bearingToleranceDeg, location.time)
        val alertLevel = alertManager.processState(distanceKm, etaMinutes, finalSpeedKmh, approachState, destinationName, activeProfile)

        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // 1. Update Foreground Persistent Notification — only when distance changed by > 100 m
        val pendingIntent = stopPendingIntent!!
        if (Math.abs(distanceKm - lastNotifiedDistanceKm) > 0.1 || lastNotifiedDistanceKm == Double.MAX_VALUE) {
            lastNotifiedDistanceKm = distanceKm
            val notification = NotificationHelper.buildTrackingNotification(
                this, destinationName, distanceKm, etaMinutes, pendingIntent
            )
            notificationManager.notify(NotificationHelper.NOTIFICATION_TRACKING_ID, notification)
        }

        // 2. If Level 4 is reached and not dismissed by user, trigger one-shot full-screen alarm notification and launch AlarmTriggerActivity
        if (alertLevel == AlertLevel.LEVEL_4_FULL_ALARM && !alertManager.isAlarmDismissedByUser) {
            if (!hasLaunchedLevel4Activity) {
                hasLaunchedLevel4Activity = true

                val dismissIntent = Intent(this, LocationTrackingService::class.java).apply { action = ACTION_STOP_ALARM }
                val dismissPendingIntent = PendingIntent.getService(this, 3, dismissIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

                val alarmNotification = NotificationHelper.buildAlarmNotification(
                    this, destinationName, distanceKm, dismissPendingIntent
                )
                notificationManager.notify(NotificationHelper.NOTIFICATION_ALARM_ID, alarmNotification)

                // Auto-launch activity once
                val alarmActivityIntent = Intent(this, AlarmTriggerActivity::class.java).apply {
                    putExtra(AlarmTriggerActivity.EXTRA_DEST_NAME, destinationName)
                    putExtra(AlarmTriggerActivity.EXTRA_DISTANCE_KM, distanceKm)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                }
                startActivity(alarmActivityIntent)
            }
        }

        // 3. Broadcast to UI EventBus — only when state meaningfully changed
        //    Avoids redundant StateFlow emissions and UI redraws when screen is off.
        val newState = TrackingState(
            isTracking = true,
            destinationName = destinationName,
            destLat = destLat,
            destLng = destLng,
            currentLat = location.latitude,
            currentLng = location.longitude,
            distanceKm = distanceKm,
            speedKmh = finalSpeedKmh,
            etaMinutes = etaMinutes,
            approachState = approachState,
            alertLevel = alertLevel,
            tripMode = activeProfile.mode
        )
        val prev = lastEmittedState
        val significantChange = prev == null
            || Math.abs(newState.distanceKm - prev.distanceKm) > 0.05
            || newState.etaMinutes != prev.etaMinutes
            || newState.approachState != prev.approachState
            || newState.alertLevel != prev.alertLevel
            || newState.tripMode != prev.tripMode
        if (significantChange) {
            lastEmittedState = newState
            ServiceEventBus.updateState(newState)
        }
    }

    private fun stopForegroundTracking() {
        locationCallback?.let { fusedLocationClient.removeLocationUpdates(it) }
        alertManager.stopAlarm()
        AudioAlarmHelper.stopFullAlarm(this)
        voiceAlertHelper.shutdown()

        if (wakeLock?.isHeld == true) {
            wakeLock?.release()
        }

        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.cancelAll()

        ServiceEventBus.resetState()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopForegroundTracking()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
