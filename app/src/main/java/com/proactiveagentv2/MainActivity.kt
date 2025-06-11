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
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.proactiveagentv2.asr.Player
import com.proactiveagentv2.asr.Player.PlaybackListener
import com.proactiveagentv2.asr.Recorder
import com.proactiveagentv2.asr.Recorder.RecorderListener
import com.proactiveagentv2.asr.Whisper
import com.proactiveagentv2.asr.Whisper.WhisperListener
import com.proactiveagentv2.utils.WaveUtil
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

class MainActivity : AppCompatActivity() {
    private var tvStatus: TextView? = null
    private var tvResult: TextView? = null
    private var fabCopy: FloatingActionButton? = null
    private var btnRecord: Button? = null
    private var btnPlay: Button? = null
    private var btnTranscribe: Button? = null

    private var mPlayer: Player? = null
    private var mRecorder: Recorder? = null
    private var mWhisper: Whisper? = null

    private var sdcardDataFolder: File? = null
    private var selectedWaveFile: File? = null
    private var selectedTfliteFile: File? = null

    private var startTime: Long = 0
    private val loopTesting = false
    private val transcriptionSync = SharedResource()
    private val handler = Handler(Looper.getMainLooper())

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
                mPlayer?.initializePlayer(selectedWaveFile!!.absolutePath)
                mPlayer?.startPlayback()
            } else {
                mPlayer?.stopPlayback()
            }
        })

        // Implementation of transcribe button functionality
        btnTranscribe = findViewById(R.id.btnTranscb)
        btnTranscribe?.setOnClickListener(View.OnClickListener { v: View? ->
            if (mRecorder != null && mRecorder!!.isInProgress) {
                Log.d(TAG, "Recording is in progress... stopping...")
                stopRecording()
            }
            if (mWhisper == null) initModel(selectedTfliteFile!!)
            if (mWhisper?.isInProgress == false) {
                Log.d(TAG, "Start transcription...")
                startTranscription(selectedWaveFile!!.absolutePath)

                // only for loop testing
                if (loopTesting) {
                    Thread {
                        for (i in 0..999) {
                            if (mWhisper?.isInProgress == false) startTranscription(selectedWaveFile!!.absolutePath)
                            else Log.d(
                                TAG,
                                "Whisper is already in progress...!"
                            )

                            val wasNotified =
                                transcriptionSync.waitForSignalWithTimeout(15000)
                            Log.d(
                                TAG,
                                if (wasNotified) "Transcription Notified...!" else "Transcription Timeout...!"
                            )
                        }
                    }.start()
                }
            } else {
                Log.d(TAG, "Whisper is already in progress...!")
                stopTranscription()
            }
        })

        tvStatus = findViewById(R.id.tvStatus)
        tvResult = findViewById(R.id.tvResult)
        fabCopy = findViewById(R.id.fabCopy)
        fabCopy?.setOnClickListener(View.OnClickListener { v: View? ->
            // Get the text from tvResult
            val textToCopy = tvResult?.getText().toString()

            // Copy the text to the clipboard
            val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("Copied Text", textToCopy)
            clipboard.setPrimaryClip(clip)
        })

        // Audio recording functionality
        mRecorder = Recorder(this)
        mRecorder?.setListener(object : RecorderListener {
            override fun onUpdateReceived(message: String?) {
                Log.d(
                    TAG,
                    "Update is received, Message: $message"
                )
                handler.post { tvStatus?.setText(message) }

                if (message == Recorder.MSG_RECORDING) {
                    handler.post { tvResult?.setText("") }
                    handler.post { btnRecord?.setText(R.string.stop) }
                } else if (message == Recorder.MSG_RECORDING_DONE) {
                    handler.post { btnRecord?.setText(R.string.record) }
                }
            }

            override fun onDataReceived(samples: FloatArray?) {
//                mWhisper.writeBuffer(samples);
            }
        })

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
                    handler.post { tvStatus?.text = message }
                    handler.post { tvResult?.text = "" }
                    startTime = System.currentTimeMillis()
                }
                if (message == Whisper.MSG_PROCESSING_DONE) {
//                    handler.post(() -> tvStatus.setText(message));
                    // for testing
                    if (loopTesting) transcriptionSync.sendSignal()
                } else if (message == Whisper.MSG_FILE_NOT_FOUND) {
                    handler.post { tvStatus?.text = message }
                    Log.d(TAG, "File not found error...!")
                }
            }

            override fun onResultReceived(result: String?) {
                val timeTaken = System.currentTimeMillis() - startTime
                handler.post {
                    tvStatus?.text =
                        "Processing done in " + timeTaken + "ms"
                }

                Log.d(TAG, "Result: $result")
                handler.post { tvResult?.append(result) }
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
        mRecorder!!.start()
    }

    private fun stopRecording() {
        mRecorder!!.stop()
    }

    // Transcription calls
    private fun startTranscription(waveFilePath: String) {
        mWhisper!!.setFilePath(waveFilePath)
        mWhisper!!.setAction(Whisper.ACTION_TRANSCRIBE)
        mWhisper!!.start()
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
            return if (elapsedTime < timeoutMillis) {
                true // Returned due to notify
            } else {
                false // Returned due to timeout
            }
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