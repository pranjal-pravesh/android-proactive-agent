package com.proactiveagentv2.classifiers

import android.content.Context
import android.util.Log
import org.tensorflow.lite.Interpreter
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import kotlin.math.exp

/**
 * MobileBERT classifier for text classification tasks
 * Supports both actionable and contextable classification
 */
class MobileBERTClassifier(
    private val context: Context,
    private val modelType: ClassifierType
) {
    
    enum class ClassifierType(val modelPath: String, val tokenizerPath: String, val vocabPath: String) {
        ACTIONABLE("mobilebert-finetuned-actionable-v2/mobilebert_actionable_float32.tflite", 
                  "mobilebert-finetuned-actionable-v2/tokenizer.json",
                  "mobilebert-finetuned-actionable-v2/vocab.txt"),
        CONTEXTABLE("mobilebert-finetuned-contextable-v2/mobilebert_contextable_float32.tflite", 
                   "mobilebert-finetuned-contextable-v2/tokenizer.json",
                   "mobilebert-finetuned-contextable-v2/vocab.txt")
    }
    
    data class ClassificationResult(
        val isPositive: Boolean,
        val confidence: Float,
        val probabilities: FloatArray,
        val processingTimeMs: Long
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            other as ClassificationResult
            return isPositive == other.isPositive && 
                   confidence == other.confidence && 
                   probabilities.contentEquals(other.probabilities) &&
                   processingTimeMs == other.processingTimeMs
        }
        
        override fun hashCode(): Int {
            var result = isPositive.hashCode()
            result = 31 * result + confidence.hashCode()
            result = 31 * result + probabilities.contentHashCode()
            result = 31 * result + processingTimeMs.hashCode()
            return result
        }
    }
    
    private var interpreter: Interpreter? = null
    private var tokenizer: CustomTokenizer? = null
    private var isInitialized = false
    
    companion object {
        private const val TAG = "MobileBERTClassifier"
        private const val MAX_SEQUENCE_LENGTH = 128
        private const val NUM_CLASSES = 2 // Binary classification
    }
    
    /**
     * Initialize the classifier by loading the model and tokenizer
     */
    fun initialize(): Boolean {
        return try {
            Log.d(TAG, "Initializing ${modelType.name} classifier...")
            
            // Load TensorFlow Lite model
            val modelBuffer = loadModelFile(modelType.modelPath)
            
            // Create interpreter with options for debugging and stability
            val options = Interpreter.Options()
            options.setNumThreads(1) // Use single thread for stability
            options.setUseNNAPI(false) // Disable NNAPI to avoid potential issues
            options.setAllowFp16PrecisionForFp32(false) // Use full precision
            interpreter = Interpreter(modelBuffer, options)
            
            // Debug model signature
            debugModelSignature()
            
            // Load custom tokenizer
            tokenizer = CustomTokenizer(context)
            tokenizer!!.initialize(modelType.tokenizerPath, modelType.vocabPath)
            
            isInitialized = true
            Log.d(TAG, "${modelType.name} classifier initialized successfully")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize ${modelType.name} classifier", e)
            isInitialized = false
            false
        }
    }
    
    /**
     * Classify the given text
     */
    @Synchronized
    fun classify(text: String): ClassificationResult? {
        if (!isInitialized || interpreter == null || tokenizer == null) {
            Log.e(TAG, "Classifier not initialized")
            return null
        }
        
        if (text.isBlank()) {
            Log.w(TAG, "Empty text provided for classification")
            return ClassificationResult(false, 0.0f, floatArrayOf(1.0f, 0.0f), 0L)
        }
        
        // Temporary safety check - disable classifier for very short texts that might cause issues
        if (text.trim().length < 3) {
            Log.w(TAG, "Text too short for reliable classification: \"$text\"")
            return ClassificationResult(true, 0.5f, floatArrayOf(0.5f, 0.5f), 0L)
        }
        
        return try {
            val startTime = System.currentTimeMillis()
            
            // Tokenize the text
            val encoding = tokenizer!!.encode(text)
            
            // Prepare inputs (already padded to 128 by our tokenizer)
            val inputIds = encoding.ids
            val attentionMask = encoding.attentionMask
            val tokenTypeIds = encoding.typeIds
            
            // Debug tokenization for actionable classifier issues
            Log.d(TAG, "${modelType.name} tokenization - Text: \"$text\"")
            Log.d(TAG, "${modelType.name} input_ids (first 10): ${inputIds.take(10).toList()}")
            Log.d(TAG, "${modelType.name} attention_mask (first 10): ${attentionMask.take(10).toList()}")
            
            // Validate input sizes (debug logging removed to reduce verbosity)
            
            // Validate inputs
            if (inputIds.size != MAX_SEQUENCE_LENGTH || 
                attentionMask.size != MAX_SEQUENCE_LENGTH || 
                tokenTypeIds.size != MAX_SEQUENCE_LENGTH) {
                Log.e(TAG, "Invalid input tensor sizes")
                return ClassificationResult(false, 0.0f, floatArrayOf(1.0f, 0.0f), 0L)
            }
            
            // Create input buffers - order must match model signature
            // Based on debugModelSignature output, ensure correct tensor mapping
            val inputs = arrayOf(
                intBuffer(attentionMask),  // Input[0]: attention_mask
                intBuffer(inputIds),       // Input[1]: input_ids  
                intBuffer(tokenTypeIds)    // Input[2]: token_type_ids
            )
            
            // Create output buffer
            val output = Array(1) { FloatArray(NUM_CLASSES) }
            
            // Validate output
            if (output[0].isEmpty()) {
                Log.e(TAG, "Model output is empty")
                return ClassificationResult(false, 0.0f, floatArrayOf(1.0f, 0.0f), 0L)
            }
            
            // Run inference with comprehensive error handling
            try {
                interpreter!!.runForMultipleInputsOutputs(inputs, mapOf(0 to output))
            } catch (e: IllegalArgumentException) {
                Log.e(TAG, "Invalid arguments for inference", e)
                return ClassificationResult(false, 0.0f, floatArrayOf(1.0f, 0.0f), 0L)
            } catch (e: RuntimeException) {
                Log.e(TAG, "Runtime error during inference", e)
                return ClassificationResult(false, 0.0f, floatArrayOf(1.0f, 0.0f), 0L)
            } catch (e: Exception) {
                Log.e(TAG, "Unexpected error during inference", e)
                return ClassificationResult(false, 0.0f, floatArrayOf(1.0f, 0.0f), 0L)
            }
            
            // Apply softmax and get predictions
            val logits = output[0]
            
            // Check for NaN or infinite values in logits
            if (logits.any { it.isNaN() || it.isInfinite() }) {
                Log.e(TAG, "Model produced NaN or infinite logits: ${logits.contentToString()}")
                return ClassificationResult(false, 0.0f, floatArrayOf(1.0f, 0.0f), 0L)
            }
            
            // Log logits and probabilities for debugging actionable classifier issues
            Log.d(TAG, "${modelType.name} raw logits: ${logits.contentToString()}")
            
            val probabilities = softmax(logits)
            Log.d(TAG, "${modelType.name} probabilities: ${probabilities.contentToString()}")
            
            // The numerically stable softmax should prevent NaN values, but keep this as a final safety check
            if (probabilities.any { it.isNaN() || it.isInfinite() }) {
                Log.e(TAG, "Softmax still produced NaN or infinite probabilities: ${probabilities.contentToString()}")
                // Return a safe fallback result
                return ClassificationResult(false, 0.5f, floatArrayOf(0.5f, 0.5f), 0L)
            }
            
            // Find index of maximum probability (more robust approach)
            var predictedClass = 0
            var maxProbability = probabilities[0]
            for (i in 1 until probabilities.size) {
                if (probabilities[i] > maxProbability) {
                    maxProbability = probabilities[i]
                    predictedClass = i
                }
            }
            val confidence = maxProbability
            
            val processingTime = System.currentTimeMillis() - startTime
            
            // Standard interpretation: class 1 = positive (actionable/contextable), class 0 = negative
            val isPositiveResult = predictedClass == 1
            
            Log.d(TAG, "${modelType.name} classification - Text: \"$text\", " +
                      "Predicted class: $predictedClass, " +
                      "Interpreted as: ${if (isPositiveResult) "POSITIVE" else "NEGATIVE"}, " +
                      "Confidence: $confidence, " +
                      "Time: ${processingTime}ms")
            
            ClassificationResult(
                isPositive = isPositiveResult,
                confidence = confidence,
                probabilities = probabilities,
                processingTimeMs = processingTime
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error during ${modelType.name} classification", e)
            null
        }
    }
    
    /**
     * Load TensorFlow Lite model from assets
     */
    private fun loadModelFile(filename: String): MappedByteBuffer {
        val fileDescriptor = context.assets.openFd(filename)
        val inputStream = FileInputStream(fileDescriptor.fileDescriptor)
        val fileChannel = inputStream.channel
        return fileChannel.map(
            FileChannel.MapMode.READ_ONLY,
            fileDescriptor.startOffset,
            fileDescriptor.declaredLength
        )
    }
    
    /**
     * Debug model input/output signature
     */
    private fun debugModelSignature() {
        try {
            val interpreter = this.interpreter ?: return
            
            Log.d(TAG, "=== Model Signature Debug ===")
            Log.d(TAG, "Input tensor count: ${interpreter.inputTensorCount}")
            Log.d(TAG, "Output tensor count: ${interpreter.outputTensorCount}")
            
            for (i in 0 until interpreter.inputTensorCount) {
                val tensor = interpreter.getInputTensor(i)
                Log.d(TAG, "Input[$i]: shape=${tensor.shape().contentToString()}, " +
                          "type=${tensor.dataType()}, name=${tensor.name()}")
            }
            
            for (i in 0 until interpreter.outputTensorCount) {
                val tensor = interpreter.getOutputTensor(i)
                Log.d(TAG, "Output[$i]: shape=${tensor.shape().contentToString()}, " +
                          "type=${tensor.dataType()}, name=${tensor.name()}")
            }
            Log.d(TAG, "=== End Model Signature ===")
        } catch (e: Exception) {
            Log.e(TAG, "Error debugging model signature", e)
        }
    }
    

    
    /**
     * Create ByteBuffer for int array input
     */
    private fun intBuffer(input: IntArray): ByteBuffer {
        if (input.size != MAX_SEQUENCE_LENGTH) {
            Log.e(TAG, "Input array size ${input.size} doesn't match expected $MAX_SEQUENCE_LENGTH")
        }
        
        val buffer = ByteBuffer.allocateDirect(MAX_SEQUENCE_LENGTH * 4)
        buffer.order(ByteOrder.nativeOrder())
        
        // Ensure we only write MAX_SEQUENCE_LENGTH integers
        for (i in 0 until MAX_SEQUENCE_LENGTH) {
            val value = if (i < input.size) input[i] else 0
            buffer.putInt(value)
        }
        
        buffer.rewind()
        return buffer
    }
    
    /**
     * Apply numerically stable softmax to logits
     */
    private fun softmax(logits: FloatArray): FloatArray {
        if (logits.isEmpty()) {
            return floatArrayOf()
        }
        
        // Numerical stability: subtract max value to prevent overflow
        val maxLogit = logits.maxOrNull() ?: 0f
        val shiftedLogits = logits.map { (it - maxLogit).coerceIn(-50f, 50f) } // Clamp to prevent extreme values
        
        // Calculate exponentials
        val exp = shiftedLogits.map { 
            val expValue = exp(it.toDouble()).toFloat()
            // Ensure no NaN or infinite values
            when {
                expValue.isNaN() -> Float.MIN_VALUE
                expValue.isInfinite() -> Float.MAX_VALUE
                expValue <= 0f -> Float.MIN_VALUE
                else -> expValue
            }
        }
        
        // Calculate sum with safety check
        val sum = exp.sum().let { s ->
            when {
                s.isNaN() || s <= 0f -> 1f // Fallback to uniform distribution
                s.isInfinite() -> Float.MAX_VALUE
                else -> s
            }
        }
        
        // Calculate probabilities with safety checks
        return exp.map { 
            val prob = it / sum
            when {
                prob.isNaN() -> 1f / logits.size // Uniform probability as fallback
                prob.isInfinite() -> 1f
                prob < 0f -> 0f
                prob > 1f -> 1f
                else -> prob
            }
        }.toFloatArray()
    }
    
    /**
     * Check if classifier is initialized
     */
    fun isInitialized(): Boolean = isInitialized
    
    /**
     * Release resources
     */
    fun release() {
        try {
            interpreter?.close()
            interpreter = null
            tokenizer = null
            isInitialized = false
            Log.d(TAG, "${modelType.name} classifier released")
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing ${modelType.name} classifier", e)
        }
    }
} 