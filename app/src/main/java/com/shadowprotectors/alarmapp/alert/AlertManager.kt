package com.shadowprotectors.alarmapp.alert

import android.content.Context
import com.shadowprotectors.alarmapp.engine.AlertProfile
import com.shadowprotectors.alarmapp.engine.ApproachState
import com.shadowprotectors.alarmapp.engine.ThresholdType

enum class AlertLevel(val rank: Int, val description: String) {
    NONE(0, "En Route — Monitoring"),
    LEVEL_1_SILENT(1, "Level 1: Heads-Up (5.0 km)"),
    LEVEL_2_VIBRATE(2, "Level 2: Vibration Alert (2.0 km)"),
    LEVEL_3_VOICE(3, "Level 3: Voice Alert (1.0 km)"),
    LEVEL_4_FULL_ALARM(4, "Level 4: Destination Alarm (0.5 km)")
}

interface AlertCallback {
    fun onAlertTriggered(level: AlertLevel, distanceKm: Double, destinationName: String)
}

class AlertManager(
    private val context: Context,
    private val voiceAlertHelper: VoiceAlertHelper,
    private val callback: AlertCallback? = null
) {
    var level1ThresholdKm: Double = 5.0
    var level2ThresholdKm: Double = 2.0
    var level3ThresholdKm: Double = 1.0
    var level4ThresholdKm: Double = 0.5

    private var highestTriggeredLevel = AlertLevel.NONE
    var isAlarmDismissedByUser: Boolean = false
        private set

    private var hasTriggeredRecedingWarning = false

    fun reset() {
        highestTriggeredLevel = AlertLevel.NONE
        isAlarmDismissedByUser = false
        hasTriggeredRecedingWarning = false
        AudioAlarmHelper.stopFullAlarm(context)
        voiceAlertHelper.stop()
    }

    /**
     * Evaluates current distance & direction state, progressing alert levels sequentially
     * according to the active mode's AlertProfile (Distance vs ETA thresholds).
     */
    fun processState(
        distanceKm: Double,
        etaMinutes: Int?,
        speedKmh: Double,
        approachState: ApproachState,
        destinationName: String,
        profile: AlertProfile
    ): AlertLevel {
        // Handle moving away (RECEDING) warning
        if (approachState == ApproachState.RECEDING && distanceKm > 1.0) {
            if (!hasTriggeredRecedingWarning) {
                hasTriggeredRecedingWarning = true
                voiceAlertHelper.speakRecedingAlert(destinationName)
                AudioAlarmHelper.playLevel2Vibration(context)
            }
            return highestTriggeredLevel
        } else if (approachState == ApproachState.APPROACHING) {
            // Reset warning so if they move away again later, warning can re-trigger
            hasTriggeredRecedingWarning = false
        }

        // Only freeze alert progression for detour loop if outside 1.0 km proximity
        if (approachState == ApproachState.CIRCLING_LOOP && distanceKm > 1.0) {
            return highestTriggeredLevel
        }

        // Evaluate metric based on mode profile: ETA minutes for Train, Distance km for Bus/Car
        val metricValue = if (profile.thresholdType == ThresholdType.ETA) {
            etaMinutes?.toDouble() ?: Double.MAX_VALUE
        } else {
            distanceKm
        }

        // If user already dismissed the Level 4 alarm, prevent repeating loud alarm loop on each GPS tick
        if (isAlarmDismissedByUser && metricValue <= profile.level4Value) {
            return AlertLevel.LEVEL_4_FULL_ALARM
        }

        val targetLevel = when {
            metricValue <= profile.level4Value -> AlertLevel.LEVEL_4_FULL_ALARM
            metricValue <= profile.level3Value -> AlertLevel.LEVEL_3_VOICE
            metricValue <= profile.level2Value -> AlertLevel.LEVEL_2_VIBRATE
            metricValue <= profile.level1Value -> AlertLevel.LEVEL_1_SILENT
            else -> AlertLevel.NONE
        }

        // Only fire alerts if progressing to a higher alert level
        if (targetLevel.rank > highestTriggeredLevel.rank) {
            highestTriggeredLevel = targetLevel
            fireAlert(targetLevel, distanceKm, destinationName)
        }

        return highestTriggeredLevel
    }

    private fun fireAlert(level: AlertLevel, distanceKm: Double, destinationName: String) {
        when (level) {
            AlertLevel.LEVEL_1_SILENT -> {
                // Handled via notification banner in Service
            }
            AlertLevel.LEVEL_2_VIBRATE -> {
                AudioAlarmHelper.playLevel2Vibration(context)
            }
            AlertLevel.LEVEL_3_VOICE -> {
                voiceAlertHelper.speakApproachAlert(destinationName, distanceKm)
            }
            AlertLevel.LEVEL_4_FULL_ALARM -> {
                voiceAlertHelper.stop()
                AudioAlarmHelper.startFullAlarm(context)
            }
            AlertLevel.NONE -> {}
        }
        callback?.onAlertTriggered(level, distanceKm, destinationName)
    }

    fun stopAlarm() {
        isAlarmDismissedByUser = true
        AudioAlarmHelper.stopFullAlarm(context)
        voiceAlertHelper.stop()
    }
}
