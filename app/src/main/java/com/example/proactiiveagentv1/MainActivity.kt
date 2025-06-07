package com.example.proactiiveagentv1

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtLoggingLevel
import ai.onnxruntime.OrtSession
import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.proactiiveagentv1.ui.theme.ProactiiveAgentV1Theme
import kotlinx.coroutines.*
import java.nio.FloatBuffer

class MainActivity : ComponentActivity() {
    private var audioRecord: AudioRecord? = null
    private var recordingJob: Job? = null
    private var ortSession: OrtSession? = null
    private var ortEnvironment: OrtEnvironment? = null
    
    // Voice detection state
    private var isListening = mutableStateOf(false)
    private var isSpeaking = mutableStateOf(false)
    private var vadConfidence = mutableFloatStateOf(0f)
    private var lastSpeechTime = 0L
    private var speechStartTime = 0L
    
    // Silero VAD parameters
    private val sampleRate = 16000
    private val windowSizeSamples = 512 // 32ms at 16kHz
    private val silenceTimeout = 500L // 1.5 seconds of silence
    private val minimumSpeechDuration = 200L // Minimum 300ms for valid speech
    private val vadThreshold = 0.5f // VAD confidence threshold
    
    // Audio buffer and model state
    private val audioBuffer = mutableListOf<Float>()
    private var modelState: FloatArray = FloatArray(2 * 1 * 128) { 0f }
    
