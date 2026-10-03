package com.cross.app.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.util.Base64

/** JVM coverage for multi-frame UTF-8 JSON transport and out-of-order delivery. */
class FrameCodecTest {
    @Test
    fun roundTripLargePayload() {
        val message = ProtocolMessage(MessageType.NOTIFICATION, id = "frame-test", payloadJson = ProtocolMessage.payload("text" to "hello 🌏 ".repeat(120)))
        val frames = FrameCodec.encode(message, chunkSize = 17)
        val reassembler = FrameCodec.Reassembler()
        var result: ByteArray? = null
        frames.forEach { result = reassembler.accept(it) ?: result }
        assertNotNull(result)
        assertEquals(message.toJson(), result!!.toString(StandardCharsets.UTF_8))
    }

    @Test
    fun rejectsMalformedFrame() {
        assertEquals(null, FrameCodec.Reassembler().accept("bad frame".toByteArray()))
    }

    @Test
    fun rejectsCoercedFrameFieldTypes() {
        fun frame(frameId: Any, chunkIndex: Any, chunkCount: Any, payload: Any): ByteArray = JSONObject()
            .put("frame_id", frameId)
            .put("chunk_index", chunkIndex)
            .put("chunk_count", chunkCount)
            .put("payload", payload)
            .toString()
            .toByteArray(StandardCharsets.UTF_8)

        assertEquals(null, FrameCodec.Reassembler().accept(frame(7, 0, 1, "")))
        assertEquals(null, FrameCodec.Reassembler().accept(frame("frame-1", 0, 1, 7)))
        assertEquals(null, FrameCodec.Reassembler().accept(frame("frame-1", "0", 1, "")))
        assertEquals(null, FrameCodec.Reassembler().accept(frame("frame-1", 0, "1", "")))
        assertEquals(null, FrameCodec.Reassembler().accept(frame("frame-1", false, 1, "")))
        assertEquals(null, FrameCodec.Reassembler().accept(frame("frame-1", 0, true, "")))
    }

    @Test
    fun rejectsUnreasonableChunkCountAndPayloadSize() {
        val tooManyChunks = JSONObject()
            .put("frame_id", "malformed")
            .put("chunk_index", 0)
            .put("chunk_count", 10_000)
            .put("payload", "")
            .toString()
            .toByteArray(StandardCharsets.UTF_8)
        val oversizedPayload = JSONObject()
            .put("frame_id", "malformed")
            .put("chunk_index", 0)
            .put("chunk_count", 1)
            .put("payload", Base64.getEncoder().encodeToString(ByteArray(49)))
            .toString()
            .toByteArray(StandardCharsets.UTF_8)

        val reassembler = FrameCodec.Reassembler()
        assertEquals(null, reassembler.accept(tooManyChunks))
        assertEquals(null, reassembler.accept(oversizedPayload))
    }

    @Test
    fun expiresIncompleteFrameBeforeAcceptingNewData() {
        var now = 0L
        val message = ProtocolMessage(MessageType.NOTIFICATION, id = "expiring", payloadJson = ProtocolMessage.payload("text" to "hello"))
        val frames = FrameCodec.encode(message, chunkSize = 17)
        val reassembler = FrameCodec.Reassembler(nowMs = { now }, timeoutMs = 1_000)

        assertTrue(frames.size > 1)
        assertEquals(null, reassembler.accept(frames.first()))
        now = 900
        assertEquals(null, reassembler.accept(frames.first()))
        now = 1_001
        assertEquals(null, reassembler.accept(frames[1]))
        var result: ByteArray? = null
        frames.drop(2).forEach { result = reassembler.accept(it) ?: result }
        assertEquals(null, result)
        result = reassembler.accept(frames.first()) ?: result
        assertEquals(message.toJson(), result!!.toString(StandardCharsets.UTF_8))
    }

    @Test
    fun continuousUniqueChunkProgressCompletesBeyondTheTotalTimeout() {
        var now = 0L
        val message = ProtocolMessage(
            MessageType.NOTIFICATION,
            id = "progressing",
            payloadJson = ProtocolMessage.payload("text" to "continuous progress ".repeat(20)),
        )
        val frames = FrameCodec.encode(message, chunkSize = 17)
        val reassembler = FrameCodec.Reassembler(nowMs = { now }, timeoutMs = 1_000)
        var result: ByteArray? = null

        frames.forEachIndexed { index, frame ->
            if (index > 0) now += 900
            result = reassembler.accept(frame) ?: result
        }

        assertTrue(now > 1_000)
        assertEquals(message.toJson(), result!!.toString(StandardCharsets.UTF_8))
    }

    @Test
    fun resetDropsIncompleteFrame() {
        val message = ProtocolMessage(MessageType.NOTIFICATION, id = "reset", payloadJson = ProtocolMessage.payload("text" to "hello"))
        val frames = FrameCodec.encode(message, chunkSize = 17)
        val reassembler = FrameCodec.Reassembler()

        assertEquals(null, reassembler.accept(frames.first()))
        reassembler.reset()
        var result: ByteArray? = null
        frames.forEach { result = reassembler.accept(it) ?: result }
        assertEquals(message.toJson(), result!!.toString(StandardCharsets.UTF_8))
    }

    @Test
    fun defaultFramesStayWithinWatchRawSliceLimit() {
        val message = ProtocolMessage(
            MessageType.NOTIFICATION,
            id = "default-size",
            payloadJson = ProtocolMessage.payload("text" to "hello ".repeat(100)),
        )
        val frames = FrameCodec.encode(message)

        assertTrue(frames.isNotEmpty())
        assertTrue(frames.all { frame ->
            Base64.getDecoder().decode(JSONObject(String(frame, StandardCharsets.UTF_8)).getString("payload")).size <= 48
        })
    }
}
