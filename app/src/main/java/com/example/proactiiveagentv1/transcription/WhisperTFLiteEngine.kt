package com.example.proactiiveagentv1.transcription

import android.content.Context
import android.util.Log
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.Tensor
import org.tensorflow.lite.support.tensorbuffer.TensorBuffer
import java.io.FileInputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import kotlin.math.*

class WhisperTFLiteEngine(private val context: Context) {
    companion object {
        private const val TAG = "WhisperTFLiteEngine"
        
        // Whisper constants
        const val WHISPER_SAMPLE_RATE = 16000
        const val WHISPER_N_FFT = 400
        const val WHISPER_N_MEL = 80
        const val WHISPER_HOP_LENGTH = 160
        const val WHISPER_CHUNK_SIZE = 30
        const val WHISPER_MEL_LEN = 3000
        const val MAX_AUDIO_LENGTH = WHISPER_SAMPLE_RATE * WHISPER_CHUNK_SIZE // 30 seconds
    }
    
    private var interpreter: Interpreter? = null
    private var whisperUtil: WhisperUtil? = null
    private var isInitialized = false
    
    fun initialize(modelPath: String, vocabPath: String, multilingual: Boolean = false): Boolean {
        return try {
            // Load TFLite model
            loadModel(modelPath)
            Log.d(TAG, "Model loaded: $modelPath")
            
            // Load vocabulary and filters
            whisperUtil = WhisperUtil().apply {
                if (loadFiltersAndVocab(multilingual, vocabPath)) {
                    Log.d(TAG, "Filters and vocab loaded: $vocabPath")
                } else {
                    Log.e(TAG, "Failed to load filters and vocab")
                    return false
                }
            }
            
            isInitialized = true
            Log.i(TAG, "WhisperTFLite engine initialized successfully")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize WhisperTFLite engine", e)
            false
        }
    }
    
    fun transcribeAudioBuffer(audioSamples: FloatArray): String {
        if (!isInitialized || interpreter == null || whisperUtil == null) {
            Log.e(TAG, "Engine not initialized")
            return ""
        }
        
        return try {
            // Prepare audio samples (pad or trim to 30 seconds)
            val processedSamples = prepareAudioSamples(audioSamples)
            
            // Calculate mel spectrogram
            Log.d(TAG, "Calculating mel spectrogram...")
            val melSpectrogram = whisperUtil!!.getMelSpectrogram(processedSamples)
            
            // Run inference
            Log.d(TAG, "Running inference...")
            val result = runInference(melSpectrogram)
            
            Log.d(TAG, "Transcription result: $result")
            result
        } catch (e: Exception) {
            Log.e(TAG, "Transcription failed", e)
            ""
        }
    }
    
    private fun loadModel(modelPath: String) {
        val fileInputStream = FileInputStream(modelPath)
        val fileChannel = fileInputStream.getChannel()
        val declaredLength = fileChannel.size()
        val tfliteModel = fileChannel.map(FileChannel.MapMode.READ_ONLY, 0, declaredLength)
        
        val options = Interpreter.Options().apply {
            numThreads = Runtime.getRuntime().availableProcessors()
            // Enable optimizations
            setUseXNNPACK(true)
        }
        
        interpreter = Interpreter(tfliteModel, options)
        fileInputStream.close()
    }
    
    private fun prepareAudioSamples(samples: FloatArray): FloatArray {
        val processedSamples = FloatArray(MAX_AUDIO_LENGTH)
        val copyLength = minOf(samples.size, MAX_AUDIO_LENGTH)
        samples.copyInto(processedSamples, 0, 0, copyLength)
        return processedSamples
    }
    
