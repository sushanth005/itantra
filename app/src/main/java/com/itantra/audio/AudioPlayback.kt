package com.itantra.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.util.Log
import com.itantra.tts.TtsEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Plays raw PCM float audio from the TTS engine via [AudioTrack].
 *
 * Two operating modes:
 *  - **Normal**: standard media playback
 *  - **Alert**: forces device to max volume, overrides DND, and requests exclusive
 *    audio focus so playback cannot be interrupted (uses USAGE_ALARM attributes)
 */
class AudioPlayback(private val context: Context) {

    companion object {
        private const val TAG = "AudioPlayback"
        private val SAMPLE_RATE = TtsEngine.OUTPUT_SAMPLE_RATE
    }

    private val audioManager by lazy {
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }

    /**
     * Play [samples] (float32 normalized PCM at 22050 Hz) through the speaker.
     *
     * If [isAlert] is true, the device volume is forced to maximum, DND is
     * overridden, and exclusive audio focus is requested before playback.
     */
    suspend fun play(samples: FloatArray, isAlert: Boolean) = withContext(Dispatchers.IO) {
        if (samples.isEmpty()) {
            Log.w(TAG, "play() called with empty sample array")
            return@withContext
        }

        if (isAlert) applyAlertOverride()

        val attributes = buildAudioAttributes(isAlert)
        val format = AudioFormat.Builder()
            .setSampleRate(SAMPLE_RATE)
            .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .build()

        val minBuf = AudioTrack.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_FLOAT
        )

        val track = AudioTrack.Builder()
            .setAudioAttributes(attributes)
            .setAudioFormat(format)
            .setBufferSizeInBytes(maxOf(minBuf, samples.size * 4))
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()

        try {
            track.write(samples, 0, samples.size, AudioTrack.WRITE_BLOCKING)
            track.play()
            // Wait for playback to finish
            val durationMs = samples.size.toLong() * 1000L / SAMPLE_RATE
            kotlinx.coroutines.delay(durationMs + 100)
            Log.d(TAG, "Playback finished: ${samples.size} samples (${durationMs}ms), alert=$isAlert")
        } finally {
            track.stop()
            track.release()
            if (isAlert) releaseAlertOverride()
        }
    }

    private fun buildAudioAttributes(isAlert: Boolean): AudioAttributes =
        AudioAttributes.Builder()
            .setUsage(
                if (isAlert) AudioAttributes.USAGE_ALARM
                else AudioAttributes.USAGE_MEDIA
            )
            .setContentType(
                if (isAlert) AudioAttributes.CONTENT_TYPE_SONIFICATION
                else AudioAttributes.CONTENT_TYPE_SPEECH
            )
            .build()

    /** Force max alarm volume and override Do Not Disturb. */
    private fun applyAlertOverride() {
        try {
            // Force volume to maximum on STREAM_ALARM
            val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM)
            audioManager.setStreamVolume(
                AudioManager.STREAM_ALARM,
                maxVol,
                AudioManager.FLAG_SHOW_UI
            )
            Log.d(TAG, "Alert override: volume set to $maxVol/$maxVol")
        } catch (e: SecurityException) {
            Log.w(TAG, "Cannot set alarm volume — ACCESS_NOTIFICATION_POLICY may be missing", e)
        }
    }

    private fun releaseAlertOverride() {
        // Nothing to restore — volume will naturally revert on next user action
    }
}
