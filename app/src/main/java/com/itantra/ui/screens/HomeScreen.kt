package com.itantra.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.itantra.model.AppMode
import com.itantra.model.DeviceRole
import com.itantra.model.Language
import com.itantra.tts.TtsEngine
import com.itantra.stt.SttEngine
import com.itantra.ui.MainViewModel
import com.itantra.ui.SessionState
import com.itantra.ui.TranscriptEntry
import com.itantra.ui.theme.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun HomeScreen(viewModel: MainViewModel) {
    val state by viewModel.state.collectAsState()
    val context = androidx.compose.ui.platform.LocalContext.current

    // Compute which languages have model files available (checked once per composition).
    // TtsEngine and SttEngine expose isLanguageAvailable() as pure asset-existence checks.
    val ttsEngine = remember { com.itantra.tts.TtsEngine(context) }
    val sttEngine = remember { com.itantra.stt.SttEngine(context) }
    val availableTts = remember { Language.entries.filter { ttsEngine.isLanguageAvailable(it) }.toSet() }
    val availableStt = remember { Language.entries.filter { sttEngine.isLanguageAvailable(it) }.toSet() }
    // For sender we need STT; for receiver we need TTS.
    val availableLangs = if (state.role == DeviceRole.SENDER) availableStt else availableTts

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(SpaceNavy, CosmoBlue, SpaceBlue)))
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
        ) {
            iTantraTopBar(state, onDiagnosticsClick = viewModel::onShowDiagnosticsToggled)

            if (state.role == DeviceRole.RECEIVER) {
                ReceiverLayout(state, viewModel, availableLangs)
            } else {
                SenderLayout(state, viewModel, availableLangs)
            }
        }

        AnimatedVisibility(
            visible = state.showDiagnostics,
            enter = expandVertically(expandFrom = Alignment.Bottom) + fadeIn(),
            exit = shrinkVertically(shrinkTowards = Alignment.Bottom) + fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) { DiagnosticsPanel(state) }

        // STT model load error banner — shown persistently when the STT engine
        // failed to load (e.g. bad quantization, ConvInteger unsupported op).
        // Positioned at the top so it's impossible to miss.
        state.sttLoadError?.let { errMsg ->
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.TopCenter)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(10.dp),
                color = androidx.compose.ui.graphics.Color(0xFFB71C1C).copy(alpha = 0.95f),
                tonalElevation = 8.dp,
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = androidx.compose.material.icons.Icons.Default.Warning,
                        contentDescription = null,
                        tint = androidx.compose.ui.graphics.Color.White,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text(
                            text = "STT model failed to load",
                            style = MaterialTheme.typography.labelLarge,
                            color = androidx.compose.ui.graphics.Color.White,
                            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                        )
                        Text(
                            text = errMsg,
                            style = MaterialTheme.typography.bodySmall,
                            color = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.85f),
                            maxLines = 3,
                        )
                    }
                }
            }
        }
    }
}

// ─── Top Bar ──────────────────────────────────────────────────────────────────

@Composable
private fun iTantraTopBar(state: SessionState, onDiagnosticsClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text("iTantra", style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold, color = IsroAmber))
            Text("Multilingual Voice Transceiver", style = MaterialTheme.typography.labelSmall.copy(color = OnSurfaceDim))
        }
        // Receiver shows "Listening" chip; Sender shows connection status chip
        if (state.role == DeviceRole.RECEIVER && state.isRunning) {
            ListeningChip()
        } else {
            StatusChip(state.connectionStatus, state.isRunning)
        }
        Spacer(Modifier.width(8.dp))
        IconButton(onClick = onDiagnosticsClick) {
            Icon(Icons.Default.Analytics, contentDescription = "Diagnostics",
                tint = if (state.showDiagnostics) IsroAmber else OnSurfaceDim)
        }
    }
}

@Composable
private fun ListeningChip() {
    val pulse = rememberInfiniteTransition(label = "listeningPulse")
    val alpha by pulse.animateFloat(
        initialValue = 1f, targetValue = 0.4f, label = "la",
        animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Reverse)
    )
    Surface(shape = RoundedCornerShape(50), color = IsroAmber.copy(alpha = 0.15f),
        border = BorderStroke(1.dp, IsroAmber)) {
        Row(modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Box(modifier = Modifier.size(6.dp).clip(CircleShape).background(IsroAmber.copy(alpha = alpha)))
            Spacer(Modifier.width(6.dp))
            Text("Listening", style = MaterialTheme.typography.labelSmall, color = IsroAmber)
        }
    }
}

