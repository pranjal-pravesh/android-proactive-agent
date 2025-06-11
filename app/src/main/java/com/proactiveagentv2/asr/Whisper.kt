package com.proactiveagentv2.asr

import android.content.Context
import android.util.Log
import com.proactiveagentv2.engine.WhisperEngine
import com.proactiveagentv2.engine.WhisperEngineNative
import java.io.File
import java.io.IOException
import java.util.LinkedList
import java.util.Queue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.Condition
import java.util.concurrent.locks.Lock
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.Volatile

class Whisper(context: Context) {
    interface WhisperListener {
        fun onUpdateReceived(message: String?)
        fun onResultReceived(result: String?)
    }

    enum class Action {
        TRANSLATE, TRANSCRIBE
    }

    private val mInProgress = AtomicBoolean(false)
    private val audioBufferQueue: Queue<FloatArray> = LinkedList()

    //        this.mWhisperEngine = new WhisperEngineJava(context);
    private val mWhisperEngine: WhisperEngine = WhisperEngineNative(context)
    private var mAction: Action? = null
    private var mWavFilePath: String? = null
    private var mUpdateListener: WhisperListener? = null

    private val taskLock: Lock = ReentrantLock()
    private val hasTask: Condition = taskLock.newCondition()

    @Volatile
    private var taskAvailable = false

    init {
        // Start thread for file transcription for file transcription
        val threadTranscbFile = Thread { this.transcribeFileLoop() }
        threadTranscbFile.start()

        // Start thread for buffer transcription for live mic feed transcription
        val threadTranscbBuffer = Thread { this.transcribeBufferLoop() }
        threadTranscbBuffer.start()
    }

    fun setListener(listener: WhisperListener?) {
        this.mUpdateListener = listener
    }

    fun loadModel(modelPath: File, vocabPath: File, isMultilingual: Boolean) {
        loadModel(modelPath.absolutePath, vocabPath.absolutePath, isMultilingual)
    }

    fun loadModel(modelPath: String?, vocabPath: String?, isMultilingual: Boolean) {
        try {
            if (modelPath != null && vocabPath != null) {
                mWhisperEngine.initialize(modelPath, vocabPath, isMultilingual)
            } else {
                Log.e(TAG, "Model path or vocab path is null")
                sendUpdate("Model initialization failed: null path")
            }
        } catch (e: IOException) {
            Log.e(TAG, "Error initializing model...", e)
            sendUpdate("Model initialization failed")
        }
    }

    fun unloadModel() {
        mWhisperEngine.deinitialize()
    }

    fun setAction(action: Action?) {
        this.mAction = action
    }

    fun setFilePath(wavFile: String?) {
        this.mWavFilePath = wavFile
    }

    fun start() {
        if (!mInProgress.compareAndSet(false, true)) {
            Log.d(TAG, "Execution is already in progress...")
            return
        }
        taskLock.lock()
        try {
            taskAvailable = true
            hasTask.signal()
        } finally {
            taskLock.unlock()
        }
    }

    fun stop() {
        mInProgress.set(false)
    }

    val isInProgress: Boolean
        get() = mInProgress.get()

    private fun transcribeFileLoop() {
        while (!Thread.currentThread().isInterrupted) {
            taskLock.lock()
            try {
                while (!taskAvailable) {
                    hasTask.await()
                }
                transcribeFile()
                taskAvailable = false
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            } finally {
                taskLock.unlock()
            }
        }
    }

    private fun transcribeFile() {
        try {
            val wavFilePath = mWavFilePath
            if (mWhisperEngine.isInitialized && wavFilePath != null) {
                val waveFile = File(wavFilePath)
                if (waveFile.exists()) {
                    val startTime = System.currentTimeMillis()
                    sendUpdate(MSG_PROCESSING)

                    var result: String? = null
                    synchronized(mWhisperEngine) {
                        if (mAction == Action.TRANSCRIBE) {
                            result = mWhisperEngine.transcribeFile(wavFilePath)
                        } else {
//                            result = mWhisperEngine.getTranslation(wavFilePath);
                            Log.d(TAG, "TRANSLATE feature is not implemented")
                        }
                    }
                    sendResult(result)

                    val timeTaken = System.currentTimeMillis() - startTime
                    Log.d(TAG, "Time Taken for transcription: " + timeTaken + "ms")
                    sendUpdate(MSG_PROCESSING_DONE)
                } else {
                    sendUpdate(MSG_FILE_NOT_FOUND)
                }
            } else {
                sendUpdate("Engine not initialized or file path not set")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error during transcription", e)
            sendUpdate("Transcription failed: " + e.message)
        } finally {
            mInProgress.set(false)
        }
    }

    private fun sendUpdate(message: String) {
        if (mUpdateListener != null) {
            mUpdateListener!!.onUpdateReceived(message)
        }
    }

    private fun sendResult(message: String?) {
        if (mUpdateListener != null) {
            mUpdateListener!!.onResultReceived(message)
        }
    }

    /**//////////////////// Live MIC feed transcription calls ///////////////////////////////// */
    private fun transcribeBufferLoop() {
        while (!Thread.currentThread().isInterrupted) {
            val samples = readBuffer()
            if (samples != null) {
                synchronized(mWhisperEngine) {
                    val result = mWhisperEngine.transcribeBuffer(samples)
                    sendResult(result)
                }
            }
        }
    }

    fun writeBuffer(samples: FloatArray) {
        synchronized(audioBufferQueue) {
            audioBufferQueue.add(samples)
            (audioBufferQueue as java.lang.Object).notify()
        }
    }

    private fun readBuffer(): FloatArray? {
        synchronized(audioBufferQueue) {
            while (audioBufferQueue.isEmpty()) {
                try {
                    (audioBufferQueue as java.lang.Object).wait()
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return null
                }
            }
            return audioBufferQueue.poll()
        }
    }

    companion object {
        private const val TAG = "Whisper"
        const val MSG_PROCESSING: String = "Processing..."
        const val MSG_PROCESSING_DONE: String = "Processing done...!"
        const val MSG_FILE_NOT_FOUND: String = "Input file doesn't exist..!"

        @JvmStatic
        val ACTION_TRANSCRIBE: Action = Action.TRANSCRIBE
        @JvmStatic
        val ACTION_TRANSLATE: Action = Action.TRANSLATE
    }
}
