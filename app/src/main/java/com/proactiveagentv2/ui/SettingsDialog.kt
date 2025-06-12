package com.proactiveagentv2.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import java.io.File

data class SettingsState(
    val speechThreshold: Float = 0.5f,
    val silenceThreshold: Float = 0.3f,
    val minSpeechDurationMs: Long = 300L,
    val maxSilenceDurationMs: Long = 800L,
    val selectedModelFile: File? = null
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsDialog(
    isVisible: Boolean,
    currentSettings: SettingsState,
    availableModels: List<File> = emptyList(),
    onDismiss: () -> Unit,
    onSaveSettings: (SettingsState) -> Unit
) {
    if (!isVisible) return

    var localSettings by remember(currentSettings) { mutableStateOf(currentSettings) }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.9f)
                .padding(16.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
        ) {
            Column(
                modifier = Modifier.fillMaxSize()
            ) {
                // Header
                Text(
                    text = "Settings",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(20.dp)
                )
                
                // Scrollable content
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // STT Model Selection
                    Text(
                        text = "Speech-to-Text Model",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.primary
                    )

                    if (availableModels.isNotEmpty()) {
                        var expanded by remember { mutableStateOf(false) }
                        
                        ExposedDropdownMenuBox(
                            expanded = expanded,
                            onExpandedChange = { expanded = !expanded }
                        ) {
                            OutlinedTextField(
                                value = localSettings.selectedModelFile?.name ?: "No model selected",
                                onValueChange = {},
                                readOnly = true,
                                label = { Text("Model") },
                                trailingIcon = {
                                    Icon(
                                        imageVector = Icons.Default.KeyboardArrowDown,
                                        contentDescription = "Dropdown"
                                    )
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .menuAnchor()
                            )
                            
                            ExposedDropdownMenu(
                                expanded = expanded,
                                onDismissRequest = { expanded = false }
                            ) {
                                availableModels.forEach { model ->
                                    DropdownMenuItem(
                                        text = { Text(model.name) },
                                        onClick = {
                                            localSettings = localSettings.copy(selectedModelFile = model)
                                            expanded = false
                                        }
                                    )
                                }
                            }
                        }
                    }

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

                    // Add some bottom padding to ensure content doesn't hide behind buttons
                    Spacer(modifier = Modifier.height(16.dp))
                }

                // Fixed Action Buttons at bottom
                Divider()
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp),
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