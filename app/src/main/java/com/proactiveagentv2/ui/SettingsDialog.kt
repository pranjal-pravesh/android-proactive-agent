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
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.activity.compose.rememberLauncherForActivityResult
import android.net.Uri
import androidx.activity.result.contract.ActivityResultContracts
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
    val selectedModelFile: File? = null,
    val maxRecordingDurationMinutes: Int = 30, // 0 means never stop
    val ttsEnabled: Boolean = true,
    val ttsSpeechRate: Float = 1.0f,
    val ttsPitch: Float = 1.0f
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsDialog(
    isVisible: Boolean,
    currentSettings: SettingsState,
    availableModels: List<File> = emptyList(),
    llmManager: LLMManager? = null,
    onDismiss: () -> Unit,
    onSaveSettings: (SettingsState) -> Unit,
    onManageConversationHistory: () -> Unit = {}
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

                    Divider()

                    // Recording Settings Section
                    Text(
                        text = "Recording Settings",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.primary
                    )

                    // Recording Duration Setting
                    var recordingDurationExpanded by remember { mutableStateOf(false) }
                    val recordingDurationOptions = listOf(
                        0 to "Never stop",
                        1 to "1 minute",
                        5 to "5 minutes", 
                        10 to "10 minutes",
                        15 to "15 minutes",
                        30 to "30 minutes",
                        60 to "1 hour",
                        120 to "2 hours"
                    )
                    
                    ExposedDropdownMenuBox(
                        expanded = recordingDurationExpanded,
                        onExpandedChange = { recordingDurationExpanded = !recordingDurationExpanded }
                    ) {
                        OutlinedTextField(
                            value = recordingDurationOptions.find { it.first == localSettings.maxRecordingDurationMinutes }?.second ?: "Custom",
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("Maximum Recording Duration") },
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
                            expanded = recordingDurationExpanded,
                            onDismissRequest = { recordingDurationExpanded = false }
                        ) {
                            recordingDurationOptions.forEach { (minutes, label) ->
                                DropdownMenuItem(
                                    text = { Text(label) },
                                    onClick = {
                                        localSettings = localSettings.copy(maxRecordingDurationMinutes = minutes)
                                        recordingDurationExpanded = false
                                    }
                                )
                            }
                        }
                    }

                    // Add explanation text for the recording duration setting
                    Text(
                        text = when (localSettings.maxRecordingDurationMinutes) {
                            0 -> "Recording will continue until manually stopped"
                            else -> "Recording will automatically stop after ${localSettings.maxRecordingDurationMinutes} minute${if (localSettings.maxRecordingDurationMinutes == 1) "" else "s"}"
                        },
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp)
                    )

                    Divider()

                    // TTS Settings Section
                    Text(
                        text = "Text-to-Speech (TTS)",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.primary
                    )

                    // TTS Enable/Disable
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Enable TTS",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                text = "Read LLM responses aloud",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = localSettings.ttsEnabled,
                            onCheckedChange = { localSettings = localSettings.copy(ttsEnabled = it) }
                        )
                    }

                    // TTS Speech Rate (only show if TTS is enabled)
                    if (localSettings.ttsEnabled) {
                        SettingsSlider(
                            label = "Speech Rate",
                            value = localSettings.ttsSpeechRate,
                            valueRange = 0.5f..2.0f,
                            steps = 15,
                            displayValue = "%.1fx".format(localSettings.ttsSpeechRate),
                            onValueChange = { localSettings = localSettings.copy(ttsSpeechRate = it) }
                        )

                        // TTS Pitch
                        SettingsSlider(
                            label = "Pitch",
                            value = localSettings.ttsPitch,
                            valueRange = 0.5f..2.0f,
                            steps = 15,
                            displayValue = "%.1fx".format(localSettings.ttsPitch),
                            onValueChange = { localSettings = localSettings.copy(ttsPitch = it) }
                        )
                    }

                    Divider()

                    // Conversation History Management Section
                    ConversationHistorySettingsSection(onManageHistoryClick = onManageConversationHistory)

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
    val availableModels = LLMManager.getAllAvailableModels()
    val currentModel = llmManager.getCurrentModel()
    
    var selectedModelIndex by remember { mutableStateOf(
        availableModels.indexOfFirst { it.modelId == currentModel?.modelId }.let { if (it >= 0) it else 0 }
    )}
    var modelStatuses by remember { mutableStateOf(
        availableModels.map { llmManager.getModelStatus(it) }
    )}
    var downloadProgress by remember { mutableStateOf<DownloadProgress?>(null) }
    var isProcessing by remember { mutableStateOf(false) }
    
    // Track GPU settings for each model - Load from persistent storage
    var modelGpuSettings by remember { mutableStateOf(
        availableModels.associate { it.modelId to llmManager.getModelGPUSetting(it.modelId) }
    )}
    
    Text(
        text = "AI Language Models",
        fontSize = 16.sp,
        fontWeight = FontWeight.Medium,
        color = MaterialTheme.colorScheme.primary
    )
    
    // Model Selection Tabs
    TabRow(
        selectedTabIndex = selectedModelIndex,
        containerColor = MaterialTheme.colorScheme.surfaceVariant
    ) {
        availableModels.forEachIndexed { index, model ->
            Tab(
                selected = selectedModelIndex == index,
                onClick = { selectedModelIndex = index },
                text = { 
                    Text(
                        text = model.name.split(" ").take(2).joinToString(" "),
                        fontSize = 12.sp
                    )
                }
            )
        }
    }
    
    Spacer(modifier = Modifier.height(8.dp))
    
    val selectedModel = availableModels[selectedModelIndex]
    val selectedModelStatus = modelStatuses[selectedModelIndex]
    
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
                        text = selectedModel.name,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = "Size: ${llmManager.getModelSize(selectedModel)}",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (selectedModel.isLocalOnly) {
                        Text(
                            text = "Local only - requires manual import",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.primary,
                            fontStyle = FontStyle.Italic
                        )
                    }
                    if (selectedModel.supportsGPU) {
                        Text(
                            text = "Supports GPU acceleration with MediaPipe",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.primary,
                            fontStyle = FontStyle.Italic
                        )
                    }
                }
                
                // Status indicator
                when (selectedModelStatus) {
                    ModelDownloadStatus.DOWNLOADED, ModelDownloadStatus.LOCAL_ONLY -> {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = "Available",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                    ModelDownloadStatus.NOT_DOWNLOADED -> {
                        Icon(
                            imageVector = if (selectedModel.isLocalOnly) Icons.Default.Upload else Icons.Default.CloudDownload,
                            contentDescription = if (selectedModel.isLocalOnly) "Import required" else "Not downloaded",
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
            
            // GPU Settings for models that support it
            if (selectedModel.supportsGPU) {
                val gpuInfo = remember { llmManager.checkGPUCompatibility() }
                val useGPU = modelGpuSettings[selectedModel.modelId] ?: selectedModel.defaultConfig.useGPU
                
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (gpuInfo.isSupported) "GPU Acceleration" else "GPU Not Available",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                            color = if (gpuInfo.isSupported) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
                        )
                        if (gpuInfo.isSupported) {
                            Text(
                                text = if (useGPU) "Enabled" else "Disabled",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else {
                            Text(
                                text = "Not supported on this device",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Switch(
                        checked = useGPU,
                        onCheckedChange = { newValue ->
                            modelGpuSettings = modelGpuSettings.toMutableMap().apply {
                                put(selectedModel.modelId, newValue)
                            }
                            // Save GPU setting immediately
                            llmManager.saveModelGPUSetting(selectedModel.modelId, newValue)
                        },
                        enabled = gpuInfo.isSupported
                    )
                }
            }
            
            // Action buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val importLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.GetContent()
                ) { uri: Uri? ->
                    uri?.let {
                        scope.launch {
                            isProcessing = true
                            val ok = llmManager.importModelFromUri(it, selectedModel)
                            val newStatuses = modelStatuses.toMutableList()
                            newStatuses[selectedModelIndex] = if (ok) {
                                if (selectedModel.isLocalOnly) ModelDownloadStatus.LOCAL_ONLY else ModelDownloadStatus.DOWNLOADED
                            } else ModelDownloadStatus.ERROR
                            modelStatuses = newStatuses
                            isProcessing = false
                        }
                    }
                }
                
                when (selectedModelStatus) {
                    ModelDownloadStatus.NOT_DOWNLOADED -> {
                        if (selectedModel.isLocalOnly) {
                            // For local-only models, show import options
                            Button(
                                onClick = { importLauncher.launch("*/*") },
                                enabled = !isProcessing,
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("Import .task file")
                            }
                            // Check for external files
                            OutlinedButton(
                                onClick = {
                                    scope.launch {
                                        isProcessing = true
                                        val foundPaths = llmManager.findExternalModelFile(selectedModel)
                                        if (foundPaths.isNotEmpty()) {
                                            val success = llmManager.importModelFromPath(foundPaths.first(), selectedModel)
                                            val newStatuses = modelStatuses.toMutableList()
                                            newStatuses[selectedModelIndex] = if (success) ModelDownloadStatus.LOCAL_ONLY else ModelDownloadStatus.ERROR
                                            modelStatuses = newStatuses
                                        }
                                        isProcessing = false
                                    }
                                },
                                enabled = !isProcessing,
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("Auto-find")
                            }
                        } else {
                            // For downloadable models
                            Button(
                                onClick = {
                                    if (!isProcessing) {
                                        isProcessing = true
                                        val newStatuses = modelStatuses.toMutableList()
                                        newStatuses[selectedModelIndex] = ModelDownloadStatus.DOWNLOADING
                                        modelStatuses = newStatuses
                                        scope.launch {
                                            val success = llmManager.downloadModel(selectedModel) { progress ->
                                                downloadProgress = progress
                                            }
                                            val finalStatuses = modelStatuses.toMutableList()
                                            finalStatuses[selectedModelIndex] = if (success) ModelDownloadStatus.DOWNLOADED else ModelDownloadStatus.ERROR
                                            modelStatuses = finalStatuses
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
                            OutlinedButton(
                                onClick = { importLauncher.launch("*/*") },
                                enabled = !isProcessing,
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("Import .task")
                            }
                        }
                    }
                    
                    ModelDownloadStatus.ERROR -> {
                        Button(
                            onClick = { importLauncher.launch("*/*") },
                            enabled = !isProcessing,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Import .task file")
                        }
                    }
                    
                    ModelDownloadStatus.DOWNLOADED, ModelDownloadStatus.LOCAL_ONLY -> {
                        OutlinedButton(
                            onClick = {
                                if (!isProcessing) {
                                    isProcessing = true
                                    scope.launch {
                                        if (llmManager.deleteModel(selectedModel)) {
                                            val newStatuses = modelStatuses.toMutableList()
                                            newStatuses[selectedModelIndex] = ModelDownloadStatus.NOT_DOWNLOADED
                                            modelStatuses = newStatuses
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
                                        // Use current GPU setting from UI
                                        val currentGpuSetting = modelGpuSettings[selectedModel.modelId] ?: selectedModel.defaultConfig.useGPU
                                        val config = selectedModel.defaultConfig.copy(
                                            useGPU = if (selectedModel.supportsGPU) currentGpuSetting else false
                                        )
                                        llmManager.initializeModel(selectedModel, config)
                                        isProcessing = false
                                    }
                                }
                            },
                            enabled = !isProcessing && (llmManager.getCurrentModel()?.modelId != selectedModel.modelId),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(
                                if (llmManager.getCurrentModel()?.modelId == selectedModel.modelId) "Active" 
                                else "Initialize"
                            )
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
                text = selectedModel.description,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 16.sp
            )
        }
    }
}

@Composable
private fun ConversationHistorySettingsSection(
    onManageHistoryClick: () -> Unit
) {
    Text(
        text = "Conversation History",
        fontSize = 16.sp,
        fontWeight = FontWeight.Medium,
        color = MaterialTheme.colorScheme.primary
    )
    
    // Manage History Button
    OutlinedButton(
        onClick = onManageHistoryClick,
        modifier = Modifier.fillMaxWidth()
    ) {
        Icon(
            Icons.Default.History,
            contentDescription = null,
            modifier = Modifier.size(18.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text("Manage Conversation History")
    }
    
    Text(
        text = "View, configure and clear conversation history",
        fontSize = 12.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontStyle = FontStyle.Italic
    )
}