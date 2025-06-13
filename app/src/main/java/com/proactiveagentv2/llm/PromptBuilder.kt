package com.proactiveagentv2.llm

import android.util.Log

/**
 * Builds comprehensive system prompts for the LLM with tool call capabilities
 */
class PromptBuilder {
    
    companion object {
        private const val TAG = "PromptBuilder"
        
        // ChatML format constants
        const val SYSTEM_START = "<|im_start|>system\n"
        const val USER_START = "<|im_start|>user\n"
        const val ASSISTANT_START = "<|im_start|>assistant\n"
        const val END_TOKEN = "\n<|im_end|>\n\n"
        
        private val CORE_SYSTEM_PROMPT = """
You are a helpful voice assistant. Answer questions briefly and accurately.
        """.trimIndent()
        
        private val TOOL_CALL_PROMPT = """
Tools available:
- CALCULATOR: TOOL_CALL:CALCULATOR:{expression}
- WEATHER: TOOL_CALL:WEATHER:{location}  
- CALENDAR: TOOL_CALL:CALENDAR:{action}:{details}

Use tools only when needed. Format responses naturally.
        """.trimIndent()
    }
    
    /**
     * Builds the complete system prompt including core instructions and tool capabilities
     */
    fun buildSystemPrompt(): String {
        val fullPrompt = CORE_SYSTEM_PROMPT + "\n" + TOOL_CALL_PROMPT
        Log.d(TAG, "Built concise system prompt: ${fullPrompt.length} characters (optimized for fast prefill)")
        return fullPrompt
    }
    
    /**
     * Builds a ChatML formatted prompt with system, conversation history, and user input
     */
    fun buildChatMLPrompt(
        systemPrompt: String,
        userInput: String,
        conversationHistory: List<String> = emptyList(),
        includeContext: Boolean = false
    ): String {
        Log.d(TAG, "Building ChatML prompt for input: $userInput")
        
        val prompt = StringBuilder()
        
        // Add system role with ChatML tags
        prompt.append(SYSTEM_START)
        prompt.append(systemPrompt)
        prompt.append(END_TOKEN)
        
        // Add conversation context if needed using ChatML format
        if (includeContext && conversationHistory.isNotEmpty()) {
            val recentHistory = conversationHistory.takeLast(3)
            for (i in recentHistory.indices step 2) {
                if (i < recentHistory.size) {
                    // User message
                    val userMsg = recentHistory[i].removePrefix("User: ")
                    prompt.append(USER_START)
                    prompt.append(userMsg)
                    prompt.append(END_TOKEN)
                    
                    // Assistant message (if exists)
                    if (i + 1 < recentHistory.size) {
                        val assistantMsg = recentHistory[i + 1].removePrefix("Assistant: ")
                        prompt.append(ASSISTANT_START)
                        prompt.append(assistantMsg)
                        prompt.append(END_TOKEN)
                    }
                }
            }
        }
        
        // Add current user input
        prompt.append(USER_START)
        prompt.append(userInput)
        prompt.append(END_TOKEN)
        
        // Start assistant response (model will complete this)
        prompt.append(ASSISTANT_START)
        
        return prompt.toString()
    }
    
    /**
     * Builds a user prompt with optional conversation context (backward compatibility)
     */
    fun buildUserPrompt(
        userInput: String,
        conversationHistory: List<String> = emptyList(),
        includeContext: Boolean = false
    ): String {
        Log.d(TAG, "Building basic user prompt for input: $userInput")
        
        val prompt = StringBuilder()
        
        // Add conversation context if needed
        if (includeContext && conversationHistory.isNotEmpty()) {
            prompt.append("Previous conversation:\n")
            conversationHistory.takeLast(3).forEach { message ->
                prompt.append("$message\n")
            }
            prompt.append("\n")
        }
        
        // Add current user input
        prompt.append("User: $userInput")
        
        return prompt.toString()
    }
    
