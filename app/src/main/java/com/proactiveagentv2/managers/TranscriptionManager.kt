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
    private var ttsManager: TTSManager? = null
    private var memoryManager: MemoryManager? = null
    private val handler = Handler(Looper.getMainLooper())
    
    // Transcription state
    private var isTranscribing = false
    private val transcriptionSync = SharedTranscriptionResource()
    
    // Callbacks
    var onTranscriptionComplete: ((segmentFile: File) -> Unit)? = null
    
    fun initialize(whisper: Whisper, dataFolder: File, llmManager: LLMManager? = null, classifierManager: ClassifierManager? = null, ttsManager: TTSManager? = null, memoryManager: MemoryManager? = null) {
        this.whisper = whisper
        this.sdcardDataFolder = dataFolder
        this.llmManager = llmManager
        this.classifierManager = classifierManager
        this.ttsManager = ttsManager
        this.memoryManager = memoryManager
        
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
                        
                        // Check if text is contextable for memory storage
                        val isContextable = classifierManager?.isTextContextable(result) ?: false
                        
                        // Store contextable text in memory (async)
                        if (isContextable) {
                            Log.d(TAG, "Text classified as contextable, storing in memory: \"$result\"")
                            storeInMemory(result)
                        }
                        
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
    
    /**
     * Process text input directly through the same pipeline as transcribed text
     */
    fun processTextInput(text: String) {
        if (text.isBlank()) {
            Log.w(TAG, "Empty text input provided")
            return
        }
        
        Log.d(TAG, "Processing text input: \"$text\"")
        
        // Update UI to show processing
        handler.post {
            viewModel.updateStatus("Processing text input...")
            viewModel.updateTranscription(text)
        }
        
        // Process through the same pipeline as transcribed text
        coroutineScope.launch {
            try {
                withContext(Dispatchers.Main) {
                    // Classify the text using both classifiers
                    classifierManager?.classifyText(text)
                    
                    // Check if text is actionable before submitting to LLM
                    val isActionable = classifierManager?.isTextActionable(text) ?: true // Default to true if classifier not available
                    
                    // Check if text is contextable for memory storage
                    val isContextable = classifierManager?.isTextContextable(text) ?: false
                    
                    // Store contextable text in memory (async)
                    if (isContextable) {
                        Log.d(TAG, "Text input classified as contextable, storing in memory: \"$text\"")
                        storeInMemory(text)
                    }
                    
                    if (isActionable) {
                        Log.d(TAG, "Text input classified as actionable, submitting to LLM: \"$text\"")
                        submitToLLM(text)
                        viewModel.updateStatus("Text processed and sent to LLM")
                    } else {
                        Log.d(TAG, "Text input classified as non-actionable, skipping LLM: \"$text\"")
                        viewModel.updateStatus("Text processed (non-actionable)")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error processing text input", e)
                withContext(Dispatchers.Main) {
                    viewModel.updateStatus("Text processing error: ${e.message}")
                }
            }
        }
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
                    Log.d(TAG, "Submitting transcription to streaming enhanced LLM: \"$transcriptionText\"")
                    
                    // Start streaming response in UI
                    withContext(Dispatchers.Main) {
                        viewModel.startStreamingResponse()
                    }
                    
                    // Use streaming enhanced LLM with tool support
                    llm.generateStreamingEnhancedResponse(
                        userInput = transcriptionText,
                        includeContext = true,
                        useTools = true
                    ) { partialText, isComplete, toolResults ->
                        // Update UI with streaming response
                        viewModel.updateStreamingResponse(
                            partialResponse = partialText,
                            isComplete = isComplete,
                            hasToolCalls = toolResults.isNotEmpty()
                        )
                        
                        // When complete, use TTS and log details
                        if (isComplete) {
                            Log.d(TAG, "Streaming LLM response completed: \"$partialText\"")
                            
                            // Log tool usage if any
                            if (toolResults.isNotEmpty()) {
                                Log.d(TAG, "LLM used ${toolResults.size} tools: ${toolResults.map { it.toolName }}")
                                toolResults.forEach { toolResult ->
                                    Log.d(TAG, "Tool ${toolResult.toolName}: ${if (toolResult.success) "SUCCESS" else "FAILED"}")
                                }
                                
                                // Show tool results in logs for debugging
                                toolResults.forEach { result ->
                                    Log.d(TAG, "Tool result - ${result.toolName}: ${result.result}")
                                }
                            }
                            
                            // Use TTS to read the response aloud
                            ttsManager?.speak(partialText)
                        }
                    }
                    
                } catch (e: Exception) {
                    Log.e(TAG, "Error submitting to streaming enhanced LLM", e)
                    // Don't crash the app, just log the error
                    withContext(Dispatchers.Main) {
                        Log.w(TAG, "Streaming LLM processing failed for: \"$transcriptionText\"")
                        
                        // Complete streaming with error response
                        viewModel.updateStreamingResponse(
                            partialResponse = "I encountered an error while processing your request. Please try again.",
                            isComplete = true,
                            hasToolCalls = false
                        )
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
    
    /**
     * Store transcription in memory for RAG retrieval
     */
    private fun storeInMemory(transcription: String) {
        memoryManager?.let { manager ->
            coroutineScope.launch {
                try {
                    // Initialize memory system if not already done
                    if (!manager.getMemoryStats().isInitialized) {
                        Log.d(TAG, "Initializing memory system for storing transcription")
                        manager.initialize()
                    }
                    
                    // Store with metadata
                    val metadata = mapOf(
                        "source" to "voice_transcription",
                        "classification" to "contextable"
                    )
                    
                    val success = manager.storeMemory(transcription, metadata)
                    if (success) {
                        Log.d(TAG, "Successfully stored transcription in memory: \"${transcription.take(50)}...\"")
                    } else {
                        Log.w(TAG, "Failed to store transcription in memory")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error storing transcription in memory", e)
                }
            }
        } ?: run {
            Log.w(TAG, "Memory manager not available, cannot store transcription")
        }
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