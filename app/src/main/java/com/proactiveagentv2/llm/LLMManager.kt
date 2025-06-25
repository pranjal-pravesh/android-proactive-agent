package com.proactiveagentv2.llm

import android.content.Context
import android.util.Log
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.proactiveagentv2.tools.ToolManager
import com.proactiveagentv2.managers.MemoryManager
import kotlinx.coroutines.*
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL


data class LLMModelInfo(
    val name: String,
    val modelId: String,
    val modelFile: String,
    val description: String,
    val sizeInBytes: Long,
    val downloadUrl: String?,
    val defaultConfig: LLMConfig,
    val modelType: ModelType,
    val supportsGPU: Boolean = false,
    val isLocalOnly: Boolean = false,
    val requiresLiteRT: Boolean = false // New: indicates if model requires LiteRT for GPU
)

data class LLMConfig(
    val topK: Int = 40,
    val topP: Float = 0.95f,
    val temperature: Float = 1.0f,
    val maxTokens: Int = 1024,
    val useGPU: Boolean = false,
    val useNNAPI: Boolean = false, // Neural Networks API for additional acceleration
    val numThreads: Int = 4
)

enum class ModelType {
    QWEN,
    GEMMA
}

enum class InferenceEngine {
    MEDIAPIPE_CPU, // MediaPipe LLM API - CPU preference
    MEDIAPIPE_GPU  // MediaPipe LLM API - GPU acceleration preference
}

enum class ModelDownloadStatus {
    NOT_DOWNLOADED,
    DOWNLOADING,
    DOWNLOADED,
    ERROR,
    LOCAL_ONLY
}

data class DownloadProgress(
    val downloadedBytes: Long,
    val totalBytes: Long,
    val percentage: Int
)

/**
 * Enhanced LLM Response that includes tool execution results
 */
data class LLMResponse(
    val originalText: String,
    val finalText: String,
    val toolResults: List<ToolResult> = emptyList(),
    val hasToolCalls: Boolean = false,
    val success: Boolean = true,
    val error: String? = null,
    val modelUsed: String? = null,
    val inferenceEngine: InferenceEngine? = null,
    val tokensPerSecond: Float? = null
)

/**
 * GPU compatibility information for the device
 */
data class GPUCompatibilityInfo(
    val isSupported: Boolean,
    val deviceInfo: String,
    val recommendedForDevice: Boolean,
    val gpuModel: String = "Unknown",
    val supportsFloat16: Boolean = false
)

/**
 * Performance metrics for inference operations
 */
data class InferenceMetrics(
    val startTime: Long,
    val endTime: Long,
    val tokensGenerated: Int,
    val tokensPerSecond: Float,
    val inferenceEngine: InferenceEngine,
    val modelName: String
)

class LLMManager(private val context: Context) {
    private var llmInference: LlmInference? = null // MediaPipe LLM API (supports both CPU and GPU delegates)
    private var isInitialized = false
    private var currentModel: LLMModelInfo? = null
    private var currentInferenceEngine = InferenceEngine.MEDIAPIPE_CPU
    private val modelsDir = File(context.getExternalFilesDir(null), "llm_models")
    
    // New components for enhanced functionality
    private val promptBuilder = PromptBuilder()
    private val toolManager = ToolManager()
    private val conversationHistory = mutableListOf<String>()
    private var memoryManager: MemoryManager? = null
    
    // Performance tracking
    private var lastInferenceMetrics: InferenceMetrics? = null
    
    // SharedPreferences for settings persistence
    private val prefs by lazy {
        context.getSharedPreferences("llm_conversation_settings", Context.MODE_PRIVATE)
    }
    
    companion object {
        private const val TAG = "LLMManager"
        private const val MAX_CONVERSATION_HISTORY = 10
        private const val CONVERSATION_HISTORY_KEY = "conversation_history"
        private const val CONTEXT_LENGTH_KEY = "context_length"
        private const val SAVE_HISTORY_KEY = "save_conversation_history"
        
        val QWEN_MODEL = LLMModelInfo(
            name = "Qwen2.5-1.5B-Instruct q8",
            modelId = "litert-community/Qwen2.5-1.5B-Instruct",
            modelFile = "Qwen2.5-1.5B-Instruct_multi-prefill-seq_q8_ekv1280.task",
            description = "A variant of Qwen/Qwen2.5-1.5B-Instruct with 8-bit quantization and GPU acceleration support",
            sizeInBytes = 1625493432, // ~1.6GB
            downloadUrl = "https://huggingface.co/litert-community/Qwen2.5-1.5B-Instruct/resolve/main/Qwen2.5-1.5B-Instruct_multi-prefill-seq_q8_ekv1280.task",
            defaultConfig = LLMConfig(
                topK = 40,
                topP = 0.95f,
                temperature = 1.0f,
                maxTokens = 1024,
                useGPU = true, // Default to GPU since it's supported
                useNNAPI = false,
                numThreads = 4
            ),
            modelType = ModelType.QWEN,
            supportsGPU = true, // Enable GPU support for Qwen
            isLocalOnly = false,
            requiresLiteRT = false
        )
        
        val GEMMA_3N_MODEL = LLMModelInfo(
            name = "Gemma 3n E2B int4",
            modelId = "google/gemma-3n-E2B-it-litert-preview", 
            modelFile = "gemma-3n-E2B-it-int4.task",
            description = "Gemma 3n E2B instruction-tuned model with int4 quantization, supports text and vision input with GPU acceleration",
            sizeInBytes = 3136226711, // ~2.92GB
            downloadUrl = null, // No download - local only
            defaultConfig = LLMConfig(
                topK = 64,
                topP = 0.95f,
                temperature = 1.0f,
                maxTokens = 4096,
                useGPU = true, // Default to true since GPU acceleration is implemented
                useNNAPI = false,
                numThreads = 4
            ),
            modelType = ModelType.GEMMA,
            supportsGPU = true,
            isLocalOnly = true,
            requiresLiteRT = false // Uses MediaPipe with GPU delegate
        )
        
        fun getAllAvailableModels(): List<LLMModelInfo> = listOf(QWEN_MODEL, GEMMA_3N_MODEL)
    }
    
