package com.proactiveagentv2.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.proactiveagentv2.vad.VADManager

data class SettingsState(
    val speechThreshold: Float = 0.5f,
    val silenceThreshold: Float = 0.3f,
    val minSpeechDurationMs: Long = 300L,
    val maxSilenceDurationMs: Long = 800L
)

@Composable
fun SettingsDialog(
    isVisible: Boolean,
    currentSettings: SettingsState,
    onDismiss: () -> Unit,
    onSaveSettings: (SettingsState) -> Unit
) {
    if (!isVisible) return

    var localSettings by remember(currentSettings) { mutableStateOf(currentSettings) }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Header
                Text(
                    text = "Settings",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.fillMaxWidth()
                )
                
                Divider()

                // VAD Settings Section
                Text(
                    text = "Voice Activity Detection",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.primary
                )

                // Speech Threshold
                SettingsSlider(
                    label = "Speech Threshold",
                    value = localSettings.speechThreshold,
                    valueRange = 0f..1f,
                    steps = 99,
                    displayValue = "%.2f".format(localSettings.speechThreshold),
                    onValueChange = { localSettings = localSettings.copy(speechThreshold = it) }
                )

                // Silence Threshold
                SettingsSlider(
                    label = "Silence Threshold",
                    value = localSettings.silenceThreshold,
                    valueRange = 0f..1f,
                    steps = 99,
                    displayValue = "%.2f".format(localSettings.silenceThreshold),
                    onValueChange = { localSettings = localSettings.copy(silenceThreshold = it) }
                )

                // Min Speech Duration
                SettingsSlider(
                    label = "Min Speech Duration",
                    value = (localSettings.minSpeechDurationMs / 100f),
                    valueRange = 1f..50f,
                    steps = 49,
                    displayValue = "${localSettings.minSpeechDurationMs}ms",
                    onValueChange = { 
                        localSettings = localSettings.copy(minSpeechDurationMs = (it * 100).toLong())
                    }
                )

                // Max Silence Duration
                SettingsSlider(
                    label = "Max Silence Duration",
                    value = (localSettings.maxSilenceDurationMs / 100f),
                    valueRange = 1f..50f,
                    steps = 49,
                    displayValue = "${localSettings.maxSilenceDurationMs}ms",
                    onValueChange = { 
                        localSettings = localSettings.copy(maxSilenceDurationMs = (it * 100).toLong())
                    }
                )

                // Help Text
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Text(
                        text = "• Speech/Silence thresholds range from 0.0 to 1.0\n" +
                                "• Min speech duration: 100ms to 5000ms\n" +
                                "• Max silence duration: 100ms to 5000ms\n" +
                                "• Lower values = more responsive detection",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(12.dp)
                    )
                }

                // Future Settings Placeholder
                Text(
                    text = "Audio Settings",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.primary
                )
                
                Text(
                    text = "More audio settings will be added here in future updates.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 8.dp)
                )

                Divider()

                // Action Buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = onDismiss) {
                        Text("Cancel")
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = { 
                            onSaveSettings(localSettings)
                            onDismiss()
                        }
                    ) {
                        Text("Save")
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsSlider(
    label: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
    displayValue: String,
    onValueChange: (Float) -> Unit
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = displayValue,
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.primary
            )
        }
        
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            steps = steps,
            modifier = Modifier.fillMaxWidth()
        )
    }
} 