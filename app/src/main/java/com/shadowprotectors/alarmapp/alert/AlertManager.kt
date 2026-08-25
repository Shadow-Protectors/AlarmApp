package com.shadowprotectors.alarmapp.alert

import android.content.Context
import com.shadowprotectors.alarmapp.engine.ApproachState

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
     * Evaluates current distance & direction state, progressing alert levels sequentially.
     */
    fun processDistance(
        distanceKm: Double,
        approachState: ApproachState,
        destinationName: String
    ): AlertLevel {
        // Handle moving away (RECEDING) warning
        if (approachState == ApproachState.RECEDING) {
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

        if (approachState == ApproachState.CIRCLING_LOOP) {
            return highestTriggeredLevel
        }

        // If user already dismissed the Level 4 alarm, prevent repeating loud alarm loop on each GPS tick
        if (isAlarmDismissedByUser && distanceKm <= level4ThresholdKm) {
            return AlertLevel.LEVEL_4_FULL_ALARM
        }

        val targetLevel = when {
            distanceKm <= level4ThresholdKm -> AlertLevel.LEVEL_4_FULL_ALARM
            distanceKm <= level3ThresholdKm -> AlertLevel.LEVEL_3_VOICE
            distanceKm <= level2ThresholdKm -> AlertLevel.LEVEL_2_VIBRATE
            distanceKm <= level1ThresholdKm -> AlertLevel.LEVEL_1_SILENT
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
