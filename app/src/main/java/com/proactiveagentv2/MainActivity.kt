package com.proactiveagentv2

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.proactiveagentv2.asr.Player
import com.proactiveagentv2.asr.Player.PlaybackListener
import com.proactiveagentv2.asr.Recorder
import com.proactiveagentv2.asr.Recorder.RecorderListener
import com.proactiveagentv2.asr.Whisper
import com.proactiveagentv2.asr.Whisper.WhisperListener
import com.proactiveagentv2.utils.WaveUtil
import com.proactiveagentv2.utils.WhisperUtil
import com.proactiveagentv2.vad.VADManager
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity(), RecorderListener {
    private var tvStatus: TextView? = null
    private var tvResult: TextView? = null
    private var fabCopy: FloatingActionButton? = null
    private var btnRecord: Button? = null
    private var btnPlay: Button? = null
    private var btnSpeechIndicator: Button? = null
    private var fabSettings: FloatingActionButton? = null

    private var mPlayer: Player? = null
    private var mRecorder: Recorder? = null
    private var mWhisper: Whisper? = null
    private var mVADManager: VADManager? = null

    private var sdcardDataFolder: File? = null
    private var selectedWaveFile: File? = null
    private var selectedTfliteFile: File? = null

    private var startTime: Long = 0
    private val loopTesting = false
    private val transcriptionSync = SharedResource()
    private val handler = Handler(Looper.getMainLooper())

    private var mWavFilePath: String? = null
    private var isRecording = false
    private var isTranscribing = false
    
    // For debugging and playback of last transcribed segment
    private var lastTranscribedSegmentFile: File? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Call the method to copy specific file types from assets to data folder
        sdcardDataFolder = this.getExternalFilesDir(null)
        copyAssetsToSdcard(this, sdcardDataFolder, EXTENSIONS_TO_COPY)

        val tfliteFiles = getFilesWithExtension(sdcardDataFolder, ".tflite")
        val waveFiles = getFilesWithExtension(sdcardDataFolder, ".wav")

        // Initialize default model to use
        selectedTfliteFile = File(sdcardDataFolder, DEFAULT_MODEL_TO_USE)

        val spinnerTflite = findViewById<Spinner>(R.id.spnrTfliteFiles)
        spinnerTflite.adapter = getFileArrayAdapter(tfliteFiles)
        spinnerTflite.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>,
                view: View,
                position: Int,
                id: Long
            ) {
                deinitModel()
                selectedTfliteFile = parent.getItemAtPosition(position) as File
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {
                // Handle case when nothing is selected, if needed
            }
        }

        val spinnerWave = findViewById<Spinner>(R.id.spnrWaveFiles)
        spinnerWave.adapter = getFileArrayAdapter(waveFiles)
        spinnerWave.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>,
                view: View,
                position: Int,
                id: Long
            ) {
                // Cast item to File and get the file name
                selectedWaveFile = parent.getItemAtPosition(position) as File

                // Check if the selected file is the recording file
                if (selectedWaveFile!!.name == WaveUtil.RECORDING_FILE) {
                    btnRecord!!.visibility = View.VISIBLE
                } else {
                    btnRecord!!.visibility = View.GONE
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {
                // Handle case when nothing is selected, if needed
            }
        }

        // Implementation of record button functionality
        btnRecord = findViewById(R.id.btnRecord)
        btnRecord?.setOnClickListener(View.OnClickListener { v: View? ->
            if (mRecorder != null && mRecorder!!.isInProgress) {
                Log.d(TAG, "Recording is in progress... stopping...")
                stopRecording()
            } else {
                Log.d(TAG, "Start recording...")
                startRecording()
            }
        })

        // Implementation of Play button functionality
        btnPlay = findViewById(R.id.btnPlay)
        btnPlay?.setOnClickListener(View.OnClickListener { v: View? ->
            if (mPlayer?.isPlaying == false) {
                // Determine which file to play
                val fileToPlay = when {
                    lastTranscribedSegmentFile?.exists() == true -> {
                        Log.d(TAG, "Playing last transcribed segment: ${lastTranscribedSegmentFile?.name}")
                        Toast.makeText(this, "Playing last transcribed segment", Toast.LENGTH_SHORT).show()
                        lastTranscribedSegmentFile!!
                    }
                    selectedWaveFile?.exists() == true -> {
                        Log.d(TAG, "Playing selected file: ${selectedWaveFile?.name}")
                        selectedWaveFile!!
                    }
                    else -> {
                        Toast.makeText(this, "No audio file available to play", Toast.LENGTH_SHORT).show()
                        return@OnClickListener
                    }
                }
                
                mPlayer?.initializePlayer(fileToPlay.absolutePath)
                mPlayer?.startPlayback()
            } else {
                mPlayer?.stopPlayback()
            }
        })

        // Initialize speech detection indicator
        btnSpeechIndicator = findViewById(R.id.btnSpeechIndicator)

        tvStatus = findViewById(R.id.tvStatus)
        tvResult = findViewById(R.id.tvResult)
        fabCopy = findViewById(R.id.fabCopy)
        fabSettings = findViewById(R.id.fabSettings)
        fabCopy?.setOnClickListener(View.OnClickListener { v: View? ->
            // Get the text from tvResult
            val textToCopy = tvResult?.getText().toString()

            // Copy the text to the clipboard
            val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("Copied Text", textToCopy)
            clipboard.setPrimaryClip(clip)
        })
        fabSettings?.setOnClickListener(View.OnClickListener { v: View? ->
            showSettingsDialog()
        })

        // Audio recording functionality
        mRecorder = Recorder(this)
        mRecorder?.setListener(this)

        // Initialize VAD Manager
        mVADManager = VADManager(this)
        if (!mVADManager!!.initialize()) {
            Log.e(TAG, "Failed to initialize VAD Manager")
            showError("Failed to initialize Voice Activity Detection")
            return
        }
        
        // Set up VAD callbacks (Python-like event handling)
        mVADManager?.setOnSpeechStartListener {
            handler.post { 
                tvStatus?.text = "Speech detected..."
                btnSpeechIndicator?.isSelected = true // Turn green
                Log.d(TAG, "🎤 Speech started detected by VAD")
            }
        }
        
        mVADManager?.setOnSpeechEndListener { speechSegment ->
            handler.post {
                tvStatus?.text = "Speech ended, auto-transcribing..."
                btnSpeechIndicator?.isSelected = false // Turn back to normal
                Log.d(TAG, "🎯 Speech ended detected by VAD, starting automatic transcription of ${speechSegment.size} samples (${speechSegment.size / 16000.0f}s)")
            }
            
            // PYTHON-LIKE BEHAVIOR: Auto-transcribe the speech segment immediately without stopping recording
            // This mimics Python's _process_speech method that triggers transcription on speech end
            if (mRecorder?.isInProgress == true) {
                // Use a background thread for transcription to avoid blocking the audio thread
                Thread {
                    try {
                        // Initialize model if needed (like Python's lazy initialization)
                        if (mWhisper == null) {
                            handler.post { 
                                tvStatus?.text = "Initializing Whisper model..."
                                initModel(selectedTfliteFile!!) 
                            }
                            Thread.sleep(1000) // Give model time to initialize
                        }
                        
                        // Check if whisper engine is ready
                        if (mWhisper?.isInProgress == false) {
                            Log.d(TAG, "🚀 Auto-starting transcription of speech segment...")
                            
                            // PYTHON-LIKE TRANSCRIPTION: Process the speech segment directly
                            transcribeSpeechSegmentDirect(speechSegment)
                        } else {
                            Log.w(TAG, "⚠️ Whisper engine busy, skipping transcription")
                            handler.post {
                                if (mRecorder?.isInProgress == true) {
                                    tvStatus?.text = "Recording - Listening... (transcription skipped, engine busy)"
                                }
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "❌ Error in auto-transcription", e)
                        handler.post {
                            if (mRecorder?.isInProgress == true) {
                                tvStatus?.text = "Recording - Listening... (transcription error)"
                            }
                        }
                    }
                }.start()
            }
        }
        
        mVADManager?.setOnVADStatusListener { isSpeech, probability ->
            // Update speech indicator in real-time (like Python's live UI updates)
            handler.post { 
                btnSpeechIndicator?.isSelected = isSpeech
                // Show VAD status and probability like Python's console output
                if (mRecorder?.isInProgress == true) {
                    val statusText = if (isSpeech) {
                        "Recording - Speaking... (${String.format("%.2f", probability)})"
                    } else {
                        "Recording - Listening... (${String.format("%.2f", probability)})"
                    }
                    tvStatus?.text = statusText
                }
            }
        }

        // Audio playback functionality
        mPlayer = Player(this)
        mPlayer?.setListener(object : PlaybackListener {
            override fun onPlaybackStarted() {
                handler.post { btnPlay?.setText(R.string.stop) }
            }

            override fun onPlaybackStopped() {
                handler.post { btnPlay?.setText(R.string.play) }
            }
        })

        // Assume this Activity is the current activity, check record permission
        checkRecordPermission()

        // for debugging
//        testParallelProcessing();
    }

    override fun onDestroy() {
        super.onDestroy()
        
        // Release VAD resources
        mVADManager?.release()
        mVADManager = null
        
        // Clean up last transcribed segment file
        lastTranscribedSegmentFile?.let { file ->
            try {
                if (file.exists()) {
                    file.delete()
                    Log.d(TAG, "Cleaned up last transcribed segment file")
                } else {
                    Log.d(TAG, "Last transcribed segment file does not exist")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to clean up last transcribed segment file", e)
            }
        }
        
        // Clean up other resources
        mPlayer = null
        mRecorder = null
        deinitModel()
        
        Log.d(TAG, "MainActivity destroyed, resources released")
    }

    // Model initialization
    private fun initModel(modelFile: File) {
        val isMultilingualModel = !(modelFile.name.endsWith(ENGLISH_ONLY_MODEL_EXTENSION))
        val vocabFileName =
            if (isMultilingualModel) MULTILINGUAL_VOCAB_FILE else ENGLISH_ONLY_VOCAB_FILE
        val vocabFile = File(sdcardDataFolder, vocabFileName)

        mWhisper = Whisper(this)
        mWhisper!!.loadModel(modelFile, vocabFile, isMultilingualModel)
        mWhisper?.setListener(object : WhisperListener {
            override fun onUpdateReceived(message: String?) {
                Log.d(
                    TAG,
                    "Update is received, Message: $message"
                )

                if (message == Whisper.MSG_PROCESSING) {
                    handler.post { 
                        if (mRecorder?.isInProgress == true) {
                            tvStatus?.text = "Recording - Transcribing previous speech..."
                        } else {
                            tvStatus?.text = message
                        }
                    }
                    startTime = System.currentTimeMillis()
                }
                if (message == Whisper.MSG_PROCESSING_DONE) {
                    handler.post {
                        if (mRecorder?.isInProgress == true) {
                            tvStatus?.text = "Recording - Listening..."
                        } else {
                            tvStatus?.text = "Processing done"
                        }
                    }
                    // for testing
                    if (loopTesting) transcriptionSync.sendSignal()
                } else if (message == Whisper.MSG_FILE_NOT_FOUND) {
                    handler.post { tvStatus?.text = message }
                    Log.d(TAG, "File not found error...!")
                }
            }

            override fun onResultReceived(result: String?) {
                val timeTaken = System.currentTimeMillis() - startTime
                
                Log.d(TAG, "Result: $result")
                handler.post { 
                    if (!result.isNullOrBlank()) {
                        val currentText = tvResult?.text.toString()
                        val newText = if (currentText.isBlank()) result else "$currentText\n$result"
                        tvResult?.text = newText
                        
                        // Show completion status with result preview
                        if (mRecorder?.isInProgress == true) {
                            tvStatus?.text = "Recording - Listening... (last: \"$result\")"
                        } else {
                            tvStatus?.text = "Processing done in ${timeTaken}ms: \"$result\""
                        }
                        
                        Log.i(TAG, "Successfully transcribed: \"$result\"")
                    } else {
                        Log.w(TAG, "Empty transcription result")
                        if (mRecorder?.isInProgress == true) {
                            tvStatus?.text = "Recording - Listening... (no speech detected)"
                        } else {
                            tvStatus?.text = "Processing done - no speech detected"
                        }
                    }
                }
            }
        })
    }

    private fun deinitModel() {
        if (mWhisper != null) {
            mWhisper!!.unloadModel()
            mWhisper = null
        }
    }

    private fun getFileArrayAdapter(waveFiles: ArrayList<File>): ArrayAdapter<File> {
        val adapter: ArrayAdapter<File> =
            object : ArrayAdapter<File>(this, android.R.layout.simple_spinner_item, waveFiles) {
                override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                    val view = super.getView(position, convertView, parent)
                    val textView = view.findViewById<TextView>(android.R.id.text1)
                    textView.text = getItem(position)!!.name // Show only the file name
                    return view
                }

                override fun getDropDownView(
                    position: Int,
                    convertView: View?,
                    parent: ViewGroup
                ): View {
                    val view = super.getDropDownView(position, convertView, parent)
                    val textView = view.findViewById<TextView>(android.R.id.text1)
                    textView.text = getItem(position)!!.name // Show only the file name
                    return view
                }
            }
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        return adapter
    }

    private fun checkRecordPermission() {
        val permission = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
        if (permission == PackageManager.PERMISSION_GRANTED) {
            Log.d(TAG, "Record permission is granted")
        } else {
            Log.d(TAG, "Requesting record permission")
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 0)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (grantResults.size > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            Log.d(TAG, "Record permission is granted")
        } else {
            Log.d(TAG, "Record permission is not granted")
        }
    }

    // Recording calls
    private fun startRecording() {
        checkRecordPermission()

        val waveFile = File(sdcardDataFolder, WaveUtil.RECORDING_FILE)
        mRecorder!!.setFilePath(waveFile.absolutePath)
        
        // Start VAD processing
        mVADManager?.startVAD()
        
        mRecorder!!.start()
        
        Log.d(TAG, "Recording started with VAD processing")
    }

    private fun stopRecording() {
        // Stop VAD processing
        mVADManager?.stopVAD()
        
        mRecorder!!.stop()
        
        Log.d(TAG, "Recording stopped, VAD processing ended")
    }

    // Transcription calls
    private fun startTranscription(waveFilePath: String) {
        mWhisper!!.setFilePath(waveFilePath)
        mWhisper!!.setAction(Whisper.ACTION_TRANSCRIBE)
        mWhisper!!.start()
    }
    
    private fun transcribeSpeechSegment(audioSamples: FloatArray) {
        Thread {
            try {
                Log.d(TAG, "Starting transcription of speech segment with ${audioSamples.size} samples (${audioSamples.size / 16000.0f} seconds)")
                
                if (audioSamples.isEmpty()) {
                    Log.w(TAG, "Received empty audio samples for transcription")
                    handler.post {
                        if (mRecorder?.isInProgress == true) {
                            tvStatus?.text = "Recording - Listening... (empty segment skipped)"
                        }
                    }
                    return@Thread
                }
                
                // STEP 1 & 2: Audio Quality Checks
                val maxSample = audioSamples.maxOrNull() ?: 0f
                val minSample = audioSamples.minOrNull() ?: 0f
                val rms = kotlin.math.sqrt(audioSamples.fold(0f) { acc, s -> acc + s * s } / audioSamples.size)
                val rmsDb = 20 * kotlin.math.log10(rms + 1e-10) // Add small value to avoid log(0)
                val peakDb = 20 * kotlin.math.log10(kotlin.math.max(kotlin.math.abs(maxSample), kotlin.math.abs(minSample)) + 1e-10)
                
                Log.d(TAG, "=== AUDIO QUALITY CHECK ===")
                Log.d(TAG, "Peak sample range: $minSample to $maxSample")
                Log.d(TAG, "RMS: $rms (${String.format("%.1f", rmsDb)} dB)")
                Log.d(TAG, "Peak: ${String.format("%.1f", peakDb)} dB")
                Log.d(TAG, "Expected: samples -1.0 to 1.0, RMS > -30dB, Peak > -6dB")
                
                // STEP 4: Guard against silence segments
                if (rms < 0.015f) { // ≈-42 dB threshold
                    Log.w(TAG, "❌ DISCARDED: RMS ${String.format("%.4f", rms)} (${String.format("%.1f", rmsDb)} dB) too low - likely silence")
                    handler.post {
                        if (mRecorder?.isInProgress == true) {
                            tvStatus?.text = "Recording - Listening... (silence segment skipped)"
                        }
                    }
                    return@Thread
                }
                
                // Check for scaling issues
                if (maxSample > 1.0f || minSample < -1.0f) {
                    Log.w(TAG, "⚠️ WARNING: Audio samples outside expected -1.0 to 1.0 range!")
                }
                if (maxSample < 0.05f && minSample > -0.05f) {
                    Log.w(TAG, "⚠️ WARNING: Audio samples very quiet (max=${maxSample}), possible scaling issue!")
                }
                
                // Initialize model if needed
                if (mWhisper == null) {
                    handler.post { initModel(selectedTfliteFile!!) }
                    Thread.sleep(1000) // Give model time to initialize
                }
                
                // Create segment file for transcription (don't delete immediately for debugging)
                val segmentFileName = "last_segment_${System.currentTimeMillis()}.wav"
                val segmentWaveFile = File(sdcardDataFolder, segmentFileName)
                
                try {
                    // STEP 2: Double-check scaling just before writing
                    Log.d(TAG, "Converting ${audioSamples.size} float samples to WAV...")
                    saveAudioAsWavWithChecks(audioSamples, segmentWaveFile.absolutePath)
                    
                    // Clean up previous segment file if it exists
                    lastTranscribedSegmentFile?.let { oldFile ->
                        if (oldFile.exists() && oldFile != segmentWaveFile) {
                            try {
                                oldFile.delete()
                                Log.d(TAG, "Deleted previous segment file: ${oldFile.name}")
                            } catch (e: Exception) {
                                Log.w(TAG, "Failed to delete previous segment file", e)
                            }
                        }
                    }
                    
                    // Update reference to latest segment file
                    lastTranscribedSegmentFile = segmentWaveFile
                    
                    // STEP 1: Verify the created WAV file
                    if (segmentWaveFile.exists()) {
                        val fileSize = segmentWaveFile.length()
                        val expectedDataSize = audioSamples.size * 2 // 2 bytes per 16-bit sample
                        val expectedFileSize = 44 + expectedDataSize // WAV header + PCM data
                        
                        Log.d(TAG, "=== WAV FILE VERIFICATION ===")
                        Log.d(TAG, "File size: ${fileSize} bytes (expected ≈${expectedFileSize} bytes)")
                        Log.d(TAG, "Path: ${segmentWaveFile.absolutePath}")
                        
                        if (fileSize < 100) {
                            Log.e(TAG, "❌ ERROR: WAV file too small, likely creation failed!")
                            return@Thread
                        }
                        
                        if (kotlin.math.abs(fileSize - expectedFileSize) > 10) {
                            Log.w(TAG, "⚠️ WARNING: File size mismatch - expected ~${expectedFileSize}, got ${fileSize}")
                        }
                        
                        // STEP 3: Verify WAV header by reading it back
                        verifyWAVHeader(segmentWaveFile, expectedDataSize)
                        
                    } else {
                        Log.e(TAG, "❌ ERROR: WAV file was not created!")
                        return@Thread
                    }
                    
                    Log.d(TAG, "✅ Starting Whisper transcription...")
                    
                    // Set up the transcription to use existing listener (don't override it)
                    handler.post {
                        // Start transcription using the working file-based method
                        mWhisper?.setFilePath(segmentWaveFile.absolutePath)
                        mWhisper?.setAction(Whisper.ACTION_TRANSCRIBE)
                        mWhisper?.start()
                        
                        // Update status
                        if (mRecorder?.isInProgress == true) {
                            tvStatus?.text = "Recording - Transcribing speech segment..."
                        } else {
                            tvStatus?.text = "Transcribing speech segment..."
                        }
                    }
                    
                    // Wait for transcription to complete (don't delete file immediately)
                    Thread.sleep(5000) // Give transcription time to complete
                    
                } catch (fileException: Exception) {
                    Log.e(TAG, "Error with segment file creation/transcription", fileException)
                    // Clean up on error
                    try {
                        if (segmentWaveFile.exists()) {
                            segmentWaveFile.delete()
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to delete segment file after error", e)
                    }
                    throw fileException
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "Error during transcription", e)
                handler.post {
                    val errorMsg = "Transcription error: ${e.message}"
                    Toast.makeText(this@MainActivity, errorMsg, Toast.LENGTH_SHORT).show()
                    if (mRecorder?.isInProgress == true) {
                        tvStatus?.text = "Recording - Listening... (transcription failed)"
                    }
                }
            }
        }.start()
    }
    
    private fun saveAudioAsWavWithChecks(audioSamples: FloatArray, filePath: String) {
        try {
            // STEP 2: Final scaling check with improved conversion
            val peekMax = audioSamples.maxOrNull() ?: 0f
            val peekMin = audioSamples.minOrNull() ?: 0f
            Log.d(TAG, "Final scaling check - max sample before save: $peekMax, min: $peekMin")
            
            if (peekMax > 1.0f || peekMin < -1.0f) {
                Log.w(TAG, "⚠️ Samples out of range, will be clamped!")
            }
            
            // Convert float samples to 16-bit PCM ByteArray with improved scaling
            val pcmData = ByteArray(audioSamples.size * 2) // 2 bytes per 16-bit sample
            var byteIndex = 0
            
            for (sample in audioSamples) {
                // STEP 2: Improved scaling (multiply by 32767f, not 32768f, and clamp)
                val clampedSample = sample.coerceIn(-1f, 1f)
                val s16 = (clampedSample * 32767f).toInt().toShort()
                
                // Write as little-endian bytes (same as AudioRecord/ByteBuffer.nativeOrder())
                pcmData[byteIndex++] = (s16.toInt() and 0xFF).toByte()
                pcmData[byteIndex++] = ((s16.toInt() shr 8) and 0xFF).toByte()
            }
            
            Log.d(TAG, "Converted ${audioSamples.size} samples to ${pcmData.size} bytes")
            
            // Use the WaveUtil.createWaveFile (which should be correct for data chunk size)
            WaveUtil.createWaveFile(
                filePath,
                pcmData,
                16000, // sampleRateInHz
                1,     // channels  
                2      // bytesPerSample
            )
            
            Log.d(TAG, "✅ WAV file created successfully")
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error saving audio as WAV", e)
            throw e
        }
    }
    
    private fun verifyWAVHeader(wavFile: File, expectedDataSize: Int) {
        try {
            val header = ByteArray(44)
            val fis = java.io.FileInputStream(wavFile)
            val bytesRead = fis.read(header)
            fis.close()
            
            if (bytesRead < 44) {
                Log.e(TAG, "❌ WAV header too short: $bytesRead bytes")
                return
            }
            
            // Check RIFF signature
            val riffSig = String(header, 0, 4)
            val waveSig = String(header, 8, 4)
            
            Log.d(TAG, "=== WAV HEADER VERIFICATION ===")
            Log.d(TAG, "RIFF signature: '$riffSig' (should be 'RIFF')")
            Log.d(TAG, "WAVE signature: '$waveSig' (should be 'WAVE')")
            
            // Check data chunk size (bytes 40-43)
            val dataChunkSize = (header[40].toInt() and 0xFF) or
                               ((header[41].toInt() and 0xFF) shl 8) or
                               ((header[42].toInt() and 0xFF) shl 16) or
                               ((header[43].toInt() and 0xFF) shl 24)
            
            Log.d(TAG, "Data chunk size in header: $dataChunkSize bytes")
            Log.d(TAG, "Expected data size: $expectedDataSize bytes")
            
            if (dataChunkSize != expectedDataSize) {
                Log.e(TAG, "❌ DATA CHUNK SIZE MISMATCH! Header says $dataChunkSize, expected $expectedDataSize")
            } else {
                Log.d(TAG, "✅ Data chunk size correct")
            }
            
            // Check sample rate (bytes 24-27)
            val sampleRate = (header[24].toInt() and 0xFF) or
                            ((header[25].toInt() and 0xFF) shl 8) or
                            ((header[26].toInt() and 0xFF) shl 16) or
                            ((header[27].toInt() and 0xFF) shl 24)
            
            // Check bits per sample (bytes 34-35)
            val bitsPerSample = (header[34].toInt() and 0xFF) or ((header[35].toInt() and 0xFF) shl 8)
            
            // Check channels (bytes 22-23)
            val channels = (header[22].toInt() and 0xFF) or ((header[23].toInt() and 0xFF) shl 8)
            
            Log.d(TAG, "Sample rate: $sampleRate Hz (should be 16000)")
            Log.d(TAG, "Bits per sample: $bitsPerSample (should be 16)")
            Log.d(TAG, "Channels: $channels (should be 1)")
            
            if (sampleRate != 16000) Log.e(TAG, "❌ Wrong sample rate!")
            if (bitsPerSample != 16) Log.e(TAG, "❌ Wrong bits per sample!")
            if (channels != 1) Log.e(TAG, "❌ Wrong channel count!")
            
        } catch (e: Exception) {
            Log.e(TAG, "Error verifying WAV header", e)
        }
    }
    
    // PYTHON-LIKE DIRECT TRANSCRIPTION: Process speech segments immediately (like Python's transcription)
    private fun transcribeSpeechSegmentDirect(audioSamples: FloatArray) {
        if (mWhisper == null) {
            Log.w(TAG, "⚠️ Whisper engine not initialized")
            return
        }
        
        try {
            // Audio quality checks (like Python's audio validation)
            val maxSample = audioSamples.maxOrNull() ?: 0f
            val minSample = audioSamples.minOrNull() ?: 0f
            val rms = kotlin.math.sqrt(audioSamples.fold(0f) { acc, s -> acc + s * s } / audioSamples.size)
            val rmsDb = 20 * kotlin.math.log10(rms + 1e-10)
            
            Log.d(TAG, "🔍 Audio quality check: ${audioSamples.size} samples (${audioSamples.size / 16000.0f}s)")
            Log.d(TAG, "📊 Peak range: $minSample to $maxSample, RMS: ${String.format("%.4f", rms)} (${String.format("%.1f", rmsDb)} dB)")
            
            // Python-like silence detection
            if (rms < 0.015f) { // ≈-42 dB threshold (similar to Python's silence detection)
                Log.w(TAG, "🔇 Discarded: RMS too low (${String.format("%.4f", rms)}), likely silence")
                handler.post {
                    if (mRecorder?.isInProgress == true) {
                        tvStatus?.text = "Recording - Listening... (silence segment skipped)"
                    }
                }
                return
            }
            
            // Check for scaling issues
            if (maxSample > 1.0f || minSample < -1.0f) {
                Log.w(TAG, "⚠️ WARNING: Audio samples outside expected -1.0 to 1.0 range!")
            }
            if (maxSample < 0.05f && minSample > -0.05f) {
                Log.w(TAG, "⚠️ WARNING: Audio samples very quiet (max=${maxSample}), possible scaling issue!")
            }
            
            // SAVE AUDIO SEGMENT FOR DEBUGGING/PLAYBACK
            val segmentFileName = "last_segment_${System.currentTimeMillis()}.wav"
            val segmentWaveFile = File(sdcardDataFolder, segmentFileName)
            
            try {
                // Save the audio segment for debugging
                saveAudioAsWavWithChecks(audioSamples, segmentWaveFile.absolutePath)
                
                // Clean up previous segment file if it exists
                lastTranscribedSegmentFile?.let { oldFile ->
                    if (oldFile.exists() && oldFile != segmentWaveFile) {
                        try {
                            oldFile.delete()
                            Log.d(TAG, "Deleted previous segment file: ${oldFile.name}")
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to delete previous segment file", e)
                        }
                    }
                }
                
                // Update reference to latest segment file (FIX FOR PLAYBACK ISSUE)
                lastTranscribedSegmentFile = segmentWaveFile
                Log.d(TAG, "💾 Saved debug segment: ${segmentWaveFile.name}")
                
            } catch (e: Exception) {
                Log.w(TAG, "Failed to save debug segment file", e)
                // Continue with transcription even if debug save fails
            }
            
            // Prepare audio for transcription (like Python's audio preprocessing)
            val trimmedSamples = trimTrailingSilence(audioSamples)
            val paddedSamples = padToWindow(trimmedSamples)
            
            Log.d(TAG, "🎯 Processing: ${audioSamples.size} → ${trimmedSamples.size} → ${paddedSamples.size} samples")
            
            // TRY BOTH TRANSCRIPTION METHODS TO DEBUG THE ISSUE
            
            // METHOD 1: Direct array transcription (faster but might have issues)
            Log.d(TAG, "🚀 Trying direct array transcription...")
            val startTime = System.currentTimeMillis()
            val directResult = mWhisper?.transcribeFromArray(paddedSamples, "en") ?: ""
            val directTimeTaken = System.currentTimeMillis() - startTime
            
            Log.d(TAG, "📋 Direct transcription result (${directTimeTaken}ms): \"$directResult\"")
            
            // If direct transcription fails or returns empty, try file-based transcription
            if (directResult.isBlank() && segmentWaveFile.exists()) {
                Log.d(TAG, "⚠️ Direct transcription empty, trying file-based transcription...")
                
                handler.post {
                    // Use file-based transcription as fallback
                    mWhisper?.setFilePath(segmentWaveFile.absolutePath)
                    mWhisper?.setAction(Whisper.ACTION_TRANSCRIBE)
                    mWhisper?.start()
                    
                    if (mRecorder?.isInProgress == true) {
                        tvStatus?.text = "Recording - Transcribing (file-based)..."
                    }
                }
                return // Let the file-based transcription handle the result
            }
            
            // Update UI with direct transcription result
            handler.post {
                if (!directResult.isBlank()) {
                    // Update UI with result (like Python's response display)
                    val currentText = tvResult?.text.toString()
                    val newText = if (currentText.isBlank()) directResult else "$currentText\n$directResult"
                    tvResult?.text = newText
                    
                    // Show status with timing (like Python's timing information)
                    if (mRecorder?.isInProgress == true) {
                        tvStatus?.text = "Recording - Listening... (${directTimeTaken}ms: \"$directResult\")"
                    }
                    
                    Log.i(TAG, "✅ Direct transcription success (${directTimeTaken}ms): \"$directResult\"")
                } else {
                    Log.w(TAG, "⚠️ Both transcription methods returned empty")
                    if (mRecorder?.isInProgress == true) {
                        tvStatus?.text = "Recording - Listening... (no speech detected)"
                    }
                }
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error in direct transcription", e)
            handler.post {
                if (mRecorder?.isInProgress == true) {
                    tvStatus?.text = "Recording - Listening... (transcription error: ${e.message})"
                }
            }
        }
    }
    
    // Audio preprocessing utilities (like Python's audio processing)
    private fun trimTrailingSilence(audioSamples: FloatArray): FloatArray {
        if (audioSamples.isEmpty()) return audioSamples
        
        val threshold = 0.01f // Silence threshold
        var lastNonSilentIndex = audioSamples.size - 1
        
        // Find the last non-silent sample
        for (i in audioSamples.size - 1 downTo 0) {
            if (kotlin.math.abs(audioSamples[i]) > threshold) {
                lastNonSilentIndex = i
                break
            }
        }
        
        // Keep a small buffer after the last speech (like Python's post-speech buffer)
        val bufferSamples = minOf(1600, audioSamples.size - lastNonSilentIndex - 1) // 0.1 second buffer
        val trimmedSize = minOf(lastNonSilentIndex + bufferSamples + 1, audioSamples.size)
        
        return audioSamples.copyOfRange(0, trimmedSize)
    }
    
    private fun padToWindow(audioSamples: FloatArray): FloatArray {
        if (audioSamples.isEmpty()) return audioSamples
        
        val windowSize = 512
        val remainder = audioSamples.size % windowSize
        
        return if (remainder == 0) {
            audioSamples
        } else {
            val paddedSize = audioSamples.size + (windowSize - remainder)
            val paddedArray = FloatArray(paddedSize)
            audioSamples.copyInto(paddedArray)
            // Padding with zeros (silence)
            paddedArray
        }
    }

    private fun stopTranscription() {
        mWhisper!!.stop()
    }

    fun getFilesWithExtension(directory: File?, extension: String): ArrayList<File> {
        val filteredFiles = ArrayList<File>()

        // Check if the directory is accessible
        if (directory != null && directory.exists()) {
            val files = directory.listFiles()

            // Filter files by the provided extension
            if (files != null) {
                for (file in files) {
                    if (file.isFile && file.name.endsWith(extension)) {
                        filteredFiles.add(file)
                    }
                }
            }
        }

        return filteredFiles
    }

    private fun showSettingsDialog() {
        val vadMgr = mVADManager ?: return
        
        val dialogView = layoutInflater.inflate(R.layout.dialog_vad_settings, null)
        val speechThresholdSlider = dialogView.findViewById<SeekBar>(R.id.speechThresholdSlider)
        val silenceThresholdSlider = dialogView.findViewById<SeekBar>(R.id.silenceThresholdSlider)
        val minSpeechDurationSlider = dialogView.findViewById<SeekBar>(R.id.minSpeechDurationSlider)
        val maxSilenceDurationSlider = dialogView.findViewById<SeekBar>(R.id.maxSilenceDurationSlider)
        
        val speechThresholdLabel = dialogView.findViewById<TextView>(R.id.speechThresholdLabel)
        val silenceThresholdLabel = dialogView.findViewById<TextView>(R.id.silenceThresholdLabel)
        val minSpeechDurationLabel = dialogView.findViewById<TextView>(R.id.minSpeechDurationLabel)
        val maxSilenceDurationLabel = dialogView.findViewById<TextView>(R.id.maxSilenceDurationLabel)
        
        // Set current values
        speechThresholdSlider.progress = (vadMgr.speechThreshold * 100).toInt()
        silenceThresholdSlider.progress = (vadMgr.silenceThreshold * 100).toInt()
        minSpeechDurationSlider.progress = (vadMgr.minSpeechDurationMs / 100).toInt()
        maxSilenceDurationSlider.progress = (vadMgr.maxSilenceDurationMs / 100).toInt()
        
        fun updateLabels() {
            speechThresholdLabel.text = "Speech Threshold: ${speechThresholdSlider.progress / 100f}"
            silenceThresholdLabel.text = "Silence Threshold: ${silenceThresholdSlider.progress / 100f}"
            minSpeechDurationLabel.text = "Min Speech Duration: ${minSpeechDurationSlider.progress * 100}ms"
            maxSilenceDurationLabel.text = "Max Silence Duration: ${maxSilenceDurationSlider.progress * 100}ms"
        }
        
        updateLabels()
        
        speechThresholdSlider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                updateLabels()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        
        silenceThresholdSlider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                updateLabels()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        
        minSpeechDurationSlider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                updateLabels()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        
        maxSilenceDurationSlider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                updateLabels()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        
        val dialog = AlertDialog.Builder(this)
            .setTitle("VAD Settings & Debug")
            .setView(dialogView)
            .setPositiveButton("Apply") { _, _ ->
                vadMgr.speechThreshold = speechThresholdSlider.progress / 100f
                vadMgr.silenceThreshold = silenceThresholdSlider.progress / 100f
                vadMgr.minSpeechDurationMs = minSpeechDurationSlider.progress * 100L
                vadMgr.maxSilenceDurationMs = maxSilenceDurationSlider.progress * 100L
                Toast.makeText(this, "VAD settings updated", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .setNeutralButton("Debug") { _, _ ->
                showDebugDialog()
            }
            .show()
    }
    
    private fun showDebugDialog() {
        val options = arrayOf(
            "Play Last Transcribed Segment", 
            "Manual Export Last WAV", 
            "Test Direct Transcription",
            "Show File Info"
        )
        
        AlertDialog.Builder(this)
            .setTitle("Debug Options")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> { // Play Last Transcribed Segment
                        lastTranscribedSegmentFile?.let { file ->
                            if (file.exists()) {
                                Log.d(TAG, "Debug: Playing last segment ${file.name}")
                                Toast.makeText(this, "Playing: ${file.name}", Toast.LENGTH_SHORT).show()
                                mPlayer?.initializePlayer(file.absolutePath)
                                mPlayer?.startPlayback()
                            } else {
                                Toast.makeText(this, "No last segment file found", Toast.LENGTH_SHORT).show()
                            }
                        } ?: Toast.makeText(this, "No last segment available", Toast.LENGTH_SHORT).show()
                    }
                    1 -> { // Manual Export Last WAV
                        lastTranscribedSegmentFile?.let { file ->
                            if (file.exists()) {
                                val exportName = "exported_segment_${System.currentTimeMillis()}.wav"
                                val exportFile = File(sdcardDataFolder, exportName)
                                try {
                                    file.copyTo(exportFile, overwrite = true)
                                    Toast.makeText(this, "Exported to: $exportName", Toast.LENGTH_LONG).show()
                                    Log.d(TAG, "Exported segment to: ${exportFile.absolutePath}")
                                } catch (e: Exception) {
                                    Toast.makeText(this, "Export failed: ${e.message}", Toast.LENGTH_SHORT).show()
                                    Log.e(TAG, "Export failed", e)
                                }
                            } else {
                                Toast.makeText(this, "No segment file to export", Toast.LENGTH_SHORT).show()
                            }
                        } ?: Toast.makeText(this, "No segment available", Toast.LENGTH_SHORT).show()
                    }
                    2 -> { // Test Direct Transcription
                        lastTranscribedSegmentFile?.let { file ->
                            if (file.exists()) {
                                Toast.makeText(this, "Testing direct transcription...", Toast.LENGTH_SHORT).show()
                                Thread {
                                    try {
                                        val audioSamples = WaveUtil.getSamples(file.absolutePath)
                                        if (audioSamples.isNotEmpty()) {
                                            Log.d(TAG, "Testing direct transcription with ${audioSamples.size} samples")
                                            transcribeSpeechSegmentDirect(audioSamples)
                                        } else {
                                            Log.w(TAG, "No audio samples loaded from file")
                                        }
                                    } catch (e: Exception) {
                                        Log.e(TAG, "Direct transcription test failed", e)
                                    }
                                }.start()
                            } else {
                                Toast.makeText(this, "No segment file available", Toast.LENGTH_SHORT).show()
                            }
                        } ?: Toast.makeText(this, "No segment available", Toast.LENGTH_SHORT).show()
                    }
                    3 -> { // Show File Info
                        lastTranscribedSegmentFile?.let { file ->
                            if (file.exists()) {
                                val info = """
                                    File: ${file.name}
                                    Size: ${file.length()} bytes
                                    Path: ${file.absolutePath}
                                    Last Modified: ${java.util.Date(file.lastModified())}
                                """.trimIndent()
                                
                                AlertDialog.Builder(this)
                                    .setTitle("Last Segment Info")
                                    .setMessage(info)
                                    .setPositiveButton("OK", null)
                                    .show()
                                    
                                Log.d(TAG, "Last segment info:\n$info")
                            } else {
                                Toast.makeText(this, "No segment file found", Toast.LENGTH_SHORT).show()
                            }
                        } ?: Toast.makeText(this, "No segment available", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    override fun onDataReceived(data: FloatArray?) {
        data?.let { mVADManager?.processAudioChunk(it) }
    }
    
    override fun onUpdateReceived(message: String?) {
        Log.d(TAG, "Recorder update: $message")
        
        when (message) {
            Recorder.MSG_RECORDING -> {
                handler.post { 
                    tvStatus?.text = "Recording - Listening..."
                    btnRecord?.setText(R.string.stop) 
                }
            }
            Recorder.MSG_RECORDING_DONE -> {
                handler.post { 
                    tvStatus?.text = "Recording stopped"
                    btnRecord?.setText(R.string.record)
                    btnSpeechIndicator?.isSelected = false
                }
            }
            else -> {
                handler.post { tvStatus?.text = message }
            }
        }
    }

    private fun updateStatus(status: String) {
        tvStatus?.text = status
        Log.d(TAG, "Status: $status")
    }

    private fun showError(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        Log.e(TAG, "Error: $message")
    }

    internal class SharedResource {
        // Synchronized method for Thread 1 to wait for a signal with a timeout
        @Synchronized
        fun waitForSignalWithTimeout(timeoutMillis: Long): Boolean {
            val startTime = System.currentTimeMillis()

            try {
                (this as java.lang.Object).wait(timeoutMillis) // Wait for the given timeout
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt() // Restore interrupt status
                return false // Thread interruption as timeout
            }

            val elapsedTime = System.currentTimeMillis() - startTime

            // Check if wait returned due to notify or timeout
            return elapsedTime < timeoutMillis
        }

        // Synchronized method for Thread 2 to send a signal
        @Synchronized
        fun sendSignal() {
            (this as java.lang.Object).notify() // Notifies the waiting thread
        }
    } // Test code for parallel processing
    //    private void testParallelProcessing() {
    //
    //        // Define the file names in an array
    //        String[] fileNames = {
    //                "english_test1.wav",
    //                "english_test2.wav",
    //                "english_test_3_bili.wav"
    //        };
    //
    //        // Multilingual model and vocab
    //        String modelMultilingual = getFilePath("whisper-tiny.tflite");
    //        String vocabMultilingual = getFilePath("filters_vocab_multilingual.bin");
    //
    //        // Perform task for multiple audio files using multilingual model
    //        for (String fileName : fileNames) {
    //            Whisper whisper = new Whisper(this);
    //            whisper.setAction(Whisper.ACTION_TRANSCRIBE);
    //            whisper.loadModel(modelMultilingual, vocabMultilingual, true);
    //            //whisper.setListener((msgID, message) -> Log.d(TAG, message));
    //            String waveFilePath = getFilePath(fileName);
    //            whisper.setFilePath(waveFilePath);
    //            whisper.start();
    //        }
    //
    //        // English-only model and vocab
    //        String modelEnglish = getFilePath("whisper-tiny-en.tflite");
    //        String vocabEnglish = getFilePath("filters_vocab_en.bin");
    //
    //        // Perform task for multiple audio files using english only model
    //        for (String fileName : fileNames) {
    //            Whisper whisper = new Whisper(this);
    //            whisper.setAction(Whisper.ACTION_TRANSCRIBE);
    //            whisper.loadModel(modelEnglish, vocabEnglish, false);
    //            //whisper.setListener((msgID, message) -> Log.d(TAG, message));
    //            String waveFilePath = getFilePath(fileName);
    //            whisper.setFilePath(waveFilePath);
    //            whisper.start();
    //        }
    //    }

    companion object {
        private const val TAG = "MainActivity"

        // whisper-tiny.tflite and whisper-base-nooptim.en.tflite works well
        private const val DEFAULT_MODEL_TO_USE = "whisper-tiny.tflite"

        // English only model ends with extension ".en.tflite"
        private const val ENGLISH_ONLY_MODEL_EXTENSION = ".en.tflite"
        private const val ENGLISH_ONLY_VOCAB_FILE = "filters_vocab_en.bin"
        private const val MULTILINGUAL_VOCAB_FILE = "filters_vocab_multilingual.bin"
        private val EXTENSIONS_TO_COPY = arrayOf("tflite", "bin", "wav", "pcm")

        // Copy assets with specified extensions to destination folder
        @JvmStatic
        private fun copyAssetsToSdcard(
            context: Context,
            destFolder: File?,
            extensions: Array<String>
        ) {
            val assetManager = context.assets

            try {
                // List all files in the assets folder once
                val assetFiles = assetManager.list("") ?: return

                for (assetFileName in assetFiles) {
                    // Check if file matches any of the provided extensions
                    for (extension in extensions) {
                        if (assetFileName.endsWith(".$extension")) {
                            val outFile = File(destFolder, assetFileName)

                            // Skip if file already exists
                            if (outFile.exists()) break

                            assetManager.open(assetFileName).use { inputStream ->
                                FileOutputStream(outFile).use { outputStream ->
                                    val buffer = ByteArray(1024)
                                    var bytesRead: Int
                                    while ((inputStream.read(buffer)
                                            .also { bytesRead = it }) != -1
                                    ) {
                                        outputStream.write(buffer, 0, bytesRead)
                                    }
                                }
                            }
                            break // No need to check further extensions
                        }
                    }
                }
            } catch (e: IOException) {
                e.printStackTrace()
            }
        }
    }
}