    private fun runInference(melSpectrogram: FloatArray): String {
        val interpreter = this.interpreter ?: return ""
        val whisperUtil = this.whisperUtil ?: return ""
        
        return try {
            // Create input tensor
            val inputTensor = interpreter.getInputTensor(0)
            val inputShape = inputTensor.shape()
            val inputDataType = inputTensor.dataType()
            Log.d(TAG, "Input tensor - shape: ${inputShape.contentToString()}, type: $inputDataType")
            
            // Calculate expected input size
            val expectedSize = inputShape.fold(1) { acc, dim -> acc * dim }
            Log.d(TAG, "Expected input size: $expectedSize, actual mel size: ${melSpectrogram.size}")
            
            // Prepare input array - the mel spectrogram is already in row-major layout from WhisperUtil
            // We need to reshape it to [1, 80, 3000] for the model
            val inputArray = Array(1) { Array(inputShape[1]) { FloatArray(inputShape[2]) } }
            
            // The mel spectrogram comes as row-major: melData[j * nLen + i] where j=mel_bin, i=time_frame
            // We need to convert this to [batch=1, mel_bins=80, time_frames=3000]
            val nMel = inputShape[1] // 80
            val nTime = inputShape[2] // 3000
            
            for (mel in 0 until nMel) {
                for (time in 0 until nTime) {
                    // Row-major indexing from WhisperUtil: melData[mel * nTime + time]
                    val index = mel * nTime + time
                    inputArray[0][mel][time] = if (index < melSpectrogram.size) {
                        melSpectrogram[index]
                    } else {
                        0f
                    }
                }
            }
            
            // Create output tensor
            val outputTensor = interpreter.getOutputTensor(0)
            val outputShape = outputTensor.shape()
            val outputDataType = outputTensor.dataType()
            Log.d(TAG, "Output tensor - shape: ${outputShape.contentToString()}, type: $outputDataType")
            
            // Prepare output buffer
            val outputArray = Array(outputShape[0]) { IntArray(outputShape[1]) }
            
            Log.d(TAG, "Running TensorFlow Lite inference...")
            
            // Run inference
            interpreter.run(inputArray, outputArray)
            
            Log.d(TAG, "Inference completed successfully")
            
            // Process output tokens
            val outputTokens = outputArray[0]
            Log.d(TAG, "Processing ${outputTokens.size} output tokens")
            Log.d(TAG, "tokenEOT value: ${whisperUtil.tokenEOT}")
            
            // Debug: Log first 20 tokens to see what the model is producing
            val debugTokens = outputTokens.take(20)
            Log.d(TAG, "First 20 tokens: ${debugTokens.joinToString(", ")}")
            
            // Convert tokens to text
            val result = StringBuilder()
            var validTokenCount = 0
            
            for (i in outputTokens.indices) {
                val token = outputTokens[i]
                
                // Log each token for debugging
                if (i < 10) {
                    val tokenText = whisperUtil.getTokenString(token)
                    Log.d(TAG, "Token $i: $token -> '$tokenText' (EOT=${whisperUtil.tokenEOT})")
                }
                
                if (token == whisperUtil.tokenEOT) {
                    Log.d(TAG, "Reached EOT token at position $i")
                    break // End of transcript
                }
                
                // Skip Start of Transcript and other special tokens at the beginning
                if (token == 50257 || token == 50259 || token == 50359 || token == 50363) {
                    Log.d(TAG, "Skipping special token at position $i: $token")
                    continue
                }
                
                val tokenText = whisperUtil.getTokenString(token)
                if (tokenText.isNotEmpty()) {
                    // Match demo implementation: include tokens less than EOT (50256)
                    if (token < whisperUtil.tokenEOT) {
                        result.append(tokenText)
                        validTokenCount++
                        if (i < 10) {
                            Log.d(TAG, "Added token $token: '$tokenText'")
                        }
                    } else {
                        // Log special tokens for debugging
                        if (i < 10) {
                            Log.d(TAG, "Skipping special token $token: '$tokenText'")
                        }
                    }
                }
            }
            
            val transcription = result.toString().trim()
            Log.d(TAG, "Valid tokens processed: $validTokenCount")
            Log.d(TAG, "Transcription result: '$transcription'")
            
            transcription
            
        } catch (e: Exception) {
            Log.e(TAG, "Error during inference", e)
            ""
        }
    }
    

    
    fun isEngineInitialized(): Boolean = isInitialized
    
    fun release() {
        interpreter?.close()
        interpreter = null
        whisperUtil = null
        isInitialized = false
        Log.d(TAG, "WhisperTFLite engine released")
    }
} 