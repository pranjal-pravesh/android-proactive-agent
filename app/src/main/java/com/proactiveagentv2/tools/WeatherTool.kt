package com.proactiveagentv2.tools

import android.util.Log
import com.proactiveagentv2.llm.ToolResult
import kotlinx.coroutines.delay
import java.util.*

/**
 * Weather tool for fetching weather information
 * This is a placeholder implementation - in production you'd integrate with a real weather API
 */
class WeatherTool : Tool {
    
    override val name: String = "WEATHER"
    override val description: String = "Provides current weather information and forecasts for specified locations"
    
    companion object {
        private const val TAG = "WeatherTool"
        
        // Placeholder weather data for demo purposes
        private val mockWeatherData = mapOf(
            "new york" to WeatherInfo("New York", 72, "Partly Cloudy", "Light breeze from the west"),
            "london" to WeatherInfo("London", 65, "Overcast", "Cloudy with chance of rain"),
            "tokyo" to WeatherInfo("Tokyo", 78, "Clear", "Sunny and warm"),
            "paris" to WeatherInfo("Paris", 68, "Partly Cloudy", "Pleasant with scattered clouds"),
            "berlin" to WeatherInfo("Berlin", 63, "Rainy", "Light rain throughout the day"),
            "sydney" to WeatherInfo("Sydney", 75, "Sunny", "Beautiful clear skies"),
            "mumbai" to WeatherInfo("Mumbai", 85, "Humid", "Hot and humid conditions"),
            "los angeles" to WeatherInfo("Los Angeles", 82, "Sunny", "Clear skies, perfect weather")
        )
    }
    
    override suspend fun execute(parameters: String): ToolResult {
        Log.d(TAG, "Fetching weather for: $parameters")
        
        return try {
            if (!validateParameters(parameters)) {
                return ToolResult(
                    toolName = name,
                    result = "",
                    success = false,
                    error = "Invalid location format"
                )
            }
            
            // Simulate API call delay
            delay(500)
            
            val weatherInfo = getWeatherInfo(parameters.trim())
            
            Log.d(TAG, "Weather info retrieved: $weatherInfo")
            
            ToolResult(
                toolName = name,
                result = formatWeatherInfo(weatherInfo),
                success = true
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching weather: ${e.message}", e)
            ToolResult(
                toolName = name,
                result = "",
                success = false,
                error = "Weather service error: ${e.message}"
            )
        }
    }
    
    override fun validateParameters(parameters: String): Boolean {
        if (parameters.isBlank()) return false
        
        // Basic validation - location should contain letters and common location characters
        val validChars = Regex("[a-zA-Z0-9\\s,.-]")
        return parameters.all { char ->
            validChars.matches(char.toString())
        } && parameters.length <= 100
    }
    
    /**
     * Fetches weather information for a given location
     * This is a mock implementation - replace with real weather API integration
     */
    private fun getWeatherInfo(location: String): WeatherInfo {
        val normalizedLocation = location.lowercase().trim()
        
        // Check if we have mock data for this location
        return mockWeatherData[normalizedLocation] 
            ?: getDefaultWeatherInfo(location)
    }
    
    /**
     * Generates default weather info for unknown locations
     */
    private fun getDefaultWeatherInfo(location: String): WeatherInfo {
        // Generate pseudo-random weather based on location name for demo
        val locationHash = location.hashCode().let { if (it < 0) -it else it }
        val temperature = 60 + (locationHash % 30) // Temperature between 60-89°F
        
        val conditions = listOf("Sunny", "Partly Cloudy", "Cloudy", "Clear", "Overcast")
        val condition = conditions[locationHash % conditions.size]
        
        val descriptions = listOf(
            "Pleasant weather conditions",
            "Typical for this time of year",
            "Comfortable outdoor weather",
            "Good day to be outside",
            "Moderate weather conditions"
        )
        val description = descriptions[locationHash % descriptions.size]
        
        return WeatherInfo(location, temperature, condition, description)
    }
    
    /**
     * Formats weather information into a readable string
     */
    private fun formatWeatherInfo(weather: WeatherInfo): String {
        return buildString {
            append("Weather in ${weather.location}:\n")
            append("Temperature: ${weather.temperature}°F\n")
            append("Conditions: ${weather.condition}\n")
            append("Details: ${weather.description}")
        }
    }
    
    /**
     * Data class to hold weather information
     */
    private data class WeatherInfo(
        val location: String,
        val temperature: Int,
        val condition: String,
        val description: String
    )
    
    /**
     * TODO: Replace with real weather API integration
     * 
     * To implement real weather functionality:
     * 1. Add weather API dependency (e.g., OpenWeatherMap, WeatherAPI)
     * 2. Add API key to your configuration
     * 3. Replace getWeatherInfo() with actual API calls
     * 4. Handle API errors and rate limiting
     * 5. Add location geocoding for better location parsing
     * 6. Cache weather data to avoid excessive API calls
     * 
     * Example API integration:
     * ```kotlin
     * private suspend fun fetchRealWeather(location: String): WeatherInfo {
     *     val apiKey = "your_api_key"
     *     val url = "https://api.openweathermap.org/data/2.5/weather?q=$location&appid=$apiKey&units=imperial"
     *     
     *     val response = httpClient.get(url)
     *     val weatherData = Json.decodeFromString<WeatherApiResponse>(response.bodyAsText())
     *     
     *     return WeatherInfo(
     *         location = weatherData.name,
     *         temperature = weatherData.main.temp.toInt(),
     *         condition = weatherData.weather[0].main,
     *         description = weatherData.weather[0].description
     *     )
     * }
     * ```
     */
} 