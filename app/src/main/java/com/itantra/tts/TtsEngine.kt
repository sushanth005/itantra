package com.itantra.tts

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.util.Log
import com.itantra.model.Language
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.nio.LongBuffer

/**
 * On-device Text-to-Speech engine using the Piper VITS ONNX model.
 *
 * Pipeline:
 *   Indian language text (Devanagari / other Indic scripts)
 *   → textToPhonemeIds() — script-specific G2P → phoneme ID lookup in tts_{lang}.json
 *   → Piper ONNX inference → audio waveform [batch, 1, 1, T_samples]
 *   → Squeeze → FloatArray at 22050 Hz
 *
 * Asset: tts_{langCode}.onnx + tts_{langCode}.json (Piper voice config)
 * Output: 22050 Hz, mono, float32 PCM
 *
 * ONNX model inputs (verified):
 *   "input"          [batch, phonemes]  int64
 *   "input_lengths"  [batch]            int64
 *   "scales"         [3]                float32  → [noise_scale, length_scale, noise_w]
 *
 * G2P fix notes (v2):
 *   • INHERENT_A_ID changed from 14 (IPA 'a') to 59 (IPA 'ə'/schwa).
 *     espeak-ng Hindi produces schwa, not open /a/, for the inherent vowel.
 *   • Word-final schwa deletion implemented: bare consonants at word boundaries
 *     get no inherent vowel appended, matching espeak-ng Hindi behaviour.
 *     This fixes the "first character only" symptom caused by spurious final vowels.
 *   • Debug phoneme logging added — enable via debugPhonemeLog flag.
 *
 * Language availability:
 *   Only languages whose model files exist in assets are "available". When a
 *   requested language is unavailable, [load] falls back to Hindi and sets
 *   [isFallbackActive] = true so the caller can notify the user.
 */
class TtsEngine(private val context: Context) {

