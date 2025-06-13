package com.proactiveagentv2

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.proactiveagentv2.managers.*
import com.proactiveagentv2.ui.MainScreen
import com.proactiveagentv2.ui.MainViewModel
import com.proactiveagentv2.ui.SettingsDialog
import com.proactiveagentv2.ui.theme.WhisperNativeTheme

/**
 * Main activity for the Proactive Agent application
 * Orchestrates all managers and handles the UI lifecycle
 */
class MainActivity : ComponentActivity() {
    
    // ViewModels
    private val composeViewModel: MainViewModel by viewModels()
    
    // Manager instances
    private lateinit var appInitializer: AppInitializer
    private lateinit var audioSessionManager: AudioSessionManager
    private lateinit var transcriptionManager: TranscriptionManager
    private lateinit var settingsManager: SettingsManager
    private lateinit var uiCoordinator: UICoordinator
    
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        Log.d(TAG, "=== Starting MainActivity initialization ===")
        
        try {
            initializeManagers()
            initializeApplication()
            setupUI()
            checkPermissions()
            
            Log.d(TAG, "=== MainActivity initialization completed successfully ===")
        } catch (e: Exception) {
            Log.e(TAG, "Fatal error during MainActivity initialization", e)
            uiCoordinator.showError("Application failed to start: ${e.message}")
        }
    }
    
    private fun initializeManagers() {
        Log.d(TAG, "Initializing manager instances...")
        
        // Create manager instances
        appInitializer = AppInitializer(this, composeViewModel)
        audioSessionManager = AudioSessionManager(this, composeViewModel)
        transcriptionManager = TranscriptionManager(this, composeViewModel, lifecycleScope)
        settingsManager = SettingsManager(this, composeViewModel)
        uiCoordinator = UICoordinator(this, composeViewModel)
        
        Log.d(TAG, "Manager instances created")
    }
    
    private fun initializeApplication() {
        Log.d(TAG, "Initializing application components...")
        
        // Initialize core application
        val initResult = appInitializer.initialize()
        when (initResult) {
            is AppInitializer.InitializationResult.Success -> {
                Log.d(TAG, "Application core initialized successfully")
            }
            is AppInitializer.InitializationResult.Error -> {
                throw IllegalStateException("Application initialization failed: ${initResult.message}")
            }
        }
        
        // Initialize audio session manager
        audioSessionManager.initialize(
            recorder = appInitializer.recorder!!,
            player = appInitializer.player!!,
            vadManager = appInitializer.vadManager!!,
            dataFolder = getExternalFilesDir(null)!!
        )
        
        // Initialize transcription manager
        transcriptionManager.initialize(
            whisper = appInitializer.whisper!!,
            dataFolder = getExternalFilesDir(null)!!,
            llmManager = appInitializer.llmManager
        )
        
        // Initialize settings manager
        settingsManager.initialize(
            vadManager = appInitializer.vadManager!!,
            appInitializer = appInitializer,
            audioSessionManager = audioSessionManager
        )
        
        // Initialize UI coordinator
        uiCoordinator.initialize(
            appInitializer = appInitializer,
            audioSessionManager = audioSessionManager,
            transcriptionManager = transcriptionManager,
            settingsManager = settingsManager
        )
        
        setupManagerCallbacks()
        
        Log.d(TAG, "All managers initialized successfully")
    }
    
    private fun setupManagerCallbacks() {
        Log.d(TAG, "Setting up manager callbacks...")
        
        // Audio session callbacks
        audioSessionManager.onAudioDataReceived = { audioData ->
            appInitializer.vadManager?.processAudioChunk(audioData)
        }
        
        // Transcription callbacks
        transcriptionManager.onTranscriptionComplete = { segmentFile ->
            audioSessionManager.setLastTranscribedSegment(segmentFile)
        }
        
        // VAD callbacks
        setupVADCallbacks()
        
        Log.d(TAG, "Manager callbacks configured")
    }
    
    private fun setupVADCallbacks() {
        appInitializer.vadManager?.setOnSpeechStartListener {
            handler.post {
                composeViewModel.updateStatus("Speech detected...")
                uiCoordinator.updateVADIndicator(true)
            }
        }

        appInitializer.vadManager?.setOnSpeechEndListener { audioSamples ->
            handler.post {
                composeViewModel.updateStatus("Processing speech...")
                uiCoordinator.updateVADIndicator(false)
            }
            
            if (!transcriptionManager.isCurrentlyTranscribing()) {
                transcriptionManager.transcribeSpeechSegment(audioSamples)
            } else {
                Log.w(TAG, "Transcription in progress, skipping new speech segment")
                handler.post {
                    composeViewModel.updateStatus("Recording - Listening... (transcription skipped, engine busy)")
                }
            }
        }

        appInitializer.vadManager?.setOnVADStatusListener { isSpeech, probability ->
            handler.post {
                composeViewModel.updateVadStatus(isSpeech, probability)
                
                if (composeViewModel.appState.isRecording) {
                    val statusText = if (isSpeech) {
                        "Recording - Speaking... (${String.format("%.2f", probability)})"
                    } else {
                        "Recording - Listening... (${String.format("%.2f", probability)})"
                    }
                    composeViewModel.updateStatus(statusText)
                }
            }
        }
    }
    
    private fun setupUI() {
        Log.d(TAG, "Setting up Compose UI...")
        
        setContent {
            WhisperNativeTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MainScreen(
                        appState = composeViewModel.appState,
                        onRecordClick = { uiCoordinator.handleRecordButtonClick() },
                        onPlayClick = { uiCoordinator.handlePlayButtonClick() },
                        onClearClick = { uiCoordinator.handleClearButtonClick() },
                        onSettingsClick = { uiCoordinator.handleSettingsButtonClick() }
                    )
                    
                    // Settings Dialog
                    SettingsDialog(
                        isVisible = uiCoordinator.isSettingsDialogVisible.value,
                        currentSettings = uiCoordinator.getCurrentSettings(),
                        availableModels = uiCoordinator.getAvailableModels(),
                        llmManager = uiCoordinator.getLLMManager(),
                        onDismiss = { uiCoordinator.handleSettingsDialogDismiss() },
                        onSaveSettings = { newSettings ->
                            uiCoordinator.handleSettingsSave(newSettings)
                        }
                    )
                }
            }
        }
        
        Log.d(TAG, "Compose UI setup completed")
    }
    
    private fun checkPermissions() {
        val permission = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
        if (permission == PackageManager.PERMISSION_GRANTED) {
            Log.d(TAG, "Audio recording permission granted")
        } else {
            Log.d(TAG, "Requesting audio recording permission")
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), PERMISSION_REQUEST_CODE)
        }
    }
    
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        
        when (requestCode) {
            PERMISSION_REQUEST_CODE -> {
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    Log.d(TAG, "Audio recording permission granted")
                    uiCoordinator.showSuccess("Audio recording permission granted")
                } else {
                    Log.w(TAG, "Audio recording permission denied")
                    uiCoordinator.showError("Audio recording permission is required for the app to function")
                }
            }
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        
        Log.d(TAG, "=== Starting MainActivity cleanup ===")
        
        try {
            // Release managers in reverse initialization order
            uiCoordinator.release()
            settingsManager.release()
            transcriptionManager.release()
            audioSessionManager.release()
            appInitializer.release()
            
            Log.d(TAG, "=== MainActivity cleanup completed ===")
        } catch (e: Exception) {
            Log.e(TAG, "Error during MainActivity cleanup", e)
        }
    }





    companion object {
        private const val TAG = "MainActivity"
        private const val PERMISSION_REQUEST_CODE = 1001
    }
}