package com.itantra.network

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

/**
 * Manages a Bluetooth Classic (RFCOMM) connection between two iTantra devices.
 *
 * Frame format: [4 bytes big-endian payload length] [N bytes Protobuf payload]
 *
 * FIX A: Reflection-based RFCOMM fallback in connect() bypasses SDP lookup —
 *        the primary reason connections failed on most Android OEMs.
 * FIX B: listenFlow fires onConnected immediately after accept() returns, so the
 *        Receiver UI shows "Connected" without waiting for the first data packet.
 */
class BluetoothTransceiver(private val adapter: BluetoothAdapter) {

    companion object {
        private const val TAG = "BluetoothTransceiver"
        private const val SERVICE_NAME = "iTantra"
        val SPP_UUID: UUID = UUID.fromString("fa87c0d0-afac-11de-8a39-0800200c9a66")
        private const val HEADER_BYTES = 4
        private const val MAX_PAYLOAD_BYTES = 65_535
        private const val CONNECT_RETRY_COUNT = 5
        private const val CONNECT_RETRY_DELAY_MS = 2_000L
        // Direct RFCOMM channel used by the reflection fallback (bypasses SDP)
        private const val RFCOMM_FALLBACK_CHANNEL = 1
    }

    private var serverSocket: BluetoothServerSocket? = null
    private var activeSocket: BluetoothSocket? = null

    @Volatile private var outputStream: OutputStream? = null

    /** Display name of the currently connected remote device, or null. */
    @Volatile
    var connectedDeviceName: String? = null
        private set

    // ─── Server mode ──────────────────────────────────────────────────────────

    /**
     * Listens for an incoming connection and emits received payload byte arrays.
     * Re-listens automatically after disconnect so Receiver is always ready.
     *
     * [onConnected] is invoked immediately after accept() returns — FIX B.
     */
    fun listenFlow(onConnected: ((deviceName: String) -> Unit)? = null): Flow<ByteArray> = flow {
        while (currentCoroutineContext().isActive) {
            Log.d(TAG, "Server: opening RFCOMM listener")
            val srv = try {
                adapter.listenUsingRfcommWithServiceRecord(SERVICE_NAME, SPP_UUID)
            } catch (e: IOException) {
                Log.e(TAG, "Server: failed to create server socket", e)
                delay(2_000); continue
            }
            serverSocket = srv
            try {
                Log.d(TAG, "Server: waiting for client...")
                val socket = srv.accept()   // blocking
                activeSocket = socket
                outputStream = socket.outputStream
                val name = remoteName(socket)
                connectedDeviceName = name
                Log.d(TAG, "Server: accepted from $name")
                srv.close()
                onConnected?.invoke(name)   // FIX B — immediate "connected" notification
                val input = socket.inputStream
                while (currentCoroutineContext().isActive) {
                    val payload = readFrame(input) ?: break
                    emit(payload)
                }
                Log.d(TAG, "Server: client disconnected — re-listening")
            } catch (e: IOException) {
                Log.e(TAG, "Server: error: ${e.message}")
            } finally {
                runCatching { serverSocket?.close() }
                runCatching { activeSocket?.close() }
                activeSocket = null; outputStream = null; serverSocket = null; connectedDeviceName = null
            }
            if (currentCoroutineContext().isActive) delay(1_000)
        }
    }.flowOn(Dispatchers.IO)

    // ─── Client mode ──────────────────────────────────────────────────────────

    /**
     * Connect to [device] as a client.
     *
     * FIX A: Two-strategy approach:
     *  1. SDP-based RFCOMM (standard but fails on many OEMs)
     *  2. Reflection RFCOMM on channel 1 (bypasses SDP, works reliably for paired devices)
     */
    suspend fun connect(device: BluetoothDevice): Boolean = withContext(Dispatchers.IO) {
        runCatching { adapter.cancelDiscovery() }   // discovery blocks RFCOMM
        repeat(CONNECT_RETRY_COUNT) { attempt ->
            Log.d(TAG, "Client: attempt ${attempt + 1}/$CONNECT_RETRY_COUNT -> ${device.name ?: device.address}")

            // Strategy 1: SDP
            val sdpSocket = runCatching { device.createRfcommSocketToServiceRecord(SPP_UUID) }.getOrNull()
            if (sdpSocket != null && tryConnect(sdpSocket, "SDP")) return@withContext true

            // Strategy 2: Reflection fallback (FIX A)
            val reflectSocket = runCatching {
                @Suppress("DiscouragedPrivateApi")
                val m = device.javaClass.getMethod("createRfcommSocket", Int::class.java)
                m.invoke(device, RFCOMM_FALLBACK_CHANNEL) as BluetoothSocket
            }.getOrNull()
            if (reflectSocket != null && tryConnect(reflectSocket, "Reflect-Ch$RFCOMM_FALLBACK_CHANNEL")) return@withContext true

            if (attempt < CONNECT_RETRY_COUNT - 1) delay(CONNECT_RETRY_DELAY_MS)
        }
        Log.e(TAG, "Client: all $CONNECT_RETRY_COUNT attempts failed")
        false
    }

    private fun tryConnect(socket: BluetoothSocket, strategy: String): Boolean {
        return try {
            socket.connect()
            activeSocket = socket
            outputStream = socket.outputStream
            connectedDeviceName = remoteName(socket)
            Log.d(TAG, "Client: connected via $strategy to $connectedDeviceName")
            true
        } catch (e: IOException) {
            Log.w(TAG, "Client: $strategy failed: ${e.message}")
            runCatching { socket.close() }
            false
        }
    }

    fun receiveFlow(): Flow<ByteArray> = flow {
        val socket = activeSocket ?: run { Log.w(TAG, "receiveFlow: no socket"); return@flow }
        val input = socket.inputStream
        try {
            while (currentCoroutineContext().isActive) { val p = readFrame(input) ?: break; emit(p) }
        } catch (e: IOException) { Log.e(TAG, "receiveFlow error: ${e.message}") }
    }.flowOn(Dispatchers.IO)

    suspend fun send(frame: ByteArray) = withContext(Dispatchers.IO) {
        val out = outputStream ?: run { Log.w(TAG, "send: not connected"); return@withContext }
        try { out.write(frame); out.flush(); Log.v(TAG, "Sent ${frame.size} bytes") }
        catch (e: IOException) { Log.e(TAG, "send failed: ${e.message}") }
    }

    private fun readFrame(input: InputStream): ByteArray? {
        return try {
            val hdr = ByteArray(HEADER_BYTES); var r = 0
            while (r < HEADER_BYTES) { val n = input.read(hdr, r, HEADER_BYTES - r); if (n < 0) return null; r += n }
            val len = PayloadSerializer.readFrameLength(hdr)
            if (len <= 0 || len > MAX_PAYLOAD_BYTES) { Log.w(TAG, "Bad length: $len"); return null }
            val buf = ByteArray(len); r = 0
            while (r < len) { val n = input.read(buf, r, len - r); if (n < 0) return null; r += n }
            buf
        } catch (e: IOException) { null }
    }

    private fun remoteName(s: BluetoothSocket) = try { s.remoteDevice.name ?: s.remoteDevice.address } catch (_: Exception) { "Unknown" }

    val isConnected: Boolean get() = activeSocket?.isConnected == true

    fun close() {
        runCatching { activeSocket?.close() }
        runCatching { serverSocket?.close() }
        activeSocket = null; outputStream = null; serverSocket = null; connectedDeviceName = null
        Log.d(TAG, "Bluetooth sockets closed")
    }
}
