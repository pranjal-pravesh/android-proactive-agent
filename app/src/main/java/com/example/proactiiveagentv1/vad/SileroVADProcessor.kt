package com.example.proactiiveagentv1.vad

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtLoggingLevel
import ai.onnxruntime.OrtSession
import android.content.Context
import java.nio.FloatBuffer

class SileroVADProcessor(private val context: Context) {
    private var ortSession: OrtSession? = null
    private var ortEnvironment: OrtEnvironment? = null
    private var modelState: FloatArray = FloatArray(2 * 1 * 128) { 0f }
    
    // VAD parameters
    private val sampleRate = 16000
    private val windowSizeSamples = 512 // 32ms at 16kHz
    
    fun initialize(): Boolean {
        return try {
            ortEnvironment = OrtEnvironment.getEnvironment()
            
            val modelFile = copyModelFromAssets()
            if (modelFile == null) return false
            
            val sessionOptions = OrtSession.SessionOptions()
            sessionOptions.setSessionLogLevel(OrtLoggingLevel.ORT_LOGGING_LEVEL_WARNING)
            
            ortSession = ortEnvironment?.createSession(modelFile.absolutePath, sessionOptions)
            resetState()
            
            ortSession != null
        } catch (e: Exception) {
            false
        }
    }
    
    private fun copyModelFromAssets(): java.io.File? {
        return try {
            val assetInputStream = context.assets.open("silero_vad.onnx")
            val modelFile = java.io.File(context.filesDir, "silero_vad.onnx")
            
            if (!modelFile.exists()) {
                val outputStream = modelFile.outputStream()
                assetInputStream.copyTo(outputStream)
                assetInputStream.close()
                outputStream.close()
            }
            
            modelFile
        } catch (e: Exception) {
            null
        }
    }
    
    fun resetState() {
        modelState.fill(0f)
    }
    
    fun processAudio(audioChunk: FloatArray): Float {
        if (audioChunk.size != windowSizeSamples) {
            return 0f
        }
        
        return runVADInference(audioChunk)
    }
    
    private fun runVADInference(audioChunk: FloatArray): Float {
        return try {
            ortSession?.let { session ->
                ortEnvironment?.let { env ->
                    // Create input tensors
                    val inputNames = session.inputNames.toList()
                    
                    val audioShape = longArrayOf(1, audioChunk.size.toLong())
                    val audioTensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(audioChunk), audioShape)
                    
                    val srData = longArrayOf(sampleRate.toLong())
                    val srShape = longArrayOf(1)
                    val srTensor = OnnxTensor.createTensor(env, java.nio.LongBuffer.wrap(srData), srShape)
                    
                    val stateShape = longArrayOf(2, 1, 128)
                    val stateTensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(modelState), stateShape)
                    
                    // Create input map
                    val inputs = mutableMapOf<String, OnnxTensor>()
                    
                    // Map inputs dynamically
                    when {
                        inputNames.contains("input") -> inputs["input"] = audioTensor
                        inputNames.contains("audio") -> inputs["audio"] = audioTensor  
                        else -> inputs[inputNames[0]] = audioTensor
                    }
                    
                    when {
                        inputNames.contains("sr") -> inputs["sr"] = srTensor
                        inputNames.contains("sample_rate") -> inputs["sample_rate"] = srTensor
                        else -> if (inputNames.size > 1) inputs[inputNames[1]] = srTensor
                    }
                    
                    when {
                        inputNames.contains("state") -> inputs["state"] = stateTensor
                        inputNames.contains("h") -> inputs["h"] = stateTensor
                        else -> if (inputNames.size > 2) inputs[inputNames[2]] = stateTensor
                    }
                    
                    val results = session.run(inputs)
                    
                    // Get the speech probability and updated state
                    val result = results[0].value as Array<FloatArray>
                    val speechProb = result[0][0]
                    
                    // Update model state if available
                    if (results.size() > 1) {
                        try {
                            val newState = results[1].value as? Array<Array<FloatArray>>
                            if (newState != null) {
                                for (i in 0 until 128) {
                                    modelState[i] = newState[0][0][i]
                                    modelState[i + 128] = newState[1][0][i]
                                }
                            }
                        } catch (e: Exception) {
                            // Handle state update error
                        }
                    }
                    
                    // Clean up
                    audioTensor.close()
                    srTensor.close()
                    stateTensor.close()
                    results.close()
                    
                    speechProb.coerceIn(0f, 1f)
                }
            } ?: 0f
        } catch (e: Exception) {
            0f
        }
    }
    
    fun isInitialized(): Boolean = ortSession != null
    
    fun release() {
        try {
            ortSession?.close()
            ortEnvironment?.close()
        } catch (e: Exception) {
            // Handle release error silently
        }
        ortSession = null
        ortEnvironment = null
    }
    
    companion object {
        const val WINDOW_SIZE_SAMPLES = 512
        const val SAMPLE_RATE = 16000
    }
} 