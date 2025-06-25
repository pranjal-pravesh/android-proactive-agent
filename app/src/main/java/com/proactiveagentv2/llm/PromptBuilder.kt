package com.proactiveagentv2.llm

import android.util.Log
import com.proactiveagentv2.managers.MemoryItem

/**
 * Builds comprehensive system prompts for the LLM with tool call capabilities
 */
class PromptBuilder {
    
    // Current model type for formatting
    private var currentModelType: ModelType = ModelType.QWEN
    
    /**
     * Set the current model type for proper prompt formatting
     */
    fun setModelType(modelType: ModelType) {
        currentModelType = modelType
        Log.d(TAG, "Prompt builder configured for model type: $modelType")
    }
    
    /**
     * Build RAG context section from relevant memories
     */
    private fun buildRAGContext(memories: List<MemoryItem>): String {
        if (memories.isEmpty()) return ""
        
        val context = StringBuilder()
        context.append("RELEVANT CONTEXT FROM MEMORY:\n\n")
        
        memories.forEachIndexed { index, memory ->
            val formattedTimestamp = java.text.SimpleDateFormat("MMM dd, HH:mm", java.util.Locale.getDefault())
                .format(java.util.Date(memory.timestamp))
            
            context.append("${index + 1}. [${formattedTimestamp}] ${memory.text}\n")
        }
        
        context.append("\nPlease use this context to provide more accurate and relevant answers. If the context contains information relevant to the user's question, reference it in your response.")
        
        Log.d(TAG, "Built RAG context with ${memories.size} memories, ${context.length} characters")
        return context.toString()
    }
    
    /**
     * Analyzes user input and adds tool usage hints if needed
     */
    private fun enhanceUserInputForToolUsage(userInput: String): String {
        val lowercaseInput = userInput.lowercase()
        
        // Check for weather patterns
        val weatherKeywords = listOf("weather", "temperature", "rain", "sunny", "cloudy", "forecast", "climate")
        val hasWeatherKeyword = weatherKeywords.any { lowercaseInput.contains(it) }
        val hasLocationPattern = Regex("\\b(in|at|for)\\s+[a-zA-Z\\s]+").containsMatchIn(lowercaseInput)
        
        // Check for math patterns
        val mathKeywords = listOf("calculate", "area", "volume", "plus", "minus", "times", "multiply", "divide", "square", "radius", "circumference", "percentage")
        val hasMathKeyword = mathKeywords.any { lowercaseInput.contains(it) }
        val hasMathSymbols = Regex("[+\\-*/=×÷%^]|\\d+\\s*[+\\-*/×÷]\\s*\\d+").containsMatchIn(lowercaseInput)
        
        return when {
            hasWeatherKeyword && hasLocationPattern -> {
                Log.d(TAG, "Detected weather query - enhancing prompt")
                "$userInput\n\n[REMINDER: Use weather tool with JSON format: {\"name\": \"weather\", \"arguments\": {\"location\": \"...\"}}}]"
            }
            hasMathKeyword || hasMathSymbols -> {
                Log.d(TAG, "Detected math query - enhancing prompt")
                "$userInput\n\n[REMINDER: Use calculator tool with JSON format: {\"name\": \"calculator\", \"arguments\": {\"expression\": \"...\"}}}]"
            }
            else -> userInput
        }
    }
    
