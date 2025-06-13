package com.proactiveagentv2.asr

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.core.app.ActivityCompat
import com.proactiveagentv2.utils.WaveUtil.createWaveFile
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.Condition
import java.util.concurrent.locks.Lock
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.Volatile

class Recorder(private val mContext: Context) {
    interface RecorderListener {
        fun onUpdateReceived(message: String?)

        fun onDataReceived(samples: FloatArray?)
    }

    private val mInProgress = AtomicBoolean(false)

    private var mWavFilePath: String? = null
    private var mListener: RecorderListener? = null
    private val lock: Lock = ReentrantLock()
    private val hasTask: Condition = lock.newCondition()
    private val fileSavedLock = Any() // Lock object for wait/notify

    @Volatile
    private var shouldStartRecording = false
    
    // Configurable recording duration (0 means never stop)
    private var maxRecordingDurationMinutes = 30

    private val workerThread: Thread

    init {
        // Initialize and start the worker thread
        workerThread = Thread { this.recordLoop() }
        workerThread.start()
    }

    fun setListener(listener: RecorderListener?) {
        this.mListener = listener
    }

    fun setFilePath(wavFile: String?) {
        this.mWavFilePath = wavFile
    }

    fun setMaxRecordingDuration(durationMinutes: Int) {
        this.maxRecordingDurationMinutes = durationMinutes
        Log.d(TAG, "Recording duration set to: ${if (durationMinutes == 0) "Never stop" else "$durationMinutes minutes"}")
    }

    fun start() {
        if (!mInProgress.compareAndSet(false, true)) {
            Log.d(TAG, "Recording is already in progress...")
            return
        }
        lock.lock()
        try {
            shouldStartRecording = true
            hasTask.signal()
        } finally {
            lock.unlock()
        }
    }