    // Audio recording parameters
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT
    private val bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat) * 2
    
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions[Manifest.permission.RECORD_AUDIO] == true) {
            initializeComponents()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        
        setContent {
            ProactiiveAgentV1Theme {
                VoiceDetectionScreen()
            }
        }
        
        checkPermissions()
    }

    private fun checkPermissions() {
        when {
            ContextCompat.checkSelfPermission(
                this, Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED -> {
                initializeComponents()
            }
            else -> {
                requestPermissionLauncher.launch(
                    arrayOf(Manifest.permission.RECORD_AUDIO)
                )
            }
        }
    }

    private fun initializeComponents() {
        initializeAudioRecord()
        initializeSileroVAD()
    }

    private fun initializeAudioRecord() {
        try {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) 
                != PackageManager.PERMISSION_GRANTED) {
                return
            }
            
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                channelConfig,
                audioFormat,
                bufferSize
            )
            
            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                return
            }
            
        } catch (e: Exception) {
            // Handle audio record initialization error
        }
    }

    private fun initializeSileroVAD() {
        try {
            ortEnvironment = OrtEnvironment.getEnvironment()
            
            val modelFile = copyModelFromAssets()
            if (modelFile == null) return
            
            val sessionOptions = OrtSession.SessionOptions()
            sessionOptions.setSessionLogLevel(OrtLoggingLevel.ORT_LOGGING_LEVEL_WARNING)
            
            ortSession = ortEnvironment?.createSession(modelFile.absolutePath, sessionOptions)
            
        } catch (e: Exception) {
            // Handle model loading error
        }
    }

    private fun copyModelFromAssets(): java.io.File? {
        try {
            val assetInputStream = assets.open("silero_vad.onnx")
            val modelFile = java.io.File(filesDir, "silero_vad.onnx")
            
            if (!modelFile.exists()) {
                val outputStream = modelFile.outputStream()
                assetInputStream.copyTo(outputStream)
                assetInputStream.close()
                outputStream.close()
            }
            
            return modelFile
        } catch (e: Exception) {
            return null
        }
    }

    private fun startListening() {
        if (isListening.value || audioRecord == null || ortSession == null) {
            return
        }
        
        try {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) 
                != PackageManager.PERMISSION_GRANTED) {
                return
            }
            
            audioRecord?.startRecording()
            isListening.value = true
            isSpeaking.value = false
            
            // Reset VAD state
            audioBuffer.clear()
            modelState.fill(0f)
            
            recordingJob = CoroutineScope(Dispatchers.IO).launch {
                val buffer = ShortArray(bufferSize / 2)
                
                while (isActive && isListening.value) {
                    val samplesRead = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                    
                    if (samplesRead > 0) {
                        processAudioWithSileroVAD(buffer, samplesRead)
                    }
                }
            }
            
        } catch (e: SecurityException) {
            isListening.value = false
        } catch (e: Exception) {
            isListening.value = false
        }
    }

    private fun stopListening() {
        if (!isListening.value) return
        
        isListening.value = false
        isSpeaking.value = false
        
        recordingJob?.cancel()
        recordingJob = null
        
        try {
            audioRecord?.stop()
        } catch (e: Exception) {
            // Handle stop error
        }
    }

    private fun processAudioWithSileroVAD(buffer: ShortArray, samplesRead: Int) {
        // Convert to float using proper normalization
        for (i in 0 until samplesRead) {
            val sample = buffer[i].toFloat() / Short.MAX_VALUE.toFloat()
            audioBuffer.add(sample)
        }
        
        // Process audio in 512-sample chunks for VAD
        while (audioBuffer.size >= windowSizeSamples) {
            val audioChunk = FloatArray(windowSizeSamples)
            for (i in 0 until windowSizeSamples) {
                audioChunk[i] = audioBuffer.removeAt(0)
            }
            
            // Run Silero VAD inference
            val vadScore = runSileroVADInference(audioChunk)
            
            runOnUiThread {
                vadConfidence.floatValue = vadScore
            }
            
            // Speech detection based on VAD score
            val currentTime = System.currentTimeMillis()
            val isSpeechDetected = vadScore > vadThreshold
            
            if (isSpeechDetected) {
                // Speech detected
                if (!isSpeaking.value) {
                    speechStartTime = currentTime
                    runOnUiThread {
                        isSpeaking.value = true
                    }
                    onSpeechStarted(vadScore)
                }
                lastSpeechTime = currentTime
            } else {
                // Check for end of speech
                if (isSpeaking.value) {
                    val speechDuration = currentTime - speechStartTime
                    val silenceDuration = currentTime - lastSpeechTime
                    
                    // End speech if we've been silent long enough and had minimum speech duration
                    if (silenceDuration > silenceTimeout && speechDuration > minimumSpeechDuration) {
                        runOnUiThread {
                            isSpeaking.value = false
                        }
                        onSpeechEnded()
                    }
                }
            }
        }
    }

    private fun runSileroVADInference(audioChunk: FloatArray): Float {
        try {
            ortSession?.let { session ->
                ortEnvironment?.let { env ->
                    // Create input tensors
                    val inputNames = session.inputNames.toList()
                    
                    val audioShape = longArrayOf(1, audioChunk.size.toLong())
                    val audioTensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(audioChunk), audioShape)
                    
                    val srData = longArrayOf(sampleRate.toLong())
                    val srShape = longArrayOf(1)
                    val srTensor = OnnxTensor.createTensor(env, java.nio.LongBuffer.wrap(srData), srShape)
                    
                    val stateShape = longArrayOf(2, 1, 128)
                    val stateTensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(modelState), stateShape)
                    
                    // Create input map
                    val inputs = mutableMapOf<String, OnnxTensor>()
                    
                    // Map inputs
                    when {
                        inputNames.contains("input") -> inputs["input"] = audioTensor
                        inputNames.contains("audio") -> inputs["audio"] = audioTensor  
                        else -> inputs[inputNames[0]] = audioTensor
                    }
                    
                    when {
                        inputNames.contains("sr") -> inputs["sr"] = srTensor
                        inputNames.contains("sample_rate") -> inputs["sample_rate"] = srTensor
                        else -> if (inputNames.size > 1) inputs[inputNames[1]] = srTensor
                    }
                    
                    when {
                        inputNames.contains("state") -> inputs["state"] = stateTensor
                        inputNames.contains("h") -> inputs["h"] = stateTensor
                        else -> if (inputNames.size > 2) inputs[inputNames[2]] = stateTensor
                    }
                    
                    val results = session.run(inputs)
                    
                    // Get the speech probability and updated state
                    val result = results[0].value as Array<FloatArray>
                    val speechProb = result[0][0]
                    
                    // Update model state if available
                    if (results.size() > 1) {
                        try {
                            val newState = results[1].value as? Array<Array<FloatArray>>
                            if (newState != null) {
                                for (i in 0 until 128) {
                                    modelState[i] = newState[0][0][i]
                                    modelState[i + 128] = newState[1][0][i]
                                }
                            }
                        } catch (e: Exception) {
                            // Handle state update error
                        }
                    }
                    
                    // Clean up
                    audioTensor.close()
                    srTensor.close()
                    stateTensor.close()
                    results.close()
                    
                    return speechProb.coerceIn(0f, 1f)
                }
            }
        } catch (e: Exception) {
            // Handle inference error
        }
        
        return 0f
    }

    private fun onSpeechStarted(confidence: Float) {
        // Add your custom logic here when speech starts
    }

    private fun onSpeechEnded() {
        val duration = System.currentTimeMillis() - speechStartTime
        // Add your custom logic here when speech ends
    }

    @Composable
    fun VoiceDetectionScreen() {
        val isListeningState by isListening
        val isSpeakingState by isSpeaking
        val vadConfidenceState by vadConfidence
        
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
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 32.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = when {
                            !isListeningState -> Color.Gray
                            isSpeakingState -> Color.Green
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
                                !isListeningState -> "🔇 Ready"
                                isSpeakingState -> "🗣️ Speech Detected"
                                else -> "👂 Listening..."
                            },
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Medium,
                            color = Color.White
                        )
                        
                        if (isListeningState) {
                            Text(
                                text = "Confidence: ${(vadConfidenceState * 100).toInt()}%",
                                fontSize = 16.sp,
                                color = Color.White.copy(alpha = 0.9f),
                                modifier = Modifier.padding(top = 8.dp)
                            )
                        }
                    }
                }
                
                // VAD confidence visualization
                if (isListeningState) {
                    Text(
                        text = "Voice Activity: ${(vadConfidenceState * 100).toInt()}%",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(bottom = 16.dp)
                    )
                    
                    LinearProgressIndicator(
                        progress = { vadConfidenceState },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(16.dp)
                            .padding(bottom = 32.dp),
                        color = if (vadConfidenceState > vadThreshold) Color.Green else Color.Blue
                    )
                }
                
                // Control buttons
                Row(
                    horizontalArrangement = Arrangement.spacedBy(20.dp)
                ) {
                    Button(
                        onClick = { startListening() },
                        enabled = !isListeningState && ortSession != null,
                        modifier = Modifier
                            .height(56.dp)
                            .weight(1f)
                    ) {
                        Text("Start Detection", fontSize = 16.sp)
                    }
                    
                    Button(
                        onClick = { stopListening() },
                        enabled = isListeningState,
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
                
                // Status info
                Spacer(modifier = Modifier.height(40.dp))
                
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
                
                // Model status
                if (ortSession == null) {
                    Spacer(modifier = Modifier.height(16.dp))
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
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopListening()
        ortSession?.close()
        ortEnvironment?.close()
        audioRecord?.release()
        audioRecord = null
    }
}