@Composable
private fun StatusChip(status: String, isActive: Boolean) {
    val pulse = rememberInfiniteTransition(label = "pulse")
    val alpha by pulse.animateFloat(
        initialValue = 1f, targetValue = 0.3f, label = "a",
        animationSpec = infiniteRepeatable(tween(800, easing = LinearEasing), RepeatMode.Reverse)
    )
    val isConnected = status == "Connected"
    Surface(shape = RoundedCornerShape(50),
        color = if (isConnected) Color(0xFF064E3B) else SurfaceMid,
        border = BorderStroke(1.dp, if (isConnected) SignalGreen else SurfaceLight)) {
        Row(modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Box(modifier = Modifier.size(6.dp).clip(CircleShape)
                .background(if (isConnected) SignalGreen.copy(alpha = alpha) else OnSurfaceDim))
            Spacer(Modifier.width(6.dp))
            Text(status, style = MaterialTheme.typography.labelSmall,
                color = if (isConnected) SignalGreen else OnSurfaceDim)
        }
    }
}

// ─── SENDER LAYOUT ────────────────────────────────────────────────────────────

@Composable
private fun SenderLayout(state: SessionState, vm: MainViewModel, availableLangs: Set<Language>) {
    Column(modifier = Modifier.fillMaxSize()) {
        // Role & Mode toggles
        RoleAndModeRow(state, vm)
        // Language selector — dims chips with no STT model
        LanguageSelector(state.language, availableLangs) { vm.onLanguageChanged(it) }
        // Fallback warning (shown when Hindi is used instead of selected language)
        LangFallbackBanner(state.languageFallbackMessage)
        Spacer(Modifier.height(8.dp))
        // Connection panel
        SenderConnectionPanel(state, vm)
        Spacer(Modifier.height(10.dp))
        // Transcript / Ready area
        SenderTranscriptArea(
            entries = state.transcript,
            isConnected = state.connectionStatus == "Connected",
            isRunning = state.isRunning,
            modifier = Modifier.weight(1f).padding(horizontal = 16.dp)
        )
        Spacer(Modifier.height(8.dp))
        // VAD gate label (visible in Phone mode)
        if (state.mode == AppMode.PHONE_MODE && state.isRunning) {
            VadGateLabel()
        }
        // Alert + PTT
        SenderBottomControls(state, vm)
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun RoleAndModeRow(state: SessionState, vm: MainViewModel) {
    SegmentedCard(
        label = "ROLE",
        options = listOf("Sender", "Receiver"),
        selected = if (state.role == DeviceRole.SENDER) 0 else 1,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
    ) { idx -> vm.onRoleChanged(if (idx == 0) DeviceRole.SENDER else DeviceRole.RECEIVER) }
    Spacer(Modifier.height(10.dp))
}

@Composable
private fun SegmentedCard(label: String, options: List<String>, selected: Int,
    modifier: Modifier = Modifier, onSelect: (Int) -> Unit) {
    Surface(modifier = modifier, shape = RoundedCornerShape(12.dp),
        color = SurfaceDark, border = BorderStroke(1.dp, SurfaceMid)) {
        Column(modifier = Modifier.padding(10.dp)) {
            Text(label, style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp), color = OnSurfaceDim)
            Spacer(Modifier.height(6.dp))
            Row(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(SurfaceMid)) {
                options.forEachIndexed { idx, opt ->
                    val active = idx == selected
                    Surface(modifier = Modifier.weight(1f), shape = RoundedCornerShape(8.dp),
                        color = if (active) IsroAmber else Color.Transparent, onClick = { onSelect(idx) }) {
                        Text(opt, modifier = Modifier.padding(vertical = 6.dp), textAlign = TextAlign.Center,
                            style = MaterialTheme.typography.labelMedium.copy(
                                color = if (active) SpaceNavy else OnSurfaceDim,
                                fontWeight = if (active) FontWeight.Bold else FontWeight.Normal))
                    }
                }
            }
        }
    }
}

@Composable
private fun LanguageSelector(selected: Language, availableLangs: Set<Language>, onSelect: (Language) -> Unit) {
    val scroll = rememberScrollState()
    Row(modifier = Modifier.fillMaxWidth().horizontalScroll(scroll).padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Language.entries.forEach { lang ->
            val isSel = lang == selected
            val isAvailable = lang in availableLangs
            FilterChip(selected = isSel, onClick = { onSelect(lang) },
                label = {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(lang.nativeName, style = MaterialTheme.typography.labelSmall)
                        Text(
                            if (isAvailable) lang.displayName else "${lang.displayName} ⚠",
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontSize = 9.sp,
                                color = if (isAvailable) OnSurfaceDim else AlertRed.copy(alpha = 0.7f)
                            )
                        )
                    }
                },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = if (isAvailable) IsroAmber else AlertRedDim,
                    selectedLabelColor = if (isAvailable) SpaceNavy else Color.White,
                    containerColor = SurfaceMid,
                    labelColor = if (isAvailable) OnSurface else OnSurface.copy(alpha = 0.5f)
                ),
                border = FilterChipDefaults.filterChipBorder(
                    enabled = true, selected = isSel,
                    selectedBorderColor = if (isAvailable) IsroGold else AlertRed,
                    borderColor = if (isAvailable) SurfaceLight else SurfaceLight.copy(alpha = 0.4f)
                )
            )
        }
    }
}