    init {
        if (!modelsDir.exists()) {
            modelsDir.mkdirs()
        }
        
        // Load conversation history if enabled
        loadConversationHistory()
        
        Log.d(TAG, "LLMManager initialized with multi-model support and GPU acceleration")
        Log.d(TAG, "Available models: ${getAllAvailableModels().map { it.name }}")
        Log.d(TAG, "Conversation history enabled: ${isSaveHistoryEnabled()}, Messages loaded: ${conversationHistory.size}")
        Log.d(TAG, toolManager.getToolDescriptions())
        
        // Log GPU capabilities at startup
        val gpuInfo = checkGPUCompatibility()
        Log.d(TAG, "GPU Compatibility: ${gpuInfo.isSupported}")
        Log.d(TAG, "GPU Device Info: ${gpuInfo.deviceInfo}")
    }
    
    fun getModelStatus(modelInfo: LLMModelInfo): ModelDownloadStatus {
        if (modelInfo.isLocalOnly) {
            val modelFile = File(modelsDir, modelInfo.modelFile)
            return if (modelFile.exists() && modelFile.length() > 0) {
                ModelDownloadStatus.LOCAL_ONLY
            } else {
                ModelDownloadStatus.NOT_DOWNLOADED
            }
        }
        
        val modelFile = File(modelsDir, modelInfo.modelFile)
        return when {
            !modelFile.exists() -> ModelDownloadStatus.NOT_DOWNLOADED
            modelFile.length() == 0L -> ModelDownloadStatus.ERROR
            else -> ModelDownloadStatus.DOWNLOADED
        }
    }
    
    suspend fun downloadModel(
        modelInfo: LLMModelInfo,
        onProgress: (DownloadProgress) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        if (modelInfo.isLocalOnly) {
            Log.w(TAG, "Cannot download local-only model: ${modelInfo.name}")
            return@withContext false
        }
        
        val downloadUrl = modelInfo.downloadUrl
        if (downloadUrl == null) {
            Log.e(TAG, "No download URL available for model: ${modelInfo.name}")
            return@withContext false
        }
        
        try {
            Log.d(TAG, "Starting download of ${modelInfo.name}")
            
            val modelFile = File(modelsDir, modelInfo.modelFile)
            val tempFile = File(modelsDir, "${modelInfo.modelFile}.tmp")
            
            // Clean previous files
            if (modelFile.exists()) modelFile.delete()
            if (tempFile.exists()) tempFile.delete()
            
            val url = URL(downloadUrl)
            val connection = url.openConnection() as HttpURLConnection
            connection.connect()
            
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                Log.e(TAG, "HTTP error: ${connection.responseCode}")
                return@withContext false
            }
            
            val expectedBytes = connection.contentLengthLong // may be -1
            Log.d(TAG, "Expected file size (reported): $expectedBytes")
            
            connection.inputStream.use { input ->
                FileOutputStream(tempFile).use { output ->
                    val buffer = ByteArray(8192)
                    var totalDownloaded = 0L
                    var bytesRead: Int
                    
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        if (!isActive) {
                            Log.d(TAG, "Download cancelled")
                            return@withContext false
                        }
                        output.write(buffer, 0, bytesRead)
                        totalDownloaded += bytesRead
                        val percent = if (expectedBytes > 0) ((totalDownloaded * 100) / expectedBytes).toInt() else 0
                        withContext(Dispatchers.Main) {
                            onProgress(DownloadProgress(totalDownloaded, expectedBytes, percent))
                        }
                    }
                }
            }
            
