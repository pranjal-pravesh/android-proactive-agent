package com.proactiveagentv2.tools

import android.util.Log
import com.proactiveagentv2.llm.ToolCall
import com.proactiveagentv2.llm.ToolResult
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/**
 * Manages all available tools and handles tool execution for the LLM
 */
class ToolManager {
    
    companion object {
        private const val TAG = "ToolManager"
    }
    
    private val tools: Map<String, Tool> = mapOf(
        "CALCULATOR" to CalculatorTool(),
        "WEATHER" to WeatherTool(),
        "CALENDAR" to CalendarTool()
    )
    
    /**
     * Gets all available tools
     */
    fun getAvailableTools(): List<Tool> {
        Log.d(TAG, "Available tools: ${tools.keys.joinToString(", ")}")
        return tools.values.toList()
    }
    
    /**
     * Gets a specific tool by name
     */
    fun getTool(name: String): Tool? {
        return tools[name.uppercase()]
    }
    
    /**
     * Executes a single tool call
     */
    suspend fun executeTool(toolCall: ToolCall): ToolResult {
        Log.d(TAG, "Executing tool: ${toolCall.toolName} with parameters: ${toolCall.parameters}")
        
        val tool = getTool(toolCall.toolName)
        
        return if (tool != null) {
            try {
                tool.execute(toolCall.parameters)
            } catch (e: Exception) {
                Log.e(TAG, "Error executing tool ${toolCall.toolName}", e)
                ToolResult(
                    toolName = toolCall.toolName,
                    result = "",
                    success = false,
                    error = "Tool execution failed: ${e.message}"
                )
            }
        } else {
            Log.w(TAG, "Unknown tool: ${toolCall.toolName}")
            ToolResult(
                toolName = toolCall.toolName,
                result = "",
                success = false,
                error = "Unknown tool: ${toolCall.toolName}"
            )
        }
    }
    
    /**
     * Executes multiple tool calls in parallel
     */
    suspend fun executeTools(toolCalls: List<ToolCall>): List<ToolResult> = coroutineScope {
        Log.d(TAG, "Executing ${toolCalls.size} tools in parallel")
        
        val results = toolCalls.map { toolCall ->
            async {
                executeTool(toolCall)
            }
        }.awaitAll()
        
        Log.d(TAG, "All tools executed. Results: ${results.size} total")
        results
    }
    
    /**
     * Validates if a tool call is properly formatted
     */
    fun validateToolCall(toolCall: ToolCall): Boolean {
        val tool = getTool(toolCall.toolName)
        return tool?.validateParameters(toolCall.parameters) ?: false
    }
    
    /**
     * Gets tool descriptions for prompt building
     */
    fun getToolDescriptions(): String {
        return buildString {
            append("Available Tools:\n")
            tools.values.forEach { tool ->
                append("- ${tool.name}: ${tool.description}\n")
            }
        }
    }
    
    /**
     * Checks if a tool name is valid
     */
    fun isValidTool(toolName: String): Boolean {
        return tools.containsKey(toolName.uppercase())
    }
    
    /**
     * Gets statistics about tool usage
     */
    fun getToolStats(): Map<String, Any> {
        return mapOf(
            "totalTools" to tools.size,
            "availableTools" to tools.keys.toList(),
            "toolDescriptions" to tools.mapValues { it.value.description }
        )
    }
} 