package com.proactiveagentv2.tools

import android.util.Log
import com.proactiveagentv2.llm.ToolResult
import kotlin.math.*
import java.text.DecimalFormat

/**
 * Calculator tool for mathematical computations
 * Supports basic arithmetic, scientific functions, and common mathematical operations
 */
class CalculatorTool : Tool {
    
    override val name: String = "CALCULATOR"
    override val description: String = "Performs mathematical calculations and evaluates expressions"
    
    companion object {
        private const val TAG = "CalculatorTool"
        private val decimalFormat = DecimalFormat("#.##########")
    }
    
    override suspend fun execute(parameters: String): ToolResult {
        Log.d(TAG, "Executing calculation: $parameters")
        
        return try {
            if (!validateParameters(parameters)) {
                return ToolResult(
                    toolName = name,
                    result = "",
                    success = false,
                    error = "Invalid mathematical expression"
                )
            }
            
            val result = evaluateExpression(parameters.trim())
            val formattedResult = formatResult(result)
            
            Log.d(TAG, "Calculation result: $formattedResult")
            
            ToolResult(
                toolName = name,
                result = formattedResult,
                success = true
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error in calculation: ${e.message}", e)
            ToolResult(
                toolName = name,
                result = "",
                success = false,
                error = "Calculation error: ${e.message}"
            )
        }
    }
    
    override fun validateParameters(parameters: String): Boolean {
        if (parameters.isBlank()) return false
        
        // Basic validation - check for valid characters
        val validChars = Regex("[0-9+\\-*/().,\\s\\^√πe]")
        return parameters.all { char ->
            validChars.matches(char.toString()) || 
            char.toString().lowercase() in listOf("s", "i", "n", "c", "o", "t", "a", "l", "g", "r", "q")
        }
    }
    
    /**
     * Evaluates a mathematical expression
     * This is a placeholder implementation - in a real app, you'd use a proper math parser
     */
    private fun evaluateExpression(expression: String): Double {
        // For now, we'll handle basic arithmetic operations
        // In a production app, you'd use a proper expression parser like exp4j or similar
        
        var expr = expression.lowercase()
            .replace("π", PI.toString())
            .replace("pi", PI.toString())
            .replace("e", E.toString())
            .replace("√", "sqrt")
            .replace("^", "pow")
        
        // Handle basic operations using Kotlin's built-in capabilities
        // This is a simplified implementation - real calculator would need proper parsing
        
        return when {
            expr.contains("sqrt(") -> handleSqrt(expr)
            expr.contains("sin(") -> handleTrig(expr, "sin")
            expr.contains("cos(") -> handleTrig(expr, "cos")
            expr.contains("tan(") -> handleTrig(expr, "tan")
            expr.contains("log(") -> handleLog(expr)
            else -> evaluateBasicArithmetic(expr)
        }
    }
    
    private fun handleSqrt(expr: String): Double {
        val regex = Regex("sqrt\\(([^)]+)\\)")
        val match = regex.find(expr)
        return if (match != null) {
            val value = match.groupValues[1].toDouble()
            sqrt(value)
        } else {
            evaluateBasicArithmetic(expr)
        }
    }
    
    private fun handleTrig(expr: String, function: String): Double {
        val regex = Regex("$function\\(([^)]+)\\)")
        val match = regex.find(expr)
        return if (match != null) {
            val value = match.groupValues[1].toDouble()
            when (function) {
                "sin" -> sin(value)
                "cos" -> cos(value)
                "tan" -> tan(value)
                else -> evaluateBasicArithmetic(expr)
            }
        } else {
            evaluateBasicArithmetic(expr)
        }
    }
    
    private fun handleLog(expr: String): Double {
        val regex = Regex("log\\(([^)]+)\\)")
        val match = regex.find(expr)
        return if (match != null) {
            val value = match.groupValues[1].toDouble()
            log10(value)
        } else {
            evaluateBasicArithmetic(expr)
        }
    }
    
    /**
     * Evaluates basic arithmetic expressions
     * This is a simplified implementation - production code would use a proper parser
     */
    private fun evaluateBasicArithmetic(expr: String): Double {
        // Remove spaces
        val cleanExpr = expr.replace(" ", "")
        
        try {
            // For basic expressions, we can use a simple evaluation
            // This is just a placeholder - real implementation would parse properly
            
            // Handle simple cases first
            return when {
                cleanExpr.contains("+") && !cleanExpr.contains("*") && !cleanExpr.contains("/") -> {
                    cleanExpr.split("+").sumOf { it.toDouble() }
                }
                cleanExpr.contains("-") && cleanExpr.count { it == '-' } == 1 && !cleanExpr.startsWith("-") -> {
                    val parts = cleanExpr.split("-")
                    parts[0].toDouble() - parts[1].toDouble()
                }
                cleanExpr.contains("*") && !cleanExpr.contains("+") && !cleanExpr.contains("-") -> {
                    cleanExpr.split("*").fold(1.0) { acc, part -> acc * part.toDouble() }
                }
                cleanExpr.contains("/") && !cleanExpr.contains("+") && !cleanExpr.contains("-") -> {
                    val parts = cleanExpr.split("/")
                    parts.fold(parts[0].toDouble()) { acc, part -> 
                        if (acc == parts[0].toDouble()) acc else acc / part.toDouble()
                    }
                }
                else -> {
                    // For complex expressions, try to parse as double directly
                    // Or implement a proper expression evaluator
                    cleanExpr.toDouble()
                }
            }
        } catch (e: Exception) {
            throw IllegalArgumentException("Cannot evaluate expression: $expr")
        }
    }
    
    private fun formatResult(result: Double): String {
        return when {
            result.isNaN() -> "Not a Number"
            result.isInfinite() -> if (result > 0) "Infinity" else "-Infinity"
            result == result.toLong().toDouble() -> result.toLong().toString()
            else -> decimalFormat.format(result)
        }
    }
} 