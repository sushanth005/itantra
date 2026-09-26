package com.itantra.tts

import android.content.Context
import android.util.Log
import com.itantra.model.Language
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class SherpaTtsEngine(private val context: Context) {
    companion object {
        private const val TAG = "SherpaTtsEngine"
    }

    private var tts: OfflineTts? = null
    var loadedLanguage: Language = Language.ENGLISH
        private set
    var isLoaded: Boolean = false
        private set

    suspend fun load(language: Language): Language = withContext(Dispatchers.IO) {
        if (isLoaded && loadedLanguage == language) {
            return@withContext language
        }

        tts?.release()
        tts = null
        isLoaded = false

        val modelDir = "sherpa_tts_${language.code}"
        
        // Check if models exist
        val hasModel = try {
            context.assets.list(modelDir)?.contains("model.onnx") == true
        } catch(e: Exception) { false }

        if (!hasModel) {
            Log.e(TAG, "No Sherpa TTS model found for ${language.code} in assets/$modelDir")
            return@withContext language
        }

        try {
            val config = OfflineTtsConfig(
                model = OfflineTtsModelConfig(
                    vits = OfflineTtsVitsModelConfig(
                        model = "$modelDir/model.onnx",
                        tokens = "$modelDir/tokens.txt",
                        lexicon = "",
                        dataDir = "",
                        dictDir = "",
                    ),
                    numThreads = 2,
                    debug = false,
                    provider = "cpu"
                ),
                maxNumSentences = 1
            )

            tts = OfflineTts(assetManager = context.assets, config = config)
            loadedLanguage = language
            isLoaded = true
            Log.d(TAG, "Sherpa TTS loaded for ${language.code}")
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to load Sherpa TTS for ${language.code}: ${e.message}")
        }
        
        language
    }

    suspend fun synthesize(text: String): FloatArray = withContext(Dispatchers.Default) {
        val engine = tts
        if (engine == null) {
            Log.e(TAG, "SherpaTtsEngine not loaded when synthesize() called")
            return@withContext FloatArray(0)
        }
        
        try {
            // Sherpa-ONNX TTS generate() blocks and returns generated audio
            val audio = engine.generate(text, sid = 0, speed = 1.0f)
            audio?.samples ?: FloatArray(0)
        } catch (e: Throwable) {
            Log.e(TAG, "Sherpa TTS synthesize failed", e)
            FloatArray(0)
        }
    }

    fun release() {
        tts?.release()
        tts = null
        isLoaded = false
    }
}