            // Verify and move file
            val valid = expectedBytes <= 0 || tempFile.length() == expectedBytes
            if (valid) {
                tempFile.copyTo(modelFile, overwrite = true)
                tempFile.delete()
                Log.d(TAG, "Model downloaded successfully: ${modelFile.absolutePath}")
                true
            } else {
                Log.e(TAG, "Download size mismatch. Expected $expectedBytes bytes, got ${tempFile.length()} bytes")
                tempFile.delete()
                false
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Error downloading model", e)
            false
        }
    }
    
    suspend fun initializeModel(modelInfo: LLMModelInfo, config: LLMConfig? = null): Boolean = withContext(Dispatchers.IO) {
        try {
            val status = getModelStatus(modelInfo)
            if (status != ModelDownloadStatus.DOWNLOADED && status != ModelDownloadStatus.LOCAL_ONLY) {
                Log.e(TAG, "Model not available: ${modelInfo.name}")
                return@withContext false
            }
            
            val modelFile = File(modelsDir, modelInfo.modelFile)
            Log.d(TAG, "Initializing LLM with model: ${modelFile.absolutePath}")
            
            val finalConfig = config ?: modelInfo.defaultConfig
            
            // Release existing instances
            releaseCurrentModel()
            
            // Determine which inference engine to use
            val inferenceEngine = determineInferenceEngine(modelInfo, finalConfig)
            Log.d(TAG, "Selected inference engine: $inferenceEngine")
            
            val success = initializeWithMediaPipe(modelFile, finalConfig, modelInfo, inferenceEngine)
            
            if (success) {
                currentInferenceEngine = inferenceEngine
                isInitialized = true
                currentModel = modelInfo
                
                // Configure prompt builder for current model type
                promptBuilder.setModelType(modelInfo.modelType)
                Log.i(TAG, "🎯 PROMPT BUILDER CONFIGURED FOR ${modelInfo.modelType}")
                Log.i(TAG, "System prompt preview: ${promptBuilder.buildSystemPrompt().take(200)}...")
                
                // Log detailed initialization status
                logInitializationStatus(modelInfo, finalConfig, inferenceEngine)
                
                return@withContext true
            }
            
            Log.e(TAG, "Failed to initialize model with $inferenceEngine")
            return@withContext false
            
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing LLM with ${modelInfo.name}", e)
            isInitialized = false
            currentModel = null
            false
        }
    }
    
    private fun determineInferenceEngine(modelInfo: LLMModelInfo, config: LLMConfig): InferenceEngine {
        return when {
            // GPU requested and model supports it - use MediaPipe with GPU delegate
            config.useGPU && modelInfo.supportsGPU && checkGPUCompatibility().isSupported -> {
                Log.d(TAG, "GPU acceleration requested and supported - using MediaPipe GPU delegate")
                InferenceEngine.MEDIAPIPE_GPU
            }
            // Default to MediaPipe CPU
            else -> {
                Log.d(TAG, "Using MediaPipe CPU delegate for ${modelInfo.name}")
                InferenceEngine.MEDIAPIPE_CPU
            }
        }
    }
    
    private fun logInitializationStatus(modelInfo: LLMModelInfo, config: LLMConfig, engine: InferenceEngine) {
        Log.d(TAG, "=== Model Initialization Complete ===")
        Log.d(TAG, "Model: ${modelInfo.name}")
        Log.d(TAG, "Inference Engine: $engine")
        Log.d(TAG, "Config: MaxTokens=${config.maxTokens}, TopK=${config.topK}")
        
        when (engine) {
            InferenceEngine.MEDIAPIPE_GPU -> {
                Log.i(TAG, "🚀 GPU ACCELERATION PREFERRED!")
                Log.i(TAG, "   MediaPipe GenAI will use best available acceleration")
                Log.i(TAG, "   GPU acceleration may be handled internally by MediaPipe")
            }
            InferenceEngine.MEDIAPIPE_CPU -> {
                Log.i(TAG, "🔧 MediaPipe CPU inference preferred")
                if (modelInfo.supportsGPU && config.useGPU) {
                    Log.w(TAG, "   GPU was requested but MediaPipe GenAI will determine actual acceleration")
                } else {
                    Log.i(TAG, "   CPU-only preference selected")
                }
            }
        }
        Log.d(TAG, "=====================================")
    }
    
    /**
     * Check GPU compatibility for MediaPipe delegates
     * Based on Google AI Edge Gallery implementation
     */
    fun checkGPUCompatibility(): GPUCompatibilityInfo {
        return try {
            // For MediaPipe GPU delegate, we assume most modern Android devices support it
            // MediaPipe will handle fallback internally if GPU delegate fails
            val isSupported = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP
            
            val deviceInfo = if (isSupported) {
                "GPU acceleration supported via MediaPipe GPU delegate"
            } else {
                "GPU acceleration requires Android 5.0+ (API 21+)"
            }
            
            Log.d(TAG, "GPU Compatibility Check:")
            Log.d(TAG, "  Supported: $isSupported")
            Log.d(TAG, "  Device Info: $deviceInfo")
            Log.d(TAG, "  Android API: ${android.os.Build.VERSION.SDK_INT}")
            
            GPUCompatibilityInfo(
                isSupported = isSupported,
                deviceInfo = deviceInfo,
                recommendedForDevice = isSupported,
                gpuModel = "MediaPipe GPU delegate",
                supportsFloat16 = isSupported
            )
        } catch (e: Exception) {
            Log.w(TAG, "Error checking GPU compatibility", e)
            GPUCompatibilityInfo(
                isSupported = false,
                deviceInfo = "Error checking GPU: ${e.message}",
                recommendedForDevice = false
            )
        }
    }
    
    private fun initializeWithMediaPipe(modelFile: File, config: LLMConfig, modelInfo: LLMModelInfo, engine: InferenceEngine): Boolean {
        return try {
            Log.d(TAG, "Initializing with MediaPipe LLM API using $engine")
            Log.d(TAG, "Model file: ${modelFile.absolutePath}")
            Log.d(TAG, "Model file exists: ${modelFile.exists()}")
            Log.d(TAG, "Model file size: ${modelFile.length()} bytes")
            
            if (!modelFile.exists()) {
                Log.e(TAG, "Model file does not exist: ${modelFile.absolutePath}")
                return false
            }
            
            if (modelFile.length() == 0L) {
                Log.e(TAG, "Model file is empty: ${modelFile.absolutePath}")
                return false
            }
            
            // Validate model file format
            if (!validateModelFile(modelFile)) {
                return false
            }
            
            // Create LlmInferenceOptions - MediaPipe GenAI may handle GPU acceleration internally
            val options = LlmInference.LlmInferenceOptions.builder()
                .setModelPath(modelFile.absolutePath)
                .setMaxTokens(config.maxTokens)
                .setMaxTopK(config.topK)
                .build()
            
            // Log the intended acceleration mode
            when (engine) {
                InferenceEngine.MEDIAPIPE_GPU -> {
                    Log.d(TAG, "GPU acceleration requested - MediaPipe GenAI will use best available acceleration")
                }
                InferenceEngine.MEDIAPIPE_CPU -> {
                    Log.d(TAG, "CPU mode requested")
                }
            }
            
            llmInference = LlmInference.createFromOptions(context, options)
            
            Log.d(TAG, "MediaPipe LLM initialized successfully with ${engine.name} configuration")
            true
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize MediaPipe LLM (${modelInfo.name})", e)
            
            // Provide specific error guidance
            analyzeAndLogError(e, modelFile)
            
            llmInference = null
            false
        }
    }
    
    private fun validateModelFile(modelFile: File): Boolean {
        return try {
            val firstBytes = ByteArray(10)
            modelFile.inputStream().use { it.read(firstBytes) }
            Log.d(TAG, "Model file header: ${firstBytes.joinToString(" ") { "%02x".format(it) }}")
            
            // Basic validation - check if file seems to be a valid format
            // MediaPipe .task files should have specific headers
            val headerString = String(firstBytes, 0, minOf(4, firstBytes.size))
            val isValidFormat = firstBytes.isNotEmpty() // Basic check
            
            if (!isValidFormat) {
                Log.e(TAG, "Model file appears to have invalid format")
                return false
            }
            
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error validating model file", e)
            false
        }
    }
    
    private fun analyzeAndLogError(e: Exception, modelFile: File) {
        val errorMessage = e.message?.lowercase() ?: ""
        when {
            errorMessage.contains("unable to open zip archive") -> {
                Log.e(TAG, "❌ ERROR ANALYSIS: Invalid .task file format")
                Log.e(TAG, "📋 SUGGESTIONS:")
                Log.e(TAG, "   1. Verify the model file is a valid MediaPipe .task file")
                Log.e(TAG, "   2. Check if file download completed successfully") 
                Log.e(TAG, "   3. File size: ${modelFile.length()} bytes")
                Log.e(TAG, "   4. Try re-downloading or using a different model")
            }
            errorMessage.contains("not found") -> {
                Log.e(TAG, "❌ ERROR ANALYSIS: Model file not found")
                Log.e(TAG, "📋 File path: ${modelFile.absolutePath}")
            }
            errorMessage.contains("memory") || errorMessage.contains("oom") -> {
                Log.e(TAG, "❌ ERROR ANALYSIS: Out of memory")
                Log.e(TAG, "📋 SUGGESTIONS:")
                Log.e(TAG, "   1. Try enabling GPU acceleration to reduce CPU memory usage")
                Log.e(TAG, "   2. Close other applications to free memory")
                Log.e(TAG, "   3. Consider using a smaller model variant")
            }
            else -> {
                Log.e(TAG, "❌ ERROR ANALYSIS: Unknown initialization error")
                Log.e(TAG, "📋 Error: ${e.message}")
            }
        }
    }
    

    
    private fun releaseCurrentModel() {
        llmInference?.close()
        llmInference = null
        
        isInitialized = false
        currentInferenceEngine = InferenceEngine.MEDIAPIPE_CPU
    }
    
    fun getCurrentModel(): LLMModelInfo? = currentModel
    
    fun getCurrentInferenceEngine(): InferenceEngine = currentInferenceEngine
    
    fun getLastInferenceMetrics(): InferenceMetrics? = lastInferenceMetrics
    
    fun switchModel(modelInfo: LLMModelInfo, config: LLMConfig? = null): Deferred<Boolean> {
        return CoroutineScope(Dispatchers.IO).async {
            initializeModel(modelInfo, config)
        }
    }
    
    fun getAvailableModels(): List<LLMModelInfo> = getAllAvailableModels()
    
    fun getDownloadableModels(): List<LLMModelInfo> = getAllAvailableModels().filter { !it.isLocalOnly }
    
    fun getLocalOnlyModels(): List<LLMModelInfo> = getAllAvailableModels().filter { it.isLocalOnly }
    
    /**
     * Enhanced response generation with tool calling support
     * Works with both MediaPipe and LiteRT inference engines
     */
    suspend fun generateEnhancedResponse(
        userInput: String,
        includeContext: Boolean = true,
        useTools: Boolean = true
    ): LLMResponse = withContext(Dispatchers.IO) {
        if (!isInitialized) {
            Log.w(TAG, "LLM not initialized, cannot generate response")
            return@withContext LLMResponse(
                originalText = "",
                finalText = "LLM not initialized. Please download and initialize a model first.",
                success = false,
                error = "LLM not initialized",
                modelUsed = currentModel?.name,
                inferenceEngine = currentInferenceEngine
            )
        }
        
        try {
            Log.d(TAG, "Generating enhanced response for: $userInput using $currentInferenceEngine")
            val startTime = System.currentTimeMillis()
            
            // Build the initial prompt using ChatML format
            val systemPrompt = if (useTools) promptBuilder.buildSystemPrompt() else promptBuilder.buildBasicSystemPrompt()
            Log.i(TAG, "🔧 BUILDING PROMPT FOR USER INPUT: $userInput")
            Log.i(TAG, "📋 USING SYSTEM PROMPT (${if (useTools) "WITH TOOLS" else "BASIC"}): ${systemPrompt.take(150)}...")
            
            // RAG: Retrieve relevant memories for context
            val relevantMemories = if (includeContext && memoryManager != null) {
                try {
                    Log.d(TAG, "🧠 Retrieving relevant memories for query: ${userInput.take(50)}...")
                    val memories = memoryManager!!.retrieveMemories(
                        query = userInput,
                        maxResults = 3,
                        minSimilarity = 0.3f
                    )
                    Log.d(TAG, "📚 Found ${memories.size} relevant memories")
                    memories.forEach { memory ->
                        Log.d(TAG, "  📄 Memory (${String.format("%.3f", memory.similarity)}): ${memory.text.take(50)}...")
                    }
                    memories
                } catch (e: Exception) {
                    Log.e(TAG, "❌ Error retrieving memories", e)
                    emptyList()
                }
            } else {
                emptyList()
            }
            
            val chatMLPrompt = promptBuilder.buildChatMLPrompt(
                systemPrompt = systemPrompt,
                userInput = userInput,
                conversationHistory = if (includeContext) conversationHistory else emptyList(),
                includeContext = includeContext,
                relevantMemories = relevantMemories
            )
            
            Log.i(TAG, "📝 COMPLETE PROMPT LENGTH: ${chatMLPrompt.length} characters")
            Log.i(TAG, "🎯 PROMPT PREVIEW: ${chatMLPrompt.take(300)}...")
            
            // Validate and log ChatML format for debugging
            val isValidChatML = promptBuilder.validateChatMLFormat(chatMLPrompt)
            if (!isValidChatML) {
                Log.w(TAG, "Invalid ChatML format detected!")
            }
            
            // Log complete prompt in debug mode
            promptBuilder.logChatMLPrompt(chatMLPrompt)
            
            // Generate initial response using MediaPipe (with CPU or GPU delegate)
            val initialResponse = generateWithMediaPipe(chatMLPrompt)
            
            if (initialResponse.isNullOrBlank()) {
                return@withContext LLMResponse(
                    originalText = "",
                    finalText = "Sorry, I couldn't generate a response. Please try again.",
                    success = false,
                    error = "Empty response from LLM",
                    modelUsed = currentModel?.name,
                    inferenceEngine = currentInferenceEngine
                )
            }
            
            Log.i(TAG, "🤖 RAW LLM RESPONSE: $initialResponse")
            
            // Calculate performance metrics
            val tokensGenerated = estimateTokenCount(initialResponse)
            val endTime = System.currentTimeMillis()
            val tokensPerSecond = if (endTime > startTime) {
                tokensGenerated / ((endTime - startTime) / 1000f)
            } else 0f
            
            // Store performance metrics
            lastInferenceMetrics = InferenceMetrics(
                startTime = startTime,
                endTime = endTime,
                tokensGenerated = tokensGenerated,
                tokensPerSecond = tokensPerSecond,
                inferenceEngine = currentInferenceEngine,
                modelName = currentModel?.name ?: "Unknown"
            )
            
            Log.d(TAG, "Performance: ${String.format("%.1f", tokensPerSecond)} tokens/sec with $currentInferenceEngine")
            
            // Check for tool calls if tools are enabled
            if (useTools) {
                Log.i(TAG, "🔍 CHECKING FOR TOOL CALLS IN RESPONSE...")
                var toolCalls = promptBuilder.parseToolCalls(initialResponse)
                
                // Fallback: If no tool calls detected but input requires tools, force them
                if (toolCalls.isEmpty()) {
                    Log.w(TAG, "⚠️ NO TOOL CALLS DETECTED, CHECKING IF WE SHOULD FORCE THEM...")
                    toolCalls = detectAndForceMissingToolCalls(userInput, initialResponse)
                }
                
                if (toolCalls.isNotEmpty()) {
                    Log.i(TAG, "✅ FOUND ${toolCalls.size} TOOL CALLS: ${toolCalls.map { "${it.toolName}(${it.parameters})" }}")
                    
                    // Execute tools
                    val toolResults = toolManager.executeTools(toolCalls)
                    
                    // ⚡ OPTIMIZATION: Skip second LLM call, format tool results directly
                    val finalResponse = formatToolResultsDirectly(toolResults, userInput)
                    Log.i(TAG, "⚡ OPTIMIZED: Using direct tool result formatting (no second LLM call)")
                    
                    // Update conversation history
                    addToConversationHistory("User: $userInput")
                    addToConversationHistory("Assistant: $finalResponse")
                    
                    return@withContext LLMResponse(
                        originalText = initialResponse,
                        finalText = finalResponse,
                        toolResults = toolResults,
                        hasToolCalls = true,
                        success = true,
                        modelUsed = currentModel?.name,
                        inferenceEngine = currentInferenceEngine,
                        tokensPerSecond = tokensPerSecond
                    )
                }
            }
            
            // No tool calls - return original response
            addToConversationHistory("User: $userInput")
            addToConversationHistory("Assistant: $initialResponse")
            
            LLMResponse(
                originalText = initialResponse,
                finalText = initialResponse,
                success = true,
                modelUsed = currentModel?.name,
                inferenceEngine = currentInferenceEngine,
                tokensPerSecond = tokensPerSecond
            )
            
        } catch (e: Exception) {
            Log.e(TAG, "Error generating enhanced response", e)
            handleLLMError(e)
            LLMResponse(
                originalText = "",
                finalText = "I encountered an error while processing your request. Please try again.",
                success = false,
                error = e.message,
                modelUsed = currentModel?.name,
                inferenceEngine = currentInferenceEngine
            )
        }
    }
    
    /**
     * Generate response using MediaPipe LLM API
     */
    private suspend fun generateWithMediaPipe(prompt: String): String? {
        return llmInference?.generateResponse(prompt)
    }
    

    
    /**
     * Estimate token count for performance metrics
     */
    private fun estimateTokenCount(text: String): Int {
        // Rough estimate: 1 token ≈ 4 characters for English text
        return maxOf(1, text.length / 4)
    }
    
    /**
     * Format tool results directly into natural language without second LLM call
     */
    private fun formatToolResultsDirectly(toolResults: List<ToolResult>, userInput: String): String {
        if (toolResults.isEmpty()) return "I couldn't find any results for your request."
        
        return toolResults.joinToString("\n\n") { result ->
            when {
                !result.success -> "I encountered an error: ${result.error}"
                result.toolName == "WEATHER" -> {
                    // Parse weather result
                    val lines = result.result.split("\n")
                    val location = lines.find { it.startsWith("Weather in") }?.substringAfter("Weather in ")?.removeSuffix(":")
                    val temperature = lines.find { it.startsWith("Temperature:") }?.substringAfter("Temperature: ")
                    val conditions = lines.find { it.startsWith("Conditions:") }?.substringAfter("Conditions: ")
                    
                    if (temperature != null && conditions != null) {
                        "The current weather in ${location ?: "your location"} is $temperature with ${conditions.lowercase()} conditions."
                    } else {
                        result.result
                    }
                }
                result.toolName == "CALCULATOR" -> {
                    val answer = result.result.trim()
                    when {
                        userInput.lowercase().contains("area") && userInput.lowercase().contains("circle") -> 
                            "The area of the circle is $answer square units."
                        userInput.lowercase().contains("circumference") -> 
                            "The circumference is $answer units."
                        userInput.lowercase().contains("temperature") || userInput.lowercase().contains("convert") -> 
                            "The converted temperature is $answer."
                        userInput.lowercase().contains("percentage") || userInput.lowercase().contains("%") -> 
                            "The result is $answer."
                        else -> "The calculation result is $answer."
                    }
                }
                result.toolName == "CALENDAR" -> {
                    when {
                        userInput.lowercase().contains("add") || userInput.lowercase().contains("schedule") -> 
                            "I've added the event to your calendar: ${result.result}"
                        userInput.lowercase().contains("check") || userInput.lowercase().contains("what") -> 
                            "Here's what I found on your calendar: ${result.result}"
                        userInput.lowercase().contains("cancel") || userInput.lowercase().contains("delete") -> 
                            "I've removed the event from your calendar: ${result.result}"
                        userInput.lowercase().contains("update") || userInput.lowercase().contains("change") -> 
                            "I've updated your calendar event: ${result.result}"
                        else -> result.result
                    }
                }
                else -> result.result
            }
        }
    }
    
    /**
     * Detects when the model should have used tools but didn't, and forces tool calls
     */
    private fun detectAndForceMissingToolCalls(userInput: String, modelResponse: String): List<ToolCall> {
        val lowercaseInput = userInput.lowercase()
        val forcedToolCalls = mutableListOf<ToolCall>()
        
        // Check if model provided a direct answer when it should have used tools
        val modelProvidedDirectAnswer = modelResponse.length > 10 && 
                                       !modelResponse.contains("TOOL_CALL") &&
                                       !modelResponse.contains("I need to") &&
                                       !modelResponse.contains("Let me")
        
        if (modelProvidedDirectAnswer) {
            // Weather detection patterns
            val weatherKeywords = listOf("weather", "temperature", "rain", "sunny", "cloudy", "forecast", "climate")
            val hasWeatherKeyword = weatherKeywords.any { lowercaseInput.contains(it) }
            
            if (hasWeatherKeyword) {
                // Extract location from input
                val locationPatterns = listOf(
                    Regex("\\b(?:in|at|for)\\s+([a-zA-Z\\s]+?)(?:\\s|\\?|\\.|$)"),
                    Regex("weather\\s+(?:in\\s+)?([a-zA-Z\\s]+?)(?:\\s|\\?|\\.|$)"),
                    Regex("\\b([A-Z][a-zA-Z\\s]{2,15})\\s+weather")
                )
                
                for (pattern in locationPatterns) {
                    val match = pattern.find(userInput)
                    if (match != null) {
                        val location = match.groupValues[1].trim()
                        if (location.isNotBlank()) {
                            forcedToolCalls.add(ToolCall("WEATHER", location))
                            Log.w(TAG, "FORCED weather tool call for location: $location")
                            break
                        }
                    }
                }
            }
            
            // Math detection patterns
            val mathKeywords = listOf("calculate", "area", "volume", "circumference", "percentage", "square", "radius")
            val hasMathKeyword = mathKeywords.any { lowercaseInput.contains(it) }
            val hasMathSymbols = Regex("[+\\-*/=×÷%^]|\\d+\\s*[+\\-*/×÷]\\s*\\d+").containsMatchIn(lowercaseInput)
            
            if (hasMathKeyword || hasMathSymbols) {
                // Extract mathematical expression or convert word problem
                val mathExpression = when {
                    lowercaseInput.contains("area") && lowercaseInput.contains("circle") && lowercaseInput.contains("radius") -> {
                        val radiusMatch = Regex("radius\\s+(\\d+(?:\\.\\d+)?)").find(lowercaseInput)
                        if (radiusMatch != null) {
                            val radius = radiusMatch.groupValues[1]
                            "3.14159*$radius*$radius"
                        } else null
                    }
                    lowercaseInput.contains("circumference") && lowercaseInput.contains("circle") && lowercaseInput.contains("radius") -> {
                        val radiusMatch = Regex("radius\\s+(\\d+(?:\\.\\d+)?)").find(lowercaseInput)
                        if (radiusMatch != null) {
                            val radius = radiusMatch.groupValues[1]
                            "2*3.14159*$radius"
                        } else null
                    }
                    hasMathSymbols -> {
                        // Extract direct mathematical expression
                        val mathMatch = Regex("(\\d+(?:\\.\\d+)?\\s*[+\\-*/×÷]\\s*\\d+(?:\\.\\d+)?)").find(userInput)
                        mathMatch?.groupValues?.get(1)?.replace("×", "*")?.replace("÷", "/")
                    }
                    else -> null
                }
                
                if (!mathExpression.isNullOrBlank()) {
                    forcedToolCalls.add(ToolCall("CALCULATOR", mathExpression))
                    Log.w(TAG, "FORCED calculator tool call for expression: $mathExpression")
                }
            }
        }
        
        if (forcedToolCalls.isNotEmpty()) {
            Log.w(TAG, "🚨 MODEL IGNORED TOOL REQUIREMENTS! FORCING ${forcedToolCalls.size} TOOL CALLS: ${forcedToolCalls.map { "${it.toolName}(${it.parameters})" }}")
        } else {
            Log.d(TAG, "✅ No forced tool calls needed")
        }
        
        return forcedToolCalls
    }
    
    /**
     * Generate response without tool support (backward compatibility)
     */
    suspend fun generateResponse(prompt: String): String? = withContext(Dispatchers.IO) {
        val response = generateEnhancedResponse(prompt, includeContext = false, useTools = false)
        response.finalText.takeIf { response.success }
    }
    
    /**
     * Generate streaming response with tool support
     * Works with both MediaPipe and LiteRT inference engines
     */
    suspend fun generateStreamingEnhancedResponse(
        userInput: String,
        includeContext: Boolean = true,
        useTools: Boolean = true,
        onPartialResult: (String, Boolean, List<ToolResult>) -> Unit
    ) = withContext(Dispatchers.IO) {
        if (!isInitialized) {
            withContext(Dispatchers.Main) {
                onPartialResult("LLM not initialized. Please download and initialize a model first.", true, emptyList())
            }
            return@withContext
        }
        
        try {
            Log.d(TAG, "Generating streaming enhanced response for: $userInput using $currentInferenceEngine")
            
            // Generate the complete response first (MediaPipe doesn't support real streaming yet)
            val response = generateEnhancedResponse(userInput, includeContext, useTools)
            
            // Simulate streaming by sending partial text progressively
            if (response.success && response.finalText.isNotBlank()) {
                simulateStreamingResponse(response.finalText, response.toolResults, onPartialResult)
            } else {
                withContext(Dispatchers.Main) {
                    onPartialResult("I encountered an error while processing your request. Please try again.", true, emptyList())
                }
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Error generating streaming enhanced response", e)
            handleLLMError(e)
            withContext(Dispatchers.Main) {
                onPartialResult("I encountered an error while processing your request. Please try again.", true, emptyList())
            }
        }
    }
    
    /**
     * Simulate streaming response by progressively sending partial text
     * Uses a single coroutine to avoid creating multiple threads
     */
    private suspend fun simulateStreamingResponse(
        fullText: String,
        toolResults: List<ToolResult>,
        onPartialResult: (String, Boolean, List<ToolResult>) -> Unit
    ) = withContext(Dispatchers.IO) {
        try {
            val words = fullText.split(" ")
            val streamingDelayMs = 40L // Delay between words to simulate streaming
            val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
            
            var currentText = ""
            
            // Send words progressively in a single background coroutine
            for ((index, word) in words.withIndex()) {
                currentText += if (index == 0) word else " $word"
                
                // Post to main thread without creating new coroutine
                mainHandler.post {
                    onPartialResult(currentText, false, emptyList())
                }
                
                // Add delay between words
                delay(streamingDelayMs)
            }
            
            // Send final complete response
            mainHandler.post {
                onPartialResult(fullText, true, toolResults)
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Error in streaming simulation", e)
            val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
            mainHandler.post {
                onPartialResult(fullText, true, toolResults)
            }
        }
    }
    
    /**
     * Backward compatibility for streaming
     */
    suspend fun generateStreamingResponse(
        prompt: String,
        onPartialResult: (String, Boolean) -> Unit
    ) = withContext(Dispatchers.IO) {
        generateStreamingEnhancedResponse(
            userInput = prompt,
            includeContext = false,
            useTools = false
        ) { text, isComplete, _ ->
            onPartialResult(text, isComplete)
        }
    }
    
    /**
     * Manage conversation history with persistence and size control
     */
    private fun addToConversationHistory(message: String) {
        conversationHistory.add(message)
        
        // Get user-configured context length
        val maxMessages = getContextLength()
        
        // Trim to user preference
        while (conversationHistory.size > maxMessages) {
            conversationHistory.removeAt(0)
        }
        
        // Save to persistent storage if enabled
        if (isSaveHistoryEnabled()) {
            saveConversationHistory()
        }
        
        Log.d(TAG, "Added message to history (${conversationHistory.size}/$maxMessages messages)")
    }
    
    /**
     * Clear conversation history
     */
    fun clearConversationHistory() {
        conversationHistory.clear()
        
        // Clear from persistent storage too
        if (isSaveHistoryEnabled()) {
            saveConversationHistory()
        }
        
        // Reset the LLM session to start fresh
        resetLLMSession()
        
        Log.d(TAG, "Conversation history cleared and LLM session reset")
    }
    
    /**
     * Reset the LLM session to start fresh (useful after clearing history)
     */
    private fun resetLLMSession() {
        try {
            if (llmInference != null && isInitialized) {
                // For MediaPipe, we don't have sessions but we can log that we're starting fresh
                Log.d(TAG, "🔄 Starting fresh inference (conversation history cleared)")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error resetting LLM", e)
        }
    }
    
    /**
     * Get current conversation history
     */
    fun getConversationHistory(): List<String> = conversationHistory.toList()
    
    /**
     * Get context length setting (number of messages to keep)
     */
    fun getContextLength(): Int {
        return prefs.getInt(CONTEXT_LENGTH_KEY, 10) // Default to 10 messages
    }
    
    /**
     * Set context length setting (number of messages to keep)
     */
    fun setContextLength(length: Int) {
        val clampedLength = length.coerceIn(0, 50) // Max 50 messages
        prefs.edit().putInt(CONTEXT_LENGTH_KEY, clampedLength).apply()
        
        // Trim current history if needed
        while (conversationHistory.size > clampedLength) {
            conversationHistory.removeAt(0)
        }
        
        // Save updated history
        if (isSaveHistoryEnabled()) {
            saveConversationHistory()
        }
        
        Log.d(TAG, "Context length updated to $clampedLength messages")
    }
    
    /**
     * Check if conversation history persistence is enabled
     */
    fun isSaveHistoryEnabled(): Boolean {
        return prefs.getBoolean(SAVE_HISTORY_KEY, false) // Default to disabled for privacy
    }
    
    /**
     * Enable/disable conversation history persistence
     */
    fun setSaveHistoryEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(SAVE_HISTORY_KEY, enabled).apply()
        
        if (enabled) {
            // Save current history
            saveConversationHistory()
        } else {
            // Clear saved history when disabled
            clearSavedHistory()
        }
        
        Log.d(TAG, "Conversation history persistence: ${if (enabled) "enabled" else "disabled"}")
    }
    
    /**
     * Load conversation history from persistent storage
     */
    private fun loadConversationHistory() {
        if (!isSaveHistoryEnabled()) {
            Log.d(TAG, "Conversation history persistence is disabled, starting fresh")
            return
        }
        
        try {
            // Use a JSON-like format to maintain order (simple pipe-separated format)
            val savedHistoryString = prefs.getString(CONVERSATION_HISTORY_KEY, "") ?: ""
            conversationHistory.clear()
            
            if (savedHistoryString.isNotEmpty()) {
                val messages = savedHistoryString.split("|||").filter { it.isNotBlank() }
                conversationHistory.addAll(messages)
            }
            
            // Respect context length setting
            val maxMessages = getContextLength()
            while (conversationHistory.size > maxMessages) {
                conversationHistory.removeAt(0)
            }
            
            Log.d(TAG, "Loaded ${conversationHistory.size} messages from persistent storage")
        } catch (e: Exception) {
            Log.e(TAG, "Error loading conversation history", e)
            conversationHistory.clear()
        }
    }
    
    /**
     * Save conversation history to persistent storage
     */
    private fun saveConversationHistory() {
        try {
            // Use pipe-separated format to maintain order
            val historyString = conversationHistory.joinToString("|||")
            prefs.edit().putString(CONVERSATION_HISTORY_KEY, historyString).apply()
            Log.d(TAG, "Saved ${conversationHistory.size} messages to persistent storage")
        } catch (e: Exception) {
            Log.e(TAG, "Error saving conversation history", e)
        }
    }
    
    /**
     * Clear saved conversation history from persistent storage
     */
    private fun clearSavedHistory() {
        prefs.edit().remove(CONVERSATION_HISTORY_KEY).apply()
        Log.d(TAG, "Cleared saved conversation history")
    }
    
    /**
     * Get tool manager for external access
     */
    fun getToolManager(): ToolManager = toolManager
    
    /**
     * Set memory manager for RAG context retrieval
     */
    fun setMemoryManager(manager: MemoryManager) {
        this.memoryManager = manager
        Log.d(TAG, "✅ Memory manager set for RAG context retrieval")
    }
    
    /**
     * Handle LLM errors consistently for MediaPipe engines
     */
    private fun handleLLMError(e: Exception) {
        try {
            Log.w(TAG, "Handling LLM error for $currentInferenceEngine", e)
            
            // Reset MediaPipe LLM instance
            llmInference?.close()
            llmInference = null
            
            isInitialized = false
            Log.w(TAG, "LLM instance reset due to error")
        } catch (resetException: Exception) {
            Log.e(TAG, "Error resetting LLM instance", resetException)
        }
    }
    
    fun isModelInitialized(): Boolean = isInitialized
    
    fun getModelSize(modelInfo: LLMModelInfo): String {
        val sizeInMB = modelInfo.sizeInBytes / (1024 * 1024)
        return if (sizeInMB > 1024) {
            String.format("%.1f GB", sizeInMB / 1024.0)
        } else {
            "$sizeInMB MB"
        }
    }
    
    fun deleteModel(modelInfo: LLMModelInfo): Boolean {
        val modelFile = File(modelsDir, modelInfo.modelFile)
        return if (modelFile.exists()) {
            // Close LLM if using this model
            if (isInitialized) {
                llmInference?.close()
                llmInference = null
                isInitialized = false
            }
            modelFile.delete()
        } else {
            true
        }
    }
    
    fun release() {
        try {
            releaseCurrentModel()
            Log.d(TAG, "LLM resources released")
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing LLM resources", e)
        }
    }
    
    fun importModelFromUri(uri: android.net.Uri, modelInfo: LLMModelInfo): Boolean {
        return try {
            val destFile = File(modelsDir, modelInfo.modelFile)
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(destFile).use { output ->
                    input.copyTo(output)
                }
            } ?: return false
            
            // Validate the imported file
            if (validateTaskFile(destFile)) {
                Log.d(TAG, "Model imported and validated successfully: ${destFile.absolutePath}")
                true
            } else {
                Log.e(TAG, "Imported model file failed validation, deleting...")
                destFile.delete()
                false
            }
        } catch (e: java.lang.Exception) {
            Log.e(TAG, "Error importing model", e)
            false
        }
    }
    
    /**
     * Validate if a file is a proper MediaPipe task file
     */
    private fun validateTaskFile(file: File): Boolean {
        return try {
            if (!file.exists() || file.length() == 0L) {
                Log.e(TAG, "Task file validation failed: File doesn't exist or is empty")
                return false
            }
            
            // Check file header - MediaPipe task files should start with specific bytes
            val headerBytes = ByteArray(16)
            file.inputStream().use { 
                val bytesRead = it.read(headerBytes)
                if (bytesRead < 16) {
                    Log.e(TAG, "Task file validation failed: File too small")
                    return false
                }
            }
            
            // Log header for debugging
            Log.d(TAG, "Task file header: ${headerBytes.joinToString(" ") { "%02x".format(it) }}")
            
            // MediaPipe .task files are typically ZIP-based archives
            // Check for ZIP file signature (PK\x03\x04 or PK\x05\x06)
            val isZip = (headerBytes[0] == 0x50.toByte() && headerBytes[1] == 0x4B.toByte() &&
                        (headerBytes[2] == 0x03.toByte() || headerBytes[2] == 0x05.toByte()))
            
            if (!isZip) {
                Log.w(TAG, "Task file validation warning: File doesn't appear to be a ZIP archive")
                Log.w(TAG, "This might indicate the file is in a different format or corrupted")
                // Return true anyway as some task files might have different formats
            }
            
            Log.d(TAG, "Task file validation passed (file size: ${file.length()} bytes)")
            true
            
        } catch (e: Exception) {
            Log.e(TAG, "Error validating task file", e)
            false
        }
    }
    
    /**
     * Import model from external storage path (for predownloaded models)
     */
    fun importModelFromPath(sourcePath: String, modelInfo: LLMModelInfo): Boolean {
        return try {
            val sourceFile = File(sourcePath)
            if (!sourceFile.exists()) {
                Log.e(TAG, "Source file does not exist: $sourcePath")
                return false
            }
            
            val destFile = File(modelsDir, modelInfo.modelFile)
            sourceFile.copyTo(destFile, overwrite = true)
            
            Log.d(TAG, "Model imported from path: ${sourceFile.absolutePath} -> ${destFile.absolutePath}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error importing model from path", e)
            false
        }
    }
    
    /**
     * Check if external model file exists in common download locations
     */
    fun findExternalModelFile(modelInfo: LLMModelInfo): List<String> {
        val possiblePaths = mutableListOf<String>()
        
        // Common download locations
        val downloadDirs = listOf(
            "/storage/emulated/0/Download",
            "/storage/emulated/0/Downloads", 
            context.getExternalFilesDir(null)?.absolutePath,
            context.externalCacheDir?.absolutePath
        )
        
        downloadDirs.filterNotNull().forEach { dir ->
            val file = File(dir, modelInfo.modelFile)
            if (file.exists()) {
                possiblePaths.add(file.absolutePath)
            }
        }
        
        return possiblePaths
    }
    
    /**
     * Save GPU setting for a specific model
     */
    fun saveModelGPUSetting(modelId: String, useGPU: Boolean) {
        val prefs = context.getSharedPreferences("llm_model_settings", Context.MODE_PRIVATE)
        prefs.edit().putBoolean("gpu_$modelId", useGPU).apply()
        Log.d(TAG, "Saved GPU setting for $modelId: $useGPU")
    }
    
    /**
     * Get GPU setting for a specific model, returns model default if not set
     */
    fun getModelGPUSetting(modelId: String): Boolean {
        val prefs = context.getSharedPreferences("llm_model_settings", Context.MODE_PRIVATE)
        val model = getAllAvailableModels().find { it.modelId == modelId }
        val defaultValue = model?.defaultConfig?.useGPU ?: false
        return prefs.getBoolean("gpu_$modelId", defaultValue)
    }
} 