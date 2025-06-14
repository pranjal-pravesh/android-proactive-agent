package com.proactiveagentv2.managers

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.proactiveagentv2.asr.Whisper
import com.proactiveagentv2.llm.LLMManager
import com.proactiveagentv2.ui.MainViewModel
import com.proactiveagentv2.utils.WaveUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Manages speech-to-text transcription processing including audio conversion and Whisper model interaction
 */
class TranscriptionManager(
    private val context: Context,
    private val viewModel: MainViewModel,
    private val coroutineScope: CoroutineScope
) {
    private var whisper: Whisper? = null
    private var sdcardDataFolder: File? = null
    private var llmManager: LLMManager? = null
    private var classifierManager: ClassifierManager? = null
    private val handler = Handler(Looper.getMainLooper())
    
    // Transcription state
    private var isTranscribing = false
    private val transcriptionSync = SharedTranscriptionResource()
    
    // Callbacks
    var onTranscriptionComplete: ((segmentFile: File) -> Unit)? = null
    
    fun initialize(whisper: Whisper, dataFolder: File, llmManager: LLMManager? = null, classifierManager: ClassifierManager? = null) {
        this.whisper = whisper
        this.sdcardDataFolder = dataFolder
        this.llmManager = llmManager
        this.classifierManager = classifierManager
        
        setupWhisperListener()
        
        Log.d(TAG, "TranscriptionManager initialized")
    }
    
    private fun setupWhisperListener() {
        whisper?.setListener(object : Whisper.WhisperListener {
            override fun onUpdateReceived(message: String?) {
                handler.post {
                    when (message) {
                        Whisper.MSG_PROCESSING -> {
                            viewModel.updateStatus("Transcribing speech...")
                        }
                        Whisper.MSG_PROCESSING_DONE -> {
                            viewModel.updateStatus("Transcription completed")
                        }
                        else -> {
                            viewModel.updateStatus(message ?: "Processing...")
                        }
                    }
                }
            }
            
            override fun onResultReceived(result: String?) {
                handler.post {
                    if (!result.isNullOrBlank()) {
                        Log.d(TAG, "Transcription result: $result")
                        viewModel.updateTranscription(result)
                        
                        // Classify the transcription using both classifiers
                        classifierManager?.classifyText(result)
                        
                        // Check if text is actionable before submitting to LLM
                        val isActionable = classifierManager?.isTextActionable(result) ?: true // Default to true if classifier not available
                        
                        if (isActionable) {
                            Log.d(TAG, "Text classified as actionable, submitting to LLM: \"$result\"")
                            submitToLLM(result)
                        } else {
                            Log.d(TAG, "Text classified as non-actionable, skipping LLM: \"$result\"")
                            viewModel.updateStatus("Transcription completed (non-actionable)")
                        }
                    } else {
                        Log.w(TAG, "Empty transcription result received")
                    }
                    
                    synchronized(transcriptionSync) {
                        isTranscribing = false
                    }
                }
            }
        })
    }
    
    fun transcribeSpeechSegment(audioSamples: FloatArray) {
        synchronized(transcriptionSync) {
            if (isTranscribing) {
                Log.w(TAG, "Transcription already in progress, skipping segment")
                return
            }
            isTranscribing = true
        }
        
        coroutineScope.launch {
            try {
                val result = processAudioSegment(audioSamples)
                
                withContext(Dispatchers.Main) {
                    when (result) {
                        is TranscriptionResult.Success -> {
                            Log.d(TAG, "Successfully processed audio segment: ${result.segmentFile.name}")
                            onTranscriptionComplete?.invoke(result.segmentFile)
                        }
                        is TranscriptionResult.Error -> {
                            Log.e(TAG, "Transcription failed: ${result.message}")
                            viewModel.updateStatus("Transcription failed: ${result.message}")
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Unexpected error during transcription", e)
                withContext(Dispatchers.Main) {
                    viewModel.updateStatus("Transcription error: ${e.message}")
                }
            } finally {
                synchronized(transcriptionSync) {
                    isTranscribing = false
                }
            }
        }
    }
    
    private suspend fun processAudioSegment(audioSamples: FloatArray): TranscriptionResult = withContext(Dispatchers.IO) {
        try {
            // Convert float array to 16-bit PCM WAV file
            val segmentFile = createSegmentWaveFile(audioSamples)
            
            if (!segmentFile.exists() || segmentFile.length() == 0L) {
                return@withContext TranscriptionResult.Error("Failed to create audio segment file")
            }
            
            Log.d(TAG, "Processing segment: ${segmentFile.name}, size: ${segmentFile.length()} bytes, samples: ${audioSamples.size}")
            
            // Transcribe using Whisper
            whisper?.setFilePath(segmentFile.absolutePath)
            whisper?.setAction(Whisper.ACTION_TRANSCRIBE)
            whisper?.start()
            
            TranscriptionResult.Success(segmentFile)
        } catch (e: Exception) {
            Log.e(TAG, "Error processing audio segment", e)
            TranscriptionResult.Error(e.message ?: "Unknown processing error")
        }
    }
    
    private fun createSegmentWaveFile(audioSamples: FloatArray): File {
        val timestamp = System.currentTimeMillis()
        val segmentFile = File(sdcardDataFolder, "speech_segment_$timestamp.wav")
        
        try {
            // Convert float samples to 16-bit PCM
            val pcmData = convertFloatToPCM(audioSamples)
            
            // Create WAV file
            WaveUtil.createWaveFile(segmentFile.absolutePath, pcmData, SAMPLE_RATE, CHANNELS, BITS_PER_SAMPLE / 8)
            
            Log.d(TAG, "Created segment WAV file: ${segmentFile.name}, PCM bytes: ${pcmData.size}")
            
        } catch (e: Exception) {
            Log.e(TAG, "Error creating segment WAV file", e)
            throw e
        }
        
        return segmentFile
    }
    
    private fun convertFloatToPCM(floatSamples: FloatArray): ByteArray {
        val pcmData = ByteArray(floatSamples.size * 2) // 16-bit = 2 bytes per sample
        val buffer = ByteBuffer.wrap(pcmData).apply {
            order(ByteOrder.LITTLE_ENDIAN)
        }
        
        floatSamples.forEach { sample ->
            // Clamp and convert float (-1.0 to 1.0) to 16-bit signed integer
            val clampedSample = sample.coerceIn(-1.0f, 1.0f)
            val pcmValue = (clampedSample * SHORT_MAX_VALUE).toInt().toShort()
            buffer.putShort(pcmValue)
        }
        
        return pcmData
    }
    
    fun isCurrentlyTranscribing(): Boolean {
        synchronized(transcriptionSync) {
            return isTranscribing
        }
    }
    
    fun updateWhisperModel(newWhisper: Whisper) {
        this.whisper = newWhisper
        setupWhisperListener()
        Log.d(TAG, "Whisper model updated")
    }
    
    private fun submitToLLM(transcriptionText: String) {
        llmManager?.let { llm ->
            // Only submit to LLM if it's properly initialized
            if (!llm.isModelInitialized()) {
                Log.d(TAG, "LLM not initialized, skipping submission: \"$transcriptionText\"")
                return
            }
            
            coroutineScope.launch {
                try {
                    Log.d(TAG, "Submitting transcription to enhanced LLM: \"$transcriptionText\"")
                    val startTime = System.currentTimeMillis()
                    
                    // Use enhanced LLM with tool support
                    val llmResponse = llm.generateEnhancedResponse(
                        userInput = transcriptionText,
                        includeContext = true,
                        useTools = true
                    )
                    
                    val duration = System.currentTimeMillis() - startTime
                    
                    withContext(Dispatchers.Main) {
                        if (llmResponse.success && llmResponse.finalText.isNotBlank()) {
                            // Log tool usage if any
                            if (llmResponse.hasToolCalls) {
                                Log.d(TAG, "LLM used ${llmResponse.toolResults.size} tools: ${llmResponse.toolResults.map { it.toolName }}")
                                llmResponse.toolResults.forEach { toolResult ->
                                    Log.d(TAG, "Tool ${toolResult.toolName}: ${if (toolResult.success) "SUCCESS" else "FAILED"}")
                                }
                            }
                            
                            viewModel.appendLLMResponse(llmResponse.finalText, duration)
                            Log.d(TAG, "Enhanced LLM response received (${duration}ms): \"${llmResponse.finalText}\"")
                            
                            // Show tool results in logs for debugging
                            if (llmResponse.toolResults.isNotEmpty()) {
                                llmResponse.toolResults.forEach { result ->
                                    Log.d(TAG, "Tool result - ${result.toolName}: ${result.result}")
                                }
                            }
                        } else {
                            Log.w(TAG, "Enhanced LLM response failed or empty: ${llmResponse.error}")
                            
                            // Fallback to basic response if enhanced fails
                            val fallbackResponse = llm.generateResponse(transcriptionText) ?: ""
                            if (fallbackResponse.isNotBlank()) {
                                viewModel.appendLLMResponse(fallbackResponse, duration)
                                Log.d(TAG, "Fallback LLM response used: \"$fallbackResponse\"")
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error submitting to enhanced LLM", e)
                    // Don't crash the app, just log the error
                    withContext(Dispatchers.Main) {
                        Log.w(TAG, "Enhanced LLM processing failed for: \"$transcriptionText\"")
                        
                        // Try basic LLM as fallback
                        try {
                            coroutineScope.launch {
                                val fallbackResponse = llm.generateResponse(transcriptionText) ?: ""
                                if (fallbackResponse.isNotBlank()) {
                                    viewModel.appendLLMResponse(fallbackResponse, 0)
                                    Log.d(TAG, "Fallback response used after error")
                                }
                            }
                        } catch (fallbackError: Exception) {
                            Log.e(TAG, "Even fallback LLM failed", fallbackError)
                        }
                    }
                }
            }
        }
    }
    
    fun release() {
        synchronized(transcriptionSync) {
            isTranscribing = false
        }
        
        // Clean up temporary segment files
        cleanupTemporaryFiles()
        
        whisper = null
        Log.d(TAG, "TranscriptionManager released")
    }
    
    private fun cleanupTemporaryFiles() {
        try {
            sdcardDataFolder?.listFiles { file ->
                file.name.startsWith("speech_segment_") && file.name.endsWith(".wav")
            }?.forEach { file ->
                if (file.delete()) {
                    Log.d(TAG, "Cleaned up temporary file: ${file.name}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error cleaning up temporary files", e)
        }
    }
    
    private sealed class TranscriptionResult {
        data class Success(val segmentFile: File) : TranscriptionResult()
        data class Error(val message: String) : TranscriptionResult()
    }
    
    private class SharedTranscriptionResource
    
    companion object {
        private const val TAG = "TranscriptionManager"
        
        // Audio format constants
        private const val SAMPLE_RATE = 16000
        private const val BITS_PER_SAMPLE = 16
        private const val CHANNELS = 1
        private const val SHORT_MAX_VALUE = 32767.0f
    }
} 