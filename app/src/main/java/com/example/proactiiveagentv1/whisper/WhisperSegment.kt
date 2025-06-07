package com.example.proactiiveagentv1.whisper

data class WhisperSegment(
    val start: Long,
    val end: Long,
    val text: String
) {
    override fun toString(): String {
        return "[$start -> $end]: $text"
    }
    
    companion object {
        fun formatTime(timeMs: Long): String {
            val totalSeconds = timeMs / 1000
            val minutes = totalSeconds / 60
            val seconds = totalSeconds % 60
            val milliseconds = timeMs % 1000
            return String.format("%02d:%02d.%03d", minutes, seconds, milliseconds)
        }
    }
    
    fun getFormattedTimeRange(): String {
        return "${formatTime(start)} - ${formatTime(end)}"
    }
} 