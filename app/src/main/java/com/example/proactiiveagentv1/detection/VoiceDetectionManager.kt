package com.example.proactiiveagentv1.detection

import android.content.Context
import com.example.proactiiveagentv1.audio.AudioManager
import com.example.proactiiveagentv1.vad.SileroVADProcessor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class VoiceDetectionManager(
    private val context: Context,
    private val onVoiceActivityUpdate: (Float) -> Unit,
    private val onSpeechStateChange: (Boolean) -> Unit,
    private val onSpeechStarted: (Float) -> Unit,
    private val onSpeechEnded: () -> Unit
) {
    private val audioManager: AudioManager
    private val vadProcessor: SileroVADProcessor
    
    // Detection parameters
    private val vadThreshold = 0.5f
    private val silenceTimeout = 500L
    private val minimumSpeechDuration = 200L
    
    // Audio buffer and state
    private val audioBuffer = mutableListOf<Float>()
    private var isSpeaking = false
    private var lastSpeechTime = 0L
    private var speechStartTime = 0L
    
    init {
        vadProcessor = SileroVADProcessor(context)
        audioManager = AudioManager(context) { audioData, samplesRead ->
            processAudioData(audioData, samplesRead)
        }
    }
    
    fun initialize(): Boolean {
        val audioInitialized = audioManager.initialize()
        val vadInitialized = vadProcessor.initialize()
        
        if (vadInitialized) {
            vadProcessor.resetState()
        }
        
        return audioInitialized && vadInitialized
    }
    
    fun startDetection(): Boolean {
        if (!vadProcessor.isInitialized()) {
            return false
        }
        
        audioBuffer.clear()
        vadProcessor.resetState()
        isSpeaking = false
        
        return audioManager.startRecording()
    }
    
    fun stopDetection() {
        audioManager.stopRecording()
        
        if (isSpeaking) {
            isSpeaking = false
            onSpeechStateChange(false)
            onSpeechEnded()
        }
    }
    
    fun isDetecting(): Boolean = audioManager.isCurrentlyRecording()
    
    fun isModelInitialized(): Boolean = vadProcessor.isInitialized()
    
    private fun processAudioData(audioData: FloatArray, samplesRead: Int) {
        // Add audio data to buffer
        for (i in 0 until samplesRead) {
            audioBuffer.add(audioData[i])
        }
        
        // Process audio in chunks suitable for VAD
        while (audioBuffer.size >= SileroVADProcessor.WINDOW_SIZE_SAMPLES) {
            val audioChunk = FloatArray(SileroVADProcessor.WINDOW_SIZE_SAMPLES)
            for (i in 0 until SileroVADProcessor.WINDOW_SIZE_SAMPLES) {
                audioChunk[i] = audioBuffer.removeAt(0)
            }
            
            // Run VAD inference
            val vadScore = vadProcessor.processAudio(audioChunk)
            
            // Update UI on main thread
            CoroutineScope(Dispatchers.Main).launch {
                onVoiceActivityUpdate(vadScore)
                processSpeechDetection(vadScore)
            }
        }
    }
    
    private fun processSpeechDetection(vadScore: Float) {
        val currentTime = System.currentTimeMillis()
        val isSpeechDetected = vadScore > vadThreshold
        
        if (isSpeechDetected) {
            // Speech detected
            if (!isSpeaking) {
                speechStartTime = currentTime
                isSpeaking = true
                onSpeechStateChange(true)
                onSpeechStarted(vadScore)
            }
            lastSpeechTime = currentTime
        } else {
            // Check for end of speech
            if (isSpeaking) {
                val speechDuration = currentTime - speechStartTime
                val silenceDuration = currentTime - lastSpeechTime
                
                // End speech if we've been silent long enough and had minimum speech duration
                if (silenceDuration > silenceTimeout && speechDuration > minimumSpeechDuration) {
                    isSpeaking = false
                    onSpeechStateChange(false)
                    onSpeechEnded()
                }
            }
        }
    }
    
    fun release() {
        stopDetection()
        audioManager.release()
        vadProcessor.release()
    }
    
    companion object {
        const val DEFAULT_VAD_THRESHOLD = 0.5f
        const val DEFAULT_SILENCE_TIMEOUT = 500L
        const val DEFAULT_MIN_SPEECH_DURATION = 200L
    }
} 