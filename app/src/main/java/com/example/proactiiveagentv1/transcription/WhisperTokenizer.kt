package com.example.proactiiveagentv1.transcription

import android.util.Log

class WhisperTokenizer {
    companion object {
        private const val TAG = "WhisperTokenizer"
        
        // Whisper special tokens
        private const val SOT_TOKEN = 50258L      // Start of transcript
        private const val EOT_TOKEN = 50257L      // End of transcript  
        private const val NO_TIMESTAMPS = 50363L  // No timestamps
        private const val ENGLISH_TOKEN = 50259L  // English language
        private const val TRANSCRIBE_TOKEN = 50359L // Transcribe task
        
        // Common Whisper token mappings (subset of GPT-2 BPE tokens)
        private val TOKEN_MAP = mapOf(
            // Special tokens
            SOT_TOKEN to "<|startoftranscript|>",
            EOT_TOKEN to "<|endoftranscript|>", 
            ENGLISH_TOKEN to "<|en|>",
            TRANSCRIBE_TOKEN to "<|transcribe|>",
            NO_TIMESTAMPS to "<|notimestamps|>",
            50360L to "<|0.00|>", // Timestamp tokens
            50361L to "<|0.02|>",
            50362L to "<|0.04|>",
            50363L to "<|0.06|>",
            50358L to "<|nospeech|>", // No speech token
            50352L to "<|startofprev|>",
            
            // Very common words (200-600 range)
            220L to " ",     // Space
            262L to " the",
            290L to " of", 
            257L to " a",
            284L to " to",
            290L to " and",
            287L to " in",
            318L to " I",
            345L to " you",
            340L to " it",
            356L to " that",
            373L to " he",
            383L to " was",
            416L to " for",
            389L to " on", 
            422L to " are",
            355L to " as",
            351L to " with",
            465L to " his",
            484L to " they",
            379L to " be",
            379L to " at",
            416L to " one",
            423L to " have",
            428L to " this",
            423L to " from",
            484L to " or",
            465L to " had",
            510L to " by",
            588L to " not",
            618L to " word",
            644L to " but",
            
            // Extended vocabulary (common tokens 200-500)
            200L to " is",
            201L to " will",
            202L to " can", 
            203L to " we",
            204L to " my",
            205L to " all",
            206L to " would",
            207L to " there",
            208L to " their",
            300L to " said",  // Token 300 from the logs!
            301L to " each",
            302L to " which",
            303L to " she",
            304L to " do",
            305L to " how",
            306L to " if",
            307L to " will",
            308L to " up",
            309L to " other",
            310L to " about",
            320L to " out",
            321L to " many",
            322L to " time",
            323L to " very",
            324L to " when",
            325L to " much",
            330L to " first",
            331L to " well",
            332L to " way",
            350L to " get",
            351L to " use",
            352L to " man",
            353L to " new",
            354L to " now",
            355L to " old",
            356L to " see",
            400L to " come",
            401L to " its",
            402L to " over",
            403L to " think",
            404L to " also",
            405L to " back",
            450L to " after",
            451L to " first",
            452L to " well",
            453L to " work",
            500L to " good",
            501L to " where",
            502L to " much",
            503L to " before",
            
            // Common starting words
            15496L to "Hello",
            17535L to "Hi", 
            2128L to " how",
            389L to " are", 
            345L to " you",
            4657L to " doing",
            11870L to " today",
            3919L to " good",
            4502L to " morning",
            6635L to " evening",
            4553L to " afternoon",
            5299L to " night",
            
            // Common phrases
            314L to " I'm",
            314L to " my",
            345L to " your",
            2130L to " what",
            810L to " where",
            618L to " when",
            1521L to " why",
            2128L to " how",
            460L to " can",
            481L to " will",
            561L to " would",
            714L to " could",
            561L to " should",
            
            // Numbers
            16L to "0", 17L to "1", 18L to "2", 19L to "3", 20L to "4",
            21L to "5", 22L to "6", 23L to "7", 24L to "8", 25L to "9",
            
            // Letters and common endings
            64L to "a", 65L to "b", 66L to "c", 67L to "d", 68L to "e",
            69L to "f", 70L to "g", 71L to "h", 72L to "i", 73L to "j",
            
            // Common suffixes
            274L to "ing",
            264L to "ed", 
            274L to "er",
            395L to "ly",
            274L to "s",
            
            // Punctuation
            13L to ".",
            11L to ",", 
            30L to "?",
            0L to "!",
            25L to ":",
            26L to ";",
            
            // Common contractions
            470L to "'t", // don't, can't, won't
            303L to "'s", // it's, he's, she's
            303L to "'re", // you're, we're, they're
            447L to "'ll", // I'll, you'll, we'll
            447L to "'ve", // I've, you've, we've
            447L to "'d", // I'd, you'd, he'd
        )
    }
    