    companion object {
        private const val TAG = "TtsEngine"
        const val OUTPUT_SAMPLE_RATE = 22_050

        // Piper sentence frame token IDs (from tts_hi.json phoneme_id_map)
        private const val BOS_ID = 1L   // '^'
        private const val EOS_ID = 2L   // '$'
        private const val SPC_ID = 3L   // ' '

        // ── Devanagari G2P: inherent vowel ────────────────────────────────────
        //
        // FIX (v2): Changed from 14L ('a') to 59L ('ə'/schwa).
        //
        // Reason: The Piper Hindi model was trained on espeak-ng Hindi (hi) phoneme
        // sequences. espeak-ng produces the schwa /ə/ (IPA U+0259, phoneme_id_map
        // key 'ə' → [59]) for the inherent vowel in Hindi consonant syllables.
        // Using 14L ('a') caused wrong vowel quality on every open syllable, making
        // speech sound unnatural and truncated.
        //
        // Word-final schwa deletion: espeak-ng applies this Sanskrit-derived rule
        // automatically — a bare consonant at the end of a word receives NO inherent
        // vowel. The old code always appended INHERENT_A_ID, adding a spurious
        // final syllable that broke prosody for every Hindi word.
        //
        // Reference: tts_hi.json phoneme_id_map: 'ə' → [59], 'a' → [14].
        private const val INHERENT_SCHWA_ID = 59L  // ə — mid-word inherent vowel

        // ── Devanagari → eSpeak IPA phoneme ID tables ────────────────────────
        //
        // IDs are taken directly from tts_hi.json phoneme_id_map.
        // All mappings verified against espeak-ng Hindi (hi) output.
        //
        // Key corrections vs. previous version:
        //   • आ/ा  : [14,122] (aː) — correct, represents long /aː/
        //   • ज    : [17,107] (dʑ) — eSpeak produces palatal affricate
        //   • Dentals: include dental diacritic ̪ (ID 142)
        //   • झ    : [17,107,145] (dʑʰ)

        /** Independent vowels: Devanagari char → phoneme IDs (eSpeak verified) */
        private val VOWELS: Map<String, List<Long>> = mapOf(
            "अ"  to listOf(14L),                  // a
            "आ"  to listOf(14L, 122L),             // aː
            "इ"  to listOf(74L),                   // ɪ
            "ई"  to listOf(21L),                   // i
            "उ"  to listOf(100L),                  // ʊ
            "ऊ"  to listOf(33L),                   // u
            "ऋ"  to listOf(30L, 74L),              // rɪ
            "ए"  to listOf(18L),                   // e
            "ऐ"  to listOf(61L),                   // ɛ
            "ओ"  to listOf(27L),                   // o
            "औ"  to listOf(54L),                   // ɔ
            "ऑ"  to listOf(54L),                   // ɔ  (loan vowel)
            "अं" to listOf(14L, 26L),              // an̪ (anusvara on अ)
        )

        /**
         * Vowel signs (mātrā): replace the inherent vowel of the preceding consonant.
         * eSpeak verified.
         */
        private val MATRAS: Map<String, List<Long>> = mapOf(
            "ा"  to listOf(14L, 122L),             // aː
            "ि"  to listOf(74L),                   // ɪ
            "ी"  to listOf(21L),                   // i
            "ु"  to listOf(100L),                  // ʊ
            "ू"  to listOf(33L),                   // u
            "ृ"  to listOf(30L, 74L),              // rɪ
            "े"  to listOf(18L),                   // e
            "ै"  to listOf(61L),                   // ɛ
            "ो"  to listOf(27L),                   // o
            "ौ"  to listOf(54L),                   // ɔ
            "ॉ"  to listOf(54L),                   // ɔ
            "ँ"  to listOf(26L),                   // n̪ (chandrabindu on vowel)
            "ं"  to listOf(26L),                   // n̪ (anusvara on vowel)
        )

        /**
         * Consonants → IPA phoneme IDs (inherent vowel NOT included here;
         * it is added by the G2P logic, with word-final schwa deletion applied).
         *
         * eSpeak-ng Hindi verified:
         *   Dental stops (त/थ/द/ध/न) include dental diacritic ̪ (ID 142).
         *   Palatal affricate ज uses dʑ ([17,107]).
         *   Retroflexes use ʈ/ɖ/ɳ (IDs 98/56/83).
         */
        private val CONSONANTS: Map<String, List<Long>> = mapOf(
            // Velars
            "क"  to listOf(23L),                   // k
            "ख"  to listOf(23L, 145L),             // kʰ
            "ग"  to listOf(66L),                   // ɡ
            "घ"  to listOf(66L, 145L),             // ɡʰ
            "ङ"  to listOf(44L),                   // ŋ

            // Palatals
            "च"  to listOf(32L, 55L),              // tɕ
            "छ"  to listOf(32L, 55L, 145L),        // tɕʰ
            "ज"  to listOf(17L, 107L),             // dʑ
            "झ"  to listOf(17L, 107L, 145L),       // dʑʰ
            "ञ"  to listOf(82L),                   // ɲ

            // Retroflexes
            "ट"  to listOf(98L),                   // ʈ
            "ठ"  to listOf(98L, 145L),             // ʈʰ
            "ड"  to listOf(56L),                   // ɖ
            "ढ"  to listOf(56L, 145L),             // ɖʰ
            "ण"  to listOf(83L),                   // ɳ

            // Dentals — include dental diacritic ̪ (ID 142)
            "त"  to listOf(32L, 142L),             // t̪
            "थ"  to listOf(32L, 142L, 145L),       // t̪ʰ
            "द"  to listOf(17L, 142L),             // d̪
            "ध"  to listOf(17L, 142L, 145L),       // d̪ʰ
            "न"  to listOf(26L, 142L),             // n̪

            // Labials
            "प"  to listOf(28L),                   // p
            "फ"  to listOf(28L, 145L),             // pʰ
            "ब"  to listOf(15L),                   // b
            "भ"  to listOf(15L, 145L),             // bʰ
            "म"  to listOf(25L),                   // m

            // Approximants / liquids
            "य"  to listOf(22L),                   // j
            "र"  to listOf(30L),                   // r
            "ल"  to listOf(24L),                   // l
            "व"  to listOf(101L),                  // ʋ

            // Sibilants / fricatives
            "श"  to listOf(55L),                   // ɕ
            "ष"  to listOf(95L),                   // ʂ
            "स"  to listOf(31L),                   // s
            "ह"  to listOf(71L),                   // ɦ

            // Nukta (loanword) consonants — multi-codepoint String key
            "\u0915\u093C" to listOf(23L),          // क़ → k (q)
            "\u0916\u093C" to listOf(29L),          // ख़ → x
            "\u0917\u093C" to listOf(68L),          // ग़ → ɣ
            "\u091C\u093C" to listOf(38L),          // ज़ → z
            "\u0921\u093C" to listOf(91L),          // ड़ → ɽ
            "\u0922\u093C" to listOf(91L, 145L),    // ढ़ → ɽʰ
            "\u092B\u093C" to listOf(19L),          // फ़ → f
        )

        /** Halant (virama) — suppresses the inherent vowel */
        private const val HALANT       = '\u094D'
        /** Anusvara — nasal */
        private const val ANUSVARA     = '\u0902'
        /** Visarga */
        private const val VISARGA      = '\u0903'
        /** Chandrabindu */
        private const val CHANDRABINDU = '\u0901'
        /** Nukta combining char */
        private const val NUKTA        = '\u093C'

        /** Check whether an asset file exists without throwing. */
        private fun assetExists(context: Context, name: String): Boolean = try {
            context.assets.open(name).close(); true
        } catch (_: Exception) { false }
    }

