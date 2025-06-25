package com.proactiveagentv2.managers

import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.*
import org.json.JSONObject
import org.json.JSONArray
import java.text.SimpleDateFormat
import org.tensorflow.lite.Interpreter
import kotlin.math.*

/**
 * MemoryManager handles embedding-based memory storage and retrieval
 * Uses Gecko embedder for on-device text embedding extraction
 * and vector storage with cosine similarity for semantic search
 */
class MemoryManager(private val context: Context) {
    
    private val memories = mutableListOf<MemoryItem>()
    private var isInitialized = false
    private val memoryFile: File by lazy { 
        // Use external files directory so it's visible on PC via USB
        val externalDir = context.getExternalFilesDir(null) ?: context.filesDir
        File(externalDir, "memory_storage.json")
    }
    
    // Gecko embedding components
    private var geckoInterpreter: Interpreter? = null
    private var tokenizerVocab: Map<String, Int>? = null
    
    companion object {
        private const val TAG = "MemoryManager"
        
        // Gecko model configuration
        private const val GECKO_MODEL_FILENAME = "Gecko_256_quant.tflite"
        private const val TOKENIZER_FILENAME = "sentencepiece.model"
        private const val EMBEDDING_DIMENSION = 768 // Gecko embeddings are 768-dimensional
        private const val MAX_SEQUENCE_LENGTH = 256 // Gecko_256_quant supports up to 256 tokens
        
        // Special tokens
        private const val PAD_TOKEN = "[PAD]"
        private const val UNK_TOKEN = "[UNK]"
        private const val CLS_TOKEN = "[CLS]"
        private const val SEP_TOKEN = "[SEP]"
    }
    
