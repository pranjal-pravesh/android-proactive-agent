package com.example.proactiiveagentv1.transcription

import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.math.*

class WhisperUtil {
    companion object {
        private const val TAG = "WhisperUtil"
        
        const val WHISPER_SAMPLE_RATE = 16000
        const val WHISPER_N_FFT = 400
        const val WHISPER_N_MEL = 80
        const val WHISPER_HOP_LENGTH = 160
        const val WHISPER_CHUNK_SIZE = 30
        const val WHISPER_MEL_LEN = 3000
    }
    
    private val vocab = WhisperVocab()
    private val filters = WhisperFilter()
    val tokenEOT: Int get() = vocab.tokenEOT
    
    fun loadFiltersAndVocab(multilingual: Boolean, vocabPath: String): Boolean {
        return try {
            val bytes = Files.readAllBytes(Paths.get(vocabPath))
            val loaded = vocab.loadFromBytes(bytes, multilingual) && filters.loadFromBytes(bytes)
            if (loaded) {
                Log.d(TAG, "Filters and vocabulary loaded successfully")
            }
            loaded
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load filters and vocabulary", e)
            false
        }
    }
    
    fun getTokenString(token: Int): String {
        return vocab.getTokenString(token)
    }
    
    fun getMelSpectrogram(samples: FloatArray): FloatArray {
        return getMelSpectrogram(samples, samples.size, Runtime.getRuntime().availableProcessors())
    }
    
    fun getMelSpectrogram(samples: FloatArray, length: Int, cores: Int): FloatArray {
        Log.d(TAG, "Computing mel spectrogram for ${length} samples")
        
        // Pad/truncate audio to fixed size (30 seconds at 16kHz = 480,000 samples)
        val fixedInputSize = WHISPER_SAMPLE_RATE * WHISPER_CHUNK_SIZE
        val inputSamples = FloatArray(fixedInputSize)
        val copyLength = minOf(length, fixedInputSize)
        System.arraycopy(samples, 0, inputSamples, 0, copyLength)
        // Rest of array is already zero-filled
        
        val fftSize = WHISPER_N_FFT
        val fftStep = WHISPER_HOP_LENGTH
        val nLen = inputSamples.size / fftStep
        val nFft = 1 + fftSize / 2
        
        Log.d(TAG, "nLen: $nLen, nFft: $nFft, expected output size: ${WHISPER_N_MEL * nLen}")
        
        // Create Hanning window
        val hann = FloatArray(fftSize) { i ->
            (0.5 * (1.0 - cos(2.0 * PI * i / fftSize))).toFloat()
        }
        
        // Initialize mel spectrogram data
        val melData = FloatArray(WHISPER_N_MEL * nLen)
        
        // Process each frame
        val fftIn = FloatArray(fftSize)
        val fftOut = FloatArray(fftSize * 2)
        
        for (i in 0 until nLen) {
            val offset = i * fftStep
            
            // Apply Hanning window
            for (j in 0 until fftSize) {
                fftIn[j] = if (offset + j < inputSamples.size) {
                    hann[j] * inputSamples[offset + j]
                } else {
                    0.0f
                }
            }
            
            // FFT -> mag^2
            fft(fftIn, fftOut)
            for (j in 0 until fftSize) {
                fftOut[j] = fftOut[2 * j] * fftOut[2 * j] + fftOut[2 * j + 1] * fftOut[2 * j + 1]
            }
            
            // Symmetry for real FFT
            for (j in 1 until fftSize / 2) {
                fftOut[j] += fftOut[fftSize - j]
            }
            
            // Apply mel filter banks
            for (j in 0 until WHISPER_N_MEL) {
                var sum = 0.0
                for (k in 0 until nFft) {
                    if (filters.data != null && j * nFft + k < filters.data!!.size) {
                        sum += (fftOut[k] * filters.data!![j * nFft + k]).toDouble()
                    }
                }
                
                if (sum < 1e-10) {
                    sum = 1e-10
                }
                
                sum = log10(sum)
                melData[j * nLen + i] = sum.toFloat()
            }
        }
        
        // Clamping and normalization (following demo exactly)
        var mmax = -1e20
        for (i in melData.indices) {
            if (melData[i] > mmax) {
                mmax = melData[i].toDouble()
            }
        }
        
        mmax -= 8.0
        for (i in melData.indices) {
            if (melData[i] < mmax) {
                melData[i] = mmax.toFloat()
            }
            melData[i] = ((melData[i] + 4.0) / 4.0).toFloat()
        }
        
        Log.d(TAG, "Mel spectrogram computed: ${melData.size} elements")
        return melData
    }
    