    fun stop() {
        mInProgress.set(false)

        // Wait for the recording thread to finish
        synchronized(fileSavedLock) {
            try {
                (fileSavedLock as java.lang.Object).wait() // Wait until notified by the recording thread
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
    }

    val isInProgress: Boolean
        get() = mInProgress.get()

    private fun sendUpdate(message: String?) {
        if (mListener != null) mListener!!.onUpdateReceived(message)
    }

    private fun sendData(samples: FloatArray) {
        if (mListener != null) mListener!!.onDataReceived(samples)
    }

    private fun recordLoop() {
        while (true) {
            lock.lock()
            try {
                while (!shouldStartRecording) {
                    hasTask.await()
                }
                shouldStartRecording = false
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                return
            } finally {
                lock.unlock()
            }

            // Start recording process
            try {
                recordAudio()
            } catch (e: Exception) {
                Log.e(TAG, "Recording error...", e)
                sendUpdate(e.message)
            } finally {
                mInProgress.set(false)
            }
        }
    }

    private fun recordAudio() {
        if (ActivityCompat.checkSelfPermission(
                mContext,
                Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            Log.d(TAG, "AudioRecord permission is not granted")
            sendUpdate("Permission not granted for recording")
            return
        }

        sendUpdate(MSG_RECORDING)

        val channels = 1
        val bytesPerSample = 2
        val sampleRateInHz = 16000
        val channelConfig = AudioFormat.CHANNEL_IN_MONO
        val audioFormat = AudioFormat.ENCODING_PCM_16BIT
        val audioSource = MediaRecorder.AudioSource.MIC

        val bufferSize = AudioRecord.getMinBufferSize(sampleRateInHz, channelConfig, audioFormat)
        val audioRecord =
            AudioRecord(audioSource, sampleRateInHz, channelConfig, audioFormat, bufferSize)
        audioRecord.startRecording()

        // Calculate byte counts for different durations
        val bytesForOneSecond = sampleRateInHz * bytesPerSample * channels
        
        // Use configurable recording duration (0 means never stop)
        val effectiveDurationSeconds = if (maxRecordingDurationMinutes == 0) {
            Int.MAX_VALUE // Never stop - use maximum possible value
        } else {
            maxRecordingDurationMinutes * 60 // Convert minutes to seconds
        }
        val bytesForMaxDuration = if (effectiveDurationSeconds == Int.MAX_VALUE) {
            Long.MAX_VALUE // Never stop
        } else {
            bytesForOneSecond.toLong() * effectiveDurationSeconds
        }
        
        // Real-time streaming for VAD processing
        val streamingChunkSizeMs = 500 // 500ms chunks for real-time processing
        val bytesForStreamingChunk = (bytesForOneSecond * streamingChunkSizeMs) / 1000

        val outputBuffer = ByteArrayOutputStream() // Buffer for saving complete recording
        val streamingBuffer = ByteArrayOutputStream() // Buffer for real-time streaming to VAD

        val audioData = ByteArray(bufferSize)
        var totalBytesRead = 0L
        
        val durationText = if (maxRecordingDurationMinutes == 0) "never stop" else "${maxRecordingDurationMinutes} minutes"
        Log.d(TAG, "Starting continuous recording with VAD processing (duration: $durationText)")

        while (mInProgress.get() && totalBytesRead < bytesForMaxDuration) {
            val bytesRead = audioRecord.read(audioData, 0, bufferSize)
            if (bytesRead > 0) {
                // Save to complete recording buffer
                outputBuffer.write(audioData, 0, bytesRead)
                // Add to streaming buffer for real-time processing
                streamingBuffer.write(audioData, 0, bytesRead)
                totalBytesRead += bytesRead

                // Send audio chunks frequently for real-time VAD processing
                if (streamingBuffer.size() >= bytesForStreamingChunk) {
                    val samples = convertToFloatArray(ByteBuffer.wrap(streamingBuffer.toByteArray()))
                    streamingBuffer.reset() // Clear the streaming buffer
                    sendData(samples) // Send for real-time VAD processing
                    
                    // Occasional logging (every 60 seconds)
                    if (totalBytesRead % (bytesForOneSecond * 60) < bufferSize) {
                        val recordingMinutes = totalBytesRead / bytesForOneSecond / 60
                        Log.d(TAG, "Recording: ${recordingMinutes} minutes completed")
                    }
                }
            } else {
                Log.d(TAG, "AudioRecord error, bytes read: $bytesRead")
                break
            }
        }

        // Check if we hit the duration limit (not applicable for "never stop")
        if (maxRecordingDurationMinutes > 0 && totalBytesRead >= bytesForMaxDuration) {
            Log.w(TAG, "Recording stopped due to duration limit (${maxRecordingDurationMinutes} minutes)")
            sendUpdate("Recording stopped - duration limit reached")
        }

        // Send any remaining audio data in the streaming buffer
        if (streamingBuffer.size() > 0) {
            val remainingSamples = convertToFloatArray(ByteBuffer.wrap(streamingBuffer.toByteArray()))
            sendData(remainingSamples)
            Log.d(TAG, "Sent final chunk: ${remainingSamples.size} samples")
        }

        audioRecord.stop()
        audioRecord.release()

        // Save the complete recording to file
        createWaveFile(
            mWavFilePath,
            outputBuffer.toByteArray(),
            sampleRateInHz,
            channels,
            bytesPerSample
        )
        sendUpdate(MSG_RECORDING_DONE)

        // Notify the waiting thread that recording is complete
        synchronized(fileSavedLock) {
            (fileSavedLock as java.lang.Object).notify()
        }
        
        val recordingDurationSeconds = totalBytesRead / bytesForOneSecond
        Log.d(TAG, "Recording completed. Duration: ${recordingDurationSeconds} seconds, Total samples: ${totalBytesRead / bytesPerSample}")
    }

    private fun convertToFloatArray(buffer: ByteBuffer): FloatArray {
        buffer.order(ByteOrder.nativeOrder())
        val samples = FloatArray(buffer.remaining() / 2)
        for (i in samples.indices) {
            samples[i] = buffer.getShort() / 32768.0f
        }
        return samples
    }

    // Move file from /data/user/0/com.proactiveagentv2/files/MicInput.wav to
    // sdcard path /storage/emulated/0/Android/data/com.proactiveagentv2/files/MicInput.wav
    // Copy and delete the original file
    private fun moveFileToSdcard(waveFilePath: String) {
        val sourceFile = File(waveFilePath)
        val destinationFile = File(mContext.getExternalFilesDir(null), sourceFile.name)
        try {
            FileInputStream(sourceFile).use { inputStream ->
                FileOutputStream(destinationFile).use { outputStream ->
                    val buffer = ByteArray(1024)
                    var length: Int
                    while ((inputStream.read(buffer).also { length = it }) > 0) {
                        outputStream.write(buffer, 0, length)
                    }
                    if (sourceFile.delete()) {
                        Log.d(
                            "FileMove",
                            "File moved successfully to " + destinationFile.absolutePath
                        )
                    } else {
                        Log.e("FileMove", "Failed to delete the original file.")
                    }
                }
            }
        } catch (e: IOException) {
            Log.e("FileMove", "File move failed", e)
        }
    }

    companion object {
        private const val TAG = "Recorder"
        const val ACTION_STOP: String = "Stop"
        const val ACTION_RECORD: String = "Record"
        const val MSG_RECORDING: String = "Recording..."
        const val MSG_RECORDING_DONE: String = "Recording done...!"
        const val MAX_RECORDING_DURATION: Int = 30 * 60 // 30 minutes
    }
}
