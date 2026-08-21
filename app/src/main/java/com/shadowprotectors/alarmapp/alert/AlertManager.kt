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
    private val audioAlarmHelper: AudioAlarmHelper,
    private val callback: AlertCallback? = null
) {
    var level1ThresholdKm: Double = 5.0
    var level2ThresholdKm: Double = 2.0
    var level3ThresholdKm: Double = 1.0
    var level4ThresholdKm: Double = 0.5

    private var highestTriggeredLevel = AlertLevel.NONE

    fun reset() {
        highestTriggeredLevel = AlertLevel.NONE
        audioAlarmHelper.stopFullAlarm()
    }

    /**
     * Evaluates current distance & direction state, progressing alert levels sequentially.
     */
    fun processDistance(
        distanceKm: Double,
        approachState: ApproachState,
        destinationName: String
    ): AlertLevel {
        // If passenger is moving away or on a distinct detour loop, suppress higher level alerts
        if (approachState == ApproachState.RECEDING || approachState == ApproachState.CIRCLING_LOOP) {
            return highestTriggeredLevel
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
                audioAlarmHelper.playLevel2Vibration()
            }
            AlertLevel.LEVEL_3_VOICE -> {
                voiceAlertHelper.speakApproachAlert(destinationName, distanceKm)
            }
            AlertLevel.LEVEL_4_FULL_ALARM -> {
                audioAlarmHelper.startFullAlarm()
            }
            AlertLevel.NONE -> {}
        }
        callback?.onAlertTriggered(level, distanceKm, destinationName)
    }

    fun stopAlarm() {
        audioAlarmHelper.stopFullAlarm()
    }
}