/**
 * Animated banner shown when a language model is missing and Hindi is used as fallback.
 * Auto-visible when [message] is non-null.
 */
@Composable
private fun LangFallbackBanner(message: String?) {
    AnimatedVisibility(
        visible = message != null,
        enter = expandVertically() + fadeIn(),
        exit = shrinkVertically() + fadeOut(),
    ) {
        if (message != null) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                shape = RoundedCornerShape(8.dp),
                color = AlertRed.copy(alpha = 0.12f),
                border = BorderStroke(1.dp, AlertRed.copy(alpha = 0.5f))
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.Warning,
                        contentDescription = null,
                        tint = AlertRed,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        message,
                        style = MaterialTheme.typography.labelSmall.copy(
                            color = AlertRed,
                            fontSize = 10.sp
                        )
                    )
                }
            }
        }
    }
}

@Composable
private fun SenderConnectionPanel(state: SessionState, vm: MainViewModel) {
    var expanded by remember { mutableStateOf(false) }
    Surface(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        shape = RoundedCornerShape(12.dp), color = SurfaceDark, border = BorderStroke(1.dp, SurfaceMid)) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Bluetooth, contentDescription = null, tint = IsroAmber, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Bluetooth Device", style = MaterialTheme.typography.titleSmall, color = OnSurface, modifier = Modifier.weight(1f))
                if (state.pairedDevice != null) {
                    Surface(shape = RoundedCornerShape(50), color = StellarBlue) {
                        Text("Paired", modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                            style = MaterialTheme.typography.labelSmall.copy(color = IsroAmber, fontWeight = FontWeight.Bold))
                    }
                    Spacer(Modifier.width(6.dp))
                }
                TextButton(onClick = { expanded = !expanded }) {
                    Text(if (state.pairedDevice != null) "Change" else "Select", color = IsroAmber)
                }
            }
            if (state.pairedDevice != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(modifier = Modifier.size(7.dp).clip(CircleShape)
                        .background(if (state.connectionStatus == "Connected") SignalGreen else OnSurfaceDim))
                    Spacer(Modifier.width(6.dp))
                    Text("Connected to: ${state.pairedDevice.name ?: state.pairedDevice.address} (Receiver)",
                        style = MaterialTheme.typography.bodySmall, color = SignalGreen)
                }
            } else {
                Text("No device selected", style = MaterialTheme.typography.bodySmall, color = OnSurfaceDim)
            }
            if (expanded) {
                Spacer(Modifier.height(8.dp)); HorizontalDivider(color = SurfaceMid); Spacer(Modifier.height(8.dp))
                if (state.pairedDevices.isEmpty()) {
                    Text("No paired devices found. Pair via Settings > Bluetooth first.",
                        style = MaterialTheme.typography.bodySmall, color = OnSurfaceDim)
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 200.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        items(state.pairedDevices) { device ->
                            TextButton(
                                onClick = { vm.onDeviceSelected(device); expanded = false },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Default.PhoneAndroid, contentDescription = null, tint = IsroAmber, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(device.name ?: device.address, color = OnSurface, modifier = Modifier.weight(1f))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SenderTranscriptArea(entries: List<TranscriptEntry>, isConnected: Boolean,
    isRunning: Boolean, modifier: Modifier = Modifier) {
    val listState = rememberLazyListState()
    val fmt = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
    LaunchedEffect(entries.size) { if (entries.isNotEmpty()) listState.animateScrollToItem(entries.lastIndex) }
    Surface(modifier = modifier, shape = RoundedCornerShape(12.dp),
        color = SurfaceDark, border = BorderStroke(1.dp, SurfaceMid)) {
        if (entries.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.Mic, contentDescription = null, tint = SurfaceLight.copy(alpha = 0.5f),
                        modifier = Modifier.size(44.dp))
                    Spacer(Modifier.height(10.dp))
                    Text("Ready to Transmit", style = MaterialTheme.typography.titleSmall.copy(color = OnSurface, fontWeight = FontWeight.SemiBold))
                    Spacer(Modifier.height(4.dp))
                    Text(
                        if (!isRunning) "Start session to begin"
                        else if (!isConnected) "Connecting to receiver..."
                        else "Hold PTT to speak in selected language",
                        style = MaterialTheme.typography.bodySmall, color = OnSurfaceDim, textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 24.dp))
                }
            }
        } else {
            LazyColumn(state = listState, modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(entries) { entry -> TranscriptBubble(entry, fmt) }
            }
        }
    }
}

