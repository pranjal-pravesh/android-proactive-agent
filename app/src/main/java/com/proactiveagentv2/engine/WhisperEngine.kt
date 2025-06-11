package com.proactiveagentv2.engine

import java.io.IOException

interface WhisperEngine {
    val isInitialized: Boolean

    @Throws(IOException::class)
    fun initialize(modelPath: String, vocabPath: String, multilingual: Boolean): Boolean
    fun deinitialize()
    fun transcribeBuffer(samples: FloatArray): String
    fun transcribeFile(waveFile: String): String
}
