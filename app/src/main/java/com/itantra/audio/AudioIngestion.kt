package com.itantra.audio

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive

/**
 * Wraps [AudioRecord] and exposes a cold [Flow] of 30ms PCM frames.
 *
 * Specs:
 *  - Sample rate: 16,000 Hz (required by IndicConformer and Silero VAD)
 *  - Channel: MONO
 *  - Encoding: PCM_16BIT (signed 16-bit little-endian)
 *  - Frame size: 480 samples = 30ms at 16kHz
 */
class AudioIngestion {

    companion object {
        private const val TAG = "AudioIngestion"
        const val SAMPLE_RATE = 16_000                   // Hz
        const val FRAME_SIZE_SAMPLES = 480               // 30ms at 16kHz
        private val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        private val MIN_BUFFER = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT
        )
        // Use 4× minimum for headroom
        private val BUFFER_SIZE = maxOf(MIN_BUFFER * 4, FRAME_SIZE_SAMPLES * 2 * 4)
    }

    /**
     * Emits 30ms [ShortArray] frames captured from the microphone.
     * Collection runs on [Dispatchers.IO]. Cancel the collecting coroutine to stop recording.
     */
    fun frameFlow(): Flow<ShortArray> = flow {
        val recorder = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            SAMPLE_RATE,
            CHANNEL_CONFIG,
            AUDIO_FORMAT,
            BUFFER_SIZE,
        )

        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord failed to initialize — check RECORD_AUDIO permission")
            recorder.release()
            return@flow
        }

        recorder.startRecording()
        Log.d(TAG, "AudioRecord started: $SAMPLE_RATE Hz, frame=$FRAME_SIZE_SAMPLES samples")

        try {
            val frame = ShortArray(FRAME_SIZE_SAMPLES)
            while (currentCoroutineContext().isActive) {
                val read = recorder.read(frame, 0, FRAME_SIZE_SAMPLES, AudioRecord.READ_BLOCKING)
                if (read > 0) {
                    emit(frame.copyOf(read))
                } else {
                    Log.w(TAG, "AudioRecord.read returned $read")
                }
            }
        } finally {
            recorder.stop()
            recorder.release()
            Log.d(TAG, "AudioRecord stopped and released")
        }
    }.flowOn(Dispatchers.IO)
}
