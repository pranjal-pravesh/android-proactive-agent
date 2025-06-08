package com.example.proactiiveagentv1.transcription

import ai.onnxruntime.*
import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.nio.FloatBuffer
import java.nio.LongBuffer
import kotlin.math.*

class WhisperONNXProcessor(private val context: Context) {
    
    private var encoderSession: OrtSession? = null
    private var decoderSession: OrtSession? = null
    private var ortEnvironment: OrtEnvironment? = null
    private var isInitialized = false
    private val tokenizer = WhisperTokenizer()
    
    companion object {
        private const val TAG = "WhisperONNXProcessor"
        private const val ENCODER_MODEL_NAME = "encoder_model.onnx"
        private const val DECODER_MODEL_NAME = "decoder_model.onnx"
        private const val SAMPLE_RATE = 16000
        private const val N_FFT = 400
        private const val HOP_LENGTH = 160
        private const val N_MELS = 80
        private const val MAX_AUDIO_SECONDS = 30
        
        // Whisper tokenizer constants
        private const val SOT_TOKEN = 50258L    // Start of transcript
        private const val EOT_TOKEN = 50257L    // End of transcript
        private const val NO_TIMESTAMPS = 50363L
        private const val ENGLISH_TOKEN = 50259L
        private const val TRANSCRIBE_TOKEN = 50359L
    }
    
    suspend fun initialize(): Boolean {
        return try {
            Log.i(TAG, "🚀 Initializing Whisper ONNX processor with full precision models...")
            
            // Copy both models from assets
            Log.d(TAG, "📁 Copying encoder model...")
            val encoderFile = copyModelFromAssets(ENCODER_MODEL_NAME)
            Log.d(TAG, "📁 Copying decoder model...")
            val decoderFile = copyModelFromAssets(DECODER_MODEL_NAME)
            
            Log.i(TAG, "📊 Encoder: ${encoderFile.name} (${encoderFile.length() / (1024 * 1024)} MB)")
            Log.i(TAG, "📊 Decoder: ${decoderFile.name} (${decoderFile.length() / (1024 * 1024)} MB)")
            
            if (!encoderFile.exists() || !decoderFile.exists()) {
                Log.e(TAG, "❌ Model files missing!")
                return false
            }
            
            // Initialize ONNX Runtime
            Log.d(TAG, "🔧 Setting up ONNX Runtime...")
            ortEnvironment = OrtEnvironment.getEnvironment()
            
            val sessionOptions = OrtSession.SessionOptions()
            sessionOptions.setIntraOpNumThreads(4) // Use more threads for full models
            
            // Create encoder session
            Log.d(TAG, "🔨 Loading encoder model...")
            encoderSession = ortEnvironment?.createSession(encoderFile.absolutePath, sessionOptions)
            Log.i(TAG, "✅ Encoder loaded successfully")
            
            // Create decoder session  
            Log.d(TAG, "🔨 Loading decoder model...")
            decoderSession = ortEnvironment?.createSession(decoderFile.absolutePath, sessionOptions)
            Log.i(TAG, "✅ Decoder loaded successfully")
            
            // Log model info
            Log.d(TAG, "📋 Encoder inputs: ${encoderSession?.inputNames}")
            Log.d(TAG, "📋 Encoder outputs: ${encoderSession?.outputNames}")
            Log.d(TAG, "📋 Decoder inputs: ${decoderSession?.inputNames}")
            Log.d(TAG, "📋 Decoder outputs: ${decoderSession?.outputNames}")
            
            isInitialized = true
            Log.i(TAG, "🎉 Whisper ONNX processor initialized successfully!")
            true
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to initialize Whisper ONNX processor", e)
            false
        }
    }
    
