package com.example.proactiiveagentv1.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun VoiceDetectionScreen(
    isListening: Boolean,
    isSpeaking: Boolean,
    vadConfidence: Float,
    isModelLoaded: Boolean,
    vadThreshold: Float,
    onStartDetection: () -> Unit,
    onStopDetection: () -> Unit
) {
    Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // Title
            Text(
                text = "Voice Activity Detection",
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 40.dp)
            )
            
            // Status indicator
            StatusCard(
                isListening = isListening,
                isSpeaking = isSpeaking,
                vadConfidence = vadConfidence
            )
            
            // VAD confidence visualization
            if (isListening) {
                VADVisualization(
                    vadConfidence = vadConfidence,
                    vadThreshold = vadThreshold
                )
            }
            
            // Control buttons
            ControlButtons(
                isListening = isListening,
                isModelLoaded = isModelLoaded,
                onStartDetection = onStartDetection,
                onStopDetection = onStopDetection
            )
            
            // Status info
            Spacer(modifier = Modifier.height(40.dp))
            
            InfoCard()
            
            // Model status
            if (!isModelLoaded) {
                Spacer(modifier = Modifier.height(16.dp))
                ModelErrorCard()
            }
        }
    }
}

@Composable
private fun StatusCard(
    isListening: Boolean,
    isSpeaking: Boolean,
    vadConfidence: Float
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 32.dp),
        colors = CardDefaults.cardColors(
            containerColor = when {
                !isListening -> Color.Gray
                isSpeaking -> Color.Green
                else -> Color.Blue
            }
        )
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = when {
                    !isListening -> "🔇 Ready"
                    isSpeaking -> "🗣️ Speech Detected"
                    else -> "👂 Listening..."
                },
                fontSize = 20.sp,
                fontWeight = FontWeight.Medium,
                color = Color.White
            )
            
            if (isListening) {
                Text(
                    text = "Confidence: ${(vadConfidence * 100).toInt()}%",
                    fontSize = 16.sp,
                    color = Color.White.copy(alpha = 0.9f),
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
        }
    }
}

@Composable
private fun VADVisualization(
    vadConfidence: Float,
    vadThreshold: Float
) {
    Text(
        text = "Voice Activity: ${(vadConfidence * 100).toInt()}%",
        fontSize = 18.sp,
        fontWeight = FontWeight.Medium,
        modifier = Modifier.padding(bottom = 16.dp)
    )
    
    LinearProgressIndicator(
        progress = { vadConfidence },
        modifier = Modifier
            .fillMaxWidth()
            .height(16.dp)
            .padding(bottom = 32.dp),
        color = if (vadConfidence > vadThreshold) Color.Green else Color.Blue
    )
}

@Composable
private fun ControlButtons(
    isListening: Boolean,
    isModelLoaded: Boolean,
    onStartDetection: () -> Unit,
    onStopDetection: () -> Unit
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        Button(
            onClick = onStartDetection,
            enabled = !isListening && isModelLoaded,
            modifier = Modifier
                .height(56.dp)
                .weight(1f)
        ) {
            Text("Start Detection", fontSize = 16.sp)
        }
        
        Button(
            onClick = onStopDetection,
            enabled = isListening,
            colors = ButtonDefaults.buttonColors(
                containerColor = Color.Red
            ),
            modifier = Modifier
                .height(56.dp)
                .weight(1f)
        ) {
            Text("Stop Detection", fontSize = 16.sp)
        }
    }
}

@Composable
private fun InfoCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier.padding(20.dp)
        ) {
            Text(
                text = "Silero VAD",
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = "• Neural network voice detection\n" +
                        "• Real-time processing\n" +
                        "• High accuracy speech recognition",
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
    }
}

@Composable
private fun ModelErrorCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = Color.Red.copy(alpha = 0.1f)
        )
    ) {
        Text(
            text = "⚠️ Model not loaded\n\nEnsure 'silero_vad.onnx' is in assets folder",
            fontSize = 14.sp,
            color = Color.Red,
            modifier = Modifier.padding(16.dp)
        )
    }
} 