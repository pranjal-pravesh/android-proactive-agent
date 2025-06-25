package com.proactiveagentv2.managers

import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import java.io.File
import java.util.*
import org.json.JSONObject
import org.json.JSONArray
import java.text.SimpleDateFormat

/**
 * MemoryManager handles memory storage and retrieval using a simple file-based approach
 * This is a fallback implementation while the AI Edge RAG SDK is under development
 * Uses JSON file storage for persistence with basic text matching for retrieval
 */
class MemoryManager(private val context: Context) {
    
    private val memories = mutableListOf<MemoryItem>()
    private var isInitialized = false
    private val memoryFile: File by lazy { 
        File(context.filesDir, "memory_storage.json") 
    }
    
    companion object {
        private const val TAG = "MemoryManager"
    }
    
    /**
     * Initialize the memory system with file-based storage
     */
    suspend fun initialize(): Boolean = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "🧠 Initializing simple memory system...")
            
            // Load existing memories from file
            loadMemoriesFromFile()
            
            isInitialized = true
            Log.i(TAG, "✅ Memory system initialized successfully")
            Log.d(TAG, "📄 Memory file: ${memoryFile.absolutePath}")
            Log.d(TAG, "💾 Loaded ${memories.size} existing memories")
            
            return@withContext true
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to initialize memory system", e)
            isInitialized = false
            return@withContext false
        }
    }
    
    /**
     * Store a text chunk in memory (only for CONTEXTABLE transcriptions)
     */
    suspend fun storeMemory(text: String, metadata: Map<String, String> = emptyMap()): Boolean = withContext(Dispatchers.IO) {
        if (!isInitialized) {
            Log.w(TAG, "⚠️ Memory system not initialized, cannot store memory")
            return@withContext false
        }
        
        try {
            // Add timestamp and type metadata
            val enrichedMetadata = metadata.toMutableMap().apply {
                put("timestamp", System.currentTimeMillis().toString())
                put("type", "contextable_transcription")
            }
            
            val memoryItem = MemoryItem(
                text = text,
                similarity = 1.0f, // Max similarity for stored items
                metadata = enrichedMetadata,
                timestamp = System.currentTimeMillis()
            )
            
            memories.add(memoryItem)
            saveMemoriesToFile()
            
            Log.d(TAG, "💾 Stored memory: ${text.take(50)}...")
            return@withContext true
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to store memory", e)
            return@withContext false
        }
    }
    
    /**
     * Retrieve relevant memories based on query text using simple text matching
     */
    suspend fun retrieveMemories(
        query: String, 
        maxResults: Int = 3,
        minSimilarity: Float = 0.1f
    ): List<MemoryItem> = withContext(Dispatchers.IO) {
        if (!isInitialized) {
            Log.w(TAG, "⚠️ Memory system not initialized, cannot retrieve memories")
            return@withContext emptyList()
        }
        
        try {
            Log.d(TAG, "🔍 Retrieving memories for query: ${query.take(50)}...")
            
            val queryWords = query.lowercase().split("\\s+".toRegex())
            
            // Simple text matching with basic scoring
            val scoredMemories = memories.map { memory ->
                val memoryWords = memory.text.lowercase().split("\\s+".toRegex())
                val matchCount = queryWords.count { queryWord ->
                    memoryWords.any { memoryWord -> 
                        memoryWord.contains(queryWord) || queryWord.contains(memoryWord)
                    }
                }
                val similarity = if (queryWords.isNotEmpty()) {
                    matchCount.toFloat() / queryWords.size
                } else {
                    0f
                }
                
                memory.copy(similarity = similarity)
            }
            
            val relevantMemories = scoredMemories
                .filter { it.similarity >= minSimilarity }
                .sortedByDescending { it.similarity }
                .take(maxResults)
            
            Log.d(TAG, "📚 Retrieved ${relevantMemories.size} relevant memories")
            relevantMemories.forEach { item ->
                Log.d(TAG, "  📄 Memory (similarity: ${String.format("%.3f", item.similarity)}): ${item.text.take(50)}...")
            }
            
            return@withContext relevantMemories
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to retrieve memories", e)
            return@withContext emptyList()
        }
    }
    
    /**
     * Get all stored memories for viewing
     */
    suspend fun getAllMemories(): List<MemoryItem> = withContext(Dispatchers.IO) {
        if (!isInitialized) {
            Log.w(TAG, "⚠️ Memory system not initialized, cannot get memories")
            return@withContext emptyList()
        }
        
        return@withContext memories.sortedByDescending { it.timestamp }
    }
    
    /**
     * Clear all stored memories
     */
    suspend fun clearAllMemories(): Boolean = withContext(Dispatchers.IO) {
        if (!isInitialized) {
            Log.w(TAG, "⚠️ Memory system not initialized, cannot clear memories")
            return@withContext false
        }
        
        try {
            memories.clear()
            saveMemoriesToFile()
            
            Log.i(TAG, "🗑️ All memories cleared successfully")
            return@withContext true
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to clear memories", e)
            return@withContext false
        }
    }
    
    /**
     * Load memories from JSON file
     */
    private fun loadMemoriesFromFile() {
        try {
            if (!memoryFile.exists()) {
                Log.d(TAG, "Memory file doesn't exist, starting fresh")
                return
            }
            
            val jsonContent = memoryFile.readText()
            if (jsonContent.isBlank()) {
                Log.d(TAG, "Memory file is empty, starting fresh")
                return
            }
            
            val jsonArray = JSONArray(jsonContent)
            memories.clear()
            
            for (i in 0 until jsonArray.length()) {
                val jsonItem = jsonArray.getJSONObject(i)
                val metadataJson = jsonItem.getJSONObject("metadata")
                val metadata = mutableMapOf<String, String>()
                
                val keys = metadataJson.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    metadata[key] = metadataJson.getString(key)
                }
                
                val memoryItem = MemoryItem(
                    text = jsonItem.getString("text"),
                    similarity = jsonItem.getDouble("similarity").toFloat(),
                    metadata = metadata,
                    timestamp = jsonItem.getLong("timestamp")
                )
                
                memories.add(memoryItem)
            }
            
            Log.d(TAG, "Loaded ${memories.size} memories from file")
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load memories from file", e)
            memories.clear()
        }
    }
    
    /**
     * Save memories to JSON file
     */
    private fun saveMemoriesToFile() {
        try {
            val jsonArray = JSONArray()
            
            memories.forEach { memory ->
                val jsonItem = JSONObject().apply {
                    put("text", memory.text)
                    put("similarity", memory.similarity.toDouble())
                    put("timestamp", memory.timestamp)
                    
                    val metadataJson = JSONObject()
                    memory.metadata.forEach { (key, value) ->
                        metadataJson.put(key, value)
                    }
                    put("metadata", metadataJson)
                }
                jsonArray.put(jsonItem)
            }
            
            memoryFile.writeText(jsonArray.toString(2))
            Log.d(TAG, "Saved ${memories.size} memories to file")
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save memories to file", e)
        }
    }
    
    /**
     * Get memory statistics
     */
    suspend fun getMemoryStats(): MemoryStats = withContext(Dispatchers.IO) {
        val allMemories = getAllMemories()
        MemoryStats(
            totalMemories = allMemories.size,
            oldestTimestamp = allMemories.minOfOrNull { it.timestamp } ?: 0L,
            newestTimestamp = allMemories.maxOfOrNull { it.timestamp } ?: 0L,
            isInitialized = isInitialized
        )
    }
    
    /**
     * Release resources
     */
    fun release() {
        try {
            isInitialized = false
            Log.d(TAG, "🧠 Memory system resources released")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error releasing memory system", e)
        }
    }
}

/**
 * Data class representing a stored memory item
 */
data class MemoryItem(
    val text: String,
    val similarity: Float,
    val metadata: Map<String, String>,
    val timestamp: Long
) {
    fun getFormattedTimestamp(): String {
        return if (timestamp > 0) {
            SimpleDateFormat("MMM dd, HH:mm", Locale.getDefault())
                .format(Date(timestamp))
        } else {
            "Unknown"
        }
    }
}

/**
 * Data class for memory statistics
 */
data class MemoryStats(
    val totalMemories: Int,
    val oldestTimestamp: Long,
    val newestTimestamp: Long,
    val isInitialized: Boolean
) 