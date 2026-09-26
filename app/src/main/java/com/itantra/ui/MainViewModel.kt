package com.itantra.ui

import android.app.Application
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import com.itantra.model.AppMode
import com.itantra.model.DeviceRole
import com.itantra.model.Language
import com.itantra.service.TransceiverService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

// ─── UI State Models ──────────────────────────────────────────────────────────

data class TranscriptEntry(
    val text: String,
    val direction: String,       // "sent" | "received"
    val timestampMs: Long = System.currentTimeMillis(),
)

data class DiagnosticsState(
    val sttLatencyMs: Long = 0,
    val ttsLatencyMs: Long = 0,
    val rtf: Float = 0f,
    val payloadBytes: Int = 0,
    val e2eLatencyMs: Long = 0,
    val ramUsageMb: Int = 0,
)

data class SessionState(
    val isRunning: Boolean = false,
    val role: DeviceRole = DeviceRole.SENDER,
    val language: Language = Language.HINDI,
    val mode: AppMode = AppMode.PUSH_TO_TALK,
    val isAlertFlagSet: Boolean = false,
    val isPttHeld: Boolean = false,
    val connectionStatus: String = "Disconnected",
    val pairedDevice: BluetoothDevice? = null,
    val transcript: List<TranscriptEntry> = emptyList(),
    val diagnostics: DiagnosticsState = DiagnosticsState(),
    val showDiagnostics: Boolean = false,
    val pairedDevices: List<BluetoothDevice> = emptyList(),
    // Receiver-specific state
    val ttsReady: Boolean = false,
    val lastReceivedText: String = "",
    // Bluetooth hardware state
    val isBluetoothEnabled: Boolean = true,
    // Non-null when the selected language model is missing and Hindi is used instead.
    // e.g. "Gujarati model not available \u2014 using Hindi"
    val languageFallbackMessage: String? = null,
    // Live pipeline stage — driven by TransceiverService.BROADCAST_PIPELINE_STATE.
    // Values: "IDLE" | "LISTENING" | "PROCESSING_STT" | "SENDING" |
    //         "RECEIVING" | "PROCESSING_TTS" | "PLAYING"
    val pipelineState: String = "IDLE",
    // Non-null when the STT model failed to load (e.g. unsupported operators
    // after bad quantization). Shown as a persistent error banner in the UI.
    // Format matches SttEngine.loadError:
    //   "unsupported_operators: ..." | "oom: ..." | "unknown: ..."
    val sttLoadError: String? = null,
)