@Composable
private fun VadGateLabel() {
    Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically) {
        val pulse = rememberInfiniteTransition(label = "vad")
        val alpha by pulse.animateFloat(initialValue = 0.5f, targetValue = 1f, label = "va",
            animationSpec = infiniteRepeatable(tween(600), RepeatMode.Reverse))
        Box(modifier = Modifier.size(6.dp).clip(CircleShape).background(SignalGreen.copy(alpha = alpha)))
        Spacer(Modifier.width(8.dp))
        Text("Silero VAD Speech Gate", style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp), color = OnSurfaceDim)
    }
}

@Composable
private fun SenderBottomControls(state: SessionState, vm: MainViewModel) {
    Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        // Alert toggle
        Row(modifier = Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Warning, contentDescription = null, tint = AlertRed, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text("Mark as Alert (DND Bypass)", style = MaterialTheme.typography.labelMedium, color = OnSurface, modifier = Modifier.weight(1f))
            Switch(checked = state.isAlertFlagSet, onCheckedChange = vm::onAlertToggled,
                colors = SwitchDefaults.colors(checkedThumbColor = AlertRed, checkedTrackColor = AlertRedDim,
                    uncheckedThumbColor = OnSurfaceDim, uncheckedTrackColor = SurfaceMid))
        }
        Spacer(Modifier.height(10.dp))
        if (state.mode == AppMode.PUSH_TO_TALK) {
            PttButton(state, vm)
        } else {
            StartStopButton(state, vm)
        }
        Spacer(Modifier.height(8.dp))
        // Session Active bottom bar
        BottomStatusBar(if (state.isRunning) "Session Active" else "Session Inactive", state.isRunning)
    }
}

@Composable
private fun PttButton(state: SessionState, vm: MainViewModel) {
    val pttColor by animateColorAsState(
        if (state.isPttHeld) AlertRed else IsroAmber, animationSpec = tween(150), label = "pttColor")
    val pulse = rememberInfiniteTransition(label = "pttPulse")
    val pttScale by pulse.animateFloat(initialValue = 1f, targetValue = 1.09f, label = "pttScale",
        animationSpec = infiniteRepeatable(tween(600), RepeatMode.Reverse))

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        val label = when {
            !state.isRunning -> "Start session first"
            state.connectionStatus != "Connected" -> state.connectionStatus
            state.isPttHeld -> "Listening..."
            else -> "Hold to Talk"
        }
        Text(label, style = MaterialTheme.typography.labelMedium,
            color = if (state.isPttHeld) AlertRed else if (state.connectionStatus == "Connected") OnSurface else OnSurfaceDim)
        Spacer(Modifier.height(8.dp))
        Box(modifier = Modifier
            .size(100.dp)
            .scale(if (state.isPttHeld) pttScale else 1f)
            .clip(CircleShape)
            .background(Brush.radialGradient(listOf(pttColor, StellarBlue)))
            .border(2.dp, pttColor, CircleShape)
            .pointerInput(Unit) {
                detectTapGestures(onPress = {
                    val canTalk = state.isRunning && state.connectionStatus == "Connected"
                    if (canTalk) {
                        vm.onPttDown()
                        try { tryAwaitRelease() } finally { vm.onPttUp() }
                    }
                })
            },
            contentAlignment = Alignment.Center) {
            Icon(Icons.Default.Mic, contentDescription = "Push to Talk",
                tint = if (state.isPttHeld) Color.White else SpaceNavy, modifier = Modifier.size(40.dp))
        }
        Spacer(Modifier.height(8.dp))
        Button(onClick = { if (state.isRunning) vm.stopSession() else vm.startSession() },
            colors = ButtonDefaults.buttonColors(containerColor = if (state.isRunning) AlertRedDim else StellarBlue),
            shape = RoundedCornerShape(10.dp)) {
            Icon(if (state.isRunning) Icons.Default.Stop else Icons.Default.PlayArrow,
                contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(if (state.isRunning) "End Session" else "Start Session")
        }
    }
}