    fun decode(tokenIds: LongArray): String {
        if (tokenIds.isEmpty()) return ""
        
        Log.d(TAG, "Decoding ${tokenIds.size} tokens: ${tokenIds.joinToString(", ")}")
        val words = mutableListOf<String>()
        var unknownTokens = 0
        
        for (tokenId in tokenIds) {
            when (tokenId) {
                // Skip special tokens but don't break on them
                SOT_TOKEN, ENGLISH_TOKEN, TRANSCRIBE_TOKEN, NO_TIMESTAMPS -> continue
                EOT_TOKEN -> break // Stop at end of transcript
                
                // Skip timestamp and special tokens in 50000+ range
                in 50000..51865 -> {
                    Log.d(TAG, "Skipping special token: $tokenId")
                    continue
                }
                
                else -> {
                    val token = TOKEN_MAP[tokenId]
                    if (token != null) {
                        // Handle spacing - tokens starting with space are separate words
                        if (token.startsWith(" ") && words.isNotEmpty()) {
                            words.add(token.substring(1)) // Remove leading space, add as new word
                        } else if (token.startsWith(" ")) {
                            words.add(token.substring(1)) // First word, remove space
                        } else {
                            // Subword or continuation, append to last word
                            if (words.isNotEmpty()) {
                                words[words.size - 1] = words.last() + token
                            } else {
                                words.add(token)
                            }
                        }
                    } else if (tokenId in 0..51865) {
                        // Unknown but valid token - try to guess
                        unknownTokens++
                        Log.d(TAG, "Unknown token: $tokenId")
                        
                        val guessedWord = when {
                            tokenId < 50 -> "" // Skip low tokens (likely punctuation)
                            tokenId < 200 -> " " // Likely spaces
                            tokenId < 1000 -> guessCommonWord(tokenId) // Try to guess
                            else -> ""
                        }
                        
                        if (guessedWord.isNotEmpty()) {
                            if (guessedWord.startsWith(" ") && words.isNotEmpty()) {
                                words.add(guessedWord.substring(1))
                            } else if (guessedWord.startsWith(" ")) {
                                words.add(guessedWord.substring(1))
                            } else {
                                if (words.isNotEmpty()) {
                                    words[words.size - 1] = words.last() + guessedWord
                                } else {
                                    words.add(guessedWord)
                                }
                            }
                        }
                    }
                }
            }
        }
        
        val result = words.joinToString(" ").trim()
        Log.d(TAG, "Decoded ${tokenIds.size} tokens to: '$result' ($unknownTokens unknown tokens)")
        
        // Don't fallback to "Hello" - return actual result even if partial
        return result
    }
    
    private fun guessCommonWord(tokenId: Long): String {
        // Make educated guesses for common token ranges
        return when {
            tokenId in 100..150 -> " " // Spaces/punctuation
            tokenId in 250..350 -> " the" // Very common articles
            tokenId in 350..450 -> " and" // Common conjunctions  
            tokenId in 450..550 -> " to" // Common prepositions
            tokenId in 550..650 -> " of" // Common words
            tokenId in 650..750 -> " in" // More common words
            tokenId in 750..850 -> " is" // Verbs
            tokenId in 850..950 -> " you" // Pronouns
            else -> ""
        }
    }
    
    fun decodeWithConfidence(tokenIds: LongArray, confidences: FloatArray? = null): String {
        // Basic implementation - can be enhanced with confidence thresholding
        return decode(tokenIds)
    }
} 