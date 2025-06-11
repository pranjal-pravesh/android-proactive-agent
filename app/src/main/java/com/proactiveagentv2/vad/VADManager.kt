package com.proactiveagentv2.vad

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log

class VADManager(context: Context) {
    private val vadProcessor = SileroVADProcessor(context)
    private val handler = Handler(Looper.getMainLooper())
    
    // VAD parameters
    private val speechThreshold = 0.5f
    private val silenceThreshold = 0.3f
    private val minSpeechDurationMs = 500L // Minimum speech duration to consider as valid speech
    private val maxSilenceDurationMs = 1500L // Maximum silence before considering speech ended
    
    // State tracking
    private var isSpeechDetected = false
    private var speechStartTime = 0L
    private var lastSpeechTime = 0L
    private var silenceStartTime = 0L
    
    // Callbacks
    private var onSpeechStartListener: (() -> Unit)? = null
    private var onSpeechEndListener: (() -> Unit)? = null
    private var onVADStatusListener: ((Boolean, Float) -> Unit)? = null
    
    // Audio buffer for processing
    private val audioBuffer = mutableListOf<Float>()
    private val processingRunnable = object : Runnable {
        override fun run() {
            checkForSpeechEnd()
            handler.postDelayed(this, 100) // Check every 100ms
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
        handler.post(processingRunnable)
        Log.d(TAG, "VAD started")
    }
    
    fun stopVAD() {
        handler.removeCallbacks(processingRunnable)
        resetState()
        Log.d(TAG, "VAD stopped")
    }
    
    fun processAudioChunk(audioData: FloatArray) {
        // Add audio data to buffer
        audioBuffer.addAll(audioData.toList())
        
        // Process in chunks of 512 samples (32ms at 16kHz)
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
                Log.d(TAG, "Speech started - probability: $speechProb")
                onSpeechStartListener?.invoke()
            }
            
            isSpeech && isSpeechDetected -> {
                // Continuing speech
                lastSpeechTime = currentTime
                silenceStartTime = 0L // Reset silence timer
            }
            
            !isSpeech && isSpeechDetected -> {
                // Potential silence during speech
                if (speechProb < silenceThreshold) {
                    if (silenceStartTime == 0L) {
                        silenceStartTime = currentTime
                    }
                    // Don't reset lastSpeechTime here, let the timeout handle it
                }
            }
        }
    }
    
    private fun checkForSpeechEnd() {
        if (!isSpeechDetected) return
        
        val currentTime = System.currentTimeMillis()
        val speechDuration = currentTime - speechStartTime
        val silenceDuration = if (silenceStartTime > 0) currentTime - silenceStartTime else 0
        
        // Check if we should end speech detection
        val shouldEndSpeech = speechDuration >= minSpeechDurationMs && 
                             silenceDuration >= maxSilenceDurationMs
        
        if (shouldEndSpeech) {
            Log.d(TAG, "Speech ended - duration: ${speechDuration}ms, silence: ${silenceDuration}ms")
            isSpeechDetected = false
            onSpeechEndListener?.invoke()
            resetState()
        }
    }
    
    private fun resetState() {
        isSpeechDetected = false
        speechStartTime = 0L
        lastSpeechTime = 0L
        silenceStartTime = 0L
        audioBuffer.clear()
    }
    
    fun setOnSpeechStartListener(listener: () -> Unit) {
        onSpeechStartListener = listener
    }
    
    fun setOnSpeechEndListener(listener: () -> Unit) {
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