package com.itantra.stt

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.util.Log
import com.itantra.audio.MelSpectrogram
import com.itantra.model.Language
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.FloatBuffer

/**
 * On-device Speech-to-Text engine using the AI4Bharat IndicConformer ONNX model.
 *
 * Pipeline:
 *   Raw PCM (ShortArray, 16kHz)
 *   → MelSpectrogram.extract() → [1, 80, T_frames] log-mel tensor
 *   → IndicConformer ONNX inference → logprobs [1, T_out, 257]
 *   → Greedy CTC decode (blank = 256) → text string
 *
 * ONNX model inputs (verified):
 *   "audio_signal"  [batch, 80, T_frames]  float32  ← mel spectrogram (NOT raw waveform)
 *   "length"        [batch]                int64    ← number of mel frames
 * ONNX model outputs:
 *   "logprobs"      [batch, T_out, 257]    float32
 *
 * Asset naming:
 *   assets/stt_{langCode}.onnx     (e.g. stt_hi.onnx for Hindi)
 *   assets/vocab_{langCode}.txt    (SentencePiece BPE vocab, format: "token id")
 *
 * Language availability:
 *   When the model file for the requested language is absent, [load] falls back
 *   to Hindi and sets [isFallbackActive] = true.
 *
 * Fix notes (v2):
 *   • Vocab file now opened with explicit Charsets.UTF_8 — fixes potential
 *     silent encoding failure on Android when the platform default charset is
 *     not UTF-8 (some Android builds default to US-ASCII or ISO-8859-1).
 *   • Added debugTokenLog flag: logs raw CTC token IDs before vocab lookup,
 *     so you can tell whether the model is producing output at all.
 *   • Added input-shape validation on model load: logs exact shapes so you
 *     can immediately detect any model/engine mismatch.
 *   • Added model size warning when STT model exceeds 60 MB (suggests
 *     the INT8 quantization step was skipped).
 */
class SttEngine(private val context: Context) {

    companion object {
        private const val TAG = "SttEngine"

        // CTC blank token is the LAST token in the vocabulary (id=256, "<blk>")
        // This is confirmed by reading vocab_hi.txt: last line is "<blk> 256".
        private const val CTC_BLANK_ID = 256L

        /** Check whether an asset file exists without throwing. */
        private fun assetExists(context: Context, name: String): Boolean = try {
            context.assets.open(name).close(); true
        } catch (_: Exception) { false }
    }

    // ── Debug flags ───────────────────────────────────────────────────────────

    /**
     * When true, [transcribe] logs the raw CTC token IDs (before vocab lookup)
     * to Logcat at DEBUG level under the tag "SttEngine".
     * Useful for diagnosing blank output: if IDs are all 256 (blank), the model
     * is not producing any speech tokens — likely a mel spectrogram issue.
     * If IDs are valid (0–255) but vocab lookup returns empty strings, it's a
     * vocab encoding issue.
     */
    var debugTokenLog: Boolean = true

    private var ortEnv: OrtEnvironment? = null
    private var session: OrtSession? = null
    private var currentLanguage: Language? = null

    /** Last inference latency in milliseconds. Exposed for the diagnostics overlay. */
    var lastLatencyMs: Long = 0L
        private set

    /** True when model is loaded and ready for inference. */
    val isLoaded: Boolean get() = session != null

    /**
     * Non-null when the last [load] call failed. Contains a human-readable
     * description of the failure reason:
     *   - "unsupported_operators: ConvInteger" — model was quantized with ops
     *     not supported by the current ORT Android runtime. Re-export with
     *     QDQ format or use the FP32 model.
     *   - "model_missing" — asset file not found.
     *   - "oom" — model too large for available heap.
     *   - "unknown: <message>" — any other exception.
     *
     * Reset to null on a successful [load]. The UI / service can read this
     * to show a concrete error banner instead of silent blank STT output.
     */
    var loadError: String? = null
        private set

    /**
     * True when the requested language had no model file and Hindi was used
     * as a fallback. The UI should surface this to the user.
     */
    var isFallbackActive: Boolean = false
        private set

    /** The language that is actually loaded (may differ from requested). */
    var loadedLanguage: Language = Language.HINDI
        private set

    // id → decoded string (SentencePiece BPE tokens; ▁ prefix = word boundary)
    private val vocabMap = mutableMapOf<Long, String>()
    
