package com.proactiveagentv2.managers

import android.content.Context
import android.util.Log
import android.widget.Toast
import com.proactiveagentv2.ui.MainViewModel
import com.proactiveagentv2.ui.SettingsState
import com.proactiveagentv2.vad.VADManager
import java.io.File

/**
 * Manages application settings including VAD parameters, model selection, and recording configuration
 */
class SettingsManager(
    private val context: Context,
    private val viewModel: MainViewModel
) {
    private var vadManager: VADManager? = null
    private var appInitializer: AppInitializer? = null
    private var audioSessionManager: AudioSessionManager? = null
    
    // Current settings state
    private var currentSettings = SettingsState()
    
    fun initialize(
        vadManager: VADManager,
        appInitializer: AppInitializer,
        audioSessionManager: AudioSessionManager
    ) {
        this.vadManager = vadManager
        this.appInitializer = appInitializer
        this.audioSessionManager = audioSessionManager
        
        // Load initial settings from components
        refreshCurrentSettings()
        
        Log.d(TAG, "SettingsManager initialized")
    }
    
    fun getCurrentSettings(): SettingsState {
        refreshCurrentSettings()
        return currentSettings
    }
    
    private fun refreshCurrentSettings() {
        currentSettings = SettingsState(
            speechThreshold = vadManager?.speechThreshold ?: 0.6f,
            silenceThreshold = vadManager?.silenceThreshold ?: 0.3f,
            minSpeechDurationMs = vadManager?.minSpeechDurationMs ?: 500L,
            maxSilenceDurationMs = vadManager?.maxSilenceDurationMs ?: 1000L,
            selectedModelFile = appInitializer?.selectedModelFile,
            maxRecordingDurationMinutes = getRecordingDurationFromState()
        )
    }
    
    fun applySettings(newSettings: SettingsState): SettingsApplicationResult {
        return try {
            // Apply VAD settings
            applyVADSettings(newSettings)
            
            // Apply recording duration settings
            applyRecordingDurationSettings(newSettings)
            
            // Apply model settings if changed
            val modelChanged = applyModelSettings(newSettings)
            
            // Update current settings
            currentSettings = newSettings.copy()
            
            Log.d(TAG, "Settings applied successfully: $newSettings")
            
            val changeMessage = buildSettingsChangeMessage(modelChanged)
            Toast.makeText(context, "Settings saved", Toast.LENGTH_SHORT).show()
            
            SettingsApplicationResult.Success(changeMessage)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to apply settings", e)
            Toast.makeText(context, "Failed to save settings: ${e.message}", Toast.LENGTH_LONG).show()
            SettingsApplicationResult.Error(e.message ?: "Unknown settings error")
        }
    }
    
    private fun applyVADSettings(settings: SettingsState) {
        vadManager?.apply {
            speechThreshold = settings.speechThreshold
            silenceThreshold = settings.silenceThreshold
            minSpeechDurationMs = settings.minSpeechDurationMs
            maxSilenceDurationMs = settings.maxSilenceDurationMs
        }
        
        Log.d(TAG, "VAD settings applied: threshold=${settings.speechThreshold}, silence=${settings.silenceThreshold}")
    }
    
    private fun applyRecordingDurationSettings(settings: SettingsState) {
        audioSessionManager?.updateRecordingDuration(settings.maxRecordingDurationMinutes)
        Log.d(TAG, "Recording duration setting applied: ${settings.maxRecordingDurationMinutes} minutes")
    }
    
    private fun applyModelSettings(settings: SettingsState): Boolean {
        val currentModel = appInitializer?.selectedModelFile
        val newModel = settings.selectedModelFile
        
        return if (newModel != null && newModel != currentModel) {
            Log.d(TAG, "Model change requested: ${currentModel?.name} -> ${newModel.name}")
            
            // Deinitialize current model
            appInitializer?.deinitializeSTTModel()
            
            // Initialize new model
            val success = appInitializer?.initializeSTTModel(newModel) ?: false
            
            if (success) {
                viewModel.selectModelFile(newModel)
                Log.d(TAG, "Model successfully changed to: ${newModel.name}")
                true
            } else {
                // Rollback on failure
                currentModel?.let { fallbackModel ->
                    Log.w(TAG, "Model change failed, reverting to: ${fallbackModel.name}")
                    appInitializer?.initializeSTTModel(fallbackModel)
                }
                throw IllegalStateException("Failed to initialize new STT model: ${newModel.name}")
            }
        } else {
            false
        }
    }
    
    private fun buildSettingsChangeMessage(modelChanged: Boolean): String {
        return when {
            modelChanged -> "Settings saved. STT model updated."
            else -> "Settings saved successfully."
        }
    }
    
    private fun getRecordingDurationFromState(): Int {
        // This would ideally come from a persistent settings store
        // For now, return a default value
        return 30
    }
    
    fun resetToDefaults(): SettingsState {
        val defaultSettings = SettingsState(
            speechThreshold = DEFAULT_SPEECH_THRESHOLD,
            silenceThreshold = DEFAULT_SILENCE_THRESHOLD,
            minSpeechDurationMs = DEFAULT_MIN_SPEECH_DURATION_MS,
            maxSilenceDurationMs = DEFAULT_MAX_SILENCE_DURATION_MS,
            selectedModelFile = appInitializer?.selectedModelFile,
            maxRecordingDurationMinutes = DEFAULT_RECORDING_DURATION_MINUTES
        )
        
        Log.d(TAG, "Settings reset to defaults")
        return defaultSettings
    }
    
    fun getAvailableModels(): List<File> {
        return viewModel.appState.modelFiles
    }
    
    fun validateSettings(settings: SettingsState): SettingsValidationResult {
        val errors = mutableListOf<String>()
        
        // Validate VAD thresholds
        if (settings.speechThreshold !in 0.1f..1.0f) {
            errors.add("Speech threshold must be between 0.1 and 1.0")
        }
        
        if (settings.silenceThreshold !in 0.1f..1.0f) {
            errors.add("Silence threshold must be between 0.1 and 1.0")
        }
        
        if (settings.speechThreshold <= settings.silenceThreshold) {
            errors.add("Speech threshold must be higher than silence threshold")
        }
        
        // Validate durations
        if (settings.minSpeechDurationMs < 100L || settings.minSpeechDurationMs > 5000L) {
            errors.add("Minimum speech duration must be between 100ms and 5000ms")
        }
        
        if (settings.maxSilenceDurationMs < 500L || settings.maxSilenceDurationMs > 10000L) {
            errors.add("Maximum silence duration must be between 500ms and 10000ms")
        }
        
        // Validate recording duration
        if (settings.maxRecordingDurationMinutes < 0 || settings.maxRecordingDurationMinutes > 480) {
            errors.add("Recording duration must be between 0 (never stop) and 480 minutes (8 hours)")
        }
        
        // Validate model file
        if (settings.selectedModelFile?.exists() != true) {
            errors.add("Selected model file does not exist")
        }
        
        return if (errors.isEmpty()) {
            SettingsValidationResult.Valid
        } else {
            SettingsValidationResult.Invalid(errors)
        }
    }
    
    fun release() {
        vadManager = null
        appInitializer = null
        audioSessionManager = null
        Log.d(TAG, "SettingsManager released")
    }
    
    sealed class SettingsApplicationResult {
        data class Success(val message: String) : SettingsApplicationResult()
        data class Error(val message: String) : SettingsApplicationResult()
    }
    
    sealed class SettingsValidationResult {
        object Valid : SettingsValidationResult()
        data class Invalid(val errors: List<String>) : SettingsValidationResult()
    }
    
    companion object {
        private const val TAG = "SettingsManager"
        
        // Default settings values
        const val DEFAULT_SPEECH_THRESHOLD = 0.6f
        const val DEFAULT_SILENCE_THRESHOLD = 0.3f
        const val DEFAULT_MIN_SPEECH_DURATION_MS = 500L
        const val DEFAULT_MAX_SILENCE_DURATION_MS = 1000L
        const val DEFAULT_RECORDING_DURATION_MINUTES = 30
    }
} 