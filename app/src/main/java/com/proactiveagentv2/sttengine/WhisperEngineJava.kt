package com.proactiveagentv2.sttengine

import android.content.Context
import android.util.Log
import com.proactiveagentv2.utils.WaveUtil.getSamples
import com.proactiveagentv2.utils.WhisperUtil
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.Tensor
import org.tensorflow.lite.support.tensorbuffer.TensorBuffer
import java.io.FileInputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import kotlin.math.min

//import com.google.android.gms.tflite.client.TfLiteInitializationOptions;
//import com.google.android.gms.tflite.gpu.support.TfLiteGpu;
//import com.google.android.gms.tflite.java.TfLite;
//import org.tensorflow.lite.gpu.CompatibilityList;
//import org.tensorflow.lite.gpu.GpuDelegate;
//import org.tensorflow.lite.nnapi.NnApiDelegate;
class WhisperEngineJava //    private GpuDelegate gpuDelegate;
    (private val mContext: Context) : WhisperEngine {
    private val TAG = "WhisperEngineJava"
    private val mWhisperUtil = WhisperUtil()

    private var mIsInitialized = false
    private var mInterpreter: Interpreter? = null

    override val isInitialized: Boolean
        get() = mIsInitialized

    @Throws(IOException::class)
    override fun initialize(modelPath: String, vocabPath: String, multilingual: Boolean): Boolean {
        // Load model
        loadModel(modelPath)
        Log.d(TAG, "Model is loaded...$modelPath")

        // Load filters and vocab
        val ret = mWhisperUtil.loadFiltersAndVocab(multilingual, vocabPath)
        if (ret) {
            mIsInitialized = true
            Log.d(TAG, "Filters and Vocab are loaded...$vocabPath")
        } else {
            mIsInitialized = false
            Log.d(TAG, "Failed to load Filters and Vocab...")
        }

        return mIsInitialized
    }

    // Unload the model by closing the interpreter
    override fun deinitialize() {
        if (mInterpreter != null) {
            mInterpreter!!.close()
            mInterpreter = null // Optional: Set to null to avoid accidental reuse
        }
    }

    override fun transcribeFile(wavePath: String): String {
        // Calculate Mel spectrogram
        Log.d(TAG, "Calculating Mel spectrogram...")
        val melSpectrogram = getMelSpectrogram(wavePath)
        Log.d(TAG, "Mel spectrogram is calculated...!")

        // Perform inference
        val result = runInference(melSpectrogram)
        Log.d(TAG, "Inference is executed...!")

        return result
    }

    override fun transcribeBuffer(samples: FloatArray): String {
        return ""
    }

    // Load TFLite model
    @Throws(IOException::class)
    private fun loadModel(modelPath: String) {
        val fileInputStream = FileInputStream(modelPath)
        val fileChannel = fileInputStream.channel
        val startOffset: Long = 0
        val declaredLength = fileChannel.size()
        val tfliteModel: ByteBuffer =
            fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength)

        // Set the number of threads for inference
        val options = Interpreter.Options()
        options.setNumThreads(Runtime.getRuntime().availableProcessors())

        //        options.setUseXNNPACK(true);

//        boolean isNNAPI = true;
//        if (isNNAPI) {
//            // Initialize interpreter with NNAPI delegate for Android Pie or above
//            NnApiDelegate nnapiDelegate = new NnApiDelegate();
//            options.addDelegate(nnapiDelegate);
//            options.setUseNNAPI(false);
//            options.setAllowFp16PrecisionForFp32(true);
//            options.setAllowBufferHandleOutput(true);
//            options.setUseNNAPI(true);
//        }

        // Check if GPU delegate is available asynchronously
//        TfLiteGpu.isGpuDelegateAvailable(mContext).addOnCompleteListener(task -> {
//            if (task.isSuccessful() && task.getResult()) {
//                // GPU is available; initialize the interpreter with GPU delegate
//                GpuDelegate gpuDelegate = new GpuDelegate();
//                Interpreter.Options options = new Interpreter.Options().addDelegate(gpuDelegate);
//                tflite = new Interpreter(loadModelFile(), options);
//                TfLite.initialize(mContext, TfLiteInitializationOptions.builder().setEnableGpuDelegateSupport(true).build());
//                Log.d(TAG, "GPU is available; initialize the interpreter with GPU delegate........................");
//            } else {
//                // GPU is not available; fallback to CPU
//                tflite = new Interpreter (loadModelFile());
//                System.out.println("Initialized with CPU.");
//                Log.d(TAG, "GPU is not available; fallback to CPU........................");
//            }
//        });

//        boolean isGPU = true;
//        if (isGPU) {
//            gpuDelegate = new GpuDelegate();
//            options.setPrecisionLossAllowed(true); // It seems that the default is true
//            options.setInferencePreference(GpuDelegate.Options.INFERENCE_PREFERENCE_SUSTAINED_SPEED);
//             .setPrecisionLossAllowed(true) // Allow FP16 precision for faster performance
//                    .setInferencePreference(GpuDelegate.Options.INFERENCE_PREFERENCE_FAST_SINGLE_ANSWER);
//            options.addDelegate(gpuDelegate);
//        }
        mInterpreter = Interpreter(tfliteModel, options)
    }

    private fun getMelSpectrogram(wavePath: String): FloatArray {
        // Get samples in PCM_FLOAT format
        val samples = getSamples(wavePath)

        val fixedInputSize = WhisperUtil.WHISPER_SAMPLE_RATE * WhisperUtil.WHISPER_CHUNK_SIZE
        val inputSamples = FloatArray(fixedInputSize)
        val copyLength = min(samples.size.toDouble(), fixedInputSize.toDouble()).toInt()
        System.arraycopy(samples, 0, inputSamples, 0, copyLength)

        val cores = Runtime.getRuntime().availableProcessors()
        return mWhisperUtil.getMelSpectrogram(inputSamples, inputSamples.size, cores)
    }

    private fun runInference(inputData: FloatArray): String {
        // Create input tensor
        val inputTensor = mInterpreter!!.getInputTensor(0)
        val inputBuffer = TensorBuffer.createFixedSize(inputTensor.shape(), inputTensor.dataType())

        //        printTensorDump("Input Tensor Dump ===>", inputTensor);

        // Create output tensor
        val outputTensor = mInterpreter!!.getOutputTensor(0)
        val outputBuffer = TensorBuffer.createFixedSize(outputTensor.shape(), DataType.FLOAT32)

        //        printTensorDump("Output Tensor Dump ===>", outputTensor);

        // Load input data
        val inputSize =
            inputTensor.shape()[0] * inputTensor.shape()[1] * inputTensor.shape()[2] * java.lang.Float.BYTES
        val inputBuf = ByteBuffer.allocateDirect(inputSize)
        inputBuf.order(ByteOrder.nativeOrder())
        for (input in inputData) {
            inputBuf.putFloat(input)
        }

        // To test mel data as a input directly
//        try {
//            byte[] bytes = Files.readAllBytes(Paths.get("/data/user/0/com.example.tfliteaudio/files/mel_spectrogram.bin"));
//            inputBuf = ByteBuffer.wrap(bytes);
//        } catch (IOException e) {
//            throw new RuntimeException(e);
//        }
        inputBuffer.loadBuffer(inputBuf)

        //        Log.d(TAG, "Before inference...");
        // Run inference
        mInterpreter!!.run(inputBuffer.buffer, outputBuffer.buffer)

        //        Log.d(TAG, "After inference...");

        // Retrieve the results
        val outputLen = outputBuffer.intArray.size
        Log.d(TAG, "output_len: $outputLen")
        val result = StringBuilder()
        for (i in 0..<outputLen) {
            val token = outputBuffer.buffer.getInt()
            if (token == mWhisperUtil.tokenEOT) break

            // Get word for token and Skip additional token
            if (token < mWhisperUtil.tokenEOT) {
                val word = mWhisperUtil.getWordFromToken(token)
                //Log.d(TAG, "Adding token: " + token + ", word: " + word);
                result.append(word)
            } else {
                if (token == mWhisperUtil.tokenTranscribe) Log.d(TAG, "It is Transcription...")

                if (token == mWhisperUtil.tokenTranslate) Log.d(TAG, "It is Translation...")

                val word = mWhisperUtil.getWordFromToken(token)
                Log.d(TAG, "Skipping token: $token, word: $word")
            }
        }

        return result.toString()
    }

    private fun printTensorDump(message: String, tensor: Tensor) {
        Log.d(TAG, "Output Tensor Dump ===>")
        Log.d(TAG, "  shape.length: " + tensor.shape().size)
        for (i in tensor.shape().indices) Log.d(TAG, "    shape[" + i + "]: " + tensor.shape()[i])
        Log.d(TAG, "  dataType: " + tensor.dataType())
        Log.d(TAG, "  name: " + tensor.name())
        Log.d(TAG, "  numBytes: " + tensor.numBytes())
        Log.d(TAG, "  index: " + tensor.index())
        Log.d(TAG, "  numDimensions: " + tensor.numDimensions())
        Log.d(TAG, "  numElements: " + tensor.numElements())
        Log.d(TAG, "  shapeSignature.length: " + tensor.shapeSignature().size)
        Log.d(TAG, "  quantizationParams.getScale: " + tensor.quantizationParams().scale)
        Log.d(TAG, "  quantizationParams.getZeroPoint: " + tensor.quantizationParams().zeroPoint)
        Log.d(TAG, "==================================================================")
    }
}