    // Sherpa-ONNX fallback for non-Hindi languages
    private val sherpaTts = SherpaTtsEngine(context)

    // ── Debug flag ────────────────────────────────────────────────────────────
    /**
     * When true, every call to [synthesize] logs the full phoneme ID array to
     * Logcat at DEBUG level under the tag "TtsEngine".
     * Enable during testing; disable for production builds.
     */
    var debugPhonemeLog: Boolean = true

    private var ortEnv: OrtEnvironment? = null
    private var session: OrtSession? = null
    private var currentLanguage: Language? = null

    // Loaded from tts_{lang}.json: IPA symbol string → phoneme ID list
    private val phonemeIdMap = mutableMapOf<String, List<Long>>()

    var lastLatencyMs: Long = 0L
        private set
    var lastRtf: Float = 0f
        private set

    /** True when model is loaded and ready for inference. */
    val isLoaded: Boolean get() = session != null

    /**
     * True when the requested language had no model file and Hindi was used
     * as a fallback. The UI should surface this to the user.
     */
    var isFallbackActive: Boolean = false
        private set

    /**
     * The language that is actually loaded (may differ from the requested one
     * when [isFallbackActive] is true).
     */
    var loadedLanguage: Language = Language.HINDI
        private set

    // ─── Language availability ────────────────────────────────────────────────

    /** Returns true when both model files for [language] exist in assets. */
    fun isLanguageAvailable(language: Language): Boolean {
        if (language != Language.HINDI) {
            val sherpaDir = "sherpa_tts_${language.code}"
            return assetExists(context, "$sherpaDir/model.onnx") && 
                   assetExists(context, "$sherpaDir/tokens.txt")
        }
        val onnx = "tts_${language.code}.onnx"
        val json  = "tts_${language.code}.json"
        return assetExists(context, onnx) && assetExists(context, json)
    }

    // ─── Loading ──────────────────────────────────────────────────────────────

