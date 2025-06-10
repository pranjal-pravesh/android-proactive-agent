package com.example.proactiiveagentv1.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.proactiiveagentv1.settings.VadSettings

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    currentSettings: VadSettings,
    onSettingsChanged: (VadSettings) -> Unit,
    onBackPressed: () -> Unit
) {
    var minSpeechDuration by remember { mutableFloatStateOf(currentSettings.minimumSpeechDurationMs.toFloat()) }
    var silenceTimeout by remember { mutableFloatStateOf(currentSettings.silenceTimeoutMs.toFloat()) }
    var vadThreshold by remember { mutableFloatStateOf(currentSettings.vadThreshold) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("VAD Settings") },
                navigationIcon = {
                    IconButton(onClick = onBackPressed) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            Text(
                text = "Voice Activity Detection Settings",
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold
            )
            
            Text(
                text = "Configure timing parameters for speech detection",
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            
            Spacer(modifier = Modifier.height(8.dp))
            
            // Minimum Speech Duration Setting
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Minimum Speech Duration",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = "How long speech must continue before being detected",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 12.dp)
                    )
                    
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "${minSpeechDuration.toInt()}ms",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.width(60.dp)
                        )
                        Slider(
                            value = minSpeechDuration,
                            onValueChange = { 
                                minSpeechDuration = it
                                onSettingsChanged(
                                    currentSettings.copy(
                                        minimumSpeechDurationMs = it.toLong(),
                                        silenceTimeoutMs = silenceTimeout.toLong(),
                                        vadThreshold = vadThreshold
                                    )
                                )
                            },
                            valueRange = 50f..1000f,
                            steps = 19, // 50ms steps
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
            
            // Silence Timeout Setting
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Silence Timeout",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = "How long silence must continue before speech ends",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 12.dp)
                    )
                    
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "${silenceTimeout.toInt()}ms",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.width(60.dp)
                        )
                        Slider(
                            value = silenceTimeout,
                            onValueChange = { 
                                silenceTimeout = it
                                onSettingsChanged(
                                    currentSettings.copy(
                                        minimumSpeechDurationMs = minSpeechDuration.toLong(),
                                        silenceTimeoutMs = it.toLong(),
                                        vadThreshold = vadThreshold
                                    )
                                )
                            },
                            valueRange = 100f..2000f,
                            steps = 19, // 100ms steps
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
            
            // VAD Threshold Setting
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Voice Activity Threshold",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = "Sensitivity level for voice detection (higher = less sensitive)",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 12.dp)
                    )
                    
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "${(vadThreshold * 100).toInt()}%",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.width(60.dp)
                        )
                        Slider(
                            value = vadThreshold,
                            onValueChange = { 
                                vadThreshold = it
                                onSettingsChanged(
                                    currentSettings.copy(
                                        minimumSpeechDurationMs = minSpeechDuration.toLong(),
                                        silenceTimeoutMs = silenceTimeout.toLong(),
                                        vadThreshold = it
                                    )
                                )
                            },
                            valueRange = 0.1f..0.9f,
                            steps = 8, // 0.1 steps
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
            
            // Reset to defaults button
            Button(
                onClick = {
                    val defaultSettings = VadSettings()
                    minSpeechDuration = defaultSettings.minimumSpeechDurationMs.toFloat()
                    silenceTimeout = defaultSettings.silenceTimeoutMs.toFloat()
                    vadThreshold = defaultSettings.vadThreshold
                    onSettingsChanged(defaultSettings)
                },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.secondary
                )
            ) {
                Text("Reset to Defaults")
            }
        }
    }
} 