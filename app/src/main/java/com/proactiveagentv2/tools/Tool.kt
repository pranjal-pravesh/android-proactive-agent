package com.proactiveagentv2.tools

import com.proactiveagentv2.llm.ToolResult

/**
 * Base interface for all tools that can be called by the LLM
 */
interface Tool {
    /**
     * The name of the tool (should match the tool name in prompts)
     */
    val name: String
    
    /**
     * Description of what this tool does
     */
    val description: String
    
    /**
     * Execute the tool with given parameters
     * @param parameters The parameters string from the LLM
     * @return ToolResult containing the result or error
     */
    suspend fun execute(parameters: String): ToolResult
    
    /**
     * Validate if the parameters are in the correct format
     * @param parameters The parameters to validate
     * @return true if valid, false otherwise
     */
    fun validateParameters(parameters: String): Boolean
} 