    private fun fft(input: FloatArray, output: FloatArray) {
        val inSize = input.size
        if (inSize == 1) {
            output[0] = input[0]
            output[1] = 0.0f
            return
        }
        
        if (inSize % 2 == 1) {
            dft(input, output)
            return
        }
        
        val even = FloatArray(inSize / 2)
        val odd = FloatArray(inSize / 2)
        
        var indxEven = 0
        var indxOdd = 0
        for (i in 0 until inSize) {
            if (i % 2 == 0) {
                even[indxEven] = input[i]
                indxEven++
            } else {
                odd[indxOdd] = input[i]
                indxOdd++
            }
        }
        
        val evenFft = FloatArray(inSize)
        val oddFft = FloatArray(inSize)
        
        fft(even, evenFft)
        fft(odd, oddFft)
        
        for (k in 0 until inSize / 2) {
            val theta = 2 * PI * k / inSize
            val re = cos(theta).toFloat()
            val im = -sin(theta).toFloat()
            val reOdd = oddFft[2 * k + 0]
            val imOdd = oddFft[2 * k + 1]
            
            output[2 * k + 0] = evenFft[2 * k + 0] + re * reOdd - im * imOdd
            output[2 * k + 1] = evenFft[2 * k + 1] + re * imOdd + im * reOdd
            output[2 * (k + inSize / 2) + 0] = evenFft[2 * k + 0] - re * reOdd + im * imOdd
            output[2 * (k + inSize / 2) + 1] = evenFft[2 * k + 1] - re * imOdd - im * reOdd
        }
    }
    
    private fun dft(input: FloatArray, output: FloatArray) {
        val inSize = input.size
        for (k in 0 until inSize) {
            var re = 0.0f
            var im = 0.0f
            for (n in 0 until inSize) {
                val angle = 2 * PI * k * n / inSize
                re += input[n] * cos(angle).toFloat()
                im -= input[n] * sin(angle).toFloat()
            }
            output[k * 2 + 0] = re
            output[k * 2 + 1] = im
        }
    }
}

class WhisperVocab {
    private val tokenToString = mutableMapOf<Int, String>()
    private var isMultilingual = false
    
    val tokenEOT = 50256 // End of text token
    val tokenSOT = 50257 // Start of text token
    
    fun loadFromBytes(bytes: ByteArray, multilingual: Boolean): Boolean {
        this.isMultilingual = multilingual
        
        return try {
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.nativeOrder())
            Log.d("WhisperVocab", "Vocab file size: ${buffer.limit()}")
            
            // Check magic number: @magic:USEN = 0x5553454e
            val magic = buffer.int
            if (magic != 0x5553454e) {
                Log.e("WhisperVocab", "Invalid vocab file (bad magic: $magic)")
                return false
            }
            Log.d("WhisperVocab", "Magic number verified: $magic")
            
            // Skip mel filter data
            val nMel = buffer.int
            val nFft = buffer.int
            Log.d("WhisperVocab", "n_mel: $nMel, n_fft: $nFft")
            
            // Skip filter data
            val filterDataSize = nMel * nFft * Float.SIZE_BYTES
            buffer.position(buffer.position() + filterDataSize)
            
            // Load vocabulary
            val nVocab = buffer.int
            Log.d("WhisperVocab", "nVocab: $nVocab")
            
            for (i in 0 until nVocab) {
                val len = buffer.int
                if (len < 0 || len > 1000) { // Sanity check
                    Log.e("WhisperVocab", "Invalid token length: $len at token $i")
                    return false
                }
                
                val wordBytes = ByteArray(len)
                buffer.get(wordBytes)
                val word = String(wordBytes, Charsets.UTF_8)
                tokenToString[i] = word
            }
            
            Log.d("WhisperVocab", "Vocabulary loaded successfully: ${tokenToString.size} tokens")
            true
        } catch (e: Exception) {
            Log.e("WhisperVocab", "Failed to load vocabulary", e)
            false
        }
    }
    
    fun getTokenString(token: Int): String {
        return tokenToString[token] ?: ""
    }
}

class WhisperFilter {
    var nMel: Int = 0
    var nFft: Int = 0
    var data: FloatArray? = null
    
    fun loadFromBytes(bytes: ByteArray): Boolean {
        return try {
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.nativeOrder())
            
            // Skip magic number
            buffer.int
            
            // Read mel filter dimensions
            nMel = buffer.int
            nFft = buffer.int
            
            Log.d("WhisperFilter", "Loading mel filters: nMel=$nMel, nFft=$nFft")
            
            // Read filter data
            val filterDataSize = nMel * nFft
            data = FloatArray(filterDataSize)
            
            for (i in 0 until filterDataSize) {
                data!![i] = buffer.float
            }
            
            Log.d("WhisperFilter", "Mel filters loaded successfully")
            true
        } catch (e: Exception) {
            Log.e("WhisperFilter", "Failed to load mel filters", e)
            false
        }
    }
} 