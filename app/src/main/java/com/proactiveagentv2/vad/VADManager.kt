package com.proactiveagentv2.vad

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class VADManager(context: Context) {
    private val vadProcessor = SileroVADProcessor(context)
    private val handler = Handler(Looper.getMainLooper())
    
    // VAD parameters - made mutable for settings (similar to Python's configurable thresholds)
    var speechThreshold = 0.5f
    var silenceThreshold = 0.3f
    var minSpeechDurationMs = 300L // Minimum speech duration to consider as valid speech
    var maxSilenceDurationMs = 1000L // Maximum silence before considering speech ended (like Python's 1 second timeout)
    
    // Audio constants
    private val SAMPLE_RATE = 16000 // 16kHz
    private val MIN_SAMPLES_FOR_TRANSCRIPTION = SAMPLE_RATE / 2 // 0.5 second minimum
    private val MAX_SAMPLES_FOR_TRANSCRIPTION = SAMPLE_RATE * 30 // 30 seconds maximum
    private val PRE_SPEECH_BUFFER_SIZE = SAMPLE_RATE / 2 // 0.5 second buffer before speech (like Python)
    
    // State tracking (similar to Python's speech state management)
    private var isSpeechDetected = false
    private var speechStartTime = 0L
    private var lastSpeechTime = 0L
    private var silenceStartTime = 0L
    
    // Speech segment recording (similar to Python's buffered_audio)
    private var currentSpeechSegment = ByteArrayOutputStream()
    private var speechSegmentSamples = mutableListOf<Float>()
    private var isRecordingSpeechSegment = false
    
    // SIMPLIFIED BUFFER: Use efficient LinkedList for rolling audio buffer
    private val rollingAudioBuffer = mutableListOf<Float>()
    private val maxRollingBufferSize = PRE_SPEECH_BUFFER_SIZE + SAMPLE_RATE // 1.5 seconds max
    
    // Callbacks (similar to Python's event handling)
    private var onSpeechStartListener: (() -> Unit)? = null
    private var onSpeechEndListener: ((FloatArray) -> Unit)? = null // Now passes the speech segment
    private var onVADStatusListener: ((Boolean, Float) -> Unit)? = null
    
    // SIMPLIFIED BUFFER: Use simple list for audio buffer processing
    private val audioBuffer = mutableListOf<Float>()
    
    // Timer for checking speech end conditions (like Python's timeout mechanism)
    private var speechEndCheckRunnable: Runnable? = null
    private var isCheckingForSpeechEnd = false
    
    fun initialize(): Boolean {
        val success = vadProcessor.initialize()
        if (success) {
            Log.d(TAG, "VAD Manager initialized successfully")
        } else {
            Log.e(TAG, "Failed to initialize VAD Manager")
        }
        return success
    }
    
    fun startVAD() {
        vadProcessor.resetState()
        resetState()
        startSpeechEndChecking()
        Log.d(TAG, "VAD started with ${maxSilenceDurationMs}ms silence timeout")
    }
    
    fun stopVAD() {
        stopSpeechEndChecking()
        resetState()
        Log.d(TAG, "VAD stopped")
    }
    
    private fun startSpeechEndChecking() {
        if (speechEndCheckRunnable == null) {
            speechEndCheckRunnable = object : Runnable {
                override fun run() {
                    if (isInitialized() && isCheckingForSpeechEnd) {
                        checkForSpeechEnd()
                        handler.postDelayed(this, 100) // Check every 100ms
                    }
                }
            }
        }
        isCheckingForSpeechEnd = true
        handler.post(speechEndCheckRunnable!!)
    }
    
    private fun stopSpeechEndChecking() {
        isCheckingForSpeechEnd = false
        speechEndCheckRunnable?.let { handler.removeCallbacks(it) }
    }
    
    // SIMPLIFIED AUDIO PROCESSING: More efficient approach
    fun processAudioChunk(audioData: FloatArray) {
        // PERFORMANCE FIX: Prevent buffer overflow - drop old data instead of expensive operations
        if (audioBuffer.size > SAMPLE_RATE * 3) { // If more than 3 seconds backlog
            audioBuffer.clear()
            Log.w(TAG, "Audio buffer overflow cleared")
        }
        
        // Add to rolling buffer efficiently
        rollingAudioBuffer.addAll(audioData.toList())
        
        // PERFORMANCE FIX: Efficient buffer size management
        if (rollingAudioBuffer.size > maxRollingBufferSize) {
            val excessSamples = rollingAudioBuffer.size - maxRollingBufferSize
            repeat(excessSamples) {
                rollingAudioBuffer.removeAt(0)
            }
        }
        
        // If recording speech segment, add to speech buffer
        if (isRecordingSpeechSegment) {
            speechSegmentSamples.addAll(audioData.toList())
            
            // Limit speech segment size
            if (speechSegmentSamples.size > MAX_SAMPLES_FOR_TRANSCRIPTION) {
                Log.w(TAG, "Speech segment too long, triggering transcription")
                handler.post { forceSpeechEnd() }
                return
            }
        }
        
        // Add to processing buffer
        audioBuffer.addAll(audioData.toList())
        
        // Process in chunks of 512 samples (32ms at 16kHz)
        while (audioBuffer.size >= SileroVADProcessor.WINDOW_SIZE_SAMPLES) {
            val chunk = audioBuffer.take(SileroVADProcessor.WINDOW_SIZE_SAMPLES).toFloatArray()
            repeat(SileroVADProcessor.WINDOW_SIZE_SAMPLES) {
                audioBuffer.removeAt(0)
            }
            
            // Process through VAD
            val speechProb = vadProcessor.processAudio(chunk)
            processVADResult(speechProb)
        }
    }
    
    // SIMPLIFIED VAD RESULT PROCESSING
    private fun processVADResult(speechProb: Float) {
        val currentTime = System.currentTimeMillis()
        val isSpeech = speechProb > speechThreshold
        
        // Update listeners with current VAD status
        onVADStatusListener?.invoke(isSpeech, speechProb)
        
        when {
            isSpeech && !isSpeechDetected -> {
                // Speech started
                isSpeechDetected = true
                speechStartTime = currentTime
                lastSpeechTime = currentTime
                silenceStartTime = 0L
                
                // Start recording speech segment and include pre-speech buffer
                isRecordingSpeechSegment = true
                speechSegmentSamples.clear()
                
                // Add recent audio from rolling buffer (pre-speech context)
                val preSpeechSamples = minOf(PRE_SPEECH_BUFFER_SIZE, rollingAudioBuffer.size)
                if (preSpeechSamples > 0) {
                    val startIndex = rollingAudioBuffer.size - preSpeechSamples
                    speechSegmentSamples.addAll(rollingAudioBuffer.subList(startIndex, rollingAudioBuffer.size))
                }
                
                Log.d(TAG, "Speech started - probability: $speechProb, pre-buffered ${speechSegmentSamples.size} samples")
                onSpeechStartListener?.invoke()
            }
            
            isSpeech && isSpeechDetected -> {
                // Continuing speech
                lastSpeechTime = currentTime
                silenceStartTime = 0L
            }
            
            !isSpeech && isSpeechDetected -> {
                // Potential silence during speech
                if (speechProb < silenceThreshold) {
                    if (silenceStartTime == 0L) {
                        silenceStartTime = currentTime
                    }
                }
            }
        }
    }
    
    // SIMPLIFIED SPEECH END DETECTION
    private fun checkForSpeechEnd() {
        if (!isSpeechDetected) return
        
        val currentTime = System.currentTimeMillis()
        val speechDuration = currentTime - speechStartTime
        val silenceDuration = if (silenceStartTime > 0) currentTime - silenceStartTime else 0
        
        val shouldEndSpeech = speechDuration >= minSpeechDurationMs && 
                             silenceDuration >= maxSilenceDurationMs
        
        if (shouldEndSpeech) {
            Log.d(TAG, "Speech end detected: speech=${speechDuration}ms, silence=${silenceDuration}ms")
            forceSpeechEnd()
        }
    }
    
    // SIMPLIFIED SPEECH END PROCESSING
    private fun forceSpeechEnd() {
        val speechDuration = System.currentTimeMillis() - speechStartTime
        val silenceDuration = if (silenceStartTime > 0) System.currentTimeMillis() - silenceStartTime else 0
        
        Log.d(TAG, "Speech ended - duration: ${speechDuration}ms, silence: ${silenceDuration}ms, samples: ${speechSegmentSamples.size}")
        
        // Stop recording and create copy
        isRecordingSpeechSegment = false
        val speechSamplesCopy = speechSegmentSamples.toFloatArray()
        
        val speechSegment = prepareSpeechSegmentForTranscription(speechSamplesCopy)
        
        // Reset state
        isSpeechDetected = false
        resetSpeechSegmentState()
        
        // Call listener
        if (speechSegment.isNotEmpty()) {
            Log.d(TAG, "Triggering transcription for ${speechSegment.size} samples")
            onSpeechEndListener?.invoke(speechSegment)
        } else {
            Log.w(TAG, "Speech segment too short for transcription")
        }
    }
    
    private fun prepareSpeechSegmentForTranscription(rawSegment: FloatArray): FloatArray {
        if (rawSegment.size < MIN_SAMPLES_FOR_TRANSCRIPTION) {
            Log.w(TAG, "Speech segment too short: ${rawSegment.size} samples")
            return FloatArray(0)
        }
        
        Log.d(TAG, "Prepared speech segment: ${rawSegment.size} samples")
        return rawSegment
    }
    
    private fun resetState() {
        isSpeechDetected = false
        speechStartTime = 0L
        lastSpeechTime = 0L
        silenceStartTime = 0L
        audioBuffer.clear()
        rollingAudioBuffer.clear()
        resetSpeechSegmentState()
    }
    
    private fun resetSpeechSegmentState() {
        isRecordingSpeechSegment = false
        speechSegmentSamples.clear()
        currentSpeechSegment.reset()
    }
    
    fun setOnSpeechStartListener(listener: () -> Unit) {
        onSpeechStartListener = listener
    }
    
    fun setOnSpeechEndListener(listener: (FloatArray) -> Unit) {
        onSpeechEndListener = listener
    }
    
    fun setOnVADStatusListener(listener: (Boolean, Float) -> Unit) {
        onVADStatusListener = listener
    }
    
    fun release() {
        stopVAD()
        vadProcessor.release()
        Log.d(TAG, "VAD Manager released")
    }
    
    fun isInitialized(): Boolean = vadProcessor.isInitialized()
    
    fun isSpeechActive(): Boolean = isSpeechDetected
    
    companion object {
        private const val TAG = "VADManager"
    }
} 