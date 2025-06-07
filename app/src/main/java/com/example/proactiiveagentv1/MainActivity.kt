package com.example.proactiiveagentv1

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.*
import com.example.proactiiveagentv1.detection.VoiceDetectionManager
import com.example.proactiiveagentv1.permissions.PermissionManager
import com.example.proactiiveagentv1.ui.VoiceDetectionScreen
import com.example.proactiiveagentv1.ui.theme.ProactiiveAgentV1Theme

class MainActivity : ComponentActivity() {
    private lateinit var permissionManager: PermissionManager
    private lateinit var voiceDetectionManager: VoiceDetectionManager
    
    // UI State
    private var isListening = mutableStateOf(false)
    private var isSpeaking = mutableStateOf(false)
    private var vadConfidence = mutableFloatStateOf(0f)
    private var isModelLoaded = mutableStateOf(false)
    
    // Detection parameters
    private val vadThreshold = 0.5f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        
        initializeComponents()
        
        setContent {
            ProactiiveAgentV1Theme {
                VoiceDetectionScreen(
                    isListening = isListening.value,
                    isSpeaking = isSpeaking.value,
                    vadConfidence = vadConfidence.floatValue,
                    isModelLoaded = isModelLoaded.value,
                    vadThreshold = vadThreshold,
                    onStartDetection = { startVoiceDetection() },
                    onStopDetection = { stopVoiceDetection() }
                )
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
        
        // Initialize voice detection manager
        voiceDetectionManager = VoiceDetectionManager(
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
        val initialized = voiceDetectionManager.initialize()
        isModelLoaded.value = initialized && voiceDetectionManager.isModelInitialized()
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