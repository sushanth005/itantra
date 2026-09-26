package com.itantra.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.itantra.MainActivity
import com.itantra.R
import com.itantra.audio.AudioIngestion
import com.itantra.audio.AudioPlayback
import com.itantra.audio.VadManager
import com.itantra.model.AppMode
import com.itantra.model.DeviceRole
import com.itantra.model.Language
import com.itantra.network.BluetoothTransceiver
import com.itantra.network.PayloadSerializer
import com.itantra.stt.SttEngine
import com.itantra.tts.TtsEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Foreground service that owns the full transceiver pipeline.
 *
 * Sender: AudioIngestion → VAD/PTT → SttEngine → BluetoothTransceiver
 * Receiver: BluetoothTransceiver → PayloadSerializer → TtsEngine → AudioPlayback
 */
class TransceiverService : Service() {

    companion object {
        private const val TAG = "TransceiverService"
        private const val CHANNEL_ID = "itantra_transceiver"
        private const val NOTIF_ID = 1001

        const val ACTION_START = "com.itantra.START"
        const val ACTION_STOP = "com.itantra.STOP"
        const val ACTION_PTT_DOWN = "com.itantra.PTT_DOWN"
        const val ACTION_PTT_UP = "com.itantra.PTT_UP"
        const val ACTION_REPLAY = "com.itantra.REPLAY"

        const val EXTRA_ROLE = "role"
        const val EXTRA_LANGUAGE = "language"
        const val EXTRA_MODE = "mode"
        const val EXTRA_IS_ALERT = "is_alert"
        const val EXTRA_REMOTE_DEVICE_ADDRESS = "remote_device_address"

        const val BROADCAST_TRANSCRIPT = "com.itantra.TRANSCRIPT"
        const val EXTRA_TEXT = "text"
        const val EXTRA_DIRECTION = "direction"

        const val BROADCAST_DIAGNOSTICS = "com.itantra.DIAGNOSTICS"
        const val BROADCAST_CONNECTION_STATE = "com.itantra.CONNECTION_STATE"
        const val EXTRA_CONNECTION_STATE = "state"

        // Receiver-specific broadcasts
        const val BROADCAST_TTS_STATUS = "com.itantra.TTS_STATUS"
        const val EXTRA_TTS_READY = "tts_ready"
        const val EXTRA_TTS_LAST_TEXT = "tts_last_text"

        // Language fallback broadcast (sent when Hindi replaces selected language)
        const val BROADCAST_LANG_FALLBACK = "com.itantra.LANG_FALLBACK"
        const val EXTRA_FALLBACK_REQUESTED = "fallback_requested"   // language code requested
        const val EXTRA_FALLBACK_ACTUAL    = "fallback_actual"      // language code actually used

        // Pipeline state broadcast — sent at each stage transition so the UI
        // can show live progress: IDLE → LISTENING → PROCESSING_STT → SENDING
        //                    or: RECEIVING → PROCESSING_TTS → PLAYING → IDLE
        const val BROADCAST_PIPELINE_STATE = "com.itantra.PIPELINE_STATE"
        const val EXTRA_PIPELINE_STATE     = "pipeline_state"
        const val STATE_IDLE              = "IDLE"
        const val STATE_LISTENING         = "LISTENING"
        const val STATE_PROCESSING_STT    = "PROCESSING_STT"
        const val STATE_SENDING           = "SENDING"
        const val STATE_RECEIVING         = "RECEIVING"
        const val STATE_PROCESSING_TTS    = "PROCESSING_TTS"
        const val STATE_PLAYING           = "PLAYING"

        // STT model load error — sent when SttEngine.load() fails so the UI
        // can show a concrete banner instead of silent blank transcriptions.
        const val BROADCAST_STT_LOAD_ERROR  = "com.itantra.STT_LOAD_ERROR"
        const val EXTRA_STT_LOAD_ERROR_MSG  = "stt_load_error_msg"

        // Minimum PTT buffer: 100ms of audio at 16kHz
        private const val MIN_PTT_SAMPLES = 16_000 / 10
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var pipelineJob: Job? = null

    private lateinit var audioIngestion: AudioIngestion
    private lateinit var vadManager: VadManager
    private lateinit var sttEngine: SttEngine
    private lateinit var ttsEngine: TtsEngine
    private lateinit var audioPlayback: AudioPlayback
    private lateinit var btTransceiver: BluetoothTransceiver
    private lateinit var btAdapter: BluetoothAdapter

    private var currentRole = DeviceRole.SENDER
    private var currentLanguage = Language.HINDI
    private var currentMode = AppMode.PUSH_TO_TALK
    private var isAlertFlagSet = false
    private var remoteDeviceAddress: String? = null

    private var isPipelineActive = false
    private val isPttActive = AtomicBoolean(false)
    private val pttBuffer = mutableListOf<Short>()
    private val pttBufferLock = Any()

    // Last synthesised PCM buffer for Replay Voice feature
    @Volatile private var lastSynthesisedPcm: FloatArray = FloatArray(0)
    @Volatile private var lastSynthesisedText: String = ""

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "TransceiverService created")
        createNotificationChannel()
        // Call startForeground ASAP in onCreate so Android 12+ never kills us
        // before onStartCommand even fires. Use a generic "starting" notification.
        startForeground(NOTIF_ID, buildStartingNotification())
        audioIngestion = AudioIngestion()
        vadManager = VadManager(this)
        sttEngine = SttEngine(this)
        ttsEngine = TtsEngine(this)
        audioPlayback = AudioPlayback(this)
        val btManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        btAdapter = btManager.adapter
        btTransceiver = BluetoothTransceiver(btAdapter)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                @Suppress("DEPRECATION")
                val role = intent.getSerializableExtra(EXTRA_ROLE) as? DeviceRole ?: DeviceRole.SENDER
                @Suppress("DEPRECATION")
                val lang = intent.getSerializableExtra(EXTRA_LANGUAGE) as? Language ?: Language.HINDI
                @Suppress("DEPRECATION")
                val mode = intent.getSerializableExtra(EXTRA_MODE) as? AppMode ?: AppMode.PUSH_TO_TALK
                remoteDeviceAddress = intent.getStringExtra(EXTRA_REMOTE_DEVICE_ADDRESS)
                startSession(role, lang, mode)
            }
            ACTION_STOP -> stopSession()
            ACTION_PTT_DOWN -> {
                isAlertFlagSet = intent.getBooleanExtra(EXTRA_IS_ALERT, false)
                synchronized(pttBufferLock) { pttBuffer.clear() }
                isPttActive.set(true)
                Log.d(TAG, "PTT DOWN")
            }
            ACTION_PTT_UP -> {
                isPttActive.set(false)
                Log.d(TAG, "PTT UP — processing buffer")
                val segment = synchronized(pttBufferLock) { pttBuffer.toShortArray().also { pttBuffer.clear() } }
                if (segment.size >= MIN_PTT_SAMPLES) {
                    serviceScope.launch { processPttSegment(segment) }
                } else {
                    Log.d(TAG, "PTT segment too short (${segment.size} samples) — ignoring")
                }
            }
            ACTION_REPLAY -> {
                val pcm = lastSynthesisedPcm
                if (pcm.isNotEmpty()) {
                    serviceScope.launch { audioPlayback.play(pcm, false) }
                }
            }
        }
        return START_STICKY
    }

    private fun startSession(role: DeviceRole, language: Language, mode: AppMode) {
        currentRole = role; currentLanguage = language; currentMode = mode
        isPipelineActive = true
        // Update notification content now that we know the role/language.
        // startForeground() was already called in onCreate(); this just refreshes it.
        try { startForeground(NOTIF_ID, buildNotification(role, language)) } catch (_: Exception) {}
        pipelineJob?.cancel()
        pipelineJob = serviceScope.launch {
            when (role) {
                DeviceRole.SENDER -> {
                    vadManager.load()
                    val loadedLang = sttEngine.load(language)
                    if (sttEngine.isFallbackActive) {
                        broadcastLangFallback(requested = language.code, actual = loadedLang.code)
                    }
                    // Surface any model-load failure to the UI immediately
                    sttEngine.loadError?.let { err ->
                        Log.e(TAG, "STT model failed to load: $err")
                        broadcastSttLoadError(err)
                    }
                    startSenderPipeline()
                }
                DeviceRole.RECEIVER -> {
                    val loadedLang = ttsEngine.load(language)
                    if (ttsEngine.isFallbackActive) {
                        broadcastLangFallback(requested = language.code, actual = loadedLang.code)
                    }
                    broadcastTtsStatus(ready = true, lastText = "")
                    startReceiverPipeline()
                }
            }
        }
    }

    // ─── Sender pipeline ──────────────────────────────────────────────────────

    private suspend fun startSenderPipeline() {
        Log.d(TAG, "Starting sender pipeline (mode=$currentMode)")
        val address = remoteDeviceAddress
        if (address == null) { Log.e(TAG, "Sender: no remote address"); broadcastConnectionState("failed"); return }
        val remoteDevice: BluetoothDevice = try {
            btAdapter.getRemoteDevice(address)
        } catch (e: IllegalArgumentException) { Log.e(TAG, "Invalid BT address: $address"); broadcastConnectionState("failed"); return }

        broadcastConnectionState("connecting")
        if (!btTransceiver.connect(remoteDevice)) {
            Log.e(TAG, "Sender: failed to connect")
            broadcastConnectionState("failed")
            return
        }
        broadcastConnectionState("connected")
        Log.d(TAG, "Sender: BT connected")

        val frameFlow = audioIngestion.frameFlow()
        broadcastPipelineState(STATE_LISTENING)
        when (currentMode) {
            AppMode.PUSH_TO_TALK -> {
                frameFlow.onEach { frame ->
                    if (isPttActive.get()) { synchronized(pttBufferLock) { pttBuffer.addAll(frame.toList()) } }
                }.launchIn(serviceScope)
            }
            AppMode.PHONE_MODE -> {
                vadManager.speechSegmentFlow(frameFlow)
                    .onEach { segment -> sendTranscription(segment) }
                    .launchIn(serviceScope)
            }
        }
    }

    private suspend fun processPttSegment(segment: ShortArray) = sendTranscription(segment)

    private suspend fun sendTranscription(pcm: ShortArray) {
        broadcastPipelineState(STATE_PROCESSING_STT)
        val t0Stt = System.currentTimeMillis()
        val text = sttEngine.transcribe(pcm)
        val sttMs = System.currentTimeMillis() - t0Stt
        if (text.isBlank()) {
            Log.d(TAG, "STT blank — skip (${sttMs}ms)")
            broadcastPipelineState(STATE_LISTENING)
            return
        }
        Log.d(TAG, "STT: \"$text\" (${sttEngine.lastLatencyMs}ms)")
        broadcastTranscript(text, "sent")

        broadcastPipelineState(STATE_SENDING)
        val t0Send = System.currentTimeMillis()
        val frame = PayloadSerializer.pack(text, currentLanguage.code, isAlertFlagSet)
        btTransceiver.send(frame)
        val sendMs = System.currentTimeMillis() - t0Send
        Log.d(TAG, "BT send: ${frame.size} bytes in ${sendMs}ms")

        broadcastDiagnostics(sttLatency = sttEngine.lastLatencyMs, payloadBytes = frame.size)
        broadcastPipelineState(STATE_LISTENING)
    }

    // ─── Receiver pipeline ────────────────────────────────────────────────────

    private suspend fun startReceiverPipeline() {
        Log.d(TAG, "Starting receiver pipeline")
        broadcastConnectionState("connecting")

        btTransceiver.listenFlow(
            onConnected = { deviceName ->
                // FIX: Broadcast "connected" immediately on socket accept
                Log.d(TAG, "Receiver: connected to $deviceName")
                broadcastConnectionState("connected")
            }
        ).onEach { payloadBytes ->
            broadcastPipelineState(STATE_RECEIVING)
            val message = PayloadSerializer.unpack(payloadBytes)
            Log.d(TAG, "Received: \"${message.text}\" lang=${message.langCode} alert=${message.isAlert}")
            val e2eLatency = System.currentTimeMillis() - message.senderTimestampMs
            broadcastTranscript(message.text, "received")

            val lang = Language.fromCode(message.langCode)
            if (lang != currentLanguage) {
                currentLanguage = lang
                val loadedLang = ttsEngine.load(lang)
                if (ttsEngine.isFallbackActive) {
                    broadcastLangFallback(requested = lang.code, actual = loadedLang.code)
                }
            }

            broadcastPipelineState(STATE_PROCESSING_TTS)
            val t0Tts = System.currentTimeMillis()
            val pcm = ttsEngine.synthesize(message.text, lang)
            val ttsMs = System.currentTimeMillis() - t0Tts
            Log.d(TAG, "TTS synthesis: ${ttsMs}ms for \"${message.text}\"")
            lastSynthesisedPcm = pcm
            lastSynthesisedText = message.text
            broadcastTtsStatus(ready = true, lastText = message.text)

            if (pcm.isNotEmpty()) {
                broadcastPipelineState(STATE_PLAYING)
                audioPlayback.play(pcm, message.isAlert)
            } else {
                Log.e(TAG, "TTS produced empty audio for \"${message.text}\" — skipping playback")
            }
            broadcastDiagnostics(ttsLatency = ttsEngine.lastLatencyMs, rtf = ttsEngine.lastRtf, e2eLatencyMs = e2eLatency)
            broadcastPipelineState(STATE_IDLE)
        }.launchIn(serviceScope)
    }

    // ─── Broadcasts ───────────────────────────────────────────────────────────

    private fun broadcastTranscript(text: String, direction: String) =
        sendBroadcast(Intent(BROADCAST_TRANSCRIPT).apply {
            `package` = packageName; putExtra(EXTRA_TEXT, text); putExtra(EXTRA_DIRECTION, direction)
        })

    private fun broadcastConnectionState(state: String) {
        Log.d(TAG, "Connection state -> $state")
        sendBroadcast(Intent(BROADCAST_CONNECTION_STATE).apply {
            `package` = packageName; putExtra(EXTRA_CONNECTION_STATE, state)
        })
    }

    private fun broadcastTtsStatus(ready: Boolean, lastText: String) =
        sendBroadcast(Intent(BROADCAST_TTS_STATUS).apply {
            `package` = packageName; putExtra(EXTRA_TTS_READY, ready); putExtra(EXTRA_TTS_LAST_TEXT, lastText)
        })

    private fun broadcastLangFallback(requested: String, actual: String) {
        Log.w(TAG, "Language fallback: $requested → $actual")
        sendBroadcast(Intent(BROADCAST_LANG_FALLBACK).apply {
            `package` = packageName
            putExtra(EXTRA_FALLBACK_REQUESTED, requested)
            putExtra(EXTRA_FALLBACK_ACTUAL, actual)
        })
    }

    private fun broadcastDiagnostics(
        sttLatency: Long = 0, ttsLatency: Long = 0, rtf: Float = 0f,
        payloadBytes: Int = 0, e2eLatencyMs: Long = 0,
    ) = sendBroadcast(Intent(BROADCAST_DIAGNOSTICS).apply {
        `package` = packageName
        putExtra("stt_latency_ms", sttLatency); putExtra("tts_latency_ms", ttsLatency)
        putExtra("rtf", rtf); putExtra("payload_bytes", payloadBytes); putExtra("e2e_latency_ms", e2eLatencyMs)
    })

    private fun broadcastPipelineState(state: String) {
        Log.v(TAG, "Pipeline state → $state")
        sendBroadcast(Intent(BROADCAST_PIPELINE_STATE).apply {
            `package` = packageName
            putExtra(EXTRA_PIPELINE_STATE, state)
        })
    }

    private fun broadcastSttLoadError(errorMsg: String) =
        sendBroadcast(Intent(BROADCAST_STT_LOAD_ERROR).apply {
            `package` = packageName
            putExtra(EXTRA_STT_LOAD_ERROR_MSG, errorMsg)
        })

    // ─── Notification ─────────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        val ch = NotificationChannel(CHANNEL_ID, "iTantra Transceiver", NotificationManager.IMPORTANCE_LOW)
            .apply { description = "iTantra voice transceiver session" }
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(ch)
    }

    private fun buildStartingNotification() =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("iTantra — Starting...")
            .setContentText("Initializing voice transceiver")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .setContentIntent(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
            .build()

    private fun buildNotification(role: DeviceRole, language: Language) =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("iTantra Active — ${role.name}")
            .setContentText("Language: ${language.displayName} | Link: Bluetooth")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .setContentIntent(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
            .build()

    // ─── Lifecycle ────────────────────────────────────────────────────────────

    private fun stopSession() {
        isPttActive.set(false)
        synchronized(pttBufferLock) { pttBuffer.clear() }
        broadcastConnectionState("disconnected")
        pipelineJob?.cancel()
        btTransceiver.close(); vadManager.close(); sttEngine.close(); ttsEngine.close()
        isPipelineActive = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        if (isPipelineActive) {
            btTransceiver.close(); vadManager.close(); sttEngine.close(); ttsEngine.close()
        }
        Log.d(TAG, "TransceiverService destroyed")
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
