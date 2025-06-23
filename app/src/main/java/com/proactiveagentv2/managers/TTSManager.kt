package com.proactiveagentv2.managers

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.*

/**
 * Manages Text-to-Speech functionality for reading LLM responses aloud
 */
class TTSManager(private val context: Context) {
    
    private var tts: TextToSpeech? = null
    private var isInitialized = false
    private var isEnabled = true
    private var speechRate = 1.0f
    private var pitch = 1.0f
    
    // Callbacks
    var onSpeechStart: (() -> Unit)? = null
    var onSpeechComplete: (() -> Unit)? = null
    var onSpeechError: ((String) -> Unit)? = null
    
    companion object {
        private const val TAG = "TTSManager"
        private const val UTTERANCE_ID = "LLM_RESPONSE"
    }
    
    /**
     * Initialize TTS engine
     */
    fun initialize(): Boolean {
        return try {
            Log.d(TAG, "Initializing TTS engine...")
            
            tts = TextToSpeech(context) { status ->
                when (status) {
                    TextToSpeech.SUCCESS -> {
                        Log.d(TAG, "TTS initialization successful")
                        
                        // Set language to English (US)
                        val langResult = tts?.setLanguage(Locale.US)
                        
                        when (langResult) {
                            TextToSpeech.LANG_MISSING_DATA, TextToSpeech.LANG_NOT_SUPPORTED -> {
                                Log.w(TAG, "Language not supported or missing data")
                                onSpeechError?.invoke("Language not supported")
                            }
                            else -> {
                                isInitialized = true
                                setupTTSListener()
                                applySpeechSettings()
                                Log.d(TAG, "TTS engine ready")
                            }
                        }
                    }
                    else -> {
                        Log.e(TAG, "TTS initialization failed with status: $status")
                        isInitialized = false
                        onSpeechError?.invoke("TTS initialization failed")
                    }
                }
            }
            
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing TTS", e)
            isInitialized = false
            onSpeechError?.invoke("TTS initialization error: ${e.message}")
            false
        }
    }
    
    /**
     * Setup TTS utterance progress listener
     */
    private fun setupTTSListener() {
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                if (utteranceId == UTTERANCE_ID) {
                    Log.d(TAG, "TTS speech started")
                    onSpeechStart?.invoke()
                }
            }
            
            override fun onDone(utteranceId: String?) {
                if (utteranceId == UTTERANCE_ID) {
                    Log.d(TAG, "TTS speech completed")
                    onSpeechComplete?.invoke()
                }
            }
            
            override fun onError(utteranceId: String?) {
                if (utteranceId == UTTERANCE_ID) {
                    Log.e(TAG, "TTS speech error")
                    onSpeechError?.invoke("Speech synthesis error")
                }
            }
        })
    }
    
    /**
     * Apply current speech settings to TTS engine
     */
    private fun applySpeechSettings() {
        tts?.let { engine ->
            val speechRateResult = engine.setSpeechRate(speechRate)
            val pitchResult = engine.setPitch(pitch)
            
            Log.d(TAG, "Applied TTS settings - Rate: $speechRate (result: $speechRateResult), Pitch: $pitch (result: $pitchResult)")
        }
    }
    
    /**
     * Speak the provided text
     */
    fun speak(text: String) {
        if (!isInitialized || !isEnabled) {
            Log.d(TAG, "TTS not initialized or disabled, skipping speech")
            return
        }
        
        if (text.isBlank()) {
            Log.w(TAG, "Empty text provided for TTS")
            return
        }
        
        try {
            Log.d(TAG, "Speaking text: \"${text.take(50)}${if (text.length > 50) "..." else ""}\"")
            
            // Stop any currently playing speech
            stop()
            
            // Speak the text
            val result = tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, UTTERANCE_ID)
            
            when (result) {
                TextToSpeech.SUCCESS -> {
                    Log.d(TAG, "TTS speak request successful")
                }
                TextToSpeech.ERROR -> {
                    Log.e(TAG, "TTS speak request failed")
                    onSpeechError?.invoke("Failed to start speech")
                }
                else -> {
                    Log.w(TAG, "TTS speak request returned unexpected result: $result")
                }
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Error during TTS speak", e)
            onSpeechError?.invoke("Speech error: ${e.message}")
        }
    }
    
    /**
     * Stop current speech
     */
    fun stop() {
        try {
            tts?.stop()
            Log.d(TAG, "TTS speech stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping TTS", e)
        }
    }
    
    /**
     * Check if TTS is currently speaking
     */
    fun isSpeaking(): Boolean {
        return tts?.isSpeaking ?: false
    }
    
    /**
     * Update TTS settings
     */
    fun updateSettings(enabled: Boolean, speechRate: Float, pitch: Float) {
        this.isEnabled = enabled
        this.speechRate = speechRate.coerceIn(0.1f, 4.0f)
        this.pitch = pitch.coerceIn(0.1f, 4.0f)
        
        Log.d(TAG, "Updated TTS settings - Enabled: $enabled, Rate: ${this.speechRate}, Pitch: ${this.pitch}")
        
        if (isInitialized) {
            applySpeechSettings()
        }
    }
    
    /**
     * Check if TTS is enabled
     */
    fun isEnabled(): Boolean = isEnabled && isInitialized
    
    /**
     * Get current speech rate
     */
    fun getSpeechRate(): Float = speechRate
    
    /**
     * Get current pitch
     */
    fun getPitch(): Float = pitch
    
    /**
     * Get available TTS languages
     */
    fun getAvailableLanguages(): Set<Locale> {
        return tts?.availableLanguages ?: emptySet()
    }
    
    /**
     * Set TTS language
     */
    fun setLanguage(locale: Locale): Boolean {
        return try {
            val result = tts?.setLanguage(locale)
            val success = result != TextToSpeech.LANG_MISSING_DATA && result != TextToSpeech.LANG_NOT_SUPPORTED
            
            if (success) {
                Log.d(TAG, "TTS language set to: ${locale.displayName}")
            } else {
                Log.w(TAG, "Failed to set TTS language to: ${locale.displayName}")
            }
            
            success
        } catch (e: Exception) {
            Log.e(TAG, "Error setting TTS language", e)
            false
        }
    }
    
    /**
     * Release TTS resources
     */
    fun release() {
        try {
            Log.d(TAG, "Releasing TTS resources...")
            
            stop()
            tts?.shutdown()
            tts = null
            isInitialized = false
            
            Log.d(TAG, "TTS resources released")
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing TTS resources", e)
        }
    }
} 