package com.proactiveagentv2.llm

import android.content.Context
import android.util.Log
import com.google.mediapipe.tasks.genai.llminference.LlmInference
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

class LLMManager(private val context: Context) {
    private var llmInference: LlmInference? = null
    private var isInitialized = false
    private val modelsDir = File(context.getExternalFilesDir(null), "llm_models")
    
    companion object {
        private const val TAG = "LLMManager"
        
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
    }
    
    fun getModelStatus(modelInfo: LLMModelInfo): ModelDownloadStatus {
        val modelFile = File(modelsDir, modelInfo.modelFile)
        return when {
            !modelFile.exists() -> ModelDownloadStatus.NOT_DOWNLOADED
            modelFile.length() != modelInfo.sizeInBytes -> ModelDownloadStatus.ERROR
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
            
            // Delete existing files
            if (modelFile.exists()) modelFile.delete()
            if (tempFile.exists()) tempFile.delete()
            
            val url = URL(modelInfo.downloadUrl)
            val connection = url.openConnection() as HttpURLConnection
            connection.connect()
            
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                Log.e(TAG, "HTTP error: ${connection.responseCode}")
                return@withContext false
            }
            
            val fileLength = connection.contentLength.toLong()
            Log.d(TAG, "Expected file size: $fileLength bytes")
            
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
                        
                        val percentage = if (fileLength > 0) {
                            (totalDownloaded * 100 / fileLength).toInt()
                        } else 0
                        
                        withContext(Dispatchers.Main) {
                            onProgress(DownloadProgress(totalDownloaded, fileLength, percentage))
                        }
                    }
                }
            }
            
            // Verify download
            if (tempFile.length() == modelInfo.sizeInBytes) {
                tempFile.renameTo(modelFile)
                Log.d(TAG, "Model downloaded successfully: ${modelFile.absolutePath}")
                true
            } else {
                Log.e(TAG, "Download verification failed. Expected: ${modelInfo.sizeInBytes}, Got: ${tempFile.length()}")
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
    
    suspend fun generateResponse(prompt: String): String? = withContext(Dispatchers.IO) {
        if (!isInitialized || llmInference == null) {
            Log.e(TAG, "LLM not initialized")
            return@withContext null
        }
        
        try {
            Log.d(TAG, "Generating response for prompt: $prompt")
            val result = llmInference!!.generateResponse(prompt)
            Log.d(TAG, "Generated response: $result")
            result
        } catch (e: Exception) {
            Log.e(TAG, "Error generating response", e)
            null
        }
    }
    
    suspend fun generateStreamingResponse(
        prompt: String,
        onPartialResult: (String, Boolean) -> Unit
    ) = withContext(Dispatchers.IO) {
        if (!isInitialized || llmInference == null) {
            Log.e(TAG, "LLM not initialized")
            return@withContext
        }
        
        try {
            Log.d(TAG, "Generating streaming response for prompt: $prompt")
            
            // For now, use sync response as MediaPipe async API might be different
            val result = llmInference!!.generateResponse(prompt)
            withContext(Dispatchers.Main) {
                onPartialResult(result ?: "", true)
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Error generating streaming response", e)
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
} 