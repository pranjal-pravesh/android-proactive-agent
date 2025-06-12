package com.proactiveagentv2

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import com.proactiveagentv2.asr.Player
import com.proactiveagentv2.asr.Player.PlaybackListener
import com.proactiveagentv2.asr.Recorder
import com.proactiveagentv2.asr.Recorder.RecorderListener
import com.proactiveagentv2.asr.Whisper
import com.proactiveagentv2.asr.Whisper.WhisperListener
import com.proactiveagentv2.ui.MainScreen
import com.proactiveagentv2.ui.MainViewModel
import com.proactiveagentv2.ui.SettingsDialog
import com.proactiveagentv2.ui.SettingsState
import com.proactiveagentv2.ui.theme.WhisperNativeTheme
import com.proactiveagentv2.utils.WaveUtil
import com.proactiveagentv2.vad.VADManager
import com.proactiveagentv2.llm.LLMManager
import java.io.File
import java.io.FileOutputStream
import java.io.IOException


class MainActivity : ComponentActivity(), RecorderListener {
    private val composeViewModel: MainViewModel by viewModels()

    private var mPlayer: Player? = null
    private var mRecorder: Recorder? = null
    private var mWhisper: Whisper? = null
    private var mVADManager: VADManager? = null
    private var mLLMManager: LLMManager? = null

    private var sdcardDataFolder: File? = null
    private var selectedTfliteFile: File? = null

    private var startTime: Long = 0
    private val loopTesting = false
    private val transcriptionSync = SharedResource()
    private val handler = Handler(Looper.getMainLooper())

    private var isRecording = false
    private var isTranscribing = false
    