// ─── ViewModel ────────────────────────────────────────────────────────────────

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val _state = MutableStateFlow(SessionState())
    val state: StateFlow<SessionState> = _state.asStateFlow()

    private val transcriptReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val text = intent.getStringExtra(TransceiverService.EXTRA_TEXT) ?: return
            val dir = intent.getStringExtra(TransceiverService.EXTRA_DIRECTION) ?: "received"
            _state.update { s -> s.copy(transcript = (s.transcript + TranscriptEntry(text, dir)).takeLast(50)) }
        }
    }

    private val diagnosticsReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val rt = Runtime.getRuntime()
            val mb = ((rt.totalMemory() - rt.freeMemory()) / 1_048_576).toInt()
            _state.update { s ->
                s.copy(diagnostics = DiagnosticsState(
                    sttLatencyMs = intent.getLongExtra("stt_latency_ms", 0),
                    ttsLatencyMs = intent.getLongExtra("tts_latency_ms", 0),
                    rtf = intent.getFloatExtra("rtf", 0f),
                    payloadBytes = intent.getIntExtra("payload_bytes", 0),
                    e2eLatencyMs = intent.getLongExtra("e2e_latency_ms", 0),
                    ramUsageMb = mb,
                ))
            }
        }
    }

    private val connectionStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val raw = intent.getStringExtra(TransceiverService.EXTRA_CONNECTION_STATE) ?: return
            val status = when (raw) {
                "connecting"    -> "Connecting..."
                "connected"     -> "Connected"
                "failed"        -> "Connection Failed"
                "disconnected"  -> "Disconnected"
                else            -> raw
            }
            _state.update { it.copy(connectionStatus = status) }
        }
    }

    private val ttsStatusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val ready = intent.getBooleanExtra(TransceiverService.EXTRA_TTS_READY, false)
            val text = intent.getStringExtra(TransceiverService.EXTRA_TTS_LAST_TEXT) ?: ""
            _state.update { it.copy(ttsReady = ready, lastReceivedText = text) }
        }
    }

    private val langFallbackReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val requested = intent.getStringExtra(TransceiverService.EXTRA_FALLBACK_REQUESTED) ?: return
            val actual    = intent.getStringExtra(TransceiverService.EXTRA_FALLBACK_ACTUAL) ?: return
            // Map lang codes to display names for the user-facing message
            val reqDisplay = Language.entries.firstOrNull { it.code == requested }?.displayName ?: requested
            val actDisplay = Language.entries.firstOrNull { it.code == actual }?.displayName ?: actual
            _state.update { it.copy(languageFallbackMessage = "$reqDisplay model not available — using $actDisplay") }
        }
    }

    private val pipelineStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val state = intent.getStringExtra(TransceiverService.EXTRA_PIPELINE_STATE) ?: return
            _state.update { it.copy(pipelineState = state) }
        }
    }

    private val sttLoadErrorReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val err = intent.getStringExtra(TransceiverService.EXTRA_STT_LOAD_ERROR_MSG) ?: return
            _state.update { it.copy(sttLoadError = err) }
        }
    }

    init {
        val ctx = application
        registerReceiver(ctx, transcriptReceiver, TransceiverService.BROADCAST_TRANSCRIPT)
        registerReceiver(ctx, diagnosticsReceiver, TransceiverService.BROADCAST_DIAGNOSTICS)
        registerReceiver(ctx, connectionStateReceiver, TransceiverService.BROADCAST_CONNECTION_STATE)
        registerReceiver(ctx, ttsStatusReceiver, TransceiverService.BROADCAST_TTS_STATUS)
        registerReceiver(ctx, langFallbackReceiver, TransceiverService.BROADCAST_LANG_FALLBACK)
        registerReceiver(ctx, pipelineStateReceiver, TransceiverService.BROADCAST_PIPELINE_STATE)
        registerReceiver(ctx, sttLoadErrorReceiver, TransceiverService.BROADCAST_STT_LOAD_ERROR)
        loadPairedDevices()
        checkBluetoothState()
    }

    private fun registerReceiver(ctx: Context, receiver: BroadcastReceiver, action: String) {
        val filter = IntentFilter(action)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ctx.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            ctx.registerReceiver(receiver, filter)
        }
    }

    private fun loadPairedDevices() {
        val btManager = getApplication<Application>().getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        try {
            val bonded = btManager.adapter?.bondedDevices?.toList() ?: emptyList()
            _state.update { it.copy(pairedDevices = bonded) }
        } catch (_: SecurityException) {}
    }

    private fun checkBluetoothState() {
        val btManager = getApplication<Application>().getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val enabled = btManager.adapter?.isEnabled ?: false
        _state.update { it.copy(isBluetoothEnabled = enabled) }
    }

    fun onBluetoothStateChanged(enabled: Boolean) {
        _state.update { it.copy(isBluetoothEnabled = enabled) }
        if (enabled) loadPairedDevices()
    }

    fun onRoleChanged(role: DeviceRole) = _state.update { it.copy(role = role) }
    fun onLanguageChanged(lang: Language) = _state.update { it.copy(language = lang, languageFallbackMessage = null) }
    fun onModeChanged(mode: AppMode) = _state.update { it.copy(mode = mode) }
    fun onAlertToggled(value: Boolean) = _state.update { it.copy(isAlertFlagSet = value) }
    fun onShowDiagnosticsToggled() = _state.update { it.copy(showDiagnostics = !it.showDiagnostics) }

    fun onDeviceSelected(device: BluetoothDevice) {
        _state.update { it.copy(pairedDevice = device, connectionStatus = "Device selected") }
    }

    fun startSession() {
        val s = _state.value
        val ctx = getApplication<Application>()
        if (s.role == DeviceRole.SENDER && s.pairedDevice == null) {
            _state.update { it.copy(connectionStatus = "Select a device first") }
            return
        }
        val intent = Intent(ctx, TransceiverService::class.java).apply {
            action = TransceiverService.ACTION_START
            putExtra(TransceiverService.EXTRA_ROLE, s.role)
            putExtra(TransceiverService.EXTRA_LANGUAGE, s.language)
            putExtra(TransceiverService.EXTRA_MODE, s.mode)
            s.pairedDevice?.address?.let { addr -> putExtra(TransceiverService.EXTRA_REMOTE_DEVICE_ADDRESS, addr) }
        }
        ctx.startForegroundService(intent)
        _state.update { it.copy(isRunning = true, connectionStatus = "Starting...") }
    }

    fun stopSession() {
        val ctx = getApplication<Application>()
        ctx.startService(Intent(ctx, TransceiverService::class.java).apply { action = TransceiverService.ACTION_STOP })
        _state.update { it.copy(isRunning = false, connectionStatus = "Disconnected", ttsReady = false, lastReceivedText = "") }
    }

    fun onPttDown() {
        val ctx = getApplication<Application>()
        _state.update { it.copy(isPttHeld = true) }
        ctx.startService(Intent(ctx, TransceiverService::class.java).apply {
            action = TransceiverService.ACTION_PTT_DOWN
            putExtra(TransceiverService.EXTRA_IS_ALERT, _state.value.isAlertFlagSet)
        })
    }

    fun onPttUp() {
        val ctx = getApplication<Application>()
        _state.update { it.copy(isPttHeld = false) }
        ctx.startService(Intent(ctx, TransceiverService::class.java).apply { action = TransceiverService.ACTION_PTT_UP })
    }

    fun replayVoice() {
        val ctx = getApplication<Application>()
        ctx.startService(Intent(ctx, TransceiverService::class.java).apply { action = TransceiverService.ACTION_REPLAY })
    }

    override fun onCleared() {
        super.onCleared()
        val ctx = getApplication<Application>()
        for (r in listOf(transcriptReceiver, diagnosticsReceiver, connectionStateReceiver,
                ttsStatusReceiver, langFallbackReceiver, pipelineStateReceiver, sttLoadErrorReceiver)) {
            try { ctx.unregisterReceiver(r) } catch (_: Exception) {}
        }
    }
}
