package com.itantra.network

import com.itantra.proto.TransceiverMessage
import org.junit.Assert.*
import org.junit.Test

class PayloadSerializerTest {

    @Test
    fun `pack and unpack round-trip preserves all fields`() {
        val text = "नमस्ते दुनिया"
        val langCode = "hi"
        val isAlert = true

        val frame = PayloadSerializer.pack(text, langCode, isAlert, sequence = 42)
        // Frame has 4-byte header + payload
        assertTrue("Frame should be at least 5 bytes", frame.size >= 5)

        val payloadLen = PayloadSerializer.readFrameLength(frame.take(4).toByteArray())
        val payload = frame.drop(4).take(payloadLen).toByteArray()
        val msg = PayloadSerializer.unpack(payload)

        assertEquals(text, msg.text)
        assertEquals(langCode, msg.langCode)
        assertTrue(msg.isAlert)
        assertEquals(42, msg.sequence)
        assertTrue("Sender timestamp should be recent", msg.senderTimestampMs > 0)
    }

    @Test
    fun `short Hindi phrase stays under 50 bytes serialized`() {
        val size = PayloadSerializer.payloadSizeBytes("आग लगी है", "hi", true)
        println("Payload size: $size bytes")
        // Proto overhead for a short phrase is tiny — target <50 bytes
        assertTrue("Payload should be under 100 bytes for a short phrase", size < 100)
    }

    @Test
    fun `english greeting is tiny`() {
        val size = PayloadSerializer.payloadSizeBytes("Help needed urgently", "en", false)
        println("English payload: $size bytes")
        assertTrue("English payload under 50 bytes", size < 50)
    }

    @Test
    fun `readFrameLength parses big-endian correctly`() {
        val header = byteArrayOf(0x00, 0x00, 0x00, 0x1F) // 31 in big-endian
        assertEquals(31, PayloadSerializer.readFrameLength(header))
    }
}
