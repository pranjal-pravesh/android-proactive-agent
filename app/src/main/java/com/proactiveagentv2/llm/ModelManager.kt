package com.proactiveagentv2.llm

import android.content.Context
import android.util.Log
import kotlinx.coroutines.*

/**
 * Helper class to manage multiple LLM models and provide utilities for model switching
 */
class ModelManager(private val context: Context) {
    private val llmManager = LLMManager(context)
    
    companion object {
        private const val TAG = "ModelManager"
        
        // Recommended configurations for different use cases
        val SPEED_CONFIG = LLMConfig(
            topK = 20,
            topP = 0.8f,
            temperature = 0.5f,
            maxTokens = 512,
            useGPU = true
        )
        
        val QUALITY_CONFIG = LLMConfig(
            topK = 40,
            topP = 0.95f,
            temperature = 0.7f,
            maxTokens = 2048,
            useGPU = true
        )
        
        val BALANCED_CONFIG = LLMConfig(
            topK = 30,
            topP = 0.9f,
            temperature = 0.6f,
            maxTokens = 1024,
            useGPU = true
        )
    }
    
    /**
     * Get the underlying LLMManager
     */
    fun getLLMManager(): LLMManager = llmManager
    
    /**
     * Initialize the best available model based on user preferences
     */
    suspend fun initializeBestAvailableModel(preferGPU: Boolean = true): LLMModelInfo? {
        val availableModels = getAvailableModelsForInitialization()
        
        if (availableModels.isEmpty()) {
            Log.w(TAG, "No models available for initialization")
            return null
        }
        
        // Prioritize models based on GPU support if requested
        val sortedModels = if (preferGPU) {
            availableModels.sortedByDescending { it.supportsGPU }
        } else {
            availableModels
        }
        
        for (model in sortedModels) {
            val config = if (preferGPU && model.supportsGPU) {
                model.defaultConfig.copy(useGPU = true)
            } else {
                model.defaultConfig.copy(useGPU = false)
            }
            
            val success = llmManager.initializeModel(model, config)
            if (success) {
                Log.d(TAG, "Successfully initialized model: ${model.name}")
                return model
            } else {
                Log.w(TAG, "Failed to initialize model: ${model.name}")
            }
        }
        
        Log.e(TAG, "Failed to initialize any available model")
        return null
    }
    
    /**
     * Get models that are downloaded and ready for initialization
     */
    private fun getAvailableModelsForInitialization(): List<LLMModelInfo> {
        return LLMManager.getAllAvailableModels().filter { model ->
            val status = llmManager.getModelStatus(model)
            status == ModelDownloadStatus.DOWNLOADED || status == ModelDownloadStatus.LOCAL_ONLY
        }
    }
    
    /**
     * Switch to a specific model with optional configuration
     */
    suspend fun switchToModel(
        modelInfo: LLMModelInfo, 
        config: LLMConfig? = null,
        useRecommendedConfig: String? = null
    ): Boolean {
        val finalConfig = when {
            config != null -> config
            useRecommendedConfig == "speed" -> SPEED_CONFIG
            useRecommendedConfig == "quality" -> QUALITY_CONFIG
            useRecommendedConfig == "balanced" -> BALANCED_CONFIG
            else -> modelInfo.defaultConfig
        }
        
        return llmManager.initializeModel(modelInfo, finalConfig)
    }
    
    /**
     * Get setup instructions for Gemma model
     */
    fun getGemmaSetupInstructions(): String {
        return """
        Gemma 3N Setup Instructions:
        
        1. Download the model file 'gemma-3n-E2B-it-litert-preview.task' from Google AI
        2. Place it in one of these locations:
           - Downloads folder (/storage/emulated/0/Download/)
           - Device Downloads (/storage/emulated/0/Downloads/)
        3. Use the 'Auto-find' button or 'Import .task file' to load it
        4. The model supports GPU acceleration for faster inference
        
        Note: This model requires manual download due to Google's authentication requirements.
        """.trimIndent()
    }
    
    /**
     * Check device GPU capabilities
     */
    fun checkGPUCapabilities(): GPUInfo {
        // Simple GPU detection - could be enhanced with more detailed checks
        return try {
            GPUInfo(
                hasGPU = true, // Most modern Android devices have GPU
                supportsLiteRT = true, // LiteRT generally supports GPU on most devices
                recommendGPU = true
            )
        } catch (e: Exception) {
            Log.w(TAG, "Error checking GPU capabilities", e)
            GPUInfo(hasGPU = false, supportsLiteRT = false, recommendGPU = false)
        }
    }
    
    /**
     * Get model recommendations based on device capabilities
     */
    fun getModelRecommendations(): ModelRecommendations {
        val gpuInfo = checkGPUCapabilities()
        val availableModels = getAvailableModelsForInitialization()
        
        val recommendedModel = when {
            availableModels.any { it.modelType == ModelType.GEMMA } && gpuInfo.recommendGPU -> {
                availableModels.first { it.modelType == ModelType.GEMMA }
            }
            availableModels.any { it.modelType == ModelType.QWEN } -> {
                availableModels.first { it.modelType == ModelType.QWEN }
            }
            else -> null
        }
        
        return ModelRecommendations(
            recommendedModel = recommendedModel,
            preferGPU = gpuInfo.recommendGPU,
            availableModels = availableModels,
            setupInstructions = if (availableModels.isEmpty()) {
                "No models available. Please download or import a model first."
            } else {
                "Ready to initialize with available models."
            }
        )
    }
    
    /**
     * Release resources
     */
    fun release() {
        llmManager.release()
    }
}

/**
 * GPU capability information
 */
data class GPUInfo(
    val hasGPU: Boolean,
    val supportsLiteRT: Boolean,
    val recommendGPU: Boolean
)

/**
 * Model recommendations based on device and availability
 */
data class ModelRecommendations(
    val recommendedModel: LLMModelInfo?,
    val preferGPU: Boolean,
    val availableModels: List<LLMModelInfo>,
    val setupInstructions: String
) 