@Composable
private fun StartStopButton(state: SessionState, vm: MainViewModel) {
    Button(onClick = { if (state.isRunning) vm.stopSession() else vm.startSession() },
        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp).height(52.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (state.isRunning) AlertRedDim else IsroAmber,
            contentColor = if (state.isRunning) Color.White else SpaceNavy),
        shape = RoundedCornerShape(14.dp)) {
        Icon(if (state.isRunning) Icons.Default.Stop else Icons.Default.PlayArrow, contentDescription = null)
        Spacer(Modifier.width(8.dp))
        Text(if (state.isRunning) "End Session" else "Start Session", style = MaterialTheme.typography.titleSmall)
    }
}

// ─── RECEIVER LAYOUT ──────────────────────────────────────────────────────────

@Composable
private fun ReceiverLayout(state: SessionState, vm: MainViewModel, availableLangs: Set<Language>) {
    Column(modifier = Modifier.fillMaxSize()) {
        // "CURRENT ASSIGNED ROLE" section label
        Text("CURRENT ASSIGNED ROLE",
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp, letterSpacing = 1.5.sp),
            color = OnSurfaceDim, modifier = Modifier.padding(start = 16.dp, bottom = 6.dp))

        // Role selector (Receiver mode highlighted, mode toggle hidden for receiver)
        ReceiverRoleRow(state, vm)
        Spacer(Modifier.height(10.dp))

        // TTS Status card
        TtsStatusCard(state)
        // Fallback warning
        LangFallbackBanner(state.languageFallbackMessage)
        Spacer(Modifier.height(10.dp))

        // Incoming messages transcript
        ReceiverTranscriptArea(
            entries = state.transcript,
            modifier = Modifier.weight(1f).padding(horizontal = 16.dp)
        )
        Spacer(Modifier.height(8.dp))

        // AudioTrack info bar
        AudioTrackBar(state)
        Spacer(Modifier.height(6.dp))

        // Speaker stream + Replay button
        SpeakerStreamRow(state, vm)
        Spacer(Modifier.height(10.dp))

        // Session start/stop
        ReceiverBottomControls(state, vm)
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun ReceiverRoleRow(state: SessionState, vm: MainViewModel) {
    Surface(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        shape = RoundedCornerShape(12.dp), color = SurfaceDark, border = BorderStroke(1.dp, SurfaceMid)) {
        Row(modifier = Modifier.padding(10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            // Sender tab (unselected)
            Surface(modifier = Modifier.weight(1f), shape = RoundedCornerShape(8.dp),
                color = Color.Transparent, onClick = { vm.onRoleChanged(DeviceRole.SENDER) }) {
                Text("Sender", modifier = Modifier.padding(vertical = 8.dp), textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.labelMedium.copy(color = OnSurfaceDim))
            }
            // Receiver tab (selected)
            Surface(modifier = Modifier.weight(1.4f), shape = RoundedCornerShape(8.dp),
                color = IsroAmber, onClick = {}) {
                Text("Receiver Mode", modifier = Modifier.padding(vertical = 8.dp), textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.labelMedium.copy(color = SpaceNavy, fontWeight = FontWeight.Bold))
            }
        }
    }
}

@Composable
private fun TtsStatusCard(state: SessionState) {
    Surface(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        shape = RoundedCornerShape(12.dp), color = SurfaceDark, border = BorderStroke(1.dp, SurfaceMid)) {
        Row(modifier = Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(shape = RoundedCornerShape(10.dp), color = StellarBlue) {
                Icon(Icons.Default.VolumeUp, contentDescription = null, tint = IsroAmber,
                    modifier = Modifier.padding(10.dp).size(22.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    if (state.ttsReady) "Indic-VITS Synthesizer Ready" else "Indic-VITS Synthesizer Loading...",
                    style = MaterialTheme.typography.titleSmall.copy(color = OnSurface, fontWeight = FontWeight.SemiBold))
                Spacer(Modifier.height(2.dp))
                Text(
                    if (state.lastReceivedText.isNotEmpty()) "Last: \"${state.lastReceivedText.take(40)}${if (state.lastReceivedText.length > 40) "..." else ""}\""
                    else "Awaiting incoming text packet...",
                    style = MaterialTheme.typography.bodySmall.copy(color = OnSurfaceDim))
            }
        }
    }
}

@Composable
private fun ReceiverTranscriptArea(entries: List<TranscriptEntry>, modifier: Modifier = Modifier) {
    val listState = rememberLazyListState()
    val fmt = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
    LaunchedEffect(entries.size) { if (entries.isNotEmpty()) listState.animateScrollToItem(entries.lastIndex) }
    Surface(modifier = modifier, shape = RoundedCornerShape(12.dp),
        color = SurfaceDark, border = BorderStroke(1.dp, SurfaceMid)) {
        if (entries.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.Chat, contentDescription = null, tint = SurfaceLight.copy(alpha = 0.4f),
                        modifier = Modifier.size(40.dp))
                    Spacer(Modifier.height(10.dp))
                    Text("No Incoming Messages", style = MaterialTheme.typography.titleSmall.copy(color = OnSurface))
                    Spacer(Modifier.height(4.dp))
                    Text("Messages received over RFCOMM will\nsynthesize and play aloud here",
                        style = MaterialTheme.typography.bodySmall, color = OnSurfaceDim,
                        textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 24.dp))
                }
            }
        } else {
            LazyColumn(state = listState, modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(entries) { entry -> TranscriptBubble(entry, fmt) }
            }
        }
    }
}

