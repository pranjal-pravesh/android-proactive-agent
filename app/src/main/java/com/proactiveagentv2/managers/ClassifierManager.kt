package com.proactiveagentv2.managers

import android.content.Context
import android.util.Log
import com.proactiveagentv2.classifiers.MobileBERTClassifier
import com.proactiveagentv2.ui.MainViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Manages MobileBERT classifiers for actionable and contextable text classification
 */
class ClassifierManager(
    private val context: Context,
    private val viewModel: MainViewModel,
    private val coroutineScope: CoroutineScope
) {
    
    data class ClassificationResults(
        val actionableResult: MobileBERTClassifier.ClassificationResult?,
        val contextableResult: MobileBERTClassifier.ClassificationResult?,
        val isActionable: Boolean,
        val isContextable: Boolean,
        val totalProcessingTimeMs: Long
    )
    
    private var actionableClassifier: MobileBERTClassifier? = null
    private var contextableClassifier: MobileBERTClassifier? = null
    private var isInitialized = false
    
    // Callbacks
    var onClassificationComplete: ((ClassificationResults) -> Unit)? = null
    
    companion object {
        private const val TAG = "ClassifierManager"
    }
    
    /**
     * Initialize both classifiers
     */
    fun initialize(): Boolean {
        return try {
            Log.d(TAG, "Initializing classifiers...")
            
            // Initialize actionable classifier
            actionableClassifier = MobileBERTClassifier(context, MobileBERTClassifier.ClassifierType.ACTIONABLE)
            val actionableInit = actionableClassifier?.initialize() ?: false
            
            // Initialize contextable classifier
            contextableClassifier = MobileBERTClassifier(context, MobileBERTClassifier.ClassifierType.CONTEXTABLE)
            val contextableInit = contextableClassifier?.initialize() ?: false
            
            // Consider initialization successful if at least one classifier works
            // But for now, since both are disabled, this will be false
            isInitialized = actionableInit && contextableInit
            
            if (isInitialized) {
                Log.d(TAG, "Both classifiers initialized successfully")
            } else {
                Log.w(TAG, "Classifiers initialization failed - Actionable: $actionableInit, Contextable: $contextableInit")
                Log.w(TAG, "App will continue without classification features")
            }
            
            isInitialized
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing classifiers", e)
            isInitialized = false
            false
        }
    }
    
    /**
     * Classify text using both classifiers
     */
    fun classifyText(text: String) {
        if (!isInitialized) {
            Log.e(TAG, "Classifiers not initialized")
            return
        }
        
        if (text.isBlank()) {
            Log.w(TAG, "Empty text provided for classification")
            return
        }
        
        coroutineScope.launch {
            try {
                val startTime = System.currentTimeMillis()
                
                // Run classifications sequentially to avoid race conditions
                val actionableResult = withContext(Dispatchers.Default) {
                    synchronized(this@ClassifierManager) {
                        actionableClassifier?.classify(text)
                    }
                }
                
                val contextableResult = withContext(Dispatchers.Default) {
                    synchronized(this@ClassifierManager) {
                        contextableClassifier?.classify(text)
                    }
                }
                
                val totalTime = System.currentTimeMillis() - startTime
                
                val results = ClassificationResults(
                    actionableResult = actionableResult,
                    contextableResult = contextableResult,
                    isActionable = actionableResult?.isPositive ?: false,
                    isContextable = contextableResult?.isPositive ?: false,
                    totalProcessingTimeMs = totalTime
                )
                
                withContext(Dispatchers.Main) {
                    // Update ViewModel with classification results
                    viewModel.updateClassificationResults(results)
                    
                    // Notify callback
                    onClassificationComplete?.invoke(results)
                    
                    Log.d(TAG, "Classification completed - Text: \"$text\", " +
                              "Actionable: ${results.isActionable} (${actionableResult?.confidence ?: 0f}), " +
                              "Contextable: ${results.isContextable} (${contextableResult?.confidence ?: 0f}), " +
                              "Total time: ${totalTime}ms")
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "Error during text classification", e)
                withContext(Dispatchers.Main) {
                    viewModel.updateStatus("Classification error: ${e.message}")
                }
            }
        }
    }
    
        /**
     * Check if text is actionable (synchronous version for quick checks)
     */
    fun isTextActionable(text: String): Boolean {
        return if (isInitialized && !text.isBlank()) {
            try {
                synchronized(this) {
                    actionableClassifier?.classify(text)?.isPositive ?: false
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in actionable classification", e)
                // Default to true (actionable) when classifier fails to avoid blocking LLM
                true
            }
        } else {
            // Default to true (actionable) when not initialized to avoid blocking LLM
            Log.d(TAG, "Classifier not initialized, defaulting to actionable=true")
            true
        }
    }

    /**
     * Check if text is contextable (synchronous version for quick checks)
     */
    fun isTextContextable(text: String): Boolean {
        return if (isInitialized && !text.isBlank()) {
            try {
                synchronized(this) {
                    contextableClassifier?.classify(text)?.isPositive ?: false
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in contextable classification", e)
                // Default to false (not contextable) when classifier fails
                false
            }
        } else {
            // Default to false (not contextable) when not initialized
            Log.d(TAG, "Classifier not initialized, defaulting to contextable=false")
            false
        }
    }
    
    /**
     * Get classifier status
     */
    fun getClassifierStatus(): String {
        return when {
            !isInitialized -> "Classifiers not initialized"
            actionableClassifier?.isInitialized() != true -> "Actionable classifier failed"
            contextableClassifier?.isInitialized() != true -> "Contextable classifier failed"
            else -> "Classifiers ready"
        }
    }
    
    /**
     * Check if classifiers are initialized
     */
    fun isInitialized(): Boolean = isInitialized
    
    /**
     * Release resources
     */
    fun release() {
        try {
            actionableClassifier?.release()
            contextableClassifier?.release()
            
            actionableClassifier = null
            contextableClassifier = null
            isInitialized = false
            
            Log.d(TAG, "ClassifierManager released")
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing ClassifierManager", e)
        }
    }
} 