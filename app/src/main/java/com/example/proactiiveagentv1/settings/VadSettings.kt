package com.example.proactiiveagentv1.settings

import android.content.Context
import android.content.SharedPreferences

data class VadSettings(
    val minimumSpeechDurationMs: Long = 200L,
    val silenceTimeoutMs: Long = 300L,
    val vadThreshold: Float = 0.5f
)

class VadPreferencesManager(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    
    fun getVadSettings(): VadSettings {
        return VadSettings(
            minimumSpeechDurationMs = prefs.getLong(KEY_MIN_SPEECH_DURATION, 200L),
            silenceTimeoutMs = prefs.getLong(KEY_SILENCE_TIMEOUT, 300L),
            vadThreshold = prefs.getFloat(KEY_VAD_THRESHOLD, 0.5f)
        )
    }
    
    fun saveVadSettings(settings: VadSettings) {
        prefs.edit()
            .putLong(KEY_MIN_SPEECH_DURATION, settings.minimumSpeechDurationMs)
            .putLong(KEY_SILENCE_TIMEOUT, settings.silenceTimeoutMs)
            .putFloat(KEY_VAD_THRESHOLD, settings.vadThreshold)
            .apply()
    }
    
    companion object {
        private const val PREFS_NAME = "vad_settings"
        private const val KEY_MIN_SPEECH_DURATION = "min_speech_duration"
        private const val KEY_SILENCE_TIMEOUT = "silence_timeout"
        private const val KEY_VAD_THRESHOLD = "vad_threshold"
    }
} 