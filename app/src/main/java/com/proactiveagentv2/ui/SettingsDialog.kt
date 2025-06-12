package com.proactiveagentv2.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.proactiveagentv2.llm.LLMManager
import com.proactiveagentv2.llm.LLMModelInfo
import com.proactiveagentv2.llm.ModelDownloadStatus
import com.proactiveagentv2.llm.DownloadProgress
import kotlinx.coroutines.launch
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
    llmManager: LLMManager? = null,
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

                    // LLM Model Section
                    if (llmManager != null) {
                        LLMModelSection(llmManager = llmManager)
                        Divider()
                    }

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

@Composable
private fun LLMModelSection(llmManager: LLMManager) {
    val scope = rememberCoroutineScope()
    val modelInfo = LLMManager.QWEN_MODEL
    
    var modelStatus by remember { mutableStateOf(llmManager.getModelStatus(modelInfo)) }
    var downloadProgress by remember { mutableStateOf<DownloadProgress?>(null) }
    var isProcessing by remember { mutableStateOf(false) }
    
    Text(
        text = "AI Language Model",
        fontSize = 16.sp,
        fontWeight = FontWeight.Medium,
        color = MaterialTheme.colorScheme.primary
    )
    
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Model Info
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = modelInfo.name,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = "Size: ${llmManager.getModelSize(modelInfo)}",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                
                // Status indicator
                when (modelStatus) {
                    ModelDownloadStatus.DOWNLOADED -> {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = "Downloaded",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                    ModelDownloadStatus.NOT_DOWNLOADED -> {
                        Icon(
                            imageVector = Icons.Default.CloudDownload,
                            contentDescription = "Not downloaded",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                    ModelDownloadStatus.DOWNLOADING -> {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            strokeWidth = 2.dp
                        )
                    }
                    ModelDownloadStatus.ERROR -> {
                        Icon(
                            imageVector = Icons.Default.Error,
                            contentDescription = "Error",
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }
            }
            
            // Download progress
            downloadProgress?.let { progress ->
                Column {
                    LinearProgressIndicator(
                        progress = { progress.percentage / 100f },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "${progress.percentage}% (${progress.downloadedBytes / (1024 * 1024)} MB / ${progress.totalBytes / (1024 * 1024)} MB)",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            
            // Action buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                when (modelStatus) {
                    ModelDownloadStatus.NOT_DOWNLOADED, ModelDownloadStatus.ERROR -> {
                        Button(
                            onClick = {
                                if (!isProcessing) {
                                    isProcessing = true
                                    modelStatus = ModelDownloadStatus.DOWNLOADING
                                    scope.launch {
                                        val success = llmManager.downloadModel(modelInfo) { progress ->
                                            downloadProgress = progress
                                        }
                                        
                                        if (success) {
                                            modelStatus = ModelDownloadStatus.DOWNLOADED
                                            // Initialize the model after download
                                            llmManager.initializeModel(modelInfo)
                                        } else {
                                            modelStatus = ModelDownloadStatus.ERROR
                                        }
                                        
                                        downloadProgress = null
                                        isProcessing = false
                                    }
                                }
                            },
                            enabled = !isProcessing,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Download Model")
                        }
                    }
                    
                    ModelDownloadStatus.DOWNLOADED -> {
                        OutlinedButton(
                            onClick = {
                                if (!isProcessing) {
                                    isProcessing = true
                                    scope.launch {
                                        if (llmManager.deleteModel(modelInfo)) {
                                            modelStatus = ModelDownloadStatus.NOT_DOWNLOADED
                                        }
                                        isProcessing = false
                                    }
                                }
                            },
                            enabled = !isProcessing,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Delete Model")
                        }
                        
                        Button(
                            onClick = {
                                if (!isProcessing) {
                                    isProcessing = true
                                    scope.launch {
                                        llmManager.initializeModel(modelInfo)
                                        isProcessing = false
                                    }
                                }
                            },
                            enabled = !isProcessing && !llmManager.isModelInitialized(),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(if (llmManager.isModelInitialized()) "Ready" else "Initialize")
                        }
                    }
                    
                    ModelDownloadStatus.DOWNLOADING -> {
                        Button(
                            onClick = { /* TODO: Cancel download */ },
                            enabled = false,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Downloading...")
                        }
                    }
                }
            }
            
            // Model description
            Text(
                text = modelInfo.description,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 16.sp
            )
        }
    }
}