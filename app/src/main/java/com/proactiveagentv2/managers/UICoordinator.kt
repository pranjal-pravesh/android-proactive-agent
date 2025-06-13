package com.proactiveagentv2.managers

import android.content.Context
import android.util.Log
import android.widget.Toast
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import com.proactiveagentv2.llm.LLMManager
import com.proactiveagentv2.ui.MainViewModel
import com.proactiveagentv2.ui.SettingsState

/**
 * Coordinates UI interactions and state management between different managers and the UI layer
 */
class UICoordinator(
    private val context: Context,
    private val viewModel: MainViewModel
) {
    private var appInitializer: AppInitializer? = null
    private var audioSessionManager: AudioSessionManager? = null
    private var transcriptionManager: TranscriptionManager? = null
    private var settingsManager: SettingsManager? = null
    
    // UI state
    val isSettingsDialogVisible: MutableState<Boolean> = mutableStateOf(false)
    
    fun initialize(
        appInitializer: AppInitializer,
        audioSessionManager: AudioSessionManager,
        transcriptionManager: TranscriptionManager,
        settingsManager: SettingsManager
    ) {
        this.appInitializer = appInitializer
        this.audioSessionManager = audioSessionManager
        this.transcriptionManager = transcriptionManager
        this.settingsManager = settingsManager
        
        Log.d(TAG, "UICoordinator initialized")
    }
    
    fun handleRecordButtonClick() {
        try {
            val isCurrentlyRecording = audioSessionManager?.isRecordingActive() ?: false
            
            if (isCurrentlyRecording) {
                Log.d(TAG, "Stopping recording session")
                audioSessionManager?.stopRecordingSession()
            } else {
                Log.d(TAG, "Starting recording session")
                audioSessionManager?.startRecordingSession()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling record button click", e)
            showError("Recording error: ${e.message}")
        }
    }
    
    fun handlePlayButtonClick() {
        try {
            Log.d(TAG, "Play button clicked")
            audioSessionManager?.playLastSegment()
        } catch (e: Exception) {
            Log.e(TAG, "Error handling play button click", e)
            showError("Playback error: ${e.message}")
        }
    }
    
    fun handleClearButtonClick() {
        try {
            Log.d(TAG, "Clear button clicked")
            viewModel.clearTranscription()
            Toast.makeText(context, "Transcription cleared", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Log.e(TAG, "Error handling clear button click", e)
            showError("Clear error: ${e.message}")
        }
    }
    
    fun handleSettingsButtonClick() {
        Log.d(TAG, "Settings button clicked")
        isSettingsDialogVisible.value = true
    }
    
    fun handleSettingsDialogDismiss() {
        Log.d(TAG, "Settings dialog dismissed")
        isSettingsDialogVisible.value = false
    }
    
    fun handleSettingsSave(newSettings: SettingsState) {
        try {
            Log.d(TAG, "Applying new settings: $newSettings")
            
            val result = settingsManager?.applySettings(newSettings)
            
            when (result) {
                is SettingsManager.SettingsApplicationResult.Success -> {
                    Log.d(TAG, "Settings applied successfully: ${result.message}")
                    isSettingsDialogVisible.value = false
                }
                is SettingsManager.SettingsApplicationResult.Error -> {
                    Log.e(TAG, "Settings application failed: ${result.message}")
                    showError("Settings error: ${result.message}")
                }
                null -> {
                    Log.e(TAG, "SettingsManager not initialized")
                    showError("Settings manager not available")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error saving settings", e)
            showError("Failed to save settings: ${e.message}")
        }
    }
    
    fun getCurrentSettings(): SettingsState {
        return settingsManager?.getCurrentSettings() ?: SettingsState()
    }
    
    fun getAvailableModels() = settingsManager?.getAvailableModels() ?: emptyList()
    
    fun getLLMManager(): LLMManager? = appInitializer?.llmManager
    
    fun showError(message: String) {
        Log.w(TAG, "Showing error to user: $message")
        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        viewModel.updateStatus("Error: $message")
    }
    
    fun showSuccess(message: String) {
        Log.d(TAG, "Showing success to user: $message")
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        viewModel.updateStatus(message)
    }
    
    fun showInfo(message: String) {
        Log.d(TAG, "Showing info to user: $message")
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        viewModel.updateStatus(message)
    }
    
    fun updateVADIndicator(isSpeechDetected: Boolean) {
        // Update VAD status in viewModel - this could be expanded to show visual indicators
        val currentStatus = if (isSpeechDetected) "Speech detected" else "Listening"
        viewModel.updateStatus(currentStatus)
    }
    
    fun updateTranscriptionProgress(isTranscribing: Boolean) {
        if (isTranscribing) {
            viewModel.updateStatus("Transcribing speech...")
        }
    }
    
    fun isRecordingActive(): Boolean {
        return audioSessionManager?.isRecordingActive() ?: false
    }
    
    fun isTranscribingActive(): Boolean {
        return transcriptionManager?.isCurrentlyTranscribing() ?: false
    }
    
    fun getApplicationState(): ApplicationState {
        return ApplicationState(
            isRecording = isRecordingActive(),
            isTranscribing = isTranscribingActive(),
            isPlaying = viewModel.appState.isPlaying,
            selectedModel = appInitializer?.selectedModelFile,
            vadAvailable = appInitializer?.vadManager != null,
            llmAvailable = appInitializer?.llmManager != null
        )
    }
    
    fun release() {
        isSettingsDialogVisible.value = false
        
        appInitializer = null
        audioSessionManager = null
        transcriptionManager = null
        settingsManager = null
        
        Log.d(TAG, "UICoordinator released")
    }
    
    data class ApplicationState(
        val isRecording: Boolean,
        val isTranscribing: Boolean,
        val isPlaying: Boolean,
        val selectedModel: java.io.File?,
        val vadAvailable: Boolean,
        val llmAvailable: Boolean
    )
    
    companion object {
        private const val TAG = "UICoordinator"
    }
} 