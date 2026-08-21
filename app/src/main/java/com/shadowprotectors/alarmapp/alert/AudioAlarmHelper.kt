package com.shadowprotectors.alarmapp.alert

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log

class AudioAlarmHelper(private val context: Context) {

    private var mediaPlayer: MediaPlayer? = null
    private var isAlarmPlaying = false

    private val vibrator: Vibrator by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vibratorManager.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
    }

    /**
     * Triggers Level 2 vibration pulse (gentle alert).
     */
    fun playLevel2Vibration() {
        val pattern = longArrayOf(0, 500, 200, 500)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(pattern, -1)
        }
    }

    /**
     * Starts continuous high-priority loud alarm loop + intense vibration.
     */
    fun startFullAlarm() {
        if (isAlarmPlaying) return
        isAlarmPlaying = true

        // 1. Continuous Alarm Vibration
        val alarmVibratePattern = longArrayOf(0, 800, 400, 800, 400)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createWaveform(alarmVibratePattern, 0)) // 0 = repeat indefinitely
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(alarmVibratePattern, 0)
        }

        // 2. Audio Playback via MediaPlayer
        try {
            var alarmUri: Uri? = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            if (alarmUri == null) {
                alarmUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            }

            mediaPlayer = MediaPlayer().apply {
                setDataSource(context, alarmUri)
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                isLooping = true
                prepare()
                start()
            }
        } catch (e: Exception) {
            Log.e("AudioAlarmHelper", "Error playing alarm sound: ${e.message}", e)
        }
    }

    /**
     * Stops alarm audio and vibration.
     */
    fun stopFullAlarm() {
        vibrator.cancel()
        mediaPlayer?.let {
            if (it.isPlaying) {
                it.stop()
            }
            it.release()
        }
        mediaPlayer = null
        isAlarmPlaying = false
    }

    fun isPlaying(): Boolean = isAlarmPlaying
}
