package com.shadowprotectors.alarmapp.alert

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.Ringtone
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log

object AudioAlarmHelper {

    @Volatile
    private var mediaPlayer: MediaPlayer? = null

    @Volatile
    private var ringtone: Ringtone? = null

    @Volatile
    private var isAlarmPlaying = false

    /**
     * Triggers Level 2 vibration pulse (gentle alert).
     */
    @Synchronized
    fun playLevel2Vibration(context: Context) {
        try {
            val vibrator = getVibrator(context)
            val pattern = longArrayOf(0, 500, 200, 500)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(pattern, -1)
            }
        } catch (e: Exception) {
            Log.e("AudioAlarmHelper", "Error in playLevel2Vibration: ${e.message}")
        }
    }

    /**
     * Starts continuous high-priority loud alarm loop + intense vibration.
     */
    @Synchronized
    fun startFullAlarm(context: Context) {
        if (isAlarmPlaying) return
        isAlarmPlaying = true

        val appContext = context.applicationContext

        // 1. Continuous Alarm Vibration
        try {
            val vibrator = getVibrator(appContext)
            val alarmVibratePattern = longArrayOf(0, 800, 400, 800, 400)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createWaveform(alarmVibratePattern, 0)) // 0 = repeat indefinitely
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(alarmVibratePattern, 0)
            }
        } catch (e: Exception) {
            Log.e("AudioAlarmHelper", "Error starting vibrator: ${e.message}")
        }

        // 2. Audio Playback via MediaPlayer with Ringtone fallback
        try {
            var alarmUri: Uri? = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            if (alarmUri == null) {
                alarmUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            }
            if (alarmUri == null) {
                alarmUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            }

            // Stop any existing playback
            stopFullAlarm(appContext)
            isAlarmPlaying = true

            if (alarmUri != null) {
                try {
                    mediaPlayer = MediaPlayer().apply {
                        setDataSource(appContext, alarmUri)
                        setAudioAttributes(
                            AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_ALARM)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                                .setLegacyStreamType(AudioManager.STREAM_ALARM)
                                .build()
                        )
                        isLooping = true
                        prepare()
                        start()
                    }
                } catch (mpEx: Exception) {
                    Log.w("AudioAlarmHelper", "MediaPlayer failed, using Ringtone fallback: ${mpEx.message}")
                    ringtone = RingtoneManager.getRingtone(appContext, alarmUri)?.apply {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                            isLooping = true
                        }
                        play()
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("AudioAlarmHelper", "Error playing alarm sound: ${e.message}", e)
        }
    }

    /**
     * Stops alarm audio and vibration completely across the entire app.
     */
    @Synchronized
    fun stopFullAlarm(context: Context) {
        val appContext = context.applicationContext

        // 1. Cancel Vibrator via all managers
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = appContext.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vm?.cancel()
                vm?.defaultVibrator?.cancel()
            }
            val v = appContext.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            v?.cancel()
        } catch (e: Exception) {
            Log.e("AudioAlarmHelper", "Error cancelling vibrator: ${e.message}")
        }

        // 2. Stop Ringtone
        try {
            ringtone?.let {
                if (it.isPlaying) {
                    it.stop()
                }
            }
        } catch (e: Exception) {
            Log.e("AudioAlarmHelper", "Error stopping ringtone: ${e.message}")
        } finally {
            ringtone = null
        }

        // 3. Stop MediaPlayer
        try {
            mediaPlayer?.let {
                if (it.isPlaying) {
                    it.stop()
                }
                it.reset()
                it.release()
            }
        } catch (e: Exception) {
            Log.e("AudioAlarmHelper", "Error stopping media player: ${e.message}")
        } finally {
            mediaPlayer = null
            isAlarmPlaying = false
        }
    }

    private fun getVibrator(context: Context): Vibrator {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vibratorManager.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
    }

    fun isPlaying(): Boolean = isAlarmPlaying
}