    private fun copyModelFromAssets(modelName: String): File {
        val modelFile = File(context.filesDir, modelName)
        
        try {
            if (!modelFile.exists()) {
                Log.d(TAG, "📥 Copying $modelName from assets...")
                
                context.assets.open(modelName).use { inputStream ->
                    FileOutputStream(modelFile).use { outputStream ->
                        val bytesCopied = inputStream.copyTo(outputStream)
                        Log.i(TAG, "✅ Copied ${bytesCopied / (1024 * 1024)} MB for $modelName")
                    }
                }
            } else {
                Log.d(TAG, "📋 $modelName already exists (${modelFile.length() / (1024 * 1024)} MB)")
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error copying $modelName", e)
            throw e
        }
        
        return modelFile
    }
    
    fun transcribeAudio(audioSamples: FloatArray): String {
        if (!isInitialized || encoderSession == null || decoderSession == null) {
            Log.w(TAG, "Processor not initialized")
            return ""
        }
        
        return try {
            val startTime = System.currentTimeMillis()
            Log.d(TAG, "🎙️ Transcribing ${audioSamples.size} samples (${audioSamples.size / SAMPLE_RATE.toFloat()}s)")
            
            // Step 1: Convert audio to mel spectrogram
            val melSpectrogram = audioToMelSpectrogram(audioSamples)
            Log.d(TAG, "🎵 Mel spectrogram: ${melSpectrogram.size}x${melSpectrogram[0].size}")
            
            // Step 2: Run encoder
            val encoderStartTime = System.currentTimeMillis()
            val audioFeatures = runEncoder(melSpectrogram)
            val encoderEndTime = System.currentTimeMillis()
            Log.d(TAG, "🔄 Encoder completed in ${encoderEndTime - encoderStartTime}ms")
            
            // Step 3: Run decoder
            val decoderStartTime = System.currentTimeMillis()
            val tokens = runDecoder(audioFeatures)
            val decoderEndTime = System.currentTimeMillis()
            Log.d(TAG, "🔄 Decoder completed in ${decoderEndTime - decoderStartTime}ms")
            
            // Step 4: Decode tokens to text
            val transcription = decodeTokens(tokens)
            val totalTime = System.currentTimeMillis() - startTime
            
            Log.i(TAG, "✅ Transcription completed in ${totalTime}ms: '$transcription'")
            transcription
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Transcription failed", e)
            ""
        }
    }
    
    private fun audioToMelSpectrogram(audio: FloatArray): Array<FloatArray> {
        // Pad or trim audio to 30 seconds (Whisper's standard input length)
        val targetLength = SAMPLE_RATE * MAX_AUDIO_SECONDS
        val paddedAudio = FloatArray(targetLength)
        
        if (audio.size <= targetLength) {
            System.arraycopy(audio, 0, paddedAudio, 0, audio.size)
        } else {
            System.arraycopy(audio, 0, paddedAudio, 0, targetLength)
        }
        
        // Whisper expects exactly 3000 frames for 30 seconds of audio
        val numFrames = 3000 // Fixed to match Whisper's expectation
        val melSpec = Array(N_MELS) { FloatArray(numFrames) }
        
        // Simplified mel spectrogram computation
        for (frame in 0 until numFrames) {
            val startIdx = frame * HOP_LENGTH
            val endIdx = minOf(startIdx + N_FFT, paddedAudio.size)
            
            // Ensure we don't exceed the audio length
            if (startIdx < paddedAudio.size) {
                for (mel in 0 until N_MELS) {
                    var energy = 0.0f
                    for (i in startIdx until endIdx) {
                        if (i < paddedAudio.size) {
                            energy += paddedAudio[i] * paddedAudio[i]
                        }
                    }
                    // Apply mel scaling and log
                    melSpec[mel][frame] = ln(energy + 1e-10f)
                }
            } else {
                // Fill remaining frames with silence (log of small value)
                for (mel in 0 until N_MELS) {
                    melSpec[mel][frame] = ln(1e-10f)
                }
            }
        }
        
        return melSpec
    }
    
    private fun runEncoder(melSpectrogram: Array<FloatArray>): OnnxTensor {
        val nMels = melSpectrogram.size
        val timeSteps = melSpectrogram[0].size
        
        // Create input tensor [batch=1, n_mels=80, time_steps=3000]
        val shape = longArrayOf(1, nMels.toLong(), timeSteps.toLong())
        val buffer = FloatBuffer.allocate(nMels * timeSteps)
        
        // Fill buffer in channel-first order (n_mels, time_steps)
        for (m in 0 until nMels) {
            for (t in 0 until timeSteps) {
                buffer.put(melSpectrogram[m][t])
            }
        }
        buffer.rewind()
        
        val inputTensor = OnnxTensor.createTensor(ortEnvironment!!, buffer, shape)
        val inputName = encoderSession!!.inputNames.first()
        val inputs = mapOf(inputName to inputTensor)
        
        // Run encoder inference
        val results = encoderSession!!.run(inputs)
        val outputName = encoderSession!!.outputNames.first()
        
        // Handle Optional<OnnxTensor> return type on Android
        val outputValue = results[outputName]
        return if (outputValue is java.util.Optional<*>) {
            outputValue.get() as OnnxTensor
        } else {
            outputValue as OnnxTensor
        }
    }
    
    private fun runDecoder(audioFeatures: OnnxTensor): LongArray {
        return try {
            // Create initial token sequence
            val initialTokens = longArrayOf(SOT_TOKEN, ENGLISH_TOKEN, TRANSCRIBE_TOKEN, NO_TIMESTAMPS)
            
            // Prepare decoder inputs
            val tokenShape = longArrayOf(1, initialTokens.size.toLong())
            val tokenBuffer = LongBuffer.allocate(initialTokens.size)
            tokenBuffer.put(initialTokens)
            tokenBuffer.rewind()
            
            val tokenTensor = OnnxTensor.createTensor(ortEnvironment!!, tokenBuffer, tokenShape)
            
            // Get decoder input names
            val decoderInputNames = decoderSession!!.inputNames
            val inputs = mutableMapOf<String, OnnxTensor>()
            
            // Map inputs based on decoder model structure
            for (inputName in decoderInputNames) {
                when {
                    inputName.contains("input_ids") || inputName.contains("tokens") -> {
                        inputs[inputName] = tokenTensor
                    }
                    inputName.contains("encoder") || inputName.contains("hidden") -> {
                        inputs[inputName] = audioFeatures
                    }
                }
            }
            
            // Run decoder inference
            val results = decoderSession!!.run(inputs)
            val outputName = decoderSession!!.outputNames.first()
            
            // Handle Optional<OnnxTensor> return type on Android
            val outputValue = results[outputName]
            val outputTensor = if (outputValue is java.util.Optional<*>) {
                outputValue.get() as OnnxTensor
            } else {
                outputValue as OnnxTensor
            }
            
            // Extract token IDs from logits
            val tokenIds = extractTokensFromLogits(outputTensor)
            Log.d(TAG, "Extracted ${tokenIds.size} tokens from decoder output")
            
            return initialTokens + tokenIds + longArrayOf(EOT_TOKEN)
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Decoder error", e)
            longArrayOf(SOT_TOKEN, ENGLISH_TOKEN, TRANSCRIBE_TOKEN, EOT_TOKEN)
        }
    }
    
    private fun extractTokensFromLogits(outputTensor: OnnxTensor): LongArray {
        return try {
            // Get tensor data as FloatArray
            val logits = outputTensor.floatBuffer.array()
            val vocabSize = 51865L // Whisper vocabulary size
            
            // Calculate number of token positions
            val numTokens = logits.size / vocabSize.toInt()
            val tokens = mutableListOf<Long>()
            
            Log.d(TAG, "Processing ${numTokens} token positions from logits array size ${logits.size}")
            
            // Process each token position
            for (pos in 0 until minOf(numTokens, 50)) { // Limit to 50 tokens max
                val startIdx = pos * vocabSize.toInt()
                val endIdx = startIdx + vocabSize.toInt()
                
                if (endIdx <= logits.size) {
                    // Find token with highest probability
                    var maxIdx = 0
                    var maxValue = Float.NEGATIVE_INFINITY
                    
                    for (i in startIdx until endIdx) {
                        if (logits[i] > maxValue) {
                            maxValue = logits[i]
                            maxIdx = i - startIdx
                        }
                    }
                    
                    val tokenId = maxIdx.toLong()
                    
                    // Skip special tokens that shouldn't be in output
                    if (tokenId !in setOf(SOT_TOKEN, ENGLISH_TOKEN, TRANSCRIBE_TOKEN, NO_TIMESTAMPS)) {
                        tokens.add(tokenId)
                        
                        // Stop at end of transcript token  
                        if (tokenId == EOT_TOKEN) break
                        
                        // Also break if we hit silence/no-speech token repeatedly
                        if (tokenId == 50358L && tokens.count { it == 50358L } > 2) {
                            Log.d(TAG, "Multiple silence tokens detected, stopping generation")
                            break
                        }
                    }
                }
            }
            
            Log.d(TAG, "Extracted tokens: ${tokens.take(10).joinToString(", ")}${if (tokens.size > 10) "..." else ""}")
            
            // Filter out repetitive silence/timestamp tokens
            val filteredTokens = tokens.filter { token ->
                token !in setOf(50358L, 50352L, 50360L, 50361L, 50362L, 50363L) ||
                tokens.count { it == token } <= 1
            }
            
            Log.d(TAG, "After filtering: ${filteredTokens.size} tokens")
            filteredTokens.toLongArray()
            
        } catch (e: Exception) {
            Log.e(TAG, "Error extracting tokens from logits", e)
            // Return empty array instead of fallback tokens
            longArrayOf()
        }
    }
    
    private fun decodeTokens(tokens: LongArray): String {
        return try {
            Log.d(TAG, "Decoding ${tokens.size} tokens using Whisper tokenizer")
            val transcription = tokenizer.decode(tokens)
            Log.d(TAG, "Tokenizer result: '$transcription'")
            
            // Only fallback if we have very few meaningful tokens
            if (transcription.isBlank() && tokens.size <= 4) {
                Log.w(TAG, "No valid transcription found - possible silence or unclear audio")
                "" // Return empty instead of "Hello"
            } else {
                transcription
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error decoding tokens", e)
            "" // Return empty on error
        }
    }
    
    fun isInitialized(): Boolean = isInitialized
    
    fun release() {
        try {
            encoderSession = null
            decoderSession = null
            ortEnvironment = null
            isInitialized = false
            Log.d(TAG, "🔄 Whisper ONNX processor released")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error releasing processor", e)
        }
    }
} 