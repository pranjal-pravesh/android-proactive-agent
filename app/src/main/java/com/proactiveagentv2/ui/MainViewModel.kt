package com.proactiveagentv2.ui

import androidx.compose.runtime.*
import androidx.lifecycle.ViewModel
import java.io.File

data class VadStatus(
    val isActive: Boolean = false,
    val probability: Float = 0f
)

data class AppState(
    val status: String = "Ready to transcribe",
    val transcriptionText: String = "",
    val isRecording: Boolean = false,
    val isPlaying: Boolean = false,
    val vadStatus: VadStatus = VadStatus(),
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
        android.util.Log.d("MainViewModel", "updateTranscription called with: \"$text\"")
        android.util.Log.d("MainViewModel", "Current transcription: \"${appState.transcriptionText}\"")
        val newText = if (appState.transcriptionText.isEmpty()) {
            ">> $text"
        } else {
            ">> $text\n${appState.transcriptionText}"
        }
        android.util.Log.d("MainViewModel", "New transcription will be: \"$newText\"")
        appState = appState.copy(transcriptionText = newText)
        android.util.Log.d("MainViewModel", "After update, transcription is: \"${appState.transcriptionText}\"")
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
        android.util.Log.d("MainViewModel", "Updating VAD status: active=$isActive, prob=$probability")
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
} 