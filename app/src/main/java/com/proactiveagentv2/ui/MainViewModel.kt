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
    val modelFiles: List<File> = emptyList()
)

class MainViewModel : ViewModel() {
    var appState by mutableStateOf(AppState())
        private set

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

    fun appendLLMResponse(response: String, durationMs: Long) {
        val formatted = if (response.isNotBlank()) "LLM >> $response" else "LLM >> (no response)"
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
} 