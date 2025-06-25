package com.proactiveagentv2.ui

import androidx.compose.runtime.*
import androidx.lifecycle.ViewModel
import java.io.File

data class VadStatus(
    val isActive: Boolean = false,
    val probability: Float = 0f
)

data class ClassificationStatus(
    val isActionable: Boolean = false,
    val actionableConfidence: Float = 0f,
    val isContextable: Boolean = false,
    val contextableConfidence: Float = 0f,
    val processingTimeMs: Long = 0L,
    val lastClassifiedText: String = ""
)

data class AppState(
    val status: String = "Ready to transcribe",
    val transcriptionText: String = "",
    val isRecording: Boolean = false,
    val isPlaying: Boolean = false,
    val vadStatus: VadStatus = VadStatus(),
    val classificationStatus: ClassificationStatus = ClassificationStatus(),
    val selectedModelFile: File? = null,
    val modelFiles: List<File> = emptyList(),
    val isStreamingResponse: Boolean = false,
    val streamingResponseText: String = "",
    val streamingHasToolCalls: Boolean = false,
    val showConversationHistoryDialog: Boolean = false
)

data class StreamingMetrics(
    val timeToFirstToken: Long = 0L, // milliseconds
    val tokensPerSecond: Float = 0f,
    val totalTokens: Int = 0,
    val totalTimeMs: Long = 0L,
    val isComplete: Boolean = false
)

class MainViewModel : ViewModel() {
    var appState by mutableStateOf(AppState())
        private set

    // Track streaming state
    private var streamingStartTime: Long = 0L
    private var firstTokenTime: Long = 0L
    private var currentStreamingResponse: String = ""
    private var lastResponseMetrics: StreamingMetrics? = null

    fun updateStatus(status: String) {
        android.util.Log.d("MainViewModel", "Updating status to: $status")
        appState = appState.copy(status = status)
    }

    fun updateTranscription(text: String) {
        android.util.Log.d("MainViewModel", "New transcription: \"$text\"")
        val newText = if (appState.transcriptionText.isEmpty()) {
            ">> $text"
        } else {
            ">> $text\n${appState.transcriptionText}"
        }
        appState = appState.copy(transcriptionText = newText)
    }

    fun clearTranscription() {
        android.util.Log.d("MainViewModel", "Clearing transcription")
        appState = appState.copy(transcriptionText = "")
    }

    fun updateRecordingState(isRecording: Boolean) {
        android.util.Log.d("MainViewModel", "Updating recording state to: $isRecording")
        appState = appState.copy(isRecording = isRecording)
    }

    fun updatePlayingState(isPlaying: Boolean) {
        android.util.Log.d("MainViewModel", "Updating playing state to: $isPlaying")
        appState = appState.copy(isPlaying = isPlaying)
    }

    fun updateVadStatus(isActive: Boolean, probability: Float) {
        // Only log significant VAD changes to reduce log spam
        if (isActive != appState.vadStatus.isActive) {
            android.util.Log.d("MainViewModel", "VAD status changed: active=$isActive, prob=$probability")
        }
        appState = appState.copy(vadStatus = VadStatus(isActive, probability))
    }

    fun updateModelFiles(files: List<File>) {
        android.util.Log.d("MainViewModel", "Updating model files: ${files.map { it.name }}")
        appState = appState.copy(modelFiles = files)
    }

    fun selectModelFile(file: File) {
        android.util.Log.d("MainViewModel", "Selecting model file: ${file.name}")
        appState = appState.copy(selectedModelFile = file)
    }

    fun appendLLMResponse(response: String, durationMs: Long, hasToolCalls: Boolean = false) {
        val formatted = if (response.isNotBlank()) {
            if (hasToolCalls) "LLM_TOOL >> $response" else "LLM >> $response"
        } else {
            "LLM >> (no response)"
        }
        val newText = if (appState.transcriptionText.isEmpty()) {
            formatted
        } else {
            "$formatted\n${appState.transcriptionText}"
        }
        appState = appState.copy(transcriptionText = newText, status = "LLM responded in ${durationMs}ms")
    }
    
    fun updateClassificationResults(results: com.proactiveagentv2.managers.ClassifierManager.ClassificationResults) {
        android.util.Log.d("MainViewModel", "Updating classification results - Actionable: ${results.isActionable}, Contextable: ${results.isContextable}")
        
        val classificationStatus = ClassificationStatus(
            isActionable = results.isActionable,
            actionableConfidence = results.actionableResult?.confidence ?: 0f,
            isContextable = results.isContextable,
            contextableConfidence = results.contextableResult?.confidence ?: 0f,
            processingTimeMs = results.totalProcessingTimeMs,
            lastClassifiedText = results.actionableResult?.let { "Last classified" } ?: ""
        )
        
        appState = appState.copy(classificationStatus = classificationStatus)
    }

