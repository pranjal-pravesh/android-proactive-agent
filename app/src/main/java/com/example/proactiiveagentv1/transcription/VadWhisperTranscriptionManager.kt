package com.example.proactiiveagentv1.transcription

import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import java.io.File

class VadWhisperTranscriptionManager(
    private val context: Context,
    private val onTranscriptionResult: (String) -> Unit,
    private val onTranscriptionError: (String) -> Unit
) {
    companion object {
        private const val TAG = "VadWhisperManager"
        private const val WHISPER_TINY_MODEL = "whisper-tiny.en.tflite"
        private const val VOCAB_FILE = "filters_vocab_en.bin"
    }
    
    private var whisperEngine: WhisperTFLiteEngine? = null
    private var isInitialized = false
    private var transcriptionJob: Job? = null
    
    suspend fun initialize(): Boolean = withContext(Dispatchers.IO) {
        return@withContext try {
            Log.d(TAG, "Initializing VAD-Whisper transcription manager...")
            
            // Initialize Whisper TFLite engine
            whisperEngine = WhisperTFLiteEngine(context)
            
            // Get model and vocab file paths
            val modelPath = getAssetFilePath(WHISPER_TINY_MODEL)
            val vocabPath = getAssetFilePath(VOCAB_FILE)
            
            if (modelPath == null || vocabPath == null) {
                Log.e(TAG, "Required files not found in assets")
                return@withContext false
            }
            
            // Initialize the engine
            val initialized = whisperEngine!!.initialize(modelPath, vocabPath, multilingual = false)
            
            if (initialized) {
                isInitialized = true
                Log.i(TAG, "VAD-Whisper transcription manager initialized successfully")
            } else {
                Log.e(TAG, "Failed to initialize Whisper engine")
            }
            
            initialized
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize transcription manager", e)
            false
        }
    }
    
    fun transcribeAudioSegment(audioSamples: FloatArray) {
        if (!isInitialized || whisperEngine == null) {
            Log.w(TAG, "Transcription manager not initialized")
            onTranscriptionError("Transcription engine not ready")
            return
        }
        
        // Cancel any ongoing transcription
        transcriptionJob?.cancel()
        
        transcriptionJob = CoroutineScope(Dispatchers.IO).launch {
            try {
                Log.d(TAG, "Starting transcription of ${audioSamples.size} samples " +
                      "(${audioSamples.size / 16000f} seconds)")
                
                // Run transcription
                val result = whisperEngine!!.transcribeAudioBuffer(audioSamples)
                
                if (result.isNotBlank()) {
                    Log.i(TAG, "Transcription completed: '$result'")
                    
                    withContext(Dispatchers.Main) {
                        onTranscriptionResult(result)
                    }
                } else {
                    Log.d(TAG, "Transcription returned empty result")
                    withContext(Dispatchers.Main) {
                        onTranscriptionResult("[No speech detected]")
                    }
                }
                
            } catch (e: CancellationException) {
                Log.d(TAG, "Transcription cancelled")
            } catch (e: Exception) {
                Log.e(TAG, "Transcription failed", e)
                withContext(Dispatchers.Main) {
                    onTranscriptionError("Transcription failed: ${e.message}")
                }
            }
        }
    }
    
    fun isReady(): Boolean = isInitialized && whisperEngine?.isEngineInitialized() == true
    
    fun cancelTranscription() {
        transcriptionJob?.cancel()
        transcriptionJob = null
    }
    
    fun release() {
        cancelTranscription()
        whisperEngine?.release()
        whisperEngine = null
        isInitialized = false
        Log.d(TAG, "VAD-Whisper transcription manager released")
    }
    
    private fun getAssetFilePath(fileName: String): String? {
        return try {
            val assetFile = File(context.filesDir, fileName)
            
            if (!assetFile.exists()) {
                // Copy from assets to internal storage
                context.assets.open(fileName).use { inputStream ->
                    assetFile.outputStream().use { outputStream ->
                        inputStream.copyTo(outputStream)
                    }
                }
                Log.d(TAG, "Copied asset file: $fileName")
            }
            
            assetFile.absolutePath
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get asset file: $fileName", e)
            null
        }
    }
} 