    // Sherpa-ONNX fallback for non-Hindi languages
    private val sherpaStt = SherpaSttEngine(context)

    // ─── Language availability ────────────────────────────────────────────────

    /** Returns true when both model files for [language] exist in assets. */
    fun isLanguageAvailable(language: Language): Boolean {
        if (language != Language.HINDI) {
            val sherpaDir = "sherpa_stt"
            return assetExists(context, "$sherpaDir/tiny-decoder.int8.onnx") && 
                   assetExists(context, "$sherpaDir/tiny-encoder.int8.onnx") &&
                   assetExists(context, "$sherpaDir/tiny-tokens.txt")
        }
        val onnx  = "stt_${language.code}.onnx"
        val vocab = "vocab_${language.code}.txt"
        return assetExists(context, onnx) && assetExists(context, vocab)
    }

    // ─── Loading ──────────────────────────────────────────────────────────────

    /**
     * Load the STT model for [language].
     *
     * Falls back to [Language.HINDI] if model files are missing, setting
     * [isFallbackActive] = true.
     *
     * Logs a warning if the model file is larger than 60 MB (suggesting the
     * INT8 quantization step was skipped).
     *
     * @return the language that was actually loaded.
     */
    suspend fun load(language: Language): Language = withContext(Dispatchers.IO) {
        if (language != Language.HINDI) {
            val loaded = sherpaStt.load(language)
            loadedLanguage = loaded
            isFallbackActive = false
            loadError = if (sherpaStt.isLoaded) null else "sherpa_failed"
            return@withContext loaded
        }

        // Determine which language to actually load
        val targetLang = if (isLanguageAvailable(language)) {
            isFallbackActive = false
            language
        } else {
            if (language != Language.HINDI) {
                Log.w(TAG, "No STT model for ${language.code} — falling back to Hindi")
                isFallbackActive = true
            } else {
                isFallbackActive = false
            }
            Language.HINDI
        }

        if (currentLanguage == targetLang && session != null) {
            loadedLanguage = targetLang
            return@withContext targetLang
        }
        close()

        val assetName = "stt_${targetLang.code}.onnx"
        try {
            // ── Model size info ───────────────────────────────────────────────
            val assetFd = context.assets.openFd(assetName)
            val sizeMb = assetFd.length / 1_048_576L
            assetFd.close()
            if (sizeMb > 60) {
                // Running FP32 is fine for correctness; quantize later for perf.
                Log.i(TAG, "STT model $assetName is ${sizeMb}MB (FP32). " +
                        "For smaller size run: python scripts/quantize_models.py --lang ${targetLang.code}")
            }

            Log.d(TAG, "Loading STT model: $assetName (${sizeMb}MB) — please wait...")
            ortEnv = OrtEnvironment.getEnvironment()
            val modelBytes = context.assets.open(assetName).readBytes()
            val opts = OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(4)
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            }
            session = ortEnv!!.createSession(modelBytes, opts)
            currentLanguage = targetLang
            loadedLanguage  = targetLang
            loadError = null  // clear any previous failure

            // ── Input/output shape validation ─────────────────────────────────
            Log.d(TAG, "✅ STT model loaded successfully. Inputs:")
            session!!.inputInfo.forEach { (name, info) ->
                Log.d(TAG, "  '$name': ${info.info}")
            }
            Log.d(TAG, "   Outputs:")
            session!!.outputInfo.forEach { (name, info) ->
                Log.d(TAG, "  '$name': ${info.info}")
            }
        } catch (e: ai.onnxruntime.OrtException) {
            // ── Specific OrtException handling ────────────────────────────────
            val msg = e.message ?: ""
            val errorCode = when {
                // ConvInteger / QLinearConv / MatMulInteger → quantized model,
                // unsupported operators on this ORT Android build.
                msg.contains("NOT_IMPLEMENTED", ignoreCase = true) ||
                msg.contains("ConvInteger", ignoreCase = true) ||
                msg.contains("QLinearConv", ignoreCase = true) ||
                msg.contains("MatMulInteger", ignoreCase = true) ->
                    "unsupported_operators: model was quantized with ops not supported " +
                    "by this ORT build. Restore FP32 model or re-quantize using QDQ format."
                msg.contains("No space left", ignoreCase = true) ||
                msg.contains("OutOfMemory", ignoreCase = true) ->
                    "oom: model too large for available heap (${sizeMbOf(assetName)}MB). " +
                    "Quantize the model or increase heap in AndroidManifest."
                else -> "ort_error: $msg"
            }
            loadError = errorCode
            Log.e(TAG, "❌ STT model load FAILED ($assetName): $errorCode", e)
            Log.e(TAG, "   → STT will be disabled until model is fixed and reloaded.")
            loadedLanguage = targetLang
            return@withContext targetLang
        } catch (e: OutOfMemoryError) {
            val errorCode = "oom: JVM OOM loading ${assetName}. " +
                    "Model is ${sizeMbOf(assetName)}MB — increase Xmx or quantize."
            loadError = errorCode
            Log.e(TAG, "❌ STT model load FAILED (OOM): $errorCode")
            loadedLanguage = targetLang
            return@withContext targetLang
        } catch (e: Exception) {
            val errorCode = "unknown: ${e.message}"
            loadError = errorCode
            Log.e(TAG, "❌ STT model load FAILED ($assetName): $errorCode", e)
            loadedLanguage = targetLang
            return@withContext targetLang
        }

