package com.example.proactiiveagentv1.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EnhancedVoiceDetectionScreen(
    isListening: Boolean,
    isSpeaking: Boolean,
    vadConfidence: Float,
    isModelLoaded: Boolean,
    vadThreshold: Float,
    onStartDetection: () -> Unit,
    onStopDetection: () -> Unit,
    onOpenSettings: () -> Unit
) {
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text("Voice Activity Detection") },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp)
        ) {
            
            // Status indicators
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                StatusCard(
                    title = "VAD Model",
                    status = if (isModelLoaded) "Loaded" else "Loading",
                    confidence = 1f,
                    color = if (isModelLoaded) Color.Green else Color.Gray,
                    modifier = Modifier.weight(1f)
                )
                
                StatusCard(
                    title = "Detection",
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
                    enabled = !isListening && isModelLoaded,
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
            
            // VAD Information
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Voice Activity Detection",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(bottom = 12.dp)
                    )
                    
                    Text(
                        text = "Using Silero VAD model for real-time voice activity detection.",
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    
                    Text(
                        text = "Threshold: ${(vadThreshold * 100).toInt()}%",
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
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
            if (title == "Detection") {
                Text(
                    text = "${(confidence * 100).toInt()}%",
                    fontSize = 11.sp,
                    color = Color.White.copy(alpha = 0.8f)
                )
            }
        }
    }
}