@Composable
private fun AudioTrackBar(state: SessionState) {
    val isActive = state.isRunning && state.ttsReady
    val pulse = rememberInfiniteTransition(label = "wave")
    val bars = (1..8).map { i ->
        pulse.animateFloat(initialValue = 0.2f, targetValue = if (isActive) 1f else 0.2f, label = "b$i",
            animationSpec = infiniteRepeatable(tween((300 + i * 60), easing = FastOutSlowInEasing), RepeatMode.Reverse,
                initialStartOffset = StartOffset(i * 60)))
    }
    Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text("AudioTrack (16kHz PCM / mono)",
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp), color = OnSurfaceDim, modifier = Modifier.weight(1f))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            bars.forEach { bar ->
                val h by bar
                Box(modifier = Modifier.width(3.dp).height((6 + (h * 16)).dp).clip(RoundedCornerShape(2.dp))
                    .background(if (isActive) SignalGreen else SurfaceLight))
            }
        }
    }
}

@Composable
private fun SpeakerStreamRow(state: SessionState, vm: MainViewModel) {
    Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text("Speaker Stream:", style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp), color = OnSurfaceDim)
        Spacer(Modifier.width(8.dp))
        Text("STREAM_MUSIC (Normal)", style = MaterialTheme.typography.labelSmall.copy(color = SignalGreen, fontWeight = FontWeight.Medium),
            modifier = Modifier.weight(1f))
        // Replay Voice button
        if (state.lastReceivedText.isNotEmpty()) {
            Surface(shape = RoundedCornerShape(8.dp), color = IsroAmber.copy(alpha = 0.15f),
                border = BorderStroke(1.dp, IsroAmber), onClick = { vm.replayVoice() }) {
                Row(modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Replay, contentDescription = "Replay Voice", tint = IsroAmber, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Replay\nVoice", style = MaterialTheme.typography.labelSmall.copy(color = IsroAmber, lineHeight = 13.sp))
                }
            }
        }
    }
}

