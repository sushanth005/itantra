package com.itantra.audio

import kotlin.math.*

/**
 * Computes 80-band log-mel spectrogram matching NeMo IndicConformer's preprocessing.
 *
 * Parameters (must match the model's training config):
 *   sample_rate = 16000 Hz
 *   n_fft       = 512
 *   hop_length  = 160   (10 ms)
 *   win_length  = 400   (25 ms)
 *   n_mels      = 80
 *   f_min       = 0 Hz
 *   f_max       = 8000 Hz
 *   normalize   = "per_feature"  (zero-mean, unit-variance per mel band)
 *   log_offset  = 2^-24 (added before log to avoid log(0))
 */
object MelSpectrogram {

    const val N_MELS = 80

    private const val SAMPLE_RATE = 16_000
    private const val N_FFT = 512              // FFT size (power of 2)
    private const val HOP_LENGTH = 160         // 10 ms stride
    private const val WIN_LENGTH = 400         // 25 ms window
    private const val N_FFT_BINS = N_FFT / 2 + 1  // 257
    private const val F_MIN = 0.0
    private const val F_MAX = 8000.0
    private const val LOG_GUARD = 5.960464e-8f  // 2^-24
    private const val NORM_EPS = 1e-5f
    private const val PRE_EMPHASIS = 0.97f

    // ── Pre-computed Hann window ──────────────────────────────────────────────
    private val HANN = FloatArray(WIN_LENGTH) { n ->
        (0.5 * (1.0 - cos(2.0 * PI * n / WIN_LENGTH))).toFloat()
    }

    // ── Pre-computed mel filterbank [N_MELS × N_FFT_BINS] ────────────────────
    private val MEL_FB: Array<FloatArray> = buildFilterbank()

    private fun hzToMel(hz: Double) = 2595.0 * log10(1.0 + hz / 700.0)
    private fun melToHz(mel: Double) = 700.0 * (10.0.pow(mel / 2595.0) - 1.0)

    private fun buildFilterbank(): Array<FloatArray> {
        val melMin = hzToMel(F_MIN)
        val melMax = hzToMel(F_MAX)
        // N_MELS + 2 centre points equally spaced in mel scale
        val melPts = DoubleArray(N_MELS + 2) { i ->
            melToHz(melMin + i * (melMax - melMin) / (N_MELS + 1))
        }
        // Convert Hz centre points to FFT bin indices
        val bins = IntArray(N_MELS + 2) { i ->
            floor((N_FFT + 1) * melPts[i] / SAMPLE_RATE).toInt().coerceIn(0, N_FFT_BINS - 1)
        }
        return Array(N_MELS) { m ->
            FloatArray(N_FFT_BINS) { k ->
                when {
                    k in bins[m] until bins[m + 1] && bins[m + 1] > bins[m] ->
                        (k - bins[m]).toFloat() / (bins[m + 1] - bins[m]).toFloat()
                    k in bins[m + 1]..bins[m + 2] && bins[m + 2] > bins[m + 1] ->
                        (bins[m + 2] - k).toFloat() / (bins[m + 2] - bins[m + 1]).toFloat()
                    else -> 0f
                }
            }
        }
    }

    // ── Cooley-Tukey radix-2 in-place FFT ────────────────────────────────────

    private fun fft(re: DoubleArray, im: DoubleArray) {
        val n = re.size
        // Bit-reversal permutation
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) {
                re[i] = re[j].also { re[j] = re[i] }
                im[i] = im[j].also { im[j] = im[i] }
            }
        }
        // Butterfly passes
        var len = 2
        while (len <= n) {
            val ang = -2.0 * PI / len
            val wRe = cos(ang); val wIm = sin(ang)
            var i = 0
            while (i < n) {
                var curRe = 1.0; var curIm = 0.0
                for (k in 0 until len / 2) {
                    val uRe = re[i + k];    val uIm = im[i + k]
                    val vRe = re[i + k + len / 2] * curRe - im[i + k + len / 2] * curIm
                    val vIm = re[i + k + len / 2] * curIm + im[i + k + len / 2] * curRe
                    re[i + k] = uRe + vRe;  im[i + k] = uIm + vIm
                    re[i + k + len / 2] = uRe - vRe; im[i + k + len / 2] = uIm - vIm
                    val newRe = curRe * wRe - curIm * wIm
                    curIm = curRe * wIm + curIm * wRe
                    curRe = newRe
                }
                i += len
            }
            len *= 2
        }
    }

    /**
     * Extract log-mel spectrogram from raw 16 kHz PCM audio.
     *
     * @param audio  Raw PCM ShortArray from AudioIngestion (16 kHz mono)
     * @return [flat, nFrames] where flat is FloatArray of size N_MELS × nFrames
     *         stored row-major (mel band first). Pass as tensor [1, 80, nFrames].
     */
    fun extract(audio: ShortArray): Pair<FloatArray, Int> {
        // BUG FIX: Return nFrames=0 so SttEngine.transcribe() skips inference.
        // Previously returned nFrames=1 with a zero-filled tensor, causing the model
        // to produce garbage output for any sub-25ms segment.
        if (audio.size < WIN_LENGTH) return Pair(FloatArray(0), 0)

        // ── Pre-emphasis ──────────────────────────────────────────────────────
        val sig = FloatArray(audio.size)
        sig[0] = audio[0] / 32768f
        for (i in 1 until audio.size) {
            sig[i] = audio[i] / 32768f - PRE_EMPHASIS * (audio[i - 1] / 32768f)
        }

        val nFrames = 1 + (sig.size - WIN_LENGTH) / HOP_LENGTH
        val mel = Array(N_MELS) { FloatArray(nFrames) }

        val re = DoubleArray(N_FFT)
        val im = DoubleArray(N_FFT)

        for (frame in 0 until nFrames) {
            val start = frame * HOP_LENGTH
            re.fill(0.0); im.fill(0.0)

            for (i in 0 until WIN_LENGTH) {
                val idx = start + i
                re[i] = if (idx < sig.size) (sig[idx] * HANN[i]).toDouble() else 0.0
            }

            fft(re, im)

            // Power spectrum magnitude² for bins 0..N_FFT_BINS-1
            // Apply mel filterbank and log
            for (m in 0 until N_MELS) {
                var energy = 0.0
                val fb = MEL_FB[m]
                for (k in 0 until N_FFT_BINS) {
                    val mag2 = re[k] * re[k] + im[k] * im[k]
                    energy += fb[k] * mag2
                }
                mel[m][frame] = ln(energy.toFloat().coerceAtLeast(LOG_GUARD).toDouble()).toFloat()
            }
        }

        // ── Per-feature normalization (zero mean, unit std) ───────────────────
        for (m in 0 until N_MELS) {
            var mean = 0.0; var varAcc = 0.0
            for (t in 0 until nFrames) mean += mel[m][t]
            mean /= nFrames
            for (t in 0 until nFrames) { val d = mel[m][t] - mean; varAcc += d * d }
            val std = sqrt(varAcc / nFrames).toFloat() + NORM_EPS
            for (t in 0 until nFrames) mel[m][t] = ((mel[m][t] - mean) / std).toFloat()
        }

        // ── Flatten [N_MELS × nFrames] → row-major FloatArray ────────────────
        val flat = FloatArray(N_MELS * nFrames)
        for (m in 0 until N_MELS) {
            System.arraycopy(mel[m], 0, flat, m * nFrames, nFrames)
        }
        return Pair(flat, nFrames)
    }
}
