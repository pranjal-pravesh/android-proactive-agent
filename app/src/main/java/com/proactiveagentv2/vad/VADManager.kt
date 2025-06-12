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
    
    // Rolling audio buffer to capture pre-speech audio (like Python's buffering approach)
    private val rollingAudioBuffer = mutableListOf<Float>()
    
    // Callbacks (similar to Python's event handling)
    private var onSpeechStartListener: (() -> Unit)? = null
    private var onSpeechEndListener: ((FloatArray) -> Unit)? = null // Now passes the speech segment
    private var onVADStatusListener: ((Boolean, Float) -> Unit)? = null
    
    // Audio buffer for processing
    private val audioBuffer = mutableListOf<Float>()
    
    // Timer for checking speech end conditions (like Python's timeout mechanism)
    private val speechEndCheckRunnable = object : Runnable {
        override fun run() {
            checkForSpeechEnd()
            if (isInitialized()) {
                handler.postDelayed(this, 50) // Check every 50ms for more responsive detection
            }
        }
    }
    
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
        handler.post(speechEndCheckRunnable)
        Log.d(TAG, "VAD started with ${maxSilenceDurationMs}ms silence timeout (Python-like)")
    }
    
    fun stopVAD() {
        handler.removeCallbacks(speechEndCheckRunnable)
        resetState()
        Log.d(TAG, "VAD stopped")
    }
    
    // PYTHON-LIKE AUDIO PROCESSING: Process smaller chunks continuously
    fun processAudioChunk(audioData: FloatArray) {
        // Add all incoming audio to rolling buffer (for pre-speech capture, like Python)
        rollingAudioBuffer.addAll(audioData.toList())
        
        // Limit rolling buffer size (prevent memory issues)
        while (rollingAudioBuffer.size > PRE_SPEECH_BUFFER_SIZE + MAX_SAMPLES_FOR_TRANSCRIPTION) {
            rollingAudioBuffer.removeAt(0)
        }
        
        // THREAD SAFETY FIX: Synchronized access to speech segment buffer
        // If we're recording a speech segment, add to speech segment buffer (like Python's buffered_audio)
        synchronized(speechSegmentSamples) {
            if (isRecordingSpeechSegment) {
                speechSegmentSamples.addAll(audioData.toList())
                
                // Limit speech segment size to prevent memory issues
                if (speechSegmentSamples.size > MAX_SAMPLES_FOR_TRANSCRIPTION) {
                    Log.w(TAG, "Speech segment too long (${speechSegmentSamples.size} samples), triggering transcription")
                    // Use handler to trigger speech end on main thread to avoid nested synchronization
                    handler.post { forceSpeechEnd() }
                    return
                }
            }
        }
        
        // Add audio data to processing buffer
        audioBuffer.addAll(audioData.toList())
        
        // Process in chunks of 512 samples (32ms at 16kHz) - VAD model requirement
        while (audioBuffer.size >= SileroVADProcessor.WINDOW_SIZE_SAMPLES) {
            val chunk = FloatArray(SileroVADProcessor.WINDOW_SIZE_SAMPLES)
            
            // Copy first N elements to chunk
            for (i in 0 until SileroVADProcessor.WINDOW_SIZE_SAMPLES) {
                chunk[i] = audioBuffer[i]
            }
            
            // Remove the first N elements efficiently
            audioBuffer.subList(0, SileroVADProcessor.WINDOW_SIZE_SAMPLES).clear()
            
            // Process through VAD
            val speechProb = vadProcessor.processAudio(chunk)
            processVADResult(speechProb)
        }
    }
    
    // PYTHON-LIKE VAD RESULT PROCESSING: Similar to Python's speech detection logic
    private fun processVADResult(speechProb: Float) {
        val currentTime = System.currentTimeMillis()
        val isSpeech = speechProb > speechThreshold
        
        // Update listeners with current VAD status
        onVADStatusListener?.invoke(isSpeech, speechProb)
        
        when {
            isSpeech && !isSpeechDetected -> {
                // Speech started (like Python's speech detection begin)
                isSpeechDetected = true
                speechStartTime = currentTime
                lastSpeechTime = currentTime
                silenceStartTime = 0L
                
                // THREAD SAFETY FIX: Synchronized access when starting speech recording
                // Start recording speech segment and include pre-speech buffer (like Python)
                synchronized(speechSegmentSamples) {
                    isRecordingSpeechSegment = true
                    speechSegmentSamples.clear()
                    
                    // Add recent audio from rolling buffer (pre-speech context, like Python's buffering)
                    val preSpeechSamples = minOf(PRE_SPEECH_BUFFER_SIZE, rollingAudioBuffer.size)
                    val startIndex = maxOf(0, rollingAudioBuffer.size - preSpeechSamples)
                    speechSegmentSamples.addAll(rollingAudioBuffer.subList(startIndex, rollingAudioBuffer.size))
                }
                
                Log.d(TAG, "Speech started - probability: $speechProb, pre-buffered ${speechSegmentSamples.size} samples")
                onSpeechStartListener?.invoke()
            }
            
            isSpeech && isSpeechDetected -> {
                // Continuing speech (update last speech time, like Python)
                lastSpeechTime = currentTime
                silenceStartTime = 0L // Reset silence timer
            }
            
            !isSpeech && isSpeechDetected -> {
                // Potential silence during speech (like Python's silence detection)
                if (speechProb < silenceThreshold) {
                    if (silenceStartTime == 0L) {
                        silenceStartTime = currentTime
                    }
                    // Don't reset lastSpeechTime here, let the timeout handle it
                }
            }
        }
    }
    
    // PYTHON-LIKE SPEECH END DETECTION: Similar to Python's timeout-based speech end detection
    private fun checkForSpeechEnd() {
        if (!isSpeechDetected) return
        
        val currentTime = System.currentTimeMillis()
        val speechDuration = currentTime - speechStartTime
        val silenceDuration = if (silenceStartTime > 0) currentTime - silenceStartTime else 0
        
        // Check if we should end speech detection (like Python's 1 second timeout)
        val shouldEndSpeech = speechDuration >= minSpeechDurationMs && 
                             silenceDuration >= maxSilenceDurationMs
        
        if (shouldEndSpeech) {
            Log.d(TAG, "Speech end detected: speech=${speechDuration}ms, silence=${silenceDuration}ms (Python-like timeout)")
            forceSpeechEnd()
        }
    }
    
    // PYTHON-LIKE SPEECH END PROCESSING: Similar to Python's _process_speech method
    private fun forceSpeechEnd() {
        val speechDuration = System.currentTimeMillis() - speechStartTime
        val silenceDuration = if (silenceStartTime > 0) System.currentTimeMillis() - silenceStartTime else 0
        
        Log.d(TAG, "Speech ended - duration: ${speechDuration}ms, silence: ${silenceDuration}ms, samples: ${speechSegmentSamples.size}")
        
        // Stop recording speech segment and create a safe copy to avoid ConcurrentModificationException
        isRecordingSpeechSegment = false
        
        // THREAD SAFETY FIX: Create a synchronized copy of the speech samples
        val speechSamplesCopy = synchronized(speechSegmentSamples) {
            speechSegmentSamples.toFloatArray()
        }
        
        val speechSegment = prepareSpeechSegmentForTranscription(speechSamplesCopy)
        
        // Reset state before calling listener (like Python)
        isSpeechDetected = false
        resetSpeechSegmentState()
        
        // Call listener with the speech segment (like Python's transcription trigger)
        if (speechSegment.isNotEmpty()) {
            Log.d(TAG, "Triggering transcription for ${speechSegment.size} samples (${speechSegment.size / SAMPLE_RATE.toFloat()}s)")
            onSpeechEndListener?.invoke(speechSegment)
        } else {
            Log.w(TAG, "Speech segment too short for transcription")
        }
    }
    
    private fun prepareSpeechSegmentForTranscription(rawSegment: FloatArray): FloatArray {
        // Check minimum length
        if (rawSegment.size < MIN_SAMPLES_FOR_TRANSCRIPTION) {
            Log.w(TAG, "Speech segment too short: ${rawSegment.size} samples (need at least $MIN_SAMPLES_FOR_TRANSCRIPTION)")
            return FloatArray(0)
        }
        
        // Use the segment as-is (don't pad for shorter segments as file-based transcription handles this)
        Log.d(TAG, "Prepared speech segment: ${rawSegment.size} samples (${rawSegment.size / SAMPLE_RATE.toFloat()} seconds)")
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
        synchronized(speechSegmentSamples) {
            isRecordingSpeechSegment = false
            speechSegmentSamples.clear()
            currentSpeechSegment.reset()
        }
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