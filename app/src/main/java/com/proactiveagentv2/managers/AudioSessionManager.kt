package com.proactiveagentv2.managers

import android.content.Context
import android.util.Log
import android.widget.Toast
import com.proactiveagentv2.asr.Player
import com.proactiveagentv2.asr.Recorder
import com.proactiveagentv2.ui.MainViewModel
import com.proactiveagentv2.utils.WaveUtil
import com.proactiveagentv2.vad.VADManager
import java.io.File

/**
 * Manages audio recording and playback sessions including VAD integration
 */
class AudioSessionManager(
    private val context: Context,
    private val viewModel: MainViewModel
) {
    private var recorder: Recorder? = null
    private var player: Player? = null
    private var vadManager: VADManager? = null
    private var sdcardDataFolder: File? = null
    
    // Session state
    private var isRecording = false
    private var lastTranscribedSegmentFile: File? = null
    private var maxRecordingDurationMinutes = 30
    
    // Callbacks
    var onAudioDataReceived: ((FloatArray) -> Unit)? = null
    
    fun initialize(
        recorder: Recorder,
        player: Player,
        vadManager: VADManager,
        dataFolder: File
    ) {
        this.recorder = recorder
        this.player = player
        this.vadManager = vadManager
        this.sdcardDataFolder = dataFolder
        
        setupRecorderListener()
        setupPlayerListener()
        
        Log.d(TAG, "AudioSessionManager initialized")
    }
    
    private fun setupRecorderListener() {
        recorder?.setListener(object : Recorder.RecorderListener {
            override fun onUpdateReceived(message: String?) {
                when (message) {
                    Recorder.MSG_RECORDING -> {
                        isRecording = true
                        viewModel.updateRecordingState(true)
                        viewModel.updateStatus("Recording started...")
                    }
                    Recorder.MSG_RECORDING_DONE -> {
                        isRecording = false
                        viewModel.updateRecordingState(false)
                        viewModel.updateStatus("Recording completed")
                    }
                    else -> {
                        viewModel.updateStatus(message ?: "Unknown recorder message")
                    }
                }
            }
            
            override fun onDataReceived(samples: FloatArray?) {
                samples?.let { 
                    onAudioDataReceived?.invoke(it)
                }
            }
        })
    }
    
    private fun setupPlayerListener() {
        player?.setListener(object : Player.PlaybackListener {
            override fun onPlaybackStarted() {
                viewModel.updatePlayingState(true)
            }
            
            override fun onPlaybackStopped() {
                viewModel.updatePlayingState(false)
            }
        })
    }
    
    fun startRecordingSession() {
        if (isRecording) {
            Log.w(TAG, "Recording session already in progress")
            return
        }
        
        try {
            val waveFile = File(sdcardDataFolder, WaveUtil.RECORDING_FILE)
            
            // Configure recorder
            recorder?.setFilePath(waveFile.absolutePath)
            recorder?.setMaxRecordingDuration(maxRecordingDurationMinutes)
            
            // Start VAD processing
            vadManager?.startVAD()
            
            // Start recording
            recorder?.start()
            
            Log.d(TAG, "Recording session started with VAD processing")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start recording session", e)
            viewModel.updateStatus("Failed to start recording: ${e.message}")
        }
    }
    
    fun stopRecordingSession() {
        if (!isRecording) {
            Log.w(TAG, "No recording session in progress")
            return
        }
        
        try {
            // Stop VAD processing
            vadManager?.stopVAD()
            
            // Stop recording
            recorder?.stop()
            
            Log.d(TAG, "Recording session stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop recording session", e)
            viewModel.updateStatus("Failed to stop recording: ${e.message}")
        }
    }
    
    fun toggleRecording() {
        if (isRecording) {
            stopRecordingSession()
        } else {
            startRecordingSession()
        }
    }
    
    fun playLastSegment() {
        if (player?.isPlaying == true) {
            player?.stopPlayback()
            return
        }
        
        val fileToPlay = when {
            lastTranscribedSegmentFile?.exists() == true -> {
                Log.d(TAG, "Playing last transcribed segment: ${lastTranscribedSegmentFile?.name}")
                Toast.makeText(context, "Playing last transcribed segment", Toast.LENGTH_SHORT).show()
                lastTranscribedSegmentFile!!
            }
            File(sdcardDataFolder, WaveUtil.RECORDING_FILE).exists() -> {
                val recordingFile = File(sdcardDataFolder, WaveUtil.RECORDING_FILE)
                Log.d(TAG, "Playing full recording: ${recordingFile.name}")
                Toast.makeText(context, "Playing last recording", Toast.LENGTH_SHORT).show()
                recordingFile
            }
            else -> {
                Toast.makeText(context, "No audio recording available to play", Toast.LENGTH_SHORT).show()
                return
            }
        }
        
        try {
            player?.initializePlayer(fileToPlay.absolutePath)
            player?.startPlayback()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to play audio file", e)
            Toast.makeText(context, "Failed to play audio: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
    
    fun updateRecordingDuration(durationMinutes: Int) {
        maxRecordingDurationMinutes = durationMinutes
        Log.d(TAG, "Recording duration updated to: ${if (durationMinutes == 0) "Never stop" else "$durationMinutes minutes"}")
    }
    
    fun setLastTranscribedSegment(segmentFile: File) {
        lastTranscribedSegmentFile = segmentFile
    }
    
    fun isRecordingActive(): Boolean = isRecording
    
    fun release() {
        try {
            stopRecordingSession()
            
            lastTranscribedSegmentFile?.let { file ->
                if (file.exists()) {
                    file.delete()
                    Log.d(TAG, "Cleaned up last transcribed segment file")
                }
            }
            
            recorder = null
            player = null
            vadManager = null
            
            Log.d(TAG, "AudioSessionManager released")
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing AudioSessionManager", e)
        }
    }
    
    companion object {
        private const val TAG = "AudioSessionManager"
    }
} 