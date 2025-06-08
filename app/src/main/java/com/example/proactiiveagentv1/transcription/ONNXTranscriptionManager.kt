package com.example.proactiiveagentv1.transcription

import android.content.Context
import android.util.Log
import kotlinx.coroutines.*

class ONNXTranscriptionManager(
    private val context: Context,
    private val onTranscriptionResult: (String) -> Unit,
    private val onError: (String) -> Unit
) {
    private var whisperProcessor: WhisperONNXProcessor? = null
    private var isInitialized = false
    
    companion object {
        private const val TAG = "ONNXTranscriptionManager"
    }
    
    suspend fun initialize(): Boolean = withContext(Dispatchers.IO) {
        return@withContext try {
            Log.i(TAG, "🚀 Initializing ONNX transcription manager...")
            
            whisperProcessor = WhisperONNXProcessor(context)
            val success = whisperProcessor!!.initialize()
            
            if (success) {
                isInitialized = true
                Log.i(TAG, "✅ ONNX transcription manager ready")
            } else {
                Log.e(TAG, "❌ Failed to initialize Whisper ONNX processor")
                onError("Failed to initialize ONNX transcription")
            }
            
            success
        } catch (e: Exception) {
            Log.e(TAG, "❌ Exception during initialization", e)
            onError("Transcription initialization error: ${e.message}")
            false
        }
    }
    
    suspend fun transcribeSpeechSegment(audioData: FloatArray): String = withContext(Dispatchers.IO) {
        if (!isInitialized || whisperProcessor == null) {
            Log.w(TAG, "⚠️ Transcription not initialized")
            return@withContext ""
        }
        
        if (audioData.isEmpty()) {
            Log.w(TAG, "⚠️ Empty audio data provided")
            return@withContext ""
        }
        
        return@withContext try {
            val audioLevel = audioData.maxOrNull()?.let { kotlin.math.abs(it) } ?: 0f
            val duration = audioData.size / 16000.0f
            
            Log.d(TAG, "🎙️ Transcribing ${audioData.size} samples (${String.format("%.2f", duration)}s, level: ${String.format("%.3f", audioLevel)})")
            
            val startTime = System.currentTimeMillis()
            val result = whisperProcessor!!.transcribeAudio(audioData)
            val endTime = System.currentTimeMillis()
            
            Log.i(TAG, "✅ ONNX transcription completed in ${endTime - startTime}ms")
            
            if (result.isNotEmpty()) {
                Log.i(TAG, "📝 Result: '$result'")
            } else {
                Log.d(TAG, "⚠️ Empty transcription result")
            }
            
            result
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error during transcription", e)
            ""
        }
    }
    
    fun isReady(): Boolean = isInitialized && whisperProcessor?.isInitialized() == true
    
    fun release() {
        Log.d(TAG, "🔄 Releasing ONNX transcription manager...")
        whisperProcessor?.release()
        whisperProcessor = null
        isInitialized = false
    }
} 