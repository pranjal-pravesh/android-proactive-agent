package com.example.proactiiveagentv1.transcription

import android.content.Context
import android.util.Log
import com.example.proactiiveagentv1.whisper.WhisperContext
import com.example.proactiiveagentv1.whisper.WhisperSegment
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentLinkedQueue

class LiveTranscriptionManager(
    private val context: Context,
    private val onTranscriptionResult: (String, List<WhisperSegment>) -> Unit,
    private val onError: (String) -> Unit
) {
    private var whisperContext: WhisperContext? = null
    private var isInitialized = false
    
    private val sampleRate = 16000 // Whisper expects 16kHz
    
    companion object {
        private const val LOG_TAG = "LiveTranscriptionManager"
        private const val DEFAULT_MODEL_PATH = "models/ggml-base-q5_1.bin"
        private val FALLBACK_MODELS = listOf(
            "models/ggml-tiny.bin",
            "models/ggml-base.bin",
            "models/ggml-small.bin"
        )
    }
    
    suspend fun initialize(modelPath: String = DEFAULT_MODEL_PATH): Boolean = withContext(Dispatchers.IO) {
        return@withContext try {
            Log.d(LOG_TAG, "Initializing whisper context with model: $modelPath")
            
            // Copy model from assets if needed
            val modelFile = copyModelFromAssets(modelPath)
            
            Log.d(LOG_TAG, "Creating whisper context from file: ${modelFile.absolutePath}")
            Log.d(LOG_TAG, "Model file exists: ${modelFile.exists()}, size: ${modelFile.length()} bytes")
            
            whisperContext = WhisperContext.createContextFromFile(modelFile.absolutePath)
            isInitialized = true
            
            Log.d(LOG_TAG, "Whisper context initialized successfully")
            Log.d(LOG_TAG, "System info: ${WhisperContext.getSystemInfo()}")
            
            true
        } catch (e: Exception) {
            Log.e(LOG_TAG, "Failed to initialize whisper context", e)
            onError("Failed to initialize transcription: ${e.message}")
            false
        }
    }
    
    private fun copyModelFromAssets(modelPath: String): java.io.File {
        // First try the specified model
        var actualModelPath = modelPath
        val modelFile = java.io.File(context.filesDir, actualModelPath)
        
        Log.d(LOG_TAG, "Looking for model at: ${modelFile.absolutePath}")
        
        if (!modelFile.exists()) {
            // Check if the specified model exists in assets
            try {
                val assetList = context.assets.list("models")
                Log.d(LOG_TAG, "Available models in assets: ${assetList?.joinToString(", ") ?: "none"}")
                
                val modelFileName = actualModelPath.substringAfterLast("/")
                val modelExists = assetList?.contains(modelFileName) == true
                
                if (!modelExists) {
                    Log.w(LOG_TAG, "Specified model '$actualModelPath' not found, trying fallback models...")
                    
                    // Try fallback models
                    var foundFallback = false
                    for (fallbackPath in FALLBACK_MODELS) {
                        val fallbackFileName = fallbackPath.substringAfterLast("/")
                        if (assetList?.contains(fallbackFileName) == true) {
                            Log.i(LOG_TAG, "Using fallback model: $fallbackPath")
                            actualModelPath = fallbackPath
                            foundFallback = true
                            break
                        }
                    }
                    
                    if (!foundFallback) {
                        throw RuntimeException("No suitable whisper model found in assets. Available: ${assetList?.joinToString(", ")}")
                    }
                }
                
                val finalModelFile = java.io.File(context.filesDir, actualModelPath)
                finalModelFile.parentFile?.mkdirs()
                
                Log.d(LOG_TAG, "Copying model from assets: $actualModelPath")
                
                context.assets.open(actualModelPath).use { inputStream ->
                    val totalBytes = inputStream.available()
                    Log.d(LOG_TAG, "Copying model file, size: ${totalBytes / (1024 * 1024)} MB")
                    
                    finalModelFile.outputStream().use { outputStream ->
                        inputStream.copyTo(outputStream)
                    }
                }
                
                Log.d(LOG_TAG, "Model copied successfully to: ${finalModelFile.absolutePath}")
                Log.d(LOG_TAG, "Final model file size: ${finalModelFile.length() / (1024 * 1024)} MB")
                
                return finalModelFile
                
            } catch (e: Exception) {
                Log.e(LOG_TAG, "Failed to copy model from assets", e)
                throw RuntimeException("Failed to copy model from assets: ${e.message}", e)
            }
        } else {
            Log.d(LOG_TAG, "Model already exists locally, size: ${modelFile.length() / (1024 * 1024)} MB")
            return modelFile
        }
    }
    
    suspend fun transcribeSpeechSegment(audioData: FloatArray): List<WhisperSegment> = withContext(Dispatchers.IO) {
        if (!isInitialized) {
            throw IllegalStateException("Transcription not initialized")
        }
        
        if (audioData.isEmpty()) {
            Log.w(LOG_TAG, "Empty audio data provided for transcription")
            return@withContext emptyList()
        }
        
        val audioLevel = audioData.maxOrNull()?.let { kotlin.math.abs(it) } ?: 0f
        Log.d(LOG_TAG, "Transcribing speech segment: ${audioData.size} samples (${audioData.size / sampleRate.toFloat()} seconds), max level: $audioLevel")
        
        return@withContext try {
            whisperContext?.transcribeDataWithTime(audioData) ?: emptyList()
        } catch (e: Exception) {
            Log.e(LOG_TAG, "Failed to transcribe speech segment", e)
            throw e
        }
    }
    
    fun isRunning(): Boolean = false // No longer running continuously
    
    fun isReady(): Boolean = isInitialized && whisperContext?.isValid() == true
    
    fun release() {
        Log.d(LOG_TAG, "Releasing transcription manager...")
        whisperContext?.release()
        whisperContext = null
        isInitialized = false
    }
} 