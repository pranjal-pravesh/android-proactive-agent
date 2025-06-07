package com.example.proactiiveagentv1.whisper

import android.content.res.AssetManager
import android.os.Build
import android.util.Log
import java.io.InputStream

object WhisperLib {
    private const val LOG_TAG = "WhisperLib"

    init {
        Log.d(LOG_TAG, "Primary ABI: ${Build.SUPPORTED_ABIS[0]}")
        
        when {
            WhisperUtils.isArmEabiV7a() -> {
                val cpuInfo = WhisperUtils.cpuInfo()
                if (cpuInfo?.contains("vfpv4") == true) {
                    Log.d(LOG_TAG, "Loading libwhisper_vfpv4.so")
                    System.loadLibrary("whisper_vfpv4")
                } else {
                    Log.d(LOG_TAG, "Loading libwhisper.so")
                    System.loadLibrary("whisper")
                }
            }
            WhisperUtils.isArmEabiV8a() -> {
                val cpuInfo = WhisperUtils.cpuInfo()
                if (cpuInfo?.contains("fphp") == true) {
                    Log.d(LOG_TAG, "Loading libwhisper_v8fp16_va.so")
                    System.loadLibrary("whisper_v8fp16_va")
                } else {
                    Log.d(LOG_TAG, "Loading libwhisper.so")
                    System.loadLibrary("whisper")
                }
            }
            else -> {
                Log.d(LOG_TAG, "Loading libwhisper.so")
                System.loadLibrary("whisper")
            }
        }
    }

    @JvmStatic
    external fun initContextFromInputStream(inputStream: InputStream): Long

    @JvmStatic
    external fun initContextFromAsset(assetManager: AssetManager, assetPath: String): Long

    @JvmStatic
    external fun initContext(modelPath: String): Long

    @JvmStatic
    external fun freeContext(contextPtr: Long)

    @JvmStatic
    external fun fullTranscribe(contextPtr: Long, numThreads: Int, audioData: FloatArray)

    @JvmStatic
    external fun getTextSegmentCount(contextPtr: Long): Int

    @JvmStatic
    external fun getTextSegment(contextPtr: Long, index: Int): String

    @JvmStatic
    external fun getTextSegmentT0(contextPtr: Long, index: Int): Long

    @JvmStatic
    external fun getTextSegmentT1(contextPtr: Long, index: Int): Long

    @JvmStatic
    external fun getSystemInfo(): String

    @JvmStatic
    external fun benchMemcpy(nthread: Int): String

    @JvmStatic
    external fun benchGgmlMulMat(nthread: Int): String
} 