        // ── Load BPE vocabulary ───────────────────────────────────────────────
        // Format: one entry per line — "<token> <id>"
        // SentencePiece ▁ (U+2581) marks word boundary (maps to a leading space).
        // FIX: Explicitly specify UTF_8 charset — Android's platform default
        //      charset may not be UTF-8, causing Devanagari tokens to fail silently.
        vocabMap.clear()
        val vocabAsset = "vocab_${targetLang.code}.txt"
        try {
            context.assets.open(vocabAsset)
                .bufferedReader(Charsets.UTF_8)
                .forEachLine { raw ->
                    val line = raw.trim()
                    if (line.isEmpty()) return@forEachLine
                    val spaceIdx = line.lastIndexOf(' ')
                    if (spaceIdx < 0) return@forEachLine
                    val token = line.substring(0, spaceIdx)
                    val id = line.substring(spaceIdx + 1).toLongOrNull() ?: return@forEachLine
                    // ▁ (SentencePiece word-boundary marker) → space prefix
                    vocabMap[id] = token.replace("\u2581", " ")
                }
            Log.d(TAG, "Vocab loaded: ${vocabMap.size} tokens for ${targetLang.code}")
            if (vocabMap.isEmpty()) {
                Log.e(TAG, "Vocab file $vocabAsset was read but produced 0 tokens — " +
                        "check file encoding and format")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Vocab file not found for ${targetLang.code} ($vocabAsset): ${e.message}")
            Log.w(TAG, "STT will output raw token IDs instead of text")
        }

        targetLang
    }

    // ─── Inference ────────────────────────────────────────────────────────────

    /**
     * Transcribe raw 16 kHz mono PCM and return the decoded text.
     *
     * Steps:
     *   1. Compute 80-band log-mel spectrogram via [MelSpectrogram.extract]
     *   2. Run IndicConformer ONNX model → logprobs [1, T, 257]
     *   3. Greedy argmax per time step → token IDs
     *   4. CTC decode (collapse repeats, remove blank id=256)
     *   5. Vocabulary lookup → string
     *
     * Input shape: audio_signal [1, 80, nFrames], length [1] — mel frames.
     * This is confirmed by model inspection (NOT raw waveform).
     */
    suspend fun transcribe(audioBuffer: ShortArray): String = withContext(Dispatchers.Default) {
        if (loadedLanguage != Language.HINDI) {
            val t0 = System.currentTimeMillis()
            val text = sherpaStt.transcribe(audioBuffer)
            lastLatencyMs = System.currentTimeMillis() - t0
            return@withContext text
        }

        val sess = session ?: run {
            val reason = loadError ?: "load() not called yet"
            Log.e(TAG, "transcribe() skipped — STT engine not loaded. Reason: $reason")
            return@withContext ""
        }
        val env = ortEnv ?: return@withContext ""

        val t0 = System.currentTimeMillis()

        // ── Step 1: Mel spectrogram ───────────────────────────────────────────
        val (melFlat, nFrames) = MelSpectrogram.extract(audioBuffer)
        if (nFrames == 0) {
            Log.d(TAG, "transcribe(): audio too short (${audioBuffer.size} samples) — skipping")
            return@withContext ""
        }

        // ── Step 2: Build input tensors ───────────────────────────────────────
        // audio_signal: [1, 80, nFrames]  (mel spectrogram, NOT raw waveform)
        val audioTensor = OnnxTensor.createTensor(
            env,
            FloatBuffer.wrap(melFlat),
            longArrayOf(1L, MelSpectrogram.N_MELS.toLong(), nFrames.toLong())
        )
        // length: [1] — number of mel frames
        val lengthTensor = OnnxTensor.createTensor(env, longArrayOf(nFrames.toLong()))

        try {
            // ── Step 3: Run model ─────────────────────────────────────────────
            val results = sess.run(mapOf("audio_signal" to audioTensor, "length" to lengthTensor))
            try {
                // Output: logprobs [1, T_out, 257]  (output name: "logprobs")
                val rawValue = results[0].value
                val tokenIds = greedyArgmax(rawValue)

                // ── Debug token log ───────────────────────────────────────────
                if (debugTokenLog) {
                    val nonBlank = tokenIds.filter { it != CTC_BLANK_ID }
                    Log.d(TAG, "CTC raw token IDs (non-blank, ${nonBlank.size} of ${tokenIds.size}): " +
                            nonBlank.take(50).joinToString(", "))
                }

                val text = ctcDecode(tokenIds)
                lastLatencyMs = System.currentTimeMillis() - t0
                Log.d(TAG, "STT (${lastLatencyMs}ms, $nFrames frames): \"$text\"")
                text
            } finally {
                results.close()
            }
        } catch (e: Exception) {
            Log.e(TAG, "STT inference failed", e)
            ""
        } finally {
            // Always close input tensors even on inference exception
            audioTensor.close()
            lengthTensor.close()
        }
    }

