package com.proactiveagentv2.engine

import android.content.Context
import android.util.Log

class WhisperEngineNative(private val mContext: Context) : WhisperEngine {
    private val TAG = "WhisperEngineNative"
    private val nativePtr: Long // Native pointer to the TFLiteEngine instance

    private var mIsInitialized = false

    override val isInitialized: Boolean
        get() = mIsInitialized

    override fun initialize(modelPath: String, vocabPath: String, multilingual: Boolean): Boolean {
        val ret = loadModel(modelPath, multilingual)
        Log.d(TAG, "Model is loaded...$modelPath")

        mIsInitialized = true
        return true
    }

    override fun deinitialize() {
        freeModel()
    }

    override fun transcribeBuffer(samples: FloatArray): String {
        return transcribeBuffer(nativePtr, samples)
    }

    override fun transcribeFile(waveFile: String): String {
        return transcribeFile(nativePtr, waveFile)
    }

    private fun loadModel(modelPath: String, isMultilingual: Boolean): Int {
        return loadModel(nativePtr, modelPath, isMultilingual)
    }

    init {
        nativePtr = createTFLiteEngine()
    }

    // Native methods
    private external fun createTFLiteEngine(): Long
    private external fun loadModel(nativePtr: Long, modelPath: String, isMultilingual: Boolean): Int
    private external fun freeModel(nativePtr: Long = this.nativePtr)

    private external fun transcribeBuffer(nativePtr: Long, samples: FloatArray): String
    private external fun transcribeFile(nativePtr: Long, waveFile: String): String

    companion object {
        init {
            System.loadLibrary("audioEngine")
        }
    }
}
