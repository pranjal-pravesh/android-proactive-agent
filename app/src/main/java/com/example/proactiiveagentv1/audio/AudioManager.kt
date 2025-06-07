package com.example.proactiiveagentv1.audio

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*

class AudioManager(
    private val context: Context,
    private val onAudioData: (FloatArray, Int) -> Unit
) {
    private var audioRecord: AudioRecord? = null
    private var recordingJob: Job? = null
    private var isRecording = false
    
    // Audio parameters
    private val sampleRate = 16000
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT
    private val bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat) * 2
    
    fun initialize(): Boolean {
        return try {
            if (!hasAudioPermission()) {
                return false
            }
            
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                channelConfig,
                audioFormat,
                bufferSize
            )
            
            audioRecord?.state == AudioRecord.STATE_INITIALIZED
        } catch (e: Exception) {
            false
        }
    }
    
    fun startRecording(): Boolean {
        if (isRecording || audioRecord == null || !hasAudioPermission()) {
            return false
        }
        
        return try {
            audioRecord?.startRecording()
            isRecording = true
            
            recordingJob = CoroutineScope(Dispatchers.IO).launch {
                val buffer = ShortArray(bufferSize / 2)
                
                while (isActive && isRecording) {
                    val samplesRead = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                    
                    if (samplesRead > 0) {
                        // Convert to float and normalize
                        val floatBuffer = FloatArray(samplesRead)
                        for (i in 0 until samplesRead) {
                            floatBuffer[i] = buffer[i].toFloat() / Short.MAX_VALUE.toFloat()
                        }
                        
                        onAudioData(floatBuffer, samplesRead)
                    }
                }
            }
            
            true
        } catch (e: SecurityException) {
            isRecording = false
            false
        } catch (e: Exception) {
            isRecording = false
            false
        }
    }
    
    fun stopRecording() {
        if (!isRecording) return
        
        isRecording = false
        recordingJob?.cancel()
        recordingJob = null
        
        try {
            audioRecord?.stop()
        } catch (e: Exception) {
            // Handle stop error silently
        }
    }
    
    fun isCurrentlyRecording(): Boolean = isRecording
    
    private fun hasAudioPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context, 
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }
    
    fun release() {
        stopRecording()
        try {
            audioRecord?.release()
        } catch (e: Exception) {
            // Handle release error silently
        }
        audioRecord = null
    }
    
    companion object {
        const val SAMPLE_RATE = 16000
    }
} 