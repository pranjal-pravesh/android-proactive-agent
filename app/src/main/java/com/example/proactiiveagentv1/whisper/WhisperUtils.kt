package com.example.proactiiveagentv1.whisper

import android.os.Build
import android.util.Log
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths

object WhisperUtils {
    private const val LOG_TAG = "WhisperUtils"

    fun isArmEabiV7a(): Boolean {
        return Build.SUPPORTED_ABIS[0] == "armeabi-v7a"
    }

    fun isArmEabiV8a(): Boolean {
        return Build.SUPPORTED_ABIS[0] == "arm64-v8a"
    }

    fun cpuInfo(): String? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val path = Paths.get("/proc/cpuinfo")
                String(Files.readAllBytes(path))
            } else {
                // Fallback for older Android versions
                val file = File("/proc/cpuinfo")
                if (file.exists()) {
                    file.readText()
                } else {
                    null
                }
            }
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Couldn't read /proc/cpuinfo", e)
            null
        }
    }
    
    fun getOptimalThreadCount(): Int {
        val processors = Runtime.getRuntime().availableProcessors()
        return when {
            processors >= 8 -> 4
            processors >= 4 -> 2
            else -> 1
        }
    }
    
    fun convertSamplesToMs(samples: Int, sampleRate: Int): Long {
        return (samples * 1000L) / sampleRate
    }
    
    fun convertMsToSamples(timeMs: Long, sampleRate: Int): Int {
        return ((timeMs * sampleRate) / 1000).toInt()
    }
} 