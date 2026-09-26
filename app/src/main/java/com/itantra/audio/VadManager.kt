package com.itantra.audio

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import java.nio.FloatBuffer

/**
 * Voice Activity Detection using the Silero VAD ONNX model.
 *
 * Accepts a [Flow] of 30ms PCM frames from [AudioIngestion] and emits
 * complete speech segments (concatenated frames) when speech ends.
 *
 * State machine:
 *   SILENCE → SPEAKING  (when VAD probability > START_THRESHOLD)
 *   SPEAKING → END_OF_UTTERANCE  (when silence duration > SILENCE_TIMEOUT_MS)
 *
 * Model asset: assets/vad_silero.onnx (~2MB)
 */
class VadManager(private val context: Context) {

    companion object {
        private const val TAG = "VadManager"
        private const val MODEL_ASSET = "vad_silero.onnx"
        private const val SAMPLE_RATE = AudioIngestion.SAMPLE_RATE
        private const val START_THRESHOLD = 0.5f
        private const val END_THRESHOLD = 0.35f
        private const val SILENCE_TIMEOUT_MS = 500
        private const val FRAME_DURATION_MS = 30 // 480 samples at 16kHz
        private const val SILENCE_FRAMES_NEEDED = SILENCE_TIMEOUT_MS / FRAME_DURATION_MS
    }

    private var ortEnv: OrtEnvironment? = null
    private var session: OrtSession? = null

    // Silero stateful LSTM hidden/cell states (2×1×64 float32 each)
    private var h = Array(2) { Array(1) { FloatArray(64) } }
    private var c = Array(2) { Array(1) { FloatArray(64) } }

    /** Load the Silero VAD ONNX model from assets. Call once on worker thread. */
    suspend fun load() = withContext(Dispatchers.IO) {
        try {
            ortEnv = OrtEnvironment.getEnvironment()
            val modelBytes = context.assets.open(MODEL_ASSET).readBytes()
            val opts = OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(1) // VAD needs only 1 thread
            }
            session = ortEnv!!.createSession(modelBytes, opts)
            Log.d(TAG, "Silero VAD loaded: ${session!!.inputNames}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load VAD model", e)
        }
    }

    /**
     * Processes a [Flow] of raw 30ms PCM frames and emits complete speech utterances
     * as [ShortArray] buffers ready for the STT engine.
     */
    fun speechSegmentFlow(frames: Flow<ShortArray>): Flow<ShortArray> = flow {
        val speechBuffer = mutableListOf<Short>()
        var silenceFrameCount = 0
        var isSpeaking = false

        frames.collect { frame ->
            val prob = runVad(frame)

            when {
                prob >= START_THRESHOLD && !isSpeaking -> {
                    isSpeaking = true
                    silenceFrameCount = 0
                    Log.v(TAG, "Speech started (prob=${"%.2f".format(prob)})")
                }
                isSpeaking -> {
                    if (prob >= END_THRESHOLD) {
                        silenceFrameCount = 0
                    } else {
                        silenceFrameCount++
                    }
                    // Always accumulate while in speaking state
                    speechBuffer.addAll(frame.toList())

                    if (silenceFrameCount >= SILENCE_FRAMES_NEEDED) {
                        // End of utterance — emit the collected segment
                        val segment = speechBuffer.toShortArray()
                        Log.d(TAG, "Utterance ended: ${segment.size} samples (${segment.size / SAMPLE_RATE * 1000}ms)")
                        emit(segment)
                        speechBuffer.clear()
                        isSpeaking = false
                        silenceFrameCount = 0
                        resetState()
                    }
                }
            }
        }
    }

    /**
     * Run a single 30ms frame through the Silero VAD model.
     * Returns the speech probability [0.0, 1.0].
     *
     * Silero VAD v4 ONNX input names: "input", "sr", "h0", "c0"
     * Output names:                   "output", "hn", "cn"
     * (Bug fix: was "h"/"c" → "h0"/"c0" and reading wrong output indices)
     */
    private fun runVad(frame: ShortArray): Float {
        val sess = session ?: return 0f
        val env = ortEnv ?: return 0f

        // Convert Short PCM → Float32 normalized [-1, 1]
        val floatFrame = FloatArray(frame.size) { frame[it] / 32768f }

        // BUG FIX: Silero v4 uses "h0"/"c0" for state inputs, not "h"/"c".
        // Using wrong names caused OrtException: Unknown input name, breaking Phone Mode entirely.
        val audioTensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(floatFrame), longArrayOf(1, floatFrame.size.toLong()))
        val srTensor    = OnnxTensor.createTensor(env, longArrayOf(SAMPLE_RATE.toLong()))
        val hTensor     = OnnxTensor.createTensor(env, h)
        val cTensor     = OnnxTensor.createTensor(env, c)

        try {
            val inputs = mapOf("input" to audioTensor, "sr" to srTensor, "h0" to hTensor, "c0" to cTensor)
            val results = sess.run(inputs)
            try {
                // output[0] = "output" (speech prob), output[1] = "hn", output[2] = "cn"
                val prob = (results[0].value as Array<*>)[0] as FloatArray
                // Update stateful LSTM states from corrected output names
                @Suppress("UNCHECKED_CAST")
                h = results[1].value as Array<Array<FloatArray>>
                @Suppress("UNCHECKED_CAST")
                c = results[2].value as Array<Array<FloatArray>>
                return prob[0]
            } finally {
                results.close()
            }
        } finally {
            // BUG FIX: Always close input tensors even if inference throws,
            // preventing native heap OOM from hundreds of leaked tensors per second.
            audioTensor.close()
            srTensor.close()
            hTensor.close()
            cTensor.close()
        }
    }

    private fun resetState() {
        h = Array(2) { Array(1) { FloatArray(64) } }
        c = Array(2) { Array(1) { FloatArray(64) } }
    }

    fun close() {
        session?.close()
        ortEnv?.close()
    }
}
