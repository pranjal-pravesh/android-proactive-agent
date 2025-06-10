package com.example.proactiiveagentv1.detection

import android.content.Context
import com.example.proactiiveagentv1.audio.AudioManager
import com.example.proactiiveagentv1.vad.SileroVADProcessor
import com.example.proactiiveagentv1.settings.VadSettings
import com.example.proactiiveagentv1.settings.VadPreferencesManager
import com.example.proactiiveagentv1.transcription.VadWhisperTranscriptionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class EnhancedVoiceDetectionManager(
    private val context: Context,
    private val onVoiceActivityUpdate: (Float) -> Unit,
    private val onSpeechStateChange: (Boolean) -> Unit,
    private val onSpeechStarted: (Float) -> Unit,
    private val onSpeechEnded: () -> Unit,
    private val onTranscriptionResult: (String) -> Unit = { },
    private val onTranscriptionError: (String) -> Unit = { }
) {
    private val audioManager: AudioManager
    private val vadProcessor: SileroVADProcessor
    private val preferencesManager: VadPreferencesManager
    private val transcriptionManager: VadWhisperTranscriptionManager
    
    // Detection parameters (loaded from settings)
    private var vadSettings: VadSettings = VadSettings()
    
    // Audio buffer and state
    private val audioBuffer = mutableListOf<Float>()
    private var isSpeaking = false
    private var lastSpeechTime = 0L
    private var speechStartTime = 0L
    
    // Speech audio buffer for transcription
    private val speechAudioBuffer = mutableListOf<Float>()
    private val speechBufferLock = Any()
    
    // Transcription state
    private var isTranscriptionEnabled = false
    private var isTranscriptionInitialized = false
    
    init {
        vadProcessor = SileroVADProcessor(context)
        audioManager = AudioManager(context) { audioData, samplesRead ->
            processAudioData(audioData, samplesRead)
        }
        preferencesManager = VadPreferencesManager(context)
        vadSettings = preferencesManager.getVadSettings()
        
        // Initialize transcription manager
        transcriptionManager = VadWhisperTranscriptionManager(
            context = context,
            onTranscriptionResult = onTranscriptionResult,
            onTranscriptionError = onTranscriptionError
        )
    }
    
    suspend fun initialize(enableTranscription: Boolean = true): Boolean {
        val audioInitialized = audioManager.initialize()
        val vadInitialized = vadProcessor.initialize()
        
        if (vadInitialized) {
            vadProcessor.resetState()
        }
        
        // Initialize transcription if requested
        var transcriptionInitialized = true
        if (enableTranscription) {
            transcriptionInitialized = transcriptionManager.initialize()
            isTranscriptionInitialized = transcriptionInitialized
            isTranscriptionEnabled = transcriptionInitialized
            
            if (transcriptionInitialized) {
                android.util.Log.i("EnhancedVoiceDetectionManager", "✅ Transcription initialized")
            } else {
                android.util.Log.w("EnhancedVoiceDetectionManager", "❌ Transcription initialization failed")
            }
        }
        
        return audioInitialized && vadInitialized && transcriptionInitialized
    }
    
    fun startDetection(enableTranscription: Boolean = true): Boolean {
        if (!vadProcessor.isInitialized()) {
            return false
        }
        
        audioBuffer.clear()
        synchronized(speechBufferLock) {
            speechAudioBuffer.clear()
        }
        vadProcessor.resetState()
        isSpeaking = false
        isTranscriptionEnabled = enableTranscription && isTranscriptionInitialized
        
        return audioManager.startRecording()
    }
    
    fun stopDetection() {
        audioManager.stopRecording()
        
        // Transcribe any remaining speech audio if we're still speaking
        if (isSpeaking && isTranscriptionEnabled) {
            transcribeCurrentSpeech()
        }
        
        if (isSpeaking) {
            isSpeaking = false
            onSpeechStateChange(false)
            onSpeechEnded()
        }
        
        synchronized(speechBufferLock) {
            speechAudioBuffer.clear()
        }
    }
    
    fun isDetecting(): Boolean = audioManager.isCurrentlyRecording()
    
    fun isModelInitialized(): Boolean = vadProcessor.isInitialized()
    
    fun getVadSettings(): VadSettings = vadSettings
    
    fun updateVadSettings(newSettings: VadSettings) {
        vadSettings = newSettings
        preferencesManager.saveVadSettings(newSettings)
        android.util.Log.d("EnhancedVoiceDetectionManager", 
            "Updated VAD settings: minSpeech=${newSettings.minimumSpeechDurationMs}ms, " +
            "silence=${newSettings.silenceTimeoutMs}ms, threshold=${newSettings.vadThreshold}")
    }
    
    fun isTranscriptionReady(): Boolean = transcriptionManager.isReady()
    
    fun setTranscriptionEnabled(enabled: Boolean) {
        isTranscriptionEnabled = enabled && isTranscriptionInitialized
    }
    
    private fun processAudioData(audioData: FloatArray, samplesRead: Int) {
        // Buffer speech audio for transcription when speech is detected
        if (isSpeaking && isTranscriptionEnabled) {
            synchronized(speechBufferLock) {
                for (i in 0 until samplesRead) {
                    speechAudioBuffer.add(audioData[i])
                }
            }
        }
        
        // Add audio data to VAD buffer
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
        val isSpeechDetected = vadScore > vadSettings.vadThreshold
        
        if (isSpeechDetected) {
            // Speech detected
            if (!isSpeaking) {
                speechStartTime = currentTime
                isSpeaking = true
                synchronized(speechBufferLock) {
                    speechAudioBuffer.clear() // Start fresh for new speech segment
                }
                android.util.Log.d("EnhancedVoiceDetectionManager", "Speech started - beginning audio buffer")
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
                if (silenceDuration > vadSettings.silenceTimeoutMs && speechDuration > vadSettings.minimumSpeechDurationMs) {
                    val bufferSize = synchronized(speechBufferLock) { speechAudioBuffer.size }
                    android.util.Log.d("EnhancedVoiceDetectionManager", 
                        "Speech ended - transcribing $bufferSize samples (duration: ${speechDuration}ms)")
                    
                    // Transcribe the collected speech audio
                    if (isTranscriptionEnabled) {
                        transcribeCurrentSpeech()
                    }
                    
                    isSpeaking = false
                    onSpeechStateChange(false)
                    onSpeechEnded()
                }
            }
        }
    }
    
    private fun transcribeCurrentSpeech() {
        val speechData = synchronized(speechBufferLock) {
            if (speechAudioBuffer.isEmpty()) {
                android.util.Log.d("EnhancedVoiceDetectionManager", "No speech audio to transcribe")
                return
            }
            
            val data = speechAudioBuffer.toFloatArray()
            speechAudioBuffer.clear()
            data
        }
        
        android.util.Log.d("EnhancedVoiceDetectionManager", 
            "Starting transcription of ${speechData.size} samples (${speechData.size / 16000.0f} seconds)")
        
        // Send to transcription manager
        transcriptionManager.transcribeAudioSegment(speechData)
    }
    
    fun release() {
        stopDetection()
        audioManager.release()
        vadProcessor.release()
        transcriptionManager.release()
    }
    
    companion object {
        const val DEFAULT_VAD_THRESHOLD = 0.5f
        const val DEFAULT_SILENCE_TIMEOUT = 300L
        const val DEFAULT_MIN_SPEECH_DURATION = 200L
    }
} 