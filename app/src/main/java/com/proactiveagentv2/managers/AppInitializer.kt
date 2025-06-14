package com.proactiveagentv2.managers

import android.content.Context
import android.util.Log
import com.proactiveagentv2.asr.Player
import com.proactiveagentv2.asr.Recorder
import com.proactiveagentv2.asr.Whisper
import com.proactiveagentv2.llm.LLMManager
import com.proactiveagentv2.ui.MainViewModel
import com.proactiveagentv2.utils.WaveUtil
import com.proactiveagentv2.vad.VADManager
import java.io.File
import java.io.FileOutputStream
import java.util.ArrayList

/**
 * Handles application initialization including model setup, audio components, VAD, and LLM initialization
 */
class AppInitializer(
    private val context: Context,
    private val viewModel: MainViewModel
) {
    private var sdcardDataFolder: File? = null
    
    // Audio components
    var recorder: Recorder? = null
        private set
    var player: Player? = null
        private set
    var whisper: Whisper? = null
        private set
    var vadManager: VADManager? = null
        private set
    var llmManager: LLMManager? = null
        private set
    var classifierManager: ClassifierManager? = null
        private set
    
    // Model management
    var selectedModelFile: File? = null
        private set
    
    fun initialize(): InitializationResult {
        Log.d(TAG, "=== Starting application initialization ===")
        
        return try {
            initializeFileSystem()
            initializeModels()
            initializeAudioComponents()
            initializeVADSystem()
            initializeLLMSystem()
            initializeClassifierSystem()
            
            Log.d(TAG, "=== Application initialization completed successfully ===")
            InitializationResult.Success
        } catch (e: Exception) {
            Log.e(TAG, "Application initialization failed", e)
            InitializationResult.Error(e.message ?: "Unknown initialization error")
        }
    }
    
    private fun initializeFileSystem() {
        Log.d(TAG, "Initializing file system...")
        sdcardDataFolder = context.getExternalFilesDir(null)
        copyAssetsToSdcard(context, sdcardDataFolder, REQUIRED_ASSET_EXTENSIONS)
        Log.d(TAG, "File system initialized: ${sdcardDataFolder?.absolutePath}")
    }
    
    private fun initializeModels() {
        Log.d(TAG, "Initializing STT models...")
        
        val modelFiles = getModelFiles()
        Log.d(TAG, "Found ${modelFiles.size} model files: ${modelFiles.map { it.name }}")
        
        // Update ViewModel with available models
        viewModel.updateModelFiles(modelFiles)
        
        // Set and initialize default model
        selectedModelFile = File(sdcardDataFolder, DEFAULT_MODEL_NAME)
        
        if (selectedModelFile?.exists() == true) {
            viewModel.selectModelFile(selectedModelFile!!)
            initializeSTTModel(selectedModelFile!!)
            Log.d(TAG, "Default STT model initialized: ${selectedModelFile?.name}")
        } else {
            Log.w(TAG, "Default model not found: $DEFAULT_MODEL_NAME")
            throw IllegalStateException("Required STT model not found")
        }
    }
    
    private fun initializeAudioComponents() {
        Log.d(TAG, "Initializing audio components...")
        
        // Initialize Recorder
        recorder = Recorder(context)
        
        // Initialize Player
        player = Player(context)
        
        Log.d(TAG, "Audio components initialized successfully")
    }
    
    private fun initializeVADSystem() {
        Log.d(TAG, "Initializing Voice Activity Detection...")
        
        vadManager = VADManager(context)
        val vadInitialized = vadManager?.initialize() ?: false
        
        if (!vadInitialized) {
            throw IllegalStateException("Failed to initialize Voice Activity Detection")
        }
        
        Log.d(TAG, "VAD system initialized successfully")
    }
    
    private fun initializeLLMSystem() {
        Log.d(TAG, "Initializing LLM system...")
        
        try {
            llmManager = LLMManager(context)
            Log.d(TAG, "LLM manager created successfully (model not loaded)")
            Log.d(TAG, "Note: LLM model needs to be downloaded and initialized separately via settings")
        } catch (e: Exception) {
            Log.e(TAG, "Error creating LLM manager", e)
            llmManager = null
            Log.w(TAG, "LLM functionality will be disabled")
        }
    }
    
    private fun initializeClassifierSystem() {
        Log.d(TAG, "Initializing MobileBERT classifier system...")
        
        try {
            // We need to pass a coroutine scope, but AppInitializer doesn't have one
            // The ClassifierManager will be initialized later in MainActivity with proper scope
            Log.d(TAG, "ClassifierManager will be initialized in MainActivity with proper coroutine scope")
        } catch (e: Exception) {
            Log.e(TAG, "Error preparing classifier system", e)
            Log.w(TAG, "Classifier functionality will be disabled")
        }
    }
    
    fun initializeSTTModel(modelFile: File): Boolean {
        return try {
            Log.d(TAG, "Initializing STT model: ${modelFile.name}")
            
            val isMultilingualModel = !modelFile.name.endsWith(ENGLISH_ONLY_MODEL_EXTENSION)
            val vocabFileName = if (isMultilingualModel) MULTILINGUAL_VOCAB_FILE else ENGLISH_ONLY_VOCAB_FILE
            val vocabFile = File(sdcardDataFolder, vocabFileName)
            
            whisper = Whisper(context)
            whisper?.loadModel(modelFile, vocabFile, isMultilingualModel)
            
            selectedModelFile = modelFile
            viewModel.selectModelFile(modelFile)
            
            Log.d(TAG, "STT model initialized successfully")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize STT model", e)
            false
        }
    }
    
    fun deinitializeSTTModel() {
        whisper?.unloadModel()
        whisper = null
        Log.d(TAG, "STT model deinitialized")
    }
    
    fun release() {
        Log.d(TAG, "Releasing application resources...")
        
        vadManager?.release()
        vadManager = null
        
        llmManager?.release()
        llmManager = null
        
        classifierManager?.release()
        classifierManager = null
        
        deinitializeSTTModel()
        
        player = null
        recorder = null
        
        Log.d(TAG, "Application resources released")
    }
    
    private fun getModelFiles(): List<File> {
        return getFilesWithExtension(sdcardDataFolder, ".tflite")
    }
    
    private fun copyAssetsToSdcard(context: Context, sdcardDataFolder: File?, extensions: Array<String>) {
        if (sdcardDataFolder == null) return
        
        try {
            val assetManager = context.assets
            val files = assetManager.list("") ?: return
            
            for (filename in files) {
                if (extensions.any { filename.endsWith(it) }) {
                    val assetInputStream = assetManager.open(filename)
                    val outFile = File(sdcardDataFolder, filename)
                    
                    if (!outFile.exists()) {
                        val outStream = FileOutputStream(outFile)
                        assetInputStream.copyTo(outStream)
                        assetInputStream.close()
                        outStream.close()
                        Log.d(TAG, "Copied asset: $filename")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error copying assets", e)
        }
    }
    
    private fun getFilesWithExtension(directory: File?, extension: String): ArrayList<File> {
        val filteredFiles = ArrayList<File>()
        
        if (directory?.exists() == true) {
            directory.listFiles()?.forEach { file ->
                if (file.name.endsWith(extension)) {
                    filteredFiles.add(file)
                }
            }
        }
        
        return filteredFiles
    }
    
    sealed class InitializationResult {
        object Success : InitializationResult()
        data class Error(val message: String) : InitializationResult()
    }
    
    companion object {
        private const val TAG = "AppInitializer"
        
        // Model configuration
        private const val DEFAULT_MODEL_NAME = "whisper-tiny.tflite"
        private const val ENGLISH_ONLY_MODEL_EXTENSION = ".en.tflite"
        private const val MULTILINGUAL_VOCAB_FILE = "filters_vocab_multilingual.bin"
        private const val ENGLISH_ONLY_VOCAB_FILE = "filters_vocab_en.bin"
        
        // Required asset extensions
        private val REQUIRED_ASSET_EXTENSIONS = arrayOf(".tflite", ".bin")
    }
} 