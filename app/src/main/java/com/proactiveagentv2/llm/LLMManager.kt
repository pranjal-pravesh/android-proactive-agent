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