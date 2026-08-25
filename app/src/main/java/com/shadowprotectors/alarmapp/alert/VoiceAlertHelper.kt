package com.shadowprotectors.alarmapp.alert

import android.content.Context
import android.speech.tts.TextToSpeech
import android.util.Log
import java.util.Locale

enum class SupportedLanguage(val code: String, val displayName: String, val locale: Locale) {
    ENGLISH("en", "English", Locale.ENGLISH),
    TAMIL("ta", "தமிழ் (Tamil)", Locale("ta", "IN")),
    HINDI("hi", "हिन्दी (Hindi)", Locale("hi", "IN"))
}

class VoiceAlertHelper(private val context: Context) : TextToSpeech.OnInitListener {

    private var tts: TextToSpeech? = null
    private var isInitialized = false
    private var pendingSpeechText: String? = null

    var currentLanguage: SupportedLanguage = SupportedLanguage.ENGLISH
        set(value) {
            field = value
            applyLanguage(value)
        }

    init {
        tts = TextToSpeech(context.applicationContext, this)
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            isInitialized = true
            applyLanguage(currentLanguage)

            // Play any pending speech that was requested during initialization
            pendingSpeechText?.let {
                tts?.speak(it, TextToSpeech.QUEUE_FLUSH, null, "TravelAlarmTTS_${System.currentTimeMillis()}")
                pendingSpeechText = null
            }
        } else {
            Log.e("VoiceAlertHelper", "TTS Initialization failed with status: $status")
        }
    }

    private fun applyLanguage(language: SupportedLanguage) {
        if (!isInitialized || tts == null) return
        val result = tts?.setLanguage(language.locale)
        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
            Log.w("VoiceAlertHelper", "Language ${language.code} not supported on device, falling back to English")
            tts?.setLanguage(Locale.ENGLISH)
        }
    }

    fun speakApproachAlert(destinationName: String, distanceKm: Double) {
        val formattedDistance = String.format(Locale.US, "%.1f", distanceKm)
        val text = when (currentLanguage) {
            SupportedLanguage.TAMIL -> {
                "கவனம்! $destinationName இன்னும் $formattedDistance கிலோமீட்டரில் வரவுள்ளது. இறங்குவதற்கு தயாராகுங்கள்."
            }
            SupportedLanguage.HINDI -> {
                "ध्यान दें! $destinationName $formattedDistance किलोमीटर में आने वाला है। कृपया उतरने के लिए तैयार रहें।"
            }
            SupportedLanguage.ENGLISH -> {
                "Attention! Approaching $destinationName in $formattedDistance kilometers. Please prepare your luggage."
            }
        }

        if (isInitialized && tts != null) {
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "TravelAlarmTTS_${System.currentTimeMillis()}")
        } else {
            pendingSpeechText = text
        }
    }

    fun speakRecedingAlert(destinationName: String) {
        val text = when (currentLanguage) {
            SupportedLanguage.TAMIL -> {
                "எச்சரிக்கை! $destinationName இலக்கிலிருந்து நீங்கள் விலகிச் செல்கிறீர்கள்."
            }
            SupportedLanguage.HINDI -> {
                "चेतावनी! आप $destinationName से दूर जा रहे हैं।"
            }
            SupportedLanguage.ENGLISH -> {
                "Warning! You are moving away from $destinationName."
            }
        }

        if (isInitialized && tts != null) {
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "TravelAlarmTTS_Receding_${System.currentTimeMillis()}")
        } else {
            pendingSpeechText = text
        }
    }

    fun stop() {
        pendingSpeechText = null
        try {
            tts?.stop()
        } catch (e: Exception) {
            Log.e("VoiceAlertHelper", "Error stopping TTS: ${e.message}")
        }
    }

    fun shutdown() {
        pendingSpeechText = null
        try {
            tts?.stop()
            tts?.shutdown()
        } catch (e: Exception) {
            Log.e("VoiceAlertHelper", "Error shutting down TTS: ${e.message}")
        } finally {
            tts = null
            isInitialized = false
        }
    }
}