    // ─── CTC decoding ────────────────────────────────────────────────────────

    /**
     * Greedy argmax over logprobs [1, T, 257].
     * ORT Java returns this as Array<Array<FloatArray>> = [batch][time][vocab].
     */
    private fun greedyArgmax(rawValue: Any?): LongArray {
        return when (rawValue) {
            is Array<*> -> {
                // [batch=1][T][257]
                @Suppress("UNCHECKED_CAST")
                val batchItem = (rawValue as? Array<Array<FloatArray>>)?.getOrNull(0)
                    ?: return LongArray(0)
                LongArray(batchItem.size) { t ->
                    val logits = batchItem[t]
                    var best = 0; var bestVal = Float.NEGATIVE_INFINITY
                    for (v in logits.indices) {
                        if (logits[v] > bestVal) { bestVal = logits[v]; best = v }
                    }
                    best.toLong()
                }
            }
            else -> {
                Log.e(TAG, "Unexpected logprobs type: ${rawValue?.javaClass?.name}")
                LongArray(0)
            }
        }
    }

    /**
     * CTC decode: collapse consecutive repeated tokens, remove blank (id=256).
     * Then map remaining ids through vocab to produce text.
     *
     * If vocabMap is empty (vocab file failed to load), falls back to showing
     * raw token IDs in [ID] format so the failure is visible, not silent.
     */
    private fun ctcDecode(ids: LongArray): String {
        val collapsed = mutableListOf<Long>()
        var prev = -1L
        for (id in ids) {
            if (id != prev) {
                if (id != CTC_BLANK_ID) collapsed.add(id)
                prev = id
            }
        }

        return if (vocabMap.isNotEmpty()) {
            val text = collapsed.mapNotNull { id ->
                val token = vocabMap[id]
                if (token == null) {
                    Log.v(TAG, "CTC: no vocab entry for token id $id")
                }
                token
            }.joinToString("").trim()
            text
        } else {
            // Fallback: show raw token IDs — makes failure visible for debugging
            Log.w(TAG, "ctcDecode(): vocabMap is empty — outputting raw IDs")
            collapsed.joinToString(" ") { "[$it]" }
        }
    }

    // ─── Lifecycle ────────────────────────────────────────────────────────────

    /** Returns the asset size in MB without throwing, used in error messages. */
    private fun sizeMbOf(assetName: String): Long = try {
        val fd = context.assets.openFd(assetName)
        val mb = fd.length / 1_048_576L
        fd.close(); mb
    } catch (_: Exception) { -1L }

    fun close() {
        session?.close()
        ortEnv?.close()
        session = null
        ortEnv = null
        currentLanguage = null
        vocabMap.clear()
        isFallbackActive = false
        sherpaStt.release()
        // Note: loadError is intentionally NOT cleared here — callers may read
        // it after close() to understand why a previous session failed.
    }
}
