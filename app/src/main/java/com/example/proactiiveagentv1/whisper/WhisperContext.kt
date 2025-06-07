package com.example.proactiiveagentv1.whisper

import android.content.res.AssetManager
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.InputStream
import java.util.concurrent.Executors

class WhisperContext private constructor(private var ptr: Long) {
    
    companion object {
        private const val LOG_TAG = "WhisperContext"

        fun createContextFromFile(filePath: String): WhisperContext {
            val ptr = WhisperLib.initContext(filePath)
            if (ptr == 0L) {
                throw RuntimeException("Couldn't create context with path $filePath")
            }
            return WhisperContext(ptr)
        }

        fun createContextFromInputStream(stream: InputStream): WhisperContext {
            val ptr = WhisperLib.initContextFromInputStream(stream)
            if (ptr == 0L) {
                throw RuntimeException("Couldn't create context from input stream")
            }
            return WhisperContext(ptr)
        }

        fun createContextFromAsset(assetManager: AssetManager, assetPath: String): WhisperContext {
            val ptr = WhisperLib.initContextFromAsset(assetManager, assetPath)
            if (ptr == 0L) {
                throw RuntimeException("Couldn't create context from asset $assetPath")
            }
            return WhisperContext(ptr)
        }

        fun getSystemInfo(): String = WhisperLib.getSystemInfo()
    }

    private val executorService = Executors.newSingleThreadExecutor()

    suspend fun transcribeData(data: FloatArray): String = withContext(Dispatchers.IO) {
        if (ptr == 0L) {
            throw IllegalStateException("Context has been released")
        }
        
        val numThreads = WhisperUtils.getOptimalThreadCount()
        Log.d(LOG_TAG, "Selecting $numThreads threads")

        val result = StringBuilder()
        synchronized(this@WhisperContext) {
            WhisperLib.fullTranscribe(ptr, numThreads, data)
            val textCount = WhisperLib.getTextSegmentCount(ptr)
            for (i in 0 until textCount) {
                val sentence = WhisperLib.getTextSegment(ptr, i)
                result.append(sentence)
            }
        }
        result.toString()
    }

    suspend fun transcribeDataWithTime(data: FloatArray): List<WhisperSegment> = withContext(Dispatchers.IO) {
        if (ptr == 0L) {
            throw IllegalStateException("Context has been released")
        }

        val numThreads = WhisperUtils.getOptimalThreadCount()
        Log.d(LOG_TAG, "Selecting $numThreads threads")

        val segments = mutableListOf<WhisperSegment>()
        
        // Add timeout to prevent hanging  
        return@withContext withTimeout(15000L) { // Reduced to 15 second timeout
            // Remove synchronization to allow cancellation
            try {
                try {
                    Log.d(LOG_TAG, "About to call JNI fullTranscribe with ${data.size} samples")
                    val startTime = System.currentTimeMillis()
                    
                    WhisperLib.fullTranscribe(ptr, numThreads, data)
                    
                    val endTime = System.currentTimeMillis()
                    Log.d(LOG_TAG, "JNI fullTranscribe completed in ${endTime - startTime}ms, getting segment count")
                    
                    val textCount = WhisperLib.getTextSegmentCount(ptr)
                    Log.d(LOG_TAG, "Found $textCount segments")
                    
                    for (i in 0 until textCount) {
                        val start = WhisperLib.getTextSegmentT0(ptr, i)
                        val sentence = WhisperLib.getTextSegment(ptr, i)
                        val end = WhisperLib.getTextSegmentT1(ptr, i)
                        Log.d(LOG_TAG, "Segment $i: '$sentence' [$start -> $end]")
                        segments.add(WhisperSegment(start, end, sentence))
                    }
                } catch (e: Exception) {
                    Log.e(LOG_TAG, "Error during transcription", e)
                    throw e
                }
            } catch (e: Exception) {
                Log.e(LOG_TAG, "Outer transcription error", e)
                throw e
            }
            segments
        }
    }

    fun isValid(): Boolean = ptr != 0L

    fun release() {
        if (ptr != 0L) {
            WhisperLib.freeContext(ptr)
            ptr = 0L
        }
        executorService.shutdown()
    }
} 