    /**
     * Initialize the memory system with Gecko embedder
     */
    suspend fun initialize(): Boolean = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "🧠 Initializing embedding-based memory system...")
            
            // Initialize Gecko embedder
            if (!initializeGeckoEmbedder()) {
                Log.e(TAG, "❌ Failed to initialize Gecko embedder")
                return@withContext false
            }
            
            // Load existing memories from file
            loadMemoriesFromFile()
            
            isInitialized = true
            Log.i(TAG, "✅ Memory system initialized successfully")
            Log.d(TAG, "📄 Memory file: ${memoryFile.absolutePath}")
            Log.d(TAG, "💾 Loaded ${memories.size} existing memories")
            Log.d(TAG, "🧮 Embedding dimension: $EMBEDDING_DIMENSION")
            
            return@withContext true
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to initialize memory system", e)
            isInitialized = false
            return@withContext false
        }
    }
    
    /**
     * Initialize the Gecko embedding model
     */
    private suspend fun initializeGeckoEmbedder(): Boolean = withContext(Dispatchers.IO) {
        try {
            // Load Gecko model
            val geckoModelFile = copyAssetToFile(GECKO_MODEL_FILENAME)
            if (geckoModelFile == null) {
                Log.e(TAG, "❌ Failed to copy Gecko model from assets")
                return@withContext false
            }
            
            // Initialize TensorFlow Lite interpreter
            val options = Interpreter.Options().apply {
                setNumThreads(4)
            }
            
            try {
                // Try to add GPU delegate if available
                // Note: GPU delegate requires additional setup with LiteRT
                geckoInterpreter = Interpreter(geckoModelFile, options)
                Log.d(TAG, "✅ Gecko interpreter initialized successfully")
            } catch (e: Exception) {
                Log.w(TAG, "GPU acceleration not available, using CPU", e)
                geckoInterpreter = Interpreter(geckoModelFile, options)
            }
            
            // Load tokenizer (simplified - in production you'd parse sentencepiece.model properly)
            initializeSimpleTokenizer()
            
            return@withContext true
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to initialize Gecko embedder", e)
            return@withContext false
        }
    }
    
    /**
     * Initialize a simple tokenizer (basic implementation)
     * In production, you'd properly parse the sentencepiece.model file
     */
    private fun initializeSimpleTokenizer() {
        // Basic tokenizer vocabulary - simplified for demo
        tokenizerVocab = mapOf(
            PAD_TOKEN to 0,
            UNK_TOKEN to 1,
            CLS_TOKEN to 2,
            SEP_TOKEN to 3
        ).toMutableMap().apply {
            // Add common English words with arbitrary IDs
            val commonWords = listOf("the", "a", "an", "and", "or", "but", "in", "on", "at", "to", "for", "of", "with", "by")
            commonWords.forEachIndexed { index, word ->
                put(word, index + 4)
            }
        }
        Log.d(TAG, "✅ Simple tokenizer initialized with ${tokenizerVocab?.size} tokens")
    }
    
    /**
     * Copy asset file to internal storage
     */
    private fun copyAssetToFile(filename: String): File? {
        return try {
            val internalFile = File(context.filesDir, filename)
            
            if (!internalFile.exists()) {
                context.assets.open(filename).use { inputStream ->
                    internalFile.outputStream().use { outputStream ->
                        inputStream.copyTo(outputStream)
                    }
                }
                Log.d(TAG, "📁 Copied $filename to internal storage")
            }
            
            internalFile
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to copy asset file: $filename", e)
            null
        }
    }
    
    /**
     * Generate embeddings for text using Gecko model
     */
    private suspend fun generateEmbedding(text: String): FloatArray? = withContext(Dispatchers.Default) {
        try {
            val interpreter = geckoInterpreter ?: return@withContext null
            val vocab = tokenizerVocab ?: return@withContext null
            
            // Tokenize text (simplified tokenization)
            val tokens = tokenizeText(text, vocab)
            
            // Prepare input tensor
            val inputBuffer = ByteBuffer.allocateDirect(MAX_SEQUENCE_LENGTH * 4).apply {
                order(ByteOrder.nativeOrder())
            }
            
            // Fill input buffer with token IDs
            repeat(MAX_SEQUENCE_LENGTH) { i ->
                val tokenId = if (i < tokens.size) tokens[i] else vocab[PAD_TOKEN] ?: 0
                inputBuffer.putInt(tokenId)
            }
            inputBuffer.rewind()
            
            // Prepare output tensor
            val outputBuffer = ByteBuffer.allocateDirect(EMBEDDING_DIMENSION * 4).apply {
                order(ByteOrder.nativeOrder())
            }
            
            // Run inference
            interpreter.run(inputBuffer, outputBuffer)
            
            // Extract embeddings
            outputBuffer.rewind()
            val embeddings = FloatArray(EMBEDDING_DIMENSION)
            repeat(EMBEDDING_DIMENSION) { i ->
                embeddings[i] = outputBuffer.float
            }
            
            // Normalize embeddings
            val norm = sqrt(embeddings.map { it * it }.sum())
            if (norm > 0) {
                for (i in embeddings.indices) {
                    embeddings[i] /= norm
                }
            }
            
            Log.d(TAG, "🧮 Generated ${embeddings.size}-dimensional embedding for text: ${text.take(50)}...")
            return@withContext embeddings
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to generate embedding", e)
            return@withContext null
        }
    }
    
    /**
     * Simple text tokenization (basic implementation)
     */
    private fun tokenizeText(text: String, vocab: Map<String, Int>): List<Int> {
        val words = text.lowercase()
            .replace(Regex("[^a-zA-Z0-9\\s]"), " ")
            .split("\\s+".toRegex())
            .filter { it.isNotBlank() }
        
        val tokens = mutableListOf<Int>()
        tokens.add(vocab[CLS_TOKEN] ?: 2) // Add CLS token
        
        words.take(MAX_SEQUENCE_LENGTH - 2).forEach { word ->
            tokens.add(vocab[word] ?: vocab[UNK_TOKEN] ?: 1)
        }
        
        tokens.add(vocab[SEP_TOKEN] ?: 3) // Add SEP token
        
        return tokens
    }
    
    /**
     * Calculate cosine similarity between two embeddings
     */
    private fun calculateCosineSimilarity(embedding1: FloatArray, embedding2: FloatArray): Float {
        if (embedding1.size != embedding2.size) return 0f
        
        var dotProduct = 0f
        var norm1 = 0f
        var norm2 = 0f
        
        for (i in embedding1.indices) {
            dotProduct += embedding1[i] * embedding2[i]
            norm1 += embedding1[i] * embedding1[i]
            norm2 += embedding2[i] * embedding2[i]
        }
        
        val denominator = sqrt(norm1) * sqrt(norm2)
        return if (denominator > 0) dotProduct / denominator else 0f
    }
    
    /**
     * Store a text chunk in memory with embeddings
     */
    suspend fun storeMemory(text: String, metadata: Map<String, String> = emptyMap()): Boolean = withContext(Dispatchers.IO) {
        Log.i(TAG, "🔄 ATTEMPTING TO STORE MEMORY: \"${text.take(100)}...\"")
        Log.d(TAG, "   📊 Current memory count: ${memories.size}")
        Log.d(TAG, "   📁 Memory file path: ${memoryFile.absolutePath}")
        Log.d(TAG, "   🔧 Is initialized: $isInitialized")
        
        if (!isInitialized) {
            Log.w(TAG, "⚠️ Memory system not initialized, attempting to initialize now...")
            val initSuccess = initialize()
            if (!initSuccess) {
                Log.e(TAG, "❌ Failed to initialize memory system")
                return@withContext false
            }
        }
        
        try {
            Log.d(TAG, "🧮 Generating embedding for text...")
            // Generate embedding for the text
            val embedding = generateEmbedding(text)
            if (embedding == null) {
                Log.e(TAG, "❌ Failed to generate embedding for text")
                return@withContext false
            }
            Log.d(TAG, "✅ Embedding generated successfully (${embedding.size} dimensions)")
            
            // Add timestamp and type metadata
            val enrichedMetadata = metadata.toMutableMap().apply {
                put("timestamp", System.currentTimeMillis().toString())
                put("type", "contextable_transcription")
            }
            
            val memoryItem = MemoryItem(
                text = text,
                similarity = 1.0f, // Max similarity for stored items
                metadata = enrichedMetadata,
                timestamp = System.currentTimeMillis(),
                embedding = embedding
            )
            
            memories.add(memoryItem)
            Log.d(TAG, "📝 Added to memory list (new count: ${memories.size})")
            
            saveMemoriesToFile()
            Log.i(TAG, "💾 SUCCESSFULLY STORED MEMORY: \"${text.take(50)}...\"")
            Log.d(TAG, "📁 Memory file exists: ${memoryFile.exists()}, size: ${if(memoryFile.exists()) memoryFile.length() else 0} bytes")
            
            return@withContext true
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to store memory", e)
            e.printStackTrace()
            return@withContext false
        }
    }
    
    /**
     * Retrieve relevant memories using semantic similarity search
     */
    suspend fun retrieveMemories(
        query: String, 
        maxResults: Int = 3,
        minSimilarity: Float = 0.3f
    ): List<MemoryItem> = withContext(Dispatchers.IO) {
        Log.i(TAG, "🔍 RETRIEVING MEMORIES FOR QUERY: \"${query.take(100)}...\"")
        Log.d(TAG, "   📊 Available memories: ${memories.size}")
        Log.d(TAG, "   📝 Max results: $maxResults, Min similarity: $minSimilarity")
        Log.d(TAG, "   🔧 Is initialized: $isInitialized")
        
        if (!isInitialized) {
            Log.w(TAG, "⚠️ Memory system not initialized, cannot retrieve memories")
            return@withContext emptyList()
        }
        
        if (memories.isEmpty()) {
            Log.w(TAG, "⚠️ No memories stored, returning empty list")
            return@withContext emptyList()
        }
        
        try {
            Log.d(TAG, "🧮 Generating query embedding...")
            
            // Generate embedding for the query
            val queryEmbedding = generateEmbedding(query)
            if (queryEmbedding == null) {
                Log.e(TAG, "❌ Failed to generate embedding for query")
                return@withContext emptyList()
            }
            Log.d(TAG, "✅ Query embedding generated (${queryEmbedding.size} dimensions)")
            
            // Calculate similarities with all stored memories
            Log.d(TAG, "🔢 Calculating similarities with ${memories.size} stored memories...")
            val scoredMemories = memories.mapNotNull { memory ->
                memory.embedding?.let { memoryEmbedding ->
                    val similarity = calculateCosineSimilarity(queryEmbedding, memoryEmbedding)
                    Log.v(TAG, "   📄 Memory similarity ${String.format("%.3f", similarity)}: \"${memory.text.take(30)}...\"")
                    memory.copy(similarity = similarity)
                }
            }
            
            val relevantMemories = scoredMemories
                .filter { it.similarity >= minSimilarity }
                .sortedByDescending { it.similarity }
                .take(maxResults)
            
            Log.i(TAG, "📚 RETRIEVED ${relevantMemories.size} RELEVANT MEMORIES (from ${memories.size} total)")
            relevantMemories.forEach { item ->
                Log.i(TAG, "  ✅ Memory (similarity: ${String.format("%.3f", item.similarity)}): \"${item.text.take(50)}...\"")
            }
            
            return@withContext relevantMemories
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to retrieve memories", e)
            e.printStackTrace()
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
                
                // Load embedding if present
                val embedding = if (jsonItem.has("embedding")) {
                    val embeddingArray = jsonItem.getJSONArray("embedding")
                    FloatArray(embeddingArray.length()) { idx ->
                        embeddingArray.getDouble(idx).toFloat()
                    }
                } else {
                    null
                }
                
                val memoryItem = MemoryItem(
                    text = jsonItem.getString("text"),
                    similarity = jsonItem.getDouble("similarity").toFloat(),
                    metadata = metadata,
                    timestamp = jsonItem.getLong("timestamp"),
                    embedding = embedding
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
                    
                    // Save embedding as array
                    memory.embedding?.let { embedding ->
                        val embeddingArray = JSONArray()
                        embedding.forEach { value ->
                            embeddingArray.put(value.toDouble())
                        }
                        put("embedding", embeddingArray)
                    }
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
            geckoInterpreter?.close()
            geckoInterpreter = null
            tokenizerVocab = null
            isInitialized = false
            Log.d(TAG, "🧠 Memory system resources released")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error releasing memory system", e)
        }
    }
}

/**
 * Data class representing a stored memory item with embedding
 */
data class MemoryItem(
    val text: String,
    val similarity: Float,
    val metadata: Map<String, String>,
    val timestamp: Long,
    val embedding: FloatArray? = null
) {
    fun getFormattedTimestamp(): String {
        return if (timestamp > 0) {
            SimpleDateFormat("MMM dd, HH:mm", Locale.getDefault())
                .format(Date(timestamp))
        } else {
            "Unknown"
        }
    }
    
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        
        other as MemoryItem
        
        if (text != other.text) return false
        if (similarity != other.similarity) return false
        if (metadata != other.metadata) return false
        if (timestamp != other.timestamp) return false
        if (embedding != null) {
            if (other.embedding == null) return false
            if (!embedding.contentEquals(other.embedding)) return false
        } else if (other.embedding != null) return false
        
        return true
    }
    
    override fun hashCode(): Int {
        var result = text.hashCode()
        result = 31 * result + similarity.hashCode()
        result = 31 * result + metadata.hashCode()
        result = 31 * result + timestamp.hashCode()
        result = 31 * result + (embedding?.contentHashCode() ?: 0)
        return result
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