package com.example.proactiiveagentv1

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.*
import androidx.lifecycle.lifecycleScope
import com.example.proactiiveagentv1.detection.EnhancedVoiceDetectionManager
import com.example.proactiiveagentv1.permissions.PermissionManager
import com.example.proactiiveagentv1.ui.EnhancedVoiceDetectionScreen
import com.example.proactiiveagentv1.ui.SettingsScreen
import com.example.proactiiveagentv1.settings.VadSettings
import kotlinx.coroutines.launch
import com.example.proactiiveagentv1.ui.theme.ProactiiveAgentV1Theme

class MainActivity : ComponentActivity() {
    private lateinit var permissionManager: PermissionManager
    private lateinit var voiceDetectionManager: EnhancedVoiceDetectionManager
    
    // UI State
    private var isListening = mutableStateOf(false)
    private var isSpeaking = mutableStateOf(false)
    private var vadConfidence = mutableFloatStateOf(0f)
    private var isModelLoaded = mutableStateOf(false)
    private var showSettings = mutableStateOf(false)
    private var currentVadSettings = mutableStateOf(VadSettings())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        
        initializeComponents()
        
        setContent {
            ProactiiveAgentV1Theme {
                if (showSettings.value) {
                    SettingsScreen(
                        currentSettings = currentVadSettings.value,
                        onSettingsChanged = { newSettings ->
                            currentVadSettings.value = newSettings
                            voiceDetectionManager.updateVadSettings(newSettings)
                        },
                        onBackPressed = { showSettings.value = false }
                    )
                } else {
                    EnhancedVoiceDetectionScreen(
                        isListening = isListening.value,
                        isSpeaking = isSpeaking.value,
                        vadConfidence = vadConfidence.floatValue,
                        isModelLoaded = isModelLoaded.value,
                        vadThreshold = currentVadSettings.value.vadThreshold,
                        onStartDetection = { startVoiceDetection() },
                        onStopDetection = { stopVoiceDetection() },
                        onOpenSettings = { showSettings.value = true }
                    )
                }
            }
        }
        
        checkPermissions()
    }
    
    private fun initializeComponents() {
        // Initialize permission manager
        permissionManager = PermissionManager(this) { hasPermission ->
            if (hasPermission) {
                setupVoiceDetection()
            }
        }
        
        // Initialize enhanced voice detection manager
        voiceDetectionManager = EnhancedVoiceDetectionManager(
            context = this,
            onVoiceActivityUpdate = { confidence ->
                vadConfidence.floatValue = confidence
            },
            onSpeechStateChange = { speaking ->
                isSpeaking.value = speaking
            },
            onSpeechStarted = { confidence ->
                onSpeechStarted(confidence)
            },
            onSpeechEnded = {
                onSpeechEnded()
            }
        )
    }
    
    private fun checkPermissions() {
        permissionManager.checkAndRequestAudioPermission()
    }
    
    private fun setupVoiceDetection() {
        lifecycleScope.launch {
            val initialized = voiceDetectionManager.initialize()
            isModelLoaded.value = initialized && voiceDetectionManager.isModelInitialized()
            
            // Load current VAD settings
            if (initialized) {
                currentVadSettings.value = voiceDetectionManager.getVadSettings()
            }
        }
    }
    
    private fun startVoiceDetection() {
        if (!permissionManager.hasAudioPermission()) {
            permissionManager.checkAndRequestAudioPermission()
            return
        }
        
        val started = voiceDetectionManager.startDetection()
        if (started) {
            isListening.value = true
            isSpeaking.value = false
            vadConfidence.floatValue = 0f
        }
    }
    
    private fun stopVoiceDetection() {
        voiceDetectionManager.stopDetection()
        isListening.value = false
        isSpeaking.value = false
        vadConfidence.floatValue = 0f
    }
    
    private fun onSpeechStarted(confidence: Float) {
        // Add your custom logic here when speech starts
        // For example: log speech events, trigger actions, etc.
    }
    
    private fun onSpeechEnded() {
        // Add your custom logic here when speech ends
        // For example: process speech session, save data, etc.
    }

    override fun onDestroy() {
        super.onDestroy()
        voiceDetectionManager.release()
    }
}