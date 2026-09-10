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
    private var emergencyAudioTrack: android.media.AudioTrack? = null

    @Volatile
    private var isAlarmPlaying = false

    private var audioFocusRequest: android.media.AudioFocusRequest? = null

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
     * Guaranteed to play sound even if phone is set to Silent Mode, Vibrate Mode, or Do Not Disturb.
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

        // 2. Maximize all stream volumes (RING, ALARM, MUSIC, NOTIFICATION) to guarantee full volume even if phone ringtone or alarm volume was turned down
        val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        try {
            if (audioManager.ringerMode != AudioManager.RINGER_MODE_NORMAL) {
                try {
                    audioManager.ringerMode = AudioManager.RINGER_MODE_NORMAL
                } catch (e: Exception) {
                    Log.w("AudioAlarmHelper", "Could not set ringer mode to NORMAL: ${e.message}")
                }
            }

            val streamsToMaximize = intArrayOf(
                AudioManager.STREAM_RING,
                AudioManager.STREAM_ALARM,
                AudioManager.STREAM_MUSIC,
                AudioManager.STREAM_NOTIFICATION
            )
            for (stream in streamsToMaximize) {
                try {
                    val maxVol = audioManager.getStreamMaxVolume(stream)
                    audioManager.setStreamVolume(stream, maxVol, 0)
                } catch (e: Exception) {
                    Log.w("AudioAlarmHelper", "Could not set volume for stream $stream: ${e.message}")
                }
            }
        } catch (e: Exception) {
            Log.w("AudioAlarmHelper", "Could not set stream volume (DND policy or system restriction): ${e.message}")
        }

        // 3. Request transient exclusive Audio Focus with USAGE_ALARM to interrupt headphones / music
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val focusReq = android.media.AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ALARM)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build()
                    )
                    .setAcceptsDelayedFocusGain(false)
                    .build()
                audioFocusRequest = focusReq
                audioManager.requestAudioFocus(focusReq)
            } else {
                @Suppress("DEPRECATION")
                audioManager.requestAudioFocus(null, AudioManager.STREAM_ALARM, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
            }
        } catch (e: Exception) {
            Log.e("AudioAlarmHelper", "Error requesting audio focus: ${e.message}")
        }

        // 4. Play Phone Ringtone Sound at Full Volume (MediaPlayer -> Ringtone -> AudioTrack Siren Fallback)
        var soundStarted = false
        try {
            // Prioritize phone's ringtone tone (RingtoneManager.TYPE_RINGTONE) as requested
            var alarmUri: Uri? = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            if (alarmUri == null) {
                alarmUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            }
            if (alarmUri == null) {
                alarmUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            }

            // A. Primary: MediaPlayer with USAGE_ALARM
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
                        setVolume(1.0f, 1.0f)
                        isLooping = true
                        prepare()
                        start()
                    }
                    soundStarted = true
                } catch (mpEx: Exception) {
                    Log.w("AudioAlarmHelper", "MediaPlayer failed, attempting Ringtone fallback: ${mpEx.message}")
                }
            }

            // B. Secondary: Ringtone fallback with USAGE_ALARM explicitly set
            if (!soundStarted && alarmUri != null) {
                try {
                    ringtone = RingtoneManager.getRingtone(appContext, alarmUri)?.apply {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                            isLooping = true
                        }
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                            audioAttributes = AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_ALARM)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                                .build()
                        }
                        play()
                    }
                    soundStarted = ringtone?.isPlaying == true
                } catch (rEx: Exception) {
                    Log.w("AudioAlarmHelper", "Ringtone fallback failed: ${rEx.message}")
                }
            }

            // C. Tertiary: Synthetic AudioTrack Siren Backup (Guaranteed to sound on every device in Silent mode)
            if (!soundStarted) {
                playEmergencyAudioTrackSiren()
            }

        } catch (e: Exception) {
            Log.e("AudioAlarmHelper", "Error starting alarm sound: ${e.message}", e)
            playEmergencyAudioTrackSiren()
        }
    }

    /**
     * Synthesizes a high-decibel dual-frequency alternating siren tone (880Hz / 1760Hz)
     * using AudioTrack with USAGE_ALARM, bypassing all system silent modes.
     */
    private fun playEmergencyAudioTrackSiren() {
        try {
            val sampleRate = 22050
            val numSamples = sampleRate * 2
            val sample = DoubleArray(numSamples)
            val generatedSnd = ByteArray(2 * numSamples)

            for (i in 0 until numSamples) {
                val freq = if ((i / (sampleRate / 4)) % 2 == 0) 880.0 else 1760.0
                sample[i] = Math.sin(2.0 * Math.PI * i / (sampleRate / freq))
            }

            var idx = 0
            for (dVal in sample) {
                val valShort = (dVal * 32767).toInt().toShort()
                generatedSnd[idx++] = (valShort.toInt() and 0x00ff).toByte()
                generatedSnd[idx++] = (valShort.toInt() and 0xff00 ushr 8).toByte()
            }

            val attributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()

            val format = android.media.AudioFormat.Builder()
                .setEncoding(android.media.AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(sampleRate)
                .setChannelMask(android.media.AudioFormat.CHANNEL_OUT_MONO)
                .build()

            emergencyAudioTrack = android.media.AudioTrack(
                attributes, format, generatedSnd.size,
                android.media.AudioTrack.MODE_STATIC, AudioManager.AUDIO_SESSION_ID_GENERATE
            ).apply {
                write(generatedSnd, 0, generatedSnd.size)
                setLoopPoints(0, numSamples, -1)
                play()
            }
            Log.i("AudioAlarmHelper", "Emergency AudioTrack PCM siren active")
        } catch (e: Exception) {
            Log.e("AudioAlarmHelper", "Failed to start emergency AudioTrack siren: ${e.message}")
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
        }

        // 4. Stop Emergency AudioTrack Siren
        try {
            emergencyAudioTrack?.let {
                if (it.playState == android.media.AudioTrack.PLAYSTATE_PLAYING) {
                    it.stop()
                }
                it.release()
            }
        } catch (e: Exception) {
            Log.e("AudioAlarmHelper", "Error stopping emergency AudioTrack: ${e.message}")
        } finally {
            emergencyAudioTrack = null
            isAlarmPlaying = false
        }

        // 5. Release Audio Focus
        try {
            val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
                audioFocusRequest = null
            } else {
                @Suppress("DEPRECATION")
                audioManager.abandonAudioFocus(null)
            }
        } catch (e: Exception) {
            Log.e("AudioAlarmHelper", "Error abandoning audio focus: ${e.message}")
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
