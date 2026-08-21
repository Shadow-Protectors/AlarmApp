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
        if (!isInitialized || tts == null) return

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

        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "TravelAlarmTTS_${System.currentTimeMillis()}")
    }

    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        isInitialized = false
    }
}
