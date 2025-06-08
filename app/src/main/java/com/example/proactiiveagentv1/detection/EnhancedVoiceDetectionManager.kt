package com.example.proactiiveagentv1.detection

import android.content.Context
import com.example.proactiiveagentv1.audio.AudioManager
import com.example.proactiiveagentv1.vad.SileroVADProcessor
import com.example.proactiiveagentv1.transcription.LiveTranscriptionManager
import com.example.proactiiveagentv1.transcription.ONNXTranscriptionManager
import com.example.proactiiveagentv1.whisper.WhisperSegment
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class EnhancedVoiceDetectionManager(
    private val context: Context,
    private val onVoiceActivityUpdate: (Float) -> Unit,
    private val onSpeechStateChange: (Boolean) -> Unit,
    private val onSpeechStarted: (Float) -> Unit,
    private val onSpeechEnded: () -> Unit,
    private val onTranscriptionResult: (String, List<WhisperSegment>) -> Unit,
    private val onTranscriptionError: (String) -> Unit
) {
    private val audioManager: AudioManager
    private val vadProcessor: SileroVADProcessor
    private val transcriptionManager: LiveTranscriptionManager
    
    // Detection parameters
    private val vadThreshold = 0.5f
    private val silenceTimeout = 500L
    private val minimumSpeechDuration = 200L
    
    // Audio buffer and state
    private val audioBuffer = mutableListOf<Float>()
    private var isSpeaking = false
    private var lastSpeechTime = 0L
    private var speechStartTime = 0L
    
    // Speech audio buffer for transcription (synchronized access)
    private val speechAudioBuffer = mutableListOf<Float>()
    private val speechBufferLock = Any()
    
    // Transcription state
    private var isTranscriptionEnabled = false
    private var isTranscriptionInitialized = false
    private var transcriptionInProgress = false
    private var consecutiveTimeouts = 0
    
    init {
        vadProcessor = SileroVADProcessor(context)
        audioManager = AudioManager(context) { audioData, samplesRead ->
            processAudioData(audioData, samplesRead)
        }
        transcriptionManager = LiveTranscriptionManager(
            context = context,
            onTranscriptionResult = onTranscriptionResult,
            onError = onTranscriptionError
        )
    }
    
    // ONNX transcription manager as primary transcription method
    private val onnxTranscriptionManager = ONNXTranscriptionManager(
        context = context,
        onTranscriptionResult = { transcription ->
            CoroutineScope(Dispatchers.Main).launch {
                onTranscriptionResult("📝 $transcription", emptyList())
            }
        },
        onError = { error ->
            CoroutineScope(Dispatchers.Main).launch {
                onTranscriptionError("❌ $error")
            }
        }
    )
    
    suspend fun initialize(enableTranscription: Boolean = true): Boolean {
        val audioInitialized = audioManager.initialize()
        val vadInitialized = vadProcessor.initialize()
        
        if (vadInitialized) {
            vadProcessor.resetState()
        }
        
        var transcriptionInitialized = true
        if (enableTranscription) {
            // Try ONNX transcription first (full precision models should work well)
            android.util.Log.i("EnhancedVoiceDetectionManager", "🚀 Trying ONNX full precision transcription...")
            transcriptionInitialized = onnxTranscriptionManager.initialize()
            
            if (!transcriptionInitialized) {
                android.util.Log.w("EnhancedVoiceDetectionManager", "❌ ONNX failed, falling back to whisper.cpp...")
                transcriptionInitialized = transcriptionManager.initialize()
                if (transcriptionInitialized) {
                    android.util.Log.i("EnhancedVoiceDetectionManager", "✅ Whisper.cpp fallback initialized")
                }
            } else {
                android.util.Log.i("EnhancedVoiceDetectionManager", "✅ ONNX transcription initialized successfully")
            }
            
            isTranscriptionInitialized = transcriptionInitialized
        }
        
        return audioInitialized && vadInitialized && transcriptionInitialized
    }
    
    fun startDetection(enableTranscription: Boolean = true): Boolean {
        if (!vadProcessor.isInitialized()) {
            return false
        }
        
        audioBuffer.clear()
        speechAudioBuffer.clear()
        vadProcessor.resetState()
        isSpeaking = false
        isTranscriptionEnabled = enableTranscription && isTranscriptionInitialized
        
        val started = audioManager.startRecording()
        
        // Note: We don't start continuous transcription anymore - we'll transcribe on-demand during speech
        
        return started
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
    
    fun isTranscriptionReady(): Boolean = onnxTranscriptionManager.isReady() || transcriptionManager.isReady()
    
    fun isTranscriptionRunning(): Boolean {
        return if (onnxTranscriptionManager.isReady()) {
            false // ONNX processes on-demand
        } else {
            transcriptionManager.isRunning()
        }
    }
    
    private fun processAudioData(audioData: FloatArray, samplesRead: Int) {
        // Buffer speech audio for transcription when speech is detected
        if (isSpeaking && isTranscriptionEnabled) {
            synchronized(speechBufferLock) {
                for (i in 0 until samplesRead) {
                    speechAudioBuffer.add(audioData[i])
                }
            }
            
            val audioLevel = audioData.take(samplesRead).maxOrNull()?.let { kotlin.math.abs(it) } ?: 0f
            if ((System.currentTimeMillis() % 2000) < 100) { // Log occasionally
                val bufferSize = synchronized(speechBufferLock) { speechAudioBuffer.size }
                android.util.Log.d("EnhancedVoiceDetectionManager", 
                    "Buffering ${samplesRead} speech samples, total buffer: $bufferSize, audio level: $audioLevel")
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
        val isSpeechDetected = vadScore > vadThreshold
        
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
                if (silenceDuration > silenceTimeout && speechDuration > minimumSpeechDuration) {
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
        // Skip if transcription is already in progress or we've had too many timeouts
        if (transcriptionInProgress) {
            android.util.Log.w("EnhancedVoiceDetectionManager", "Skipping transcription - another transcription in progress")
            return
        }
        
        if (consecutiveTimeouts >= 3) {
            android.util.Log.w("EnhancedVoiceDetectionManager", "Skipping transcription - too many consecutive timeouts (model too slow)")
            return
        }
        
        val speechData = synchronized(speechBufferLock) {
            if (speechAudioBuffer.isEmpty()) {
                android.util.Log.d("EnhancedVoiceDetectionManager", "No speech audio to transcribe")
                return
            }
            
            val data = speechAudioBuffer.toFloatArray()
            speechAudioBuffer.clear()
            data
        }
        
        transcriptionInProgress = true
        
        // Launch transcription in background
        CoroutineScope(Dispatchers.IO).launch {
            try {
                android.util.Log.d("EnhancedVoiceDetectionManager", 
                    "Starting transcription of ${speechData.size} samples (${speechData.size / 16000.0f} seconds)")
                
                // Try ONNX first, fallback to whisper.cpp
                val fullText = if (onnxTranscriptionManager.isReady()) {
                    android.util.Log.d("EnhancedVoiceDetectionManager", "🎯 Using ONNX transcription")
                    onnxTranscriptionManager.transcribeSpeechSegment(speechData)
                } else {
                    android.util.Log.d("EnhancedVoiceDetectionManager", "🔄 Using whisper.cpp fallback")
                    val segments = transcriptionManager.transcribeSpeechSegment(speechData)
                    segments.joinToString(" ") { it.text.trim() }
                }
                
                val segments = emptyList<WhisperSegment>() // ONNX returns string directly
                
                if (fullText.isNotBlank()) {
                    android.util.Log.i("EnhancedVoiceDetectionManager", "Speech transcribed: '$fullText'")
                    consecutiveTimeouts = 0 // Reset timeout counter on success
                    
                    // Update UI on main thread
                    CoroutineScope(Dispatchers.Main).launch {
                        onTranscriptionResult(fullText, segments)
                    }
                } else {
                    android.util.Log.d("EnhancedVoiceDetectionManager", "Transcription returned empty text")
                }
                transcriptionInProgress = false
            } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                consecutiveTimeouts++
                transcriptionInProgress = false
                android.util.Log.e("EnhancedVoiceDetectionManager", "Transcription timed out after 15 seconds (timeout #$consecutiveTimeouts)")
                CoroutineScope(Dispatchers.Main).launch {
                    if (consecutiveTimeouts >= 3) {
                        onTranscriptionError("Model too slow for real-time transcription. Please use a smaller model (ggml-tiny.bin recommended)")
                    } else {
                        onTranscriptionError("Transcription timed out - model may be too large for this device")
                    }
                }
            } catch (e: Exception) {
                transcriptionInProgress = false
                android.util.Log.e("EnhancedVoiceDetectionManager", "Transcription failed", e)
                CoroutineScope(Dispatchers.Main).launch {
                    onTranscriptionError("Transcription failed: ${e.message}")
                }
            }
        }
    }
    
    fun setTranscriptionEnabled(enabled: Boolean) {
        isTranscriptionEnabled = enabled && isTranscriptionInitialized
    }
    
    fun release() {
        stopDetection()
        audioManager.release()
        vadProcessor.release()
        transcriptionManager.release()
        onnxTranscriptionManager.release()
    }
    
    companion object {
        const val DEFAULT_VAD_THRESHOLD = 0.5f
        const val DEFAULT_SILENCE_TIMEOUT = 500L
        const val DEFAULT_MIN_SPEECH_DURATION = 200L
    }
} 