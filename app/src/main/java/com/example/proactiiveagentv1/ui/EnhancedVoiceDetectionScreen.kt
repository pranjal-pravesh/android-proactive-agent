package com.example.proactiiveagentv1.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.proactiiveagentv1.whisper.WhisperSegment

@Composable
fun EnhancedVoiceDetectionScreen(
    isListening: Boolean,
    isSpeaking: Boolean,
    vadConfidence: Float,
    isModelLoaded: Boolean,
    isTranscriptionReady: Boolean,
    transcriptionText: String,
    transcriptionSegments: List<WhisperSegment>,
    vadThreshold: Float,
    onStartDetection: () -> Unit,
    onStopDetection: () -> Unit
) {
    Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp)
        ) {
            // Title
            Text(
                text = "Enhanced Voice Detection & Transcription",
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 20.dp)
            )
            
            // Status indicators
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                StatusCard(
                    title = "VAD",
                    status = when {
                        !isListening -> "Ready"
                        isSpeaking -> "Speaking"
                        else -> "Listening"
                    },
                    confidence = vadConfidence,
                    color = when {
                        !isListening -> Color.Gray
                        isSpeaking -> Color.Green
                        else -> Color.Blue
                    },
                    modifier = Modifier.weight(1f)
                )
                
                StatusCard(
                    title = "Transcription",
                    status = if (isTranscriptionReady) "Ready" else "Loading",
                    confidence = 1f,
                    color = if (isTranscriptionReady) Color.Green else Color.Cyan,
                    modifier = Modifier.weight(1f)
                )
            }
            
            // VAD visualization
            if (isListening) {
                Text(
                    text = "Voice Activity: ${(vadConfidence * 100).toInt()}%",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                
                LinearProgressIndicator(
                    progress = { vadConfidence },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(12.dp)
                        .padding(bottom = 16.dp),
                    color = if (vadConfidence > vadThreshold) Color.Green else Color.Blue
                )
            }
            
            // Control buttons
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Button(
                    onClick = onStartDetection,
                    enabled = !isListening && isModelLoaded && isTranscriptionReady,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Start Detection")
                }
                
                Button(
                    onClick = onStopDetection,
                    enabled = isListening,
                    colors = ButtonDefaults.buttonColors(containerColor = Color.Red),
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Stop Detection")
                }
            }
            
            // Transcription results
            if (isTranscriptionReady) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "Live Transcription",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.padding(bottom = 12.dp)
                        )
                        
                        if (transcriptionSegments.isNotEmpty()) {
                            LazyColumn(
                                modifier = Modifier.fillMaxSize(),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                items(transcriptionSegments) { segment ->
                                    TranscriptionItem(segment = segment)
                                }
                            }
                        } else {
                            Text(
                                text = "Waiting for speech...",
                                fontSize = 14.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                            )
                        }
                    }
                }
            } else {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = Color.Cyan.copy(alpha = 0.1f)
                    )
                ) {
                    Text(
                        text = "⚠️ Loading Whisper model...\n\nEnsure whisper model is in assets/models/ folder",
                        fontSize = 14.sp,
                        color = Color.Cyan,
                        modifier = Modifier.padding(16.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun StatusCard(
    title: String,
    status: String,
    confidence: Float,
    color: Color,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = color)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = title,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = Color.White
            )
            Text(
                text = status,
                fontSize = 12.sp,
                color = Color.White.copy(alpha = 0.9f)
            )
            if (title == "VAD") {
                Text(
                    text = "${(confidence * 100).toInt()}%",
                    fontSize = 11.sp,
                    color = Color.White.copy(alpha = 0.8f)
                )
            }
        }
    }
}

@Composable
private fun TranscriptionItem(segment: WhisperSegment) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = segment.getFormattedTimeRange(),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = segment.text.trim(),
                fontSize = 14.sp,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
} 