    /**
     * Parses tool calls from LLM response
     */
    fun parseToolCalls(response: String): List<ToolCall> {
        Log.d(TAG, "Parsing tool calls from response")
        
        val toolCalls = mutableListOf<ToolCall>()
        val lines = response.split("\n")
        
        for (line in lines) {
            if (line.startsWith("TOOL_CALL:")) {
                try {
                    val parts = line.removePrefix("TOOL_CALL:").split(":", limit = 3)
                    if (parts.size >= 2) {
                        val toolName = parts[0].trim()
                        val parameters = if (parts.size > 2) parts[2].trim() else parts[1].trim()
                        
                        toolCalls.add(ToolCall(toolName, parameters))
                        Log.d(TAG, "Parsed tool call: $toolName with parameters: $parameters")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error parsing tool call: $line", e)
                }
            }
        }
        
        return toolCalls
    }
    
    /**
     * Builds a ChatML prompt including tool results
     */
    fun buildChatMLPromptWithToolResults(
        systemPrompt: String,
        userInput: String,
        toolResults: List<ToolResult>,
        conversationHistory: List<String> = emptyList(),
        includeContext: Boolean = false
    ): String {
        if (toolResults.isEmpty()) {
            return buildChatMLPrompt(systemPrompt, userInput, conversationHistory, includeContext)
        }
        
        Log.d(TAG, "Building ChatML prompt with tool results")
        
        val prompt = StringBuilder()
        
        // Add system role with ChatML tags
        prompt.append(SYSTEM_START)
        prompt.append(systemPrompt)
        prompt.append(END_TOKEN)
        
        // Add conversation context if needed using ChatML format
        if (includeContext && conversationHistory.isNotEmpty()) {
            val recentHistory = conversationHistory.takeLast(3)
            for (i in recentHistory.indices step 2) {
                if (i < recentHistory.size) {
                    // User message
                    val userMsg = recentHistory[i].removePrefix("User: ")
                    prompt.append(USER_START)
                    prompt.append(userMsg)
                    prompt.append(END_TOKEN)
                    
                    // Assistant message (if exists)
                    if (i + 1 < recentHistory.size) {
                        val assistantMsg = recentHistory[i + 1].removePrefix("Assistant: ")
                        prompt.append(ASSISTANT_START)
                        prompt.append(assistantMsg)
                        prompt.append(END_TOKEN)
                    }
                }
            }
        }
        
        // Add current user input
        prompt.append(USER_START)
        prompt.append(userInput)
        prompt.append(END_TOKEN)
        
        // Add tool results as a system message
        prompt.append(SYSTEM_START)
        prompt.append("Tool Results:\n")
        toolResults.forEach { result ->
            if (result.success) {
                prompt.append("${result.toolName}: ${result.result}\n")
            } else {
                prompt.append("${result.toolName}: Error - ${result.error}\n")
            }
        }
        prompt.append("\nPlease provide a natural response incorporating these tool results.")
        prompt.append(END_TOKEN)
        
        // Start assistant response (model will complete this)
        prompt.append(ASSISTANT_START)
        
        return prompt.toString()
    }
    
    /**
     * Builds a prompt including tool results (backward compatibility)
     */
    fun buildPromptWithToolResults(
        originalPrompt: String,
        toolResults: List<ToolResult>
    ): String {
        if (toolResults.isEmpty()) return originalPrompt
        
        val prompt = StringBuilder(originalPrompt)
        prompt.append("\n\nTool Results:\n")
        
        toolResults.forEach { result ->
            prompt.append("${result.toolName}: ${result.result}\n")
        }
        
        prompt.append("\nPlease provide a natural response incorporating these results.")
        
        return prompt.toString()
    }
    
    /**
     * Debug method to log the complete ChatML prompt structure
     */
    fun logChatMLPrompt(prompt: String) {
        if (Log.isLoggable(TAG, Log.DEBUG)) {
            Log.d(TAG, "=== COMPLETE CHATML PROMPT ===")
            Log.d(TAG, prompt)
            Log.d(TAG, "=== END CHATML PROMPT ===")
        }
    }
    
    /**
     * Validates ChatML prompt format
     */
    fun validateChatMLFormat(prompt: String): Boolean {
        val hasSystemStart = prompt.contains(SYSTEM_START)
        val hasUserStart = prompt.contains(USER_START)
        val hasAssistantStart = prompt.contains(ASSISTANT_START)
        val endsWithAssistantStart = prompt.trimEnd().endsWith(ASSISTANT_START.trimEnd())
        
        Log.d(TAG, "ChatML validation - System: $hasSystemStart, User: $hasUserStart, Assistant: $hasAssistantStart, EndsCorrectly: $endsWithAssistantStart")
        
        return hasSystemStart && hasUserStart && hasAssistantStart && endsWithAssistantStart
    }
}

/**
 * Represents a parsed tool call
 */
data class ToolCall(
    val toolName: String,
    val parameters: String
)

/**
 * Represents the result of a tool execution
 */
data class ToolResult(
    val toolName: String,
    val result: String,
    val success: Boolean = true,
    val error: String? = null
) 