    /**
     * Start a new streaming response
     */
    fun startStreamingResponse() {
        android.util.Log.d("MainViewModel", "Starting streaming response")
        streamingStartTime = System.currentTimeMillis()
        firstTokenTime = 0L
        currentStreamingResponse = ""
        lastResponseMetrics = null
        appState = appState.copy(
            isStreamingResponse = true,
            streamingResponseText = "",
            streamingHasToolCalls = false
        )
    }
    
    /**
     * Update streaming response with partial text
     */
    fun updateStreamingResponse(partialResponse: String, isComplete: Boolean, hasToolCalls: Boolean = false) {
        android.util.Log.d("MainViewModel", "Updating streaming response: isComplete=$isComplete, length=${partialResponse.length}")
        
        // Record time to first token
        if (firstTokenTime == 0L && partialResponse.isNotEmpty()) {
            firstTokenTime = System.currentTimeMillis()
            android.util.Log.d("MainViewModel", "First token received at ${firstTokenTime - streamingStartTime}ms")
        }
        
        currentStreamingResponse = partialResponse
        
        if (isComplete) {
            // Calculate final metrics
            val totalTime = System.currentTimeMillis() - streamingStartTime
            val timeToFirstToken = if (firstTokenTime > 0L) firstTokenTime - streamingStartTime else totalTime
            val totalTokens = estimateTokenCount(partialResponse)
            val tokensPerSecond = if (totalTime > 0) (totalTokens * 1000f) / totalTime else 0f
            
            lastResponseMetrics = StreamingMetrics(
                timeToFirstToken = timeToFirstToken,
                tokensPerSecond = tokensPerSecond,
                totalTokens = totalTokens,
                totalTimeMs = totalTime,
                isComplete = true
            )
            
            android.util.Log.d("MainViewModel", "Streaming complete - TTFT: ${timeToFirstToken}ms, TPS: ${"%.2f".format(tokensPerSecond)}, Tokens: $totalTokens")
            
            // Add final response to conversation
            val formatted = if (partialResponse.isNotBlank()) {
                if (hasToolCalls) "LLM_TOOL >> $partialResponse" else "LLM >> $partialResponse"
            } else {
                "LLM >> (no response)"
            }
            val newText = if (appState.transcriptionText.isEmpty()) {
                formatted
            } else {
                "$formatted\n${appState.transcriptionText}"
            }
            
            appState = appState.copy(
                transcriptionText = newText,
                isStreamingResponse = false,
                streamingResponseText = "",
                streamingHasToolCalls = false,
                status = "LLM responded in ${totalTime}ms"
            )
        } else {
            // Update streaming state without modifying transcription text
            appState = appState.copy(
                isStreamingResponse = true,
                streamingResponseText = partialResponse,
                streamingHasToolCalls = hasToolCalls,
                status = "LLM is responding..."
            )
        }
    }
    
    /**
     * Get the current streaming metrics
     */
    fun getCurrentStreamingMetrics(): StreamingMetrics? {
        return if (appState.isStreamingResponse) {
            val currentTime = System.currentTimeMillis()
            val timeToFirstToken = if (firstTokenTime > 0L) firstTokenTime - streamingStartTime else 0L
            val totalTime = currentTime - streamingStartTime
            val totalTokens = estimateTokenCount(currentStreamingResponse)
            val tokensPerSecond = if (totalTime > 0 && totalTokens > 0) (totalTokens * 1000f) / totalTime else 0f
            
            StreamingMetrics(
                timeToFirstToken = timeToFirstToken,
                tokensPerSecond = tokensPerSecond,
                totalTokens = totalTokens,
                totalTimeMs = totalTime,
                isComplete = false
            )
        } else {
            lastResponseMetrics
        }
    }
    
    /**
     * Estimate token count (rough approximation)
     */
    private fun estimateTokenCount(text: String): Int {
        // Rough estimate: 1 token ≈ 4 characters for English text
        return maxOf(1, text.length / 4)
    }
    
    /**
     * Show conversation history dialog
     */
    fun showConversationHistoryDialog() {
        android.util.Log.d("MainViewModel", "Showing conversation history dialog")
        appState = appState.copy(showConversationHistoryDialog = true)
    }
    
    /**
     * Hide conversation history dialog
     */
    fun hideConversationHistoryDialog() {
        android.util.Log.d("MainViewModel", "Hiding conversation history dialog")
        appState = appState.copy(showConversationHistoryDialog = false)
    }
} 