    // For debugging and playback of last transcribed segment
    private var lastTranscribedSegmentFile: File? = null
    


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        initializeComposeUI()
    }
    
    private fun initializeComposeUI() {
        setContent {
            val isSettingsDialogVisible = remember { mutableStateOf(false) }
            
            WhisperNativeTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MainScreen(
                        appState = composeViewModel.appState,
                        onRecordClick = { handleRecordClick() },
                        onPlayClick = { handlePlayClick() },
                        onClearClick = { 
                            composeViewModel.clearTranscription()
                            Toast.makeText(this@MainActivity, "Transcription cleared", Toast.LENGTH_SHORT).show()
                        },
                        onSettingsClick = { isSettingsDialogVisible.value = true }
                    )
                    
                    // Settings Dialog
                    val vadMgr = mVADManager
                    if (vadMgr != null) {
                        SettingsDialog(
                            isVisible = isSettingsDialogVisible.value,
                            currentSettings = SettingsState(
                                speechThreshold = vadMgr.speechThreshold,
                                silenceThreshold = vadMgr.silenceThreshold,
                                minSpeechDurationMs = vadMgr.minSpeechDurationMs,
                                maxSilenceDurationMs = vadMgr.maxSilenceDurationMs,
                                selectedModelFile = selectedTfliteFile
                            ),
                            availableModels = composeViewModel.appState.modelFiles,
                            llmManager = mLLMManager,
                            onDismiss = { isSettingsDialogVisible.value = false },
                            onSaveSettings = { newSettings ->
                                // Update VAD settings
                                vadMgr.speechThreshold = newSettings.speechThreshold
                                vadMgr.silenceThreshold = newSettings.silenceThreshold
                                vadMgr.minSpeechDurationMs = newSettings.minSpeechDurationMs
                                vadMgr.maxSilenceDurationMs = newSettings.maxSilenceDurationMs
                                
                                // Update model if changed
                                if (newSettings.selectedModelFile != selectedTfliteFile) {
                                    newSettings.selectedModelFile?.let { file ->
                                        Log.d(TAG, "Model changed to: ${file.name}")
                                        deinitModel()
                                        selectedTfliteFile = file
                                        composeViewModel.selectModelFile(file)
                                        initModel(file)
                                    }
                                }
                                
                                Toast.makeText(this@MainActivity, "Settings saved", Toast.LENGTH_SHORT).show()
                                Log.d(TAG, "Settings updated: $newSettings")
                            }
                        )
                    }
                }
            }
        }
        
        // Initialize backend
        initializeBackend()
    }
    
    private fun initializeBackend() {
        Log.d(TAG, "=== Initializing backend ===")
        
        sdcardDataFolder = this.getExternalFilesDir(null)
        copyAssetsToSdcard(this, sdcardDataFolder, EXTENSIONS_TO_COPY)

        val tfliteFiles = getFilesWithExtension(sdcardDataFolder, ".tflite")

        Log.d(TAG, "Found ${tfliteFiles.size} model files: ${tfliteFiles.map { it.name }}")

        // Update ViewModel
        composeViewModel.updateModelFiles(tfliteFiles)
        
        // Set default model and initialize it
        selectedTfliteFile = File(sdcardDataFolder, DEFAULT_MODEL_TO_USE)
        Log.d(TAG, "Default model: ${selectedTfliteFile?.absolutePath}")
        Log.d(TAG, "Default model exists: ${selectedTfliteFile?.exists()}")
        
        if (selectedTfliteFile?.exists() == true) {
            composeViewModel.selectModelFile(selectedTfliteFile!!)
            Log.d(TAG, "Initializing default model...")
            initModel(selectedTfliteFile!!)
            Log.d(TAG, "Model initialization completed")
        } else {
            Log.w(TAG, "Default model not found: $DEFAULT_MODEL_TO_USE")
        }

        // Initialize audio components
        mRecorder = Recorder(this)
        mRecorder?.setListener(this)

        mPlayer = Player(this)
        mPlayer?.setListener(object : PlaybackListener {
            override fun onPlaybackStarted() {
                composeViewModel.updatePlayingState(true)
            }

            override fun onPlaybackStopped() {
                composeViewModel.updatePlayingState(false)
            }
        })

        // Initialize VAD Manager
        mVADManager = VADManager(this)
        val vadInitialized = mVADManager?.initialize() ?: false
        Log.d(TAG, "VAD Manager initialization: $vadInitialized")
        
        if (!vadInitialized) {
            Log.e(TAG, "Failed to initialize VAD Manager")
            showError("Failed to initialize Voice Activity Detection")
            return
        }

        setupVADCallbacks()
        
        // Initialize LLM Manager
        mLLMManager = LLMManager(this)
        Log.d(TAG, "LLM Manager initialized")
        
        checkRecordPermission()
        
        Log.d(TAG, "=== Backend initialization completed ===")
        Log.d(TAG, "Whisper engine status: ${if (mWhisper != null) "✅ READY" else "❌ NOT INITIALIZED"}")
        Log.d(TAG, "LLM Manager status: ${if (mLLMManager != null) "✅ READY" else "❌ NOT INITIALIZED"}")
    }
    
    private fun setupVADCallbacks() {
        mVADManager?.setOnSpeechStartListener {
            handler.post {
                composeViewModel.updateStatus("Speech detected...")
            }
        }

        mVADManager?.setOnSpeechEndListener { audioSamples ->
            handler.post {
                composeViewModel.updateStatus("Processing speech...")
            }
            
            if (!isTranscribing) {
                transcribeSpeechSegment(audioSamples)
            } else {
                Log.w(TAG, "Transcription in progress, skipping new speech segment")
                handler.post {
                    composeViewModel.updateStatus("Recording - Listening... (transcription skipped, engine busy)")
                }
            }
        }



        mVADManager?.setOnVADStatusListener { isSpeech, probability ->
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

    override fun onDestroy() {
        super.onDestroy()
        
        // Release VAD resources
        mVADManager?.release()
        mVADManager = null
        
        // Release LLM resources
        mLLMManager?.release()
        mLLMManager = null
        
        // Clean up last transcribed segment file
        lastTranscribedSegmentFile?.let { file ->
            try {
                if (file.exists()) {
                    file.delete()
                    Log.d(TAG, "Cleaned up last transcribed segment file")
                } else {
                    Log.d(TAG, "Last transcribed segment file does not exist")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to clean up last transcribed segment file", e)
            }
        }
        
        // Clean up other resources
        mPlayer = null
        mRecorder = null
        deinitModel()
        
        Log.d(TAG, "MainActivity destroyed, resources released")
    }

    // Model initialization
    private fun initModel(modelFile: File) {
        val isMultilingualModel = !(modelFile.name.endsWith(ENGLISH_ONLY_MODEL_EXTENSION))
        val vocabFileName =
            if (isMultilingualModel) MULTILINGUAL_VOCAB_FILE else ENGLISH_ONLY_VOCAB_FILE
        val vocabFile = File(sdcardDataFolder, vocabFileName)

        mWhisper = Whisper(this)
        mWhisper!!.loadModel(modelFile, vocabFile, isMultilingualModel)
        mWhisper?.setListener(object : WhisperListener {
            override fun onUpdateReceived(message: String?) {
                Log.d(TAG, "Update is received, Message: $message")

                if (message == Whisper.MSG_PROCESSING) {
                    handler.post { 
                        composeViewModel.updateStatus("Recording - Transcribing previous speech...")
                    }
                    startTime = System.currentTimeMillis()
                }
                if (message == Whisper.MSG_PROCESSING_DONE) {
                    handler.post {
                        composeViewModel.updateStatus("Recording - Listening...")
                    }
                    // for testing
                    if (loopTesting) transcriptionSync.sendSignal()
                } else if (message == Whisper.MSG_FILE_NOT_FOUND) {
                    handler.post { 
                        composeViewModel.updateStatus(message ?: "File not found")
                    }
                    Log.d(TAG, "File not found error...!")
                }
            }

            override fun onResultReceived(result: String?) {
                val timeTaken = System.currentTimeMillis() - startTime
                
                Log.d(TAG, "Result: $result")
                handler.post { 
                    if (!result.isNullOrBlank()) {
                        Log.d(TAG, "Updating UI with transcription: \"$result\"")
                        Log.d(TAG, "Before update - transcription: \"${composeViewModel.appState.transcriptionText}\"")
                        composeViewModel.updateTranscription(result)
                        Log.d(TAG, "After update - transcription: \"${composeViewModel.appState.transcriptionText}\"")
                        Log.d(TAG, "AppState object reference: ${composeViewModel.appState}")
                        
                        // Show completion status with result preview
                        if (composeViewModel.appState.isRecording) {
                            composeViewModel.updateStatus("Recording - Listening... (last: \"$result\")")
                        } else {
                            composeViewModel.updateStatus("Processing done in ${timeTaken}ms: \"$result\"")
                        }
                        
                        Log.i(TAG, "Successfully transcribed: \"$result\"")
                    } else {
                        Log.w(TAG, "Empty transcription result")
                        if (composeViewModel.appState.isRecording) {
                            composeViewModel.updateStatus("Recording - Listening... (no speech detected)")
                        } else {
                            composeViewModel.updateStatus("Processing done - no speech detected")
                        }
                    }
                }
            }
        })
    }

    private fun deinitModel() {
        if (mWhisper != null) {
            mWhisper!!.unloadModel()
            mWhisper = null
        }
    }

    private fun checkRecordPermission() {
        val permission = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
        if (permission == PackageManager.PERMISSION_GRANTED) {
            Log.d(TAG, "Record permission is granted")
        } else {
            Log.d(TAG, "Requesting record permission")
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 0)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (grantResults.size > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            Log.d(TAG, "Record permission is granted")
        } else {
            Log.d(TAG, "Record permission is not granted")
        }
    }

    private fun handleRecordClick() {
        if (mRecorder != null && mRecorder!!.isInProgress) {
            Log.d(TAG, "Recording is in progress... stopping...")
            stopRecording()
        } else {
            Log.d(TAG, "Start recording...")
            startRecording()
        }
    }

    private fun handlePlayClick() {
        if (mPlayer?.isPlaying == false) {
            val fileToPlay = when {
                lastTranscribedSegmentFile?.exists() == true -> {
                    Log.d(TAG, "Playing last transcribed segment: ${lastTranscribedSegmentFile?.name}")
                    Toast.makeText(this, "Playing last transcribed segment", Toast.LENGTH_SHORT).show()
                    lastTranscribedSegmentFile!!
                }
                File(sdcardDataFolder, WaveUtil.RECORDING_FILE).exists() -> {
                    val recordingFile = File(sdcardDataFolder, WaveUtil.RECORDING_FILE)
                    Log.d(TAG, "Playing recording file: ${recordingFile.name}")
                    Toast.makeText(this, "Playing last recording", Toast.LENGTH_SHORT).show()
                    recordingFile
                }
                else -> {
                    Toast.makeText(this, "No audio recording available to play", Toast.LENGTH_SHORT).show()
                    return
                }
            }
            
            mPlayer?.initializePlayer(fileToPlay.absolutePath)
            mPlayer?.startPlayback()
        } else {
            mPlayer?.stopPlayback()
        }
    }

    // Recording calls
    private fun startRecording() {
        checkRecordPermission()

        val waveFile = File(sdcardDataFolder, WaveUtil.RECORDING_FILE)
        mRecorder!!.setFilePath(waveFile.absolutePath)
        
        // Start VAD processing
        mVADManager?.startVAD()
        
        mRecorder!!.start()
        
        Log.d(TAG, "Recording started with VAD processing")
    }

    private fun stopRecording() {
        // Stop VAD processing
        mVADManager?.stopVAD()
        
        mRecorder!!.stop()
        
        Log.d(TAG, "Recording stopped, VAD processing ended")
    }

    // Transcription calls
    private fun startTranscription(waveFilePath: String) {
        mWhisper!!.setFilePath(waveFilePath)
        mWhisper!!.setAction(Whisper.ACTION_TRANSCRIBE)
        mWhisper!!.start()
    }
    
    private fun transcribeSpeechSegment(audioSamples: FloatArray) {
        Thread {
            try {
                Log.d(TAG, "Starting transcription of speech segment with ${audioSamples.size} samples (${audioSamples.size / 16000.0f} seconds)")
                
                if (audioSamples.isEmpty()) {
                    Log.w(TAG, "Received empty audio samples for transcription")
                    handler.post {
                        composeViewModel.updateStatus("Recording - Listening... (empty segment skipped)")
                    }
                    return@Thread
                }
                
                // STEP 1 & 2: Audio Quality Checks
                val maxSample = audioSamples.maxOrNull() ?: 0f
                val minSample = audioSamples.minOrNull() ?: 0f
                val rms = kotlin.math.sqrt(audioSamples.fold(0f) { acc, s -> acc + s * s } / audioSamples.size)
                val rmsDb = 20 * kotlin.math.log10(rms + 1e-10) // Add small value to avoid log(0)
                val peakDb = 20 * kotlin.math.log10(kotlin.math.max(kotlin.math.abs(maxSample), kotlin.math.abs(minSample)) + 1e-10)
                
                Log.d(TAG, "=== AUDIO QUALITY CHECK ===")
                Log.d(TAG, "Peak sample range: $minSample to $maxSample")
                Log.d(TAG, "RMS: $rms (${String.format("%.1f", rmsDb)} dB)")
                Log.d(TAG, "Peak: ${String.format("%.1f", peakDb)} dB")
                Log.d(TAG, "Expected: samples -1.0 to 1.0, RMS > -30dB, Peak > -6dB")
                
                // STEP 4: Guard against silence segments
                if (rms < 0.015f) { // ≈-42 dB threshold
                    Log.w(TAG, "❌ DISCARDED: RMS ${String.format("%.4f", rms)} (${String.format("%.1f", rmsDb)} dB) too low - likely silence")
                    handler.post {
                        composeViewModel.updateStatus("Recording - Listening... (silence segment skipped)")
                    }
                    return@Thread
                }
                
                // Check for scaling issues
                if (maxSample > 1.0f || minSample < -1.0f) {
                    Log.w(TAG, "⚠️ WARNING: Audio samples outside expected -1.0 to 1.0 range!")
                }
                
                // Set transcription flag
                isTranscribing = true
                
                // STEP 3: Save segment for debugging (like Python's debug file saving)
                val timestamp = System.currentTimeMillis()
                val segmentFileName = "segment_${timestamp}.wav"
                val segmentWaveFile = File(sdcardDataFolder, segmentFileName)
                lastTranscribedSegmentFile = segmentWaveFile // Update reference for playback
                
                // Update status during processing
                handler.post {
                    composeViewModel.updateStatus("Recording - Transcribing speech segment...")
                }
                
                Log.d(TAG, "💾 Saving audio segment: ${segmentWaveFile.absolutePath}")
                val audioBytes = floatArrayToByteArray(audioSamples)
                WaveUtil.createWaveFile(segmentWaveFile.absolutePath, audioBytes, 16000, 1, 2)
                
                if (!segmentWaveFile.exists()) {
                    Log.e(TAG, "❌ Failed to save audio segment file")
                    handler.post {
                        composeViewModel.updateStatus("Recording - Listening... (transcription failed)")
                    }
                    return@Thread
                }
                
                // AUDIO PREPROCESSING (like Python's preprocessing)
                val trimmedSamples = trimTrailingSilence(audioSamples)
                val paddedSamples = padToWindow(trimmedSamples)
                
                Log.d(TAG, "🔧 Audio preprocessing completed:")
                Log.d(TAG, "   Original: ${audioSamples.size} samples (${audioSamples.size / 16000.0f}s)")
                Log.d(TAG, "   Trimmed:  ${trimmedSamples.size} samples (${trimmedSamples.size / 16000.0f}s)")
                Log.d(TAG, "   Padded:   ${paddedSamples.size} samples (${paddedSamples.size / 16000.0f}s)")
                
                // TRY BOTH TRANSCRIPTION METHODS TO DEBUG THE ISSUE
                
                // METHOD 1: Direct array transcription (faster but might have issues)
                Log.d(TAG, "🚀 Trying direct array transcription...")
                val startTime = System.currentTimeMillis()
                val directResult = mWhisper?.transcribeFromArray(paddedSamples, "en") ?: ""
                val directTimeTaken = System.currentTimeMillis() - startTime
                
                Log.d(TAG, "📋 Direct transcription result (${directTimeTaken}ms): \"$directResult\"")
                
                // If direct transcription fails or returns empty, try file-based transcription
                if (directResult.isBlank() && segmentWaveFile.exists()) {
                    Log.d(TAG, "⚠️ Direct transcription empty, trying file-based transcription...")
                    
                    handler.post {
                        // Use file-based transcription as fallback
                        mWhisper?.setFilePath(segmentWaveFile.absolutePath)
                        mWhisper?.setAction(Whisper.ACTION_TRANSCRIBE)
                        mWhisper?.start()
                        
                        composeViewModel.updateStatus("Recording - Transcribing (file-based)...")
                    }
                    return@Thread // Let the file-based transcription handle the result
                }
                
                // Update UI with direct transcription result
                handler.post {
                    if (!directResult.isBlank()) {
                        Log.d(TAG, "Updating UI with direct transcription: \"$directResult\"")
                        Log.d(TAG, "Before update - transcription: \"${composeViewModel.appState.transcriptionText}\"")
                        composeViewModel.updateTranscription(directResult)
                        Log.d(TAG, "After update - transcription: \"${composeViewModel.appState.transcriptionText}\"")
                        
                        if (composeViewModel.appState.isRecording) {
                            composeViewModel.updateStatus("Recording - Listening... (${directTimeTaken}ms: \"$directResult\")")
                        } else {
                            composeViewModel.updateStatus("Processing done in ${directTimeTaken}ms: \"$directResult\"")
                        }
                        
                        // Feed prompt to LLM
                        submitPromptToLLM(directResult)
                        
                        Log.i(TAG, "✅ Direct transcription success (${directTimeTaken}ms): \"$directResult\"")
                    } else {
                        Log.w(TAG, "⚠️ Both transcription methods returned empty")
                        if (composeViewModel.appState.isRecording) {
                            composeViewModel.updateStatus("Recording - Listening... (no speech detected)")
                        } else {
                            composeViewModel.updateStatus("Processing done - no speech detected")
                        }
                    }
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "❌ Error in direct transcription", e)
                handler.post {
                    composeViewModel.updateStatus("Recording - Listening... (transcription error: ${e.message})")
                }
            } finally {
                // Always reset transcription flag
                isTranscribing = false
            }
        }.start()
    }
    
    // Audio preprocessing utilities
    private fun trimTrailingSilence(audioSamples: FloatArray): FloatArray {
        if (audioSamples.isEmpty()) return audioSamples
        
        val threshold = 0.01f // Silence threshold
        var lastNonSilentIndex = audioSamples.size - 1
        
        // Find the last non-silent sample
        for (i in audioSamples.size - 1 downTo 0) {
            if (kotlin.math.abs(audioSamples[i]) > threshold) {
                lastNonSilentIndex = i
                break
            }
        }
        
        // Keep a small buffer after the last speech
        val bufferSamples = minOf(1600, audioSamples.size - lastNonSilentIndex - 1) // 0.1 second buffer
        val trimmedSize = minOf(lastNonSilentIndex + bufferSamples + 1, audioSamples.size)
        
        return audioSamples.copyOfRange(0, trimmedSize)
    }
    
    private fun padToWindow(audioSamples: FloatArray): FloatArray {
        if (audioSamples.isEmpty()) return audioSamples
        
        val windowSize = 512
        val remainder = audioSamples.size % windowSize
        
        return if (remainder == 0) {
            audioSamples
        } else {
            val paddedSize = audioSamples.size + (windowSize - remainder)
            val paddedArray = FloatArray(paddedSize)
            audioSamples.copyInto(paddedArray)
            // Padding with zeros (silence)
            paddedArray
        }
    }

    private fun floatArrayToByteArray(floatArray: FloatArray): ByteArray {
        val byteArray = ByteArray(floatArray.size * 2) // 16-bit samples (2 bytes per sample)
        for (i in floatArray.indices) {
            val sample = (floatArray[i] * 32767).toInt().coerceIn(-32768, 32767).toShort()
            byteArray[i * 2] = (sample.toInt() and 0xFF).toByte()
            byteArray[i * 2 + 1] = ((sample.toInt() shr 8) and 0xFF).toByte()
        }
        return byteArray
    }

    private fun stopTranscription() {
        mWhisper!!.stop()
    }

    fun getFilesWithExtension(directory: File?, extension: String): ArrayList<File> {
        val filteredFiles = ArrayList<File>()

        // Check if the directory is accessible
        if (directory != null && directory.exists()) {
            val files = directory.listFiles()

            // Filter files by the provided extension
            if (files != null) {
                for (file in files) {
                    if (file.isFile && file.name.endsWith(extension)) {
                        filteredFiles.add(file)
                    }
                }
            }
        }

        return filteredFiles
    }


    


    override fun onDataReceived(data: FloatArray?) {
        data?.let { mVADManager?.processAudioChunk(it) }
    }
    
    override fun onUpdateReceived(message: String?) {
        Log.d(TAG, "Recorder update: $message")
        
        when (message) {
            Recorder.MSG_RECORDING -> {
                handler.post { 
                    composeViewModel.updateStatus("Recording - Listening...")
                    composeViewModel.updateRecordingState(true)
                }
            }
            Recorder.MSG_RECORDING_DONE -> {
                handler.post { 
                    composeViewModel.updateStatus("Recording stopped")
                    composeViewModel.updateRecordingState(false)
                }
            }
            else -> {
                handler.post { 
                    composeViewModel.updateStatus(message ?: "Unknown status")
                }
            }
        }
    }

    private fun showError(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        Log.e(TAG, "Error: $message")
    }

    internal class SharedResource {
        // Synchronized method for Thread 1 to wait for a signal with a timeout
        @Synchronized
        fun waitForSignalWithTimeout(timeoutMillis: Long): Boolean {
            val startTime = System.currentTimeMillis()

            try {
                (this as java.lang.Object).wait(timeoutMillis) // Wait for the given timeout
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt() // Restore interrupt status
                return false // Thread interruption as timeout
            }

            val elapsedTime = System.currentTimeMillis() - startTime

            // Check if wait returned due to notify or timeout
            return elapsedTime < timeoutMillis
        }

        // Synchronized method for Thread 2 to send a signal
        @Synchronized
        fun sendSignal() {
            (this as java.lang.Object).notify() // Notifies the waiting thread
        }
    }

    private fun submitPromptToLLM(prompt: String) {
        val llm = mLLMManager ?: return
        lifecycleScope.launch {
            val start = System.currentTimeMillis()
            val response = llm.generateResponse(prompt) ?: ""
            val duration = System.currentTimeMillis() - start
            handler.post {
                composeViewModel.appendLLMResponse(response, duration)
            }
        }
    }

    companion object {
        private const val TAG = "MainActivity"

        // whisper-tiny.tflite and whisper-base-nooptim.en.tflite works well
        private const val DEFAULT_MODEL_TO_USE = "whisper-tiny.tflite"

        // English only model ends with extension ".en.tflite"
        private const val ENGLISH_ONLY_MODEL_EXTENSION = ".en.tflite"
        private const val ENGLISH_ONLY_VOCAB_FILE = "filters_vocab_en.bin"
        private const val MULTILINGUAL_VOCAB_FILE = "filters_vocab_multilingual.bin"
        private val EXTENSIONS_TO_COPY = arrayOf("tflite", "bin", "wav", "pcm")

        // Copy assets with specified extensions to destination folder
        @JvmStatic
        private fun copyAssetsToSdcard(
            context: android.content.Context,
            destFolder: File?,
            extensions: Array<String>
        ) {
            val assetManager = context.assets

            try {
                // List all files in the assets folder once
                val assetFiles = assetManager.list("") ?: return

                for (assetFileName in assetFiles) {
                    // Check if file matches any of the provided extensions
                    for (extension in extensions) {
                        if (assetFileName.endsWith(".$extension")) {
                            val outFile = File(destFolder, assetFileName)

                            // Skip if file already exists
                            if (outFile.exists()) break

                            assetManager.open(assetFileName).use { inputStream ->
                                FileOutputStream(outFile).use { outputStream ->
                                    val buffer = ByteArray(1024)
                                    var bytesRead: Int
                                    while ((inputStream.read(buffer)
                                            .also { bytesRead = it }) != -1
                                    ) {
                                        outputStream.write(buffer, 0, bytesRead)
                                    }
                                }
                            }
                            break // No need to check further extensions
                        }
                    }
                }
            } catch (e: IOException) {
                e.printStackTrace()
            }
        }
    }
}