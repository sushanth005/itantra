package com.itantra.stt

import android.content.Context
import android.util.Log
import com.itantra.model.Language
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class SherpaSttEngine(private val context: Context) {
    companion object {
        private const val TAG = "SherpaSttEngine"
    }

    private var recognizer: OfflineRecognizer? = null
    var loadedLanguage: Language = Language.ENGLISH
        private set
    var isLoaded: Boolean = false
        private set

    suspend fun load(language: Language): Language = withContext(Dispatchers.IO) {
        if (isLoaded && loadedLanguage == language) {
            return@withContext language
        }

        recognizer?.release()
        
        val whisperCode = when (language) {
            Language.HINDI -> "hi"
            Language.TAMIL -> "ta"
            Language.TELUGU -> "te"
            Language.KANNADA -> "kn"
            Language.MALAYALAM -> "ml"
            Language.MARATHI -> "mr"
            Language.GUJARATI -> "gu"
            Language.BENGALI -> "bn"
            Language.ODIA -> "or"
            else -> "en"
        }

        try {
            val config = OfflineRecognizerConfig(
                featConfig = FeatureConfig(
                    sampleRate = 16000,
                    featureDim = 80
                ),
                modelConfig = OfflineModelConfig(
                    whisper = OfflineWhisperModelConfig(
                        encoder = "sherpa_stt/tiny-encoder.int8.onnx",
                        decoder = "sherpa_stt/tiny-decoder.int8.onnx",
                        language = whisperCode,
                        task = "transcribe",
                        tailPaddings = -1
                    ),
                    tokens = "sherpa_stt/tiny-tokens.txt",
                    modelType = "whisper",
                    numThreads = 4,
                    debug = false
                )
            )

            recognizer = OfflineRecognizer(
                assetManager = context.assets,
                config = config
            )
            loadedLanguage = language
            isLoaded = true
            Log.d(TAG, "Sherpa STT loaded for ${language.code}")
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to load Sherpa STT: ${e.message}")
            isLoaded = false
        }
        
        language
    }

    suspend fun transcribe(audioBuffer: ShortArray): String = withContext(Dispatchers.Default) {
        val rec = recognizer ?: return@withContext ""
        
        // Convert ShortArray to FloatArray normalized [-1.0, 1.0]
        val floats = FloatArray(audioBuffer.size) { i ->
            audioBuffer[i] / 32768.0f
        }

        val stream = rec.createStream()
        stream.acceptWaveform(floats, 16000)
        rec.decode(stream)
        val result = rec.getResult(stream)
        stream.release()
        
        result.text
    }

    fun release() {
        recognizer?.release()
        recognizer = null
        isLoaded = false
    }
}