    companion object {
        private const val TAG = "PromptBuilder"
        
        // ChatML format constants (for Qwen models)
        const val CHATML_SYSTEM_START = "<|im_start|>system\n"
        const val CHATML_USER_START = "<|im_start|>user\n"
        const val CHATML_ASSISTANT_START = "<|im_start|>assistant\n"
        const val CHATML_END_TOKEN = "\n<|im_end|>\n\n"
        
        // Gemma format constants
        const val GEMMA_SYSTEM_START = "<start_of_turn>system\n"
        const val GEMMA_USER_START = "<start_of_turn>user\n"
        const val GEMMA_ASSISTANT_START = "<start_of_turn>model\n"
        const val GEMMA_END_TOKEN = "\n<end_of_turn>\n\n"
        
        // JSON schema for available tools
        private val TOOL_SCHEMAS = """
[
  {
    "name": "calculator",
    "description": "Safely evaluate arithmetic expressions and mathematical calculations.",
    "parameters": {
      "type": "object",
      "properties": {
        "expression": {
          "type": "string",
          "description": "Mathematical expression to evaluate (e.g., '2+2', '3.14159*7*7', 'sqrt(16)')"
        }
      },
      "required": ["expression"]
    }
  },
  {
    "name": "weather",
    "description": "Get current weather information for a specific location.",
    "parameters": {
      "type": "object",
      "properties": {
        "location": {
          "type": "string",
          "description": "City name or location (e.g., 'New Delhi', 'Tokyo', 'New York')"
        }
      },
      "required": ["location"]
    }
  },
  {
    "name": "calendar",
    "description": "Manage calendar events including adding, checking, or updating appointments.",
    "parameters": {
      "type": "object",
      "properties": {
        "action": {
          "type": "string",
          "description": "Action to perform (e.g., 'add', 'check', 'update', 'delete')"
        },
        "details": {
          "type": "string",
          "description": "Event details including date, time, and description"
        }
      },
      "required": ["action", "details"]
    }
  }
]
        """.trimIndent()

        // Core system prompts for different model types
        private val QWEN_SYSTEM_PROMPT = """
You are a helpful voice assistant. For calculations, weather, or calendar tasks, respond with ONLY JSON. For other questions, answer normally.

**AVAILABLE TOOLS & SCHEMAS:**
$TOOL_SCHEMAS

**MANDATORY JSON FORMAT:**
{"name": "<tool_name>", "arguments": {...}}

**Examples:**
Q: "What's 25 times 8?"
A: {"name": "calculator", "arguments": {"expression": "25*8"}}

Q: "What's the weather in Tokyo?"
A: {"name": "weather", "arguments": {"location": "Tokyo"}}

Q: "Add meeting tomorrow 2pm"
A: {"name": "calendar", "arguments": {"action": "add", "details": "meeting tomorrow 2pm"}}

IMPORTANT: For math, weather, or calendar - respond with JSON ONLY, no other text!
        """.trimIndent()
        
        private val GEMMA_SYSTEM_PROMPT = """
You are a helpful voice assistant. For math, weather, or calendar questions, reply with JSON ONLY. For other topics, answer normally.

**AVAILABLE TOOLS & SCHEMAS:**
$TOOL_SCHEMAS

**JSON FORMAT:** {"name": "<tool_name>", "arguments": {...}}

**Examples:**
"What's 15 plus 27?" → {"name": "calculator", "arguments": {"expression": "15+27"}}
"Weather in Boston?" → {"name": "weather", "arguments": {"location": "Boston"}}
"Add gym session tomorrow 6am" → {"name": "calendar", "arguments": {"action": "add", "details": "gym session tomorrow 6am"}}

CRITICAL: Math/weather/calendar = JSON only. No explanation, just pure JSON.
        """.trimIndent()
        
        private val BASIC_QWEN_PROMPT = """
You are a helpful voice assistant. Provide brief, accurate answers.
Keep responses short and conversational. When you need to use tools, respond with JSON format only.
        """.trimIndent()
        
        private val BASIC_GEMMA_PROMPT = """
You are a concise voice assistant. Give clear, short answers.
Be helpful and conversational. For tools, use JSON format only.
        """.trimIndent()
    }
    
    /**
     * Builds the complete system prompt including core instructions and tool capabilities
     */
    fun buildSystemPrompt(): String {
        val fullPrompt = when (currentModelType) {
            ModelType.QWEN -> QWEN_SYSTEM_PROMPT
            ModelType.GEMMA -> GEMMA_SYSTEM_PROMPT
        }
        Log.d(TAG, "Built system prompt with tools for $currentModelType: ${fullPrompt.length} characters")
        return fullPrompt
    }
    
    /**
     * Builds just the basic system prompt without tool capabilities
     */
    fun buildBasicSystemPrompt(): String {
        val basicPrompt = when (currentModelType) {
            ModelType.QWEN -> BASIC_QWEN_PROMPT
            ModelType.GEMMA -> BASIC_GEMMA_PROMPT
        }
        Log.d(TAG, "Built basic system prompt for $currentModelType: ${basicPrompt.length} characters")
        return basicPrompt
    }
    
