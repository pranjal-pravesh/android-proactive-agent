package com.proactiveagentv2.llm

import android.content.Context
import android.util.Log
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.proactiveagentv2.tools.ToolManager
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
    val downloadUrl: String,
    val defaultConfig: LLMConfig
)

data class LLMConfig(
    val topK: Int = 40,
    val topP: Float = 0.95f,
    val temperature: Float = 1.0f,
    val maxTokens: Int = 1024
)

enum class ModelDownloadStatus {
    NOT_DOWNLOADED,
    DOWNLOADING,
    DOWNLOADED,
    ERROR
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
    val error: String? = null
)

class LLMManager(private val context: Context) {
    private var llmInference: LlmInference? = null
    private var isInitialized = false
    private val modelsDir = File(context.getExternalFilesDir(null), "llm_models")
    
    // New components for enhanced functionality
    private val promptBuilder = PromptBuilder()
    private val toolManager = ToolManager()
    private val conversationHistory = mutableListOf<String>()
    
    companion object {
        private const val TAG = "LLMManager"
        private const val MAX_CONVERSATION_HISTORY = 10
        
        val QWEN_MODEL = LLMModelInfo(
            name = "Qwen2.5-1.5B-Instruct q8",
            modelId = "litert-community/Qwen2.5-1.5B-Instruct",
            modelFile = "Qwen2.5-1.5B-Instruct_multi-prefill-seq_q8_ekv1280.task",
            description = "A variant of Qwen/Qwen2.5-1.5B-Instruct with 8-bit quantization ready for deployment on Android",
            sizeInBytes = 1625493432, // ~1.6GB
            downloadUrl = "https://huggingface.co/litert-community/Qwen2.5-1.5B-Instruct/resolve/main/Qwen2.5-1.5B-Instruct_multi-prefill-seq_q8_ekv1280.task",
            defaultConfig = LLMConfig(
                topK = 40,
                topP = 0.95f,
                temperature = 1.0f,
                maxTokens = 1024
            )
        )
    }
    
    init {
        if (!modelsDir.exists()) {
            modelsDir.mkdirs()
        }
        Log.d(TAG, "LLMManager initialized with enhanced tool support")
        Log.d(TAG, toolManager.getToolDescriptions())
    }
    