@Composable
private fun ReceiverBottomControls(state: SessionState, vm: MainViewModel) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Button(onClick = { if (state.isRunning) vm.stopSession() else vm.startSession() },
            modifier = Modifier.fillMaxWidth().height(52.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (state.isRunning) AlertRedDim else StellarBlue,
                contentColor = Color.White),
            shape = RoundedCornerShape(14.dp)) {
            Icon(if (state.isRunning) Icons.Default.Stop else Icons.Default.PlayArrow, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(if (state.isRunning) "Stop Receiving" else "Start Receiving", style = MaterialTheme.typography.titleSmall)
        }
        Spacer(Modifier.height(8.dp))
        BottomStatusBar("Transceiver Service Active", state.isRunning)
    }
}

// ─── Shared components ────────────────────────────────────────────────────────

@Composable
private fun TranscriptBubble(entry: TranscriptEntry, fmt: SimpleDateFormat) {
    val isSent = entry.direction == "sent"
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = if (isSent) Arrangement.End else Arrangement.Start) {
        Surface(shape = RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp,
            bottomStart = if (isSent) 12.dp else 4.dp, bottomEnd = if (isSent) 4.dp else 12.dp),
            color = if (isSent) StellarBlue else SurfaceMid,
            border = BorderStroke(1.dp, if (isSent) IsroAmber.copy(alpha = 0.3f) else SurfaceLight),
            modifier = Modifier.widthIn(max = 280.dp)) {
            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                Text(text = if (isSent) "Sent" else "Received",
                    style = MaterialTheme.typography.labelSmall, color = if (isSent) IsroAmber else SignalGreen)
                Spacer(Modifier.height(2.dp))
                Text(entry.text, style = MaterialTheme.typography.bodyMedium, color = OnSurface)
                Spacer(Modifier.height(2.dp))
                Text(fmt.format(Date(entry.timestampMs)), style = MaterialTheme.typography.labelSmall,
                    color = OnSurfaceDim, modifier = Modifier.align(Alignment.End))
            }
        }
    }
}

@Composable
private fun BottomStatusBar(label: String, isActive: Boolean) {
    val pulse = rememberInfiniteTransition(label = "bar")
    val alpha by pulse.animateFloat(initialValue = 1f, targetValue = 0.4f, label = "ba",
        animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Reverse))
    Surface(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(10.dp),
        color = SurfaceDark, border = BorderStroke(1.dp, SurfaceMid)) {
        Row(modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
            Box(modifier = Modifier.size(8.dp).clip(CircleShape)
                .background(if (isActive) SignalGreen.copy(alpha = alpha) else OnSurfaceDim))
            Spacer(Modifier.width(8.dp))
            Text(label, style = MaterialTheme.typography.labelMedium.copy(
                color = if (isActive) SignalGreen else OnSurfaceDim, fontWeight = FontWeight.Medium))
        }
    }
}

// ─── Diagnostics Panel ────────────────────────────────────────────────────────

@Composable
private fun DiagnosticsPanel(state: SessionState) {
    val d = state.diagnostics
    Surface(modifier = Modifier.fillMaxWidth(), color = SurfaceDark.copy(alpha = 0.97f),
        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp), border = BorderStroke(1.dp, SurfaceMid)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Live Diagnostics", style = MaterialTheme.typography.titleSmall.copy(color = IsroAmber))
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                DiagStat("RAM", "${d.ramUsageMb} MB", Modifier.weight(1f))
                DiagStat("STT Latency", "${d.sttLatencyMs} ms", Modifier.weight(1f))
                DiagStat("TTS Latency", "${d.ttsLatencyMs} ms", Modifier.weight(1f))
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                DiagStat("RTF", "%.3f".format(d.rtf), Modifier.weight(1f))
                DiagStat("Payload", "${d.payloadBytes} B", Modifier.weight(1f))
                DiagStat("E2E Latency", "${d.e2eLatencyMs} ms", Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun DiagStat(label: String, value: String, modifier: Modifier = Modifier) {
    Surface(modifier = modifier, shape = RoundedCornerShape(8.dp), color = SurfaceMid) {
        Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally) {
            Text(value, style = MaterialTheme.typography.titleSmall.copy(color = IsroAmber, fontWeight = FontWeight.Bold))
            Text(label, style = MaterialTheme.typography.labelSmall.copy(color = OnSurfaceDim))
        }
    }
}