    /**
     * Builds a properly formatted prompt with system, conversation history, and user input
     */
    fun buildChatMLPrompt(
        systemPrompt: String,
        userInput: String,
        conversationHistory: List<String> = emptyList(),
        includeContext: Boolean = false,
        relevantMemories: List<MemoryItem> = emptyList()
    ): String {
        Log.d(TAG, "Building formatted prompt for $currentModelType with input: $userInput")
        
        val prompt = StringBuilder()
        
        // Get appropriate formatting tokens based on model type
        val (systemStart, userStart, assistantStart, endToken) = when (currentModelType) {
            ModelType.QWEN -> arrayOf(CHATML_SYSTEM_START, CHATML_USER_START, CHATML_ASSISTANT_START, CHATML_END_TOKEN)
            ModelType.GEMMA -> arrayOf(GEMMA_SYSTEM_START, GEMMA_USER_START, GEMMA_ASSISTANT_START, GEMMA_END_TOKEN)
        }
        
        // Add system role with proper tags
        prompt.append(systemStart)
        prompt.append(systemPrompt)
        prompt.append(endToken)
        
        // Add RAG context from relevant memories if available
        if (relevantMemories.isNotEmpty()) {
            Log.d(TAG, "Adding ${relevantMemories.size} relevant memories as context")
            val contextSection = buildRAGContext(relevantMemories)
            prompt.append(userStart)
            prompt.append(contextSection)
            prompt.append(endToken)
        }
        
        // Add conversation context if needed using proper format
        if (includeContext && conversationHistory.isNotEmpty()) {
            val recentHistory = conversationHistory.takeLast(3)
            for (i in recentHistory.indices step 2) {
                if (i < recentHistory.size) {
                    // User message
                    val userMsg = recentHistory[i].removePrefix("User: ")
                    prompt.append(userStart)
                    prompt.append(userMsg)
                    prompt.append(endToken)
                    
                    // Assistant message (if exists)
                    if (i + 1 < recentHistory.size) {
                        val assistantMsg = recentHistory[i + 1].removePrefix("Assistant: ")
                        prompt.append(assistantStart)
                        prompt.append(assistantMsg)
                        prompt.append(endToken)
                    }
                }
            }
        }
        
        // Add current user input with tool usage enhancement
        val enhancedUserInput = enhanceUserInputForToolUsage(userInput)
        prompt.append(userStart)
        prompt.append(enhancedUserInput)
        prompt.append(endToken)
        
        // Start assistant response (model will complete this)
        prompt.append(assistantStart)
        
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
     * Parses tool calls from LLM response in JSON format
     */
    fun parseToolCalls(response: String): List<ToolCall> {
        Log.d(TAG, "Parsing tool calls from response: ${response.take(100)}...")
        
        val toolCalls = mutableListOf<ToolCall>()
        
        try {
            // First, try to parse the entire response as JSON
            val cleanedResponse = response.trim()
            if (cleanedResponse.startsWith("{") && cleanedResponse.endsWith("}")) {
                val jsonCall = parseJsonToolCall(cleanedResponse)
                if (jsonCall != null) {
                    toolCalls.add(jsonCall)
                    Log.d(TAG, "Parsed JSON tool call: ${jsonCall.toolName} with parameters: ${jsonCall.parameters}")
                    return toolCalls
                }
            }
            
            // Look for JSON objects in the response
            val jsonPattern = Regex("""\{[^{}]*"name"[^{}]*"arguments"[^{}]*\}""")
            val matches = jsonPattern.findAll(response)
            
            for (match in matches) {
                val jsonCall = parseJsonToolCall(match.value)
                if (jsonCall != null) {
                    toolCalls.add(jsonCall)
                    Log.d(TAG, "Found JSON tool call: ${jsonCall.toolName} with parameters: ${jsonCall.parameters}")
                }
            }
            
            // Fallback: Legacy TOOL_CALL format support
            if (toolCalls.isEmpty()) {
                val lines = response.split("\n")
                for (line in lines) {
                    val trimmedLine = line.trim()
                    
                    if (trimmedLine.startsWith("TOOL_CALL:")) {
                        try {
                            val parts = trimmedLine.removePrefix("TOOL_CALL:").split(":", limit = 2)
                            if (parts.size >= 2) {
                                val toolName = parts[0].trim().uppercase()
                                val parameters = parts[1].trim()
                                
                                toolCalls.add(ToolCall(toolName, parameters))
                                Log.d(TAG, "Parsed legacy tool call: $toolName with parameters: $parameters")
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Error parsing legacy tool call: $trimmedLine", e)
                        }
                    }
                }
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing tool calls", e)
        }
        
        Log.d(TAG, "Found ${toolCalls.size} tool calls in response")
        return toolCalls
    }
    
    /**
     * Parse a single JSON tool call
     */
    private fun parseJsonToolCall(jsonString: String): ToolCall? {
        return try {
            // Basic JSON parsing for tool calls
            val trimmed = jsonString.trim()
            
            // Extract tool name
            val nameMatch = Regex(""""name"\s*:\s*"([^"]+)"""").find(trimmed)
            val toolName = nameMatch?.groupValues?.get(1)?.uppercase()
            
            if (toolName == null) {
                Log.w(TAG, "Could not extract tool name from: $trimmed")
                return null
            }
            
            // Extract arguments based on tool type
            val parameters = when (toolName) {
                "CALCULATOR" -> {
                    val exprMatch = Regex(""""expression"\s*:\s*"([^"]+)"""").find(trimmed)
                    exprMatch?.groupValues?.get(1) ?: ""
                }
                "WEATHER" -> {
                    val locMatch = Regex(""""location"\s*:\s*"([^"]+)"""").find(trimmed)
                    locMatch?.groupValues?.get(1) ?: ""
                }
                "CALENDAR" -> {
                    val actionMatch = Regex(""""action"\s*:\s*"([^"]+)"""").find(trimmed)
                    val detailsMatch = Regex(""""details"\s*:\s*"([^"]+)"""").find(trimmed)
                    val action = actionMatch?.groupValues?.get(1) ?: ""
                    val details = detailsMatch?.groupValues?.get(1) ?: ""
                    if (action.isNotEmpty() && details.isNotEmpty()) "$action:$details" else ""
                }
                else -> {
                    Log.w(TAG, "Unknown tool name: $toolName")
                    ""
                }
            }
            
            if (parameters.isNotEmpty()) {
                ToolCall(toolName, parameters)
            } else {
                Log.w(TAG, "Could not extract parameters for tool: $toolName from: $trimmed")
                null
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing JSON tool call: $jsonString", e)
            null
        }
    }
    
    /**
     * Builds a formatted prompt including tool results
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
        
        Log.d(TAG, "Building formatted prompt with tool results for $currentModelType")
        
        val prompt = StringBuilder()
        
        // Get appropriate formatting tokens based on model type
        val (systemStart, userStart, assistantStart, endToken) = when (currentModelType) {
            ModelType.QWEN -> arrayOf(CHATML_SYSTEM_START, CHATML_USER_START, CHATML_ASSISTANT_START, CHATML_END_TOKEN)
            ModelType.GEMMA -> arrayOf(GEMMA_SYSTEM_START, GEMMA_USER_START, GEMMA_ASSISTANT_START, GEMMA_END_TOKEN)
        }
        
        // Add system role with proper tags
        prompt.append(systemStart)
        prompt.append(systemPrompt)
        prompt.append(endToken)
        
        // Add conversation context if needed using proper format
        if (includeContext && conversationHistory.isNotEmpty()) {
            val recentHistory = conversationHistory.takeLast(3)
            for (i in recentHistory.indices step 2) {
                if (i < recentHistory.size) {
                    // User message
                    val userMsg = recentHistory[i].removePrefix("User: ")
                    prompt.append(userStart)
                    prompt.append(userMsg)
                    prompt.append(endToken)
                    
                    // Assistant message (if exists)
                    if (i + 1 < recentHistory.size) {
                        val assistantMsg = recentHistory[i + 1].removePrefix("Assistant: ")
                        prompt.append(assistantStart)
                        prompt.append(assistantMsg)
                        prompt.append(endToken)
                    }
                }
            }
        }
        
        // Add current user input with tool usage enhancement
        val enhancedUserInput = enhanceUserInputForToolUsage(userInput)
        prompt.append(userStart)
        prompt.append(enhancedUserInput)
        prompt.append(endToken)
        
        // Add tool results as a system message
        prompt.append(systemStart)
        prompt.append("Tool Results:\n")
        toolResults.forEach { result ->
            if (result.success) {
                prompt.append("${result.toolName}: ${result.result}\n")
            } else {
                prompt.append("${result.toolName}: Error - ${result.error}\n")
            }
        }
        prompt.append("\nPlease provide a natural response incorporating these tool results.")
        prompt.append(endToken)
        
        // Start assistant response (model will complete this)
        prompt.append(assistantStart)
        
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
     * Debug method to log the complete formatted prompt structure
     */
    fun logChatMLPrompt(prompt: String) {
        // Always log the prompt structure to verify it's being sent correctly
        Log.i(TAG, "=== COMPLETE ${currentModelType} PROMPT ===")
        Log.i(TAG, "Prompt length: ${prompt.length} characters")
        Log.i(TAG, "System prompt section: ${prompt.substringBefore("<|im_start|>user").substringBefore("<start_of_turn>user")}")
        Log.i(TAG, "Full prompt:")
        Log.i(TAG, prompt)
        Log.i(TAG, "=== END ${currentModelType} PROMPT ===")
    }
    
    /**
     * Validates prompt format for current model type
     */
    fun validateChatMLFormat(prompt: String): Boolean {
        val (systemStart, userStart, assistantStart, _) = when (currentModelType) {
            ModelType.QWEN -> arrayOf(CHATML_SYSTEM_START, CHATML_USER_START, CHATML_ASSISTANT_START, CHATML_END_TOKEN)
            ModelType.GEMMA -> arrayOf(GEMMA_SYSTEM_START, GEMMA_USER_START, GEMMA_ASSISTANT_START, GEMMA_END_TOKEN)
        }
        
        val hasSystemStart = prompt.contains(systemStart)
        val hasUserStart = prompt.contains(userStart)
        val hasAssistantStart = prompt.contains(assistantStart)
        val endsWithAssistantStart = prompt.trimEnd().endsWith(assistantStart.trimEnd())
        
        Log.d(TAG, "$currentModelType format validation - System: $hasSystemStart, User: $hasUserStart, Assistant: $hasAssistantStart, EndsCorrectly: $endsWithAssistantStart")
        
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