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
import com.example.proactiiveagentv1.whisper.WhisperSegment
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
    private var isTranscriptionReady = mutableStateOf(false)
    private var transcriptionText = mutableStateOf("")
    private var transcriptionSegments = mutableStateOf<List<WhisperSegment>>(emptyList())
    
    // Detection parameters
    private val vadThreshold = 0.5f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        
        initializeComponents()
        
        setContent {
            ProactiiveAgentV1Theme {
                EnhancedVoiceDetectionScreen(
                    isListening = isListening.value,
                    isSpeaking = isSpeaking.value,
                    vadConfidence = vadConfidence.floatValue,
                    isModelLoaded = isModelLoaded.value,
                    isTranscriptionReady = isTranscriptionReady.value,
                    transcriptionText = transcriptionText.value,
                    transcriptionSegments = transcriptionSegments.value,
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
            },
            onTranscriptionResult = { text, segments ->
                transcriptionText.value = text
                transcriptionSegments.value = segments
            },
            onTranscriptionError = { error ->
                // Handle transcription errors
                transcriptionText.value = "Error: $error"
            }
        )
    }
    
    private fun checkPermissions() {
        permissionManager.checkAndRequestAudioPermission()
    }
    
    private fun setupVoiceDetection() {
        lifecycleScope.launch {
            val initialized = voiceDetectionManager.initialize(enableTranscription = true)
            isModelLoaded.value = initialized && voiceDetectionManager.isModelInitialized()
            isTranscriptionReady.value = initialized && voiceDetectionManager.isTranscriptionReady()
        }
    }
    
    private fun startVoiceDetection() {
        if (!permissionManager.hasAudioPermission()) {
            permissionManager.checkAndRequestAudioPermission()
            return
        }
        
        val started = voiceDetectionManager.startDetection(enableTranscription = true)
        if (started) {
            isListening.value = true
            isSpeaking.value = false
            vadConfidence.floatValue = 0f
            transcriptionText.value = ""
            transcriptionSegments.value = emptyList()
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