    /**
     * Load the TTS model for [language].
     *
     * If the model files for [language] are not found in assets, falls back to
     * [Language.HINDI] and sets [isFallbackActive] = true.
     *
     * Logs a warning if the model file is larger than 80 MB, suggesting
     * the quantization step may have been skipped.
     *
     * @return the language that was actually loaded.
     */
    suspend fun load(language: Language): Language = withContext(Dispatchers.IO) {
        if (language != Language.HINDI) {
            val loaded = sherpaTts.load(language)
            loadedLanguage = loaded
            isFallbackActive = false
            return@withContext loaded
        }

        // Determine which language to actually load
        val targetLang = if (isLanguageAvailable(language)) {
            isFallbackActive = false
            language
        } else {
            if (language != Language.HINDI) {
                Log.w(TAG, "No TTS model for ${language.code} — falling back to Hindi")
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

        val assetName = "tts_${targetLang.code}.onnx"
        try {
            // Warn if model appears unquantized (>80 MB suggests INT8 step was skipped)
            val assetFd = context.assets.openFd(assetName)
            val sizeMb = assetFd.length / 1_048_576L
            assetFd.close()
            if (sizeMb > 80) {
                Log.w(TAG, "TTS model $assetName is ${sizeMb}MB — INT8 quantization " +
                        "recommended (run scripts/quantize_models.py) for better perf on device")
            }

            Log.d(TAG, "Loading TTS model: $assetName (${sizeMb}MB)")
            ortEnv = OrtEnvironment.getEnvironment()
            val modelBytes = context.assets.open(assetName).readBytes()
            val opts = OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(4)
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            }
            session = ortEnv!!.createSession(modelBytes, opts)
            currentLanguage = targetLang
            loadedLanguage  = targetLang

            // Log exact input/output names and shapes for verification
            Log.d(TAG, "TTS model loaded. Inputs:")
            session!!.inputInfo.forEach { (name, info) ->
                Log.d(TAG, "  input '$name': ${info.info}")
            }
            Log.d(TAG, "TTS model loaded. Outputs:")
            session!!.outputInfo.forEach { (name, info) ->
                Log.d(TAG, "  output '$name': ${info.info}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load TTS model $assetName", e)
            loadedLanguage = targetLang
            return@withContext targetLang
        }

        // Load phoneme_id_map from tts_{lang}.json
        phonemeIdMap.clear()
        try {
            val json = context.assets.open("tts_${targetLang.code}.json")
                .bufferedReader(Charsets.UTF_8).readText()
            val root = JSONObject(json)
            val map = root.getJSONObject("phoneme_id_map")
            for (key in map.keys()) {
                val arr = map.getJSONArray(key)
                phonemeIdMap[key] = (0 until arr.length()).map { arr.getLong(it) }
            }
            Log.d(TAG, "Phoneme map loaded: ${phonemeIdMap.size} symbols")
        } catch (e: Exception) {
            Log.w(TAG, "Could not load phoneme map: ${e.message}")
        }

        targetLang
    }

    // ─── Inference ────────────────────────────────────────────────────────────

    suspend fun synthesize(text: String, language: Language): FloatArray =
        withContext(Dispatchers.Default) {
            if (loadedLanguage != Language.HINDI) {
                val t0 = System.currentTimeMillis()
                val audio = sherpaTts.synthesize(text)
                val synthMs = System.currentTimeMillis() - t0
                val audioDurationMs = audio.size.toLong() * 1000L / OUTPUT_SAMPLE_RATE
                lastLatencyMs = synthMs
                lastRtf = if (audioDurationMs > 0) synthMs.toFloat() / audioDurationMs else 0f
                return@withContext audio
            }

            val sess = session ?: run {
                Log.w(TAG, "synthesize() called before load()")
                return@withContext FloatArray(0)
            }
            val env = ortEnv ?: return@withContext FloatArray(0)
            val t0 = System.currentTimeMillis()

            try {
                val phonemeIds = textToPhonemeIds(text, loadedLanguage)

                // ── Debug phoneme log ────────────────────────────────────────
                if (debugPhonemeLog) {
                    Log.d(TAG, "G2P debug for \"$text\" (${phonemeIds.size} IDs): " +
                            phonemeIds.joinToString(", "))
                } else {
                    Log.d(TAG, "TTS: ${phonemeIds.size} phoneme IDs for \"$text\"")
                }

                if (phonemeIds.isEmpty() || phonemeIds.size < 3) {
                    // BOS + EOS minimum; something went wrong in G2P
                    Log.e(TAG, "G2P produced too few IDs (${phonemeIds.size}) for \"$text\" — aborting synthesis")
                    return@withContext FloatArray(0)
                }

                // Piper expects phoneme IDs to be interspersed with the pad token (ID 0)
                val interspersedIds = mutableListOf<Long>(0L)
                for (id in phonemeIds) {
                    interspersedIds.add(id)
                    interspersedIds.add(0L)
                }
                val finalIds = interspersedIds.toLongArray()

                val inputTensor = OnnxTensor.createTensor(
                    env,
                    LongBuffer.wrap(finalIds),
                    longArrayOf(1L, finalIds.size.toLong())
                )
                val inputLengths = OnnxTensor.createTensor(
                    env, longArrayOf(finalIds.size.toLong())
                )
                // Scales: [noise_scale=0.667, length_scale=1.0, noise_w=0.8]
                // Confirmed: model has a single "scales" input of shape [3].
                val scalesTensor = OnnxTensor.createTensor(
                    env, floatArrayOf(0.667f, 1.0f, 0.8f)
                )

                try {
                    // Input name is "input" (confirmed from model inspection — NOT "input_ids")
                    val inputs = mapOf(
                        "input"         to inputTensor,
                        "input_lengths" to inputLengths,
                        "scales"        to scalesTensor,
                    )

                    val results = sess.run(inputs)
                    try {
                        val waveform = extractWaveform(results[0].value)

                        val synthMs = System.currentTimeMillis() - t0
                        val audioDurationMs = waveform.size.toLong() * 1000L / OUTPUT_SAMPLE_RATE
                        lastLatencyMs = synthMs
                        lastRtf = if (audioDurationMs > 0) synthMs.toFloat() / audioDurationMs else 0f
                        Log.d(TAG, "TTS: ${waveform.size} samples in ${synthMs}ms " +
                                "RTF=${"%.3f".format(lastRtf)} audio=${audioDurationMs}ms")
                        waveform
                    } finally {
                        results.close()
                    }
                } finally {
                    // Always close input tensors even if inference throws
                    inputTensor.close()
                    inputLengths.close()
                    scalesTensor.close()
                }

            } catch (e: Exception) {
                Log.e(TAG, "TTS inference failed for \"$text\"", e)
                FloatArray(0)
            }
        }

    // ─── G2P: Devanagari → phoneme IDs ───────────────────────────────────────

    /**
     * Convert text to Piper phoneme ID sequence for the given [language].
     *
     * Currently only Devanagari-script languages (Hindi, Marathi) have a full
     * G2P implementation. For other scripts the runtime [phonemeIdMap] loaded
     * from the model JSON is used for character-by-character lookup.
     */
    private fun textToPhonemeIds(text: String, language: Language): LongArray {
        return when (language) {
            Language.HINDI, Language.MARATHI -> devanagariToPhonemeIds(text)
            else -> genericScriptToPhonemeIds(text)
        }
    }

    /**
     * Devanagari G2P — used for Hindi and Marathi.
     *
     * Rules:
     *  1. Consonant + mātrā → consonant phonemes + mātrā phonemes (no inherent vowel)
     *  2. Consonant + halant → consonant only (consonant cluster, no vowel)
     *  3. Consonant + anusvara → consonant + schwa + nasal (n̪ = 26)
     *  4. Bare consonant mid-word → consonant + INHERENT_SCHWA_ID (59, schwa)
     *  5. Bare consonant word-final → consonant only (schwa deletion rule)
     *  6. Nukta-extended consonants treated as single unit
     *  7. Independent vowels → their own phoneme IDs
     *  8. Space → SPC_ID (3)
     *
     * FIX (v2): Rules 4 and 5 replace the old rule that always appended
     * INHERENT_A_ID (14) regardless of word position. This was the root cause
     * of the "first character only / truncated speech" bug.
     */
    private fun devanagariToPhonemeIds(text: String): LongArray {
        val ids = mutableListOf<Long>()
        ids += BOS_ID

        val chars = text.toCharArray()
        val len = chars.size
        var i = 0

        while (i < len) {
            val ch = chars[i]
            val s = ch.toString()

            // ── Check nukta-extended consonant (2-char sequence) ──────────────
            val withNukta = if (i + 1 < len && chars[i + 1] == NUKTA) s + chars[i + 1] else null
            val consonantKey = when {
                withNukta != null && CONSONANTS.containsKey(withNukta) -> withNukta
                CONSONANTS.containsKey(s) -> s
                else -> null
            }

            when {
                consonantKey != null -> {
                    val consIds = CONSONANTS[consonantKey]!!
                    i += consonantKey.length  // advance past nukta if used

                    when {
                        // Case 1: Vowel mātrā follows → no inherent vowel
                        i < len && MATRAS.containsKey(chars[i].toString()) -> {
                            ids += consIds
                            ids += MATRAS[chars[i].toString()]!!
                            i++
                        }

                        // Case 3: Anusvara follows → inherent schwa + nasal
                        i < len && chars[i] == ANUSVARA -> {
                            ids += consIds
                            ids += INHERENT_SCHWA_ID
                            ids += 26L  // n̪ (nasal)
                            i++
                        }

                        // Case 2: Halant follows → consonant cluster, no vowel
                        i < len && chars[i] == HALANT -> {
                            ids += consIds
                            i++  // skip halant; next consonant follows directly
                        }

                        // Case 4 & 5: Bare consonant — apply schwa deletion rule
                        else -> {
                            ids += consIds
                            // Word-final = next char is space, string end, or punctuation
                            val isWordFinal = i >= len || chars[i] == ' ' || chars[i] == '\u00A0'
                            if (!isWordFinal) {
                                // Mid-word: add inherent schwa (espeak Hindi default)
                                ids += INHERENT_SCHWA_ID
                            }
                            // Word-final: no inherent vowel (schwa deletion)
                        }
                    }
                }

                VOWELS.containsKey(s) -> {
                    ids += VOWELS[s]!!
                    i++
                }

                ch == ANUSVARA    -> { ids += 26L; i++ }  // nasal n̪
                ch == VISARGA     -> { ids += 20L; i++ }  // h
                ch == CHANDRABINDU -> { ids += 26L; i++ }

                ch == ' ' || ch == '\u00A0' -> { ids += SPC_ID; i++ }

                ch == HALANT || ch == NUKTA -> i++  // orphan diacritics — skip

                else -> {
                    // Punctuation / numerals / Latin: look up in runtime JSON map
                    val mapped = phonemeIdMap[s]
                    if (mapped != null) {
                        ids += mapped
                    } else {
                        Log.v(TAG, "G2P: no mapping for char ${s.map { it.code.toString(16) }} — skipped")
                    }
                    i++
                }
            }
        }

        ids += EOS_ID
        return ids.toLongArray()
    }

    /**
     * Generic G2P for non-Devanagari scripts (Tamil, Kannada, Malayalam, Telugu,
     * Gujarati, Bengali, Odia, etc.).
     *
     * When a dedicated model is present (tts_{code}.onnx + tts_{code}.json), its
     * phoneme_id_map covers the native script characters. This function simply
     * looks each character up in that map and emits the corresponding IDs.
     *
     * For scripts where the phoneme_id_map may not cover every character, unknown
     * characters are logged at VERBOSE level and silently skipped.
     */
    private fun genericScriptToPhonemeIds(text: String): LongArray {
        val ids = mutableListOf<Long>()
        ids += BOS_ID

        for (ch in text) {
            if (ch == ' ' || ch == '\u00A0') {
                ids += SPC_ID
                continue
            }
            val s = ch.toString()
            val mapped = phonemeIdMap[s]
            if (mapped != null) {
                ids += mapped
            } else {
                Log.v(TAG, "G2P: no mapping for char ${s.map { it.code.toString(16) }} — skipped")
            }
        }

        ids += EOS_ID
        return ids.toLongArray()
    }

    private fun extractWaveform(value: Any?): FloatArray {
        return when (value) {
            is FloatArray -> value
            is Array<*> -> {
                val list = mutableListOf<Float>()
                fun flatten(arg: Any?) {
                    when (arg) {
                        is FloatArray -> arg.forEach { list.add(it) }
                        is Array<*> -> arg.forEach { flatten(it) }
                    }
                }
                flatten(value)
                list.toFloatArray()
            }
            else -> {
                Log.e(TAG, "Unexpected waveform type: ${value?.javaClass?.name}")
                FloatArray(0)
            }
        }
    }

    // ─── Lifecycle ────────────────────────────────────────────────────────────

    fun close() {
        session?.close()
        ortEnv?.close()
        session = null
        ortEnv = null
        currentLanguage = null
        phonemeIdMap.clear()
        isFallbackActive = false
        sherpaTts.release()
    }
}
