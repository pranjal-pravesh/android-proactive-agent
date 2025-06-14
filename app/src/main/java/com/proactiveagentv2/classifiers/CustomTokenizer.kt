package com.proactiveagentv2.classifiers

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File

/**
 * Custom tokenizer implementation for MobileBERT that reads HuggingFace tokenizer.json files
 */
class CustomTokenizer(private val context: Context) {
    
    data class TokenizerResult(
        val ids: IntArray,
        val attentionMask: IntArray,
        val typeIds: IntArray
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            other as TokenizerResult
            return ids.contentEquals(other.ids) && 
                   attentionMask.contentEquals(other.attentionMask) && 
                   typeIds.contentEquals(other.typeIds)
        }
        
        override fun hashCode(): Int {
            var result = ids.contentHashCode()
            result = 31 * result + attentionMask.contentHashCode()
            result = 31 * result + typeIds.contentHashCode()
            return result
        }
    }
    
    private var vocab: Map<String, Int> = emptyMap()
    private var specialTokens: Map<String, Int> = emptyMap()
    private var isInitialized = false
    
    companion object {
        private const val TAG = "CustomTokenizer"
        private const val CLS_TOKEN = "[CLS]"
        private const val SEP_TOKEN = "[SEP]"
        private const val PAD_TOKEN = "[PAD]"
        private const val UNK_TOKEN = "[UNK]"
        private const val MAX_LENGTH = 128
    }
    
    /**
     * Initialize tokenizer from assets
     */
    fun initialize(tokenizerPath: String, vocabPath: String): Boolean {
        return try {
            Log.d(TAG, "Initializing tokenizer from: $tokenizerPath")
            
            // Load vocabulary from vocab.txt
            loadVocabulary(vocabPath)
            
            // Load special tokens from tokenizer.json
            loadSpecialTokens(tokenizerPath)
            
            isInitialized = true
            Log.d(TAG, "Tokenizer initialized successfully. Vocab size: ${vocab.size}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize tokenizer", e)
            isInitialized = false
            false
        }
    }
    
    /**
     * Load vocabulary from vocab.txt file
     */
    private fun loadVocabulary(vocabPath: String) {
        val vocabMap = mutableMapOf<String, Int>()
        
        context.assets.open(vocabPath).bufferedReader().use { reader ->
            var index = 0
            reader.forEachLine { token ->
                vocabMap[token.trim()] = index
                index++
            }
        }
        
        vocab = vocabMap
        Log.d(TAG, "Loaded vocabulary with ${vocab.size} tokens")
    }
    
    /**
     * Load special tokens from tokenizer.json
     */
    private fun loadSpecialTokens(tokenizerPath: String) {
        val specialTokensMap = mutableMapOf<String, Int>()
        
        try {
            context.assets.open(tokenizerPath).bufferedReader().use { reader ->
                val jsonContent = reader.readText()
                val tokenizerJson = JSONObject(jsonContent)
                
                // Extract special tokens
                if (tokenizerJson.has("added_tokens")) {
                    val addedTokens = tokenizerJson.getJSONArray("added_tokens")
                    for (i in 0 until addedTokens.length()) {
                        val tokenObj = addedTokens.getJSONObject(i)
                        val content = tokenObj.getString("content")
                        val id = tokenObj.getInt("id")
                        specialTokensMap[content] = id
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not load special tokens from JSON, using defaults", e)
        }
        
        // Ensure we have the basic special tokens
        if (!specialTokensMap.containsKey(CLS_TOKEN)) {
            specialTokensMap[CLS_TOKEN] = vocab[CLS_TOKEN] ?: 101
        }
        if (!specialTokensMap.containsKey(SEP_TOKEN)) {
            specialTokensMap[SEP_TOKEN] = vocab[SEP_TOKEN] ?: 102
        }
        if (!specialTokensMap.containsKey(PAD_TOKEN)) {
            specialTokensMap[PAD_TOKEN] = vocab[PAD_TOKEN] ?: 0
        }
        if (!specialTokensMap.containsKey(UNK_TOKEN)) {
            specialTokensMap[UNK_TOKEN] = vocab[UNK_TOKEN] ?: 100
        }
        
        specialTokens = specialTokensMap
        Log.d(TAG, "Loaded special tokens: $specialTokens")
    }
    
    /**
     * Encode text to token IDs
     */
    fun encode(text: String): TokenizerResult {
        if (!isInitialized) {
            throw IllegalStateException("Tokenizer not initialized")
        }
        
        // Basic tokenization - split by whitespace and punctuation
        val tokens = basicTokenize(text.lowercase())
        
        // Convert to WordPiece tokens
        val wordPieceTokens = mutableListOf<String>()
        wordPieceTokens.add(CLS_TOKEN)
        
        for (token in tokens) {
            val subTokens = wordPieceTokenize(token)
            wordPieceTokens.addAll(subTokens)
        }
        
        wordPieceTokens.add(SEP_TOKEN)
        
        // Convert tokens to IDs
        val ids = wordPieceTokens.map { token ->
            vocab[token] ?: specialTokens[UNK_TOKEN] ?: 100
        }.toIntArray()
        
        // Create attention mask (1 for real tokens, 0 for padding)
        val attentionMask = IntArray(MAX_LENGTH) { i ->
            if (i < ids.size) 1 else 0
        }
        
        // Create token type IDs (all 0s for single sequence)
        val typeIds = IntArray(MAX_LENGTH) { 0 }
        
        // Pad or truncate to MAX_LENGTH
        val paddedIds = IntArray(MAX_LENGTH) { i ->
            if (i < ids.size) {
                ids[i]
            } else {
                specialTokens[PAD_TOKEN] ?: 0
            }
        }
        
        return TokenizerResult(paddedIds, attentionMask, typeIds)
    }
    
    /**
     * Basic tokenization - split by whitespace and punctuation
     */
    private fun basicTokenize(text: String): List<String> {
        return text
            .replace(Regex("[^a-zA-Z0-9\\s]"), " ")
            .split(Regex("\\s+"))
            .filter { it.isNotBlank() }
    }
    
    /**
     * WordPiece tokenization
     */
    private fun wordPieceTokenize(word: String): List<String> {
        if (word.length > 200) {
            return listOf(UNK_TOKEN)
        }
        
        val tokens = mutableListOf<String>()
        var start = 0
        
        while (start < word.length) {
            var end = word.length
            var curSubStr: String? = null
            
            while (start < end) {
                var subStr = word.substring(start, end)
                if (start > 0) {
                    subStr = "##$subStr"
                }
                
                if (vocab.containsKey(subStr)) {
                    curSubStr = subStr
                    break
                }
                end--
            }
            
            if (curSubStr == null) {
                tokens.add(UNK_TOKEN)
                break
            }
            
            tokens.add(curSubStr)
            start = end
        }
        
        return tokens
    }
    
    /**
     * Check if tokenizer is initialized
     */
    fun isInitialized(): Boolean = isInitialized
} 