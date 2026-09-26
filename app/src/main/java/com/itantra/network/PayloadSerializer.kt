package com.itantra.network

import com.itantra.proto.TransceiverMessage
import java.nio.ByteBuffer

/**
 * Serializes and deserializes [TransceiverMessage] Protobuf payloads.
 *
 * Wire format:
 *   [4 bytes big-endian length][N bytes Protobuf payload]
 *
 * This framing lets us reconstruct messages from a raw byte stream
 * (Bluetooth RFCOMM is stream-based, not message-based).
 */
object PayloadSerializer {

    private const val FRAME_HEADER_BYTES = 4

    /**
     * Pack a message into a length-prefixed byte frame for transmission.
     * Typical size: <50 bytes for a short phrase.
     */
    fun pack(
        text: String,
        langCode: String,
        isAlert: Boolean,
        sequence: Int = 0,
    ): ByteArray {
        val proto = TransceiverMessage.newBuilder()
            .setText(text)
            .setLangCode(langCode)
            .setIsAlert(isAlert)
            .setSenderTimestampMs(System.currentTimeMillis())
            .setSequence(sequence)
            .build()

        val payload = proto.toByteArray()
        val frame = ByteBuffer.allocate(FRAME_HEADER_BYTES + payload.size)
        frame.putInt(payload.size)
        frame.put(payload)
        return frame.array()
    }

    /**
     * Unpack a raw Protobuf byte array (without the length prefix) into a [TransceiverMessage].
     */
    fun unpack(bytes: ByteArray): TransceiverMessage =
        TransceiverMessage.parseFrom(bytes)

    /**
     * Extract the payload length from the 4-byte frame header.
     * Returns the number of bytes to read next.
     */
    fun readFrameLength(headerBytes: ByteArray): Int {
        require(headerBytes.size == FRAME_HEADER_BYTES) {
            "Header must be exactly $FRAME_HEADER_BYTES bytes"
        }
        return ByteBuffer.wrap(headerBytes).int
    }

    /** Report the serialized size in bytes for diagnostics display. */
    fun payloadSizeBytes(text: String, langCode: String, isAlert: Boolean): Int =
        TransceiverMessage.newBuilder()
            .setText(text)
            .setLangCode(langCode)
            .setIsAlert(isAlert)
            .build()
            .serializedSize
}