    fun getModelStatus(modelInfo: LLMModelInfo): ModelDownloadStatus {
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
        try {
            Log.d(TAG, "Starting download of ${modelInfo.name}")
            
            val modelFile = File(modelsDir, modelInfo.modelFile)
            val tempFile = File(modelsDir, "${modelInfo.modelFile}.tmp")
            
            // Clean previous files
            if (modelFile.exists()) modelFile.delete()
            if (tempFile.exists()) tempFile.delete()
            
            val url = URL(modelInfo.downloadUrl)
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
    
    suspend fun initializeModel(modelInfo: LLMModelInfo): Boolean = withContext(Dispatchers.IO) {
        try {
            if (getModelStatus(modelInfo) != ModelDownloadStatus.DOWNLOADED) {
                Log.e(TAG, "Model not downloaded: ${modelInfo.name}")
                return@withContext false
            }
            
            val modelFile = File(modelsDir, modelInfo.modelFile)
            Log.d(TAG, "Initializing LLM with model: ${modelFile.absolutePath}")
            
            val options = LlmInference.LlmInferenceOptions.builder()
                .setModelPath(modelFile.absolutePath)
                .setMaxTokens(1280)
                .setMaxTopK(40)
                .build()
            
            // Release existing instance
            llmInference?.close()
            
            llmInference = LlmInference.createFromOptions(context, options)
            isInitialized = true
            
            Log.d(TAG, "LLM initialized successfully")
            true
            
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing LLM", e)
            isInitialized = false
            false
        }
    }
    
    /**
     * Enhanced response generation with tool calling support
     */
    suspend fun generateEnhancedResponse(
        userInput: String,
        includeContext: Boolean = true,
        useTools: Boolean = true
    ): LLMResponse = withContext(Dispatchers.IO) {
        if (!isInitialized || llmInference == null) {
            Log.w(TAG, "LLM not initialized, cannot generate response")
            return@withContext LLMResponse(
                originalText = "",
                finalText = "LLM not initialized. Please download and initialize a model first.",
                success = false,
                error = "LLM not initialized"
            )
        }
        
        try {
            Log.d(TAG, "Generating enhanced response for: $userInput")
            
            // Build the initial prompt using ChatML format
            val systemPrompt = if (useTools) promptBuilder.buildSystemPrompt() else buildBasicSystemPrompt()
            val chatMLPrompt = promptBuilder.buildChatMLPrompt(
                systemPrompt = systemPrompt,
                userInput = userInput,
                conversationHistory = if (includeContext) conversationHistory else emptyList(),
                includeContext = includeContext
            )
            
            Log.d(TAG, "ChatML prompt length: ${chatMLPrompt.length} characters")
            Log.v(TAG, "ChatML prompt preview: ${chatMLPrompt.take(200)}...")
            
            // Validate and log ChatML format for debugging
            val isValidChatML = promptBuilder.validateChatMLFormat(chatMLPrompt)
            if (!isValidChatML) {
                Log.w(TAG, "Invalid ChatML format detected!")
            }
            
            // Log complete prompt in debug mode
            promptBuilder.logChatMLPrompt(chatMLPrompt)
            
            // Generate initial response
            val initialResponse = llmInference!!.generateResponse(chatMLPrompt)
            if (initialResponse.isNullOrBlank()) {
                return@withContext LLMResponse(
                    originalText = "",
                    finalText = "Sorry, I couldn't generate a response. Please try again.",
                    success = false,
                    error = "Empty response from LLM"
                )
            }
            
            Log.d(TAG, "Initial LLM response: $initialResponse")
            
            // Check for tool calls if tools are enabled
            if (useTools) {
                val toolCalls = promptBuilder.parseToolCalls(initialResponse)
                
                if (toolCalls.isNotEmpty()) {
                    Log.d(TAG, "Found ${toolCalls.size} tool calls")
                    
                    // Execute tools
                    val toolResults = toolManager.executeTools(toolCalls)
                    
                    // Generate final response with tool results using ChatML
                    val chatMLPromptWithResults = promptBuilder.buildChatMLPromptWithToolResults(
                        systemPrompt = systemPrompt,
                        userInput = userInput,
                        toolResults = toolResults,
                        conversationHistory = if (includeContext) conversationHistory else emptyList(),
                        includeContext = includeContext
                    )
                    
                    Log.v(TAG, "ChatML prompt with tools preview: ${chatMLPromptWithResults.take(300)}...")
                    val finalResponse = llmInference!!.generateResponse(chatMLPromptWithResults)
                    
                    // Update conversation history
                    addToConversationHistory("User: $userInput")
                    addToConversationHistory("Assistant: ${finalResponse ?: initialResponse}")
                    
                    return@withContext LLMResponse(
                        originalText = initialResponse,
                        finalText = finalResponse ?: initialResponse,
                        toolResults = toolResults,
                        hasToolCalls = true,
                        success = true
                    )
                }
            }
            
            // No tool calls - return original response
            addToConversationHistory("User: $userInput")
            addToConversationHistory("Assistant: $initialResponse")
            
            LLMResponse(
                originalText = initialResponse,
                finalText = initialResponse,
                success = true
            )
            
        } catch (e: Exception) {
            Log.e(TAG, "Error generating enhanced response", e)
            handleLLMError(e)
            LLMResponse(
                originalText = "",
                finalText = "I encountered an error while processing your request. Please try again.",
                success = false,
                error = e.message
            )
        }
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
     */
    suspend fun generateStreamingEnhancedResponse(
        userInput: String,
        includeContext: Boolean = true,
        useTools: Boolean = true,
        onPartialResult: (String, Boolean, List<ToolResult>) -> Unit
    ) = withContext(Dispatchers.IO) {
        if (!isInitialized || llmInference == null) {
            withContext(Dispatchers.Main) {
                onPartialResult("LLM not initialized. Please download and initialize a model first.", true, emptyList())
            }
            return@withContext
        }
        
        try {
            Log.d(TAG, "Generating streaming enhanced response for: $userInput")
            
            // For tool-enabled responses, we need to process synchronously for now
            // due to MediaPipe LLM API limitations with streaming and tool calls
            if (useTools) {
                val response = generateEnhancedResponse(userInput, includeContext, useTools)
                withContext(Dispatchers.Main) {
                    onPartialResult(response.finalText, true, response.toolResults)
                }
            } else {
                // Simple streaming for non-tool responses using ChatML
                val systemPrompt = buildBasicSystemPrompt()
                val chatMLPrompt = promptBuilder.buildChatMLPrompt(
                    systemPrompt = systemPrompt,
                    userInput = userInput,
                    conversationHistory = if (includeContext) conversationHistory else emptyList(),
                    includeContext = includeContext
                )
                
                val result = llmInference!!.generateResponse(chatMLPrompt)
                
                withContext(Dispatchers.Main) {
                    onPartialResult(result ?: "No response generated", true, emptyList())
                }
                
                // Update conversation history
                addToConversationHistory("User: $userInput")
                addToConversationHistory("Assistant: ${result ?: ""}")
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
     * Manage conversation history
     */
    private fun addToConversationHistory(message: String) {
        conversationHistory.add(message)
        if (conversationHistory.size > MAX_CONVERSATION_HISTORY) {
            conversationHistory.removeAt(0)
        }
    }
    
    /**
     * Clear conversation history
     */
    fun clearConversationHistory() {
        conversationHistory.clear()
        Log.d(TAG, "Conversation history cleared")
    }
    
    /**
     * Get current conversation history
     */
    fun getConversationHistory(): List<String> = conversationHistory.toList()
    
    /**
     * Get tool manager for external access
     */
    fun getToolManager(): ToolManager = toolManager
    
    /**
     * Build basic system prompt without tools
     */
    private fun buildBasicSystemPrompt(): String {
        return """
You are a helpful and knowledgeable voice assistant.
You can answer questions about a wide range of topics, including geography, science, history, mathematics, and general knowledge.
Use prior context and conversation history only if it's relevant.

Your responses should be brief, to the point, and easy to understand.
Avoid unnecessary elaboration. If a short answer is sufficient, prefer it.
Only provide longer explanations when the topic genuinely requires it.

Guidelines:
- Provide accurate, helpful answers to user questions
- Avoid showing your reasoning steps or internal thoughts
- Be friendly and informative, but stay concise
- Answer independently — don't rely on earlier questions unless context is explicitly provided
- If you truly don't know something, say so clearly
- Never refuse to answer reasonable questions
        """.trimIndent()
    }
    
    /**
     * Handle LLM errors consistently
     */
    private fun handleLLMError(e: Exception) {
        try {
            // Try to reset the LLM instance if it's corrupted
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
            llmInference?.close()
            llmInference = null
            isInitialized = false
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
            true
        } catch (e: java.lang.Exception) {
            Log.e(TAG, "Error importing model", e)
            false
        }
    }
} 